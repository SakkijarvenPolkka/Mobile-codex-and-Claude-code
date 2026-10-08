/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  DisplayInternal.h

  Shared pieces of the display module: the absolute-column geometry of
  API.md §7.1 and the providers installed with SetDisplayProviders.
  Engine thread only.

**********************************************************************/
#pragma once

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

class WaveClip;
class WaveTrack;

namespace aubridge {
class ModuleRegistry;
}

namespace aubridge::display {

//! Zoom levels accepted by the providers (API.md §7.1)
constexpr int kMinZoomLevel = -160;
constexpr int kMaxZoomLevel = 160;
//! Largest `count` of one request
constexpr int kMaxColumns = 1 << 16;
//! Largest |firstColumn| (keeps column arithmetic far from overflow)
constexpr int64_t kMaxFirstColumn = int64_t(1) << 60;
//! Largest `rows` of a spectrogram request
constexpr int kMaxSpectrogramRows = 4096;
//! Most samples one waveSamples call returns (all runs together)
constexpr size_t kMaxWaveSamples = size_t(1) << 20;

//! ZoomInfo(0, pps).TimeToPosition(t): floor(0.5 + pps * t), saturated
inline int64_t Position(double pps, double t)
{
   const double x = std::floor(0.5 + pps * t);
   if (!(x > -9.0e18))
      return std::numeric_limits<int64_t>::min() / 2;
   if (!(x < 9.0e18))
      return std::numeric_limits<int64_t>::max() / 2;
   return static_cast<int64_t>(x);
}

//! Where a clip is drawn at a zoom (port of ClipParameters::GetClipRect at
//! h = 0, src/tracks/playabletrack/wavetrack/ui/ClipParameters.cpp)
struct ClipSpan {
   int64_t first = 0;   //!< absolute column of the play start
   int64_t end = 0;     //!< exclusive: column of P1 - 0.99 sample period
   double scaledRate = 0;   //!< rate / stretchRatio (samples per second)
   bool sampleMode = false; //!< pps > 0.5 * scaledRate (WaveformView.cpp:860)
};
ClipSpan ComputeSpan(const WaveClip &clip, double pps);

//! Absolute column of the clip's sequence start (sequence-local column 0)
int64_t SequenceShift(const WaveClip &clip, double pps);

// Providers (Hooks.h DisplayProviders), engine thread
int64_t WaveColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, float *out, size_t outSize);
int64_t EnvelopeColumns(int64_t trackId, int zoomLevel, int64_t firstColumn,
   int count, float *out, size_t outSize);
std::vector<uint8_t> WaveSamples(int64_t trackId, int channel, double t0,
   double t1);
int64_t SpectrogramColumns(int64_t trackId, int channel, int zoomLevel,
   int64_t firstColumn, int count, int rows, uint8_t *out, size_t outSize);

//! Frees the spectrogram analysis state (shutdown)
void ResetSpectrogram();

//! The waveVersion provider (Hooks.h SetWaveVersionProvider): the spine's
//! DefaultWaveVersion, except for recording targets, whose Sequence block
//! arrays the AudioIO thread appends to: those hash clip geometry and
//! sample counts only (the same function as the audio module's snapshot)
int64_t DisplayWaveVersion(const WaveTrack &track);

//! AudioIO is capturing: between StartStream with capture sequences and the
//! end of StopStream, the AudioIO thread may append to the capture targets
//! (AudioIO::mCaptureSequences, which only the engine thread changes).
//! DisplayTracks.cpp
bool CaptureRunning();

//! debug.addEnvelopePoint, debug.stretchClip, debug.recording
void RegisterDebugCommands(ModuleRegistry &registry);

} // namespace aubridge::display
