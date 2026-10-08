/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  WaveDisplay.cpp

  Waveform display data (API.md §7.2 - §7.4): min/max/rms columns from the
  lib-wave-track-paint WaveDataCache of each clip, envelope columns and
  individual samples.  Replaces the data side of 3.7.9's
  src/tracks/playabletrack/wavetrack/ui/WaveformView.cpp (DrawWaveform,
  DrawIndividualSamples) and ClipParameters.cpp; the pixels are drawn in
  Kotlin.

  Coordinates: absolute column c covers [c/pps, (c+1)/pps).  WaveDataCache
  works in sequence-local columns (time 0 = the clip's sequence start S);
  local column lc is drawn at absolute column lc + floor(0.5 + pps*S), the
  same half-pixel rounding 3.7.9 accepts when it blits at
  ClipParameters::leftOffset.

**********************************************************************/
#include "DisplayInternal.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

#include "aubridge/Bridge.h"  // PpsForLevel
#include "ClipDisplayCache.h"
#include "DisplayTracks.h"
#include "Envelope.h"
#include "Hooks.h"
#include "SampleFormat.h"
#include "Sequence.h"
#include "Session.h"
#include "WaveClip.h"
#include "WaveTrack.h"
#include "ZoomInfo.h"
#include "waveform/WaveDataCache.h"

namespace aubridge::display {

namespace {

constexpr float kNaN = std::numeric_limits<float>::quiet_NaN();

//! First sample of sequence-local column `lc` as WaveDataCache computes it
//! (GraphicsDataCache.cpp PerformBaseLookup: element key
//! int64(column * spp); WaveDataCache.cpp InitializeElement: column k of an
//! element starts round(spp * k) samples later)
int64_t ColumnStartSample(int64_t lc, double spp)
{
   const int64_t col0 = lc / kElementColumns * kElementColumns;
   return static_cast<int64_t>(col0 * spp) +
      static_cast<int64_t>(std::round(spp * double(lc - col0)));
}

//! Exclusive end sample of sequence-local column `lc`
int64_t ColumnEndSample(int64_t lc, double spp)
{
   const int64_t col0 = lc / kElementColumns * kElementColumns;
   return static_cast<int64_t>(col0 * spp) +
      static_cast<int64_t>(std::round(spp * double(lc - col0 + 1)));
}

//! Exclusive end of the sequence-local columns that hold data
int64_t DataEndColumn(int64_t nSamples, double spp)
{
   if (nSamples <= 0 || !(spp > 0))
      return 0;
   int64_t e = std::max<int64_t>(0, int64_t(double(nSamples) / spp) - 1);
   while (e > 0 && ColumnStartSample(e - 1, spp) >= nSamples)
      --e;
   while (ColumnStartSample(e, spp) < nSamples)
      ++e;
   return e;
}

bool ValidRequest(int zoomLevel, int64_t firstColumn, int count)
{
   return count > 0 && count <= kMaxColumns && zoomLevel >= kMinZoomLevel &&
      zoomLevel <= kMaxZoomLevel && firstColumn >= -kMaxFirstColumn &&
      firstColumn <= kMaxFirstColumn;
}

//! The recording target grows at its rightmost clip (WaveTrack::Append ->
//! RightmostOrNewClip): a tile reaching past that clip's current end will
//! get more data
bool TailIsPartial(bool recording, bool lastClip, const ClipSpan &span,
   int64_t c1)
{
   return recording && lastClip && c1 > span.end && c1 > span.first;
}

int64_t Result(const WaveTrack &track, bool filled, bool partial,
   int intersecting, int sampleMode)
{
   const int64_t version = WaveVersion(track);
   if (filled)
      return partial ? (version | DisplayStatus::PartialBit) : version;
   if (partial)
      return DisplayStatus::Partial;
   if (intersecting > 0 && sampleMode == intersecting)
      return DisplayStatus::SampleModeNeeded;
   return version;
}

} // namespace

ClipSpan ComputeSpan(const WaveClip &clip, double pps)
{
   ClipSpan span;
   const double rate = clip.GetRate();
   const double stretch = clip.GetStretchRatio();
   span.scaledRate = rate / stretch;
   span.first = Position(pps, clip.GetPlayStartTime());
   // GetBlankSpaceBeforePlayEndTime: 0.99 sample period before the end
   span.end = std::max(span.first + 1,
      Position(pps, clip.GetPlayEndTime() - 0.99 * stretch / rate));
   span.sampleMode = pps > 0.5 * span.scaledRate;
   return span;
}

int64_t SequenceShift(const WaveClip &clip, double pps)
{
   return Position(pps, clip.GetSequenceStartTime());
}

int64_t WaveColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize)
{
   if (!out || !ValidRequest(zoomLevel, firstColumn, count) || outSize < size_t(count) * 3)
      return DisplayStatus::NoSuchTrack;
   std::fill(out, out + size_t(count) * 3, kNaN);
   auto *project = Session::Get().Project();
   if (!project)
      return DisplayStatus::NoSuchTrack;
   const auto resolved = ResolveDisplayTrack(*project, trackId);
   if (!resolved.track || channel < 0 ||
       size_t(channel) >= resolved.track->NChannels())
      return DisplayStatus::NoSuchTrack;
   const auto &track = *resolved.track;

   const double pps = PpsForLevel(zoomLevel);
   const ZoomInfo zoom{ 0.0, pps };
   const int64_t c0 = firstColumn, c1 = firstColumn + count;
   float *const outMin = out;
   float *const outMax = out + count;
   float *const outRms = out + 2 * size_t(count);

   bool filled = false, partial = false;
   int intersecting = 0, sampleMode = 0;
   const ClipDisplayCache *used = nullptr;
   const auto clips = track.SortedIntervalArray();
   for (size_t i = 0; i < clips.size(); ++i) {
      const WaveClip &clip = *clips[i];
      const auto span = ComputeSpan(clip, pps);
      if (TailIsPartial(resolved.recording, i + 1 == clips.size(), span, c1))
         partial = true;
      if (span.end <= c0 || span.first >= c1)
         continue;
      ++intersecting;
      if (span.sampleMode) {
         // Kotlin asks waveSamples for this clip; its columns stay NaN
         ++sampleMode;
         continue;
      }
      const Sequence *sequence = clip.GetSequence(size_t(channel));
      if (!sequence)
         continue;

      // Absolute columns [a, b) of this clip in the request ...
      const int64_t a = std::max(span.first, c0);
      const int64_t b = std::min(span.end, c1);
      // ... as sequence-local columns, clamped to the data (committed and
      // append buffer: WaveDataCache reads both)
      const int64_t shift = SequenceShift(clip, pps);
      const double spp = span.scaledRate / pps;
      const int64_t committed = sequence->GetNumSamples().as_long_long();
      const int64_t nData = committed + int64_t(sequence->GetAppendBufferLen());
      const int64_t la = std::max<int64_t>(a - shift, 0);
      const int64_t lb = std::min<int64_t>(b - shift, DataEndColumn(nData, spp));
      if (la >= lb)
         continue;

      // The lookup starts at the last column of the previous element, so
      // that WaveCacheElement::Smooth always joins an element to its
      // predecessor (results do not depend on the order of requests) and
      // the lookup never covers a single sample (an empty result,
      // GraphicsDataCache.cpp IsSameSample)
      const int64_t elementStart = la / kElementColumns * kElementColumns;
      const int64_t lookA = elementStart > 0 ? std::min(la, elementStart - 1) : la;
      int64_t lookB = lb;
      if (lookB - lookA < 2)
         lookB = lookA + 2;   // only when lookA == 0: stays in element 0

      auto &cache = ClipDisplayCache::Get(clip);
      cache.Sync(clip, resolved.recording);
      cache.Touch();
      used = &cache;
      auto &waveCache = cache.WaveCache(clip, size_t(channel), spp);
      auto range = waveCache.PerformLookup(zoom, double(lookA) / pps,
         double(lookB - 1) / pps);

      // GraphicsDataCache smooths an element against its predecessor only
      // when the lookup created or refreshed one of them, and never the
      // first element of a lookup: join every element of this lookup to the
      // previous one again (WaveCacheElement::Smooth is idempotent), so a
      // column has the same value whatever tiles were requested before
      WaveCacheElement *previous = nullptr;
      for (auto it = range.begin(); it != range.end(); ++it) {
         WaveCacheElement &element = *it;
         if (previous)
            element.Smooth(previous);
         previous = &element;
      }

      // While recording, columns reaching into the uncommitted tail change
      if (resolved.recording && ColumnEndSample(lb - 1, spp) > committed)
         partial = true;

      int64_t col0 = lookA / kElementColumns * kElementColumns;
      for (auto it = range.begin(); it != range.end();
           ++it, col0 += kElementColumns) {
         WaveCacheElement &element = *it;
         if (!element.IsComplete) {
            // WaveDataCache compares round(256 spp) processed samples with
            // trunc(256 spp) (WaveDataCache.cpp:382), so at half of the zoom
            // levels no element ever completes and all are recomputed on
            // every lookup.  A full element whose samples are all committed
            // is final: committed samples only change with
            // WaveClip::MarkChanged, which rebuilds the cache (recording only
            // appends).  The element at the data end stays incomplete and is
            // refreshed by every lookup, as in 3.7.9.
            const int64_t first = static_cast<int64_t>(col0 * spp);
            const int64_t last = first + static_cast<int64_t>(
               std::round(spp * double(kElementColumns)));
            if (element.AvailableColumns >= size_t(kElementColumns) &&
                last <= committed)
               element.IsComplete = true;
         }
         // Columns at or past AvailableColumns hold stale data of a
         // recycled element
         const size_t available =
            std::min<size_t>(element.AvailableColumns, kElementColumns);
         for (size_t k = 0; k < available; ++k) {
            const int64_t lc = col0 + int64_t(k);
            if (lc < la || lc >= lb)
               continue;
            const int64_t j = lc + shift - c0;
            if (j < 0 || j >= count)
               continue;
            const auto &column = element.Data[k];
            outMin[j] = column.min;
            outMax[j] = column.max;
            outRms[j] = column.rms;
            filled = true;
         }
      }
   }
   if (used)
      TrimDisplayCaches(kStandingBudgetBytes, used);
   return Result(track, filled, partial, intersecting, sampleMode);
}

int64_t EnvelopeColumns(int64_t trackId, int zoomLevel, int64_t firstColumn,
   int count, float *out, size_t outSize)
{
   if (!out || !ValidRequest(zoomLevel, firstColumn, count) || outSize < size_t(count))
      return DisplayStatus::NoSuchTrack;
   std::fill(out, out + count, kNaN);
   auto *project = Session::Get().Project();
   if (!project)
      return DisplayStatus::NoSuchTrack;
   const auto resolved = ResolveDisplayTrack(*project, trackId);
   if (!resolved.track)
      return DisplayStatus::NoSuchTrack;
   const auto &track = *resolved.track;

   const double pps = PpsForLevel(zoomLevel);
   const int64_t c0 = firstColumn, c1 = firstColumn + count;
   bool filled = false, partial = false;
   std::vector<double> values;
   const auto clips = track.SortedIntervalArray();
   for (size_t i = 0; i < clips.size(); ++i) {
      const WaveClip &clip = *clips[i];
      const auto span = ComputeSpan(clip, pps);
      if (TailIsPartial(resolved.recording, i + 1 == clips.size(), span, c1))
         partial = true;
      const int64_t a = std::max(span.first, c0);
      const int64_t b = std::min(span.end, c1);
      if (a >= b)
         continue;
      values.resize(size_t(b - a));
      // Centre of each column, absolute time (Envelope::GetValues subtracts
      // the envelope offset itself)
      clip.GetEnvelope().GetValues(values.data(), int(b - a),
         (double(a) + 0.5) / pps, 1.0 / pps);
      for (int64_t c = a; c < b; ++c)
         out[c - c0] = float(values[size_t(c - a)]);
      filled = true;
   }
   return Result(track, filled, partial, 0, 0);
}

namespace {

struct SampleRun {
   int32_t clipIndex = 0;
   double firstSampleTime = 0;
   double samplePeriod = 0;
   std::vector<float> values;
   std::vector<float> envelope;
};

//! Copies the recording tail (append buffer) of channel `ch` from
//! `offset`; false if the audio thread flushed meanwhile (port of the race
//! check of WaveDataCache.cpp AppendBufferHelper::FillBuffer)
bool CopyAppendTail(const WaveClip &clip, size_t ch, size_t offset,
   float *dst, size_t n)
{
   const Sequence *sequence = clip.GetSequence(ch);
   if (!sequence)
      return false;
   const auto committed = sequence->GetNumSamples();
   const size_t len = sequence->GetAppendBufferLen();
   if (offset >= len)
      return false;
   n = std::min(n, len - offset);
   const auto format = clip.GetSampleFormats().Stored();
   const auto src = clip.GetAppendBuffer(ch) + offset * SAMPLE_SIZE(format);
   SamplesToFloats(src, format, dst, n);
   return committed == sequence->GetNumSamples() &&
      sequence->GetAppendBufferLen() >= len;
}

//! Port of the sample range of DrawIndividualSamples (WaveformView.cpp
//! :573-700): play-relative samples [floor((t0-P0)*rate), ceil((t1-P0)*rate)]
SampleRun SamplesOfClip(const WaveClip &clip, int32_t index, size_t ch,
   double t0, double t1)
{
   const double rate = clip.GetRate() / clip.GetStretchRatio();
   const double p0 = clip.GetPlayStartTime();
   const int64_t visible = clip.GetVisibleSampleCount().as_long_long();
   const int64_t appendLen = clip.GetTrimRight() == 0
      ? int64_t(clip.GetAppendBufferLen(ch)) : 0;
   const int64_t total = visible + appendLen;
   const int64_t s0 = int64_t(std::floor(std::max(0.0, t0 - p0) * rate));
   const int64_t s1 = std::min<int64_t>(total,
      int64_t(std::ceil(std::max(0.0, t1 - p0) * rate)) + 1);
   SampleRun run;
   run.clipIndex = index;
   run.firstSampleTime = p0 + double(s0) / rate;
   run.samplePeriod = 1.0 / rate;
   if (s0 >= s1)
      return run;
   const size_t len = size_t(s1 - s0);
   run.values.assign(len, 0.0f);
   const size_t fromSequence = s0 < visible
      ? size_t(std::min<int64_t>(int64_t(len), visible - s0)) : 0;
   if (fromSequence > 0)
      clip.GetSamples(ch, reinterpret_cast<samplePtr>(run.values.data()),
         floatSample, sampleCount{ s0 }, fromSequence, false);
   if (fromSequence < len)
      CopyAppendTail(clip, ch, size_t(s0 + int64_t(fromSequence) - visible),
         run.values.data() + fromSequence, len - fromSequence);
   std::vector<double> envelope(len);
   clip.GetEnvelope().GetValues(envelope.data(), int(len),
      run.firstSampleTime, run.samplePeriod);
   run.envelope.assign(envelope.begin(), envelope.end());
   return run;
}

template<typename T> void Put(std::vector<uint8_t> &bytes, T value)
{
   // Little-endian on every supported ABI (arm64-v8a, x86_64)
   const auto *p = reinterpret_cast<const uint8_t *>(&value);
   bytes.insert(bytes.end(), p, p + sizeof(T));
}

} // namespace

std::vector<uint8_t> WaveSamples(int64_t trackId, int channel, double t0,
   double t1)
{
   if (!std::isfinite(t0) || !std::isfinite(t1) || t1 < t0)
      return {};
   auto *project = Session::Get().Project();
   if (!project)
      return {};
   const auto resolved = ResolveDisplayTrack(*project, trackId);
   if (!resolved.track || channel < 0 ||
       size_t(channel) >= resolved.track->NChannels())
      return {};

   std::vector<SampleRun> runs;
   size_t total = 0;
   const auto clips = resolved.track->SortedIntervalArray();
   for (size_t i = 0; i < clips.size(); ++i) {
      const WaveClip &clip = *clips[i];
      if (clip.GetPlayEndTime() <= t0 || clip.GetPlayStartTime() > t1)
         continue;
      // Bound the work before reading: a range this long is not a
      // sample-mode range (API.md §7.4)
      const double rate = clip.GetRate() / clip.GetStretchRatio();
      const double estimate = (std::min(t1, clip.GetPlayEndTime()) -
         std::max(t0, clip.GetPlayStartTime())) * rate;
      if (estimate > double(kMaxWaveSamples))
         return {};
      auto run = SamplesOfClip(clip, int32_t(i), size_t(channel), t0, t1);
      total += run.values.size();
      if (total > kMaxWaveSamples)
         return {};
      runs.push_back(std::move(run));
   }

   std::vector<uint8_t> bytes;
   bytes.reserve(4 + runs.size() * 24 + total * 8);
   Put<int32_t>(bytes, int32_t(runs.size()));
   for (const auto &run : runs) {
      Put<int32_t>(bytes, run.clipIndex);
      Put<double>(bytes, run.firstSampleTime);
      Put<double>(bytes, run.samplePeriod);
      Put<int32_t>(bytes, int32_t(run.values.size()));
      for (float v : run.values)
         Put<float>(bytes, v);
      for (float v : run.envelope)
         Put<float>(bytes, v);
   }
   return bytes;
}

} // namespace aubridge::display
