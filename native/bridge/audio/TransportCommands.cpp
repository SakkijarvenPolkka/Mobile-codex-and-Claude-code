/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  TransportCommands.cpp

  transport.* commands (API.md §3.3), the engine tick (TrackPanel::OnTimer),
  the stream finalizer, the snapshot contributor (synthetic ids of pending
  recording tracks, growing clips of tracks being appended to), the
  waveVersion of tracks being recorded, and the
  ProjectAudioIO::DefaultOptions hook (copy of the Scope at the end of
  src/ProjectAudioManager.cpp).

**********************************************************************/
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <optional>
#include <vector>

#include "AudioIO.h"
#include "PendingTracks.h"
#include "Project.h"
#include "ProjectAudioIO.h"
#include "ProjectHistory.h"
#include "Sequence.h"
#include "Track.h"
#include "ViewInfo.h"
#include "WaveClip.h"
#include "WaveTrack.h"

#include "AudioModule.h"
#include "DefaultPlaybackPolicy.h"
#include "TransportManager.h"

#include "BridgeError.h"
#include "Edit.h"
#include "Hooks.h"
#include "Json.h"
#include "ModuleRegistry.h"
#include "Session.h"

namespace aubridge {

namespace {

// ---------------------------------------------------------------------------
// ProjectAudioIO::DefaultOptions (ProjectAudioManager.cpp sScope)
// ---------------------------------------------------------------------------

AudioIOStartStreamOptions DefaultOptions(AudacityProject &project,
   bool newDefault)
{
   //! Invoke the library default implemantation directly bypassing the hook
   auto options = ProjectAudioIO::DefaultOptionsFactory(project, newDefault);

   //! Decorate with more info
   options.listener = TransportManager::Get(project).shared_from_this();

   bool loopEnabled = ViewInfo::Get(project).playRegion.Active();
   options.loopEnabled = loopEnabled;

   if (newDefault) {
      const double trackEndTime = TrackList::Get(project).GetEndTime();
      const double loopEndTime = ViewInfo::Get(project).playRegion.GetEnd();
      options.policyFactory = [&project, trackEndTime, loopEndTime](
         const AudioIOStartStreamOptions &options)
            -> std::unique_ptr<PlaybackPolicy>
      {
         return std::make_unique<DefaultPlaybackPolicy>( project,
            trackEndTime, loopEndTime, options.pStartTime,
            options.loopEnabled, options.variableSpeed);
      };

      // Start play from left edge of selection
      options.pStartTime.emplace(ViewInfo::Get(project).selectedRegion.t0());
   }

   return options;
}

std::unique_ptr<ProjectAudioIO::DefaultOptions::Scope> &OptionsScope()
{
   static std::unique_ptr<ProjectAudioIO::DefaultOptions::Scope> scope;
   return scope;
}

AudacityProject &Project()
{
   return Session::Get().RequireProject();
}

void RequireRecordPermission(const char *what)
{
   if (!Session::Get().RecordPermission())
      Fail(ErrorCode::UNSUPPORTED, std::string(what) +
         " needs the microphone permission (RECORD_AUDIO); report it with "
         "audio.permission");
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

json Play(const json &args)
{
   auto &project = Project();
   const bool loop = OptBool(args, "loop").value_or(false);
   const auto t0 = OptDouble(args, "t0");
   const auto t1 = OptDouble(args, "t1");
   if (t1 && !t0)
      Fail(ErrorCode::INVALID_ARGS, "t1 needs t0");
   if (t0 && t1 && *t1 < *t0)
      Fail(ErrorCode::INVALID_ARGS, "t1 must not be less than t0");

   auto &transport = TransportManager::Get(project);
   // Clip indicators are sticky until play/record starts (API.md §6.5)
   PlaybackMeter()->ResetClipping();

   bool started = false;
   if (t0) {
      // Quick-Play: once, never loops, play region untouched
      started = transport.QuickPlay(*t0, t1);
   }
   else {
      if (loop) {
         // Transport ▸ Looping ▸ Enable (SelectUtilities::
         // SetPlayRegionToSelection), the whole project for a point selection
         auto &viewInfo = ViewInfo::Get(project);
         auto &playRegion = viewInfo.playRegion;
         if (!playRegion.Active()) {
            const auto &selection = viewInfo.selectedRegion;
            double a = selection.t0(), b = selection.t1();
            if (selection.isPoint()) {
               auto &tracks = TrackList::Get(project);
               a = std::max(0.0, tracks.GetStartTime());
               b = tracks.GetEndTime();
            }
            if (b > a) {
               playRegion.SetAllTimes(a, b);
               playRegion.SetActive(true);
               // also when playing fails below
               Session::Get().ScheduleSnapshot();
            }
         }
      }
      // Space (PlayCurrentRegion with the DefaultPlaybackPolicy)
      started = transport.PlayCurrentRegion(true);
   }
   transport.UpdateState("user");
   return json{ { "started", started } };
}

json StopCommand(const json &)
{
   auto *project = Session::Get().Project();
   if (project)
      TransportManager::Get(*project).Stop("user");
   else if (auto gAudioIO = AudioIO::Get();
            gAudioIO && (gAudioIO->IsBusy() || gAudioIO->IsMonitoring())) {
      gAudioIO->StopStream();
      PlaybackMeter()->Clear();
      CaptureMeter()->Clear();
   }
   return json::object();
}

json Pause(const json &)
{
   auto *project = Session::Get().Project();
   bool toggled = false;
   if (project)
      toggled = TransportManager::Get(*project).TogglePause();
   return json{ { "toggled", toggled } };
}

json Record(const json &args)
{
   auto &project = Project();
   const bool newTrack = ArgBool(args, "newTrack");
   RequireRecordPermission("Recording");
   auto &transport = TransportManager::Get(project);
   CaptureMeter()->ResetClipping();
   PlaybackMeter()->ResetClipping();
   try {
      transport.Record(newTrack);
   }
   catch (...) {
      // A failed append went back to the current undo state
      Session::Get().Touch();
      throw;
   }
   transport.UpdateState("user");
   // Pending tracks appeared (new tracks with synthetic ids, or the
   // growing copies of the tracks appended to)
   Session::Get().Touch();
   return json::object();
}

json Seek(const json &args)
{
   auto &project = Project();
   const double t = std::max(0.0, ArgDouble(args, "t"));
   auto &transport = TransportManager::Get(project);
   if (transport.OwnStreamActive()) {
      // While playing: jump; while recording: ignored (never seek a
      // recording, audio-io.md §2.5)
      transport.SeekWhilePlaying(t);
      return json::object();
   }
   // Stopped: moves the cursor (= select.set {t, t})
   ViewInfo::Get(project).selectedRegion.setTimes(t, t);
   ModifyState(project, false);
   return json::object();
}

json Skip(bool toEnd)
{
   auto &project = Project();
   auto &transport = TransportManager::Get(project);
   auto &tracks = TrackList::Get(project);
   const double target = toEnd ? tracks.GetEndTime() : 0.0;
   if (transport.Recording())
      Fail(ErrorCode::AUDIO_BUSY,
         "You can only do this when playing and recording are stopped.");
   if (transport.OwnStreamActive()) {
      // While playing (also paused): move the play head
      transport.SeekWhilePlaying(target);
      return json::object();
   }
   if (AudioBusy(project))
      Fail(ErrorCode::AUDIO_BUSY,
         "You can only do this when playing and recording are stopped.");
   // Viewport::ScrollToStart / ScrollToEnd (without scrolling: Kotlin owns
   // the view) + ProjectHistory::ModifyState(false) (SelectMenus.cpp)
   auto &selectedRegion = ViewInfo::Get(project).selectedRegion;
   if (toEnd) {
      selectedRegion.setT1(target, false);
      selectedRegion.setT0(target);
   }
   else {
      selectedRegion.setT0(0, false);
      selectedRegion.setT1(0);
   }
   ModifyState(project, false);
   return json::object();
}

json Monitor(const json &args)
{
   auto &project = Project();
   const bool enabled = ArgBool(args, "enabled");
   auto &transport = TransportManager::Get(project);
   if (enabled) {
      RequireRecordPermission("Monitoring");
      transport.StartMonitoring();
   }
   else
      transport.StopMonitoring();
   return json::object();
}

//! Test helper (audio.debugSamples): samples of a track channel
json DebugSamples(const json &args)
{
   auto &project = Project();
   auto &track = RequireWaveTrack(project, ArgInt(args, "trackId"));
   const auto channel = OptInt(args, "channel").value_or(0);
   if (channel < 0 || channel >= int64_t(track.NChannels()))
      Fail(ErrorCode::INVALID_ARGS, "no such channel");
   const double t0 = ArgDouble(args, "t0");
   const double t1 = ArgDouble(args, "t1");
   if (t1 < t0)
      Fail(ErrorCode::INVALID_ARGS, "t1 must not be less than t0");
   const auto s0 = track.TimeToLongSamples(t0);
   const auto s1 = track.TimeToLongSamples(t1);
   const auto len = (s1 - s0).as_long_long();
   if (len > 2'000'000)
      Fail(ErrorCode::INVALID_ARGS, "at most 2000000 samples");
   std::vector<float> buffer(size_t(std::max<long long>(len, 0)));
   if (!buffer.empty()) {
      float *const buffers[] = { buffer.data() };
      track.GetFloats(size_t(channel), 1, buffers, s0, buffer.size());
   }
   json values = json::array();
   for (float v : buffer)
      values.push_back(v);
   return json{ { "rate", track.GetRate() },
      { "t0", s0.as_double() / track.GetRate() },
      { "values", std::move(values) } };
}

// ---------------------------------------------------------------------------
// Pending recording tracks in the snapshot / waveVersion
// ---------------------------------------------------------------------------

//! A track the AudioIO thread appends to right now
bool IsCaptureTrack(const WaveTrack &track)
{
   auto *project = Session::Get().Project();
   if (!project)
      return false;
   auto *transport = TransportManager::Find(*project);
   if (!transport)
      return false;
   for (const auto &pTrack : transport->CaptureTracks())
      if (pTrack.get() == &track)
         return true;
   return false;
}

//! waveVersion without walking Sequence::GetBlockArray(): that std::deque
//! is appended to by the AudioIO thread while recording
int64_t RecordingWaveVersion(const WaveTrack &track)
{
   uint64_t h = 1469598103934665603ULL;
   const auto mix = [&h](uint64_t v) {
      for (int i = 0; i < 8; ++i) {
         h ^= (v >> (8 * i)) & 0xff;
         h *= 1099511628211ULL;
      }
   };
   const auto mixDouble = [&mix](double d) {
      uint64_t v;
      static_assert(sizeof v == sizeof d);
      std::memcpy(&v, &d, sizeof v);
      mix(v);
   };
   mix(0x7265636f7264ULL);   // "record": differs from the committed hash
   mix(track.NChannels());
   for (const auto &pClip : track.SortedIntervalArray()) {
      mixDouble(pClip->GetPlayStartTime());
      mixDouble(pClip->GetPlayEndTime());
      for (size_t ii = 0; ii < pClip->NChannels(); ++ii)
         if (auto seq = pClip->GetSequence(ii))
            mix(uint64_t(seq->GetNumSamples().as_long_long()));
   }
   return int64_t(h & ((uint64_t(1) << 62) - 1));
}

void FillWaveClips(json &t, const WaveTrack &track)
{
   const auto clips = track.SortedIntervalArray();
   t["start"] = clips.empty() ? 0.0 : Finite(track.GetStartTime());
   t["end"] = clips.empty() ? 0.0 : Finite(track.GetEndTime());
   t["waveVersion"] = RecordingWaveVersion(track);
   json array = json::array();
   int index = 0;
   for (const auto &clip : clips)
      array.push_back(json{ { "index", index++ },
         { "name", ToUtf8(clip->GetName()) },
         { "start", Finite(clip->GetPlayStartTime()) },
         { "end", Finite(clip->GetPlayEndTime()) },
         { "trimLeft", Finite(clip->GetTrimLeft()) },
         { "trimRight", Finite(clip->GetTrimRight()) },
         { "stretchRatio", Finite(clip->GetStretchRatio(), 1.0) },
         { "rate", clip->GetRate() } });
   t["clips"] = std::move(array);
}

void ContributeSnapshot(json &snapshot, AudacityProject &project)
{
   auto it = snapshot.find("tracks");
   if (it == snapshot.end() || !it->is_array())
      return;
   auto &array = *it;
   auto &tracks = TrackList::Get(project);
   const auto &pending = PendingTracks::Get(project);
   size_t i = 0;
   int64_t synthetic = -2;
   // Same iteration as the spine's BuildSnapshot
   for (auto pTrack : tracks) {
      if (i >= array.size())
         break;
      auto &t = array[i++];
      if (TrackIdValue(*pTrack) == -1) {
         // Pending new recording track (TrackId{} until ApplyPendingTracks):
         // synthetic id -(2 + index among pending new tracks) (API.md §3.2)
         t["id"] = synthetic--;
         if (auto wt = dynamic_cast<const WaveTrack *>(pTrack))
            FillWaveClips(t, *wt);
         continue;
      }
      if (auto wt = dynamic_cast<WaveTrack *>(pTrack)) {
         // A track being appended to: show its growing pending copy
         auto &substitute = pending.SubstitutePendingChangedTrack(*wt);
         if (&substitute != wt)
            if (auto swt = dynamic_cast<const WaveTrack *>(&substitute))
               FillWaveClips(t, *swt);
      }
   }
}

// ---------------------------------------------------------------------------
// Tick
// ---------------------------------------------------------------------------

void PublishWithoutProject()
{
   double f[kTransportFields] = {};
   auto gAudioIO = AudioIO::Get();
   int state = TransportState::Stopped;
   if (gAudioIO && gAudioIO->IsMonitoring())
      state = TransportState::Monitoring;
   else if (gAudioIO && gAudioIO->IsBusy())
      state = gAudioIO->GetNumCaptureChannels() > 0
         ? TransportState::Recording : TransportState::Playing;
   f[0] = state;
   f[1] = f[2] = NAN;
   f[3] = double(MonotonicNowNs());
   f[11] = 1.0;
   PublishTransport(f);
}

void Tick()
{
   if (auto *project = Session::Get().Project())
      TransportManager::Get(*project).OnTick();
   else
      PublishWithoutProject();
   ApplyPendingDeviceChange();
}

} // namespace

void ResetTransportState()
{
   OptionsScope().reset();
   PlaybackMeter()->Clear();
   CaptureMeter()->Clear();
   PlaybackMeter()->ResetClipping();
   CaptureMeter()->ResetClipping();
   PublishWithoutProject();
}

void RegisterTransportCommands(ModuleRegistry &registry)
{
   // The listener, loop flag and DefaultPlaybackPolicy for every stream a
   // project starts (also effect previews), like src/ProjectAudioManager.cpp
   OptionsScope().reset();
   OptionsScope() =
      std::make_unique<ProjectAudioIO::DefaultOptions::Scope>(DefaultOptions);

   const unsigned idle = NeedsProject | NeedsIdleAudio;
   registry.AddCommand("transport.play", Play, idle | SelectionOnly);
   registry.AddCommand("transport.stop", StopCommand);
   registry.AddCommand("transport.pause", Pause);
   registry.AddCommand("transport.record", Record, idle);
   registry.AddCommand("transport.seek", Seek, NeedsProject | SelectionOnly);
   registry.AddCommand("transport.skipToStart",
      [](const json &) { return Skip(false); }, NeedsProject | SelectionOnly);
   registry.AddCommand("transport.skipToEnd",
      [](const json &) { return Skip(true); }, NeedsProject | SelectionOnly);
   registry.AddCommand("transport.monitor", Monitor, NeedsProject);
   registry.AddCommand("audio.debugSamples", DebugSamples,
      NeedsProject | NeedsIdleAudio);

   registry.AddProjectOpened([](AudacityProject &project) {
      TransportManager::Get(project).Attach();
      TransportManager::Get(project).UpdateState("user");
   });
   registry.AddProjectClosing([](AudacityProject &project) {
      // Stop streams for that project (a recording is committed first)
      auto &transport = TransportManager::Get(project);
      transport.Stop("user");
      auto gAudioIO = AudioIO::Get();
      if (gAudioIO && (gAudioIO->IsBusy() || gAudioIO->IsMonitoring()) &&
          (!gAudioIO->GetOwningProject() ||
           gAudioIO->GetOwningProject().get() == &project))
         gAudioIO->StopStream();
   });
   registry.AddTickHandler(Tick);
   registry.AddSnapshotContributor(ContributeSnapshot);

   SetStreamFinalizer([](AudacityProject &project) {
      TransportManager::Get(project).OnStreamDrained();
   });
   // Tracks being recorded: a hash that does not walk the block deque the
   // AudioIO thread appends to.  The display module may install its own
   // provider later (it then must handle tracks being recorded itself).
   SetWaveVersionProvider([](const WaveTrack &track) -> int64_t {
      const bool pendingNew = TrackIdValue(track) == -1;
      if (pendingNew || IsCaptureTrack(track))
         return RecordingWaveVersion(track);
      return DefaultWaveVersion(track);
   });
}

} // namespace aubridge
