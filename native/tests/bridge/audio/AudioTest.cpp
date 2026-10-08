/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AudioTest.cpp

  Host tests of the bridge "audio" module through Bridge.h only, on the
  simulated "Null" AAudio device of the PortAudio host API (pa_null.h):

   * audio.devices / audio.latency / audio.permission refusal
   * transport.play: selection plays and stops at its end (transport
     events, readTransport times advance monotonically, display time
     latency compensated), cursor -> end, looping play region (wraps),
     loop:true, Quick-Play t0 / t0..t1, pause/resume, seek, stop,
     skipToStart/End, AUDIO_BUSY while playing, meters while playing
   * transport.record: Shift+R new track (~1 s of the device's sine, one
     undo entry "Recorded Audio", undo removes it, live snapshots with a
     synthetic id <= -2), R appends after the end of the selected track,
     dropouts (simulated input xrun -> "Dropouts" label track), overdub in
     loopback mode with the measured latency correction (alignment of the
     recorded copy within 2 ms, also of the first take, which is re-aligned
     to its own measurement), the correction stored per route (connected
     devices), re-alignment of late and early takes
   * transport.pause {paused, cause} (audio focus, headphones unplugged,
     microphone silenced: no toggle, transport event reason "device")
   * audio.setInputOptions (input presets of the AAudio streams)
   * transport.monitor on/off, permission revoked while monitoring
   * audio.setDevices (PortAudio re-initialised; playback still works)

  Real time: generous timeouts (the machine may be loaded); ctest runs it
  alone (RUN_SERIAL).  BRIDGE_TEST_VERBOSE=1 prints events.

**********************************************************************/
#include "BridgeTestSupport.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <limits>
#include <string>
#include <thread>
#include <vector>

#include "pa_null.h"

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

std::shared_ptr<Sink> gSink;

json Req(const std::string &command, const json &args = json::object())
{
   auto r = Call(command, args);
   if (!Ok(r))
      std::fprintf(stderr, "  %s %s -> %s\n", command.c_str(),
         args.dump().c_str(), r.dump().c_str());
   CHECK_MSG(Ok(r), command);
   return r.contains("result") ? r["result"] : json::object();
}

std::string Err(const std::string &command, const json &args = json::object())
{
   return ErrorCodeOf(Call(command, args));
}

json Snap()
{
   return Req("project.snapshot");
}

struct Transport {
   bool ok = false;
   double v[16] = {};
   int State() const { return int(v[0]); }
   double Stream() const { return v[1]; }
   double Display() const { return v[2]; }
};

Transport ReadT()
{
   Transport t;
   t.ok = aubridge::ReadTransport(t.v, 16);
   return t;
}

struct Meters {
   float v[14] = {};
};

Meters ReadM()
{
   Meters m;
   CHECK(aubridge::ReadMeters(m.v, 14));
   return m;
}

size_t Mark()
{
   return gSink->Count();
}

std::optional<json> WaitTransport(const std::string &state, size_t from,
   std::chrono::milliseconds timeout = 30s)
{
   auto e = gSink->WaitFor("transport", timeout, from,
      [&](const json &p) { return p.value("state", "") == state; });
   CHECK_MSG(e.has_value(), "transport event " + state);
   return e;
}

bool WaitUntil(const std::function<bool()> &pred,
   std::chrono::milliseconds timeout = 30s)
{
   const auto deadline = std::chrono::steady_clock::now() + timeout;
   while (std::chrono::steady_clock::now() < deadline) {
      if (pred())
         return true;
      std::this_thread::sleep_for(5ms);
   }
   return pred();
}

bool Near(double a, double b, double eps)
{
   return std::fabs(a - b) <= eps;
}

int64_t Tone(double seconds, double frequency = 440, double amplitude = 0.5)
{
   return Req("debug.makeTestTrack", { { "seconds", seconds },
      { "frequency", frequency }, { "amplitude", amplitude } })
      .value("id", int64_t(-1));
}

void NewProject()
{
   Req("project.new");
}

std::vector<json> WaveTracks(const json &snapshot)
{
   std::vector<json> result;
   for (const auto &t : snapshot["tracks"])
      if (t.value("kind", "") == "wave")
         result.push_back(t);
   return result;
}

json HistoryList()
{
   return Req("history.list");
}

std::vector<float> Samples(int64_t trackId, double t0, double t1)
{
   auto r = Req("audio.debugSamples",
      { { "trackId", trackId }, { "t0", t0 }, { "t1", t1 } });
   std::vector<float> v;
   if (r.contains("values"))
      for (const auto &x : r["values"])
         v.push_back(x.get<float>());
   return v;
}

bool StopAndWait()
{
   const auto from = Mark();
   Req("transport.stop");
   return WaitUntil([] { return ReadT().State() == 0; }, 20s) &&
      (WaitTransport("stopped", 0).has_value() || from == 0);
}

// ---------------------------------------------------------------------------

void TestDevicesAndPermission()
{
   std::fprintf(stderr, "== devices, latency, permission\n");
   auto devices = Req("audio.devices");
   bool defaultOut = false, defaultIn = false;
   for (const auto &d : devices["outputs"])
      if (d.value("name", "") == "Default Output" && d.value("isDefault", false)
          && d.value("maxOutputChannels", 0) >= 2)
         defaultOut = true;
   for (const auto &d : devices["inputs"])
      if (d.value("name", "") == "Default Input" && d.value("isDefault", false))
         defaultIn = true;
   CHECK(defaultOut && defaultIn);
   CHECK(devices["current"].value("output", "") == "Default Output");
   CHECK(devices["current"].value("input", "") == "Default Input");
   CHECK(devices["current"].value("recordChannels", 0) == 1);
   CHECK(!devices["outputs"].empty() &&
      devices["outputs"][0].value("hostApi", "") == "Null");

   auto latency = Req("audio.latency");
   CHECK(latency.contains("outputLatencyMs") && latency.contains("inputLatencyMs")
      && latency.contains("correctionMs"));
   CHECK(latency.value("outputLatencyMs", -1.0) >= 0);
   // Nothing measured yet: an estimate from the device latencies
   CHECK(!latency.value("measured", true));
   CHECK(latency.value("correctionMs", 0.0) < 0);

   auto t = ReadT();
   CHECK(t.ok && t.State() == 0);
   CHECK(t.v[11] == 1.0);

   // No microphone permission (start config): record/monitor refused
   CHECK(Err("transport.record", { { "newTrack", true } }) == "UNSUPPORTED");
   CHECK(Err("transport.monitor", { { "enabled", true } }) == "UNSUPPORTED");
   CHECK(Err("transport.record", json::object()) == "INVALID_ARGS");
   CHECK(!(Snap()["flags"].get<uint64_t>() & (uint64_t(1) << 36)));
   Req("audio.permission", { { "recordPermission", true } });
   CHECK(Snap()["flags"].get<uint64_t>() & (uint64_t(1) << 36));

   // Stop / pause with nothing running are harmless
   Req("transport.stop");
   CHECK(!Req("transport.pause").value("toggled", true));
   // Nothing to play in an empty project
   NewProject();
   CHECK(!Req("transport.play").value("started", true));
}

void TestPlaySelection()
{
   std::fprintf(stderr, "== play selection\n");
   NewProject();
   const auto id = Tone(2.0);
   Req("select.set", { { "t0", 0.5 }, { "t1", 1.5 }, { "trackIds", { id } } });
   {
      // The inactive play region follows the selection (AdornedRulerPanel)
      auto pr = Snap()["playRegion"];
      CHECK(!pr.value("active", true));
      CHECK(Near(pr.value("t0", 0.0), 0.5, 1e-9) && Near(pr.value("t1", 0.0), 1.5, 1e-9));
   }
   PaNullOutputStats out{};
   PaNull_GetOutputStats(&out, 1);

   const auto from = Mark();
   auto r = Req("transport.play");
   CHECK(r.value("started", false));
   auto playing = WaitTransport("playing", from);
   if (playing)
      CHECK(playing->value("reason", "") == "user");
   CHECK(Snap()["audio"].value("busy", false));
   CHECK(Snap()["flags"].get<uint64_t>() & 2u);   // BUSY

   // AUDIO_BUSY: edits and a second play are rejected while playing
   CHECK(Err("debug.makeTestTrack") == "AUDIO_BUSY");
   CHECK(Err("transport.play") == "AUDIO_BUSY");
   CHECK(Err("transport.record", { { "newTrack", true } }) == "AUDIO_BUSY");

   double lastStream = -1, lastDisplay = -1, lastSampled = 0;
   double maxStream = 0, maxPlayPeak = 0;
   int samples = 0, monotonic = 0, compensated = 0, playChannels = 0;
   bool inRange = true, displayOk = true;
   WaitUntil([&] {
      auto t = ReadT();
      if (t.State() == 1 && std::isfinite(t.Stream())) {
         ++samples;
         if (t.Stream() + 1e-9 >= lastStream && t.Display() + 1e-9 >= lastDisplay &&
             t.v[3] >= lastSampled)
            ++monotonic;
         else
            std::fprintf(stderr, "  not monotonic: %f %f (%f %f)\n",
               t.Stream(), t.Display(), lastStream, lastDisplay);
         if (t.Stream() < 0.5 - 1e-6 || t.Stream() > 1.5 + 1e-3)
            inRange = false;
         if (!(t.Display() <= t.Stream() + 1e-9 && t.Display() >= 0.5 - 1e-6))
            displayOk = false;
         if (t.Display() < t.Stream() - 1e-6)
            ++compensated;
         lastStream = t.Stream();
         lastDisplay = t.Display();
         lastSampled = t.v[3];
         maxStream = std::max(maxStream, t.Stream());
         CHECK(t.v[7] == 48000);
         CHECK(t.v[6] == 0);
      }
      auto m = ReadM();
      maxPlayPeak = std::max<double>(maxPlayPeak, std::max(m.v[0], m.v[1]));
      playChannels = std::max(playChannels, int(m.v[12]));
      std::this_thread::sleep_for(10ms);
      return t.State() == 0;
   }, 30s);
   std::fprintf(stderr, "  %d samples, max stream time %.3f, peak %.3f\n",
      samples, maxStream, maxPlayPeak);
   CHECK(samples >= 5);
   CHECK(monotonic == samples);
   CHECK(inRange);
   CHECK(displayOk);
   CHECK(compensated > 0);
   CHECK(maxStream > 1.3);
   CHECK(maxPlayPeak > 0.3 && maxPlayPeak < 0.7);
   CHECK(playChannels == 2);

   auto stopped = WaitTransport("stopped", from);
   if (stopped)
      CHECK(stopped->value("reason", "") == "end");
   CHECK(!Snap()["audio"].value("busy", true));
   // The device really played ~1 s of the 0.5 sine
   PaNull_GetOutputStats(&out, 1);
   std::fprintf(stderr, "  device: %lld frames, peak %.3f\n",
      (long long)out.frames, out.peak[0]);
   CHECK(out.frames > 40000);
   CHECK(out.peak[0] > 0.4 && out.peak[0] < 0.6);
   // Meters drained after the stop
   auto m = ReadM();
   CHECK(m.v[12] == 0);
   // Edits allowed again
   CHECK(Err("select.all") == "");

   // Space with a point selection: cursor -> end
   std::fprintf(stderr, "== play cursor to end\n");
   Req("select.set", { { "t0", 1.6 }, { "t1", 1.6 } });
   const auto from2 = Mark();
   Req("transport.play");
   WaitTransport("playing", from2);
   double first = -1, last = -1;
   WaitUntil([&] {
      auto t = ReadT();
      if (t.State() == 1 && std::isfinite(t.Stream())) {
         if (first < 0)
            first = t.Stream();
         last = t.Stream();
      }
      return t.State() == 0;
   }, 30s);
   std::fprintf(stderr, "  %.3f .. %.3f\n", first, last);
   CHECK(first >= 1.6 - 1e-6 && first < 1.9);
   CHECK(last > 1.85 && last <= 2.0 + 1e-3);
   auto end2 = WaitTransport("stopped", from2);
   if (end2)
      CHECK(end2->value("reason", "") == "end");
}

void TestLoop()
{
   std::fprintf(stderr, "== loop\n");
   NewProject();
   Tone(2.0);
   Req("select.set", { { "t0", 0.2 }, { "t1", 0.2 } });
   Req("playRegion.set", { { "t0", 0.2 }, { "t1", 0.6 }, { "active", true } });
   const auto from = Mark();
   Req("transport.play");
   WaitTransport("playing", from);
   bool wrapped = false, looping = false;
   double prev = -1, maxT = 0;
   WaitUntil([&] {
      auto t = ReadT();
      if (t.State() == 1 && std::isfinite(t.Stream())) {
         looping = looping || (t.v[6] == 1 && Near(t.v[4], 0.2, 1e-9) &&
            Near(t.v[5], 0.6, 1e-9));
         if (prev >= 0 && t.Stream() < prev - 0.1)
            wrapped = true;
         prev = t.Stream();
         maxT = std::max(maxT, t.Stream());
      }
      return wrapped && looping;
   }, 20s);
   CHECK(wrapped);
   CHECK(looping);
   CHECK(maxT <= 0.6 + 0.02);
   CHECK(ReadT().State() == 1);   // still playing after the wrap
   const auto from2 = Mark();
   Req("transport.stop");
   auto stopped = WaitTransport("stopped", from2);
   if (stopped)
      CHECK(stopped->value("reason", "") == "user");
   CHECK(ReadT().State() == 0);

   // loop:true with an inactive region: the selection becomes the loop
   std::fprintf(stderr, "== loop:true\n");
   Req("playRegion.clear");
   Req("select.set", { { "t0", 0.3 }, { "t1", 0.7 } });
   CHECK(!Snap()["playRegion"].value("active", true));
   const auto from3 = Mark();
   Req("transport.play", { { "loop", true } });
   WaitTransport("playing", from3);
   auto pr = Snap()["playRegion"];
   CHECK(pr.value("active", false));
   CHECK(Near(pr.value("t0", 0.0), 0.3, 1e-9) && Near(pr.value("t1", 0.0), 0.7, 1e-9));
   wrapped = false;
   prev = -1;
   WaitUntil([&] {
      auto t = ReadT();
      if (t.State() == 1 && std::isfinite(t.Stream())) {
         if (prev >= 0 && t.Stream() < prev - 0.1)
            wrapped = true;
         prev = t.Stream();
      }
      return wrapped;
   }, 20s);
   CHECK(wrapped);
   CHECK(ReadT().v[6] == 1);
   StopAndWait();
   Req("playRegion.toggle");
   CHECK(!Snap()["playRegion"].value("active", true));
}

void TestQuickPlay()
{
   std::fprintf(stderr, "== quick play\n");
   NewProject();
   Tone(2.0);
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   const auto prBefore = Snap()["playRegion"];
   auto from = Mark();
   Req("transport.play", { { "t0", 1.2 } });
   WaitTransport("playing", from);
   double first = -1, last = -1;
   WaitUntil([&] {
      auto t = ReadT();
      if (t.State() == 1 && std::isfinite(t.Stream())) {
         if (first < 0)
            first = t.Stream();
         last = t.Stream();
      }
      return t.State() == 0;
   }, 30s);
   std::fprintf(stderr, "  %.3f .. %.3f\n", first, last);
   CHECK(first >= 1.2 - 1e-6 && first < 1.5);
   CHECK(last > 1.85);
   auto stopped = WaitTransport("stopped", from);
   if (stopped)
      CHECK(stopped->value("reason", "") == "end");
   CHECK(Snap()["playRegion"] == prBefore);
   CHECK(Snap()["selection"].value("t0", -1.0) == 0.0);

   from = Mark();
   Req("transport.play", { { "t0", 0.5 }, { "t1", 0.8 } });
   WaitTransport("playing", from);
   double maxT = 0;
   WaitUntil([&] {
      auto t = ReadT();
      if (std::isfinite(t.Stream()))
         maxT = std::max(maxT, t.Stream());
      return t.State() == 0;
   }, 30s);
   CHECK(maxT > 0.6 && maxT <= 0.8 + 0.02);
   WaitTransport("stopped", from);
   CHECK(Err("transport.play", { { "t0", 1.0 }, { "t1", 0.5 } }) == "INVALID_ARGS");
   CHECK(Err("transport.play", { { "t1", 0.5 } }) == "INVALID_ARGS");
}

void TestPauseSeekSkip()
{
   std::fprintf(stderr, "== pause / seek / skip\n");
   NewProject();
   Tone(6.0);
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   auto from = Mark();
   Req("transport.play");
   WaitTransport("playing", from);
   WaitUntil([] { auto t = ReadT(); return t.Stream() > 0.3; }, 20s);

   // pause
   from = Mark();
   CHECK(Req("transport.pause").value("toggled", false));
   WaitTransport("paused", from);
   CHECK(ReadT().State() == 3);
   CHECK(Snap()["flags"].get<uint64_t>() & (uint64_t(1) << 17));   // PAUSED
   std::this_thread::sleep_for(150ms);
   const auto p1 = ReadT();
   std::this_thread::sleep_for(400ms);
   const auto p2 = ReadT();
   CHECK(p2.State() == 3);
   CHECK(Near(p1.Stream(), p2.Stream(), 1e-9));
   CHECK(Near(p1.Display(), p2.Display(), 1e-9));

   // resume
   from = Mark();
   Req("transport.pause");
   WaitTransport("playing", from);
   WaitUntil([&] { return ReadT().Stream() > p2.Stream() + 0.2; }, 20s);
   CHECK(ReadT().Stream() > p2.Stream() + 0.2);
   CHECK(ReadT().State() == 1);

   // seek while playing
   Req("transport.seek", { { "t", 4.0 } });
   CHECK(ReadT().Display() >= 4.0 - 1e-6 || ReadT().Stream() >= 4.0);
   CHECK(WaitUntil([] { auto t = ReadT(); return t.Stream() >= 4.0 && t.Stream() < 5.5; }, 10s));
   auto s = ReadT();
   CHECK(s.Display() >= 4.0 - 1e-6 && s.Display() <= s.Stream() + 1e-9);

   // skipToStart while playing moves the play head
   Req("transport.skipToStart");
   CHECK(WaitUntil([] { auto t = ReadT(); return t.Stream() < 1.0; }, 10s));
   CHECK(ReadT().State() == 1);
   StopAndWait();

   // Stop right after a seek, repeatedly (AudioIO's buffer-thread handshake
   // must not hang StopStream)
   for (int i = 0; i < 6; ++i) {
      from = Mark();
      Req("transport.play", { { "t0", 0.0 } });
      WaitTransport("playing", from);
      if (i % 2)
         std::this_thread::sleep_for(std::chrono::milliseconds(20 * i));
      Req("transport.seek", { { "t", 2.0 + 0.5 * i } });
      if (i % 3 == 2)
         Req("transport.seek", { { "t", 1.0 } });
      Req("transport.stop");
      CHECK(ReadT().State() == 0);
   }

   // while stopped: seek moves the cursor, skip moves the selection
   Req("transport.seek", { { "t", 1.25 } });
   auto sel = Snap()["selection"];
   CHECK(Near(sel.value("t0", 0.0), 1.25, 1e-9) && Near(sel.value("t1", 0.0), 1.25, 1e-9));
   Req("transport.skipToEnd");
   sel = Snap()["selection"];
   CHECK(Near(sel.value("t0", 0.0), 6.0, 1e-6) && Near(sel.value("t1", 0.0), 6.0, 1e-6));
   Req("transport.skipToStart");
   sel = Snap()["selection"];
   CHECK(sel.value("t0", 1.0) == 0.0 && sel.value("t1", 1.0) == 0.0);
}

void TestRecordNewTrack()
{
   std::fprintf(stderr, "== record new track (Shift+R)\n");
   NewProject();
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   const auto states0 = HistoryList()["states"].size();
   const auto from = Mark();
   Req("transport.record", { { "newTrack", true } });
   auto rec = WaitTransport("recording", from);
   CHECK(Snap()["audio"].value("busy", false));
   CHECK(!(Snap()["flags"].get<uint64_t>() & (uint64_t(1) << 7)));   // CNB off
   // The pending new track has a synthetic id
   {
      auto tracks = WaveTracks(Snap());
      CHECK(tracks.size() == 1);
      if (!tracks.empty())
         CHECK(tracks[0].value("id", 0) <= -2);
   }
   CHECK(Err("debug.makeTestTrack") == "AUDIO_BUSY");
   CHECK(Err("transport.seek", { { "t", 0.5 } }) == "");   // ignored

   double maxRecPeak = 0;
   int recChannels = 0;
   bool capturing = false, startOk = false;
   WaitUntil([&] {
      auto t = ReadT();
      auto m = ReadM();
      maxRecPeak = std::max<double>(maxRecPeak, m.v[6]);
      recChannels = std::max(recChannels, int(m.v[13]));
      if (t.State() == 2) {
         capturing = capturing || t.v[10] == 1;
         startOk = Near(t.v[12], 0.0, 1e-9);
      }
      return t.State() == 2 && t.Stream() >= 1.05;
   }, 30s);
   CHECK(capturing);
   CHECK(startOk);
   // Pause while recording (input discarded, the clip continues later)
   {
      const auto f = Mark();
      Req("transport.pause");
      WaitTransport("paused", f);
      CHECK(ReadT().State() == 4);
      Req("transport.pause");
      WaitTransport("recording", f);
      CHECK(ReadT().State() == 2);
   }
   CHECK(maxRecPeak > 0.3 && maxRecPeak < 0.7);
   CHECK(recChannels == 1);
   // A live snapshot showed the growing pending track
   auto live = gSink->WaitFor("snapshot", 5s, from, [](const json &s) {
      for (const auto &t : s["tracks"])
         if (t.value("id", 0) <= -2 && t.value("end", 0.0) > 0.1)
            return true;
      return false;
   });
   CHECK(live.has_value());

   const auto from2 = Mark();
   Req("transport.stop");
   auto stopped = WaitTransport("stopped", from2);
   if (stopped) {
      CHECK(stopped->value("reason", "") == "user");
      CHECK(stopped->value("dropouts", -1) == 0);
   }
   auto snap = Snap();
   auto tracks = WaveTracks(snap);
   CHECK(tracks.size() == 1);
   int64_t id = -1;
   if (!tracks.empty()) {
      id = tracks[0].value("id", int64_t(-1));
      const double end = tracks[0].value("end", 0.0);
      std::fprintf(stderr, "  recorded %.3f s into track %lld\n", end, (long long)id);
      CHECK(id >= 0);
      CHECK(end > 0.9 && end < 3.0);
      CHECK(Near(tracks[0].value("start", 1.0), 0.0, 1e-6));
      CHECK(tracks[0].value("rate", 0) == 48000);
   }
   CHECK(snap["tracks"].size() == 1);   // no dropout labels
   auto history = HistoryList();
   CHECK(history["states"].size() == states0 + 1);
   CHECK(history["states"].back().value("description", "") == "Recorded Audio");
   CHECK(snap["history"].value("undo", "") == "Record");

   // ~0.5 amplitude 440 Hz sine of the simulated microphone
   if (id >= 0) {
      auto v = Samples(id, 0.3, 0.5);
      CHECK(v.size() == 9600);
      float peak = 0;
      int crossings = 0;
      for (size_t i = 0; i < v.size(); ++i) {
         peak = std::max(peak, std::fabs(v[i]));
         if (i > 0 && (v[i - 1] < 0) != (v[i] < 0))
            ++crossings;
      }
      std::fprintf(stderr, "  peak %.3f, %d zero crossings in 0.2 s\n", peak, crossings);
      CHECK(peak > 0.45 && peak < 0.55);
      CHECK(crossings > 170 && crossings < 182);
   }

   // One undo entry: undo removes the recording
   Req("history.undo");
   CHECK(WaveTracks(Snap()).empty());
   Req("history.redo");
   CHECK(WaveTracks(Snap()).size() == 1);
}

void TestRecordAppend()
{
   std::fprintf(stderr, "== record append (R)\n");
   NewProject();
   const auto id = Tone(1.0);
   // Cursor inside the track: R appends at the track's end (3.7.9)
   Req("select.set", { { "t0", 0.2 }, { "t1", 0.2 }, { "trackIds", { id } } });
   const auto from = Mark();
   Req("transport.record", { { "newTrack", false } });
   WaitTransport("recording", from);
   CHECK(Near(ReadT().v[12], 1.0, 1e-6));
   // The appended track keeps its id and shows its growing copy
   WaitUntil([&] { return ReadT().Stream() >= 1.55; }, 30s);
   auto live = gSink->WaitFor("snapshot", 5s, from, [&](const json &s) {
      for (const auto &t : s["tracks"])
         if (t.value("id", int64_t(-1)) == id && t.value("end", 0.0) > 1.1)
            return true;
      return false;
   });
   CHECK(live.has_value());
   StopAndWait();
   auto tracks = WaveTracks(Snap());
   CHECK(tracks.size() == 1);
   if (!tracks.empty()) {
      CHECK(tracks[0].value("id", int64_t(-1)) == id);
      const double end = tracks[0].value("end", 0.0);
      std::fprintf(stderr, "  track end %.3f, %zu clips\n", end, tracks[0]["clips"].size());
      CHECK(end > 1.45 && end < 4.0);
      CHECK(tracks[0]["clips"].size() == 2);
      if (tracks[0]["clips"].size() == 2)
         CHECK(Near(tracks[0]["clips"][1].value("start", 0.0), 1.0, 1e-6));
   }
   CHECK(HistoryList()["states"].back().value("description", "") == "Recorded Audio");

   // R with a time selection that ends after the start: stops by itself
   std::fprintf(stderr, "== record within the selection\n");
   NewProject();
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.4 } });
   const auto from2 = Mark();
   Req("transport.record", { { "newTrack", false } });
   WaitTransport("recording", from2);
   auto stopped = WaitTransport("stopped", from2);
   if (stopped)
      CHECK(stopped->value("reason", "") == "end");
   auto t2 = WaveTracks(Snap());
   CHECK(t2.size() == 1);
   if (!t2.empty())
      CHECK(Near(t2[0].value("end", 0.0), 0.4, 0.01));
}

void TestDropouts()
{
   std::fprintf(stderr, "== dropouts\n");
   NewProject();
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   const auto from = Mark();
   Req("transport.record", { { "newTrack", true } });
   WaitTransport("recording", from);
   WaitUntil([] { return ReadT().Stream() >= 0.4; }, 30s);
   PaNull_SimulateXRun(1, 0);
   WaitUntil([] { return ReadT().Stream() >= 0.8; }, 30s);
   const auto from2 = Mark();
   Req("transport.stop");
   auto stopped = WaitTransport("stopped", from2);
   if (stopped)
      CHECK(stopped->value("dropouts", 0) >= 1);
   auto snap = Snap();
   bool labels = false;
   for (const auto &t : snap["tracks"])
      if (t.value("kind", "") == "label" && !t["labels"].empty())
         labels = true;
   CHECK(labels);
   CHECK(WaveTracks(snap).size() == 1);
   CHECK(HistoryList()["states"].back().value("description", "") == "Recorded Audio");
   // The non-blocking 3.7.9 warning
   auto warning = gSink->WaitFor("dialog", 5s, from2, [](const json &d) {
      return d.value("message", "").find("Recorded audio was lost") != std::string::npos;
   });
   CHECK(warning.has_value());
   // Undo removes recording and labels together
   Req("history.undo");
   CHECK(Snap()["tracks"].empty());
}

//! Lag (in samples) of `b` relative to `a` minimising the squared difference
int BestLag(const std::vector<float> &a, const std::vector<float> &b,
   int maxLag, size_t from, size_t count, double &residual)
{
   int best = 0;
   double bestErr = std::numeric_limits<double>::max(), energy = 0;
   for (int lag = -maxLag; lag <= maxLag; ++lag) {
      double err = 0;
      for (size_t i = from; i < from + count; ++i) {
         const long j = long(i) + lag;
         if (j < 0 || size_t(j) >= b.size())
            continue;
         const double d = double(b[size_t(j)]) - a[i];
         err += d * d;
      }
      if (err < bestErr)
         bestErr = err, best = lag;
   }
   for (size_t i = from; i < from + count; ++i)
      energy += double(a[i]) * a[i];
   residual = energy > 0 ? bestErr / energy : 1.0;
   return best;
}

//! Lag (samples) of the recorded copy `take` of `source` from 0.1 s on
int TakeLag(int64_t source, int64_t take, double &residual)
{
   const auto a = Samples(source, 0.0, 1.5);
   const auto b = Samples(take, 0.0, 1.5);
   residual = 1;
   if (a.size() < 4800 + 48000 || b.empty())
      return 1 << 20;
   const int lag = BestLag(a, b, 720, 4800, 48000, residual);
   if (std::getenv("AUDIO_TEST_DEBUG"))
      for (size_t blk = 0; blk < 150; ++blk) {
         double err = 0, en = 0;
         for (size_t i = blk * 480; i < (blk + 1) * 480 && i < a.size(); ++i) {
            const long j = long(i) + lag;
            const double y = (j >= 0 && size_t(j) < b.size()) ? b[size_t(j)] : 0.0;
            err += (y - a[i]) * (y - a[i]);
            en += a[i] * a[i];
         }
         if (en > 0 && err / en > 0.01)
            std::fprintf(stderr, "    block %zu (%.0f ms): error %.3f\n", blk, blk * 10.0, err / en);
      }
   return lag;
}

//! Start time of the first clip of track `id`
double ClipStart(int64_t id)
{
   const auto snap = Snap();
   for (const auto &t : snap["tracks"])
      if (t.value("id", int64_t(-1)) == id && !t["clips"].empty())
         return t["clips"][0].value("start", -1.0);
   return -1.0;
}

//! The first playback stream of the process plays ~200 ms of silence at
//! its start (also without recording); the alignment checks compare from
//! 0.1 s on, so play something before the first overdub of a group
void WarmUpPlayback()
{
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.3 } });
   const auto from = Mark();
   Req("transport.play");
   WaitTransport("stopped", from);
}

int64_t Overdub(double seconds)
{
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   const auto before = WaveTracks(Snap());
   const auto from = Mark();
   Req("transport.record", { { "newTrack", true } });
   WaitTransport("recording", from);
   WaitUntil([&] { return ReadT().Stream() >= seconds; }, 30s);
   StopAndWait();
   const auto after = WaveTracks(Snap());
   CHECK(after.size() == before.size() + 1);
   return after.empty() ? -1 : after.back().value("id", int64_t(-1));
}

void TestOverdubLoopback()
{
   std::fprintf(stderr, "== overdub loopback\n");
   PaNullConfig config{};
   PaNull_GetConfig(&config);
   auto loop = config;
   loop.loopback = 1;
   loop.loopbackDelayFrames = 0;
   PaNull_SetConfig(&loop);

   NewProject();
   const auto source = Tone(1.6, 25.0, 0.5);
   WarmUpPlayback();
   // First overdub: starts with an estimate, measures the round trip and
   // is re-aligned to it when it is committed
   const auto first = Overdub(1.7);
   if (first >= 0) {
      double residual = 1;
      const int lag = TakeLag(source, first, residual);
      std::fprintf(stderr, "  first take lag %d samples, residual %.4f, starts at %.4f s\n",
         lag, residual, ClipStart(first));
      CHECK(std::abs(lag) <= 96);
      CHECK(residual < 0.05);
      // The estimate is low: the take was late and lost only input from
      // before its start
      CHECK(Near(ClipStart(first), 0.0, 1e-6));
   }
   auto latency = Req("audio.latency");
   PaAAudioStreamStats st{};
   PaAAudio_GetActiveStreamStats(&st);
   std::fprintf(stderr, "  duplex offset %.3f ms (stats %.3f ms), correction %.3f ms\n",
      latency.value("duplexOffsetMs", 0.0), st.duplexOffsetSec * 1000,
      latency.value("correctionMs", 0.0));
   CHECK(latency.value("measured", false));
   CHECK(st.hasInput && st.hasOutput);
   CHECK(Near(latency.value("duplexOffsetMs", 0.0), st.duplexOffsetSec * 1000, 0.01));
   CHECK(Near(latency.value("correctionMs", 0.0), -st.duplexOffsetSec * 1000, 0.01));
   Req("history.undo");
   CHECK(WaveTracks(Snap()).size() == 1);

   // Second overdub: corrected by the measured offset
   const auto second = Overdub(1.7);
   if (second >= 0) {
      const auto a = Samples(source, 0.0, 1.5);
      const auto b = Samples(second, 0.0, 1.5);
      double residual = 1;
      const int lag = BestLag(a, b, 720, 4800, 48000, residual);
      std::fprintf(stderr, "  recorded copy lag %d samples (%.2f ms), residual %.4f\n",
         lag, lag / 48.0, residual);
      CHECK(std::abs(lag) <= 96);   // 2 ms
      CHECK(residual < 0.05);
   }

   // The user trim is added (Audacity's sign)
   Req("settings.set", { { "settings", { { "latencyCorrectionMs", -5.0 } } } });
   auto trimmed = Req("audio.latency");
   CHECK(Near(trimmed.value("correctionMs", 0.0),
      latency.value("correctionMs", 0.0) - 5.0, 0.01));
   Req("settings.set", { { "settings", { { "latencyCorrectionMs", 0.0 } } } });

   PaNull_SetConfig(&config);
}


json DeviceList(bool withBluetooth)
{
   auto list = json::array({
      { { "id", 2 }, { "name", "Pixel" }, { "type", 2 }, { "isSink", true } },
      { { "id", 3 }, { "name", "Pixel" }, { "type", 15 }, { "isSource", true } } });
   if (withBluetooth)
      list.push_back({ { "id", 41 }, { "name", "Buds" }, { "type", 8 },
         { "isSink", true } });
   return list;
}

void TestOverdubRoutes()
{
   std::fprintf(stderr, "== overdub routes\n");
   PaNullConfig config{};
   PaNull_GetConfig(&config);
   auto loop = config;
   loop.loopback = 1;
   loop.loopbackDelayFrames = 0;
   PaNull_SetConfig(&loop);

   NewProject();
   const auto source = Tone(1.6, 25.0, 0.5);
   WarmUpPlayback();
   const auto checkTake = [&](int64_t take, const char *what) {
      double residual = 1;
      const int lag = TakeLag(source, take, residual);
      std::fprintf(stderr, "  %s: lag %d samples, residual %.4f, clip starts at %.4f s\n",
         what, lag, residual, ClipStart(take));
      CHECK_MSG(std::abs(lag) <= 96, what);
      CHECK_MSG(residual < 0.05, what);
   };

   // Route A (speaker + microphone), never measured
   Req("audio.setDevices", { { "devices", DeviceList(false) } });
   CHECK(!Req("audio.latency").value("measured", true));
   auto take = Overdub(1.7);
   checkTake(take, "route A, first take");
   CHECK(Near(ClipStart(take), 0.0, 1e-6));
   auto latencyA = Req("audio.latency");
   CHECK(latencyA.value("measured", false));
   const double offsetA = latencyA.value("duplexOffsetMs", 0.0);
   Req("history.undo");

   // A Bluetooth headset connects: another route, nothing measured there
   Req("audio.setDevices", { { "devices", DeviceList(true) } });
   CHECK(!Req("audio.latency").value("measured", true));
   // ... with a much shorter round trip on the simulated device (smaller
   // bursts: smaller AAudio buffers)
   auto shorter = loop;
   shorter.framesPerBurst = 192;
   PaNull_SetConfig(&shorter);
   take = Overdub(1.7);
   checkTake(take, "route B, first take");
   auto latencyB = Req("audio.latency");
   CHECK(latencyB.value("measured", false));
   const double offsetB = latencyB.value("duplexOffsetMs", 0.0);
   std::fprintf(stderr, "  round trip A %.2f ms, B %.2f ms\n", offsetA, offsetB);
   CHECK(offsetA - offsetB > 20);
   Req("history.undo");

   // Back on route A: its own measurement, not B's
   Req("audio.setDevices", { { "devices", DeviceList(false) } });
   auto again = Req("audio.latency");
   CHECK(again.value("measured", false));
   CHECK(Near(again.value("duplexOffsetMs", 0.0), offsetA, 0.01));
   // The route's round trip changed (still the short one): the take starts
   // with A's correction, too early, and is moved right at commit
   take = Overdub(1.7);
   checkTake(take, "route A, early take");
   const double start = ClipStart(take);
   CHECK(Near(start, (offsetA - offsetB) / 1000.0, 0.005));
   Req("history.undo");

   PaNull_SetConfig(&config);
   Req("audio.setDevices", { { "devices", json::array() } });
}

void TestPauseCause()
{
   std::fprintf(stderr, "== pause with a cause\n");
   NewProject();
   Tone(3.0);
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   auto from = Mark();
   Req("transport.play");
   WaitTransport("playing", from);
   from = Mark();
   CHECK(Req("transport.pause", { { "paused", true }, { "cause", "focus" } })
      .value("toggled", false));
   auto paused = WaitTransport("paused", from);
   if (paused) {
      CHECK(paused->value("reason", "") == "device");
      CHECK(paused->value("message", "").find("another app") != std::string::npos);
   }
   CHECK(ReadT().State() == 3);
   // Already paused: nothing toggles back
   CHECK(!Req("transport.pause", { { "paused", true }, { "cause", "noisy" } })
      .value("toggled", true));
   CHECK(ReadT().State() == 3);
   CHECK(Err("transport.pause", { { "paused", true }, { "cause", "bogus" } })
      == "INVALID_ARGS");
   from = Mark();
   CHECK(Req("transport.pause", { { "paused", false } }).value("toggled", false));
   auto resumed = WaitTransport("playing", from);
   if (resumed)
      CHECK(resumed->value("reason", "") == "user");
   CHECK(!Req("transport.pause", { { "paused", false } }).value("toggled", true));
   // Without `paused` it still toggles
   CHECK(Req("transport.pause").value("toggled", false));
   CHECK(WaitUntil([] { return ReadT().State() == 3; }, 5s));
   StopAndWait();
   // Nothing playing: no-op
   CHECK(!Req("transport.pause", { { "paused", true }, { "cause", "silenced" } })
      .value("toggled", true));
}

void TestInputOptions()
{
   std::fprintf(stderr, "== input options\n");
   auto r = Req("audio.setInputOptions");
   CHECK(r.value("preset", "") == "auto");
   CHECK(r.value("monoPreset", "") == "voiceRecognition");
   CHECK(r.value("stereoPreset", "") == "camcorder");
   CHECK(!r.value("unprocessedSupported", true));

   NewProject();
   // Mono and stereo recordings use their presets
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   const auto record = [](int channels) {
      Req("settings.set", { { "settings", { { "recordChannels", channels } } } });
      const auto from = Mark();
      Req("transport.record", { { "newTrack", true } });
      WaitTransport("recording", from);
      WaitUntil([] { return ReadT().Stream() >= 0.2; }, 30s);
      PaAAudioStreamStats st{};
      PaAAudio_GetActiveStreamStats(&st);
      StopAndWait();
      return st.inputPreset;
   };
   CHECK(record(1) == 6);   // VOICE_RECOGNITION
   CHECK(record(2) == 5);   // CAMCORDER: the stereo microphone pair

   r = Req("audio.setInputOptions", { { "unprocessedSupported", true } });
   CHECK(r.value("monoPreset", "") == "unprocessed");
   CHECK(r.value("stereoPreset", "") == "unprocessed");
   CHECK(record(2) == 9);

   r = Req("audio.setInputOptions", { { "preset", "voiceRecognition" } });
   CHECK(r.value("preset", "") == "voiceRecognition");
   CHECK(r.value("stereoPreset", "") == "voiceRecognition");
   CHECK(record(2) == 6);
   CHECK(Err("audio.setInputOptions", { { "preset", "bogus" } }) == "INVALID_ARGS");
   // The choice is a preference
   CHECK(Req("audio.setInputOptions").value("preset", "") == "voiceRecognition");

   Req("audio.setInputOptions", { { "preset", "auto" }, { "unprocessedSupported", false } });
   Req("settings.set", { { "settings", { { "recordChannels", 1 } } } });
}

void TestMonitoring()
{
   std::fprintf(stderr, "== monitoring\n");
   NewProject();
   auto from = Mark();
   Req("transport.monitor", { { "enabled", true } });
   WaitTransport("monitoring", from);
   CHECK(ReadT().State() == 5);
   CHECK(!Snap()["audio"].value("busy", true));   // monitoring is not busy
   double peak = 0;
   WaitUntil([&] {
      auto m = ReadM();
      peak = std::max<double>(peak, m.v[6]);
      std::this_thread::sleep_for(20ms);
      return peak > 0.3;
   }, 20s);
   CHECK(peak > 0.3 && peak < 0.7);
   // Edits are allowed while monitoring
   CHECK(Err("debug.makeTestTrack", { { "seconds", 0.2 } }) == "");
   from = Mark();
   Req("transport.monitor", { { "enabled", false } });
   WaitTransport("stopped", from);
   CHECK(ReadT().State() == 0);

   // Playing stops monitoring
   from = Mark();
   Req("transport.monitor", { { "enabled", true } });
   WaitTransport("monitoring", from);
   Req("transport.play");
   WaitTransport("playing", from);
   StopAndWait();

   // Revoking the permission stops monitoring
   from = Mark();
   Req("transport.monitor", { { "enabled", true } });
   WaitTransport("monitoring", from);
   Req("audio.permission", { { "recordPermission", false } });
   auto stopped = WaitTransport("stopped", from);
   if (stopped)
      CHECK(stopped->value("reason", "") == "device");
   CHECK(Err("transport.monitor", { { "enabled", true } }) == "UNSUPPORTED");
   Req("audio.permission", { { "recordPermission", true } });
}

void TestSetDevices()
{
   std::fprintf(stderr, "== audio.setDevices\n");
   NewProject();
   auto r = Req("audio.setDevices", { { "devices", json::array({
      { { "id", 7 }, { "name", "Pixel USB" }, { "type", 11 }, { "isSource", true },
        { "isSink", true }, { "channelCounts", { 2 } }, { "sampleRates", { 48000 } } },
      { { "id", 3 }, { "name", "Phone" }, { "type", 15 }, { "isSource", true },
        { "isSink", false }, { "channelCounts", { 1 } }, { "sampleRates", json::array() } },
      { { "id", 9 }, { "name", "Call" }, { "type", 18 }, { "isSink", true } } }) } });
   CHECK(r.value("applied", false));
   auto devices = Req("audio.devices");
   const auto has = [](const json &list, const std::string &name) {
      for (const auto &d : list)
         if (d.value("name", "") == name)
            return true;
      return false;
   };
   CHECK(has(devices["outputs"], "USB device: Pixel USB"));
   CHECK(has(devices["inputs"], "USB device: Pixel USB"));
   CHECK(has(devices["inputs"], "Microphone: Phone"));
   CHECK(!has(devices["outputs"], "Microphone: Phone"));
   CHECK(!has(devices["outputs"], "Call"));
   CHECK(devices["outputs"].size() == 2 && devices["inputs"].size() == 3);

   // Playback after the PortAudio re-initialisation
   Tone(0.5);
   Req("select.set", { { "t0", 0.0 }, { "t1", 0.0 } });
   auto from = Mark();
   Req("transport.play");
   WaitTransport("playing", from);
   auto stopped = WaitTransport("stopped", from);
   if (stopped)
      CHECK(stopped->value("reason", "") == "end");

   // While busy: applied after the stop
   from = Mark();
   Req("transport.play", { { "t0", 0.0 } });
   WaitTransport("playing", from);
   r = Req("audio.setDevices", { { "devices", json::array() } });
   if (ReadT().State() == 1) {
      CHECK(!r.value("applied", true));
      CHECK(Req("audio.devices").value("pending", false));
   }
   StopAndWait();
   devices = Req("audio.devices");
   CHECK(!devices.value("pending", true));
   CHECK(devices["outputs"].size() == 1 && devices["inputs"].size() == 1);
}

} // namespace

int main()
{
   TempDirs dirs;
   gSink = std::make_shared<Sink>();
   // Nobody answers blocking dialogs in this test: cancel them
   gSink->onBlockingDialog = [](const json &d) {
      aubridge::ReplyDialog(d.value("id", 0), -1);
   };
   if (!aubridge::Start(dirs.ConfigJson(), gSink)) {
      std::fprintf(stderr, "Start failed\n");
      return 1;
   }
   auto ready = gSink->WaitFor("engine.ready", 120s);
   if (!ready) {
      std::fprintf(stderr, "engine not ready\n");
      aubridge::Stop();
      return 1;
   }

   // AUDIO_TEST_ONLY=<name> runs one group (after the permission setup)
   const char *only = std::getenv("AUDIO_TEST_ONLY");
   const auto run = [only](const char *name, void (*test)()) {
      if (!only || std::string(only) == name)
         test();
   };
   TestDevicesAndPermission();
   run("play", TestPlaySelection);
   run("loop", TestLoop);
   run("quickplay", TestQuickPlay);
   run("pause", TestPauseSeekSkip);
   run("record", TestRecordNewTrack);
   run("append", TestRecordAppend);
   run("dropouts", TestDropouts);
   run("overdub", TestOverdubLoopback);
   run("routes", TestOverdubRoutes);
   run("pausecause", TestPauseCause);
   run("inputoptions", TestInputOptions);
   run("monitor", TestMonitoring);
   run("devices", TestSetDevices);

   // Stopping the engine while monitoring closes the stream cleanly
   Req("transport.monitor", { { "enabled", true } });
   aubridge::Stop();
   CHECK(PaNull_GetMisuseCount() == 0);
   if (PaNull_GetMisuseCount())
      std::fprintf(stderr, "misuse: %s\n", PaNull_GetLastMisuse());

   std::fprintf(stderr, "%s: %d failure(s)\n",
      Failures() ? "FAILED" : "OK", Failures());
   return Failures() ? 1 : 0;
}
