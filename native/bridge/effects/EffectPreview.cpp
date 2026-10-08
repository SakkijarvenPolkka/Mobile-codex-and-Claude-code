/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectPreview.cpp

  effects.preview / effects.stopPreview (API.md §3.3).

  Port of src/effects/EffectPreview.cpp (Audacity 3.7.9; Dominic Mazzoni,
  Vaughan Johnson, Martyn Shaw, Paul Licameli) and of MakeTransportTracks
  (src/TransportUtilities.cpp), made non-blocking: the effect renders a few
  seconds into a temporary track list on the engine thread (progress
  "Preparing preview" with Stop), then AudioIO plays it and the command
  returns.  The engine tick notices the end of the stream; effects.
  stopPreview, transport.stop (AudioIO::StopStream), closing the project
  and Stop() end it early.  `transport` events with reason "preview"
  report the start and the end.

  The desktop code runs inside EffectBase::DoEffect (its dialog), whose
  context fields (tracks, times, factory, rate) and Instance::Init are
  reproduced here first (EffectContext, effects.md §5.9).

**********************************************************************/
#include "EffectsInternal.h"

#include <algorithm>

#include "Edit.h"
#include "Events.h"
#include "Modules.h"
#include "Session.h"

#include "AudioIO.h"
#include "BasicUI.h"
#include "Effect.h"
#include "EffectBase.h"
#include "MixAndRender.h"
#include "Mix.h"
#include "PluginManager.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectAudioIO.h"
#include "ProjectTimeSignature.h"
#include "StretchingSequence.h"
#include "TempoChange.h"
#include "ViewInfo.h"
#include "WaveTrack.h"

namespace aubridge {
namespace effects {
namespace {

struct PreviewSession {
   //! The rendered tracks; must outlive the stream (AudioIO holds
   //! sequences that refer to them until StopStream)
   std::shared_ptr<TrackList> tracks;
   int token = 0;
   AudacityProject *project = nullptr;
};

std::optional<PreviewSession> &Current()
{
   static std::optional<PreviewSession> session;
   return session;
}

void EmitTransport(const char *state)
{
   Events::Emit("transport", json{ { "state", state }, { "reason", "preview" },
      { "dropouts", 0 } });
   // audio.busy and the NB/BUSY flags changed
   Session::Get().ScheduleSnapshot();
}

//! Ends the preview: StopStream when the stream is still ours (drained or
//! not), drop the temporary tracks, emit the `transport` event
void Finish()
{
   auto &current = Current();
   if (!current)
      return;
   auto session = std::move(*current);
   current.reset();
   if (auto audioIO = AudioIO::Get();
       audioIO && audioIO->IsAudioTokenActive(session.token)) {
      try {
         audioIO->StopStream();
      }
      catch (...) {
         Events::Log(Events::LogLevel::Warning, "preview: StopStream failed");
      }
   }
   // AudioIO dropped its sequences in StopStream (or before, when someone
   // else stopped the stream)
   session.tracks.reset();
   EmitTransport("stopped");
}

void Tick()
{
   auto &current = Current();
   if (!current)
      return;
   auto audioIO = AudioIO::Get();
   // Stream drained (StopStream still pending) or stopped by someone else
   // (transport.stop)
   if (!audioIO || !audioIO->IsStreamActive(current->token))
      Finish();
}

// src/TransportUtilities.cpp MakeTransportTracks(trackList, true, false)
TransportSequences MakeTransportTracks(TrackList &trackList)
{
   TransportSequences result;
   for (auto pTrack : trackList.Any<WaveTrack>() + &Track::IsSelected)
      result.playbackSequences.push_back(
         StretchingSequence::Create(*pTrack, pTrack->GetClipInterfaces()));
   return result;
}

json PreviewCmd(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto audioIO = AudioIO::Get();
   if (!audioIO)
      Fail(ErrorCode::UNSUPPORTED, "audio is not available");
   // A new preview replaces a running one
   Finish();
   if (AudioBusy(project))
      Fail(ErrorCode::AUDIO_BUSY,
         "cannot preview while audio is playing or recording");

   auto fx = LoadEffect(ArgString(args, "id"));
   ApplyParamArgs(fx, args);
   std::optional<double> explicitDuration;
   if (auto d = OptDouble(args, "duration"))
      explicitDuration = ValidateDuration(*d);
   if (fx.type == EffectTypeProcess || fx.type == EffectTypeAnalyze)
      RequireAudioSelection(project, PluginManager::Get().GetName(fx.id),
         fx.special == Special::NoiseReduction);
   if (fx.special == Special::NoiseReduction) {
      if (!NoiseReductionHasProfile(*fx.plugin))
         Fail(ErrorCode::FAILED, Translated(XO(
"Select a few seconds of just noise so Audacity knows what to filter out,\nthen click Get Noise Profile:")));
      // The dialog previews the reduce step (NoiseReduction.cpp OnPreview)
      NoiseReductionSetProfileMode(*fx.plugin, false);
   }

   auto &effect = *fx.effect;
   auto &settings = *fx.settings;
   const bool isGenerator = fx.type == EffectTypeGenerate;
   const bool isNyquist = effect.GetFamily() == NYQUISTEFFECTS_FAMILY;

   CaptureScope capture;
   EffectContext context{ effect, project };

   // EffectBase::DoEffect: the duration the dialog sees
   {
      double duration = effect.mT1 - effect.mT0;
      if (isGenerator) {
         if (explicitDuration)
            duration = *explicitDuration;
         else if (duration <= 0)
            duration = LastUsedDuration(fx);
      }
      settings.extra.SetDuration(duration);
   }

   // The dialog's instance (Init: Amplify's peak, EQ's rate, ...) and what
   // the dialog did after it (effects.md §5.4)
   if (!EffectBase::FindInstance(effect))
      Fail(ErrorCode::FAILED, capture.HasMessage() ? capture.Message()
         : Translated(effect.GetName()) + ": the preview could not be prepared");
   PostInitFixups(effect, settings);   // a refusal only concerns Apply

   // ---- src/effects/EffectPreview.cpp ------------------------------------
   auto cleanup0 = effect.BeginPreview(settings);

   const bool noSelectedAudio = effect.mNumTracks == 0;
   if (noSelectedAudio && !isGenerator)
      Fail(ErrorCode::NO_SELECTION, "nothing to preview");

   double previewLen = 6.0;
   if (gPrefs)
      gPrefs->Read(wxT("/AudioIO/EffectsPreviewLen"), &previewLen, 6.0);
   if (!(previewLen > 0))
      previewLen = 6.0;

   auto &base = static_cast<EffectBase &>(effect);
   double previewDuration;
   if (isNyquist && isGenerator)
      previewDuration = base.CalcPreviewInputLength(settings, previewLen);
   else
      previewDuration = std::min(settings.extra.GetDuration(),
         base.CalcPreviewInputLength(settings, previewLen));

   double t1 = effect.mT0 + previewDuration;
   if (t1 > effect.mT1 && !isGenerator)
      t1 = effect.mT1;
   if (!(t1 > effect.mT0))
      Fail(ErrorCode::NO_SELECTION, "nothing to preview");

   // The effect object is restored exactly as it was (EffectPreview's
   // valueRestorers and the re-Init in its cleanup)
   const auto saveTracks = effect.mTracks;
   const double saveT0 = effect.mT0, saveT1 = effect.mT1;
   bool success = true;
   std::shared_ptr<TrackList> temp;
   double playEnd = 0;
   {
      auto restore = finally([&] {
         effect.mTracks = saveTracks;
         effect.mT0 = saveT0;
         effect.mT1 = saveT1;
         effect.mIsPreview = false;
         effect.mProgress = nullptr;
         // (a destructor must not throw: Init may read audio)
         try {
            if (auto pInstance = std::dynamic_pointer_cast<EffectInstanceEx>(
                   effect.MakeInstance()))
               pInstance->Init();
         }
         catch (...) {
         }
      });

      if (!effect.PreviewsFullSelection())
         effect.mT1 = t1;

      // Build the temporary list; same owning project, so FindProject()
      // works within Process()
      temp = TrackList::Create(&project);
      const double tempo = ProjectTimeSignature::Get(project).GetTempo();
      auto addTrack = [&](const std::shared_ptr<WaveTrack> &track) {
         // The project's tempo listener does not watch this list
         DoProjectTempoChange(*track, tempo);
         track->SetMute(false);
         track->SetSelected(true);
         temp->Add(track);
      };
      if (effect.IsLinearEffect() && !isGenerator) {
         auto mixed = MixAndRender(saveTracks->Selected<const WaveTrack>(),
            Mixer::WarpOptions{ saveTracks->GetOwner() }, wxString{},
            effect.mFactory, effect.mProjectRate, floatSample, effect.mT0, t1);
         auto mixedWave = std::dynamic_pointer_cast<WaveTrack>(mixed);
         if (!mixedWave)
            Fail(ErrorCode::FAILED, "the preview could not be mixed");
         mixedWave->MoveTo(0);
         addTrack(mixedWave);
      }
      else if (noSelectedAudio) {
         // DoEffect gives a generator a new track when none is selected
         addTrack(effect.mFactory->Create());
      }
      else
         for (auto src : saveTracks->Selected<const WaveTrack>())
            addTrack(std::static_pointer_cast<WaveTrack>(
               src->Copy(effect.mT0, t1)));

      // New tracks start at time zero
      effect.mT1 -= effect.mT0;
      effect.mT0 = 0.0;
      effect.mTracks = temp;
      effect.CountWaveTracks();

      {
         using namespace BasicUI;
         auto progress = MakeProgress(effect.GetName(),
            XO("Preparing preview"), ProgressShowStop);
         effect.mProgress = progress.get();
         effect.mIsPreview = true;
         auto pAccess = std::make_shared<SimpleEffectSettingsAccess>(settings);
         pAccess->ModifySettings([&](EffectSettings &s) {
            auto pInstance =
               std::dynamic_pointer_cast<EffectInstanceEx>(effect.MakeInstance());
            success = pInstance && pInstance->Process(s);
            return nullptr;
         });
         effect.mProgress = nullptr;
      }
      // Some effects (Paulstretch) generate more than previewLen
      playEnd = std::min(effect.mT0 + previewLen, effect.mT1);
   }

   if (!success) {
      if (capture.Cancelled() || capture.Stopped())
         Fail(ErrorCode::CANCELLED, "preview cancelled");
      Fail(ErrorCode::FAILED, capture.HasMessage() ? capture.Message()
         : Translated(effect.GetName()) + ": the preview could not be rendered");
   }
   if (!(playEnd > 0))
      Fail(ErrorCode::NO_SELECTION, "nothing to preview");

   auto options = ProjectAudioIO::GetDefaultOptions(project);
   options.loopEnabled = false;
   const int token = audioIO->StartStream(MakeTransportTracks(*temp), 0.0,
      playEnd, playEnd, options);
   if (token <= 0)
      Fail(ErrorCode::FAILED, Translated(XO(
"Error opening sound device.\nTry changing the audio host, playback device and the project sample rate.")));
   Current() = PreviewSession{ std::move(temp), token, &project };
   EmitTransport("playing");
   json result = json::object();
   if (capture.HasMessage())
      result["message"] = capture.Message();
   return result;
}

json StopPreviewCmd(const json &)
{
   Finish();
   return json::object();
}

} // namespace

void StopPreview()
{
   Finish();
}

bool PreviewActive()
{
   return Current().has_value();
}

void RegisterPreviewCommands(ModuleRegistry &registry)
{
   // Not NeedsIdleAudio: a running preview is replaced; other streams are
   // refused inside (AUDIO_BUSY)
   registry.AddCommand("effects.preview", PreviewCmd, NeedsProject | LongRunning);
   registry.AddCommand("effects.stopPreview", StopPreviewCmd);
   registry.AddTickHandler(Tick);
   registry.AddProjectClosing([](AudacityProject &project) {
      if (Current() && Current()->project == &project)
         Finish();
   });
   registry.AddBeforeShutdown([] { Finish(); });
}

} // namespace effects
} // namespace aubridge
