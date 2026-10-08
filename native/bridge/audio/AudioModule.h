/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AudioModule.h

  Internals of the bridge "audio" module (API.md transport.* / audio.*,
  §4.5, §6.4-§6.6) shared by its translation units:

  * BridgeMeter.cpp        lock-free Meter implementation + ReadMeters
  * TransportSnapshot.cpp  seqlock written by the engine, ReadTransport
  * TransportManager.cpp   per-project transport (port of the non-GUI
                           parts of src/ProjectAudioManager.cpp)
  * TransportCommands.cpp  transport.* commands, tick, snapshot contributor,
                           stream finalizer, DefaultOptions hook
  * AudioDevices.cpp       audio.* commands, PortAudio re-initialisation,
                           latency correction
  * RegisterAudioModule.cpp

**********************************************************************/
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

#include "Json.h"
#include "Meter.h"

class AudacityProject;

namespace aubridge {

class ModuleRegistry;

// ---------------------------------------------------------------------------
// Meters (API.md §6.5)
// ---------------------------------------------------------------------------

//! Meter fed by AudioIO on the PortAudio callback thread.  Accumulates
//! peak / sum of squares / clip flags in atomics; ReadMeters() drains them.
//! No locks, no allocation in UpdateDisplay.
class BridgeMeter final : public Meter {
public:
   BridgeMeter();
   ~BridgeMeter() override;

   // Meter (callback thread)
   void UpdateDisplay(unsigned numChannels, unsigned long numFrames,
      const float *sampleData) override;
   bool IsMeterDisabled() const override { return false; }

   // Meter (engine thread)
   void Clear() override;
   void Reset(double sampleRate, bool resetClipping) override;
   float GetMaxPeak() const override;
   bool IsClipping() const override;
   int GetDBRange() const override;

   //! Any thread: {peakL, peakR, rmsL, rmsR, clipL, clipR}; drains the
   //! peak/RMS accumulators (clip flags stay)
   void Read(float out[6], float &channels);
   //! Clears the sticky clip flags (transport.play/record/monitor start)
   void ResetClipping();
   //! Forget the channel count (no stream)
   void ResetChannels();

private:
   void Drain();
   std::atomic<float> mPeak[2];
   std::atomic<double> mSumSq[2];
   std::atomic<uint64_t> mFrames[2];
   std::atomic<bool> mClip[2];
   std::atomic<unsigned> mChannels{ 0 };
   std::atomic<float> mLastPeak{ 0 };
};

//! The process-wide meters (one project at a time)
const std::shared_ptr<BridgeMeter> &PlaybackMeter();
const std::shared_ptr<BridgeMeter> &CaptureMeter();
//! Bridge.h ReadMeters provider
bool ReadMetersImpl(float *out, size_t n);

// ---------------------------------------------------------------------------
// Transport snapshot (API.md §6.4)
// ---------------------------------------------------------------------------

namespace TransportState {
inline constexpr int Stopped = 0;
inline constexpr int Playing = 1;
inline constexpr int Recording = 2;
inline constexpr int PausedPlay = 3;
inline constexpr int PausedRecord = 4;
inline constexpr int Monitoring = 5;
inline constexpr int Stopping = 6;
}

inline constexpr size_t kTransportFields = 16;

//! Engine thread: publish a full record (seqlock)
void PublishTransport(const double (&fields)[kTransportFields]);
//! Bridge.h ReadTransport provider (any thread, lock free)
bool ReadTransportImpl(double *out, size_t n);
//! CLOCK_MONOTONIC in nanoseconds
int64_t MonotonicNowNs();

// ---------------------------------------------------------------------------
// Devices / latency (AudioDevices.cpp)
// ---------------------------------------------------------------------------

void RegisterDeviceCommands(ModuleRegistry &registry);
//! Engine thread: PaAAudio_SetDefaults from the start configuration (must
//! run before AudioIO::Init)
void ApplyStartConfigDefaults();
//! Engine thread: a device list received while audio was busy is applied
//! once AudioIO is idle (no stream at all, not even monitoring)
void ApplyPendingDeviceChange();
//! The /AudioIO/LatencyCorrection value (ms) a recording with playback
//! ("overdub") on the current devices uses: -(measured duplex offset) +
//! user trim (Audacity's sign: negative shifts the recording earlier)
double OverdubCorrectionMs();
//! Measured duplex offset (ms) of the current route; false if none yet
bool MeasuredDuplexOffsetMs(double &ms);
//! After a stream with input and output stopped: store the measured duplex
//! offset of the route it used
void StoreMeasuredDuplexOffset();
//! Reset module statics (Register / BeforeShutdown)
void ResetDeviceState();

// ---------------------------------------------------------------------------
// Commands (TransportCommands.cpp)
// ---------------------------------------------------------------------------

void RegisterTransportCommands(ModuleRegistry &registry);
//! Reset module statics (Register / BeforeShutdown)
void ResetTransportState();

} // namespace aubridge
