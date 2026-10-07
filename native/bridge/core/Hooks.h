/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Hooks.h

  Registration points for the Bridge.h entry points that the audio and
  display modules implement.  Until a provider is installed the entry
  points answer "not ready" (false / -3 / empty).

  * Readers (ReadTransport / ReadMeters) are called on ANY thread, lock
    free: they are plain function pointers stored atomically; the module's
    function must itself be lock-free and must not touch library objects.
  * Display providers are called ON THE ENGINE THREAD from the display lane
    (Bridge.h posts the request and waits); they run only on the outermost
    loop, never inside another command.

  Install from the module's Register*Module() (engine thread, bootstrap).

**********************************************************************/
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

class AudacityProject;
class WaveTrack;

namespace aubridge {

//! @param out at least 16 doubles (API.md §6.4); @return false if no data
using TransportReaderFn = bool (*)(double *out, size_t n);
//! @param out at least 14 floats (API.md §6.5); resets peak accumulators
using MetersReaderFn = bool (*)(float *out, size_t n);

void SetTransportReader(TransportReaderFn fn);
void SetMetersReader(MetersReaderFn fn);
TransportReaderFn GetTransportReader();
MetersReaderFn GetMetersReader();

//! API.md §7.2; same signature as Bridge.h WaveColumns
using WaveColumnsFn = std::function<int64_t(int64_t trackId, int channel,
   int zoomLevel, int64_t firstColumn, int count, float *out, size_t outSize)>;
//! API.md §7.3
using EnvelopeColumnsFn = std::function<int64_t(int64_t trackId, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize)>;
//! API.md §7.4
using WaveSamplesFn = std::function<std::vector<uint8_t>(int64_t trackId,
   int channel, double t0, double t1)>;
//! API.md §7.5
using SpectrogramColumnsFn = std::function<int64_t(int64_t trackId,
   int channel, int zoomLevel, int64_t firstColumn, int count, int rows,
   uint8_t *out, size_t outSize)>;

struct DisplayProviders {
   WaveColumnsFn waveColumns;
   EnvelopeColumnsFn envelopeColumns;
   WaveSamplesFn waveSamples;
   SpectrogramColumnsFn spectrogramColumns;
};
//! Engine thread
void SetDisplayProviders(DisplayProviders providers);
const DisplayProviders &GetDisplayProviders();

//! waveVersion of a track (snapshot field, API.md §4.2; must be >= 0 with
//! bit 62 clear).  The spine's default hashes clip geometry, sample block
//! ids and envelope points; the display module may install a cached
//! implementation.  Engine thread.
using WaveVersionFn = std::function<int64_t(const WaveTrack &)>;
void SetWaveVersionProvider(WaveVersionFn fn);
int64_t WaveVersion(const WaveTrack &track);
int64_t DefaultWaveVersion(const WaveTrack &track);

//! Called by the spine before a NeedsIdleAudio command when the project's
//! stream has drained but was not
//! finalized yet (token > 0 && !IsStreamActive(token)): the audio module
//! installs its "stop + finalize recording" routine (port of the stop-on-end
//! polling of TrackPanel::OnTimer).  Engine thread.
using StreamFinalizerFn = std::function<void(AudacityProject &)>;
void SetStreamFinalizer(StreamFinalizerFn fn);
//! Spine: runs the finalizer when the condition above holds
void FinalizeDrainedStream(AudacityProject &project);

//! Status codes of the display entry points (API.md §7.2)
namespace DisplayStatus {
inline constexpr int64_t NoSuchTrack = -1;
inline constexpr int64_t Partial = -2;
inline constexpr int64_t NotReady = -3;
inline constexpr int64_t SampleModeNeeded = -4;
inline constexpr int64_t Unsupported = -5;
inline constexpr int64_t PartialBit = int64_t(1) << 62;
}

//! Spine: forget every provider (Stop())
void ResetHooks();

} // namespace aubridge
