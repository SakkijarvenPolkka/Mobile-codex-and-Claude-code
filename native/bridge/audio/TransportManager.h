/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  TransportManager.h

  Per-project transport of the bridge: the non-GUI parts of Audacity
  3.7.9's src/ProjectAudioManager.{h,cpp} (play, stop, pause, record,
  listener callbacks, DefaultOptions hook), TransportUtilities
  (MakeTransportTracks), DropoutDetector, the stop-on-end polling of
  TrackPanel::OnTimer and the selection follower of AdornedRulerPanel.

  Engine thread only, except the AudioIOListener callbacks marked otherwise.

**********************************************************************/
#pragma once

#include <atomic>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "AudioIOListener.h"
#include "ClientData.h"
#include "Observer.h"

class AudacityProject;
class SelectedRegion;
class WaveTrack;
struct AudioIOStartStreamOptions;
struct TransportSequences;

namespace aubridge {

//! ProjectAudioManager.h PlayMode
enum class PlayMode : int {
   normalPlay,
   oneSecondPlay,
   loopedPlay,
   cutPreviewPlay
};

class TransportManager final
   : public ClientData::Base
   , public AudioIOListener
   , public std::enable_shared_from_this<TransportManager>
{
public:
   static TransportManager &Get(AudacityProject &project);
   static TransportManager *Find(AudacityProject &project);

   explicit TransportManager(AudacityProject &project);
   TransportManager(const TransportManager &) = delete;
   TransportManager &operator=(const TransportManager &) = delete;
   ~TransportManager() override;

   //! Project became the session's project: meters, selection follower,
   //! checkpoint-failure subscription
   void Attach();

   // ---- commands (engine thread) ------------------------------------------
   //! transport.play without t0: Space (newDefault = true) or a fixed play
   //! (newDefault = false).  @return false when there was nothing to play
   //! @throws BridgeError FAILED when the device could not be opened
   bool PlayCurrentRegion(bool newDefault);
   //! transport.play {t0, t1?}: Quick-Play, once, play region untouched
   bool QuickPlay(double t0, std::optional<double> t1);
   //! Port of ProjectAudioManager::PlayPlayRegion: the token (> 0), -1 when
   //! there is nothing to play / not allowed, 0 when StartStream failed
   int PlayPlayRegion(const SelectedRegion &selectedRegion,
      const AudioIOStartStreamOptions &options, PlayMode mode);

   //! Stops this project's stream (and a preview or monitoring stream);
   //! recording is committed ("Recorded Audio").  `reason` goes into the
   //! transport event ("user", "end", "device", "error").
   void Stop(const char *reason = "user", const std::string &message = {});
   //! transport.pause; false when nothing plays/records
   bool TogglePause();
   //! transport.seek while playing (not while recording)
   bool SeekWhilePlaying(double t);
   //! transport.record (OnRecord): altAppearance = Shift+R
   //! @throws BridgeError FAILED (mismatched rates, device error)
   void Record(bool altAppearance);
   //! transport.monitor
   void StartMonitoring();
   void StopMonitoring();

   //! Engine tick (~50 ms): stop drained streams, live recording snapshots,
   //! transport snapshot, transport events
   void OnTick();
   //! Spine's stream finalizer (before a NeedsIdleAudio command)
   void OnStreamDrained();
   //! Recompute the transport state; publish §6.4 and emit a `transport`
   //! event when it changed
   void UpdateState(const char *reason, const std::string &message = {},
      int dropouts = 0);

   bool CanStopAudioStream() const;
   //! A stream of this project (token active), playing or recording
   bool OwnStreamActive() const;
   bool Playing() const;
   bool Recording() const;
   bool Looping() const { return mLooping; }

   //! The (pending) tracks the running recording appends to; empty when not
   //! recording.  Pending new tracks carry TrackId{} in the library; the
   //! snapshot gives them synthetic ids -(2 + index) (API.md §3.2).
   const std::vector<std::shared_ptr<WaveTrack>> &CaptureTracks() const
   { return mCaptureTracks; }

private:
   // AudioIOListener
   void OnAudioIORate(int rate) override;            // engine
   void OnAudioIOStartRecording() override;          // engine
   void OnAudioIOStopRecording() override;           // engine (StopStream)
   void OnAudioIONewBlocks() override;               // AUDIO THREAD
   void OnCommitRecording() override;                // engine (StopStream)
   void OnSoundActivationThreshold() override;       // CALLBACK THREAD

   bool DoRecord(const TransportSequences &sequences, double t0, double t1,
      bool altAppearance, const AudioIOStartStreamOptions &options);
   void CancelRecording();
   void AddDropoutLabels(
      const std::vector<std::pair<double, double>> &intervals);
   void FollowSelection();
   int ComputeState() const;
   void PublishTransport(int state);
   //! The stream started: initialise the display-time compensation
   void BeginTransport(double startTime, double recordStart);
   void ResetDisplay(double floor);
   double RecordedEnd() const;

   AudacityProject &mProject;
   std::weak_ptr<AudacityProject> mWeakProject;
   Observer::Subscription mSelectionSubscription;
   Observer::Subscription mCheckpointFailureSubscription;

   bool mLooping{ false };
   bool mCutting{ false };
   bool mStopping{ false };
   bool mAppending{ false };
   PlayMode mLastPlayMode{ PlayMode::normalPlay };
   std::atomic<int> mDisplayedRate{ 0 };

   // Recording in progress
   std::vector<std::shared_ptr<WaveTrack>> mCaptureTracks;
   bool mRecordingDuplex{ false };
   double mRecordStart{ 0 };
   double mLastRecordedEnd{ -1 };
   int64_t mLastLiveTouchNs{ 0 };
   int mLastDropouts{ 0 };

   // Display-time compensation (API.md §6.4 index 2)
   double DisplayTime(double streamTime, bool playback, bool paused,
      double outputLatency, bool looping, double loopT0, double loopT1);
   double mDisplayFloor{ 0 };
   double mLastShown{ 0 };
   double mLastStreamTime{ NAN };
   bool mHaveShown{ false };
   bool mWrapped{ false };
   //! transport.seek while playing: target until the callback applied it
   double mSeekTarget{ NAN };
   double mSeekFrom{ NAN };
   //! Last seek of the running stream (see SettleSeek)
   int64_t mSeekIssuedNs{ 0 };
   double mSeekIssueBase{ 0 };
   void SettleSeek();

   // Transport events
   std::string mLastEventState{ "stopped" };
};

} // namespace aubridge
