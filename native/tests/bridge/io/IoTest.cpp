/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  IoTest.cpp

  Host test of the bridge "io" module (import.*, export.*), driven only
  through Bridge.h:
   * import.formats / export.formats (keys, uniqueness, MP3 without tags)
   * options sessions: tagged values, type/enum/range/read-only checks,
     MP3 bit-rate mode switching (visible options, sample-rate list),
     WavPack correction file forced off, PCM "Other" header switching
   * round trips: a stereo test tone exported to WAV 16/24/float, AIFF,
     MP3 (preset / VBR / CBR), Ogg Vorbis, Opus (48 kHz), FLAC 16/24,
     WavPack and MP2, then re-imported into a new project: duration,
     channels, rate, pitch and level; sample comparison for the lossless
     formats (exact for float), correlation for the lossy ones at 44.1 kHz
   * export.defaults, selection vs project range, skipSilenceAtStart,
     muted audio, staging-path rules
   * cancel / stop of export (partial file deleted / kept) and import
   * import: stereo WAV into a new project (name, rate adoption), several
     files (one undo entry each, sorted), tag merge like 3.7.9 and its undo,
     a chained Ogg file (multiChoice stream dialog: all / one / cancel),
     a legacy .aup whose <import> element goes through the bridge's
     lib-app-services import handler (mod-aup)
   * errors: unknown extension, corrupt file (FAILED with the library's
     message), missing file, .aup3, bad arguments
   * limits: WAVs declaring 65 / 1024 channels are refused without the
     memory the PCM importer would need (64 channels import); the first
     import adopts the file's rate as the project rate only within
     1000 ... 768000 Hz

  Exit code 0 on success.  BRIDGE_TEST_VERBOSE=1 prints the events.

**********************************************************************/
#include "BridgeTestSupport.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <thread>

#include <sys/resource.h>
#include <sys/stat.h>

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

std::shared_ptr<Sink> gSink;
TempDirs *gDirs = nullptr;
int gDirCounter = 0;

// ---- small helpers ---------------------------------------------------------

json ReqAt(int line, const std::string &command,
   const json &args = json::object())
{
   auto r = Call(command, args);
   CHECK_MSG(Ok(r), command + " (line " + std::to_string(line) + "): " +
      r.dump());
   return Ok(r) ? r["result"] : json::object();
}
#define REQ(...) ReqAt(__LINE__, __VA_ARGS__)

std::string Err(const std::string &command, const json &args = json::object())
{
   return ErrorCodeOf(Call(command, args));
}

std::string ErrMessage(const json &envelope)
{
   if (envelope.contains("error") && envelope["error"].is_object())
      return envelope["error"].value("message", "");
   return {};
}

json Snap()
{
   auto r = Call("project.snapshot");
   CHECK(Ok(r));
   return Ok(r) ? r["result"] : json::object();
}

bool FileExists(const std::string &path)
{
   struct stat st{};
   return ::stat(path.c_str(), &st) == 0;
}

long long FileSize(const std::string &path)
{
   struct stat st{};
   return ::stat(path.c_str(), &st) == 0 ? (long long)st.st_size : -1;
}

void MakeDirs(const std::string &path)
{
   std::string partial;
   std::stringstream ss(path);
   std::string item;
   while (std::getline(ss, item, '/')) {
      partial += item + "/";
      ::mkdir(partial.c_str(), 0700);
   }
}

//! A fresh staging path cacheDir/export/<n>/<name> (directory created)
std::string StagingPath(const std::string &name)
{
   const auto dir = gDirs->cacheDir + "/export/" + std::to_string(++gDirCounter);
   MakeDirs(dir);
   return dir + "/" + name;
}

//! A fresh import staging path cacheDir/import/<n>/<name>
std::string ImportPath(const std::string &name)
{
   const auto dir = gDirs->cacheDir + "/import/" + std::to_string(++gDirCounter);
   MakeDirs(dir);
   return dir + "/" + name;
}

std::string ReadFile(const std::string &path)
{
   std::ifstream in(path, std::ios::binary);
   std::stringstream ss;
   ss << in.rdbuf();
   return ss.str();
}

void WriteFile(const std::string &path, const std::string &bytes)
{
   std::ofstream out(path, std::ios::binary);
   out << bytes;
}

void CopyFile(const std::string &from, const std::string &to)
{
   WriteFile(to, ReadFile(from));
}

json Tagged(const char *t, json v)
{
   return json{ { "t", t }, { "v", std::move(v) } };
}

json OptionById(const json &options, int64_t id)
{
   for (const auto &o : options["options"])
      if (o.value("id", int64_t(-1)) == id)
         return o;
   return json();
}

std::vector<int> Rates(const json &options)
{
   std::vector<int> rates;
   for (const auto &r : options["sampleRates"])
      rates.push_back(r.get<int>());
   return rates;
}

json SetOpt(const std::string &key, int64_t id, const json &value)
{
   return REQ("export.setOption",
      json{ { "formatKey", key }, { "id", id }, { "value", value } });
}

std::string SetOptErr(const std::string &key, int64_t id, const json &value)
{
   return Err("export.setOption",
      json{ { "formatKey", key }, { "id", id }, { "value", value } });
}

json Export(const std::string &path, const std::string &key,
   const std::string &range, int channels, int rate, bool skipSilence = false)
{
   return Call("export.run", json{ { "path", path }, { "formatKey", key },
      { "range", range }, { "channels", channels }, { "rate", rate },
      { "skipSilenceAtStart", skipSilence } });
}

json ImportFiles(const std::vector<std::string> &paths, bool newProject)
{
   return Call("import.files",
      json{ { "paths", paths }, { "newProject", newProject } });
}

json WaveTracks(const json &snap)
{
   json result = json::array();
   for (const auto &t : snap["tracks"])
      if (t.value("kind", "") == "wave")
         result.push_back(t);
   return result;
}

json History()
{
   return REQ("history.list");
}

std::string CurrentHistoryDescription()
{
   auto h = History();
   const auto current = h.value("current", -1);
   for (const auto &s : h["states"])
      if (s.value("index", -2) == current)
         return s.value("description", "");
   return {};
}

size_t HistoryCount()
{
   return History()["states"].size();
}

std::map<std::string, std::string> ProjectTags()
{
   std::map<std::string, std::string> tags;
   const auto result = REQ("project.tags.get");   // keep the temporary alive
   for (const auto &t : result["tags"])
      tags[t.value("name", "")] = t.value("value", "");
   return tags;
}

// ---- WAV reader (what libsndfile writes: PCM 16/24/32, float, extensible)

struct Audio {
   int rate = 0;
   int channels = 0;
   std::vector<float> data;   // interleaved
   size_t Frames() const { return channels ? data.size() / size_t(channels) : 0; }
   float At(size_t frame, int ch) const { return data[frame * size_t(channels) + size_t(ch)]; }
};

uint32_t U32(const std::string &b, size_t pos)
{
   return uint32_t(uint8_t(b[pos])) | uint32_t(uint8_t(b[pos + 1])) << 8 |
      uint32_t(uint8_t(b[pos + 2])) << 16 | uint32_t(uint8_t(b[pos + 3])) << 24;
}

uint16_t U16(const std::string &b, size_t pos)
{
   return uint16_t(uint8_t(b[pos]) | uint8_t(b[pos + 1]) << 8);
}

std::optional<Audio> ReadWav(const std::string &path)
{
   const auto b = ReadFile(path);
   if (b.size() < 12 || b.compare(0, 4, "RIFF") != 0 ||
       b.compare(8, 4, "WAVE") != 0)
      return std::nullopt;
   Audio audio;
   int format = 0, bits = 0;
   size_t pos = 12;
   while (pos + 8 <= b.size()) {
      const auto id = b.substr(pos, 4);
      const size_t size = U32(b, pos + 4);
      const size_t body = pos + 8;
      if (id == "fmt " && body + 16 <= b.size()) {
         format = U16(b, body);
         audio.channels = U16(b, body + 2);
         audio.rate = int(U32(b, body + 4));
         bits = U16(b, body + 14);
         if (format == 0xFFFE && size >= 26)
            format = U16(b, body + 24);   // SubFormat GUID's first 2 bytes
      }
      else if (id == "data" && audio.channels > 0) {
         const size_t bytes = std::min(size, b.size() - body);
         const size_t width = size_t(bits / 8);
         if (width == 0)
            return std::nullopt;
         const size_t n = bytes / width;
         audio.data.resize(n);
         for (size_t i = 0; i < n; ++i) {
            const size_t p = body + i * width;
            if (format == 3 && bits == 32) {
               uint32_t u = U32(b, p);
               float f;
               std::memcpy(&f, &u, 4);
               audio.data[i] = f;
            }
            else if (format == 1 && bits == 16)
               audio.data[i] = int16_t(U16(b, p)) / 32768.0f;
            else if (format == 1 && bits == 24) {
               int32_t v = int32_t(uint32_t(uint8_t(b[p])) << 8 |
                  uint32_t(uint8_t(b[p + 1])) << 16 |
                  uint32_t(uint8_t(b[p + 2])) << 24) >> 8;
               audio.data[i] = v / 8388608.0f;
            }
            else if (format == 1 && bits == 32)
               audio.data[i] = int32_t(U32(b, p)) / 2147483648.0f;
            else
               return std::nullopt;
         }
         return audio;
      }
      pos = body + size + (size & 1);
   }
   return std::nullopt;
}

double Rms(const Audio &a, int ch, size_t from, size_t count)
{
   double sum = 0;
   size_t n = 0;
   for (size_t i = from; i < std::min(a.Frames(), from + count); ++i, ++n)
      sum += double(a.At(i, ch)) * a.At(i, ch);
   return n ? std::sqrt(sum / double(n)) : 0;
}

//! Frequency from rising zero crossings over [from, from + count)
double Frequency(const Audio &a, int ch, size_t from, size_t count)
{
   const size_t end = std::min(a.Frames(), from + count);
   long first = -1, last = -1, crossings = 0;
   for (size_t i = from + 1; i < end; ++i)
      if (a.At(i - 1, ch) < 0 && a.At(i, ch) >= 0) {
         if (first < 0)
            first = long(i);
         else
            ++crossings;
         last = long(i);
      }
   if (crossings < 1 || last <= first)
      return 0;
   return double(crossings) * a.rate / double(last - first);
}

//! Best normalized correlation of a window of `ref` with `x` over lags
double BestCorrelation(const Audio &ref, const Audio &x, int ch, size_t from,
   size_t length, int maxLag, int *bestLag = nullptr)
{
   double best = -2;
   for (int lag = -maxLag; lag <= maxLag; ++lag) {
      double sab = 0, saa = 0, sbb = 0;
      bool ok = true;
      for (size_t i = from; i < from + length; ++i) {
         const long j = long(i) + lag;
         if (i >= ref.Frames() || j < 0 || size_t(j) >= x.Frames()) {
            ok = false;
            break;
         }
         const double a = ref.At(i, ch), b = x.At(size_t(j), ch);
         sab += a * b;
         saa += a * a;
         sbb += b * b;
      }
      if (!ok || saa <= 0 || sbb <= 0)
         continue;
      const double c = sab / std::sqrt(saa * sbb);
      if (c > best) {
         best = c;
         if (bestLag)
            *bestLag = lag;
      }
   }
   return best;
}

// ---- progress / dialog automation -----------------------------------------

std::atomic<int> gProgressAction{ 0 };   // 0 none, 1 cancel, 2 stop
std::atomic<int> gProgressBegins{ 0 };

void InstallProgressHandler()
{
   gSink->onProgress = [](const json &p) {
      if (p.value("phase", "") != "begin")
         return;
      ++gProgressBegins;
      const int action = gProgressAction.load();
      if (action != 0)
         aubridge::CancelProgress(p.value("id", -1), action == 2);
   };
}

struct DialogScript {
   std::mutex mutex;
   //! nullopt: cancel (replyDialog -1)
   std::optional<std::vector<int>> reply;
   std::vector<json> seen;
};
DialogScript gDialogs;

void InstallDialogHandler()
{
   gSink->onBlockingDialog = [](const json &d) {
      std::lock_guard lock{ gDialogs.mutex };
      gDialogs.seen.push_back(d);
      const int id = d.value("id", -1);
      if (d.value("kind", "") == "multiChoice" && gDialogs.reply)
         aubridge::ReplyDialogChoices(id, *gDialogs.reply);
      else
         aubridge::ReplyDialog(id, -1);
   };
}

// ---- the test tone project ------------------------------------------------

constexpr double kToneSeconds = 2.0;
constexpr double kToneFrequency = 440.0;
constexpr double kToneAmplitude = 0.5;
const double kToneRms = kToneAmplitude / std::sqrt(2.0);

//! New project with one stereo test tone (44.1 kHz); returns the track id
int64_t NewToneProject(double seconds = kToneSeconds, int rate = 44100,
   int channels = 2, double frequency = kToneFrequency)
{
   REQ("project.new");
   auto r = REQ("debug.makeTestTrack", json{ { "seconds", seconds },
      { "frequency", frequency }, { "channels", channels }, { "rate", rate },
      { "amplitude", kToneAmplitude } });
   return r.value("id", int64_t(-1));
}

// ===========================================================================

void TestFormatLists()
{
   auto imp = REQ("import.formats");
   CHECK(imp["groups"].is_array() && imp["groups"].size() >= 6);
   std::set<std::string> all;
   for (const auto &e : imp["extensions"])
      all.insert(e.get<std::string>());
   for (const char *e : { "wav", "aiff", "mp3", "ogg", "opus", "flac", "wv" })
      CHECK_MSG(all.count(e), std::string("import extension ") + e);
   CHECK(!all.count("aup3"));
   bool pcmGroup = false;
   for (const auto &g : imp["groups"])
      if (g.value("description", "") == "WAV, AIFF, and other uncompressed types")
         pcmGroup = true;
   CHECK(pcmGroup);

   auto exp = REQ("export.formats");
   std::map<std::string, json> formats;
   for (const auto &f : exp["formats"]) {
      const auto key = f.value("key", "");
      CHECK_MSG(!formats.count(key), "duplicate export key " + key);
      formats[key] = f;
   }
   for (const char *key : { "WAV (Microsoft)", "Other uncompressed files",
           "MP3 Files", "Ogg Vorbis Files", "Opus Files", "FLAC Files",
           "WavPack Files", "MP2 Files" })
      CHECK_MSG(formats.count(key), std::string("export format ") + key);
   CHECK(formats["MP3 Files"].value("canMetaData", true) == false);
   CHECK(formats["MP3 Files"].value("maxChannels", 0) == 2);
   CHECK(formats["FLAC Files"].value("canMetaData", false) == true);
   CHECK(formats["WAV (Microsoft)"]["extensions"][0] == "wav");
   CHECK(formats["Opus Files"]["extensions"][0] == "opus");
}

void TestOptions()
{
   // WAV: one encoding option with the libsndfile major type as id
   auto wav = REQ("export.options", json{ { "formatKey", "WAV (Microsoft)" } });
   auto enc = OptionById(wav, 0x10000);
   CHECK(enc.value("type", "") == "enum");
   std::set<int> encodings;
   for (const auto &v : enc["values"]) {
      CHECK(v.value("t", "") == "i");
      encodings.insert(v["v"].get<int>());
   }
   CHECK(encodings.count(2) && encodings.count(3) && encodings.count(6));
   CHECK(enc["names"].size() == enc["values"].size());
   CHECK(Rates(wav).empty());

   // Tagged-value checks
   CHECK(SetOptErr("WAV (Microsoft)", 0x10000, Tagged("s", "2")) == "INVALID_ARGS");
   CHECK(SetOptErr("WAV (Microsoft)", 0x10000, Tagged("i", 0x7777)) == "INVALID_ARGS");
   CHECK(SetOptErr("WAV (Microsoft)", 0x10000, json{ { "v", 2 } }) == "INVALID_ARGS");
   CHECK(SetOptErr("WAV (Microsoft)", 0x10000, Tagged("x", 2)) == "INVALID_ARGS");
   CHECK(SetOptErr("WAV (Microsoft)", 12345, Tagged("i", 2)) == "INVALID_ARGS");
   CHECK(SetOptErr("No Such Format", 0, Tagged("i", 2)) == "NOT_FOUND");
   CHECK(Err("export.options", json{ { "formatKey", "nope" } }) == "NOT_FOUND");
   auto set = SetOpt("WAV (Microsoft)", 0x10000, Tagged("i", 3));
   CHECK(OptionById(set, 0x10000)["value"] == Tagged("i", 3));
   // the session is reloaded from the preferences: the value persists
   wav = REQ("export.options", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(OptionById(wav, 0x10000)["value"] == Tagged("i", 3));

   // MP3: mode switching changes the visible options and the rate list
   const std::string mp3 = "MP3 Files";
   auto o = REQ("export.options", json{ { "formatKey", mp3 } });
   CHECK(OptionById(o, 0)["value"] == Tagged("s", "SET"));
   CHECK(OptionById(o, 1).value("hidden", true) == false);
   CHECK(OptionById(o, 4).value("hidden", false) == true);
   CHECK(Rates(o) == (std::vector<int>{ 8000, 11025, 12000, 16000, 22050,
                                        24000, 32000, 44100, 48000 }));
   CHECK(SetOptErr(mp3, 0, Tagged("s", "XYZ")) == "INVALID_ARGS");
   CHECK(SetOptErr(mp3, 0, Tagged("i", 1)) == "INVALID_ARGS");
   o = SetOpt(mp3, 0, Tagged("s", "CBR"));
   CHECK(OptionById(o, 1).value("hidden", false) == true);
   CHECK(OptionById(o, 4).value("hidden", true) == false);
   CHECK(OptionById(o, 2).value("hidden", false) == true);
   o = SetOpt(mp3, 4, Tagged("i", 320));
   CHECK(Rates(o) == (std::vector<int>{ 32000, 44100, 48000 }));
   o = SetOpt(mp3, 4, Tagged("i", 16));
   CHECK(Rates(o) == (std::vector<int>{ 8000, 11025, 12000, 16000, 22050, 24000 }));
   CHECK(SetOptErr(mp3, 4, Tagged("i", 17)) == "INVALID_ARGS");
   o = SetOpt(mp3, 0, Tagged("s", "VBR"));
   CHECK(OptionById(o, 2).value("hidden", true) == false);
   CHECK(OptionById(o, 4).value("hidden", false) == true);
   CHECK(Rates(o).size() == 9);
   // export.defaults follows the session's rate list
   NewToneProject(0.5);
   SetOpt(mp3, 0, Tagged("s", "CBR"));
   o = SetOpt(mp3, 4, Tagged("i", 320));
   auto d = REQ("export.defaults", json{ { "formatKey", mp3 } });
   CHECK(d["rates"] == json::array({ 32000, 44100, 48000 }));
   CHECK(d.value("defaultRate", 0) == 44100);
   SetOpt(mp3, 4, Tagged("i", 16));
   d = REQ("export.defaults", json{ { "formatKey", mp3 } });
   // 44100 is not offered at 16 kbps: the highest rate is picked
   CHECK(d.value("defaultRate", 0) == 24000);
   SetOpt(mp3, 4, Tagged("i", 128));
   SetOpt(mp3, 0, Tagged("s", "SET"));

   // WavPack: the correction file is always off, hidden and read-only
   auto wv = REQ("export.options", json{ { "formatKey", "WavPack Files" } });
   auto corr = OptionById(wv, 3);
   CHECK(corr.value("hidden", false) && corr.value("readOnly", false));
   CHECK(corr["value"] == Tagged("b", false));
   CHECK(OptionById(wv, 4).value("readOnly", false) == true);   // bit rate
   wv = SetOpt("WavPack Files", 2, Tagged("b", true));          // hybrid
   CHECK(OptionById(wv, 4).value("readOnly", true) == false);
   CHECK(OptionById(wv, 3).value("readOnly", false) == true);
   CHECK(SetOptErr("WavPack Files", 3, Tagged("b", true)) == "INVALID_ARGS");
   SetOpt("WavPack Files", 2, Tagged("b", false));

   // Opus: restricted rates; range option
   auto opus = REQ("export.options", json{ { "formatKey", "Opus Files" } });
   CHECK(Rates(opus) == (std::vector<int>{ 8000, 12000, 16000, 24000, 48000 }));
   auto quality = OptionById(opus, 1);
   CHECK(quality.value("type", "") == "range");
   CHECK(SetOptErr("Opus Files", 1, Tagged("i", 11)) == "INVALID_ARGS");

   // FLAC uses strings
   auto flac = REQ("export.options", json{ { "formatKey", "FLAC Files" } });
   CHECK(OptionById(flac, 0)["value"]["t"] == "s");

   // PCM "Other": switching the header changes the format's extension
   const std::string other = "Other uncompressed files";
   auto oth = REQ("export.options", json{ { "formatKey", other } });
   CHECK(OptionById(oth, 0).value("type", "") == "enum");
   oth = SetOpt(other, 0, Tagged("i", 0x20000));   // AIFF
   CHECK(oth["format"]["extensions"][0] == "aiff");
   CHECK(OptionById(oth, 0x20000).value("hidden", true) == false);
   bool otherHidden = true;
   for (const auto &opt : oth["options"])
      if (opt.value("id", 0) != 0 && opt.value("id", 0) != 0x20000 &&
          !opt.value("hidden", false))
         otherHidden = false;
   CHECK(otherHidden);
   // export.formats reflects it too
   bool found = false;
   const auto formatList = REQ("export.formats");
   for (const auto &f : formatList["formats"])
      if (f.value("key", "") == other)
         found = f["extensions"][0] == "aiff";
   CHECK(found);
}

struct RoundTrip {
   const char *name;
   const char *key;
   const char *ext;
   std::vector<std::pair<int64_t, json>> options;
   int rate;
   bool lossless;
   double maxDiff;          // lossless: max |sample difference|
   double durationTolerance;
};

void TestRoundTrips()
{
   NewToneProject();
   auto snap = Snap();
   CHECK(WaveTracks(snap).size() == 1);

   // Reference: the tone as float WAV
   SetOpt("WAV (Microsoft)", 0x10000, Tagged("i", 6));
   const auto refPath = StagingPath("reference.wav");
   auto r = Export(refPath, "WAV (Microsoft)", "project", 2, 44100);
   CHECK_MSG(Ok(r), r.dump());
   CHECK(Ok(r) && r["result"].value("path", "") == refPath);
   auto ref = ReadWav(refPath);
   CHECK(ref.has_value());
   if (!ref)
      return;
   CHECK(ref->rate == 44100 && ref->channels == 2);
   CHECK(ref->Frames() == size_t(kToneSeconds * 44100));
   CHECK(std::fabs(Rms(*ref, 0, 0, ref->Frames()) - kToneRms) < 0.01);

   // Existing staging path refused, nothing overwritten
   const auto refSize = FileSize(refPath);
   CHECK(ErrorCodeOf(Export(refPath, "WAV (Microsoft)", "project", 2, 44100)) ==
      "INVALID_ARGS");
   CHECK(FileSize(refPath) == refSize);

   const std::vector<RoundTrip> cases{
      { "wav16", "WAV (Microsoft)", "wav", { { 0x10000, Tagged("i", 2) } }, 44100, true, 1e-3, 0 },
      { "wav24", "WAV (Microsoft)", "wav", { { 0x10000, Tagged("i", 3) } }, 44100, true, 1e-4, 0 },
      { "wavfloat", "WAV (Microsoft)", "wav", { { 0x10000, Tagged("i", 6) } }, 44100, true, 1e-6, 0 },
      { "aiff16", "Other uncompressed files", "aiff",
        { { 0, Tagged("i", 0x20000) }, { 0x20000, Tagged("i", 2) } }, 44100, true, 1e-3, 0 },
      { "mp3set", "MP3 Files", "mp3", { { 0, Tagged("s", "SET") }, { 1, Tagged("i", 2) } }, 44100, false, 0, 0.1 },
      { "mp3vbr", "MP3 Files", "mp3", { { 0, Tagged("s", "VBR") }, { 2, Tagged("i", 2) } }, 44100, false, 0, 0.1 },
      { "mp3cbr", "MP3 Files", "mp3", { { 0, Tagged("s", "CBR") }, { 4, Tagged("i", 128) } }, 44100, false, 0, 0.1 },
      { "ogg", "Ogg Vorbis Files", "ogg", { { 0, Tagged("i", 5) } }, 44100, false, 0, 0.02 },
      { "opus", "Opus Files", "opus", {}, 48000, false, 0, 0.05 },
      { "flac16", "FLAC Files", "flac", { { 0, Tagged("s", "16") } }, 44100, true, 1e-3, 0 },
      { "flac24", "FLAC Files", "flac", { { 0, Tagged("s", "24") } }, 44100, true, 1e-4, 0 },
      { "wavpack", "WavPack Files", "wv", { { 1, Tagged("i", 16) } }, 44100, true, 1e-3, 0 },
      { "mp2", "MP2 Files", "mp2", {}, 44100, false, 0, 0.1 },
   };

   std::vector<std::string> exported;
   for (const auto &c : cases) {
      const std::string label = c.name;
      for (const auto &[id, value] : c.options)
         SetOpt(c.key, id, value);
      const auto path = StagingPath(std::string("tone.") + c.ext);
      auto e = Export(path, c.key, "project", 2, c.rate);
      CHECK_MSG(Ok(e), label + ": " + e.dump());
      CHECK_MSG(FileSize(path) > 1000, label + " output size");
      exported.push_back(path);
   }

   // Re-import each into a new project, render back to float WAV, compare
   SetOpt("WAV (Microsoft)", 0x10000, Tagged("i", 6));
   for (size_t i = 0; i < cases.size(); ++i) {
      const auto &c = cases[i];
      const std::string label = c.name;
      const auto before = Snap().value("generation", uint64_t(0));
      auto im = ImportFiles({ exported[i] }, true);
      CHECK_MSG(Ok(im), label + " import: " + im.dump());
      if (!Ok(im))
         continue;
      CHECK_MSG(im["result"]["trackIds"].size() == 1, label + ": " + im.dump());
      auto s = Snap();
      CHECK(s.value("generation", uint64_t(0)) > before);
      auto tracks = WaveTracks(s);
      CHECK_MSG(tracks.size() == 1, label);
      if (tracks.size() != 1)
         continue;
      const auto &t = tracks[0];
      CHECK_MSG(t.value("channels", 0) == 2, label + " channels");
      CHECK_MSG(t.value("rate", 0) == c.rate, label + " rate " + t.dump());
      CHECK_MSG(t.value("name", "") == "tone", label + " name " + t.value("name", ""));
      CHECK_MSG(s["project"].value("name", "") == "tone", label + " project name");
      CHECK_MSG(s["project"].value("rate", 0.0) == double(c.rate),
         label + " project rate adopted");
      const double duration = t.value("end", 0.0) - t.value("start", 0.0);
      if (c.lossless)
         CHECK_MSG(std::fabs(duration - kToneSeconds) < 1e-6,
            label + " duration " + std::to_string(duration));
      else
         CHECK_MSG(duration >= kToneSeconds - 0.01 &&
            duration <= kToneSeconds + c.durationTolerance,
            label + " duration " + std::to_string(duration));
      CHECK_MSG(CurrentHistoryDescription() ==
         std::string("Imported 'tone.") + c.ext + "'", label + " history");

      const auto back = StagingPath(label + "-back.wav");
      auto e = Export(back, "WAV (Microsoft)", "project", 2, c.rate);
      CHECK_MSG(Ok(e), label + " re-export: " + e.dump());
      auto x = ReadWav(back);
      CHECK_MSG(x.has_value(), label + " re-export readable");
      if (!x)
         continue;
      CHECK(x->rate == c.rate && x->channels == 2);
      // pitch and level
      const size_t mid = size_t(0.5 * c.rate);
      for (int ch = 0; ch < 2; ++ch) {
         const double f = Frequency(*x, ch, mid, size_t(c.rate));
         CHECK_MSG(std::fabs(f - kToneFrequency) < 2.0,
            label + " frequency " + std::to_string(f));
         const double rms = Rms(*x, ch, mid, size_t(c.rate));
         CHECK_MSG(std::fabs(rms - kToneRms) < 0.15 * kToneRms,
            label + " rms " + std::to_string(rms));
      }
      if (c.lossless) {
         CHECK_MSG(x->Frames() == ref->Frames(), label + " frames");
         double maxDiff = 0;
         for (size_t k = 0; k < std::min(x->data.size(), ref->data.size()); ++k)
            maxDiff = std::max(maxDiff, double(std::fabs(x->data[k] - ref->data[k])));
         CHECK_MSG(maxDiff <= c.maxDiff,
            label + " max diff " + std::to_string(maxDiff));
         std::printf("  %-9s %d Hz, %.6f s, max diff %.2g\n", c.name, c.rate,
            duration, maxDiff);
      }
      else if (c.rate == 44100) {
         int lag = 0;
         const double corr = BestCorrelation(*ref, *x, 0, 30000, 4096, 3000, &lag);
         CHECK_MSG(corr > 0.95, label + " correlation " + std::to_string(corr) +
            " at lag " + std::to_string(lag));
         std::printf("  %-9s %d Hz, %.6f s, correlation %.5f at lag %d\n",
            c.name, c.rate, duration, corr, lag);
      }
      else
         std::printf("  %-9s %d Hz, %.6f s\n", c.name, c.rate, duration);
   }
}

void TestRangesAndDefaults()
{
   const auto id = NewToneProject(2.0, 44100, 2);
   auto d = REQ("export.defaults", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(d.value("hasSelection", true) == false);
   CHECK(d.value("defaultChannels", 0) == 2);
   CHECK(d.value("maxChannels", 0) == 2);
   CHECK(d.value("defaultRate", 0) == 44100);
   auto rates = d["rates"];
   CHECK(std::find(rates.begin(), rates.end(), json(44100)) != rates.end());
   CHECK(std::find(rates.begin(), rates.end(), json(192000)) != rates.end());
   d = REQ("export.defaults", json{ { "formatKey", "Opus Files" } });
   CHECK(d.value("defaultRate", 0) == 48000);
   CHECK(d["rates"] == json::array({ 8000, 12000, 16000, 24000, 48000 }));
   d = REQ("export.defaults", json{ { "formatKey", "MP2 Files" } });
   CHECK(d.value("defaultRate", 0) == 44100);
   CHECK(d.value("maxChannels", 0) == 2);

   // Selection export without a selection
   CHECK(ErrorCodeOf(Export(StagingPath("sel.wav"), "WAV (Microsoft)",
      "selection", 2, 44100)) == "NO_SELECTION");

   // Selection [0.5, 1.25] of the selected track
   REQ("select.set", json{ { "t0", 0.5 }, { "t1", 1.25 }, { "trackIds", { id } } });
   d = REQ("export.defaults", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(d.value("hasSelection", false) == true);
   const auto selPath = StagingPath("sel.wav");
   auto r = Export(selPath, "WAV (Microsoft)", "selection", 1, 22050);
   CHECK_MSG(Ok(r), r.dump());
   auto sel = ReadWav(selPath);
   CHECK(sel.has_value());
   if (sel) {
      CHECK(sel->channels == 1 && sel->rate == 22050);
      CHECK(std::llabs((long long)sel->Frames() - (long long)(0.75 * 22050)) <= 1);
      // debug.makeTestTrack's stereo tone is sine (left) + cosine (right);
      // the mono mix-down averages them: amplitude 0.5 / sqrt(2)
      const double rms = Rms(*sel, 0, 0, sel->Frames());
      CHECK_MSG(std::fabs(rms - kToneRms / std::sqrt(2.0)) < 0.01,
         "mono rms " + std::to_string(rms));
   }
   // The same at the track rate: sample by sample (L + R) / 2 of the
   // project export from 0.5 s on
   const auto selPath44 = StagingPath("sel44.wav");
   r = Export(selPath44, "WAV (Microsoft)", "selection", 1, 44100);
   CHECK_MSG(Ok(r), r.dump());
   const auto stereoPath = StagingPath("stereo.wav");
   CHECK(Ok(Export(stereoPath, "WAV (Microsoft)", "project", 2, 44100)));
   auto sel44 = ReadWav(selPath44);
   auto stereo = ReadWav(stereoPath);
   CHECK(sel44 && stereo);
   if (sel44 && stereo) {
      CHECK(sel44->Frames() == size_t(0.75 * 44100));
      double maxDiff = 0;
      for (size_t i = 0; i < sel44->Frames(); ++i) {
         const size_t j = i + 22050;
         if (j >= stereo->Frames())
            break;
         const double expected = 0.5 * (stereo->At(j, 0) + stereo->At(j, 1));
         maxDiff = std::max(maxDiff, std::fabs(expected - sel44->At(i, 0)));
      }
      CHECK_MSG(maxDiff < 1e-3, "mono mix-down diff " + std::to_string(maxDiff));
   }
   // Project export ignores the selection
   const auto projPath = StagingPath("proj.wav");
   r = Export(projPath, "WAV (Microsoft)", "project", 2, 44100);
   CHECK_MSG(Ok(r), r.dump());
   auto proj = ReadWav(projPath);
   CHECK(proj && proj->Frames() == size_t(2.0 * 44100));
   // The rate of the last successful export is the project's preferred
   // export rate (ExportAudioDialog::OnExport)
   d = REQ("export.defaults", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(d.value("defaultRate", 0) == 44100);
   r = Export(StagingPath("r.wav"), "WAV (Microsoft)", "project", 2, 32000);
   CHECK(Ok(r));
   d = REQ("export.defaults", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(d.value("defaultRate", 0) == 32000);
   // Opus has no 32000: the smallest rate above it
   d = REQ("export.defaults", json{ { "formatKey", "Opus Files" } });
   CHECK(d.value("defaultRate", 0) == 48000);

   // skipSilenceAtStart: move the clip to 1.0 s
   auto snap = Snap();
   REQ("clips.move", json{ { "trackId", id }, { "clipIndex", 0 },
      { "generation", snap["generation"] }, { "newStart", 1.0 } });
   const auto fullPath = StagingPath("full.wav");
   CHECK(Ok(Export(fullPath, "WAV (Microsoft)", "project", 2, 44100)));
   const auto skipPath = StagingPath("skip.wav");
   CHECK(Ok(Export(skipPath, "WAV (Microsoft)", "project", 2, 44100, true)));
   auto full = ReadWav(fullPath), skip = ReadWav(skipPath);
   CHECK(full && full->Frames() == size_t(3.0 * 44100));
   CHECK(skip && skip->Frames() == size_t(2.0 * 44100));
   if (skip)
      CHECK(Rms(*skip, 0, 0, 4410) > 0.3);   // starts with the tone

   // Argument checks
   CHECK(ErrorCodeOf(Export("relative/x.wav", "WAV (Microsoft)", "project", 2, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(gDirs->cacheDir + "/no/such/dir/x.wav", "WAV (Microsoft)", "project", 2, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(StagingPath("x.wav"), "Nope", "project", 2, 44100)) == "NOT_FOUND");
   CHECK(ErrorCodeOf(Export(StagingPath("x.wav"), "WAV (Microsoft)", "all", 2, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(StagingPath("x.wav"), "WAV (Microsoft)", "project", 3, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(StagingPath("x.wav"), "WAV (Microsoft)", "project", 0, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(StagingPath("x.opus"), "Opus Files", "project", 2, 44100)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(Export(StagingPath("x.mp2"), "MP2 Files", "project", 2, 22050)) == "INVALID_ARGS");

   // All audio muted
   REQ("tracks.setMute", json{ { "id", id }, { "mute", true } });
   auto muted = Export(StagingPath("m.wav"), "WAV (Microsoft)", "project", 2, 44100);
   CHECK(ErrorCodeOf(muted) == "FAILED");
   CHECK(ErrMessage(muted) == "All audio is muted.");
   REQ("tracks.setMute", json{ { "id", id }, { "mute", false } });

   // Empty project
   REQ("project.new");
   auto empty = Export(StagingPath("e.wav"), "WAV (Microsoft)", "project", 2, 44100);
   CHECK(ErrorCodeOf(empty) == "FAILED");
}

void TestCancelAndStop()
{
   // A long tone so that the export certainly polls its progress
   NewToneProject(60.0, 44100, 2);
   SetOpt("MP3 Files", 0, Tagged("s", "SET"));
   const auto dir = StagingPath("cancel.mp3");
   gProgressAction = 1;
   auto r = Export(dir, "MP3 Files", "project", 2, 44100);
   gProgressAction = 0;
   CHECK_MSG(ErrorCodeOf(r) == "CANCELLED", r.dump());
   CHECK_MSG(!FileExists(dir), "cancelled export output deleted");

   const auto stopPath = StagingPath("stop.mp3");
   gProgressAction = 2;
   r = Export(stopPath, "MP3 Files", "project", 2, 44100);
   gProgressAction = 0;
   CHECK_MSG(Ok(r), r.dump());
   CHECK(Ok(r) && r["result"].value("stopped", false) == true);
   CHECK(FileExists(stopPath));
   CHECK(FileSize(stopPath) < 60LL * 128000 / 8);   // well below a full file

   // Import cancel / stop of a long float WAV
   SetOpt("WAV (Microsoft)", 0x10000, Tagged("i", 6));
   const auto longWav = StagingPath("long.wav");
   CHECK(Ok(Export(longWav, "WAV (Microsoft)", "project", 2, 44100)));
   REQ("project.new");
   const auto states = HistoryCount();
   const auto gen = Snap().value("generation", uint64_t(0));
   gProgressAction = 1;
   r = ImportFiles({ longWav }, false);
   gProgressAction = 0;
   CHECK_MSG(ErrorCodeOf(r) == "CANCELLED", r.dump());
   auto s = Snap();
   CHECK(WaveTracks(s).empty());
   CHECK(HistoryCount() == states);
   CHECK(s.value("generation", uint64_t(0)) >= gen);

   gProgressAction = 2;
   r = ImportFiles({ longWav }, false);
   gProgressAction = 0;
   CHECK_MSG(Ok(r), r.dump());
   s = Snap();
   auto tracks = WaveTracks(s);
   CHECK(tracks.size() == 1);
   if (tracks.size() == 1) {
      const double duration = tracks[0].value("end", 0.0);
      CHECK_MSG(duration > 0 && duration < 60.0,
         "stopped import keeps the beginning: " + std::to_string(duration));
   }
   CHECK(CurrentHistoryDescription() == "Imported 'long.wav'");

   // A cancelled newProject import leaves the current project alone
   const auto keepGen = Snap().value("generation", uint64_t(0));
   gProgressAction = 1;
   r = ImportFiles({ longWav }, true);
   gProgressAction = 0;
   CHECK(ErrorCodeOf(r) == "CANCELLED");
   s = Snap();
   CHECK(WaveTracks(s).size() == 1);
   CHECK(s.value("generation", uint64_t(0)) >= keepGen);
}

void TestImport()
{
   // A stereo 48 kHz WAV with tags, imported into a new project
   NewToneProject(1.5, 48000, 2);
   REQ("project.tags.set", json{ { "tags", json::array({
      json{ { "name", "TITLE" }, { "value", "Tone Title" } },
      json{ { "name", "ARTIST" }, { "value", "Tone Artist" } } }) } });
   SetOpt("WAV (Microsoft)", 0x10000, Tagged("i", 2));
   const auto wavOut = StagingPath("out.wav");
   CHECK(Ok(Export(wavOut, "WAV (Microsoft)", "project", 2, 48000)));
   SetOpt("FLAC Files", 0, Tagged("s", "16"));
   const auto flacOut = StagingPath("out.flac");
   CHECK(Ok(Export(flacOut, "FLAC Files", "project", 2, 48000)));
   // A mono 22.05 kHz file
   NewToneProject(0.5, 22050, 1);
   const auto monoOut = StagingPath("mono.wav");
   CHECK(Ok(Export(monoOut, "WAV (Microsoft)", "project", 1, 22050)));

   // Staged like Kotlin does: cacheDir/import/<n>/<display name>
   const auto stereo = ImportPath("My Song.wav");
   CopyFile(wavOut, stereo);
   REQ("project.new");
   REQ("project.setRate", json{ { "rate", 44100 } });
   auto r = ImportFiles({ stereo }, true);
   CHECK_MSG(Ok(r), r.dump());
   auto s = Snap();
   auto tracks = WaveTracks(s);
   CHECK(tracks.size() == 1);
   if (tracks.size() == 1) {
      CHECK(tracks[0].value("channels", 0) == 2);
      CHECK(tracks[0].value("rate", 0) == 48000);
      CHECK(tracks[0].value("name", "") == "My Song");
      CHECK(tracks[0].value("selected", false) == true);
      CHECK(tracks[0].value("focused", false) == true);
      CHECK(std::fabs(tracks[0].value("end", 0.0) - 1.5) < 1e-6);
      CHECK(tracks[0]["clips"][0].value("name", "") == "My Song");
      CHECK(r["result"]["trackIds"] == json::array({ tracks[0]["id"] }));
   }
   CHECK(s["project"].value("name", "") == "My Song");
   CHECK(s["project"].value("rate", 0.0) == 48000.0);
   CHECK(s["project"].value("temporary", false) == true);
   CHECK(CurrentHistoryDescription() == "Imported 'My Song.wav'");
   CHECK(r["result"]["messages"].empty());
   // tags of the WAV (PCM merges into the project's tags)
   auto tags = ProjectTags();
   CHECK(tags["TITLE"] == "Tone Title");
   CHECK(tags["ARTIST"] == "Tone Artist");

   // A second import (no new project): the rate stays, the name stays, the
   // earlier tracks are deselected, one undo state per file, sorted names
   const auto b = ImportPath("b.wav");
   const auto a = ImportPath("A.wav");
   CopyFile(monoOut, b);
   CopyFile(monoOut, a);
   const auto before = HistoryCount();
   r = ImportFiles({ b, a }, false);
   CHECK_MSG(Ok(r), r.dump());
   s = Snap();
   tracks = WaveTracks(s);
   CHECK(tracks.size() == 3);
   if (tracks.size() == 3) {
      CHECK(tracks[0].value("selected", true) == false);
      CHECK(tracks[1].value("name", "") == "A");   // sorted: A before b
      CHECK(tracks[2].value("name", "") == "b");
      CHECK(tracks[1].value("channels", 0) == 1 && tracks[1].value("rate", 0) == 22050);
      CHECK(tracks[2].value("selected", false) == true);
      CHECK(tracks[2].value("focused", false) == true);
      CHECK(r["result"]["trackIds"] ==
         json::array({ tracks[1]["id"], tracks[2]["id"] }));
   }
   CHECK(HistoryCount() == before + 2);
   CHECK(CurrentHistoryDescription() == "Imported 'b.wav'");
   CHECK(s["project"].value("rate", 0.0) == 48000.0);
   CHECK(s["project"].value("name", "") == "My Song");
   REQ("history.undo");
   CHECK(WaveTracks(Snap()).size() == 2);
   REQ("history.redo");

   // FLAC replaces the project's tags (its importer clears them) and the
   // undo of the import restores the old ones
   REQ("project.tags.set", json{ { "tags", json::array({
      json{ { "name", "ALBUM" }, { "value", "Old Album" } } }) } });
   const auto flac = ImportPath("tagged.flac");
   CopyFile(flacOut, flac);
   r = ImportFiles({ flac }, false);
   CHECK_MSG(Ok(r), r.dump());
   tags = ProjectTags();
   CHECK(tags["TITLE"] == "Tone Title");
   CHECK(!tags.count("ALBUM"));
   REQ("history.undo");
   tags = ProjectTags();
   CHECK(tags["ALBUM"] == "Old Album");
   CHECK(!tags.count("TITLE"));
   // WAV merges: ALBUM kept, TITLE/ARTIST added
   const auto wav2 = ImportPath("merge.wav");
   CopyFile(wavOut, wav2);
   CHECK(Ok(ImportFiles({ wav2 }, false)));
   tags = ProjectTags();
   CHECK(tags["ALBUM"] == "Old Album" && tags["TITLE"] == "Tone Title");

   // Solo in the project mutes the imported tracks (bug 2109)
   REQ("project.new");
   auto tone = REQ("debug.makeTestTrack", json{ { "seconds", 0.5 } });
   REQ("tracks.setSolo", json{ { "id", tone["id"] }, { "solo", true } });
   const auto soloed = ImportPath("muted.wav");
   CopyFile(monoOut, soloed);
   r = ImportFiles({ soloed }, false);
   CHECK_MSG(Ok(r), r.dump());
   tracks = WaveTracks(Snap());
   CHECK(tracks.size() == 2 && tracks[1].value("mute", false) == true);
   // ... and the project, not empty before, keeps its name and rate
   CHECK(Snap()["project"].value("name", "") != "muted");

   // Batch with a failing file: the good one is imported, the message names
   // the bad one
   REQ("project.new");
   const auto bad = ImportPath("zz-broken.wav");
   WriteFile(bad, std::string("RIFF\x24\x00\x00\x00WAVEjunk", 16) +
      std::string(200, 'x'));
   const auto good = ImportPath("good.wav");
   CopyFile(monoOut, good);
   r = ImportFiles({ bad, good }, false);
   CHECK_MSG(Ok(r), r.dump());
   CHECK(r["result"]["trackIds"].size() == 1);
   CHECK(r["result"]["messages"].size() == 1);
   if (r["result"]["messages"].size() == 1) {
      const auto message = r["result"]["messages"][0].get<std::string>();
      CHECK_MSG(message.find("zz-broken.wav") != std::string::npos, message);
      CHECK_MSG(message.find(gDirs->cacheDir) == std::string::npos, message);
   }
}

//! Serial number of the first Ogg page
uint32_t OggSerial(const std::string &path)
{
   const auto b = ReadFile(path);
   if (b.size() < 18 || b.compare(0, 4, "OggS") != 0)
      return 0;
   return U32(b, 14);
}

void TestMultiStream()
{
   // Two Ogg Vorbis links: stereo 1.0 s at 440 Hz, mono 0.5 s at 880 Hz
   SetOpt("Ogg Vorbis Files", 0, Tagged("i", 5));
   NewToneProject(1.0, 44100, 2, 440);
   const auto first = StagingPath("a.ogg");
   CHECK(Ok(Export(first, "Ogg Vorbis Files", "project", 2, 44100)));
   NewToneProject(0.5, 44100, 1, 880);
   std::string second;
   // ExportOGG seeds rand() with time(NULL): a chain needs another serial
   for (int attempt = 0; attempt < 4; ++attempt) {
      second = StagingPath("b.ogg");
      CHECK(Ok(Export(second, "Ogg Vorbis Files", "project", 1, 44100)));
      if (OggSerial(second) != OggSerial(first))
         break;
      std::this_thread::sleep_for(1100ms);
   }
   CHECK(OggSerial(second) != OggSerial(first) && OggSerial(first) != 0);
   const auto chain = ImportPath("chain.ogg");
   WriteFile(chain, ReadFile(first) + ReadFile(second));

   auto dialogs = [] {
      std::lock_guard lock{ gDialogs.mutex };
      return gDialogs.seen;
   };
   auto setReply = [](std::optional<std::vector<int>> reply) {
      std::lock_guard lock{ gDialogs.mutex };
      gDialogs.reply = std::move(reply);
      gDialogs.seen.clear();
   };

   // Both links
   REQ("project.new");
   setReply(std::vector<int>{ 0, 1 });
   auto r = ImportFiles({ chain }, false);
   CHECK_MSG(Ok(r), r.dump());
   auto seen = dialogs();
   CHECK(seen.size() == 1);
   if (seen.size() == 1) {
      CHECK(seen[0].value("kind", "") == "multiChoice");
      CHECK(seen[0]["choices"].size() == 2);
      CHECK(seen[0]["defaultChecked"] == json::array({ true, true }));
      CHECK(seen[0].value("title", "") == "Select stream(s) to import");
   }
   auto tracks = WaveTracks(Snap());
   CHECK(tracks.size() == 2);
   if (tracks.size() == 2) {
      CHECK(tracks[0].value("name", "") == "chain 1");
      CHECK(tracks[1].value("name", "") == "chain 2");
      CHECK(tracks[0].value("channels", 0) == 2);
      CHECK(tracks[1].value("channels", 0) == 1);
      CHECK(std::fabs(tracks[0].value("end", 0.0) - 1.0) < 0.01);
      CHECK(std::fabs(tracks[1].value("end", 0.0) - 0.5) < 0.01);
   }

   // Only the second link (3.7.9's OGG importer would crash on an unused
   // link; the bridge decodes both and drops the first)
   REQ("project.new");
   setReply(std::vector<int>{ 1 });
   r = ImportFiles({ chain }, false);
   CHECK_MSG(Ok(r), r.dump());
   tracks = WaveTracks(Snap());
   CHECK(tracks.size() == 1);
   if (tracks.size() == 1) {
      CHECK(tracks[0].value("name", "") == "chain");
      CHECK(tracks[0].value("channels", 0) == 1);
      CHECK(std::fabs(tracks[0].value("end", 0.0) - 0.5) < 0.01);
   }
   // Only the first link
   REQ("project.new");
   setReply(std::vector<int>{ 0 });
   r = ImportFiles({ chain }, false);
   CHECK_MSG(Ok(r), r.dump());
   tracks = WaveTracks(Snap());
   CHECK(tracks.size() == 1 && tracks[0].value("channels", 0) == 2);

   // Cancel and "none"
   REQ("project.new");
   const auto states = HistoryCount();
   setReply(std::nullopt);
   r = ImportFiles({ chain }, false);
   CHECK_MSG(ErrorCodeOf(r) == "CANCELLED", r.dump());
   setReply(std::vector<int>{});
   r = ImportFiles({ chain }, false);
   CHECK_MSG(ErrorCodeOf(r) == "CANCELLED", r.dump());
   CHECK(WaveTracks(Snap()).empty());
   CHECK(HistoryCount() == states);
   setReply(std::vector<int>{ 0, 1 });
}

//! A legacy (Audacity 2.x) project whose only content is an <import>
//! element: mod-aup imports the file through lib-app-services'
//! ProjectFileManager::Import, i.e. the bridge's import handler
void TestLegacyAup()
{
   NewToneProject(0.5, 44100, 1);
   const auto wav = StagingPath("tone.wav");
   CHECK(Ok(Export(wav, "WAV (Microsoft)", "project", 1, 44100)));
   const auto aup = ImportPath("legacy.aup");
   const auto dir = aup.substr(0, aup.rfind('/'));
   MakeDirs(dir + "/legacy_data");
   CopyFile(wav, dir + "/legacy_data/tone.wav");
   WriteFile(aup,
      "<?xml version=\"1.0\" standalone=\"no\" ?>\n"
      "<project xmlns=\"http://audacity.sourceforge.net/xml/\" "
      "projname=\"legacy_data\" version=\"1.3.0\" audacityversion=\"2.4.2\" "
      "rate=\"22050\">\n"
      "  <import filename=\"tone.wav\" offset=\"0.5\"/>\n"
      "</project>\n");
   REQ("project.new");
   const auto states = HistoryCount();
   auto r = ImportFiles({ aup }, false);
   CHECK_MSG(Ok(r), r.dump());
   auto s = Snap();
   auto tracks = WaveTracks(s);
   CHECK(tracks.size() == 1);
   if (tracks.size() == 1) {
      CHECK(tracks[0].value("name", "") == "tone");
      CHECK(std::fabs(tracks[0].value("start", 0.0) - 0.5) < 1e-6);
      CHECK(std::fabs(tracks[0].value("end", 0.0) - 1.0) < 1e-6);
      CHECK(r["result"]["trackIds"] == json::array({ tracks[0]["id"] }));
   }
   // one undo state for the .aup (the nested import pushes none)
   CHECK(HistoryCount() == states + 1);
   CHECK(CurrentHistoryDescription() == "Imported 'legacy.aup'");
   CHECK(s["project"].value("name", "") == "legacy");
   // mod-aup applies the project rate to a clean project
   CHECK(s["project"].value("rate", 0.0) == 22050.0);
   REQ("history.undo");
   CHECK(WaveTracks(Snap()).empty());

   // A missing data folder: FAILED, nothing added
   const auto broken = ImportPath("broken.aup");
   WriteFile(broken,
      "<?xml version=\"1.0\" standalone=\"no\" ?>\n"
      "<project projname=\"nowhere_data\" version=\"1.3.0\" "
      "audacityversion=\"2.4.2\">\n</project>\n");
   REQ("project.new");
   r = ImportFiles({ broken }, false);
   CHECK_MSG(ErrorCodeOf(r) == "FAILED", r.dump());
   CHECK(WaveTracks(Snap()).empty());
}

void TestImportErrors()
{
   REQ("project.new");
   const auto states = HistoryCount();
   CHECK(Err("import.files", json{ { "paths", json::array() } }) == "INVALID_ARGS");
   CHECK(Err("import.files", json::object()) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(ImportFiles({ "relative.wav" }, false)) == "INVALID_ARGS");
   CHECK(ErrorCodeOf(ImportFiles({ gDirs->cacheDir + "/import/missing.wav" }, false)) == "NOT_FOUND");
   const auto project = ImportPath("p.aup3");
   WriteFile(project, "SQLite format 3");
   CHECK(ErrorCodeOf(ImportFiles({ project }, false)) == "INVALID_ARGS");

   // Unknown extension, text content (no MP3 sync bytes, no "f-i-l-e" word)
   const auto unknown = ImportPath("notes.xyz");
   WriteFile(unknown, std::string(4096, 'q'));
   auto r = ImportFiles({ unknown }, false);
   CHECK_MSG(ErrorCodeOf(r) == "FAILED", r.dump());
   CHECK_MSG(ErrMessage(r).find("notes.xyz") != std::string::npos, r.dump());
   CHECK_MSG(ErrMessage(r).find("/import/") == std::string::npos, r.dump());

   // Corrupt WAV: the library's message
   const auto corrupt = ImportPath("corrupt.wav");
   WriteFile(corrupt, std::string("RIFF\x10\x00\x00\x00WAVEfmt ", 16) +
      std::string(64, 'z'));
   r = ImportFiles({ corrupt }, false);
   CHECK_MSG(ErrorCodeOf(r) == "FAILED", r.dump());
   CHECK_MSG(ErrMessage(r).find("corrupt.wav") != std::string::npos, r.dump());
   // Nothing changed, also not with newProject
   r = ImportFiles({ corrupt }, true);
   CHECK(ErrorCodeOf(r) == "FAILED");
   CHECK(WaveTracks(Snap()).empty());
   CHECK(HistoryCount() == states);

   // No project
   REQ("project.close");
   CHECK(ErrorCodeOf(ImportFiles({ corrupt }, false)) == "NO_PROJECT");
   CHECK(Err("export.defaults", json{ { "formatKey", "WAV (Microsoft)" } }) == "NO_PROJECT");
   // import.formats / export.formats / options work without a project
   CHECK(Ok(Call("import.formats")));
   CHECK(Ok(Call("export.formats")));
   CHECK(Ok(Call("export.options", json{ { "formatKey", "FLAC Files" } })));
   // newProject works without one
   NewToneProject(0.25, 44100, 1);
   const auto out = StagingPath("np.wav");
   CHECK(Ok(Export(out, "WAV (Microsoft)", "project", 1, 44100)));
   REQ("project.close");
   const auto staged = ImportPath("np.wav");
   CopyFile(out, staged);
   r = ImportFiles({ staged }, true);
   CHECK_MSG(Ok(r), r.dump());
   CHECK(WaveTracks(Snap()).size() == 1);
}

//! A 16-bit PCM WAV of `frames` frames with any header values
void WriteWav(const std::string &path, uint32_t rate, uint16_t channels,
   uint32_t frames)
{
   std::ofstream f(path, std::ios::binary);
   const auto u32 = [&f](uint32_t v) {
      const char b[4] = { char(v), char(v >> 8), char(v >> 16), char(v >> 24) };
      f.write(b, 4);
   };
   const auto u16 = [&f](uint16_t v) {
      const char b[2] = { char(v), char(v >> 8) };
      f.write(b, 2);
   };
   const uint32_t dataBytes = frames * channels * 2;
   f.write("RIFF", 4); u32(36 + dataBytes); f.write("WAVE", 4);
   f.write("fmt ", 4); u32(16); u16(1); u16(channels); u32(rate);
   u32(rate * 2u * channels); u16(uint16_t(2 * channels)); u16(16);
   f.write("data", 4); u32(dataBytes);
   for (uint32_t i = 0; i < frames * channels; ++i)
      u16(uint16_t(int16_t((i * 977) % 20000) - 10000));
}

//! Peak resident memory of this process (the engine runs in it), in MB
long PeakRssMb()
{
   rusage usage{};
   ::getrusage(RUSAGE_SELF, &usage);
   return usage.ru_maxrss / 1024;
}

void TestImportLimits()
{
   // A tiny file declaring very many channels is refused before the PCM
   // importer allocates about 1 MiB per channel (a 20 KB WAV with 1024
   // channels made it touch 1 GB).  Runs first: the peak memory of the
   // process is still low.
   REQ("project.new");
   const auto states = HistoryCount();
   const long peakBefore = PeakRssMb();
   for (const uint16_t channels : { uint16_t(65), uint16_t(1024) }) {
      const auto path = ImportPath("many.wav");
      WriteWav(path, 44100, channels, 10);
      auto r = ImportFiles({ path }, false);
      CHECK_MSG(ErrorCodeOf(r) == "FAILED", r.dump().substr(0, 300));
      CHECK_MSG(ErrMessage(r).find(std::to_string(channels) + " channels") !=
         std::string::npos, r.dump().substr(0, 300));
      CHECK_MSG(ErrMessage(r).find("many.wav") != std::string::npos,
         r.dump().substr(0, 300));
   }
   const long grown = PeakRssMb() - peakBefore;
   std::fprintf(stderr, "  peak memory grew by %ld MB (65 and 1024 channels)\n",
      grown);
   CHECK_MSG(grown < 100, std::to_string(grown) + " MB");
   CHECK(WaveTracks(Snap()).empty());
   CHECK(HistoryCount() == states);
   // 64 channels (the limit) still import: 64 mono tracks
   {
      const auto path = ImportPath("sixtyfour.wav");
      WriteWav(path, 44100, 64, 10);
      auto r = ImportFiles({ path }, false);
      CHECK_MSG(Ok(r), r.dump().substr(0, 300));
      CHECK(WaveTracks(Snap()).size() == 64);
   }

   // The first import into an empty project adopts the file's rate as the
   // project rate only within project.setRate's range (1000 ... 768000 Hz);
   // the track keeps its own rate either way
   for (const uint32_t rate : { 1u, 999u, 1000u, 768000u, 768001u,
           2000000000u }) {
      REQ("project.new");
      REQ("project.setRate", json{ { "rate", 44100 } });
      const auto path = ImportPath("odd.wav");
      WriteWav(path, rate, 1, 100);
      auto r = ImportFiles({ path }, false);
      CHECK_MSG(Ok(r), std::to_string(rate) + ": " + r.dump());
      const auto s = Snap();
      const bool adopted = rate >= 1000 && rate <= 768000;
      CHECK_MSG(s["project"].value("rate", 0.0) ==
         (adopted ? double(rate) : 44100.0),
         std::to_string(rate) + ": " + s["project"].dump());
      const auto tracks = WaveTracks(s);
      CHECK(tracks.size() == 1);
      if (tracks.size() == 1)
         CHECK_MSG(tracks[0].value("rate", 0.0) == double(rate),
            std::to_string(rate) + ": " + tracks[0].dump().substr(0, 200));
   }
}

} // namespace

int main()
{
   TempDirs dirs;
   gDirs = &dirs;
   gSink = std::make_shared<Sink>();
   InstallProgressHandler();
   InstallDialogHandler();
   if (!aubridge::Start(dirs.ConfigJson(), gSink)) {
      std::fprintf(stderr, "Start failed\n");
      return 1;
   }
   auto ready = gSink->WaitFor("engine.ready", 120s);
   CHECK(ready.has_value());
   if (!ready) {
      aubridge::Stop();
      return 1;
   }
   for (const auto &c : (*ready)["selfChecks"])
      if (c.value("name", "") == "importers" || c.value("name", "") == "exporters")
         CHECK_MSG(c.value("ok", false), c.dump());

   TestImportLimits();
   TestFormatLists();
   TestOptions();
   TestRoundTrips();
   TestRangesAndDefaults();
   TestCancelAndStop();
   TestImport();
   TestMultiStream();
   TestLegacyAup();
   TestImportErrors();

   // Restart: the options sessions and the import handler are re-created
   aubridge::Stop();
   CHECK(aubridge::Start(dirs.ConfigJson(), gSink));
   const auto mark = gSink->Count();
   CHECK(gSink->WaitFor("engine.ready", 120s, mark).has_value());
   auto o = Call("export.options", json{ { "formatKey", "WAV (Microsoft)" } });
   CHECK(Ok(o));
   aubridge::Stop();

   if (Failures() == 0)
      std::printf("bridge-test-io: all checks passed\n");
   else
      std::printf("bridge-test-io: %d check(s) failed\n", Failures());
   return Failures() == 0 ? 0 : 1;
}
