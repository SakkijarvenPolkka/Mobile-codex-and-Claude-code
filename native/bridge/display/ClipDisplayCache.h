/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ClipDisplayCache.h

  Per-clip display data cache, attached to every WaveClip that was drawn
  (replaces 3.7.9's src/ WaveformPainter attachment of WaveformView.cpp
  and WaveClipSpectrumCache of SpectrumCache.h, data only).

  * Waveform columns: one lib-wave-track-paint WaveDataCache per channel
    (sequence-local columns, see WaveDisplay.cpp).
  * Spectrogram: 256-column elements of 8-bit rows per channel
    (Spectrogram.cpp).

  Invalidation: WaveClip::MarkChanged (may run on the AudioIO thread while
  recording: only an atomic counter here) rebuilds everything on the next
  lookup, unless the clip is a recording target ("append only": the only
  possible change is Sequence::Append, so complete elements stay valid and
  incomplete ones refresh themselves); Invalidate (Resample), SwapChannels,
  Erase and MakeStereo force a rebuild; a replaced Sequence object or a
  changed scaled rate (stretch / SetRate, which do not MarkChanged)
  rebuilds that channel.

  Memory: the caches that hold data are registered in a global list;
  TrimDisplayCaches releases the least recently used ones until their
  estimated size fits the budget (display.trimCaches, plus a standing
  budget enforced after every display request).

  Everything except the WaveClipListener notifications is engine thread
  only.

**********************************************************************/
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <vector>

#include "WaveClip.h"

class Sequence;
class WaveDataCache;

namespace aubridge::display {

//! Width of a cache element / of the tiles Kotlin requests
constexpr int64_t kElementColumns = 256;

//! One 256-column spectrogram element (Spectrogram.cpp)
struct SpectroElement {
   std::vector<uint8_t> data;   //!< 256 * rows, column-major
   //! Leading columns that are final (all their samples were committed)
   size_t completeColumns = 0;
   bool complete = false;       //!< false: recompute on the next request
   uint64_t lastUse = 0;
};

class ClipDisplayCache;

//! Releases least recently used caches until the estimated total is at most
//! `budgetBytes`; `keep` (may be null) is never released.  @return the
//! estimated total afterwards.  Engine thread.
size_t TrimDisplayCaches(size_t budgetBytes, const ClipDisplayCache *keep = nullptr);

struct SpectroKey {
   double pps;
   int64_t firstColumn;   //!< sequence-local, multiple of 256
   int rows;
   bool operator<(const SpectroKey &other) const
   {
      if (pps != other.pps)
         return pps < other.pps;
      if (firstColumn != other.firstColumn)
         return firstColumn < other.firstColumn;
      return rows < other.rows;
   }
};

class ClipDisplayCache final : public WaveClipListener {
public:
   //! The clip's cache, created on first use.  Engine thread only.
   static ClipDisplayCache &Get(const WaveClip &clip);

   ClipDisplayCache();
   ~ClipDisplayCache() override;

   //! Call before using the caches of `clip` in a request: applies pending
   //! invalidations.  `appendOnly`: the clip belongs to a recording target.
   void Sync(const WaveClip &clip, bool appendOnly);

   //! The waveform column cache of channel `ch` (after Sync), rebuilt when
   //! the Sequence object or the scaled rate changed.  `spp` (samples per
   //! column) only feeds the memory estimate.
   WaveDataCache &WaveCache(const WaveClip &clip, size_t ch, double spp);

   //! Spectrogram elements of channel `ch` (after Sync); cleared when the
   //! Sequence object, the scaled rate or `trimKey` (the first visible
   //! sequence sample) changed
   std::map<SpectroKey, SpectroElement> &Spectro(
      const WaveClip &clip, size_t ch, int64_t trimKey);
   //! Bookkeeping after elements were added to Spectro(ch)
   void SpectroChanged(size_t ch);

   //! Marks this cache as the most recently used one
   void Touch();
   uint64_t LastUse() const { return mLastUse; }
   size_t EstimatedBytes() const;

   // WaveClipListener
   std::unique_ptr<WaveClipListener> Clone() const override;
   void MarkChanged() noexcept override;
   void Invalidate() override;
   void MakeStereo(WaveClipListener &&other, bool aligned) override;
   void SwapChannels() override;
   void Erase(size_t index) override;

private:
   friend size_t TrimDisplayCaches(size_t, const ClipDisplayCache *);

   struct Channel {
      std::unique_ptr<WaveDataCache> wave;
      const Sequence *waveSequence = nullptr;
      double waveScaledRate = 0;
      size_t waveBytes = 0;
      std::map<SpectroKey, SpectroElement> spectro;
      const Sequence *spectroSequence = nullptr;
      double spectroScaledRate = 0;
      int64_t spectroTrimKey = -1;
      size_t spectroBytes = 0;
   };
   void Register();
   void Unregister();
   Channel &ChannelAt(size_t ch);

   std::vector<Channel> mChannels;
   std::atomic<uint64_t> mChanges{ 0 };
   uint64_t mSeenChanges = 0;
   std::atomic<bool> mForceRebuild{ false };
   uint64_t mLastUse = 0;
   bool mRegistered = false;
};

//! display.setViewportWidth: widest view in pixels (sizes the per-cache
//! LRU of WaveDataCache, GraphicsDataCacheBase::UpdateViewportWidth)
void SetViewportWidth(int64_t px);
int64_t ViewportWidth();

//! Estimated size of every registered cache
size_t DisplayCacheBytes();
//! Releases every cache (shutdown)
void ReleaseAllDisplayCaches();

//! Standing budget enforced after every display request
constexpr size_t kStandingBudgetBytes = size_t(32) << 20;

} // namespace aubridge::display
