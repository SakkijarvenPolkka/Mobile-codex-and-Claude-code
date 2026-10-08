/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectsTest.cpp

  Host tests of the bridge "effects" module (effects.*, analyze.*) through
  Bridge.h only: the catalogue and menus, describe/setParams/presets, every
  built-in process effect and generator applied once, Nyquist effects,
  Noise Reduction's two steps, the EQ curve, preview on the simulated
  "Null" device, spectrum/contrast, cancel with rollback.

  The start configuration points nyquistDir/pluginsDir at the vendored
  runtime (native/audacity/nyquist, native/audacity/plug-ins).

**********************************************************************/
#include "BridgeTestSupport.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <map>
#include <set>
#include <thread>

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

std::shared_ptr<Sink> gSink;

json Res(const json &envelope)
{
   return envelope.contains("result") ? envelope["result"] : json::object();
}

//! Call and CHECK success; prints the error otherwise
json Must(int line, const std::string &command,
   const json &args = json::object())
{
   auto r = Call(command, args);
   if (!Ok(r)) {
      std::fprintf(stderr, "  [line %d] %s %s -> %s\n", line, command.c_str(),
         args.dump().substr(0, 200).c_str(), r.dump().substr(0, 400).c_str());
      ++Failures();
   }
   return Res(r);
}
#define MUST(...) Must(__LINE__, __VA_ARGS__)

void ExpectError(const json &r, const std::string &code, int line)
{
   if (ErrorCodeOf(r) != code) {
      std::fprintf(stderr, "  [line %d] expected %s, got %s\n", line,
         code.c_str(), r.dump().substr(0, 400).c_str());
      ++Failures();
   }
}
#define EXPECT_ERROR(r, code) ExpectError(r, code, __LINE__)

json Snapshot() { return Res(Call("project.snapshot")); }

//! j[key] as a string, "" when j is not an object (e.g. a null lastEffect)
std::string Str(const json &j, const char *key)
{
   if (!j.is_object() || !j.contains(key) || !j[key].is_string())
      return {};
   return j[key].get<std::string>();
}

json TrackOf(int64_t id)
{
   const auto snapshot = Snapshot();   // keep the temporary alive
   for (auto &t : snapshot["tracks"])
      if (t.value("id", int64_t(-99)) == id)
         return t;
   return nullptr;
}

std::vector<json> Tracks()
{
   auto s = Snapshot();
   return std::vector<json>(s["tracks"].begin(), s["tracks"].end());
}

void NewProject() { MUST("project.new"); }

int64_t Tone(double seconds = 1.0, double frequency = 440.0, int channels = 1,
   double amplitude = 0.5)
{
   auto r = MUST("debug.makeTestTrack", { { "seconds", seconds },
      { "frequency", frequency }, { "channels", channels },
      { "amplitude", amplitude } });
   return r.value("id", int64_t(-1));
}

void Select(double t0, double t1, std::vector<int64_t> ids = {})
{
   json args{ { "t0", t0 }, { "t1", t1 } };
   if (!ids.empty())
      args["trackIds"] = ids;
   MUST("select.set", args);
}

//! The samples of `channel` in [t0, t1] (all clips concatenated), via the
//! display module's waveSamples (API.md §7.4)
std::vector<float> Samples(int64_t id, double t0, double t1, int channel = 0)
{
   std::vector<float> out;
   const auto bytes = aubridge::WaveSamples(id, channel, t0, t1);
   if (bytes.size() < 4)
      return out;
   size_t pos = 0;
   auto rd = [&](void *dst, size_t n) {
      if (pos + n > bytes.size())
         return false;
      std::memcpy(dst, bytes.data() + pos, n);
      pos += n;
      return true;
   };
   int32_t runs = 0;
   rd(&runs, 4);
   for (int r = 0; r < runs; ++r) {
      int32_t clip = 0, n = 0;
      double first = 0, period = 0;
      if (!rd(&clip, 4) || !rd(&first, 8) || !rd(&period, 8) || !rd(&n, 4))
         break;
      const size_t start = out.size();
      out.resize(start + n);
      rd(out.data() + start, size_t(n) * 4);
      pos += size_t(n) * 4;   // envelope
   }
   return out;
}

double Peak(const std::vector<float> &v)
{
   double p = 0;
   for (auto x : v)
      p = std::max(p, double(std::fabs(x)));
   return p;
}

double Rms(const std::vector<float> &v)
{
   if (v.empty())
      return 0;
   double s = 0;
   for (auto x : v)
      s += double(x) * x;
   return std::sqrt(s / v.size());
}

double MaxDiff(const std::vector<float> &a, const std::vector<float> &b)
{
   double d = 0;
   const size_t n = std::min(a.size(), b.size());
   for (size_t i = 0; i < n; ++i)
      d = std::max(d, double(std::fabs(a[i] - b[i])));
   if (a.size() != b.size())
      d = std::max(d, 1.0);
   return d;
}

double End(int64_t id)
{
   auto t = TrackOf(id);
   return t.is_null() ? -1.0 : t.value("end", -1.0);
}

bool Near(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

// ---------------------------------------------------------------------------
std::map<std::string, std::string> gIdBySymbol;   // built-in symbol -> id
std::map<std::string, std::string> gIdByName;     // English name -> id
std::map<std::string, json> gInfoById;

std::string Id(const std::string &symbol)
{
   auto it = gIdBySymbol.find(symbol);
   if (it != gIdBySymbol.end())
      return it->second;
   auto it2 = gIdByName.find(symbol);
   if (it2 != gIdByName.end())
      return it2->second;
   std::fprintf(stderr, "  no effect '%s'\n", symbol.c_str());
   ++Failures();
   return "missing:" + symbol;
}

json Apply(int line, const std::string &symbol, const json &params = nullptr,
   std::optional<double> duration = {})
{
   json args{ { "id", Id(symbol) } };
   if (!params.is_null())
      args["params"] = params;
   if (duration)
      args["duration"] = *duration;
   return Must(line, "effects.apply", args);
}
#define APPLY(...) Apply(__LINE__, __VA_ARGS__)

json Describe(const std::string &symbol)
{
   return MUST("effects.describe", { { "id", Id(symbol) } });
}

json ParamOf(const json &description, const std::string &key)
{
   for (auto &p : description["params"])
      if (p.value("key", "") == key)
         return p;
   return nullptr;
}

// ===========================================================================
void TestCatalogue()
{
   std::fprintf(stderr, "-- catalogue\n");
   auto list = MUST("effects.list");
   CHECK(list["effects"].is_array());
   std::set<std::string> generate, process, analyze, tool;
   for (auto &e : list["effects"]) {
      const auto id = e.value("id", "");
      const auto name = e.value("name", "");
      const auto type = e.value("type", "");
      gInfoById[id] = e;
      gIdByName[name] = id;
      const std::string prefix = "_Built-in Effect: ";
      if (auto pos = id.find(prefix); pos != std::string::npos)
         gIdBySymbol[id.substr(pos + prefix.size())] = id;
      (type == "generate" ? generate : type == "process" ? process
         : type == "analyze" ? analyze : tool).insert(name);
      CHECK(!name.empty());
      CHECK(e.contains("description") && e.contains("vendor") &&
         e.contains("interactive") && e.contains("realtime") &&
         e.contains("isDefault") && e.contains("special") &&
         e.contains("family"));
   }
   // Every 3.7.9 built-in (StereoToMono is hidden)
   const char *builtins[] = { "Amplify", "Auto Duck", "Bass and Treble",
      "Change Pitch", "Change Speed and Pitch", "Change Tempo",
      "Click Removal", "Compressor", "Distortion", "Echo", "Filter Curve",
      "Graphic EQ", "Fade In", "Fade Out", "Invert", "Legacy Compressor",
      "Limiter", "Loudness Normalization", "Noise Reduction", "Normalize",
      "Paulstretch", "Phaser", "Repair", "Repeat", "Reverb", "Reverse",
      "Classic Filters", "Sliding Stretch", "Truncate Silence", "Wahwah",
      "Chirp", "DTMF Tones", "Noise", "Silence", "Tone", "Find Clipping" };
   for (auto b : builtins)
      CHECK_MSG(gIdBySymbol.count(b), b);
   CHECK(!gIdBySymbol.count("Stereo To Mono"));
   CHECK(gIdBySymbol["Amplify"] ==
      "Effect_Audacity_Audacity_Amplify_Built-in Effect: Amplify");
   CHECK(gIdBySymbol["Filter Curve"] ==
      "Effect_Audacity_Audacity_Filter Curve_Built-in Effect: Filter Curve");
   // Bundled Nyquist plug-ins
   for (auto name : { "High-Pass Filter", "Low-Pass Filter", "Tremolo",
           "Notch Filter", "Adjustable Fade", "Clip Fix", "Delay",
           "Studio Fade Out", "Vocoder", "Noise Gate" })
      CHECK_MSG(process.count(name), name);
   for (auto name : { "Pluck", "Rhythm Track", "Risset Drum", "Tone", "Noise",
           "Chirp", "DTMF Tones", "Silence" })
      CHECK_MSG(generate.count(name), name);
   for (auto name : { "Beat Finder", "Label Sounds", "Find Clipping",
           "Measure RMS" })
      CHECK_MSG(analyze.count(name), name);
   for (auto name : { "Nyquist Prompt", "Regular Interval Labels",
           "Sample Data Export", "Sample Data Import" })
      CHECK_MSG(tool.count(name), name);
   CHECK(generate.size() >= 8 && process.size() >= 45 && analyze.size() >= 4);

   // Built-ins vs Nyquist metadata
   auto &amp = gInfoById[gIdBySymbol["Amplify"]];
   CHECK(amp.value("family", "") == "Audacity");
   CHECK(amp.value("vendor", "") == "Audacity");
   CHECK(amp.value("interactive", false) == true);
   CHECK(amp.value("isDefault", false) == true);
   CHECK(!amp.value("description", "").empty());
   for (auto &[id, info] : gInfoById)
      CHECK_MSG(!info.value("description", "").empty(), id);
   CHECK(gInfoById[gIdBySymbol["Fade In"]].value("interactive", true) == false);
   CHECK(gInfoById[gIdBySymbol["Compressor"]].value("realtime", false) == true);
   CHECK(gInfoById[gIdBySymbol["Noise Reduction"]].value("special", "") ==
      "noiseReduction");
   CHECK(gInfoById[gIdBySymbol["Filter Curve"]].value("special", "") == "equalization");
   CHECK(gInfoById[gIdBySymbol["Graphic EQ"]].value("special", "") == "graphicEq");
   CHECK(gInfoById[gIdBySymbol["Auto Duck"]].value("special", "") == "autoDuck");
   CHECK(gInfoById[gIdBySymbol["Echo"]]["special"].is_null());
   CHECK(gInfoById[gIdByName["High-Pass Filter"]].value("family", "") == "Nyquist");

   // Menus: every listed id is in the menus exactly once per type
   auto &menus = list["menus"];
   for (auto kind : { "generate", "effect", "analyze", "tools" })
      CHECK_MSG(menus[kind].is_array() && !menus[kind].empty(), kind);
   std::map<std::string, int> seen;
   std::vector<std::string> sectionTitles;
   for (auto kind : { "generate", "effect", "analyze", "tools" })
      for (auto &section : menus[kind]) {
         CHECK(section.contains("title") && section["ids"].is_array());
         if (section["title"].is_string())
            sectionTitles.push_back(section["title"].get<std::string>());
         for (auto &id : section["ids"]) {
            ++seen[id.get<std::string>()];
            CHECK(gInfoById.count(id.get<std::string>()));
         }
      }
   for (auto &[id, info] : gInfoById)
      CHECK_MSG(seen[id] == 1, id + " in menus " + std::to_string(seen[id]));
   // The default Effect menu: EffectsMenuDefaults.xml groups, in order
   std::vector<std::string> effectTitles;
   for (auto &section : menus["effect"])
      if (section["title"].is_string())
         effectTitles.push_back(section["title"].get<std::string>());
   const std::vector<std::string> expected{ "Volume and Compression", "Fading",
      "Pitch and Tempo", "EQ and Filters", "Noise Removal and Repair",
      "Delay and Reverb", "Distortion and Modulation", "Special",
      "Spectral Tools", "Legacy" };
   CHECK_MSG(effectTitles.size() >= expected.size() &&
      std::equal(expected.begin(), expected.end(), effectTitles.begin()),
      json(effectTitles).dump());
   if (!menus["effect"].empty()) {
      auto first = menus["effect"][0];
      CHECK(first.value("title", "") == "Volume and Compression");
      // sorted by name: Amplify, Auto Duck, Compressor, Limiter, ...
      CHECK(first["ids"][0] == gIdBySymbol["Amplify"]);
      CHECK(first["ids"].size() == 6);
   }
   // Generate: bundled, sorted by name, untitled
   CHECK(menus["generate"][0]["title"].is_null());
   CHECK(menus["generate"][0]["ids"][0] == gIdBySymbol["Chirp"]);

   // lastApplied before anything ran
   CHECK(Res(Call("effects.lastApplied")).empty());
}

// ===========================================================================
void TestDescribeAll()
{
   std::fprintf(stderr, "-- describe every effect\n");
   NewProject();
   auto id = Tone(1.0);
   Select(0.2, 0.8, { id });
   for (auto &[effectId, info] : gInfoById) {
      auto r = Call("effects.describe", { { "id", effectId } });
      CHECK_MSG(Ok(r), effectId + " " + r.dump().substr(0, 300));
      if (!Ok(r))
         continue;
      auto d = Res(r);
      CHECK(d.value("id", "") == effectId);
      CHECK(d["params"].is_array());
      CHECK(d["presets"]["factory"].is_array() && d["presets"]["user"].is_array());
      CHECK(d.contains("supportsDuration") && d.contains("duration") &&
         d.contains("special") && d.contains("nyquist") && d.contains("help"));
      for (auto &p : d["params"]) {
         const auto kind = p.value("kind", "");
         CHECK_MSG(kind == "bool" || kind == "int" || kind == "double" ||
            kind == "enum" || kind == "string", effectId + " " + p.dump());
         CHECK_MSG(!p.value("label", "").empty(), effectId + " " + p.dump());
         CHECK(p.contains("value") && p.contains("default"));
         if (kind == "enum")
            CHECK_MSG(p["choices"].size() == p["choiceLabels"].size() &&
               !p["choices"].empty(), effectId + " " + p.dump());
      }
   }
   // Labels/units/display from the bridge table
   auto amp = Describe("Amplify");
   auto ratio = ParamOf(amp, "Ratio");
   CHECK(ratio.value("label", "") == "Amplification (dB)");
   CHECK(ratio.value("display", "") == "dB");
   CHECK(ratio.value("kind", "") == "double");
   CHECK(Near(ratio.value("min", 0.0), 0.003162, 1e-6));
   CHECK(Near(ratio.value("max", 0.0), 316.227766, 1e-4));
   CHECK(ParamOf(amp, "AllowClipping").value("kind", "") == "bool");
   // Amplify reports the peak of the selection (tone amplitude 0.5)
   CHECK(Near(amp.value("peak", 0.0), 0.5, 0.01));
   auto echo = Describe("Echo");
   auto delay = ParamOf(echo, "Delay");
   CHECK(delay.value("label", "") == "Delay time (seconds)");
   CHECK(!delay.contains("max"));   // FLT_MAX = unbounded
   auto dist = Describe("Distortion");
   auto type = ParamOf(dist, "Type");
   CHECK(type.value("kind", "") == "enum" && type["choices"].size() == 11);
   CHECK(type["choices"][0] == "Hard Clipping");
   CHECK(ParamOf(dist, "DC Block").value("kind", "") == "bool");
   CHECK(dist["presets"]["factory"].size() >= 10);
   auto loud = Describe("Loudness Normalization");
   auto normTo = ParamOf(loud, "NormalizeTo");
   CHECK(normTo.value("kind", "") == "enum" && normTo["choices"].size() == 2);
   auto tone = Describe("Tone");
   CHECK(tone.value("supportsDuration", false) == true);
   CHECK(tone.value("type", "") == "generate");
   // with a time selection the duration is the selection length
   CHECK(Near(tone.value("duration", 0.0), 0.6, 1e-6));
   CHECK(ParamOf(tone, "Frequency").value("label", "") == "Frequency (Hz)");
   auto nr = Describe("Noise Reduction");
   CHECK(nr.value("special", "") == "noiseReduction");
   CHECK(nr["params"].size() == 4);
   CHECK(nr.value("profileCaptured", true) == false);
   auto eq = Describe("Filter Curve");
   CHECK(eq.value("special", "") == "equalization");
   CHECK(eq["curve"]["points"].is_array());
   CHECK(eq["curves"].size() == 10);
   auto geq = Describe("Graphic EQ");
   CHECK_MSG(geq["curves"].size() == 5, geq["curves"].dump());
   auto comp = Describe("Compressor");
   CHECK(comp["presets"]["factory"].size() >= 20);
   CHECK(Near(ParamOf(comp, "thresholdDb").value("min", 0.0), -60.0, 1e-9));
   // Nyquist: richer metadata
   auto hp = MUST("effects.describe", { { "id", Id("High-Pass Filter") } });
   CHECK(hp["nyquist"].is_object() && hp["nyquist"]["controls"].size() >= 2);
   auto freq = ParamOf(hp, "frequency");
   if (freq.is_null())
      freq = ParamOf(hp, "FREQUENCY");
   CHECK_MSG(!freq.is_null(), hp.dump().substr(0, 600));
   CHECK(freq.value("kind", "") == "double");
   CHECK(Near(freq.value("default", 0.0), 1000.0, 1e-6));
   auto prompt = MUST("effects.describe", { { "id", Id("Nyquist Prompt") } });
   CHECK(prompt["nyquist"].is_object() && prompt["nyquist"].value("prompt", false));
   CHECK(ParamOf(prompt, "Command").value("kind", "") == "string");
   CHECK(ParamOf(prompt, "Parameters").value("kind", "") == "string");
   if (std::getenv("EFFECTS_TEST_DUMP")) {
      std::fprintf(stderr, "%s\n%s\n%s\n%s\n", hp.dump(1).c_str(),
         prompt.dump(1).c_str(), amp.dump(1).c_str(),
         MUST("effects.list")["menus"].dump(1).c_str());
      for (auto name : { "Adjustable Fade", "Sample Data Export", "Pluck" })
         std::fprintf(stderr, "%s\n", MUST("effects.describe",
            { { "id", Id(name) } }).dump(1).c_str());
   }

   EXPECT_ERROR(Call("effects.describe", { { "id", "nope" } }), "NOT_FOUND");
   EXPECT_ERROR(Call("effects.describe", json::object()), "INVALID_ARGS");
}

// ===========================================================================
void TestSetParams()
{
   std::fprintf(stderr, "-- setParams / presets\n");
   const auto echo = Id("Echo");
   MUST("effects.loadPreset", { { "id", echo }, { "kind", "defaults" } });
   auto d = MUST("effects.setParams", { { "id", echo },
      { "params", { { "Delay", 0.25 } } } });
   CHECK(Near(ParamOf(d, "Delay").value("value", 0.0), 0.25, 1e-9));
   CHECK(Near(ParamOf(d, "Decay").value("value", 0.0), 0.5, 1e-9));
   // partial merge keeps Delay
   d = MUST("effects.setParams", { { "id", echo }, { "params", { { "Decay", 0.3 } } } });
   CHECK(Near(ParamOf(d, "Delay").value("value", 0.0), 0.25, 1e-9));
   CHECK(Near(ParamOf(d, "Decay").value("value", 0.0), 0.3, 1e-9));
   // invalid values change nothing
   EXPECT_ERROR(Call("effects.setParams", { { "id", echo },
      { "params", { { "Decay", 0.4 }, { "Delay", -1 } } } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", echo },
      { "params", { { "Nope", 1 } } } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", echo },
      { "params", { { "Delay", "x" } } } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", echo },
      { "duration", 3 } }), "INVALID_ARGS");
   d = Describe("Echo");
   CHECK(Near(ParamOf(d, "Decay").value("value", 0.0), 0.3, 1e-9));
   // enum by name and by index; bool; int range
   const auto dist = Id("Distortion");
   d = MUST("effects.setParams", { { "id", dist },
      { "params", { { "Type", "Soft Clipping" }, { "DC Block", true } } } });
   CHECK(ParamOf(d, "Type").value("value", -1) == 1);
   CHECK(ParamOf(d, "DC Block").value("value", false) == true);
   d = MUST("effects.setParams", { { "id", dist }, { "params", { { "Type", 3 } } } });
   CHECK(ParamOf(d, "Type").value("value", -1) == 3);
   EXPECT_ERROR(Call("effects.setParams", { { "id", dist },
      { "params", { { "Type", 11 } } } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", dist },
      { "params", { { "Repeats", 6 } } } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", dist },
      { "params", { { "Repeats", 1.5 } } } }), "INVALID_ARGS");
   // keys with spaces also accepted with '_'
   d = MUST("effects.setParams", { { "id", dist },
      { "params", { { "DC_Block", false } } } });
   CHECK(ParamOf(d, "DC Block").value("value", true) == false);
   MUST("effects.loadPreset", { { "id", dist }, { "kind", "defaults" } });
   // DTMF sequence validation
   EXPECT_ERROR(Call("effects.setParams", { { "id", Id("DTMF Tones") },
      { "params", { { "Sequence", "12!" } } } }), "INVALID_ARGS");
   d = MUST("effects.setParams", { { "id", Id("DTMF Tones") },
      { "params", { { "Sequence", "0123#*" } } }, { "duration", 2.0 } });
   CHECK(ParamOf(d, "Sequence").value("value", "") == "0123#*");
   // generator duration (no selection -> LastUsedDuration)
   NewProject();
   d = Describe("DTMF Tones");
   CHECK(Near(d.value("duration", 0.0), 2.0, 1e-9));
   EXPECT_ERROR(Call("effects.setParams", { { "id", Id("Tone") },
      { "duration", -1 } }), "INVALID_ARGS");

   // User presets
   MUST("effects.setParams", { { "id", echo }, { "params", { { "Delay", 0.3 } } } });
   MUST("effects.savePreset", { { "id", echo }, { "name", "My Echo" } });
   d = Describe("Echo");
   CHECK(std::find(d["presets"]["user"].begin(), d["presets"]["user"].end(),
      "My Echo") != d["presets"]["user"].end());
   MUST("effects.setParams", { { "id", echo }, { "params", { { "Delay", 0.7 } } } });
   d = MUST("effects.loadPreset", { { "id", echo }, { "kind", "user" }, { "name", "My Echo" } });
   CHECK(Near(ParamOf(d, "Delay").value("value", 0.0), 0.3, 1e-9));
   d = MUST("effects.loadPreset", { { "id", echo }, { "kind", "defaults" } });
   CHECK(Near(ParamOf(d, "Delay").value("value", 0.0), 1.0, 1e-9));
   MUST("effects.deletePreset", { { "id", echo }, { "name", "My Echo" } });
   d = Describe("Echo");
   CHECK(d["presets"]["user"].empty());
   EXPECT_ERROR(Call("effects.deletePreset", { { "id", echo }, { "name", "My Echo" } }), "NOT_FOUND");
   EXPECT_ERROR(Call("effects.loadPreset", { { "id", echo }, { "kind", "user" },
      { "name", "My Echo" } }), "NOT_FOUND");
   EXPECT_ERROR(Call("effects.savePreset", { { "id", echo }, { "name", "a/b" } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.savePreset", { { "id", echo }, { "name", "  " } }), "INVALID_ARGS");
   // Factory presets by name and index
   const auto reverb = Id("Reverb");
   d = Describe("Reverb");
   CHECK(d["presets"]["factory"].size() == 18);
   const auto second = d["presets"]["factory"][1].get<std::string>();
   d = MUST("effects.loadPreset", { { "id", reverb }, { "kind", "factory" }, { "index", 1 } });
   d = MUST("effects.loadPreset", { { "id", reverb }, { "kind", "factory" }, { "name", second } });
   EXPECT_ERROR(Call("effects.loadPreset", { { "id", reverb }, { "kind", "factory" },
      { "name", "No such preset" } }), "NOT_FOUND");
   EXPECT_ERROR(Call("effects.loadPreset", { { "id", reverb }, { "kind", "bogus" } }),
      "INVALID_ARGS");
   MUST("effects.loadPreset", { { "id", reverb }, { "kind", "defaults" } });
   // Compressor factory preset changes values
   const auto comp = Id("Compressor");
   d = MUST("effects.loadPreset", { { "id", comp }, { "kind", "factory" }, { "index", 1 } });
   MUST("effects.loadPreset", { { "id", comp }, { "kind", "defaults" } });
   // Amplify defaults = 1/peak of the selection
   auto id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   d = MUST("effects.loadPreset", { { "id", Id("Amplify") }, { "kind", "defaults" } });
   CHECK_MSG(Near(ParamOf(d, "Ratio").value("value", 0.0), 2.0, 0.02), d.dump().substr(0, 300));
}

// ===========================================================================
struct ProcessCase {
   std::string symbol;
   json params;
   std::function<void(int64_t id, const std::vector<float> &before)> check;
   double seconds = 1.0, t0 = 0.0, t1 = 1.0, freq = 440, amplitude = 0.5;
   bool expectApplied = true;
};

void TestProcessEffects()
{
   std::fprintf(stderr, "-- every built-in process effect\n");
   auto changed = [](int64_t id, const std::vector<float> &before) {
      auto after = Samples(id, 0, 1);
      CHECK(MaxDiff(after, before) > 1e-3);
   };
   std::vector<ProcessCase> cases{
      { "Amplify", { { "Ratio", 1.5 }, { "AllowClipping", false } },
        [](int64_t id, auto &) { CHECK(Near(Peak(Samples(id, 0, 1)), 0.75, 0.01)); } },
      { "Bass and Treble", { { "Bass", 12.0 } }, changed },
      { "Change Pitch", { { "Percentage", 50.0 } }, [](int64_t id, auto &) {
           CHECK(Near(End(id), 1.0, 0.02)); } },
      { "Change Speed and Pitch", { { "Percentage", 100.0 } }, [](int64_t id, auto &) {
           CHECK_MSG(Near(End(id), 0.5, 0.01), std::to_string(End(id))); } },
      { "Change Tempo", { { "Percentage", 100.0 } }, [](int64_t id, auto &) {
           CHECK_MSG(Near(End(id), 0.5, 0.05), std::to_string(End(id))); } },
      { "Classic Filters", { { "FilterType", "Butterworth" },
           { "FilterSubtype", "Lowpass" }, { "Order", 6 }, { "Cutoff", 500.0 } },
        [](int64_t id, auto &) { CHECK(Peak(Samples(id, 0.5, 1)) < 0.1); },
        1.0, 0.0, 1.0, 5000 },
      { "Compressor", { { "thresholdDb", -30.0 }, { "compressionRatio", 4.0 } }, changed },
      { "Distortion", json::object(), changed },
      { "Echo", { { "Delay", 0.25 }, { "Decay", 0.5 } }, changed },
      { "Fade In", nullptr, [](int64_t id, auto &) {
           auto s = Samples(id, 0, 1);
           CHECK(Peak({ s.begin(), s.begin() + 100 }) < 0.01);
           CHECK(Peak({ s.end() - 1000, s.end() }) > 0.45); } },
      { "Fade Out", nullptr, [](int64_t id, auto &) {
           auto s = Samples(id, 0, 1);
           CHECK(Peak({ s.end() - 100, s.end() }) < 0.01);
           CHECK(Peak({ s.begin(), s.begin() + 1000 }) > 0.45); } },
      { "Invert", nullptr, [](int64_t id, const std::vector<float> &before) {
           auto s = Samples(id, 0, 1);
           CHECK(s.size() == before.size());
           double d = 0;
           for (size_t i = 0; i < std::min(s.size(), before.size()); ++i)
              d = std::max(d, double(std::fabs(s[i] + before[i])));
           CHECK(d < 1e-6); } },
      { "Legacy Compressor", json::object(), changed },
      { "Limiter", { { "thresholdDb", -12.0 }, { "makeupTargetDb", -12.0 } },
        [](int64_t id, auto &) { CHECK(Peak(Samples(id, 0.2, 1)) < 0.4); } },
      { "Loudness Normalization", { { "LUFSLevel", -30.0 } }, [](int64_t id, auto &) {
           CHECK(Rms(Samples(id, 0, 1)) < 0.2); } },
      { "Normalize", { { "PeakLevel", -6.0 }, { "RemoveDcOffset", false } },
        [](int64_t id, auto &) { CHECK(Near(Peak(Samples(id, 0, 1)), 0.501, 0.01)); } },
      { "Paulstretch", { { "Stretch Factor", 2.0 }, { "Time Resolution", 0.1 } },
        [](int64_t id, auto &) { CHECK_MSG(End(id) > 1.5, std::to_string(End(id))); } },
      { "Phaser", json::object(), changed },
      { "Repeat", { { "Count", 2 } }, [](int64_t id, auto &) {
           CHECK_MSG(Near(End(id), 3.0, 0.01), std::to_string(End(id))); } },
      { "Reverb", json::object(), changed },
      { "Reverse", nullptr, [](int64_t id, const std::vector<float> &before) {
           auto s = Samples(id, 0, 1);
           CHECK(s.size() == before.size());
           double d = 0;
           for (size_t i = 0; i < std::min(s.size(), before.size()); ++i)
              d = std::max(d, double(std::fabs(s[i] - before[before.size() - 1 - i])));
           CHECK(d < 1e-6); } },
      { "Sliding Stretch", { { "RatePercentChangeStart", 100.0 },
           { "RatePercentChangeEnd", 100.0 } }, [](int64_t id, auto &) {
           CHECK_MSG(Near(End(id), 0.5, 0.05), std::to_string(End(id))); } },
      { "Wahwah", json::object(), changed },
      { "Graphic EQ", nullptr, [](int64_t, auto &) {} },
      { "Filter Curve", nullptr, [](int64_t, auto &) {} },
   };
   for (auto &c : cases) {
      std::fprintf(stderr, "   %s\n", c.symbol.c_str());
      NewProject();
      auto id = Tone(c.seconds, c.freq, 1, c.amplitude);
      Select(c.t0, c.t1, { id });
      const auto before = Samples(id, 0, 1);
      const auto gen = Snapshot().value("generation", 0);
      auto r = APPLY(c.symbol, c.params);
      CHECK_MSG(r.value("applied", false) == c.expectApplied, c.symbol + " " + r.dump());
      if (r.value("applied", false))
         c.check(id, before);
      auto s = Snapshot();
      CHECK(s.value("generation", 0) > gen);
      if (c.expectApplied) {
         CHECK_MSG(s["history"].value("undo", "") == gInfoById[Id(c.symbol)].value("name", "?"),
            c.symbol + " " + s["history"].dump());
         CHECK(s["lastEffect"].is_object() &&
            Str(s["lastEffect"], "id") == Id(c.symbol));
      }
   }

   // Click Removal on a pure sine: "not effective", nothing changed
   {
      NewProject();
      auto id = Tone(1.0);
      Select(0, 1, { id });
      const auto before = Samples(id, 0, 1);
      auto r = APPLY("Click Removal");
      CHECK(r.value("applied", true) == false);
      CHECK_MSG(r.value("message", "").find("not effective") != std::string::npos, r.dump());
      CHECK(MaxDiff(Samples(id, 0, 1), before) < 1e-9);
   }
   // Repair: <= 128 samples with audio around
   {
      NewProject();
      auto id = Tone(1.0);
      const double rate = TrackOf(id).value("rate", 44100.0);
      Select(0.5, 0.5 + 64 / rate, { id });
      auto r = APPLY("Repair");
      CHECK_MSG(r.value("applied", false), r.dump());
      Select(0.1, 0.9, { id });
      r = APPLY("Repair");
      CHECK(r.value("applied", true) == false && !r.value("message", "").empty());
   }
   // Truncate Silence: a 0.4 s silence in the middle is truncated to 0.1 s
   {
      NewProject();
      auto id = Tone(1.0);
      Select(0.3, 0.7, { id });
      auto r = APPLY("Silence");
      CHECK(r.value("applied", false));
      Select(0, 1, { id });
      r = APPLY("Truncate Silence", { { "Threshold", -60.0 }, { "Minimum", 0.2 },
         { "Truncate", 0.1 }, { "Action", "Truncate Detected Silence" } });
      CHECK(r.value("applied", false));
      CHECK_MSG(Near(End(id), 0.7, 0.02), std::to_string(End(id)));
   }
   // Auto Duck: control track below the selection
   {
      NewProject();
      auto music = Tone(3.0, 440, 1, 0.5);
      auto voice = Tone(3.0, 880, 1, 0.5);
      Select(0, 3, { music });
      const auto before = Samples(music, 0, 3);
      auto r = APPLY("Auto Duck", { { "DuckAmountDb", -12.0 } });
      CHECK_MSG(r.value("applied", false), r.dump());
      CHECK(Rms(Samples(music, 1, 2)) < Rms(before) * 0.5);
      CHECK(Near(Rms(Samples(voice, 1, 2)), 0.3535, 0.01));
      // too short for the fades: refused with a message
      Select(0, 0.8, { music });
      r = APPLY("Auto Duck");
      CHECK(r.value("applied", true) == false && !r.value("message", "").empty());
      // no control track
      Select(0, 3, { voice });
      r = APPLY("Auto Duck");
      CHECK(r.value("applied", true) == false &&
         r.value("message", "").find("control track") != std::string::npos);
   }
   // Process effects need a time selection on wave tracks
   {
      NewProject();
      auto id = Tone(1.0);
      Select(0.5, 0.5, { id });
      EXPECT_ERROR(Call("effects.apply", { { "id", Id("Amplify") } }), "NO_SELECTION");
      Select(0, 1, {});
      MUST("select.none");
      EXPECT_ERROR(Call("effects.apply", { { "id", Id("Amplify") } }), "NO_SELECTION");
      // selectAllOnNone recovers
      MUST("settings.set", { { "settings", { { "selectAllOnNone", true } } } });
      auto r = APPLY("Invert");
      CHECK(r.value("applied", false));
      MUST("settings.set", { { "settings", { { "selectAllOnNone", false } } } });
      // Amplify refuses to clip unless allowed
      Select(0, 1, { id });
      r = APPLY("Amplify", { { "Ratio", 4.0 }, { "AllowClipping", false } });
      CHECK(r.value("applied", true) == false && !r.value("message", "").empty());
      r = APPLY("Amplify", { { "Ratio", 4.0 }, { "AllowClipping", true } });
      CHECK(r.value("applied", false));
      CHECK(Near(Peak(Samples(id, 0, 1)), 2.0, 0.02));
      // ... and Find Clipping finds it (label track "Clipping")
      const auto nTracks = Tracks().size();
      r = APPLY("Find Clipping");
      CHECK(r.value("applied", false));
      auto tracks = Tracks();
      CHECK(tracks.size() == nTracks + 1);
      CHECK(!tracks.empty() && tracks.back().value("kind", "") == "label" &&
         !tracks.back()["labels"].empty());
      CHECK(Str(Snapshot()["lastAnalyzer"], "id") == Id("Find Clipping"));
   }
   // Repeat last effect
   {
      NewProject();
      auto id = Tone(1.0);
      Select(0, 1, { id });
      APPLY("Amplify", { { "Ratio", 0.5 }, { "AllowClipping", false } });
      CHECK(Near(Peak(Samples(id, 0, 1)), 0.25, 0.01));
      auto r = MUST("effects.repeatLast");
      CHECK(r.value("applied", false));
      CHECK(Near(Peak(Samples(id, 0, 1)), 0.125, 0.01));
      auto last = MUST("effects.lastApplied");
      CHECK(last.value("id", "") == Id("Amplify") && last.value("name", "") == "Amplify");
      // flags LAST_EFF (bit 23)
      CHECK((Snapshot().value("flags", int64_t(0)) >> 23) & 1);
   }
}

// ===========================================================================
void TestGenerators()
{
   std::fprintf(stderr, "-- generators\n");
   struct Case { const char *symbol; json params; double minPeak, maxPeak; };
   const std::vector<Case> cases{
      { "Tone", { { "Frequency", 1000.0 }, { "Amplitude", 0.8 } }, 0.79, 0.81 },
      { "Chirp", json::object(), 0.1, 0.81 },
      { "DTMF Tones", { { "Sequence", "123" } }, 0.1, 0.81 },
      { "Noise", { { "Amplitude", 0.5 } }, 0.05, 0.51 },
      { "Silence", nullptr, 0.0, 0.0 },
   };
   for (auto &c : cases) {
      std::fprintf(stderr, "   %s\n", c.symbol);
      NewProject();
      auto r = APPLY(c.symbol, c.params, 1.5);
      CHECK_MSG(r.value("applied", false), std::string(c.symbol) + r.dump());
      auto tracks = Tracks();
      CHECK(tracks.size() == 1);
      if (tracks.empty())
         continue;
      const auto id = tracks[0].value("id", int64_t(-1));
      CHECK_MSG(Near(tracks[0].value("end", 0.0), 1.5, 1e-3),
         std::string(c.symbol) + " " + tracks[0].dump().substr(0, 200));
      const auto peak = Peak(Samples(id, 0, 1.5));
      CHECK_MSG(peak >= c.minPeak - 1e-9 && peak <= c.maxPeak + 1e-9,
         std::string(c.symbol) + " peak " + std::to_string(peak));
      auto s = Snapshot();
      CHECK(Near(s["selection"].value("t1", 0.0), 1.5, 1e-3));
      CHECK(Str(s["lastGenerator"], "id") == Id(c.symbol));
      CHECK(Near(Describe(c.symbol).value("duration", 0.0), 1.5, 1e-9));
   }
   // At a point selection inside audio: inserts and shifts
   NewProject();
   auto id = Tone(1.0);
   Select(0.5, 0.5, { id });
   auto r = APPLY("Silence", nullptr, 0.25);
   CHECK(r.value("applied", false));
   CHECK_MSG(Near(End(id), 1.25, 1e-3), std::to_string(End(id)));
   // With a time selection and no duration: replaces the selection
   Select(0, 0.5, { id });
   r = MUST("effects.apply", { { "id", Id("Tone") } });
   CHECK(r.value("applied", false));
   CHECK_MSG(Near(End(id), 1.25, 1e-3), std::to_string(End(id)));
}

// ===========================================================================
void TestNyquist()
{
   std::fprintf(stderr, "-- nyquist\n");
   NewProject();
   auto id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   auto hp = MUST("effects.describe", { { "id", Id("High-Pass Filter") } });
   std::string freqKey = "frequency";
   for (auto &p : hp["params"])
      if (p.value("kind", "") == "double")
         freqKey = p.value("key", freqKey);
   auto r = MUST("effects.apply", { { "id", Id("High-Pass Filter") },
      { "params", { { freqKey, 4000.0 } } } });
   CHECK_MSG(r.value("applied", false), r.dump());
   CHECK_MSG(Peak(Samples(id, 0.2, 1)) < 0.1, std::to_string(Peak(Samples(id, 0.2, 1))));
   CHECK(Snapshot()["history"].value("undo", "") == "High-Pass Filter");

   NewProject();
   id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   const auto before = Samples(id, 0, 1);
   r = MUST("effects.apply", { { "id", Id("Tremolo") } });
   CHECK_MSG(r.value("applied", false), r.dump());
   CHECK(MaxDiff(Samples(id, 0, 1), before) > 0.01);

   // Nyquist generator (Pluck decides its own length)
   NewProject();
   r = MUST("effects.apply", { { "id", Id("Pluck") } });
   CHECK_MSG(r.value("applied", false), r.dump());
   auto tracks = Tracks();
   CHECK(tracks.size() == 1 && tracks[0].value("end", 0.0) > 0.1);
   if (!tracks.empty())
      CHECK(Peak(Samples(tracks[0].value("id", int64_t(0)), 0, 0.5)) > 0.01);

   // Nyquist analyzer: the result is the message, nothing is changed
   NewProject();
   id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   const auto undo = Snapshot()["history"].value("undo", "");
   r = MUST("effects.apply", { { "id", Id("Measure RMS") } });
   CHECK_MSG(r.value("applied", false) && !r.value("message", "").empty(), r.dump());
   CHECK(Snapshot()["history"].value("undo", "") == undo);
   CHECK(Str(Snapshot()["lastAnalyzer"], "id") == Id("Measure RMS"));

   // Beat Finder adds a label track
   r = MUST("effects.apply", { { "id", Id("Beat Finder") } });
   CHECK_MSG(r.contains("applied"), r.dump());

   // Nyquist Prompt (tool) with code
   NewProject();
   id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   r = MUST("effects.apply", { { "id", Id("Nyquist Prompt") },
      { "params", { { "Command", "(mult *track* 0.5)" } } } });
   CHECK_MSG(r.value("applied", false), r.dump());
   CHECK(Near(Peak(Samples(id, 0, 1)), 0.25, 0.01));
   CHECK(Str(Snapshot()["lastTool"], "id") == Id("Nyquist Prompt"));
}

// ===========================================================================
void TestNoiseReduction()
{
   std::fprintf(stderr, "-- noise reduction\n");
   NewProject();
   auto r = APPLY("Noise", { { "Amplitude", 0.2 }, { "Type", "White" } }, 2.0);
   auto tracks = Tracks();
   CHECK(tracks.size() == 1);
   if (tracks.empty())
      return;
   const auto id = tracks[0].value("id", int64_t(-1));
   Select(0, 2, { id });
   // No profile yet
   r = MUST("effects.apply", { { "id", Id("Noise Reduction") } });
   CHECK(r.value("applied", true) == false && !r.value("message", "").empty());
   // Step 1 on the noise
   Select(0, 1, { id });
   const auto undo = Snapshot()["history"].value("undo", "");
   MUST("effects.noiseReduction.captureProfile");
   CHECK(Snapshot()["history"].value("undo", "") == undo);
   auto d = Describe("Noise Reduction");
   CHECK(d.value("profileCaptured", false) == true);
   // prefs-backed parameters
   d = MUST("effects.setParams", { { "id", Id("Noise Reduction") },
      { "params", { { "Gain", 30.0 }, { "Sensitivity", 6.0 },
         { "FreqSmoothing", 3 }, { "ReductionChoice", "Reduce" } } } });
   CHECK(Near(ParamOf(d, "Gain").value("value", 0.0), 30.0, 1e-9));
   CHECK(ParamOf(d, "FreqSmoothing").value("value", -1) == 3);
   EXPECT_ERROR(Call("effects.setParams", { { "id", Id("Noise Reduction") },
      { "params", { { "Gain", 49.0 } } } }), "INVALID_ARGS");
   // Step 2
   Select(0, 2, { id });
   const double before = Rms(Samples(id, 1, 2));
   r = MUST("effects.apply", { { "id", Id("Noise Reduction") } });
   CHECK_MSG(r.value("applied", false), r.dump());
   const double after = Rms(Samples(id, 1, 2));
   CHECK_MSG(after < before * 0.2, std::to_string(before) + " -> " + std::to_string(after));
   // capture needs a selection
   Select(1, 1, { id });
   EXPECT_ERROR(Call("effects.noiseReduction.captureProfile"), "NO_SELECTION");
   // NR is not a "last effect" for the profile step
   CHECK(Str(Snapshot()["lastEffect"], "id") == Id("Noise Reduction"));
}

// ===========================================================================
void TestEqualization()
{
   std::fprintf(stderr, "-- equalization\n");
   NewProject();
   auto id = Tone(1.0, 440, 1, 0.5);
   Select(0, 1, { id });
   const auto eq = Id("Filter Curve");
   json curve{ { "points", json::array({ { { "f", 20.0 }, { "dB", -30.0 } },
      { { "f", 20000.0 }, { "dB", -30.0 } } }) }, { "linearFreq", false } };
   auto d = MUST("effects.setParams", { { "id", eq }, { "curve", curve } });
   CHECK(d["curve"]["points"].size() == 2);
   CHECK(Near(d["curve"]["points"][0].value("dB", 0.0), -30.0, 1e-9));
   EXPECT_ERROR(Call("effects.setParams", { { "id", Id("Echo") }, { "curve", curve } }),
      "INVALID_ARGS");
   EXPECT_ERROR(Call("effects.setParams", { { "id", eq },
      { "curve", { { "points", json::array({ { { "f", -1.0 }, { "dB", 0.0 } } }) } } } }),
      "INVALID_ARGS");
   auto r = MUST("effects.apply", { { "id", eq } });
   CHECK_MSG(r.value("applied", false), r.dump());
   const auto peak = Peak(Samples(id, 0.3, 0.7));
   CHECK_MSG(Near(peak, 0.5 * std::pow(10.0, -30.0 / 20), 0.01), std::to_string(peak));
   // Built-in curves are factory presets
   d = MUST("effects.loadPreset", { { "id", eq }, { "kind", "factory" },
      { "name", "Bass Boost" } });
   CHECK(d["curve"]["points"].size() == 2);
   CHECK(Near(d["curve"]["points"][0].value("f", 0.0), 100.0, 1e-6));
   CHECK(Near(d["curve"]["points"][0].value("dB", 0.0), 9.0, 1e-6));
   // Graphic EQ
   d = MUST("effects.loadPreset", { { "id", Id("Graphic EQ") }, { "kind", "factory" },
      { "name", "Treble Cut" } });
   CHECK(!d["curve"]["points"].empty());
   r = MUST("effects.apply", { { "id", Id("Graphic EQ") } });
   CHECK(r.value("applied", false));
}

// ===========================================================================
void TestPreview()
{
   std::fprintf(stderr, "-- preview\n");
   NewProject();
   auto id = Tone(3.0, 440, 1, 0.5);
   Select(0, 3, { id });
   const auto before = Samples(id, 0, 3);
   auto from = gSink->Count();
   MUST("effects.preview", { { "id", Id("Amplify") },
      { "params", { { "Ratio", 0.5 } } } });
   auto playing = gSink->WaitFor("transport", 5s, from, [](const json &p) {
      return p.value("state", "") == "playing" && p.value("reason", "") == "preview"; });
   CHECK(playing.has_value());
   CHECK(Snapshot()["audio"].value("busy", false) == true);
   // Busy: edits are refused
   EXPECT_ERROR(Call("effects.apply", { { "id", Id("Invert") } }), "AUDIO_BUSY");
   std::this_thread::sleep_for(300ms);
   from = gSink->Count();
   MUST("effects.stopPreview");
   auto stopped = gSink->WaitFor("transport", 5s, from, [](const json &p) {
      return p.value("state", "") == "stopped" && p.value("reason", "") == "preview"; });
   CHECK(stopped.has_value());
   CHECK(Snapshot()["audio"].value("busy", true) == false);
   // The project is unchanged by a preview
   CHECK(MaxDiff(Samples(id, 0, 3), before) < 1e-9);
   MUST("effects.stopPreview");   // no-op

   // Natural end: a short selection
   Select(0, 0.3, { id });
   from = gSink->Count();
   MUST("effects.preview", { { "id", Id("Reverse") } });
   stopped = gSink->WaitFor("transport", 10s, from, [](const json &p) {
      return p.value("state", "") == "stopped" && p.value("reason", "") == "preview"; });
   CHECK(stopped.has_value());

   // transport.stop stops a preview
   Select(0, 3, { id });
   from = gSink->Count();
   MUST("effects.preview", { { "id", Id("Echo") } });
   CHECK(gSink->WaitFor("transport", 5s, from, [](const json &p) {
      return p.value("state", "") == "playing"; }).has_value());
   from = gSink->Count();
   MUST("transport.stop");
   stopped = gSink->WaitFor("transport", 5s, from, [](const json &p) {
      return p.value("state", "") == "stopped" && p.value("reason", "") == "preview"; });
   CHECK(stopped.has_value());
   std::this_thread::sleep_for(150ms);
   CHECK(Snapshot()["audio"].value("busy", true) == false);
   // Generator preview without a selected track; a second preview replaces
   NewProject();
   from = gSink->Count();
   MUST("effects.preview", { { "id", Id("Tone") }, { "duration", 2.0 } });
   MUST("effects.preview", { { "id", Id("Noise") }, { "duration", 2.0 } });
   CHECK(Tracks().empty());
   MUST("effects.stopPreview");
   std::this_thread::sleep_for(100ms);
   auto events = gSink->Since(from);
   int playingCount = 0, stoppedCount = 0;
   for (auto &e : events)
      if (e.type == "transport" && e.payload.value("reason", "") == "preview")
         (e.payload.value("state", "") == "playing" ? playingCount : stoppedCount)++;
   CHECK(playingCount == 2 && stoppedCount == 2);
   // Process effects need a selection
   EXPECT_ERROR(Call("effects.preview", { { "id", Id("Amplify") } }), "NO_SELECTION");
}

// ===========================================================================
void TestAnalyzers()
{
   std::fprintf(stderr, "-- spectrum / contrast\n");
   NewProject();
   auto id = Tone(4.0, 440, 1, 0.5);
   Select(0, 4, { id });
   auto s = MUST("analyze.spectrum", { { "algorithm", "spectrum" },
      { "window", "hann" }, { "size", 4096 } });
   CHECK(s["values"].size() == 2048);
   const double binHz = s.value("binHz", 0.0);
   CHECK(Near(binHz, s.value("rate", 0.0) / 4096, 1e-9));
   size_t best = 0;
   for (size_t i = 0; i < s["values"].size(); ++i)
      if (s["values"][i].get<double>() > s["values"][best].get<double>())
         best = i;
   CHECK_MSG(Near(best * binHz, 440.0, 2 * binHz), std::to_string(best * binHz));
   CHECK(s.value("maxValue", -1e9) > s.value("minValue", 1e9));
   CHECK(!s.contains("warning"));
   auto a = MUST("analyze.spectrum", { { "algorithm", "autocorrelation" },
      { "window", "hamming" }, { "size", 2048 } });
   CHECK(Near(a.value("binSeconds", 0.0), 1.0 / a.value("rate", 1.0), 1e-12));
   CHECK(a["values"].size() == 1024);
   auto c = MUST("analyze.spectrum", { { "algorithm", "cepstrum" },
      { "window", "blackmanHarris" }, { "size", 131072 } });
   CHECK(c["values"].size() == 65536);
   for (auto alg : { "cubeRootAutocorrelation", "enhancedAutocorrelation" })
      MUST("analyze.spectrum", { { "algorithm", alg }, { "window", "welch" },
         { "size", 1024 } });
   EXPECT_ERROR(Call("analyze.spectrum", { { "algorithm", "spectrum" },
      { "window", "hann" }, { "size", 1000 } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("analyze.spectrum", { { "algorithm", "nope" },
      { "window", "hann" }, { "size", 1024 } }), "INVALID_ARGS");
   EXPECT_ERROR(Call("analyze.spectrum", { { "algorithm", "spectrum" },
      { "window", "kaiser" }, { "size", 1024 } }), "INVALID_ARGS");
   Select(0, 0.001, { id });
   EXPECT_ERROR(Call("analyze.spectrum", { { "algorithm", "spectrum" },
      { "window", "hann" }, { "size", 4096 } }), "FAILED");
   Select(1, 1, { id });
   EXPECT_ERROR(Call("analyze.spectrum", { { "algorithm", "spectrum" },
      { "window", "hann" }, { "size", 1024 } }), "NO_SELECTION");

   // Contrast: second second 40 dB quieter
   NewProject();
   id = Tone(2.0, 440, 1, 0.5);
   Select(1, 2, { id });
   APPLY("Amplify", { { "Ratio", 0.01 } });
   Select(0, 2, { id });
   auto ct = MUST("analyze.contrast", { { "foreground", { { "t0", 0.0 }, { "t1", 1.0 } } },
      { "background", { { "t0", 1.0 }, { "t1", 2.0 } } } });
   CHECK_MSG(Near(ct.value("foregroundDb", 0.0), -9.03, 0.1), ct.dump());
   CHECK_MSG(Near(ct.value("backgroundDb", 0.0), -49.03, 0.2), ct.dump());
   CHECK(Near(ct.value("differenceDb", 0.0), 40.0, 0.3));
   CHECK(ct.value("passes", false) == true);
   CHECK(ct.value("verdict", "") == "WCAG2 Pass");
   ct = MUST("analyze.contrast", { { "foreground", { { "t0", 1.0 }, { "t1", 2.0 } } },
      { "background", { { "t0", 0.0 }, { "t1", 1.0 } } } });
   CHECK(ct.value("passes", true) == false);
   // silence
   MUST("select.set", { { "t0", 2.5 }, { "t1", 3.0 }, { "trackIds", { id } } });
   auto silent = Call("analyze.contrast", { { "foreground", { { "t0", 0.0 }, { "t1", 1.0 } } },
      { "background", { { "t0", 2.5 }, { "t1", 3.0 } } } });
   CHECK(!Ok(silent));   // beyond the track end: "Nothing to measure"
   auto id2 = Tone(1.0);
   Select(0, 1, { id, id2 });
   EXPECT_ERROR(Call("analyze.contrast", { { "foreground", { { "t0", 0.0 }, { "t1", 1.0 } } },
      { "background", { { "t0", 1.0 }, { "t1", 2.0 } } } }), "NO_SELECTION");
   EXPECT_ERROR(Call("analyze.contrast", { { "foreground", 1 } }), "INVALID_ARGS");
}

// ===========================================================================
void TestCancel()
{
   std::fprintf(stderr, "-- cancel with rollback\n");
   NewProject();
   auto id = Tone(20.0, 440, 2, 0.5);
   Select(0, 20, { id });
   const auto before = Samples(id, 0, 1);
   const auto history = MUST("history.list");
   int cancelled = 0;
   gSink->onProgress = [&](const json &p) {
      if (p.value("phase", "") == "begin" && cancelled == 0) {
         ++cancelled;
         aubridge::CancelProgress(p.value("id", 0), false);
      }
   };
   auto r = Call("effects.apply", { { "id", Id("Paulstretch") },
      { "params", { { "Stretch Factor", 10.0 } } } });
   gSink->onProgress = nullptr;
   EXPECT_ERROR(r, "CANCELLED");
   CHECK(cancelled == 1);
   CHECK(Near(End(id), 20.0, 1e-6));
   CHECK(MaxDiff(Samples(id, 0, 1), before) < 1e-9);
   auto history2 = MUST("history.list");
   CHECK(history2["states"].size() == history["states"].size());
   CHECK(history2.value("current", -1) == history.value("current", -2));
   // lastEffect not updated by a cancelled run
   CHECK(!Snapshot()["lastEffect"].is_object() ||
      Str(Snapshot()["lastEffect"], "id") != Id("Paulstretch"));
   // the engine is still usable
   auto ok = APPLY("Invert");
   CHECK(ok.value("applied", false));
}

// ===========================================================================
void TestKorean()
{
   std::fprintf(stderr, "-- korean labels\n");
   MUST("settings.set", { { "settings", { { "language", "ko" } } } });
   auto d = Describe("Amplify");
   const auto label = ParamOf(d, "Ratio").value("label", "");
   CHECK_MSG(!label.empty() && label != "Amplification (dB)" &&
      label.find('&') == std::string::npos && label.back() != ':', label);
   CHECK(d.value("name", "") != "Amplify");
   if (std::getenv("EFFECTS_TEST_DUMP"))
      for (auto name : { "Amplify", "Reverb", "Noise Reduction", "Truncate Silence",
              "Sliding Stretch", "Loudness Normalization" }) {
         auto k = Describe(name);
         std::fprintf(stderr, "%s:", k.value("name", "").c_str());
         for (auto &p : k["params"])
            std::fprintf(stderr, " [%s|%s|%s]", p.value("label", "").c_str(),
               p.value("unit", "").c_str(),
               p.contains("choiceLabels") ? p["choiceLabels"].dump().c_str() : "");
         std::fprintf(stderr, "\n");
      }
   auto list = MUST("effects.list");
   // menu titles stay msgids (Kotlin translates them)
   CHECK(list["menus"]["effect"][0].value("title", "") == "Volume and Compression");
   MUST("settings.set", { { "settings", { { "language", "en" } } } });
   CHECK(ParamOf(Describe("Amplify"), "Ratio").value("label", "") == "Amplification (dB)");
}

bool CopyFile(const std::string &from, const std::string &to)
{
   std::error_code ec;
   std::filesystem::create_directories(std::filesystem::path(to).parent_path(), ec);
   return std::filesystem::copy_file(from, to,
      std::filesystem::copy_options::overwrite_existing, ec);
}

} // namespace

int main()
{
   TempDirs dirs;
   gSink = std::make_shared<Sink>();
   // Blocking dialogs (none expected): dismiss
   gSink->onBlockingDialog = [](const json &d) {
      std::fprintf(stderr, "  unexpected blocking dialog: %s\n", d.dump().c_str());
      aubridge::ReplyDialog(d.value("id", 0), -1);
   };
   CHECK(CopyFile(AUBRIDGE_TEST_AUDACITY_DIR "/locale/ko/LC_MESSAGES/audacity.mo",
      dirs.filesDir + "/audacity/locale/ko/LC_MESSAGES/audacity.mo"));
   const json extra{ { "nyquistDir", AUBRIDGE_TEST_AUDACITY_DIR "/nyquist" },
      { "pluginsDir", AUBRIDGE_TEST_AUDACITY_DIR "/plug-ins" } };
   if (!aubridge::Start(dirs.ConfigJson(extra), gSink)) {
      std::fprintf(stderr, "Start failed\n");
      return 1;
   }
   auto ready = gSink->WaitFor("engine.ready", 120s);
   if (!ready) {
      std::fprintf(stderr, "engine not ready: %s\n",
         gSink->Last("engine.failed").value_or(json()).dump().c_str());
      aubridge::Stop();
      return 1;
   }
   for (auto &check : (*ready)["selfChecks"]) {
      const auto name = check.value("name", "");
      if (name == "effects" || name == "nyquistRuntime")
         CHECK_MSG(check.value("ok", false), check.dump());
   }

   TestCatalogue();
   TestDescribeAll();
   TestSetParams();
   TestProcessEffects();
   TestGenerators();
   TestNyquist();
   TestNoiseReduction();
   TestEqualization();
   TestPreview();
   TestAnalyzers();
   TestCancel();
   TestKorean();

   // Stop + Start again in the same process: the effect objects are rebuilt
   aubridge::Stop();
   auto sink2 = std::make_shared<Sink>();
   gSink = sink2;
   if (aubridge::Start(dirs.ConfigJson(extra), sink2) &&
       sink2->WaitFor("engine.ready", 120s)) {
      auto list = Res(Call("effects.list"));
      CHECK(list["effects"].size() == gInfoById.size());
      NewProject();
      auto id = Tone(1.0);
      Select(0, 1, { id });
      auto r = APPLY("Amplify", { { "Ratio", 0.5 } });
      CHECK(r.value("applied", false));
      r = MUST("effects.apply", { { "id", Id("High-Pass Filter") } });
      CHECK(r.value("applied", false));
   }
   else
      CHECK_MSG(false, "restart failed");
   aubridge::Stop();

   if (Failures())
      std::fprintf(stderr, "bridge-test-effects: %d FAILED checks\n", Failures());
   else
      std::fprintf(stderr, "bridge-test-effects: all checks passed\n");
   return Failures() ? 1 : 0;
}
