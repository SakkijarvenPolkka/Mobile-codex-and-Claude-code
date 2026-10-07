/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Hooks.cpp

**********************************************************************/
#include "Hooks.h"

#include <atomic>
#include <cstring>

#include "AudioIOBase.h"
#include "Envelope.h"
#include "ProjectAudioIO.h"
#include "SampleBlock.h"
#include "Sequence.h"
#include "WaveClip.h"
#include "WaveTrack.h"

namespace aubridge {

namespace {
std::atomic<TransportReaderFn> sTransportReader{ nullptr };
std::atomic<MetersReaderFn> sMetersReader{ nullptr };
DisplayProviders &Providers()
{
   static DisplayProviders providers;
   return providers;
}
WaveVersionFn &VersionFn()
{
   static WaveVersionFn fn;
   return fn;
}
StreamFinalizerFn &FinalizerFn()
{
   static StreamFinalizerFn fn;
   return fn;
}

// FNV-1a style 64-bit mixing
struct Hasher {
   uint64_t h = 1469598103934665603ull;
   void Mix(uint64_t v)
   {
      for (int i = 0; i < 8; ++i) {
         h ^= (v >> (8 * i)) & 0xff;
         h *= 1099511628211ull;
      }
   }
   void Mix(double d)
   {
      uint64_t bits;
      static_assert(sizeof bits == sizeof d);
      std::memcpy(&bits, &d, sizeof bits);
      Mix(bits);
   }
};
} // namespace

void SetTransportReader(TransportReaderFn fn) { sTransportReader = fn; }
void SetMetersReader(MetersReaderFn fn) { sMetersReader = fn; }
TransportReaderFn GetTransportReader() { return sTransportReader.load(); }
MetersReaderFn GetMetersReader() { return sMetersReader.load(); }

void SetDisplayProviders(DisplayProviders providers)
{
   Providers() = std::move(providers);
}

const DisplayProviders &GetDisplayProviders()
{
   return Providers();
}

void SetWaveVersionProvider(WaveVersionFn fn)
{
   VersionFn() = std::move(fn);
}

int64_t DefaultWaveVersion(const WaveTrack &track)
{
   Hasher hasher;
   hasher.Mix(uint64_t(track.NChannels()));
   for (const auto &pClip : track.SortedIntervalArray()) {
      const auto &clip = *pClip;
      hasher.Mix(uint64_t(clip.GetRate()));
      hasher.Mix(uint64_t(clip.NChannels()));
      hasher.Mix(clip.GetSequenceStartTime());
      hasher.Mix(clip.GetTrimLeft());
      hasher.Mix(clip.GetTrimRight());
      hasher.Mix(clip.GetStretchRatio());
      for (size_t ii = 0; ii < clip.NChannels(); ++ii) {
         const auto *seq = clip.GetSequence(ii);
         if (!seq)
            continue;
         hasher.Mix(uint64_t(seq->GetNumSamples().as_long_long()));
         // Sample blocks are immutable: an edit creates new block ids
         for (const auto &block : seq->GetBlockArray()) {
            hasher.Mix(uint64_t(block.start.as_long_long()));
            hasher.Mix(uint64_t(block.sb ? block.sb->GetBlockID() : 0));
         }
      }
      const auto &envelope = clip.GetEnvelope();
      const auto nPoints = envelope.GetNumberOfPoints();
      hasher.Mix(uint64_t(nPoints));
      for (size_t i = 0; i < nPoints; ++i) {
         hasher.Mix(envelope[int(i)].GetT());
         hasher.Mix(envelope[int(i)].GetVal());
      }
   }
   // >= 0 and bit 62 clear (bit 62 flags a partial tile, API.md §7.2)
   return int64_t(hasher.h & ((uint64_t(1) << 62) - 1));
}

int64_t WaveVersion(const WaveTrack &track)
{
   if (auto &fn = VersionFn())
      return fn(track) & ((int64_t(1) << 62) - 1);
   return DefaultWaveVersion(track);
}

void SetStreamFinalizer(StreamFinalizerFn fn)
{
   FinalizerFn() = std::move(fn);
}

void FinalizeDrainedStream(AudacityProject &project)
{
   auto &fn = FinalizerFn();
   auto audioIO = AudioIOBase::Get();
   if (!fn || !audioIO)
      return;
   const int token = ProjectAudioIO::Get(project).GetAudioIOToken();
   if (token > 0 && !audioIO->IsStreamActive(token))
      fn(project);
}

void ResetHooks()
{
   FinalizerFn() = {};
   sTransportReader = nullptr;
   sMetersReader = nullptr;
   Providers() = {};
   VersionFn() = {};
}

} // namespace aubridge
