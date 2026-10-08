/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  DisplayTest.cpp

  Host test of the bridge "display" module (API.md §7), driven only through
  Bridge.h:
   * waveColumns of sine tones at the raw-sample, 256-summary and
     64k-summary tiers and at the last column-mode level, compared with
     min/max/rms computed from the samples (WaveDataCache's column
     partition, summary frames and continuity smoothing replicated here)
   * NaN gaps between clips, a clip at a non-aligned offset, stereo
   * tiles: adjacent tiles equal one wide request in any request order,
     before and after display.trimCaches
   * per-clip sample mode (a stretched clip) and the -4 rule; waveSamples
     (values, envelope, binary layout, limits)
   * envelopeColumns after debug.addEnvelopePoint
   * waveVersion: changes with an edit of the track, not with edits (and
     undo/redo) of another track; returns to the old value after undo
   * spectrogramColumns of a 1 kHz sine peaks in the right row
   * performance: a 10-minute track at fit zoom, cold, < 200 ms
   * argument errors, synthetic ids without a recording

  Exit code 0 on success.  BRIDGE_TEST_VERBOSE=1 prints the events.

**********************************************************************/
#include "BridgeTestSupport.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <optional>

using namespace bridgetest;
using namespace std::chrono_literals;

namespace {

std::shared_ptr<Sink> gSink;
TempDirs *gDirs = nullptr;

constexpr double kRate = 44100;
//! Samples per float sample block (1 MiB, lib-wave-track Sequence.cpp)
constexpr int64_t kBlock = 262144;
constexpr int64_t kTile = 256;
constexpr int64_t kPartialBit = int64_t(1) << 62;

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

json Snap()
{
   auto r = Call("project.snapshot");
   CHECK(Ok(r));
   return Ok(r) ? r["result"] : json::object();
}

json TrackOf(const json &snap, int64_t id)
{
   for (const auto &t : snap["tracks"])
      if (t.value("id", int64_t(-100)) == id)
         return t;
   return json::object();
}

int64_t VersionOf(int64_t id)
{
   return TrackOf(Snap(), id).value("waveVersion", int64_t(-1));
}

double Pps(int level)
{
   return std::pow(2.0, level / 8.0);
}

int64_t Position(double pps, double t)
{
   return int64_t(std::floor(0.5 + pps * t));
}

//! Sample k of debug.makeTestTrack (AppCommands.cpp MakeTestTrack)
float Sine(int64_t k, double frequency, int channel = 0,
   double amplitude = 0.5)
{
   return float(amplitude * std::sin(2 * M_PI * frequency * double(k) / kRate +
      channel * M_PI / 2));
}

int64_t MakeTone(double seconds, double frequency, int channels = 1)
{
   // The project rate follows the device (48 kHz in the test config)
   return REQ("debug.makeTestTrack", { { "seconds", seconds },
      { "frequency", frequency }, { "channels", channels },
      { "rate", kRate } })
      .value("id", int64_t(-1));
}

void RemoveAllTracks()
{
   std::vector<int64_t> ids;
   const auto snap = Snap();
   for (const auto &t : snap["tracks"])
      ids.push_back(t.value("id", int64_t(-1)));
   if (!ids.empty())
      REQ("tracks.remove", { { "ids", ids } });
   CHECK(Snap()["tracks"].empty());
}

// ---------------------------------------------------------------------------

struct Tile {
   int64_t status = 0;
   std::vector<float> min, max, rms;
};

Tile Columns(int64_t id, int channel, int level, int64_t c0, int n)
{
   std::vector<float> out(size_t(n) * 3, -7.0f);
   Tile tile;
   tile.status = aubridge::WaveColumns(id, channel, level, c0, n, out.data(),
      out.size());
   tile.min.assign(out.begin(), out.begin() + n);
   tile.max.assign(out.begin() + n, out.begin() + 2 * n);
   tile.rms.assign(out.begin() + 2 * n, out.end());
   return tile;
}

bool SameFloat(float a, float b)
{
   return (std::isnan(a) && std::isnan(b)) ||
      std::memcmp(&a, &b, sizeof a) == 0;
}

bool SameTile(const Tile &a, const Tile &b, size_t offsetInB = 0)
{
   for (size_t j = 0; j < a.min.size(); ++j)
      if (!SameFloat(a.min[j], b.min[offsetInB + j]) ||
          !SameFloat(a.max[j], b.max[offsetInB + j]) ||
          !SameFloat(a.rms[j], b.rms[offsetInB + j]))
         return false;
   return true;
}

//! Expected value of one column (has = false: NaN)
struct Col {
   float min = 0, max = 0, rms = 0;
   bool has = false;
};

//! min/max/rms of sequence samples [a, b) the way WaveDataCache gets them:
//! F = 1 (samples), 256 or 65536 (summary frames of each sample block: a
//! column covers the frames floor(from/F) ... + ceil(count/F) - 1 of each
//! block it touches; rms = sqrt(mean of frame rms^2))
Col Summarize(const std::vector<float> &x, int64_t a, int64_t b, int64_t F)
{
   Col col;
   const int64_t n = int64_t(x.size());
   b = std::min(b, n);
   if (a >= b)
      return col;
   float mn = std::numeric_limits<float>::infinity();
   float mx = -mn;
   double squares = 0, items = 0;
   for (int64_t cur = a; cur < b;) {
      const int64_t blockStart = cur / kBlock * kBlock;
      const int64_t blockEnd = std::min(blockStart + kBlock, n);
      const int64_t part = std::min(b, blockEnd) - cur;
      if (F == 1) {
         for (int64_t i = cur; i < cur + part; ++i) {
            mn = std::min(mn, x[size_t(i)]);
            mx = std::max(mx, x[size_t(i)]);
            squares += double(x[size_t(i)]) * double(x[size_t(i)]);
            items += 1;
         }
      }
      else {
         const int64_t f0 = (cur - blockStart) / F;
         const int64_t nf = (part + F - 1) / F;
         for (int64_t f = f0; f < f0 + nf; ++f) {
            const int64_t fs = blockStart + f * F;
            const int64_t fe = std::min(fs + F, blockEnd);
            if (fs >= fe)
               break;
            float fmin = x[size_t(fs)], fmax = x[size_t(fs)];
            double sum = 0;
            for (int64_t i = fs; i < fe; ++i) {
               fmin = std::min(fmin, x[size_t(i)]);
               fmax = std::max(fmax, x[size_t(i)]);
               sum += double(x[size_t(i)]) * double(x[size_t(i)]);
            }
            const float frms = float(std::sqrt(sum / double(fe - fs)));
            mn = std::min(mn, fmin);
            mx = std::max(mx, fmax);
            squares += double(frms) * double(frms) * double(F);
            items += double(F);
         }
      }
      cur += part;
   }
   col.min = mn;
   col.max = mx;
   col.rms = float(std::sqrt(squares / items));
   col.has = true;
   return col;
}

//! WaveDataCache's continuity rule (WaveDataCache.cpp InitializeElement,
//! WaveCacheElement::Smooth)
void SmoothWith(Col &cur, const Col &prev)
{
   if (!cur.has || !prev.has)
      return;
   bool updated = false;
   if (prev.min > cur.max) {
      cur.max = prev.min;
      updated = true;
   }
   if (prev.max < cur.min) {
      cur.min = prev.max;
      updated = true;
   }
   if (updated)
      cur.rms = std::clamp(cur.rms, cur.min, cur.max);
}

//! A clip as the snapshot describes it, plus its sequence samples
struct ClipModel {
   double playStart = 0, playEnd = 0, sequenceStart = 0;
   double stretch = 1, rate = kRate;
   std::vector<float> samples;
};

//! The clips of a track built from a sine made by debug.makeTestTrack at
//! time 0 and later moved by `moved` seconds (unstretched clips)
std::vector<ClipModel> ModelClips(int64_t id, double frequency, int channel,
   double moved = 0)
{
   std::vector<ClipModel> result;
   const auto track = TrackOf(Snap(), id);
   for (const auto &c : track["clips"]) {
      ClipModel m;
      m.playStart = c.value("start", 0.0);
      m.playEnd = c.value("end", 0.0);
      m.stretch = c.value("stretchRatio", 1.0);
      m.rate = c.value("rate", kRate);
      const double trimLeft = c.value("trimLeft", 0.0);
      const double trimRight = c.value("trimRight", 0.0);
      m.sequenceStart = m.playStart - trimLeft;
      const auto n = std::llround((m.playEnd + trimRight - m.sequenceStart) *
         m.rate / m.stretch);
      const auto offset = std::llround((m.sequenceStart - moved) * m.rate);
      m.samples.resize(size_t(n));
      for (int64_t i = 0; i < n; ++i)
         m.samples[size_t(i)] = Sine(offset + i, frequency, channel);
      result.push_back(std::move(m));
   }
   return result;
}

//! Expected columns [c0, c0 + n) of a clip (has = false outside it, or in
//! sample mode)
std::vector<Col> ExpectedClip(const ClipModel &clip, double pps, int64_t c0,
   int64_t n)
{
   std::vector<Col> out(static_cast<size_t>(n), Col{});
   const double scaled = clip.rate / clip.stretch;
   if (pps > 0.5 * scaled)
      return out;
   const double spp = scaled / pps;
   const int64_t F = spp >= 65536 ? 65536 : spp >= 256 ? 256 : 1;
   const int64_t first = Position(pps, clip.playStart);
   const int64_t end = std::max(first + 1,
      Position(pps, clip.playEnd - 0.99 * clip.stretch / clip.rate));
   const int64_t shift = Position(pps, clip.sequenceStart);
   const int64_t nData = int64_t(clip.samples.size());
   const int64_t needEnd = std::min(end, c0 + n) - shift;

   // Sequence-local columns: element = 256 columns from sample
   // int64(col0 * spp); column k = [round(spp k), round(spp (k+1)))
   std::vector<Col> local;
   for (int64_t lc = 0; lc < needEnd; ++lc) {
      const int64_t col0 = lc / kTile * kTile, k = lc - col0;
      const int64_t base = static_cast<int64_t>(col0 * spp);
      const int64_t a = base + int64_t(std::round(spp * double(k)));
      const int64_t b = base + int64_t(std::round(spp * double(k + 1)));
      if (a >= nData)
         break;
      Col col = Summarize(clip.samples, a, b, F);
      if (k > 0)
         SmoothWith(col, local.back());   // inside the element: raw column 0
      local.push_back(col);
   }
   // Then the first column of each element joins the previous element
   for (size_t e = size_t(kTile); e < local.size(); e += size_t(kTile))
      SmoothWith(local[e], local[e - 1]);

   for (int64_t j = 0; j < n; ++j) {
      const int64_t c = c0 + j;
      if (c < first || c >= end)
         continue;
      const int64_t lc = c - shift;
      if (lc < 0 || lc >= int64_t(local.size()))
         continue;
      out[size_t(j)] = local[size_t(lc)];
   }
   return out;
}

std::vector<Col> ExpectedTrack(const std::vector<ClipModel> &clips, double pps,
   int64_t c0, int64_t n)
{
   std::vector<Col> out(static_cast<size_t>(n), Col{});
   for (const auto &clip : clips) {
      auto cols = ExpectedClip(clip, pps, c0, n);
      for (size_t j = 0; j < cols.size(); ++j)
         if (cols[j].has)
            out[j] = cols[j];
   }
   return out;
}

//! Compares a tile with the expected columns; one CHECK per tile
void CompareTile(const Tile &tile, const std::vector<Col> &expected,
   size_t offset, double tolerance, const std::string &what)
{
   int mismatches = 0;
   std::string first;
   for (size_t j = 0; j < tile.min.size(); ++j) {
      const Col &e = expected[offset + j];
      bool ok;
      if (!e.has)
         ok = std::isnan(tile.min[j]) && std::isnan(tile.max[j]) &&
            std::isnan(tile.rms[j]);
      else
         ok = std::fabs(tile.min[j] - e.min) <= tolerance &&
            std::fabs(tile.max[j] - e.max) <= tolerance &&
            std::fabs(tile.rms[j] - e.rms) <= tolerance;
      if (!ok && mismatches++ == 0) {
         char buf[256];
         std::snprintf(buf, sizeof buf,
            "column +%zu: got (%g %g %g) expected %s(%g %g %g)", j,
            tile.min[j], tile.max[j], tile.rms[j], e.has ? "" : "NaN ",
            e.min, e.max, e.rms);
         first = buf;
      }
   }
   CHECK_MSG(mismatches == 0, what + ": " + std::to_string(mismatches) +
      " mismatching columns, first " + first);
}

//! Requests the tiles [t0, t1) and compares each with the expectation
void CheckTiles(int64_t id, int channel, int level,
   const std::vector<ClipModel> &clips, const std::vector<int64_t> &tiles,
   double tolerance, const std::string &what)
{
   const double pps = Pps(level);
   int64_t maxEnd = 0;
   for (auto t : tiles)
      maxEnd = std::max(maxEnd, (t + 1) * kTile);
   const auto expected = ExpectedTrack(clips, pps, 0, maxEnd);
   for (auto t : tiles) {
      const auto tile = Columns(id, channel, level, t * kTile, int(kTile));
      CHECK_MSG(tile.status >= 0 && !(tile.status & kPartialBit),
         what + " tile " + std::to_string(t) + " status " +
         std::to_string(tile.status));
      CompareTile(tile, expected, size_t(t * kTile), tolerance,
         what + " level " + std::to_string(level) + " tile " + std::to_string(t));
   }
}

struct Run {
   int32_t clipIndex = 0;
   double firstSampleTime = 0, samplePeriod = 0;
   std::vector<float> values, envelope;
};

//! API.md §7.4 layout; nullopt for the error (empty) result
std::optional<std::vector<Run>> Samples(int64_t id, int channel, double t0,
   double t1)
{
   const auto bytes = aubridge::WaveSamples(id, channel, t0, t1);
   if (bytes.empty())
      return std::nullopt;
   size_t pos = 0;
   bool bad = false;
   auto get = [&](void *dst, size_t n) {
      if (pos + n > bytes.size()) {
         bad = true;
         std::memset(dst, 0, n);
         return;
      }
      std::memcpy(dst, bytes.data() + pos, n);
      pos += n;
   };
   int32_t count = 0;
   get(&count, 4);
   std::vector<Run> runs;
   for (int32_t i = 0; i < count && !bad; ++i) {
      Run run;
      int32_t n = 0;
      get(&run.clipIndex, 4);
      get(&run.firstSampleTime, 8);
      get(&run.samplePeriod, 8);
      get(&n, 4);
      if (n < 0 || bad)
         return std::nullopt;
      run.values.resize(size_t(n));
      run.envelope.resize(size_t(n));
      get(run.values.data(), size_t(n) * 4);
      get(run.envelope.data(), size_t(n) * 4);
      runs.push_back(std::move(run));
   }
   CHECK_MSG(!bad && pos == bytes.size(), "waveSamples layout");
   return runs;
}

// ---------------------------------------------------------------------------

void TestZoomLevels()
{
   std::fprintf(stderr, "-- zoom levels\n");
   RemoveAllTracks();
   const double frequency = 3.0;
   const int64_t id = MakeTone(30, frequency);
   const auto clips = ModelClips(id, frequency, 0);
   CHECK(clips.size() == 1);

   // raw samples: spp 86 and 7.6; last column-mode level (spp 2.07)
   CheckTiles(id, 0, 72, clips, { 0, 1, 2, 30, 59, 60, 61 }, 1e-5, "raw");
   CheckTiles(id, 0, 100, clips, { 0, 1, 3, 400, 401, 676, 677, 678 }, 1e-5, "raw2");
   CheckTiles(id, 0, 115, clips, { 0, 1, 2 }, 1e-5, "raw3");
   // 256-sample summaries: spp 1378 (pps power of two) and 531 (rounding
   // case of WaveDataCache's IsComplete)
   CheckTiles(id, 0, 40, clips, { 0, 1, 3, 4 }, 2e-4, "summary256");
   CheckTiles(id, 0, 51, clips, { 0, 1, 9, 10, 11 }, 2e-4, "summary256b");
   // 64k summaries: spp 88200
   CheckTiles(id, 0, -8, clips, { 0, 1 }, 2e-4, "summary64k");
   // Repeated requests (warm cache) give identical tiles
   for (int level : { 72, 51, 40, -8 }) {
      const auto a = Columns(id, 0, level, 0, int(kTile));
      const auto b = Columns(id, 0, level, 0, int(kTile));
      CHECK(a.status == b.status && SameTile(a, b));
   }
   // Columns before the track and long after it: NaN, status = version
   const auto version = VersionOf(id);
   auto before = Columns(id, 0, 40, -256, 256);
   CHECK(before.status == version);
   CHECK(std::all_of(before.min.begin(), before.min.end(),
      [](float v) { return std::isnan(v); }));
   auto after = Columns(id, 0, 40, 256 * 100, 256);
   CHECK(after.status == version);
   CHECK(std::isnan(after.max[0]) && std::isnan(after.rms[255]));
   // The status is the snapshot's waveVersion
   CHECK(Columns(id, 0, 40, 0, 256).status == version);
   CHECK(version >= 0 && version < kPartialBit);
}

void TestGapAndOffset()
{
   std::fprintf(stderr, "-- gaps, offsets\n");
   RemoveAllTracks();
   const double frequency = 5.0;
   // Two clips with a gap: [0, 1) and [1.5, 4)
   const int64_t id = MakeTone(4, frequency);
   REQ("select.set", { { "t0", 1.0 }, { "t1", 1.5 }, { "trackIds", { id } } });
   REQ("edit.splitDelete");
   const auto clips = ModelClips(id, frequency, 0);
   CHECK(clips.size() == 2);
   // pps 256: clip 0 = columns [0, 256), gap [256, 384), clip 1 from 384
   const auto tile = Columns(id, 0, 64, 256, 256);
   CHECK(tile.status >= 0);
   for (int j = 0; j < 128; ++j)
      CHECK_MSG(std::isnan(tile.min[size_t(j)]) && std::isnan(tile.max[size_t(j)]),
         "gap column " + std::to_string(256 + j));
   CHECK(!std::isnan(tile.min[128]) && !std::isnan(tile.max[255]));
   const auto tile0 = Columns(id, 0, 64, 0, 256);
   CHECK(!std::isnan(tile0.min[255]));
   CheckTiles(id, 0, 64, clips, { 0, 1, 2, 3 }, 1e-5, "gap");
   CheckTiles(id, 0, 90, clips, { 23, 24, 25, 35, 36, 37 }, 1e-5, "gap2");

   // A clip at a non-aligned offset
   RemoveAllTracks();
   const int64_t moved = MakeTone(10, frequency);
   auto snap = Snap();
   auto r = REQ("clips.move", { { "trackId", moved }, { "clipIndex", 0 },
      { "generation", snap["generation"] }, { "newStart", 0.3337 } });
   const double start = r.value("start", 0.0);
   CHECK(std::fabs(start - 0.3337) < 1.0 / kRate);
   const auto movedClips = ModelClips(moved, frequency, 0, start);
   CHECK(movedClips.size() == 1);
   for (int level : { 64, 40, 77 }) {
      const double pps = Pps(level);
      const int64_t firstColumn = Position(pps, start);
      const auto t = Columns(moved, 0, level,
         firstColumn / kTile * kTile, int(kTile));
      const size_t j = size_t(firstColumn % kTile);
      CHECK_MSG(!std::isnan(t.min[j]), "first column " + std::to_string(level));
      if (j > 0)
         CHECK(std::isnan(t.min[j - 1]));
   }
   CheckTiles(moved, 0, 64, movedClips, { 0, 1, 2, 3, 10, 11 }, 1e-5, "offset");
   CheckTiles(moved, 0, 40, movedClips, { 0, 1, 2 }, 2e-4, "offset-summary");
   CheckTiles(moved, 0, 77, movedClips, { 0, 1, 2, 5, 6, 7 }, 1e-5, "offset77");
}

void TestStereo()
{
   std::fprintf(stderr, "-- stereo\n");
   RemoveAllTracks();
   const double frequency = 2.0;
   const int64_t id = MakeTone(5, frequency, 2);
   const auto left = ModelClips(id, frequency, 0);
   const auto right = ModelClips(id, frequency, 1);
   CheckTiles(id, 0, 72, left, { 0, 1, 2, 3 }, 1e-5, "stereo L");
   CheckTiles(id, 1, 72, right, { 0, 1, 2, 3 }, 1e-5, "stereo R");
   CheckTiles(id, 1, 40, right, { 0 }, 2e-4, "stereo R summary");
   // The channels differ (sine / cosine)
   const auto l = Columns(id, 0, 72, 0, 256);
   const auto rt = Columns(id, 1, 72, 0, 256);
   CHECK(std::fabs(l.min[0] - rt.min[0]) > 0.1);
   // No third channel
   CHECK(Columns(id, 2, 72, 0, 256).status == -1);
   CHECK(Columns(id, -1, 72, 0, 256).status == -1);
   // Envelope columns are per track
   std::vector<float> env(256);
   CHECK(aubridge::EnvelopeColumns(id, 72, 0, 256, env.data(), env.size()) >= 0);
   CHECK(env[0] == 1.0f);
}

void TestTileConsistency()
{
   std::fprintf(stderr, "-- tile consistency\n");
   RemoveAllTracks();
   const double frequency = 7.0;
   const int64_t id = MakeTone(10, frequency);
   auto snap = Snap();
   REQ("clips.move", { { "trackId", id }, { "clipIndex", 0 },
      { "generation", snap["generation"] }, { "newStart", 0.4567 } });
   for (int level : { 64, 48, 83 }) {
      // Out-of-order single tiles after a cache flush ...
      CHECK(REQ("display.trimCaches", { { "budgetBytes", 0 } })
         .value("bytes", int64_t(-1)) == 0);
      const auto t3 = Columns(id, 0, level, 3 * kTile, int(kTile));
      const auto t2 = Columns(id, 0, level, 2 * kTile, int(kTile));
      const auto t1 = Columns(id, 0, level, 1 * kTile, int(kTile));
      // ... equal one wide request, before and after another flush
      const auto wide = Columns(id, 0, level, kTile, int(3 * kTile));
      CHECK(t1.status == wide.status && t2.status == wide.status);
      CHECK_MSG(SameTile(t1, wide, 0) && SameTile(t2, wide, size_t(kTile)) &&
         SameTile(t3, wide, size_t(2 * kTile)),
         "adjacent tiles at level " + std::to_string(level));
      REQ("display.trimCaches", { { "budgetBytes", 0 } });
      const auto wide2 = Columns(id, 0, level, kTile, int(3 * kTile));
      CHECK(SameTile(wide2, wide));
      // Odd, unaligned requests agree too
      const auto odd = Columns(id, 0, level, kTile + 100, 300);
      CHECK(SameTile(odd, wide, 100));
   }
}

void TestSampleMode()
{
   std::fprintf(stderr, "-- sample mode\n");
   RemoveAllTracks();
   const double frequency = 440.0;
   const int64_t id = MakeTone(2, frequency);
   REQ("select.set", { { "t0", 1.0 }, { "t1", 1.0 }, { "trackIds", { id } } });
   REQ("edit.split");
   CHECK(TrackOf(Snap(), id)["clips"].size() == 2);
   REQ("debug.stretchClip", { { "trackId", id }, { "clipIndex", 1 },
      { "ratio", 2.0 } });
   auto track = TrackOf(Snap(), id);
   CHECK(std::fabs(track["clips"][1].value("stretchRatio", 0.0) - 2.0) < 1e-9);
   CHECK(std::fabs(track["clips"][1].value("end", 0.0) - 3.0) < 1e-6);

   // pps 13777: clip 0 (44100 Hz) in column mode, clip 1 (22050 Hz
   // effective) in sample mode
   {
      const int level = 110;
      const double pps = Pps(level);
      const int64_t boundary = Position(pps, 1.0);
      const int64_t c0 = boundary / kTile * kTile;
      const auto tile = Columns(id, 0, level, c0, int(kTile));
      CHECK_MSG(tile.status >= 0, "mixed tile " + std::to_string(tile.status));
      const size_t j = size_t(boundary - c0);
      CHECK(j >= 2);
      CHECK(!std::isnan(tile.min[j - 2]));
      for (size_t k = j; k < size_t(kTile); ++k)
         CHECK(std::isnan(tile.min[k]));
      // Only the stretched clip: -4
      CHECK(Columns(id, 0, level, c0 + 8 * kTile, int(kTile)).status == -4);
      // Only clip 0: data
      CHECK(Columns(id, 0, level, 0, int(kTile)).status >= 0);
      // The column-mode clip is still exact
      const auto clips = ModelClips(id, frequency, 0);
      const auto expected = ExpectedClip(clips[0], pps, c0, kTile);
      CompareTile(tile, expected, 0, 1e-5, "mixed tile clip 0");
   }
   // pps 23170: both clips need samples
   {
      const int level = 116;
      const int64_t c0 = Position(Pps(level), 1.0) / kTile * kTile;
      CHECK(Columns(id, 0, level, c0, int(kTile)).status == -4);
      CHECK(Columns(id, 0, level, 0, int(kTile)).status == -4);
      // A gap after the last clip is not "sample mode": NaN, version
      CHECK(Columns(id, 0, level, Position(Pps(level), 3.5), 256).status >= 0);
   }

   // waveSamples around the split
   auto runs = Samples(id, 0, 0.999, 1.001);
   CHECK(runs.has_value());
   if (runs) {
      CHECK(runs->size() == 2);
      if (runs->size() == 2) {
         const auto &a = (*runs)[0];
         const auto &b = (*runs)[1];
         CHECK(a.clipIndex == 0 && b.clipIndex == 1);
         const int64_t s0 = int64_t(std::floor(0.999 * kRate));
         CHECK(std::fabs(a.firstSampleTime - double(s0) / kRate) < 1e-9);
         CHECK(std::fabs(a.samplePeriod - 1 / kRate) < 1e-12);
         CHECK(a.values.size() == size_t(44100 - s0));
         bool exact = true;
         for (size_t k = 0; k < a.values.size(); ++k)
            exact = exact && a.values[k] == Sine(s0 + int64_t(k), frequency) &&
               a.envelope[k] == 1.0f;
         CHECK_MSG(exact, "clip 0 samples");
         CHECK(std::fabs(b.firstSampleTime - 1.0) < 1e-9);
         CHECK(std::fabs(b.samplePeriod - 2 / kRate) < 1e-12);
         // ceil(0.001 * 22050) + 1 samples
         CHECK(b.values.size() == 24);
         exact = true;
         for (size_t k = 0; k < b.values.size(); ++k)
            exact = exact && b.values[k] == Sine(44100 + int64_t(k), frequency);
         CHECK_MSG(exact, "stretched clip samples");
      }
   }
   // A point inside clip 1, a range in no clip, errors
   auto point = Samples(id, 0, 2.0, 2.0);
   CHECK(point && point->size() == 1);
   auto none = Samples(id, 0, 3.5, 4.0);
   CHECK(none && none->empty());
   CHECK(!Samples(id, 0, 1.0, 0.5));
   CHECK(!Samples(id, 1, 0.0, 0.1));
   CHECK(!Samples(id + 1000, 0, 0.0, 0.1));
   CHECK(!Samples(id, 0, std::nan(""), 0.1));
}

double ExpEnvelope(double t, double t0, double v0, double t1, double v1)
{
   if (t <= t0)
      return v0;
   if (t >= t1)
      return v1;
   const double f = (t - t0) / (t1 - t0);
   return std::pow(10.0, std::log10(v0) + f * (std::log10(v1) - std::log10(v0)));
}

void TestEnvelope()
{
   std::fprintf(stderr, "-- envelope\n");
   RemoveAllTracks();
   const int64_t id = MakeTone(2, 100);
   std::vector<float> env(64, -7.0f);
   const int level = 32;   // pps 16
   const auto v0 = VersionOf(id);
   CHECK(aubridge::EnvelopeColumns(id, level, 0, 64, env.data(), env.size()) == v0);
   for (int c = 0; c < 32; ++c)
      CHECK(env[size_t(c)] == 1.0f);
   for (int c = 32; c < 64; ++c)
      CHECK(std::isnan(env[size_t(c)]));

   auto r = REQ("debug.addEnvelopePoint", { { "trackId", id }, { "t", 0.5 },
      { "value", 0.25 } });
   CHECK(r.value("clipIndex", -1) == 0);
   REQ("debug.addEnvelopePoint", { { "trackId", id }, { "t", 1.5 }, { "value", 1.0 } });
   const auto v1 = VersionOf(id);
   CHECK(v1 != v0 && v1 >= 0);
   CHECK(aubridge::EnvelopeColumns(id, level, 0, 64, env.data(), env.size()) == v1);
   bool ok = true;
   for (int c = 0; c < 32; ++c) {
      const double t = (c + 0.5) / 16;
      const double expected = ExpEnvelope(t, 0.5, 0.25, 1.5, 1.0);
      if (std::fabs(env[size_t(c)] - expected) > 1e-4) {
         ok = false;
         std::fprintf(stderr, "envelope column %d: %g expected %g\n", c,
            env[size_t(c)], expected);
      }
   }
   CHECK(ok);
   CHECK(std::isnan(env[32]));
   // The waveform columns are NOT multiplied by the envelope
   CHECK(Columns(id, 0, 64, 0, 256).status == v1);
   CheckTiles(id, 0, 64, ModelClips(id, 100, 0), { 0, 1 }, 1e-5, "enveloped");
   // waveSamples carries the envelope per sample
   auto runs = Samples(id, 0, 0.25, 0.2502);
   CHECK(runs && runs->size() == 1);
   if (runs && !runs->empty()) {
      const auto &run = (*runs)[0];
      CHECK(!run.values.empty());
      for (size_t k = 0; k < run.values.size(); ++k)
         CHECK(std::fabs(run.envelope[k] - 0.25f) < 1e-6);
   }
   auto mid = Samples(id, 0, 1.0, 1.0001);
   CHECK(mid && mid->size() == 1);
   if (mid && !mid->empty()) {
      const auto &run = (*mid)[0];
      const double t = run.firstSampleTime;
      CHECK(std::fabs(run.envelope[0] - ExpEnvelope(t, 0.5, 0.25, 1.5, 1.0)) < 1e-4);
   }
   // Errors
   CHECK(Err("debug.addEnvelopePoint", { { "trackId", id }, { "t", 5.0 },
      { "value", 0.5 } }) == "NOT_FOUND");
   CHECK(Err("debug.addEnvelopePoint", { { "trackId", id }, { "t", 1.0 },
      { "value", 3.0 } }) == "INVALID_ARGS");
   CHECK(aubridge::EnvelopeColumns(id + 999, level, 0, 64, env.data(), env.size()) == -1);
}

void TestWaveVersion()
{
   std::fprintf(stderr, "-- waveVersion\n");
   RemoveAllTracks();
   const int64_t a = MakeTone(1, 200);
   const int64_t b = MakeTone(1, 300);
   const auto va = VersionOf(a);
   const auto vb = VersionOf(b);
   CHECK(va >= 0 && vb >= 0 && va < kPartialBit && vb < kPartialBit);
   CHECK(Columns(a, 0, 40, 0, 256).status == va);

   // Edits of b (and their undo/redo) leave a alone
   REQ("select.set", { { "t0", 0.2 }, { "t1", 0.4 }, { "trackIds", { b } } });
   REQ("edit.silence");
   const auto vb2 = VersionOf(b);
   CHECK(vb2 != vb);
   CHECK(VersionOf(a) == va);
   REQ("history.undo");
   CHECK(VersionOf(b) == vb);
   CHECK(VersionOf(a) == va);
   REQ("history.redo");
   CHECK(VersionOf(b) == vb2);
   CHECK(VersionOf(a) == va);
   CHECK(Columns(a, 0, 40, 0, 256).status == va);
   // The silenced columns of b are 0 after the edit (the cache was rebuilt)
   const auto silent = Columns(b, 0, 64, 0, 256);   // pps 256
   CHECK(silent.status == vb2);
   CHECK(silent.min[60] == 0.0f && silent.max[90] == 0.0f);
   CHECK(silent.max[20] > 0.4f);

   // Edits of a change it; undo restores the same value
   REQ("select.set", { { "t0", 0.1 }, { "t1", 0.2 }, { "trackIds", { a } } });
   REQ("edit.silence");
   const auto va2 = VersionOf(a);
   CHECK(va2 != va);
   CHECK(Columns(a, 0, 40, 0, 256).status == va2);
   REQ("history.undo");
   CHECK(VersionOf(a) == va);
   const auto restored = Columns(a, 0, 64, 0, 256);
   CHECK(restored.status == va);
   CHECK(restored.max[40] > 0.3f);   // 0.156 s: not silent any more
   // Moving a clip changes it too
   auto snap = Snap();
   REQ("clips.move", { { "trackId", a }, { "clipIndex", 0 },
      { "generation", snap["generation"] }, { "newStart", 0.25 } });
   CHECK(VersionOf(a) != va);
}

void TestSpectrogram()
{
   std::fprintf(stderr, "-- spectrogram\n");
   RemoveAllTracks();
   const int64_t id = MakeTone(2, 1000);
   const int rows = 256, count = 256;
   std::vector<uint8_t> out(size_t(rows) * count, 7);
   const int level = 40;   // pps 32: clip = columns [0, 64)
   const auto status = aubridge::SpectrogramColumns(id, 0, level, 0, count,
      rows, out.data(), out.size());
   CHECK(status == VersionOf(id));
   // 1 kHz of 0 ... 22050 Hz in 256 rows: row 11 (11.6)
   const int expectedRow = int(1000.0 / (kRate / 2) * rows);
   int good = 0;
   for (int c = 1; c < 63; ++c) {
      const uint8_t *column = out.data() + size_t(c) * rows;
      const int peak = int(std::max_element(column, column + rows) - column);
      const bool quiet = std::all_of(column, column + 5,
            [](uint8_t v) { return v < 20; }) &&
         std::all_of(column + 20, column + rows, [](uint8_t v) { return v < 20; });
      if (peak == expectedRow && column[peak] == 255 && quiet)
         ++good;
      else if (good == c - 1)
         std::fprintf(stderr, "spectrogram column %d: peak row %d (%d)\n", c,
            peak, int(column[peak]));
   }
   CHECK_MSG(good == 62, "columns with the peak in row " +
      std::to_string(expectedRow) + ": " + std::to_string(good));
   // Outside the clip: 0
   CHECK(std::all_of(out.begin() + 64 * rows, out.end(),
      [](uint8_t v) { return v == 0; }));
   // Same result again (cached) and with another row count
   std::vector<uint8_t> again(out.size());
   CHECK(aubridge::SpectrogramColumns(id, 0, level, 0, count, rows,
      again.data(), again.size()) == status);
   CHECK(again == out);
   std::vector<uint8_t> coarse(64 * 64);
   CHECK(aubridge::SpectrogramColumns(id, 0, level, 0, 64, 64, coarse.data(),
      coarse.size()) == status);
   {
      const uint8_t *column = coarse.data() + 30 * 64;
      CHECK(std::max_element(column, column + 64) - column == 2);   // 2.9
   }
   // Tile after the clip: zeros, version
   std::fill(out.begin(), out.end(), 9);
   CHECK(aubridge::SpectrogramColumns(id, 0, level, 256, count, rows,
      out.data(), out.size()) == status);
   CHECK(std::all_of(out.begin(), out.end(), [](uint8_t v) { return v == 0; }));
   // Errors
   CHECK(aubridge::SpectrogramColumns(id, 0, level, 0, count, 0, out.data(),
      out.size()) == -1);
   CHECK(aubridge::SpectrogramColumns(id, 0, level, 0, count, 5000, out.data(),
      out.size()) == -1);
   CHECK(aubridge::SpectrogramColumns(id, 1, level, 0, count, rows, out.data(),
      out.size()) == -1);
   CHECK(aubridge::SpectrogramColumns(-2, 0, level, 0, count, rows, out.data(),
      out.size()) == -1);
}

void TestPerformanceAndTrim()
{
   std::fprintf(stderr, "-- performance, trimCaches\n");
   RemoveAllTracks();
   REQ("display.trimCaches", { { "budgetBytes", 0 } });
   const auto t0 = std::chrono::steady_clock::now();
   const int64_t id = MakeTone(600, 440);
   const auto t1 = std::chrono::steady_clock::now();
   std::fprintf(stderr, "   10-min track made in %.0f ms\n",
      std::chrono::duration<double, std::milli>(t1 - t0).count());
   REQ("display.setViewportWidth", { { "px", 1000 } });
   // Fit zoom for 1000 px: the level just below 1000 / 600 px/s
   const int level = int(std::floor(8 * std::log2(1000.0 / 600.0)));
   const double pps = Pps(level);
   const int64_t columns = Position(pps, 600.0);
   const int tiles = int((columns + kTile - 1) / kTile);
   std::vector<Tile> cold;
   const auto c0 = std::chrono::steady_clock::now();
   for (int t = 0; t < tiles; ++t)
      cold.push_back(Columns(id, 0, level, t * kTile, int(kTile)));
   const auto c1 = std::chrono::steady_clock::now();
   for (int t = 0; t < tiles; ++t)
      CHECK(Columns(id, 0, level, t * kTile, int(kTile)).status >= 0);
   const auto c2 = std::chrono::steady_clock::now();
   const double coldMs = std::chrono::duration<double, std::milli>(c1 - c0).count();
   const double warmMs = std::chrono::duration<double, std::milli>(c2 - c1).count();
   std::fprintf(stderr, "   fit zoom (level %d, %d tiles): cold %.1f ms, warm %.1f ms\n",
      level, tiles, coldMs, warmMs);
   CHECK_MSG(coldMs < 200, "cold fit-zoom columns took " + std::to_string(coldMs) + " ms");
   for (const auto &tile : cold)
      CHECK(tile.status >= 0);
   CHECK(!std::isnan(cold[0].max[0]) && std::fabs(cold[0].max[10] - 0.5f) < 0.01);
   CHECK(std::isnan(cold.back().max[size_t((columns - 1) % kTile) + 1]) ||
      (columns % kTile) == 0);

   // Colder: the project saved, closed and opened again (new database
   // connection, sample blocks loaded from the file)
   int64_t reopened = -1;
   {
      const std::string path = gDirs->filesDir + "/perf-display.aup3";
      REQ("project.saveAs", { { "path", path } });
      REQ("project.close");
      REQ("project.open", { { "path", path } });
      const auto snap = Snap();
      for (const auto &t : snap["tracks"])
         reopened = t.value("id", int64_t(-1));
      CHECK(reopened >= 0);
      const auto r0 = std::chrono::steady_clock::now();
      for (int t = 0; t < tiles; ++t) {
         const auto tile = Columns(reopened, 0, level, t * kTile, int(kTile));
         CHECK(tile.status >= 0 && SameTile(tile, cold[size_t(t)]));
      }
      const auto r1 = std::chrono::steady_clock::now();
      const double ms = std::chrono::duration<double, std::milli>(r1 - r0).count();
      std::fprintf(stderr, "   fit zoom after reopening: %.1f ms\n", ms);
      CHECK_MSG(ms < 200, "reopened fit-zoom columns took " + std::to_string(ms) + " ms");
   }

   // A spectrogram of the whole view (informational)
   {
      std::vector<uint8_t> out(size_t(kTile) * 128);
      const auto s0 = std::chrono::steady_clock::now();
      for (int t = 0; t < tiles; ++t)
         CHECK(aubridge::SpectrogramColumns(reopened, 0, level, t * kTile, int(kTile),
            128, out.data(), out.size()) >= 0);
      const auto s1 = std::chrono::steady_clock::now();
      const double ms = std::chrono::duration<double, std::milli>(s1 - s0).count();
      std::fprintf(stderr, "   fit zoom spectrogram: %.1f ms\n", ms);
      CHECK(ms < 5000);
   }
   // Too many samples for one waveSamples call
   CHECK(!Samples(reopened, 0, 0.0, 600.0));

   // trimCaches
   const auto total = REQ("display.trimCaches", { { "budgetBytes", 1e15 } })
      .value("bytes", int64_t(-1));
   std::fprintf(stderr, "   display caches: %lld bytes\n", (long long)total);
   CHECK(total > 0);
   CHECK(total < (int64_t(64) << 20));
   CHECK(REQ("display.trimCaches", { { "budgetBytes", 0 } })
      .value("bytes", int64_t(-1)) == 0);
   // Still correct after the flush
   for (int t = 0; t < tiles; ++t)
      CHECK(SameTile(Columns(reopened, 0, level, t * kTile, int(kTile)),
         cold[size_t(t)]));
   (void)id;
   CHECK(Err("display.trimCaches", { { "budgetBytes", -1 } }) == "INVALID_ARGS");
   CHECK(Err("display.trimCaches") == "INVALID_ARGS");
   CHECK(Err("display.setViewportWidth", { { "px", 0 } }) == "INVALID_ARGS");
}

void TestArguments()
{
   std::fprintf(stderr, "-- arguments, ids\n");
   RemoveAllTracks();
   const int64_t id = MakeTone(1, 440);
   std::vector<float> out(3 * 256);
   // No recording: synthetic ids resolve to nothing; -1 is never a track
   CHECK(aubridge::WaveColumns(-2, 0, 40, 0, 256, out.data(), out.size()) == -1);
   CHECK(aubridge::WaveColumns(-1, 0, 40, 0, 256, out.data(), out.size()) == -1);
   CHECK(aubridge::WaveColumns(id + 999, 0, 40, 0, 256, out.data(), out.size()) == -1);
   // Bad zoom level, count, buffer
   CHECK(aubridge::WaveColumns(id, 0, 161, 0, 256, out.data(), out.size()) == -1);
   CHECK(aubridge::WaveColumns(id, 0, -161, 0, 256, out.data(), out.size()) == -1);
   CHECK(aubridge::WaveColumns(id, 0, 40, 0, 0, out.data(), out.size()) == -1);
   CHECK(aubridge::WaveColumns(id, 0, 40, 0, 256, out.data(), 100) == -1);
   CHECK(aubridge::WaveColumns(id, 0, 160, 0, 256, out.data(), out.size()) == -4);
   CHECK(aubridge::WaveColumns(id, 0, -160, 0, 256, out.data(), out.size()) >= 0);
   // A label track is not a wave track
   const auto label = REQ("tracks.add", { { "kind", "label" } }).value("id", int64_t(-1));
   CHECK(aubridge::WaveColumns(label, 0, 40, 0, 256, out.data(), out.size()) == -1);
   CHECK(!Samples(label, 0, 0, 1));
   // Huge column numbers
   CHECK(aubridge::WaveColumns(id, 0, 40, int64_t(1) << 50, 256, out.data(),
      out.size()) >= 0);
   CHECK(aubridge::WaveColumns(id, 0, 40, -(int64_t(1) << 50), 256, out.data(),
      out.size()) >= 0);
   CHECK(aubridge::WaveColumns(id, 0, 40, int64_t(1) << 62, 256, out.data(),
      out.size()) == -1);
   CHECK(aubridge::EnvelopeColumns(id, 40, -(int64_t(1) << 62), 256, out.data(),
      out.size()) == -1);
   // Debug commands
   CHECK(Err("debug.stretchClip", { { "trackId", id }, { "clipIndex", 3 },
      { "ratio", 2.0 } }) == "NOT_FOUND");
   CHECK(Err("debug.stretchClip", { { "trackId", id }, { "clipIndex", 0 },
      { "ratio", 0.0 } }) == "INVALID_ARGS");
}

//! The pending recording targets' ids in the snapshot (the audio module
//! names pending new tracks -2, -3, ...; without it they stay -1)
std::vector<json> PendingEntries(const json &snap)
{
   std::vector<json> entries;
   for (const auto &t : snap["tracks"])
      if (t.value("id", int64_t(0)) < 0)
         entries.push_back(t);
   return entries;
}

ClipModel RecordingModel(double start, double seconds, double frequency,
   int channel = 0)
{
   ClipModel m;
   m.playStart = m.sequenceStart = start;
   const auto first = std::llround(start * kRate);
   const auto n = std::llround(seconds * kRate);
   m.playEnd = start + double(n) / kRate;
   m.samples.resize(size_t(n));
   for (int64_t i = 0; i < n; ++i)
      m.samples[size_t(i)] = Sine(first + i, frequency, channel);
   return m;
}

void TestRecording()
{
   std::fprintf(stderr, "-- simulated recording\n");
   RemoveAllTracks();
   // New recording tracks get the project rate
   REQ("project.setRate", { { "rate", kRate } });
   const double frequency = 440;
   const int64_t a = MakeTone(2, frequency);
   const auto va = VersionOf(a);
   CHECK(Columns(-2, 0, 64, 0, 256).status == -1);
   CHECK(Err("debug.recording", { { "action", "append" }, { "seconds", 1 } }) ==
      "INVALID_ARGS");

   // Record into a (new clip at 2.5 s) and into one new mono track
   REQ("debug.recording", { { "action", "start" }, { "trackId", a },
      { "t0", 2.5 }, { "newChannels", 1 } });
   CHECK(Err("debug.recording", { { "action", "start" } }) == "INVALID_ARGS");
   CHECK(PendingEntries(Snap()).size() == 1);
   const int level = 64;   // pps 256: 2.5 s = column 640
   // The empty recording clip: a tile reaching past its end is partial
   CHECK(Columns(-2, 0, level, 512, 256).status == -2);
   CHECK(Columns(-2, 0, level, 0, 256).status >= 0);
   CHECK(Columns(-3, 0, level, 0, 256).status == -1);

   // 1 s: everything is still in the append buffer
   REQ("debug.recording", { { "action", "append" }, { "seconds", 1.0 },
      { "frequency", frequency } });
   auto rec = RecordingModel(2.5, 1.0, frequency);
   {
      const auto tile = Columns(-2, 0, level, 512, 256);
      CHECK(tile.status >= 0 && (tile.status & kPartialBit));
      CompareTile(tile, ExpectedTrack({ rec }, Pps(level), 512, 256), 0, 1e-5,
         "recording tail");
      // The existing track draws its pending copy
      const auto mine = Columns(a, 0, level, 512, 256);
      CHECK(mine.status >= 0 && (mine.status & kPartialBit));
      CHECK(SameTile(mine, tile));
      // Its committed part is unchanged and not partial
      const auto old = Columns(a, 0, level, 0, 256);
      CHECK(old.status >= 0 && !(old.status & kPartialBit));
      CHECK(!std::isnan(old.max[10]));
      // The version is the recording target's, as in the snapshot
      for (const auto &t : PendingEntries(Snap()))
         if (t.value("id", int64_t(0)) == -2)
            CHECK(t.value("waveVersion", int64_t(-1)) == (tile.status & ~kPartialBit));
      CHECK((mine.status & ~kPartialBit) != va);
      // Samples come from the append buffer
      auto runs = Samples(-2, 0, 3.0, 3.0002);
      CHECK(runs && runs->size() == 1);
      if (runs && runs->size() == 1) {
         const auto &run = (*runs)[0];
         const int64_t s0 = int64_t(std::floor(0.5 * kRate));
         CHECK(run.values.size() >= 9);
         bool exact = true;
         for (size_t k = 0; k < run.values.size(); ++k)
            exact = exact && run.values[k] == rec.samples[size_t(s0) + k];
         CHECK_MSG(exact, "append buffer samples");
      }
      // Envelope: 1 inside the clip; final up to the clip's current end
      std::vector<float> env(256);
      const auto es = aubridge::EnvelopeColumns(-2, level, 512, 256, env.data(),
         env.size());
      CHECK(es >= 0 && !(es & kPartialBit));
      CHECK(std::isnan(env[127]) && env[128] == 1.0f && env[255] == 1.0f);
      CHECK(aubridge::EnvelopeColumns(-2, level, 768, 256, env.data(),
         env.size()) & kPartialBit);
      // Spectrogram: nothing committed yet
      std::vector<uint8_t> spec(256 * 64, 3);
      const auto ss = aubridge::SpectrogramColumns(-2, 0, level, 512, 256, 64,
         spec.data(), spec.size());
      CHECK(ss == -2 || (ss >= 0 && (ss & kPartialBit)));
      CHECK(std::all_of(spec.begin(), spec.end(), [](uint8_t v) { return v == 0; }));
   }

   // 9 s more: one block is committed, the rest is in the append buffer
   REQ("debug.recording", { { "action", "append" }, { "seconds", 9.0 },
      { "frequency", frequency } });
   rec = RecordingModel(2.5, 10.0, frequency);
   {
      const int lvl = 40;   // pps 32: the clip = columns [80, 400)
      const double pps = Pps(lvl);
      const auto expected = ExpectedTrack({ rec }, pps, 0, 512);
      const auto t0 = Columns(-2, 0, lvl, 0, 256);
      // Its columns end at local sample 176 * 1378 < 262144 committed
      CHECK(t0.status >= 0 && !(t0.status & kPartialBit));
      CompareTile(t0, expected, 0, 2e-4, "recording committed tile");
      const auto t1 = Columns(-2, 0, lvl, 256, 256);
      CHECK(t1.status >= 0 && (t1.status & kPartialBit));
      CompareTile(t1, expected, 256, 2e-4, "recording tail tile");
      // Same values from scratch
      REQ("display.trimCaches", { { "budgetBytes", 0 } });
      CHECK(SameTile(Columns(-2, 0, lvl, 256, 256), t1));
      CHECK(SameTile(Columns(-2, 0, lvl, 0, 256), t0));
      // Raw tier at the tail
      const auto tail = Columns(-2, 0, level, Position(Pps(level), 12.0) / 256 * 256, 256);
      CHECK(tail.status >= 0 && (tail.status & kPartialBit));
      // The committed part has a spectrogram; the tail columns are partial
      std::vector<uint8_t> spec(256 * 64);
      const auto ss = aubridge::SpectrogramColumns(-2, 0, lvl, 0, 256, 64,
         spec.data(), spec.size());
      CHECK(ss >= 0 && !(ss & kPartialBit));
      CHECK(spec[size_t(100) * 64 + 1] == 255);   // 440 Hz: row 1.28
      const auto ss1 = aubridge::SpectrogramColumns(-2, 0, lvl, 256, 256, 64,
         spec.data(), spec.size());
      CHECK(ss1 >= 0 && (ss1 & kPartialBit));
   }

   // Commit: the new track gets a real id
   REQ("debug.recording", { { "action", "commit" } });
   auto snap = Snap();
   CHECK(PendingEntries(snap).empty());
   CHECK(Columns(-2, 0, 64, 512, 256).status == -1);
   int64_t newId = -1;
   for (const auto &t : snap["tracks"])
      if (t.value("id", int64_t(-1)) != a)
         newId = t.value("id", int64_t(-1));
   CHECK(newId >= 0);
   {
      const auto clips = ModelClips(newId, frequency, 0);
      CHECK(clips.size() == 1);
      CheckTiles(newId, 0, 40, clips, { 0, 1, 2 }, 2e-4, "committed recording");
      CheckTiles(newId, 0, 64, clips, { 2, 3, 12, 13 }, 1e-5, "committed recording raw");
      CheckTiles(a, 0, 40, ModelClips(a, frequency, 0), { 0, 1, 2 }, 2e-4,
         "appended track");
      CHECK(TrackOf(snap, a)["clips"].size() == 2);
      CHECK(VersionOf(a) != va);
   }
   REQ("history.undo");
   CHECK(VersionOf(a) == va);
   CHECK(Columns(newId, 0, 40, 0, 256).status == -1);

   // Several new tracks, then cancel
   REQ("debug.recording", { { "action", "start" }, { "t0", 1.0 },
      { "newChannels", 3 } });
   REQ("debug.recording", { { "action", "append" }, { "seconds", 0.5 },
      { "frequency", frequency } });
   CHECK(PendingEntries(Snap()).size() == 3);
   for (int64_t id : { -2, -3, -4 }) {
      const auto tile = Columns(id, 0, 64, 256, 256);
      CHECK_MSG(tile.status >= 0 && (tile.status & kPartialBit),
         "synthetic id " + std::to_string(id));
      CompareTile(tile, ExpectedTrack({ RecordingModel(1.0, 0.5, frequency) },
         Pps(64), 256, 256), 0, 1e-5, "synthetic " + std::to_string(id));
   }
   CHECK(Columns(-5, 0, 64, 256, 256).status == -1);
   REQ("debug.recording", { { "action", "cancel" } });
   CHECK(Columns(-2, 0, 64, 256, 256).status == -1);
   CHECK_MSG(Snap()["tracks"].size() == 1, Snap()["tracks"].dump().substr(0, 2000));

   // A new stereo recording track: 3.7.9 gives it a real id at once (no
   // synthetic id); it is still drawn as a recording target
   REQ("debug.recording", { { "action", "start" }, { "t0", 0.0 },
      { "newChannels", 2 } });
   REQ("debug.recording", { { "action", "append" }, { "seconds", 0.25 },
      { "frequency", frequency } });
   int64_t stereo = -1;
   {
      const auto snap2 = Snap();
      for (const auto &t : snap2["tracks"])
         if (t.value("channels", 0) == 2)
            stereo = t.value("id", int64_t(-1));
   }
   CHECK(stereo >= 0);
   const auto st = Columns(stereo, 1, 64, 0, 256);
   CHECK(st.status >= 0 && (st.status & kPartialBit));
   CompareTile(st, ExpectedTrack({ RecordingModel(0, 0.25, frequency, 1) },
      Pps(64), 0, 256), 0, 1e-5, "stereo recording");
   CHECK(Columns(-2, 0, 64, 0, 256).status == -1);
   REQ("debug.recording", { { "action", "cancel" } });
   CHECK(Snap()["tracks"].size() == 1);
}

} // namespace

int main()
{
   TempDirs dirs;
   gDirs = &dirs;
   std::fprintf(stderr, "test root: %s\n", dirs.root.c_str());
   gSink = std::make_shared<Sink>();
   // Display calls before the engine runs: not ready
   std::vector<float> early(3 * 256);
   CHECK(aubridge::WaveColumns(0, 0, 0, 0, 256, early.data(), early.size()) == -3);
   if (!aubridge::Start(dirs.ConfigJson(), gSink)) {
      std::fprintf(stderr, "Start failed\n");
      return 1;
   }
   auto ready = gSink->WaitFor("engine.ready", 180s);
   if (!ready) {
      auto failed = gSink->Last("engine.failed");
      std::fprintf(stderr, "engine failed: %s\n",
         failed ? failed->dump().c_str() : "(timeout)");
      aubridge::Stop();
      return 1;
   }

   TestArguments();
   TestZoomLevels();
   TestGapAndOffset();
   TestStereo();
   TestTileConsistency();
   TestSampleMode();
   TestEnvelope();
   TestWaveVersion();
   TestSpectrogram();
   TestRecording();
   TestPerformanceAndTrim();

   aubridge::Stop();
   gSink.reset();
   if (Failures() == 0)
      std::fprintf(stderr, "bridge-test-display: all checks passed\n");
   else
      std::fprintf(stderr, "bridge-test-display: %d check(s) FAILED\n", Failures());
   return Failures() == 0 ? 0 : 1;
}
