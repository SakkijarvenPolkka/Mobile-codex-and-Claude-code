/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity: A Digital Audio Editor

  ProjectAudioManager.cpp

  Paul Licameli split from ProjectManager.cpp

  Audacity Android port: TransportManager.cpp -- the non-GUI parts of
  Audacity 3.7.9's src/ProjectAudioManager.cpp (PlayPlayRegion,
  PlayCurrentRegion, Stop, OnPause, OnRecord, DoRecord,
  ChooseExistingRecordingTracks, GetPropertiesOfSelected, the
  AudioIOListener callbacks), src/TransportUtilities.cpp
  (MakeTransportTracks), src/DropoutDetector.cpp, the stop-on-end polling
  of src/TrackPanel.cpp (TrackPanel::OnTimer) and the selection follower of
  src/AdornedRulerPanel.cpp (DoSelectionChange).  Message boxes became
  error envelopes (BridgeError) or non-blocking `dialog` events; the
  status bar, scrubbing, toolbars and timer record are not ported.

**********************************************************************/
#include "TransportManager.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <thread>

#include "AudioIO.h"
#include "AudioIOBase.h"
#include "BasicUI.h"
#include "Internat.h"
#include "LabelTrack.h"
#include "MemoryX.h"
#include "PendingTracks.h"
#include "PlayableTrack.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectAudioIO.h"
#include "ProjectFileIO.h"
#include "ProjectHistory.h"
#include "RealtimeEffectList.h"
#include "SampleTrack.h"
#include "StretchingSequence.h"
#include "TrackFocus.h"
#include "UndoManager.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"

#include <wx/datetime.h>

#include "pa_android_aaudio.h"

#include "AudioModule.h"
#include "DefaultPlaybackPolicy.h"
#include "BridgeError.h"
#include "BridgePrefs.h"
#include "Events.h"
#include "Json.h"
#include "Session.h"

namespace aubridge {

namespace {

constexpr int RATE_NOT_SELECTED{ -1 };

AudacityProject::AttachedObjects::RegisteredFactory sTransportManagerKey{
   [](AudacityProject &project) {
      return std::make_shared<TransportManager>(project);
   }
};

//! TransportUtilities.cpp MakeTransportTracks
TransportSequences MakeTransportTracks(
   TrackList &trackList, bool selectedOnly, bool nonWaveToo)
{
   TransportSequences result;
   {
      const auto range = trackList.Any<WaveTrack>()
         + (selectedOnly ? &Track::IsSelected : &Track::Any);
      for (auto pTrack : range)
         result.playbackSequences.push_back(
            StretchingSequence::Create(*pTrack, pTrack->GetClipInterfaces()));
   }
   if (nonWaveToo) {
      const auto range = trackList.Any<const PlayableTrack>() +
         (selectedOnly ? &Track::IsSelected : &Track::Any);
      for (auto pTrack : range)
         if (!track_cast<const SampleTrack *>(pTrack))
            if (auto pSequence =
               std::dynamic_pointer_cast<const OtherPlayableSequence>(
                  pTrack->shared_from_this())
            )
               result.otherPlayableSequences.push_back(pSequence);
   }
   return result;
}

//! ProjectAudioManager.h PropertiesOfSelected
struct PropertiesOfSelected {
   bool allSameRate{ false };
   int rateOfSelected{ RATE_NOT_SELECTED };
   bool anySelected{ false };
};

//! ProjectAudioManager.cpp GetPropertiesOfSelected
PropertiesOfSelected GetPropertiesOfSelected(const AudacityProject &proj)
{
   double rateOfSelection{ RATE_NOT_SELECTED };

   PropertiesOfSelected result;
   result.allSameRate = true;

   const auto selectedTracks{
      TrackList::Get(proj).Selected<const WaveTrack>() };

   for (const auto & track : selectedTracks) {
      if (rateOfSelection != RATE_NOT_SELECTED &&
         track->GetRate() != rateOfSelection)
         result.allSameRate = false;
      else if (rateOfSelection == RATE_NOT_SELECTED)
         rateOfSelection = track->GetRate();
   }

   result.anySelected = !selectedTracks.empty();
   result.rateOfSelected = rateOfSelection;

   return  result;
}

using WaveTrackArray = std::vector<std::shared_ptr<WaveTrack>>;

//! ProjectAudioManager::ChooseExistingRecordingTracks
WaveTrackArray ChooseExistingRecordingTracks(
   AudacityProject &proj, bool selectedOnly, double targetRate)
{
   auto p = &proj;
   size_t recordingChannels = std::max(0, AudioIORecordChannels.Read());
   bool strictRules = (recordingChannels <= 2);

   // Iterate over all wave tracks, or over selected wave tracks only.
   // If target rate was specified, ignore all tracks with other rates.
   //
   // In the usual cases of one or two recording channels, seek a first-fit
   // unbroken sub-sequence for which the total number of channels matches the
   // required number exactly.  Never drop inputs or fill only some channels
   // of a track.
   //
   // In case of more than two recording channels, choose tracks only among the
   // selected.  Simply take the earliest wave tracks, until the number of
   // channels is enough.  If there are fewer channels than inputs, but at least
   // one channel, then some of the input channels will be dropped.
   //
   // Resulting tracks may be non-consecutive within the list of all tracks
   // (there may be non-wave tracks between, or non-selected tracks when
   // considering selected tracks only.)

   if (!strictRules && !selectedOnly)
      return {};

   auto &trackList = TrackList::Get(*p);
   WaveTrackArray candidates;
   std::vector<unsigned> channelCounts;
   size_t totalChannels = 0;
   const auto range = trackList.Any<WaveTrack>();
   for (auto candidate : selectedOnly ? range + &Track::IsSelected : range) {
      if (targetRate != RATE_NOT_SELECTED && candidate->GetRate() != targetRate)
         continue;

      // count channels in this track
      const auto nChannels = candidate->NChannels();
      if (strictRules && nChannels > recordingChannels) {
         // The recording would under-fill this track's channels
         // Can't use any partial accumulated results
         // either.  Keep looking.
         candidates.clear();
         channelCounts.clear();
         totalChannels = 0;
         continue;
      }
      else {
         // Might use this but may have to discard some of the accumulated
         while(strictRules &&
               nChannels + totalChannels > recordingChannels) {
            candidates.erase(candidates.begin());
            auto nOldChannels = channelCounts[0];
            assert(nOldChannels > 0);
            channelCounts.erase(channelCounts.begin());
            totalChannels -= nOldChannels;
         }
         candidates.push_back(candidate->SharedPointer<WaveTrack>());
         channelCounts.push_back(nChannels);
         totalChannels += nChannels;
         if (totalChannels >= recordingChannels)
            // Done!
            return candidates;
      }
   }

   if (!strictRules && !candidates.empty())
      // good enough
      return candidates;

   // If the loop didn't exit early, we could not find enough channels
   return {};
}

//! ProjectAudioManager::UseDuplex
bool UseDuplex()
{
   bool duplex;
   gPrefs->Read(wxT("/AudioIO/Duplex"), &duplex, true);
   return duplex;
}

//! Text of the host API's last error, appended to AudioIO's generic text
std::string HostErrorSuffix()
{
   const char *text = PaAAudio_GetLastErrorText();
   if (text && *text)
      return std::string(" (") + text + ")";
   return {};
}

//! Why a stream ended by itself (stats of the most recently started stream)
void EndReason(const char *&reason, std::string &message)
{
   reason = "end";
   message.clear();
   PaAAudioStreamStats st{};
   if (!PaAAudio_GetActiveStreamStats(&st))
      return;
   if (st.disconnected) {
      reason = "device";
      message = "The audio device was disconnected";
   }
   else if (st.warmupTimedOut) {
      reason = "error";
      message = "The microphone delivered no audio";
   }
   else if (st.lastAAudioError != 0) {
      reason = "error";
      message = "Audio device error " + std::to_string(st.lastAAudioError);
   }
}

const char *StateName(int state)
{
   switch (state) {
   case TransportState::Stopped: return "stopped";
   case TransportState::Playing: return "playing";
   case TransportState::Recording: return "recording";
   case TransportState::PausedPlay:
   case TransportState::PausedRecord: return "paused";
   case TransportState::Monitoring: return "monitoring";
   default: return nullptr;
   }
}

} // namespace

TransportManager &TransportManager::Get(AudacityProject &project)
{
   return project.AttachedObjects::Get<TransportManager>(sTransportManagerKey);
}

TransportManager *TransportManager::Find(AudacityProject &project)
{
   return project.AttachedObjects::Find<TransportManager>(sTransportManagerKey);
}

TransportManager::TransportManager(AudacityProject &project)
   : mProject{ project }
{
}

TransportManager::~TransportManager() = default;

void TransportManager::Attach()
{
   mWeakProject = mProject.weak_from_this();

   // Project-owned meters reach AudioIO through the DefaultOptions factory
   auto &projectAudioIO = ProjectAudioIO::Get(mProject);
   projectAudioIO.SetPlaybackMeter(PlaybackMeter());
   projectAudioIO.SetCaptureMeter(CaptureMeter());

   // AdornedRulerPanel::DoSelectionChange: the inactive play region follows
   // the selection (PlayCurrentRegion plays it)
   mSelectionSubscription = ViewInfo::Get(mProject).selectedRegion.Subscribe(
      [this](auto &) { FollowSelection(); });
   FollowSelection();

   // ProjectAudioManager::OnCheckpointFailure
   mCheckpointFailureSubscription = ProjectFileIO::Get(mProject).Subscribe(
      [this](ProjectFileIOMessage message) {
         if (message == ProjectFileIOMessage::CheckpointFailure)
            Stop("error", "The project file could not be written");
      });
}

void TransportManager::FollowSelection()
{
   auto &viewInfo = ViewInfo::Get(mProject);
   if (!viewInfo.playRegion.Active())
      // "Inactivated" play region follows the selection.
      viewInfo.playRegion.SetTimes(
         viewInfo.selectedRegion.t0(), viewInfo.selectedRegion.t1());
}

// ---------------------------------------------------------------------------
// State queries
// ---------------------------------------------------------------------------

bool TransportManager::CanStopAudioStream() const
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      return false;
   return (!gAudioIO->IsStreamActive() ||
           gAudioIO->IsMonitoring() ||
           gAudioIO->GetOwningProject().get() == &mProject );
}

bool TransportManager::OwnStreamActive() const
{
   auto gAudioIO = AudioIO::Get();
   const int token = ProjectAudioIO::Get(mProject).GetAudioIOToken();
   return gAudioIO && token > 0 && gAudioIO->IsAudioTokenActive(token);
}

bool TransportManager::Playing() const
{
   auto gAudioIO = AudioIO::Get();
   return OwnStreamActive() && gAudioIO->GetNumCaptureChannels() == 0;
}

bool TransportManager::Recording() const
{
   auto gAudioIO = AudioIO::Get();
   return OwnStreamActive() && gAudioIO->GetNumCaptureChannels() > 0;
}

int TransportManager::ComputeState() const
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      return TransportState::Stopped;
   if (mStopping)
      return TransportState::Stopping;
   if (gAudioIO->IsMonitoring())
      return TransportState::Monitoring;
   if (!gAudioIO->IsBusy())
      return TransportState::Stopped;
   const bool capture = gAudioIO->GetNumCaptureChannels() > 0;
   const bool paused = gAudioIO->IsPaused();
   if (capture)
      return paused ? TransportState::PausedRecord : TransportState::Recording;
   return paused ? TransportState::PausedPlay : TransportState::Playing;
}

// ---------------------------------------------------------------------------
// Play (ProjectAudioManager::PlayPlayRegion / PlayCurrentRegion)
// ---------------------------------------------------------------------------

int TransportManager::PlayPlayRegion(const SelectedRegion &selectedRegion,
   const AudioIOStartStreamOptions &options, PlayMode mode)
{
   bool canStop = CanStopAudioStream();

   if ( !canStop )
      return -1;

   auto &pStartTime = options.pStartTime;

   bool nonWaveToo = options.playNonWaveTracks;

   double t0 = selectedRegion.t0();
   double t1 = selectedRegion.t1();
   // SelectedRegion guarantees t0 <= t1 (no backwards play in the port)
   const bool newDefault = (mode == PlayMode::loopedPlay);

   mLooping = mode == PlayMode::loopedPlay;
   mCutting = mode == PlayMode::cutPreviewPlay;

   auto gAudioIO = AudioIO::Get();
   if (gAudioIO->IsBusy())
      return -1;

   // Cut preview (CutPreviewPlaybackPolicy) is not offered by the bridge
   if (mode == PlayMode::cutPreviewPlay)
      return -1;

   AudacityProject *p = &mProject;

   auto &tracks = TrackList::Get(*p);

   mLastPlayMode = mode;

   bool hasaudio;
   if (nonWaveToo)
      hasaudio = ! tracks.Any<PlayableTrack>().empty();
   else
      hasaudio = ! tracks.Any<WaveTrack>().empty();

   double latestEnd = tracks.GetEndTime();

   if (!hasaudio) {
      mLooping = mCutting = false;
      return -1;  // No need to continue without audio tracks
   }

   double loopOffset = 0.0;

   if (t1 == t0) {
      if (newDefault) {
         const auto &selectedRegion = ViewInfo::Get( *p ).selectedRegion;
         // play selection if there is one, otherwise
         // set start of play region to project start,
         // and loop the project from current play position.

         if ((t0 > selectedRegion.t0()) && (t0 < selectedRegion.t1())) {
            t0 = selectedRegion.t0();
            t1 = selectedRegion.t1();
         }
         else {
            // loop the entire project
            // Bug2347, loop playback from cursor position instead of project start
            loopOffset = t0 - tracks.GetStartTime();
            if (!pStartTime)
               // TODO move this reassignment elsewhere so we don't need an
               // ugly mutable member
               pStartTime.emplace(loopOffset);
            t0 = tracks.GetStartTime();
            t1 = tracks.GetEndTime();
         }
      } else {
         // move t0 to valid range
         if (t0 < 0) {
            t0 = tracks.GetStartTime();
         }
         else if (t0 > tracks.GetEndTime()) {
            t0 = tracks.GetEndTime();
         }
      }
      t1 = tracks.GetEndTime();
   }
   else {
      t0 = std::max(0.0, std::min(t0, latestEnd));
      t1 = std::max(0.0, std::min(t1, latestEnd));
   }

   int token = -1;

   if (t1 != t0) {
      double mixerLimit = t1;
      if (newDefault) {
         mixerLimit = latestEnd;
         if (pStartTime && *pStartTime >= t1)
            t1 = latestEnd;
      }
      // A token left over from a stream that ended must not make a failed
      // start look like a finished recording (OnAudioIOStopRecording)
      auto &projectAudioIO = ProjectAudioIO::Get(*p);
      if (projectAudioIO.GetAudioIOToken() > 0 &&
          !gAudioIO->IsAudioTokenActive(projectAudioIO.GetAudioIOToken()))
         projectAudioIO.SetAudioIOToken(0);
      PaAAudio_ClearLastError();
      token = gAudioIO->StartStream(
         MakeTransportTracks(tracks, false, nonWaveToo),
         t0, t1, mixerLimit, options);
      if (token != 0) {
         projectAudioIO.SetAudioIOToken(token);
         double start = gAudioIO->GetStreamTime();
         if (start == BAD_STREAM_TIME || !std::isfinite(start))
            start = t0;
         BeginTransport(start, 0);
      }
   }

   if (token <= 0)
      mLooping = mCutting = false;

   return token;
}

bool TransportManager::PlayCurrentRegion(bool newDefault)
{
   if (!CanStopAudioStream())
      return false;

   AudacityProject *p = &mProject;
   // The inactive region follows the selection; also after it was cleared
   FollowSelection();
   const auto &playRegion = ViewInfo::Get( *p ).playRegion;

   auto options = ProjectAudioIO::GetDefaultOptions(*p, newDefault);
   auto mode = newDefault ? PlayMode::loopedPlay : PlayMode::normalPlay;
   const int token = PlayPlayRegion(
      SelectedRegion(playRegion.GetStart(), playRegion.GetEnd()),
      options, mode);
   if (token == 0)
      Fail(ErrorCode::FAILED, Translated(XO(
"Error opening sound device.\nTry changing the audio host, playback device and the project sample rate."))
         + HostErrorSuffix());
   return token > 0;
}

bool TransportManager::QuickPlay(double t0, std::optional<double> t1)
{
   if (!CanStopAudioStream())
      return false;
   // Plays [t0, t1] (or t0 to the end) once with the old default policy;
   // the play region is not touched (unlike AdornedRulerPanel::StartQPPlay)
   auto options = ProjectAudioIO::GetDefaultOptions(mProject, false);
   options.loopEnabled = false;
   // The library's default policy, counting the buffer thread's passes
   options.policyFactory = [](const AudioIOStartStreamOptions &)
      -> std::unique_ptr<PlaybackPolicy> {
      return std::make_unique<CountingPlaybackPolicy>();
   };
   const double end = t1 ? *t1 : t0;
   const int token = PlayPlayRegion(SelectedRegion(t0, end), options,
      PlayMode::normalPlay);
   if (token == 0)
      Fail(ErrorCode::FAILED, Translated(XO(
"Error opening sound device.\nTry changing the audio host, playback device and the project sample rate."))
         + HostErrorSuffix());
   return token > 0;
}

// ---------------------------------------------------------------------------
// Stop / pause / seek
// ---------------------------------------------------------------------------

void TransportManager::Stop(const char *reason, const std::string &message)
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      return;
   if (!CanStopAudioStream())
      return;

   auto &projectAudioIO = ProjectAudioIO::Get(mProject);
   const bool wasRecording = Recording();
   const bool anyStream = gAudioIO->IsBusy() || gAudioIO->IsMonitoring();
   mLastDropouts = 0;

   if (anyStream) {
      SettleSeek();
      // flag that we are stopping (readTransport state 6 while StopStream
      // blocks the engine thread)
      mStopping = true;
      PublishTransport(TransportState::Stopping);
      try {
         gAudioIO->StopStream();
      }
      catch (...) {
         mStopping = false;
         throw;
      }
      mStopping = false;
   }

   mLooping = false;
   mCutting = false;

   //Make sure you tell gAudioIO to unpause
   gAudioIO->SetPaused( false );

   // also clean the meters
   PlaybackMeter()->Clear();
   CaptureMeter()->Clear();

   // TrackPanel::OnTimer does this at the next timer tick
   if (projectAudioIO.GetAudioIOToken() > 0 &&
       !gAudioIO->IsAudioTokenActive(projectAudioIO.GetAudioIOToken()))
      projectAudioIO.SetAudioIOToken(0);

   const int dropouts = mLastDropouts;
   if (wasRecording) {
      if (mRecordingDuplex)
         StoreMeasuredDuplexOffset(mRouteKey);
      mCaptureTracks.clear();
      mRecordingDuplex = false;
      mRouteKey.clear();
      mLastRecordedEnd = -1;
      // Recording committed (new track ids, "Recorded Audio")
      Session::Get().Touch();
   }
   mSeekTarget = mSeekFrom = NAN;
   mSeekIssuedNs = 0;

   UpdateState(reason, message, dropouts);
   ApplyPendingDeviceChange();
}

void TransportManager::SettleSeek()
{
   // Audacity 3.7.9 race: AudioIO::AudioThread acknowledges a stop only from
   // its "loop running" state.  CallbackDoSeek (PortAudio callback) leaves
   // it in the "once" state and re-enables the loop; when StopStream turns
   // the loop off before the buffer thread's next pass, StopStream waits
   // forever in WaitForAudioThreadStopped (Audacity 4 also acknowledges
   // from the "once" state).  So before stopping a stream that was seeked:
   // let the seek be taken and finished, then let the buffer thread run two
   // passes (counted by our playback policies).  Bounded: 1.5 s.
   if (mSeekIssuedNs == 0 || !OwnStreamActive())
      return;
   mSeekIssuedNs = 0;
   auto gAudioIO = AudioIO::Get();
   const auto deadline = MonotonicNowNs() + 1'500'000'000LL;
   const auto pending = [&](double value) {
      return MonotonicNowNs() < deadline && gAudioIO->IsStreamActive() &&
         !gAudioIO->IsPaused() && gAudioIO->GetStreamTime() == value;
   };
   using namespace std::chrono_literals;
   // 1. the callback took the seek: the time left its base
   while (pending(mSeekIssueBase))
      std::this_thread::sleep_for(2ms);
   // 2. CallbackDoSeek returned: the time moves on (the callback that seeks
   //    does not advance it)
   const double jumped = gAudioIO->GetStreamTime();
   while (pending(jumped))
      std::this_thread::sleep_for(2ms);
   // 3. the buffer thread ran a full pass with its loop enabled
   auto &passes = AudioThreadPasses();
   const auto c0 = passes.load(std::memory_order_relaxed);
   while (passes.load(std::memory_order_relaxed) < c0 + 2 &&
          MonotonicNowNs() < deadline)
      std::this_thread::sleep_for(2ms);
}

bool TransportManager::TogglePause(const char *reason,
   const std::string &message)
{
   // ProjectAudioManager::OnPause minus scrubbing.  Pausing while nothing
   // plays would make the next stream start paused: not offered.
   if (!CanStopAudioStream() || !OwnStreamActive())
      return false;
   return SetPaused(!AudioIO::Get()->IsPaused(), reason, message);
}

bool TransportManager::SetPaused(bool paused, const char *reason,
   const std::string &message)
{
   if (!CanStopAudioStream() || !OwnStreamActive())
      return false;
   auto gAudioIO = AudioIO::Get();
   if (gAudioIO->IsPaused() == paused)
      return false;
   constexpr auto publish = true;
   gAudioIO->SetPaused(paused, publish);
   UpdateState(reason, message);
   return true;
}

bool TransportManager::SeekWhilePlaying(double t)
{
   // Never seek while recording (audio-io.md §2.5)
   if (!OwnStreamActive() || Recording())
      return false;
   auto gAudioIO = AudioIO::Get();
   const double now = gAudioIO->GetStreamTime();
   if (now == BAD_STREAM_TIME || !std::isfinite(now))
      return false;
   t = std::max(0.0, t);
   // Relative offset, applied in the callback (CallbackDoSeek) and clamped
   // by the playback policy (OffsetSequenceTime)
   gAudioIO->SeekStream(t - now);
   mSeekTarget = t;
   mSeekFrom = now;
   mSeekIssuedNs = MonotonicNowNs();
   mSeekIssueBase = now;
   ResetDisplay(t);
   PublishTransport(ComputeState());
   return true;
}

// ---------------------------------------------------------------------------
// Record (ProjectAudioManager::OnRecord / DoRecord)
// ---------------------------------------------------------------------------

void TransportManager::Record(bool altAppearance)
{
   bool bPreferNewTrack;
   gPrefs->Read("/GUI/PreferNewTrackRecord", &bPreferNewTrack, false);
   const bool appendRecord = (altAppearance == bPreferNewTrack);

   AudacityProject *p = &mProject;

   const auto &selectedRegion = ViewInfo::Get( *p ).selectedRegion;
   double t0 = selectedRegion.t0();
   double t1 = selectedRegion.t1();
   // When no time selection, recording duration is 'unlimited'.
   if (t1 == t0)
      t1 = DBL_MAX;

   auto options = ProjectAudioIO::GetDefaultOptions(*p);
   WaveTrackArray existingTracks;

   // Checking the selected tracks: counting them and
   // making sure they all have the same rate
   const auto selectedTracks{ GetPropertiesOfSelected(*p) };
   const int rateOfSelected{ selectedTracks.rateOfSelected };
   const bool anySelected{ selectedTracks.anySelected };
   const bool allSameRate{ selectedTracks.allSameRate };

   if (!allSameRate)
      Fail(ErrorCode::FAILED, Translated(XO("The tracks selected "
         "for recording must all have the same sampling rate")));

   if (appendRecord) {
      // Try to find wave tracks to record into.  (If any are selected,
      // try to choose only from them; else if wave tracks exist, may record into any.)
      existingTracks = ChooseExistingRecordingTracks(*p, true, rateOfSelected);
      if (!existingTracks.empty()) {
         t0 = std::max(t0,
            TrackList::Get(*p).Selected<const WaveTrack>()
            .max(&Track::GetEndTime));
         options.rate = rateOfSelected;
      }
      else {
         if (anySelected && rateOfSelected != options.rate)
            Fail(ErrorCode::FAILED, Translated(XO(
               "Too few tracks are selected for recording at this sample rate.\n"
               "(Audacity requires two channels at the same sample rate for\n"
               "each stereo track)")));

         existingTracks = ChooseExistingRecordingTracks(*p, false, options.rate);
         if (!existingTracks.empty())
         {
            const auto endTime = accumulate(
               existingTracks.begin(), existingTracks.end(),
               std::numeric_limits<double>::lowest(),
               [](double acc, auto &pTrack) {
                  return std::max(acc, pTrack->GetEndTime());
               }
            );

            //If there is a suitable track, then adjust t0 so
            //that recording not starts before the end of that track
            t0 = std::max(t0, endTime);
         }
         // If suitable tracks still not found, will record into NEW ones,
         // starting with t0
      }

      // Whether we decided on NEW tracks or not: record within the selection
      // when it ends after the start (API.md transport.record; 3.7.9's test
      // `t1 <= selectedRegion.t0()` can never hold with a time selection)
      if (!selectedRegion.isPoint() && selectedRegion.t1() > t0)
         t1 = selectedRegion.t1();   // record within the selection
      else
         t1 = DBL_MAX;        // record for a long, long time
   }

   TransportSequences transportTracks;
   if (UseDuplex()) {
      // Remove recording tracks from the list of tracks for duplex ("overdub")
      // playback.
      transportTracks =
         MakeTransportTracks(TrackList::Get( *p ), false, true);
      for (const auto &wt : existingTracks) {
         auto end = transportTracks.playbackSequences.end();
         auto it = std::find_if(
            transportTracks.playbackSequences.begin(), end,
            [&wt](const auto& playbackSequence) {
               return playbackSequence->FindChannelGroup() ==
                      wt->FindChannelGroup();
            });
         if (it != end)
            transportTracks.playbackSequences.erase(it);
      }
   }

   std::copy(existingTracks.begin(), existingTracks.end(),
      back_inserter(transportTracks.captureSequences));

   DoRecord(transportTracks, t0, t1, altAppearance, options);
}

bool TransportManager::DoRecord(const TransportSequences &sequences,
   double t0, double t1, bool altAppearance,
   const AudioIOStartStreamOptions &options)
{
   auto &project = mProject;

   auto gAudioIO = AudioIO::Get();
   if (gAudioIO->IsBusy() || !CanStopAudioStream())
      Fail(ErrorCode::AUDIO_BUSY,
         "You can only do this when playing and recording are stopped.");

   mAppending = !altAppearance;

   auto transportSequences = sequences;

   // Will replace any given capture tracks with temporaries
   transportSequences.captureSequences.clear();

   const auto p = &project;
   auto &trackList = TrackList::Get(project);

   bool appendRecord = !sequences.captureSequences.empty();

   auto insertEmptyInterval =
   [&](WaveTrack &track, double t0, bool placeholder) {
      wxString name;
      for (auto i = 1; ; ++i) {
         //i18n-hint a numerical suffix added to distinguish otherwise like-named clips when new record started
         name = XC("%s.%d", "clip name template")
            .Format(track.GetName(), i).Translation();
         if (!track.HasClipNamed(name))
            break;
      }

      auto clip = track.CreateClip(t0, name);
      // So that the empty clip is not skipped for insertion:
      clip->SetIsPlaceholder(true);
      track.InsertInterval(clip, true);
      if (!placeholder)
         clip->SetIsPlaceholder(false);
      return clip;
   };

   auto &pendingTracks = PendingTracks::Get(project);

   // Any failure before the stream runs: forget the pending tracks; an
   // append also inserted an empty clip into the original track, so go
   // back to the current undo state as well
   bool started = false;
   auto cleanup = finally([&] {
      if (started)
         return;
      CancelRecording();
      if (appendRecord) {
         try {
            ProjectHistory::Get(project).RollbackState();
         }
         catch (...) {
         }
      }
   });

   if (appendRecord) {
      // Append recording:
      // Pad selected/all wave tracks to make them all the same length
      for (const auto &sequence : sequences.captureSequences) {
         WaveTrack *wt{};
         if (!(wt = dynamic_cast<WaveTrack *>(sequence.get()))) {
            assert(false);
            continue;
         }

         // If the track was chosen for recording and playback both,
         // remember the original in preroll tracks, before making the
         // pending replacement.
         const auto shared = wt->SharedPointer<WaveTrack>();
         // prerollSequences should be a subset of playbackSequences.
         const auto &range = transportSequences.playbackSequences;
         bool prerollTrack = any_of(range.begin(), range.end(),
            [&](const auto &pSequence){
               return shared.get() == pSequence->FindChannelGroup(); });
         if (prerollTrack)
            transportSequences.prerollSequences.push_back(shared);

         // A function that copies all the non-sample data between
         // wave tracks; in case the track recorded to changes scale
         // type (for instance), during the recording.
         auto updater = [](Track &d, const Track &s){
            assert(d.NChannels() == s.NChannels());
            auto &dst = static_cast<WaveTrack&>(d);
            auto &src = static_cast<const WaveTrack&>(s);
            dst.Init(src);
            // Explicitly share the effect states, the pending track replaces the source on stop
            RealtimeEffectList::ShareStates(dst, src);
         };

         // End of current track is before or at recording start time.
         // Less than or equal, not just less than, to ensure a clip boundary.
         // when append recording.
         const auto lastClip = wt->GetRightmostClip();
         // RoundedT0 to have a new clip created when punch-and-roll
         // recording with the cursor in the second half of the space
         // between two samples
         // (https://github.com/audacity/audacity/issues/5113#issuecomment-1705154108)
         const auto recordingStart =
            std::round(t0 * wt->GetRate()) / wt->GetRate();
         const auto recordingStartsBeforeTrackEnd =
            lastClip && recordingStart < lastClip->GetPlayEndTime();
         // Recording doesn't start before the beginning of the last clip
         // - or the check for creating a new clip or not should be more
         // general than that ...
         assert(
            !recordingStartsBeforeTrackEnd ||
            lastClip->WithinPlayRegion(recordingStart));
         WaveTrack::IntervalHolder newClip{};
         if (!recordingStartsBeforeTrackEnd ||
            lastClip->HasPitchOrSpeed())
            newClip = insertEmptyInterval(*wt, t0, true);
         // Get a copy of the track to be appended, to be pushed into
         // undo history only later.
         const auto pending = static_cast<WaveTrack*>(
            pendingTracks.RegisterPendingChangedTrack(updater, wt)
         );
         // Source clip was marked as placeholder so that it would not be
         // skipped in clip copying.  Un-mark it and its copy now
         if (newClip)
            newClip->SetIsPlaceholder(false);
         if (auto copiedClip = pending->NewestOrNewClip())
            copiedClip->SetIsPlaceholder(false);
         transportSequences.captureSequences
            .push_back(pending->SharedPointer<WaveTrack>());
      }
      pendingTracks.UpdatePendingTracks();
   }

   if (transportSequences.captureSequences.empty()) {
      // recording to NEW track(s).
      bool recordingNameCustom, useTrackNumber, useDateStamp, useTimeStamp;
      wxString defaultTrackName, defaultRecordingTrackName;

      // Count the tracks.
      auto numTracks = trackList.Any<const WaveTrack>().size();

      auto recordingChannels = std::max(1, AudioIORecordChannels.Read());

      gPrefs->Read(wxT("/GUI/TrackNames/RecordingNameCustom"), &recordingNameCustom, false);
      gPrefs->Read(wxT("/GUI/TrackNames/TrackNumber"), &useTrackNumber, false);
      gPrefs->Read(wxT("/GUI/TrackNames/DateStamp"), &useDateStamp, false);
      gPrefs->Read(wxT("/GUI/TrackNames/TimeStamp"), &useTimeStamp, false);
      defaultTrackName = trackList.MakeUniqueTrackName(WaveTrack::GetDefaultAudioTrackNamePreference());
      gPrefs->Read(wxT("/GUI/TrackNames/RecodingTrackName"), &defaultRecordingTrackName, defaultTrackName);

      wxString baseTrackName = recordingNameCustom? defaultRecordingTrackName : defaultTrackName;

      auto newTracks =
         WaveTrackFactory::Get(*p).CreateMany(recordingChannels);
      const auto first = *newTracks->begin();
      int trackCounter = 0;
      for (auto newTrack : newTracks->Any<WaveTrack>()) {
         // Quantize bounds to the rate of the new track.
         if (newTrack == first) {
            if (t0 < DBL_MAX)
               t0 = newTrack->SnapToSample(t0);
            if (t1 < DBL_MAX)
               t1 = newTrack->SnapToSample(t1);
         }

         newTrack->MoveTo(t0);
         wxString nameSuffix = wxString(wxT(""));

         if (useTrackNumber) {
            nameSuffix += wxString::Format(wxT("%d"), 1 + (int) numTracks + trackCounter++);
         }

         if (useDateStamp) {
            if (!nameSuffix.empty()) {
               nameSuffix += wxT("_");
            }
            nameSuffix += wxDateTime::Now().FormatISODate();
         }

         if (useTimeStamp) {
            if (!nameSuffix.empty()) {
               nameSuffix += wxT("_");
            }
            nameSuffix += wxDateTime::Now().FormatISOTime();
         }

         // ISO standard would be nice, but ":" is unsafe for file name.
         nameSuffix.Replace(wxT(":"), wxT("-"));

         if (baseTrackName.empty())
            newTrack->SetName(nameSuffix);
         else if (nameSuffix.empty())
            newTrack->SetName(baseTrackName);
         else
            newTrack->SetName(baseTrackName + wxT("_") + nameSuffix);

         //create a new clip with a proper name before recording is started
         insertEmptyInterval(*newTrack, t0, false);

         transportSequences.captureSequences.push_back(
            std::static_pointer_cast<WaveTrack>(newTrack->shared_from_this())
         );
      }
      pendingTracks.RegisterPendingNewTracks(std::move(*newTracks));
      // Bug 1548.  First of new tracks needs the focus.
      TrackFocus::Get(project).Set(first);
   }

   // Bridge-owned latency correction (audio-io.md §2.7.4): AudioIO reads
   // /AudioIO/LatencyCorrection in StartStream and applies it to every
   // recording.  Only a recording with playback has something to be out of
   // sync with.
   {
      bool swPlaythrough = false;
      gPrefs->Read(wxT("/AudioIO/SWPlaythrough"), &swPlaythrough, false);
      mRecordingDuplex =
         !transportSequences.playbackSequences.empty() || swPlaythrough;
      // The route is decided now: a device change during the take must
      // not file its measurement under the new route
      mRouteKey = mRecordingDuplex ? CurrentRouteKey() : std::string{};
      mUserTrimMs = AudioUserLatencyTrimMs.Read();
      mAppliedCorrectionMs = mRecordingDuplex ? OverdubCorrectionMs() : 0.0;
      mRealignSec = 0;
      AudioIOLatencyCorrection.Write(mAppliedCorrectionMs);
   }

   auto &projectAudioIO = ProjectAudioIO::Get(*p);
   if (projectAudioIO.GetAudioIOToken() > 0 &&
       !gAudioIO->IsAudioTokenActive(projectAudioIO.GetAudioIOToken()))
      projectAudioIO.SetAudioIOToken(0);
   PaAAudio_ClearLastError();

   int token =
      gAudioIO->StartStream(transportSequences, t0, t1, t1, options);

   if (token == 0) {
      // Show error message if stream could not be opened
      auto msg = XO("Error opening recording device.\nError code: %s")
         .Format( gAudioIO->LastPaErrorString() );
      Fail(ErrorCode::FAILED, Translated(msg) + HostErrorSuffix());
   }

   started = true;
   projectAudioIO.SetAudioIOToken(token);

   mCaptureTracks.clear();
   for (const auto &sequence : transportSequences.captureSequences)
      if (auto wt = std::dynamic_pointer_cast<WaveTrack>(sequence))
         mCaptureTracks.push_back(wt);
   mRecordStart = t0;
   mLastRecordedEnd = RecordedEnd();
   mLastLiveTouchNs = MonotonicNowNs();
   double start = gAudioIO->GetStreamTime();
   if (start == BAD_STREAM_TIME || !std::isfinite(start))
      start = t0;
   BeginTransport(start, t0);
   return true;
}

void TransportManager::CancelRecording()
{
   PendingTracks::Get(mProject).ClearPendingTracks();
   mCaptureTracks.clear();
   mRecordingDuplex = false;
   mRouteKey.clear();
}

void TransportManager::RealignTake()
{
   // AudioIO applied the correction known when the take started (StartStream
   // reads it once): the stored measurement of the route, or a low estimate
   // on a route never measured.  The host API measured this take's own
   // round trip meanwhile; move the recorded clips by the difference, so
   // that every take is aligned, also the first one on a new route.
   mRealignSec = 0;
   double measuredMs = 0;
   if (!LastStreamDuplexOffsetMs(measuredMs))
      // No valid AAudio timestamps: the take keeps the applied correction
      return;
   // Audacity's sign: negative = the take was late, move it earlier
   const double shift =
      (-measuredMs + mUserTrimMs - mAppliedCorrectionMs) / 1000.0;
   for (const auto &track : mCaptureTracks) {
      try {
         const double rate = track->GetRate();
         if (rate <= 0)
            continue;
         const auto samples = std::llround(shift * rate);
         if (samples == 0)
            continue;
         const auto clip = track->GetRightmostClip();
         // Only a clip this take created (it starts at the recording start);
         // a take that continued an existing clip is left as it is
         if (!clip ||
             std::fabs(clip->GetPlayStartTime() - mRecordStart) > 1.0 / rate)
            continue;
         const double delta = double(samples) / rate;
         if (delta < 0) {
            // Late: drop what was captured before the aligned start (the
            // input AudioIO would have discarded with the right correction)
            const double cut = clip->GetPlayStartTime() - delta;
            if (cut >= clip->GetPlayEndTime())
               continue;
            clip->ClearLeft(cut);
         }
         // Early (delta > 0): AudioIO discarded too much; that beginning is
         // lost, but the rest lands where it belongs
         clip->ShiftBy(delta);
         mRealignSec = delta;
      }
      catch (const std::exception &e) {
         Events::Log(Events::LogLevel::Warning,
            std::string("could not re-align the recording: ") + e.what());
      }
      catch (...) {
         Events::Log(Events::LogLevel::Warning,
            "could not re-align the recording");
      }
   }
   if (mRealignSec != 0)
      Events::Log(Events::LogLevel::Info, "overdub re-aligned by " +
         std::to_string(mRealignSec * 1000.0) + " ms (applied correction " +
         std::to_string(mAppliedCorrectionMs) + " ms, measured round trip " +
         std::to_string(measuredMs) + " ms)");
}

double TransportManager::RecordedEnd() const
{
   double end = 0;
   for (const auto &track : mCaptureTracks)
      end = std::max(end, track->GetEndTime());
   return end;
}

// ---------------------------------------------------------------------------
// Monitoring
// ---------------------------------------------------------------------------

void TransportManager::StartMonitoring()
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      Fail(ErrorCode::UNSUPPORTED, "audio is not initialized");
   // Already monitoring, or playing/recording (which feed the input meter
   // themselves when they capture): nothing to do
   if (gAudioIO->IsMonitoring() || gAudioIO->IsBusy())
      return;
   PaAAudio_ClearLastError();
   CaptureMeter()->ResetClipping();
   gAudioIO->StartMonitoring(ProjectAudioIO::GetDefaultOptions(mProject));
   if (!gAudioIO->IsMonitoring() || !gAudioIO->IsStreamActive()) {
      auto msg = XO("Error opening recording device.\nError code: %s")
         .Format( gAudioIO->LastPaErrorString() );
      const auto text = Translated(msg) + HostErrorSuffix();
      if (gAudioIO->IsMonitoring())
         // Opened but Pa_StartStream failed
         gAudioIO->StopStream();
      UpdateState("error", text);
      Fail(ErrorCode::FAILED, text);
   }
   UpdateState("user");
}

void TransportManager::StopMonitoring()
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO || !gAudioIO->IsMonitoring())
      return;
   gAudioIO->StopStream();
   CaptureMeter()->Clear();
   UpdateState("user");
   ApplyPendingDeviceChange();
}

// ---------------------------------------------------------------------------
// Tick (TrackPanel::OnTimer)
// ---------------------------------------------------------------------------

void TransportManager::OnStreamDrained()
{
   const char *reason;
   std::string message;
   EndReason(reason, message);
   Stop(reason, message);
}

void TransportManager::OnTick()
{
   auto gAudioIO = AudioIO::Get();
   if (!gAudioIO)
      return;
   auto &projectAudioIO = ProjectAudioIO::Get(mProject);
   const int token = projectAudioIO.GetAudioIOToken();

   // The stream finished (paComplete drained, disconnect, error): only now
   // do end-of-selection playback and fixed-length recordings stop
   if (token > 0 && gAudioIO->IsAudioTokenActive(token) &&
       !gAudioIO->IsStreamActive(token)) {
      OnStreamDrained();
      return;
   }
   if (token > 0 && !gAudioIO->IsAudioTokenActive(token))
      projectAudioIO.SetAudioIOToken(0);

   // A monitoring stream that died (device disconnected, error)
   if (gAudioIO->IsMonitoring() && !gAudioIO->IsStreamActive()) {
      const char *reason;
      std::string message;
      EndReason(reason, message);
      gAudioIO->StopStream();
      CaptureMeter()->Clear();
      UpdateState(reason, message);
      ApplyPendingDeviceChange();
      return;
   }

   if (Recording()) {
      const auto now = MonotonicNowNs();
      if (now - mLastLiveTouchNs >= 200'000'000) {
         mLastLiveTouchNs = now;
         // TrackPanel.cpp: name/gain changes of the originals reach the
         // pending copies
         try {
            PendingTracks::Get(mProject).UpdatePendingTracks();
         }
         catch (...) {
         }
         // Live snapshots while recording (API.md §4.2: <= 5 Hz)
         const double end = RecordedEnd();
         if (end != mLastRecordedEnd) {
            mLastRecordedEnd = end;
            Session::Get().Touch();
         }
      }
   }

   UpdateState("end");
}

// ---------------------------------------------------------------------------
// Transport snapshot and events
// ---------------------------------------------------------------------------

void TransportManager::BeginTransport(double startTime, double recordStart)
{
   mRecordStart = recordStart;
   mSeekIssuedNs = 0;
   ResetDisplay(startTime);
   mSeekTarget = mSeekFrom = NAN;
}

void TransportManager::ResetDisplay(double floor)
{
   mDisplayFloor = floor;
   mHaveShown = false;
   mWrapped = false;
   mLastStreamTime = NAN;
}

double TransportManager::DisplayTime(double t, bool playback, bool paused,
   double outputLatency, bool looping, double loopT0, double loopT1)
{
   // A seek is applied by the callback; until then show its target
   if (std::isfinite(mSeekTarget)) {
      if (std::fabs(t - mSeekFrom) < 1e-9) {
         mLastShown = mSeekTarget;
         mHaveShown = true;
         return mSeekTarget;
      }
      mSeekTarget = mSeekFrom = NAN;
      mLastStreamTime = NAN;
   }

   const double loopLength = loopT1 - loopT0;
   // The stream time jumped back: the loop wrapped (seeks reset the state)
   if (looping && std::isfinite(mLastStreamTime) && t < mLastStreamTime - 1e-6) {
      mWrapped = true;
      mDisplayFloor = loopT0;
   }
   mLastStreamTime = t;

   if (paused && mHaveShown)
      // Frozen where the audible head was
      return mLastShown;

   // The stream time is the track time of the last frame handed to the
   // device; what is audible lags by the output latency (playback only)
   double shown = t;
   bool wrappedBack = false;
   if (playback && !paused && outputLatency > 0) {
      shown = t - outputLatency;
      if (looping && mWrapped && shown < loopT0 && loopLength > 0) {
         // Just wrapped: still hearing the end of the loop
         shown += loopLength;
         wrappedBack = true;
      }
   }
   if (!wrappedBack)
      shown = std::min(std::max(shown, mDisplayFloor), t);

   if (mHaveShown && shown < mLastShown) {
      if (!looping || loopLength <= 0 || mLastShown - shown < 0.5 * loopLength)
         // Latency estimate jitter: never move backwards
         shown = mLastShown;
   }
   mLastShown = shown;
   mHaveShown = true;
   return shown;
}

void TransportManager::PublishTransport(int state)
{
   double f[kTransportFields] = {};
   auto gAudioIO = AudioIO::Get();
   f[0] = state;
   f[1] = NAN;
   f[2] = NAN;

   PaAAudioStreamStats st{};
   const bool haveStats = PaAAudio_GetActiveStreamStats(&st) != 0;

   const auto &playRegion = ViewInfo::Get(mProject).playRegion;
   const bool own = OwnStreamActive();
   const bool looping = own && mLooping && playRegion.Active() &&
      !playRegion.Empty();
   const double loopT0 = looping ? playRegion.GetStart() : 0.0;
   const double loopT1 = looping ? playRegion.GetEnd() : 0.0;

   const auto now = MonotonicNowNs();
   if (gAudioIO && own && state != TransportState::Stopping) {
      const double t = gAudioIO->GetStreamTime();
      if (t != BAD_STREAM_TIME && std::isfinite(t)) {
         f[1] = t;
         const double latency = haveStats && st.running && st.hasOutput
            ? st.outputLatencySec : -1.0;
         f[2] = DisplayTime(t, gAudioIO->GetNumPlaybackChannels() > 0,
            gAudioIO->IsPaused(), latency, looping, loopT0, loopT1);
      }
   }
   f[3] = double(now);
   f[4] = loopT0;
   f[5] = loopT1;
   f[6] = looping ? 1.0 : 0.0;
   const bool active = state != TransportState::Stopped &&
      state != TransportState::Stopping;
   double rate = active ? double(mDisplayedRate.load()) : 0.0;
   if (active && rate <= 0 && haveStats && st.running)
      rate = st.sampleRate;
   f[7] = rate;
   f[8] = haveStats && st.outputLatencySec > 0 ? st.outputLatencySec : 0.0;
   f[9] = haveStats && st.inputLatencySec > 0 ? st.inputLatencySec : 0.0;
   f[10] = gAudioIO && own && gAudioIO->IsCapturing() ? 1.0 : 0.0;
   f[11] = 1.0;
   const bool recording = state == TransportState::Recording ||
      state == TransportState::PausedRecord;
   f[12] = recording && own ? mRecordStart : 0.0;
   f[13] = double(Session::Get().Generation());
   aubridge::PublishTransport(f);
}

void TransportManager::UpdateState(const char *reason,
   const std::string &message, int dropouts)
{
   const int state = ComputeState();
   PublishTransport(state);
   if (state == TransportState::Stopping)
      return;
   auto gAudioIO = AudioIO::Get();
   // A stream this project did not start through the transport (effect
   // preview): the effects module reports it
   if (gAudioIO && gAudioIO->IsBusy() && !OwnStreamActive() &&
       !gAudioIO->IsMonitoring())
      return;
   const char *name = StateName(state);
   if (!name || mLastEventState == name)
      return;
   mLastEventState = name;
   json payload{ { "state", name }, { "reason", reason },
      { "dropouts", dropouts } };
   if (!message.empty())
      payload["message"] = message;
   Events::Emit("transport", payload);
   // audio.busy and the NB/BUSY/PAUSED/CNB flags changed
   Session::Get().ScheduleSnapshot();
}

// ---------------------------------------------------------------------------
// AudioIOListener
// ---------------------------------------------------------------------------

void TransportManager::OnAudioIORate(int rate)
{
   mDisplayedRate = rate;
}

void TransportManager::OnAudioIOStartRecording()
{
   // Auto-save was done here before, but it is unnecessary, provided there
   // are sufficient autosaves when pushing or modifying undo states.
}

// This is called after recording has stopped and all tracks have flushed.
void TransportManager::OnAudioIOStopRecording()
{
   auto &project = mProject;
   auto &projectAudioIO = ProjectAudioIO::Get( project );

   // Only push state if we were capturing and not monitoring
   if (projectAudioIO.GetAudioIOToken() > 0)
   {
      auto &history = ProjectHistory::Get( project );

      // Inside AudioIO::StopStream: an exception must not escape (the
      // stream token would never be reset)
      try {
         // Add to history
         // We want this to have No-fail-guarantee if we get here from exception
         // handling of recording, and that means we rely on the last autosave
         // successfully committed to the database, not risking a failure
         auto flags = AudioIO::Get()->HasRecordingException()
            ? UndoPush::NOAUTOSAVE
            : UndoPush::NONE;
         history.PushState(XO("Recorded Audio"), XO("Record"), flags);
      }
      catch (const std::exception &e) {
         Events::Log(Events::LogLevel::Error,
            std::string("Recorded Audio: ") + e.what());
      }
      catch (...) {
         Events::Log(Events::LogLevel::Error, "Recorded Audio: exception");
      }

      // Now, we may add a label track to give information about
      // dropouts.  We allow failure of this.
      auto gAudioIO = AudioIO::Get();
      auto &intervals = gAudioIO->LostCaptureIntervals();
      mLastDropouts = int(intervals.size());
      if (intervals.size())
         AddDropoutLabels(intervals);
   }
}

void TransportManager::AddDropoutLabels(
   const std::vector<std::pair<double, double>> &intervals)
{
   // DropoutDetector.cpp
   try {
      auto &project = mProject;
      // Make a track with labels for recording errors
      auto &tracks = TrackList::Get( project );

      /* i18n-hint:  A name given to a track, appearing as its menu button.
       The translation should be short or else it will not display well.
       At most, about 11 Latin characters.
       Dropout is a loss of a short sequence of audio sample data from the
       recording */
      auto pTrack = LabelTrack::Create(tracks, tracks.MakeUniqueTrackName(_("Dropouts")));
      long counter = 1;
      for (auto &interval : intervals) {
         // The recorded clips may have been re-aligned (RealignTake)
         const double t0 = std::max(0.0, interval.first + mRealignSec);
         pTrack->AddLabel(
            SelectedRegion{ t0, t0 + interval.second },
            wxString::Format(wxT("%ld"), counter++));
      }

      auto &history = ProjectHistory::Get( project );
      history.ModifyState( true ); // this might fail and throw
   }
   catch (...) {
      Events::Log(Events::LogLevel::Warning, "could not add the dropout labels");
   }

   // ShowWarningDialog(..., wxT("DropoutDetected"), ...): only while the
   // warning is enabled (the same key also enables the detection)
   bool warn = true;
   gPrefs->Read(wxT("/Warnings/DropoutDetected"), &warn, true);
   if (!warn)
      return;
   BasicUI::CallAfter([] {
      using namespace BasicUI;
      ShowMessageBox(XO("\
Recorded audio was lost at the labeled locations. Possible causes:\n\
\n\
Other applications are competing with Audacity for processor time\n\
\n\
You are saving directly to a slow external storage device\n\
"
         ),
         MessageBoxOptions{}
            .Caption(XO("Warning"))
            .IconStyle(Icon::Warning));
   });
}

void TransportManager::OnAudioIONewBlocks()
{
   // AUDIO THREAD: only post
   BasicUI::CallAfter([wp = mWeakProject] {
      if (auto pProject = wp.lock()) {
         try {
            ProjectFileIO::Get(*pProject).AutoSave(true);
         }
         catch (...) {
         }
      }
   });
}

void TransportManager::OnCommitRecording()
{
   PendingTracks::Get(mProject).ApplyPendingTracks();
   // Inside AudioIO::StopStream, before OnAudioIOStopRecording pushes the
   // "Recorded Audio" state (RealignTake does not throw)
   if (mRecordingDuplex)
      RealignTake();
}

void TransportManager::OnSoundActivationThreshold()
{
   // CALLBACK THREAD (only with /AudioIO/SoundActivatedRecord): post
   BasicUI::CallAfter([wp = std::weak_ptr<TransportManager>(weak_from_this())] {
      auto self = wp.lock();
      if (!self)
         return;
      auto gAudioIO = AudioIO::Get();
      if (gAudioIO && &self->mProject == gAudioIO->GetOwningProject().get())
         self->TogglePause();
   });
}

} // namespace aubridge
