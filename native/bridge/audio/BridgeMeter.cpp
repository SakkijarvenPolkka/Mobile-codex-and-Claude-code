/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BridgeMeter.cpp

  The Meter implementation of the bridge (replaces src/widgets/MeterPanel's
  data path) and the Bridge.h ReadMeters provider (API.md §6.5).

  UpdateDisplay runs on the PortAudio callback thread: it only updates
  atomics (no locks, no allocation, no logging).  ReadMeters may be called
  from any thread; it exchanges the accumulators with zero, so there must be
  exactly one consumer per process.  Ballistics are done in Kotlin.

**********************************************************************/
#include "AudioModule.h"

#include <algorithm>
#include <cmath>

#include "Decibels.h"

namespace aubridge {

namespace {

static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<double>::is_always_lock_free);
static_assert(std::atomic<uint64_t>::is_always_lock_free);

//! Like MeterPanel: a run of this many samples at full scale is clipping
constexpr int kClipRun = 3;
//! MAX_AUDIO of MeterPanel.cpp (1 - 1/32768)
constexpr float kFullScale = 1.0f - 1.0f / 32768.0f;

void AtomicMax(std::atomic<float> &a, float v)
{
   float cur = a.load(std::memory_order_relaxed);
   while (v > cur &&
      !a.compare_exchange_weak(cur, v, std::memory_order_relaxed))
   {}
}

void AtomicAdd(std::atomic<double> &a, double v)
{
   double cur = a.load(std::memory_order_relaxed);
   while (!a.compare_exchange_weak(cur, cur + v, std::memory_order_relaxed))
   {}
}

} // namespace

BridgeMeter::BridgeMeter()
{
   for (int c = 0; c < 2; ++c) {
      mPeak[c].store(0.0f);
      mSumSq[c].store(0.0);
      mFrames[c].store(0);
      mClip[c].store(false);
   }
}

BridgeMeter::~BridgeMeter() = default;

void BridgeMeter::UpdateDisplay(unsigned numChannels, unsigned long numFrames,
   const float *sampleData)
{
   if (!sampleData || numChannels == 0)
      return;
   const unsigned n = std::min(numChannels, 2u);
   for (unsigned c = 0; c < n; ++c) {
      float peak = 0;
      double sumSq = 0;
      int run = 0;
      bool clip = false;
      for (unsigned long i = 0; i < numFrames; ++i) {
         const float v = std::fabs(sampleData[i * numChannels + c]);
         if (!(v == v))   // NaN guard
            continue;
         peak = std::max(peak, v);
         sumSq += double(v) * v;
         run = (v >= kFullScale) ? run + 1 : 0;
         clip = clip || run >= kClipRun;
      }
      AtomicMax(mPeak[c], peak);
      AtomicAdd(mSumSq[c], sumSq);
      mFrames[c].fetch_add(numFrames, std::memory_order_relaxed);
      if (clip)
         mClip[c].store(true, std::memory_order_relaxed);
   }
   mChannels.store(n, std::memory_order_relaxed);
}

void BridgeMeter::Drain()
{
   for (int c = 0; c < 2; ++c) {
      mPeak[c].store(0.0f, std::memory_order_relaxed);
      mSumSq[c].store(0.0, std::memory_order_relaxed);
      mFrames[c].store(0, std::memory_order_relaxed);
   }
}

void BridgeMeter::Clear()
{
   // ProjectAudioManager::Stop: the stream is gone
   Drain();
   ResetChannels();
}

void BridgeMeter::Reset(double, bool)
{
   // Called by AudioIO when a stream starts or stops.  The clip flags are
   // sticky until transport.play/record/monitor starts (API.md §6.5): the
   // audio module clears them itself.
   Drain();
}

float BridgeMeter::GetMaxPeak() const
{
   return mLastPeak.load(std::memory_order_relaxed);
}

bool BridgeMeter::IsClipping() const
{
   return mClip[0].load(std::memory_order_relaxed) ||
      mClip[1].load(std::memory_order_relaxed);
}

int BridgeMeter::GetDBRange() const
{
   return DecibelScaleCutoff.Read();
}

void BridgeMeter::Read(float out[6], float &channels)
{
   float maxPeak = 0;
   for (int c = 0; c < 2; ++c) {
      const float peak = mPeak[c].exchange(0.0f, std::memory_order_relaxed);
      const double sumSq = mSumSq[c].exchange(0.0, std::memory_order_relaxed);
      const uint64_t frames = mFrames[c].exchange(0, std::memory_order_relaxed);
      out[c] = peak;
      out[2 + c] = frames > 0 ? float(std::sqrt(sumSq / double(frames))) : 0.0f;
      out[4 + c] = mClip[c].load(std::memory_order_relaxed) ? 1.0f : 0.0f;
      maxPeak = std::max(maxPeak, peak);
   }
   mLastPeak.store(maxPeak, std::memory_order_relaxed);
   channels = float(mChannels.load(std::memory_order_relaxed));
}

void BridgeMeter::ResetClipping()
{
   mClip[0].store(false, std::memory_order_relaxed);
   mClip[1].store(false, std::memory_order_relaxed);
}

void BridgeMeter::ResetChannels()
{
   mChannels.store(0, std::memory_order_relaxed);
}

const std::shared_ptr<BridgeMeter> &PlaybackMeter()
{
   // Never destroyed: AudioIO's callback may still hold a weak reference
   static const auto *meter = new std::shared_ptr<BridgeMeter>(
      std::make_shared<BridgeMeter>());
   return *meter;
}

const std::shared_ptr<BridgeMeter> &CaptureMeter()
{
   static const auto *meter = new std::shared_ptr<BridgeMeter>(
      std::make_shared<BridgeMeter>());
   return *meter;
}

bool ReadMetersImpl(float *out, size_t n)
{
   if (!out || n < 14)
      return false;
   float play[6], rec[6], playCh = 0, recCh = 0;
   PlaybackMeter()->Read(play, playCh);
   CaptureMeter()->Read(rec, recCh);
   std::copy(play, play + 6, out);
   std::copy(rec, rec + 6, out + 6);
   out[12] = playCh;
   out[13] = recCh;
   for (size_t i = 14; i < n; ++i)
      out[i] = 0;
   return true;
}

} // namespace aubridge
