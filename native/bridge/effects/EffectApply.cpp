/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectApply.cpp

  effects.apply / repeatLast / lastApplied and
  effects.noiseReduction.captureProfile (API.md §3.3).

  ApplyEffect() is a port of AudacityApplicationLogic::DoEffect
  (libraries/lib-audacity-application-logic/AudacityApplicationLogic.cpp,
  Audacity 3.7.9, the Audacity Team; not part of this build) plus what
  EffectUIHost::OnApply persisted (src/effects/EffectUI.cpp): it calls
  EffectBase::DoEffect with a bridge InstanceFinder that never shows UI,
  sets the generator duration and runs the post-Init fixups the wx dialogs
  used to do (effects.md §5.3-5.4).  The CommandManager's mLast* fields
  (lib-menus is not built) live here and reach the snapshot through a
  contributor.

  CaptureScope implements critic.md C18: while an effect runs, OK-only
  message boxes and error dialogs become the command's `message` instead
  of `dialog` events.

**********************************************************************/
#include "EffectsInternal.h"

#include <atomic>
#include <cmath>

#include "Edit.h"
#include "EngineThread.h"
#include "Events.h"
#include "Modules.h"
#include "Session.h"

#include "BasicUI.h"
#include "ConfigInterface.h"
#include "Effect.h"
#include "EffectAutomationParameters.h"
#include "EffectBase.h"
#include "EffectManager.h"
#include "PluginManager.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectHistory.h"
#include "ProjectRate.h"
#include "ViewInfo.h"
#include "WaveTrack.h"

namespace aubridge {
namespace effects {

// ---------------------------------------------------------------------------
// CaptureScope
// ---------------------------------------------------------------------------
struct CaptureScope::State {
   std::vector<std::string> messages;
   bool cancelled = false;
   bool stopped = false;
};

namespace {

void Note(CaptureScope::State &state, BasicUI::ProgressResult result)
{
   if (result == BasicUI::ProgressResult::Cancelled)
      state.cancelled = true;
   else if (result == BasicUI::ProgressResult::Stopped)
      state.stopped = true;
}

class CaptureProgress final : public BasicUI::ProgressDialog {
public:
   CaptureProgress(std::unique_ptr<BasicUI::ProgressDialog> inner,
      std::shared_ptr<CaptureScope::State> state)
      : mInner{ std::move(inner) }, mState{ std::move(state) } {}
   BasicUI::ProgressResult Poll(unsigned long long numerator,
      unsigned long long denominator,
      const TranslatableString &message) override
   {
      if (!mInner)
         return BasicUI::ProgressResult::Success;
      const auto result = mInner->Poll(numerator, denominator, message);
      Note(*mState, result);
      return result;
   }
   void SetMessage(const TranslatableString &message) override
   { if (mInner) mInner->SetMessage(message); }
   void SetDialogTitle(const TranslatableString &title) override
   { if (mInner) mInner->SetDialogTitle(title); }
   void Reinit() override { if (mInner) mInner->Reinit(); }
private:
   std::unique_ptr<BasicUI::ProgressDialog> mInner;
   std::shared_ptr<CaptureScope::State> mState;
};

class CaptureGenericProgress final : public BasicUI::GenericProgressDialog {
public:
   CaptureGenericProgress(std::unique_ptr<BasicUI::GenericProgressDialog> inner,
      std::shared_ptr<CaptureScope::State> state)
      : mInner{ std::move(inner) }, mState{ std::move(state) } {}
   BasicUI::ProgressResult Pulse() override
   {
      if (!mInner)
         return BasicUI::ProgressResult::Success;
      const auto result = mInner->Pulse();
      Note(*mState, result);
      return result;
   }
private:
   std::unique_ptr<BasicUI::GenericProgressDialog> mInner;
   std::shared_ptr<CaptureScope::State> mState;
};

//! Forwards everything to the previously installed services except, on
//! the engine thread while a CaptureScope is active, informational message
//! boxes and error dialogs (captured) and progress dialogs (observed).
//! One instance for the process lifetime: other threads (the audio thread's
//! CallAfter) may still hold it after a scope ended, so it is never deleted.
class CaptureServices final : public BasicUI::Services {
public:
   static CaptureServices &Instance()
   {
      static auto *instance = new CaptureServices;   // intentionally leaked
      return *instance;
   }

   //! The services installed before the outermost scope
   std::atomic<BasicUI::Services *> inner{ nullptr };
   //! The innermost scope's state (engine thread only)
   std::shared_ptr<CaptureScope::State> state;

   void DoCallAfter(const BasicUI::Action &action) override
   { if (auto p = inner.load()) p->DoCallAfter(action); }
   void DoYield() override { if (auto p = inner.load()) p->DoYield(); }
   void DoProcessIdle() override
   { if (auto p = inner.load()) p->DoProcessIdle(); }
   void DoShowErrorDialog(const BasicUI::WindowPlacement &placement,
      const TranslatableString &title, const TranslatableString &message,
      const ManualPageID &helpPage,
      const BasicUI::ErrorDialogOptions &options) override
   {
      if (Capturing()) {
         auto text = Translated(message);
         if (!options.log.empty())
            Events::Log(Events::LogLevel::Error,
               text + "\n" + ToUtf8(wxString(options.log)));
         Add(text);
      }
      else if (auto p = inner.load())
         p->DoShowErrorDialog(placement, title, message, helpPage, options);
   }
   BasicUI::MessageBoxResult DoMessageBox(const TranslatableString &message,
      BasicUI::MessageBoxOptions options) override
   {
      using namespace BasicUI;
      if (Capturing() && options.buttonStyle != Button::YesNo &&
          !options.cancelButton) {
         Add(Translated(message));
         return MessageBoxResult::Ok;
      }
      auto p = inner.load();
      return p ? p->DoMessageBox(message, std::move(options))
               : MessageBoxResult::Cancel;
   }
   std::unique_ptr<BasicUI::ProgressDialog> DoMakeProgress(
      const TranslatableString &title, const TranslatableString &message,
      unsigned flags, const TranslatableString &remaining) override
   {
      auto p = inner.load();
      auto dialog = p
         ? p->DoMakeProgress(title, message, flags, remaining) : nullptr;
      if (!Capturing())
         return dialog;
      return std::make_unique<CaptureProgress>(std::move(dialog), state);
   }
   std::unique_ptr<BasicUI::GenericProgressDialog> DoMakeGenericProgress(
      const BasicUI::WindowPlacement &placement,
      const TranslatableString &title, const TranslatableString &message,
      int style) override
   {
      auto p = inner.load();
      auto dialog = p
         ? p->DoMakeGenericProgress(placement, title, message, style) : nullptr;
      if (!Capturing())
         return dialog;
      return std::make_unique<CaptureGenericProgress>(std::move(dialog), state);
   }
   int DoMultiDialog(const TranslatableString &message,
      const TranslatableString &title, const TranslatableStrings &buttons,
      const ManualPageID &helpPage, const TranslatableString &boxMsg,
      bool log) override
   {
      auto p = inner.load();
      return p ? p->DoMultiDialog(message, title, buttons, helpPage, boxMsg, log)
               : -1;
   }
   bool DoOpenInDefaultBrowser(const wxString &url) override
   {
      auto p = inner.load();
      return p && p->DoOpenInDefaultBrowser(url);
   }
   std::unique_ptr<BasicUI::WindowPlacement> DoFindFocus() override
   {
      // EffectPreview-style code asserts a non-null placement
      if (auto p = inner.load())
         if (auto focus = p->DoFindFocus())
            return focus;
      return std::make_unique<BasicUI::WindowPlacement>();
   }
   void DoSetFocus(const BasicUI::WindowPlacement &focus) override
   { if (auto p = inner.load()) p->DoSetFocus(focus); }
   bool IsUsingRtlLayout() const override
   {
      auto p = inner.load();
      return p && p->IsUsingRtlLayout();
   }
   bool IsUiThread() const override
   {
      auto p = inner.load();
      return p ? p->IsUiThread() : EngineThread::IsCurrent();
   }

   bool Capturing() const { return EngineThread::IsCurrent() && state; }
   void Add(const std::string &text)
   {
      if (!text.empty() && state)
         state->messages.push_back(text);
   }

private:
   CaptureServices() = default;
};

} // namespace

CaptureScope::CaptureScope()
   : mState{ std::make_shared<State>() }
{
   auto &services = CaptureServices::Instance();
   auto previous = BasicUI::Get();
   mPreviousState = new std::shared_ptr<State>(services.state);
   if (previous != &services) {
      // outermost scope: interpose
      services.inner = previous;
      mPreviousServices = previous;
      BasicUI::Install(&services);
      mInstalled = true;
   }
   services.state = mState;
}

CaptureScope::~CaptureScope()
{
   auto &services = CaptureServices::Instance();
   auto previousState = static_cast<std::shared_ptr<State> *>(mPreviousState);
   services.state = *previousState;
   delete previousState;
   // (a Stop() may have uninstalled every service meanwhile)
   if (mInstalled && BasicUI::Get() == &services)
      BasicUI::Install(static_cast<BasicUI::Services *>(mPreviousServices));
}

bool CaptureScope::Cancelled() const { return mState->cancelled; }
bool CaptureScope::Stopped() const { return mState->stopped; }
bool CaptureScope::HasMessage() const { return !mState->messages.empty(); }

std::string CaptureScope::Message() const
{
   std::string result;
   for (auto &m : mState->messages) {
      if (!result.empty())
         result += "\n\n";
      result += m;
   }
   return result;
}

bool CaptureMessage(const std::string &text)
{
   auto &services = CaptureServices::Instance();
   if (!services.Capturing())
      return false;
   services.Add(text);
   return true;
}

// ---------------------------------------------------------------------------
// EffectContext
// ---------------------------------------------------------------------------
EffectContext::EffectContext(Effect &effect, AudacityProject &project)
   : mEffect{ effect }
{
   // The fields EffectBase::DoEffect sets before the dialog appears
   const auto &region = ViewInfo::Get(project).selectedRegion;
   mEffect.mFactory = &WaveTrackFactory::Get(project);
   mEffect.mProjectRate = ProjectRate::Get(project).GetRate();
   mEffect.SetTracks(&TrackList::Get(project));
   mEffect.mT0 = region.t0();
   mEffect.mT1 = region.t1();
   if (mEffect.mT1 > mEffect.mT0) {
      // DoEffect quantizes a time selection to the project rate
      const double q0 = QUANTIZED_TIME(mEffect.mT0, mEffect.mProjectRate);
      const double q1 = QUANTIZED_TIME(mEffect.mT1, mEffect.mProjectRate);
      mEffect.mT1 = mEffect.mT0 + (q1 - q0);
   }
   mEffect.CountWaveTracks();
}

EffectContext::~EffectContext()
{
   // Don't hold the project's track list
   mEffect.SetTracks(nullptr);
}

// ---------------------------------------------------------------------------
// Preconditions (port of the menu flags, CommonCommandFlags.cpp)
// ---------------------------------------------------------------------------
namespace {

bool HasAudioSelection(AudacityProject &project)
{
   return !ViewInfo::Get(project).selectedRegion.isPoint() &&
      !TrackList::Get(project).Selected<const WaveTrack>().empty();
}

void SelectAllAudio(AudacityProject &project)
{
   // SelectUtilities::DoSelectAllAudio
   auto &tracks = TrackList::Get(project);
   ViewInfo::Get(project).selectedRegion.setTimes(
      tracks.GetStartTime(), tracks.GetEndTime());
   for (auto t : tracks)
      t->SetSelected(false);
   for (auto t : tracks.Any<WaveTrack>())
      t->SetSelected(true);
   ModifyState(project, false);
}

} // namespace

void RequireAudioSelection(AudacityProject &project,
   const TranslatableString &name, bool noiseReduction)
{
   if (HasAudioSelection(project))
      return;
   // CommandManager::TryToMakeActionAllowed with /GUI/SelectAllOnNone: the
   // "WaveTracksExist" enabler produces TimeSelected | WaveTracksSelected
   const bool selectAllOnNone =
      gPrefs && gPrefs->ReadBool(wxT("/GUI/SelectAllOnNone"), false);
   if (selectAllOnNone &&
       !TrackList::Get(project).Any<const WaveTrack>().empty()) {
      SelectAllAudio(project);
      if (HasAudioSelection(project))
         return;
   }
   const bool timeMissing = ViewInfo::Get(project).selectedRegion.isPoint();
   TranslatableString message;
   if (noiseReduction && timeMissing)
      message = XO(
"Select the audio for %s to use.\n\n1. Select audio that represents noise and use %s to get your 'noise profile'.\n\n2. When you have got your noise profile, select the audio you want to change\nand use %s to change that audio.")
         .Format(name, name, name);
   else if (timeMissing)
      message = XO(
"Select the audio for %s to use (for example, Ctrl + A to Select All) then try again.")
         .Format(name);
   else
      message = XO(
"You must first select some audio to perform this action.\n(Selecting other kinds of track won't work.)");
   Fail(ErrorCode::NO_SELECTION, Translated(message));
}

void RequireAudioSelection(AudacityProject &project)
{
   RequireAudioSelection(project, XO("this effect"), false);
}

namespace {

void RequireEffectPreconditions(AudacityProject &project, const Loaded &fx)
{
   // PluginMenus.cpp: Generate and Tools need AudioIONotBusy only; Effect
   // and Analyze also TimeSelected | WaveTracksSelected
   if (fx.type == EffectTypeProcess || fx.type == EffectTypeAnalyze)
      RequireAudioSelection(project, PluginManager::Get().GetName(fx.id),
         fx.special == Special::NoiseReduction);
}

// ---------------------------------------------------------------------------
// Last effects (CommandManager::mLast*)
// ---------------------------------------------------------------------------
struct LastEffects {
   PluginID generator, effect, analyzer, tool, any;
};

LastEffects &Last()
{
   static LastEffects last;
   return last;
}

void RememberLast(EffectType type, const PluginID &id)
{
   auto &last = Last();
   switch (type) {
   case EffectTypeGenerate: last.generator = id; break;
   case EffectTypeProcess: last.effect = id; break;
   case EffectTypeAnalyze: last.analyzer = id; break;
   case EffectTypeTool: last.tool = id; break;
   default: return;
   }
   last.any = id;
}

json LastJson(const PluginID &id)
{
   if (id.empty())
      return nullptr;
   auto &pm = PluginManager::Get();
   auto plug = pm.GetPlugin(id);
   if (!plug || !plug->IsEnabled())
      return nullptr;
   return json{ { "id", ToUtf8(id) }, { "name", Translated(pm.GetName(id)) } };
}

// ---------------------------------------------------------------------------
// Apply
// ---------------------------------------------------------------------------
struct ApplyOutcome {
   bool applied = false;
   std::string message;
};

//! Auto Duck processes nothing (and says nothing) when the selection is not
//! longer than both outer fades (AutoDuckBase::Process)
std::optional<std::string> CheckAutoDuck(AudacityProject &project,
   const Loaded &fx)
{
   CommandParameters eap{ GetParameterString(fx) };
   double down = 0.5, up = 0.5;
   eap.Read(wxT("OuterFadeDownLen"), &down, 0.5);
   eap.Read(wxT("OuterFadeUpLen"), &up, 0.5);
   const auto &region = ViewInfo::Get(project).selectedRegion;
   if (region.t1() - region.t0() <= down + up)
      return std::string(
         "The selection must be longer than the outer fade down and up lengths together.");
   return std::nullopt;
}

ApplyOutcome ApplyEffect(AudacityProject &project, const Loaded &fx,
   unsigned flags, std::optional<double> duration, bool noiseProfile = false)
{
   auto &pm = PluginManager::Get();
   auto &em = EffectManager::Get();
   auto &effect = *fx.effect;
   auto &settings = *fx.settings;
   auto &tracks = TrackList::Get(project);
   auto &factory = WaveTrackFactory::Get(project);
   const double rate = ProjectRate::Get(project).GetRate();
   auto &region = ViewInfo::Get(project).selectedRegion;
   const auto name = pm.GetName(fx.id);

   if (fx.special == Special::AutoDuck)
      if (auto why = CheckAutoDuck(project, fx))
         return { false, *why };
   if (fx.special == Special::NoiseReduction && !noiseProfile) {
      if (!NoiseReductionHasProfile(*fx.plugin))
         return { false, Translated(XO(
"Select a few seconds of just noise so Audacity knows what to filter out,\nthen click Get Noise Profile:")) };
      // Step 2 of the dialog: reduce
      NoiseReductionSetProfileMode(*fx.plugin, false);
   }

   // What the dialog's OK persisted (EffectUIHost::OnApply,
   // TransferDataFromWindow): the settings and the generator duration
   effect.GetDefinition().SaveUserPreset(CurrentSettingsGroup(), settings);
   if (fx.type == EffectTypeGenerate && duration)
      SetConfig(effect.GetDefinition(), PluginSettings::Private,
         CurrentSettingsGroup(), EffectSettingsExtra::DurationKey(), *duration);

   std::optional<std::string> refusal;
   EffectPlugin::InstanceFinder finder =
      [&](EffectSettings &s) -> std::optional<EffectPlugin::InstancePointer> {
      // An explicit duration replaces the selection length DoEffect set
      if (fx.type == EffectTypeGenerate && duration)
         s.extra.SetDuration(*duration);
      auto result = EffectBase::FindInstance(effect);   // MakeInstance + Init
      if (!result)
         return result;
      if (auto why = PostInitFixups(effect, s)) {
         refusal = std::move(why);
         return std::nullopt;
      }
      return result;
   };

   CaptureScope capture;
   bool ok = false;
   em.SetSkipStateFlag(false);
   auto pAccess = std::make_shared<SimpleEffectSettingsAccess>(settings);
   RunEditSelf(project, [&] {
      pAccess->ModifySettings([&](EffectSettings &s) {
         ok = effect.DoEffect(s, finder, rate, &tracks, &factory, region,
            flags, pAccess);
         return nullptr;
      });
      if (!ok) {
         if (capture.Cancelled() || capture.Stopped())
            // rolled back by RunEditSelf
            Fail(ErrorCode::CANCELLED, "cancelled");
         return false;
      }
      unsigned effective = flags;
      if (em.GetSkipStateFlag())
         effective |= EffectManager::kSkipState;
      if (effective & EffectManager::kSkipState)
         return false;
      ProjectHistory::Get(project).PushState(
         XO("Applied effect: %s").Format(name), name);
      return true;
   });

   if (!ok) {
      if (refusal)
         return { false, *refusal };
      if (capture.HasMessage())
         return { false, capture.Message() };
      Fail(ErrorCode::FAILED, Translated(name) + ": the effect failed (nothing was changed)");
   }
   if (!(flags & EffectManager::kDontRepeatLast))
      RememberLast(fx.type, fx.id);
   return { true, capture.Message() };
}

json OutcomeJson(const ApplyOutcome &outcome)
{
   json result{ { "applied", outcome.applied } };
   if (!outcome.message.empty())
      result["message"] = outcome.message;
   return result;
}

json ApplyCmd(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto fx = LoadEffect(ArgString(args, "id"));
   // effects.setParams semantics first (INVALID_ARGS: nothing changed)
   ApplyParamArgs(fx, args);
   std::optional<double> duration;
   if (auto d = OptDouble(args, "duration"))
      duration = ValidateDuration(*d);
   RequireEffectPreconditions(project, fx);
   return OutcomeJson(
      ApplyEffect(project, fx, EffectManager::kConfigured, duration));
}

json RepeatLastCmd(const json &)
{
   // Effect ▸ Repeat Last Effect (OnRepeatLastEffect, kConfigured)
   auto &project = Session::Get().RequireProject();
   const auto id = Last().effect;
   if (id.empty())
      Fail(ErrorCode::NOT_FOUND, "no effect was applied yet");
   auto fx = LoadEffect(ToUtf8(id));
   RequireEffectPreconditions(project, fx);
   return OutcomeJson(ApplyEffect(project, fx, EffectManager::kConfigured, {}));
}

json LastAppliedCmd(const json &)
{
   auto last = LastJson(Last().any);
   return last.is_null() ? json::object() : last;
}

json CaptureProfileCmd(const json &)
{
   // EffectNoiseReduction::Dialog::OnGetProfile: step 1 runs the effect in
   // profile mode; the tracks are not changed and it is not repeatable
   auto &project = Session::Get().RequireProject();
   const auto nrId = NoiseReductionId();
   if (nrId.empty())
      Fail(ErrorCode::NOT_FOUND, "Noise Reduction is not available");
   auto fx = LoadEffect(ToUtf8(nrId));
   RequireEffectPreconditions(project, fx);
   NoiseReductionSetProfileMode(*fx.plugin, true);
   struct Reset {
      EffectPlugin &plugin;
      ~Reset() { NoiseReductionSetProfileMode(plugin, false); }
   } reset{ *fx.plugin };
   auto outcome = ApplyEffect(project, fx, EffectManager::kConfigured |
      EffectManager::kSkipState | EffectManager::kDontRepeatLast, {}, true);
   if (!outcome.applied || !NoiseReductionHasProfile(*fx.plugin))
      Fail(ErrorCode::FAILED, outcome.message.empty()
         ? std::string("the noise profile could not be captured")
         : outcome.message);
   json result = json::object();
   if (!outcome.message.empty())
      result["message"] = outcome.message;
   return result;
}

} // namespace

// ---------------------------------------------------------------------------
// Amplify helpers for describe / defaults
// ---------------------------------------------------------------------------
std::optional<double> SelectionPeak(const Loaded &fx)
{
   if (fx.special != Special::Amplify)
      return std::nullopt;
   auto project = Session::Get().Project();
   if (!project || !HasAudioSelection(*project))
      return std::nullopt;
   try {
      CaptureScope capture;
      EffectContext context{ *fx.effect, *project };
      if (!EffectBase::FindInstance(*fx.effect))
         return std::nullopt;
      return AmplifyPeak(*fx.plugin);
   }
   catch (...) {
      return std::nullopt;
   }
}

bool LoadAmplifyDefaults(const Loaded &fx)
{
   // AmplifyBase::LoadFactoryDefaults: ratio = 1/peak of the selection
   // (needs the effect context, effects.md §4.5)
   auto project = Session::Get().Project();
   if (!project || !HasAudioSelection(*project))
      return false;
   CaptureScope capture;
   EffectContext context{ *fx.effect, *project };
   return fx.plugin->GetDefinition().LoadFactoryDefaults(*fx.settings)
      .has_value();
}

void RegisterApplyCommands(ModuleRegistry &registry)
{
   const unsigned apply = NeedsProject | NeedsIdleAudio | Mutates | LongRunning;
   registry.AddCommand("effects.apply", ApplyCmd, apply);
   registry.AddCommand("effects.repeatLast", RepeatLastCmd, apply);
   registry.AddCommand("effects.lastApplied", LastAppliedCmd);
   registry.AddCommand("effects.noiseReduction.captureProfile",
      CaptureProfileCmd, NeedsProject | NeedsIdleAudio | LongRunning);

   // A new project has a new CommandManager (no repeatable effects)
   registry.AddProjectOpened([](AudacityProject &) { Last() = {}; });
   registry.AddBeforeShutdown([] { Last() = {}; });
   registry.AddSnapshotContributor([](json &snapshot, AudacityProject &) {
      auto &last = Last();
      snapshot["lastEffect"] = LastJson(last.effect);
      snapshot["lastGenerator"] = LastJson(last.generator);
      snapshot["lastAnalyzer"] = LastJson(last.analyzer);
      snapshot["lastTool"] = LastJson(last.tool);
   });
}

} // namespace effects
} // namespace aubridge
