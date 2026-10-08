/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ClipDisplayCache.cpp

  The per-clip display cache attachment and the global memory budget
  (see ClipDisplayCache.h).  The attachment pattern follows 3.7.9's
  WaveformPainter (src/tracks/playabletrack/wavetrack/ui/WaveformView.cpp)
  and WaveClipSpectrumCache (SpectrumCache.cpp, Paul Licameli).

**********************************************************************/
#include "ClipDisplayCache.h"

#include <algorithm>
#include <mutex>
#include <utility>

#include "DisplayInternal.h"   // CaptureRunning
#include "Sequence.h"
#include "waveform/WaveDataCache.h"

namespace aubridge::display {

namespace {

//! Caches that hold data.  Leaked on purpose: clip attachments may be
//! destroyed during static destruction at process exit
struct Registry {
   std::mutex mutex;
   std::vector<ClipDisplayCache *> active;
};
Registry &GetRegistry()
{
   static auto *registry = new Registry;
   return *registry;
}

std::atomic<int64_t> sViewportWidth{ 0 };
//! Engine thread
uint64_t sUseCounter = 0;

//! Spectrogram elements kept per clip channel before the oldest is evicted
constexpr size_t kMaxSpectroElements = 64;
constexpr size_t kMaxSpectroBytes = size_t(16) << 20;

const WaveClip::Attachments::RegisteredFactory sKey{
   [](WaveClip &) { return std::make_unique<ClipDisplayCache>(); }
};

size_t RoundUpDiv(size_t a, size_t b)
{
   return (a + b - 1) / b;
}

} // namespace

ClipDisplayCache &ClipDisplayCache::Get(const WaveClip &clip)
{
   // Display data is "mutable" state of the clip, like in 3.7.9
   return const_cast<WaveClip &>(clip)
      .Attachments::Get<ClipDisplayCache>(sKey);
}

namespace {
//! Caches of clips of live capture targets (ForRequest).  Engine thread.
//! Leaked on purpose, like the registry
struct DetachedCache {
   std::weak_ptr<const WaveClip> clip;
   std::unique_ptr<ClipDisplayCache> cache;
};
std::vector<DetachedCache> &Detached()
{
   static auto *detached = new std::vector<DetachedCache>;
   return *detached;
}
}

ClipDisplayCache &ClipDisplayCache::ForRequest(
   const std::shared_ptr<const WaveClip> &clip, bool liveCapture)
{
   auto &detached = Detached();
   // Forget the caches of destroyed clips
   detached.erase(std::remove_if(detached.begin(), detached.end(),
      [](const DetachedCache &d) { return d.clip.expired(); }),
      detached.end());
   const auto same = [&clip](const DetachedCache &d) {
      return d.clip.lock() == clip;
   };
   if (liveCapture) {
      // Not ClipDisplayCache::Get, and not even Attachments::Find: both
      // resize the attachment vector when it has no slot for sKey yet
      const auto it = std::find_if(detached.begin(), detached.end(), same);
      if (it != detached.end())
         return *it->cache;
      detached.push_back(
         DetachedCache{ clip, std::make_unique<ClipDisplayCache>() });
      return *detached.back().cache;
   }
   // The capture is over (or never concerned this clip): the clip's own
   // attachment from now on.  Its stand-in is dropped (its data is redone
   // once; a committed recording changes waveVersion anyway).
   if (!CaptureRunning())
      detached.clear();
   else
      detached.erase(std::remove_if(detached.begin(), detached.end(), same),
         detached.end());
   return Get(*clip);
}

size_t DetachedCacheCount()
{
   return Detached().size();
}

ClipDisplayCache::ClipDisplayCache() = default;

ClipDisplayCache::~ClipDisplayCache()
{
   // May run on any thread that destroys a clip: the registry lock keeps a
   // concurrent TrimDisplayCaches (engine thread) away from this object
   Unregister();
}

void ClipDisplayCache::Register()
{
   auto &registry = GetRegistry();
   std::lock_guard lock{ registry.mutex };
   if (!mRegistered) {
      registry.active.push_back(this);
      mRegistered = true;
   }
}

void ClipDisplayCache::Unregister()
{
   auto &registry = GetRegistry();
   std::lock_guard lock{ registry.mutex };
   if (mRegistered) {
      auto &v = registry.active;
      v.erase(std::remove(v.begin(), v.end(), this), v.end());
      mRegistered = false;
   }
}

ClipDisplayCache::Channel &ClipDisplayCache::ChannelAt(size_t ch)
{
   if (ch >= mChannels.size())
      mChannels.resize(ch + 1);
   return mChannels[ch];
}

void ClipDisplayCache::Sync(const WaveClip &clip, bool appendOnly)
{
   bool rebuild = mForceRebuild.exchange(false, std::memory_order_acq_rel);
   if (mChannels.size() != clip.NChannels())
      rebuild = true;
   const auto changes = mChanges.load(std::memory_order_acquire);
   if (changes != mSeenChanges) {
      // Any edit may have changed the samples anywhere.  While recording
      // the only mutation is Sequence::Append: complete elements stay valid
      // and GraphicsDataCache refreshes incomplete ones on every lookup.
      if (!appendOnly)
         rebuild = true;
      mSeenChanges = changes;
   }
   if (rebuild) {
      // Destroy the old caches before new ones subscribe to the clip
      mChannels.clear();
      mChannels.resize(clip.NChannels());
   }
}

WaveDataCache &ClipDisplayCache::WaveCache(
   const WaveClip &clip, size_t ch, double spp)
{
   auto &c = ChannelAt(ch);
   const Sequence *sequence = clip.GetSequence(ch);
   // The same expression as WaveDataCache's constructor
   const double scaledRate = clip.GetRate() / clip.GetStretchRatio();
   if (!c.wave || c.waveSequence != sequence || c.waveScaledRate != scaledRate) {
      // WaveDataCache keeps a raw Sequence pointer (WaveDataCache.cpp:190):
      // a replaced Sequence (Resample, SwapChannels, rollback) needs a new
      // cache; stretching changes the scaled rate without MarkChanged
      c.wave.reset();
      c.wave = std::make_unique<WaveDataCache>(clip, int(ch));
      c.waveSequence = sequence;
      c.waveScaledRate = scaledRate;
      c.waveBytes = 0;
   }
   const auto viewport = ViewportWidth();
   if (viewport > 0)
      c.wave->UpdateViewportWidth(viewport);

   // Memory estimate: the elements GraphicsDataCache keeps (LRU of
   // ceil(maxWidth / 256) * 4, GraphicsDataCache.cpp PerformCleanup, plus
   // the elements of one lookup) and the data provider's one-block buffer
   // of the tier in use (it is reused, never shrunk)
   const size_t maxWidth = size_t(std::max<int64_t>({ 1600, viewport,
      c.wave->GetMaxViewportWidth() }));
   const size_t elements = RoundUpDiv(maxWidth, size_t(kElementColumns)) * 4 + 3;
   const size_t maxBlock = sequence ? sequence->GetMaxBlockSize() : 0;
   size_t blockBytes;
   if (spp >= 64 * 1024)
      blockBytes = RoundUpDiv(maxBlock, 64 * 1024) * 3 * sizeof(float);
   else if (spp >= 256)
      blockBytes = RoundUpDiv(maxBlock, 256) * 3 * sizeof(float);
   else
      blockBytes = maxBlock * sizeof(float);
   c.waveBytes = std::max(c.waveBytes,
      elements * sizeof(WaveCacheElement) + blockBytes +
      sizeof(WaveDataCache));
   Register();
   return *c.wave;
}

std::map<SpectroKey, SpectroElement> &ClipDisplayCache::Spectro(
   const WaveClip &clip, size_t ch, int64_t trimKey)
{
   auto &c = ChannelAt(ch);
   const Sequence *sequence = clip.GetSequence(ch);
   const double scaledRate = clip.GetRate() / clip.GetStretchRatio();
   if (c.spectroSequence != sequence || c.spectroScaledRate != scaledRate ||
       c.spectroTrimKey != trimKey) {
      c.spectro.clear();
      c.spectroBytes = 0;
      c.spectroSequence = sequence;
      c.spectroScaledRate = scaledRate;
      c.spectroTrimKey = trimKey;
   }
   Register();
   return c.spectro;
}

void ClipDisplayCache::SpectroChanged(size_t ch)
{
   auto &c = ChannelAt(ch);
   const auto bytesOf = [](const SpectroElement &element) {
      return element.data.capacity() + sizeof(SpectroElement) + 64;
   };
   size_t bytes = 0;
   for (const auto &[key, element] : c.spectro)
      bytes += bytesOf(element);
   // Keep the most recently used elements (at least one: the request that
   // just ran may need many rows)
   while (c.spectro.size() > 1 &&
          (c.spectro.size() > kMaxSpectroElements || bytes > kMaxSpectroBytes)) {
      auto oldest = std::min_element(c.spectro.begin(), c.spectro.end(),
         [](const auto &a, const auto &b) {
            return a.second.lastUse < b.second.lastUse;
         });
      bytes -= std::min(bytes, bytesOf(oldest->second));
      c.spectro.erase(oldest);
   }
   c.spectroBytes = bytes;
}

void ClipDisplayCache::Touch()
{
   mLastUse = ++sUseCounter;
}

size_t ClipDisplayCache::EstimatedBytes() const
{
   size_t bytes = sizeof(*this);
   for (const auto &c : mChannels)
      bytes += (c.wave ? c.waveBytes : 0) + c.spectroBytes;
   return bytes;
}

std::unique_ptr<WaveClipListener> ClipDisplayCache::Clone() const
{
   // Copies of clips (undo states, clipboard, pending tracks) start empty
   return std::make_unique<ClipDisplayCache>();
}

void ClipDisplayCache::MarkChanged() noexcept
{
   // AudioIO thread while recording: lock free
   mChanges.fetch_add(1, std::memory_order_acq_rel);
}

void ClipDisplayCache::Invalidate()
{
   mForceRebuild.store(true, std::memory_order_release);
}

void ClipDisplayCache::MakeStereo(WaveClipListener &&, bool)
{
   mForceRebuild.store(true, std::memory_order_release);
}

void ClipDisplayCache::SwapChannels()
{
   mForceRebuild.store(true, std::memory_order_release);
}

void ClipDisplayCache::Erase(size_t)
{
   mForceRebuild.store(true, std::memory_order_release);
}

void SetViewportWidth(int64_t px)
{
   sViewportWidth.store(px);
}

int64_t ViewportWidth()
{
   return sViewportWidth.load();
}

size_t DisplayCacheBytes()
{
   auto &registry = GetRegistry();
   std::lock_guard lock{ registry.mutex };
   size_t total = 0;
   for (auto *cache : registry.active)
      total += cache->EstimatedBytes();
   return total;
}

size_t TrimDisplayCaches(size_t budgetBytes, const ClipDisplayCache *keep)
{
   auto &registry = GetRegistry();
   std::vector<std::pair<uint64_t, ClipDisplayCache *>> order;
   size_t total = 0;
   std::lock_guard lock{ registry.mutex };
   for (auto *cache : registry.active) {
      total += cache->EstimatedBytes();
      order.emplace_back(cache->LastUse(), cache);
   }
   if (total <= budgetBytes)
      return total;
   std::sort(order.begin(), order.end(),
      [](const auto &a, const auto &b) { return a.first < b.first; });
   std::vector<ClipDisplayCache *> released;
   for (auto &[lastUse, cache] : order) {
      if (total <= budgetBytes)
         break;
      if (cache == keep)
         continue;
      total -= std::min(total, cache->EstimatedBytes());
      released.push_back(cache);
   }
   // Still under the lock: a clip destroyed on another thread waits in
   // ~ClipDisplayCache until these objects are done
   for (auto *cache : released) {
      cache->mChannels.clear();
      cache->mRegistered = false;
   }
   auto &v = registry.active;
   v.erase(std::remove_if(v.begin(), v.end(),
      [&](ClipDisplayCache *c) {
         return std::find(released.begin(), released.end(), c) != released.end();
      }), v.end());
   return total;
}

void ReleaseAllDisplayCaches()
{
   Detached().clear();
   TrimDisplayCaches(0, nullptr);
}

} // namespace aubridge::display
