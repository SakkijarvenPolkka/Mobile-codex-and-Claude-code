/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  BuiltinEffects.cpp

  Registration of the Audacity 3.7.9 built-in effects without src/.

  In 3.7.9 every BuiltinEffectsModule::Registration lives in src/effects
  and registers the wxWidgets UI subclasses (EffectAmplify, ...).  The
  bridge registers the UI-less lib-builtin-effects "Base" classes instead,
  with small subclasses where the Base class is abstract or incomplete
  (BassTreble/Echo/Reverb: MakeInstance; Tone/Chirp and the two EQs:
  symbols), plus Compressor and Limiter, which exist only in src/.

  The symbols (and therefore the PluginIDs, `Effect_Audacity_Audacity_
  <Symbol>_Built-in Effect: <Symbol>`) are exactly the desktop ones, so
  presets, macros and realtime stacks in .aup3 files stay compatible.

  Ported pieces keep their attribution:
   * CompressorFx: src/effects/Compressor.{h,cpp}, CompressorEditor.h
     (Matthieu Hodgkinson)
   * LimiterFx: src/effects/Limiter.{h,cpp}, LimiterEditor.h
     (Matthieu Hodgkinson)
   * DrpDummyOutputs: src/effects/DynamicRangeProcessorDummyOutputs.h
     (Matthieu Hodgkinson)
   * ToneFx/ChirpFx: src/effects/ToneGen.cpp (Steve Jolly)
   * MakeInstance overrides: src/effects/{Amplify,BassTreble,Echo,
     Reverb}.cpp (Dominic Mazzoni, Steve Daulton, Rob Sykes, ...)
   * the hooks: src/effects/EffectUI.cpp, src/effects/nyquist/Nyquist.cpp

**********************************************************************/
#include "EffectsInternal.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>

#include "Events.h"
#include "Session.h"

#include "ConfigInterface.h"
#include "EffectManager.h"
#include "LoadEffects.h"
#include "ModuleManager.h"
#include "PerTrackEffect.h"
#include "PluginManager.h"
#include "RealtimeEffectState.h"
#include "ShuttleAutomation.h"

#include "AmplifyBase.h"
#include "AutoDuckBase.h"
#include "BassTrebleBase.h"
#include "ChangePitchBase.h"
#include "ChangeSpeedBase.h"
#include "ChangeTempoBase.h"
#include "ClickRemovalBase.h"
#include "CompressorInstance.h"
#include "DistortionBase.h"
#include "DtmfBase.h"
#include "EchoBase.h"
#include "EqualizationBase.h"
#include "Fade.h"
#include "FindClippingBase.h"
#include "Invert.h"
#include "LegacyCompressorBase.h"
#include "LoudnessBase.h"
#include "NoiseBase.h"
#include "NoiseReductionBase.h"
#include "NormalizeBase.h"
#include "PaulstretchBase.h"
#include "PhaserBase.h"
#include "Repair.h"
#include "RepeatBase.h"
#include "ReverbBase.h"
#include "Reverse.h"
#include "ScienFilterBase.h"
#include "SilenceBase.h"
#include "StereoToMono.h"
#include "TimeScaleBase.h"
#include "ToneGenBase.h"
#include "TruncSilenceBase.h"
#include "WahWahBase.h"

#include "DynamicRangeProcessorTypes.h"
#include "DynamicRangeProcessorUtils.h"

#include "NyquistBase.h"
#include "WaveChannelViewConstants.h"

namespace aubridge {
namespace effects {
namespace {

// ---------------------------------------------------------------------------
// 1. Base classes that are abstract in 3.7.9 (no MakeInstance)
// ---------------------------------------------------------------------------
struct BassTrebleFx final : BassTrebleBase {
   // src/effects/BassTreble.cpp
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<BassTrebleBase::Instance>(*this); }
};

struct EchoFx final : EchoBase {
   // src/effects/Echo.cpp
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<EchoBase::Instance>(*this); }
};

struct ReverbFx final : ReverbBase {
   // src/effects/Reverb.cpp
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<ReverbBase::Instance>(*this); }
};

// ---------------------------------------------------------------------------
// 2. Base classes without Symbol/GetSymbol()/default constructor
// ---------------------------------------------------------------------------
struct ToneFx final : ToneGenBase {
   static const ComponentInterfaceSymbol Symbol;
   ToneFx() : ToneGenBase{ false } {}
   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
   TranslatableString GetDescription() const override
   { return XO("Generates a constant frequency tone of one of four types"); }
   ManualPageID ManualPage() const override { return L"Tone"; }
};
const ComponentInterfaceSymbol ToneFx::Symbol{ XO("Tone") };

struct ChirpFx final : ToneGenBase {
   static const ComponentInterfaceSymbol Symbol;
   ChirpFx() : ToneGenBase{ true } {}
   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
   TranslatableString GetDescription() const override
   {
      return XO(
         "Generates an ascending or descending tone of one of four types");
   }
   ManualPageID ManualPage() const override { return L"Chirp"; }
};
const ComponentInterfaceSymbol ChirpFx::Symbol{ XO("Chirp") };

// src/effects/Equalization.cpp: the internal names "Filter Curve" and
// "Graphic EQ" are kept for config and macro compatibility
struct FilterCurveEqFx final : EqualizationBase {
   static const ComponentInterfaceSymbol Symbol;
   FilterCurveEqFx() : EqualizationBase{ kEqOptionCurve } {}
   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
};
const ComponentInterfaceSymbol FilterCurveEqFx::Symbol{
   wxT("Filter Curve"), XO("Filter Curve EQ") };

struct GraphicEqFx final : EqualizationBase {
   static const ComponentInterfaceSymbol Symbol;
   GraphicEqFx() : EqualizationBase{ kEqOptionGraphic } {}
   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
};
const ComponentInterfaceSymbol GraphicEqFx::Symbol{
   wxT("Graphic EQ"), XO("Graphic EQ") };

// ---------------------------------------------------------------------------
// 3. Accessors for hidden state of stateful effects
// ---------------------------------------------------------------------------
struct AmplifyFx final : AmplifyBase {
   // src/effects/Amplify.cpp: "Cheat with const_cast to return an object
   // that calls through to non-const methods of a stateful effect."
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<Instance>(const_cast<AmplifyFx &>(*this)); }
   //! Valid after Init()
   double Peak() const { return mPeak; }
   double Ratio() const { return mRatio; }
   bool CanClip() const { return mCanClip; }
};

struct ScienFilterFx final : ScienFilterBase {
   //! CalcFilter() allocates mpBiquad and must run after Init(), which sets
   //! mNyquist from the track rate; only the wx dialog (ScienFilter.cpp)
   //! and the LoadSettings PostSet call it
   void Recalc() { mOrderIndex = mOrder - 1; CalcFilter(); }
};

struct NoiseReductionFx final : NoiseReductionBase {
   Settings &NrSettings() { return *mSettings; }
   bool HasProfile() const { return mStatistics != nullptr; }
};

// ---------------------------------------------------------------------------
// 4. Compressor / Limiter: re-implementation of src/effects/Compressor.cpp
//    and Limiter.cpp without the editor
// ---------------------------------------------------------------------------

//! src/effects/DynamicRangeProcessorDummyOutputs.h: only distinguishes
//! realtime from destructive CompressorInstances
struct DrpDummyOutputs final : EffectOutputs {
   std::unique_ptr<EffectOutputs> Clone() const override
   { return std::make_unique<DrpDummyOutputs>(); }
   void Assign(EffectOutputs &&) override {}
};

using CompressorParameter = EffectParameter<CompressorSettings, double, double>;
using LimiterParameter = EffectParameter<LimiterSettings, double, double>;

struct CompressorFx final
   : EffectWithSettings<CompressorSettings, PerTrackEffect>
{
   static const ComponentInterfaceSymbol Symbol;
   // Keys of src/effects/CompressorEditor.h.  3.7.9 declares min/max there
   // pre-multiplied by 1/dbStep (= 10) because its editor divides them by
   // `scale` again; the real ranges are declared here (same keys, so stored
   // values stay compatible).
   static constexpr CompressorParameter thresholdDb{
      &CompressorSettings::thresholdDb, L"thresholdDb",
      compressorThresholdDbDefault, -60.0, 0.0, 10.0 };
   static constexpr CompressorParameter makeupGainDb{
      &CompressorSettings::makeupGainDb, L"makeupGainDb",
      compressorMakeupGainDbDefault, -30.0, 30.0, 10.0 };
   static constexpr CompressorParameter kneeWidthDb{
      &CompressorSettings::kneeWidthDb, L"kneeWidthDb",
      compressorKneeWidthDbDefault, 0.0, 30.0, 10.0 };
   static constexpr CompressorParameter compressionRatio{
      &CompressorSettings::compressionRatio, L"compressionRatio",
      compressorCompressionRatioDefault, 1.0, 100.0, 1.0 };
   static constexpr CompressorParameter lookaheadMs{
      &CompressorSettings::lookaheadMs, L"lookaheadMs",
      compressorLookaheadMsDefault, 0.0, compressorMaxLookaheadMs, 1.0 };
   static constexpr CompressorParameter attackMs{
      &CompressorSettings::attackMs, L"attackMs",
      compressorAttackMsDefault, 0.0, 200.0, 1.0 };
   static constexpr CompressorParameter releaseMs{
      &CompressorSettings::releaseMs, L"releaseMs",
      compressorReleaseMsDefault, 0.0, 1000.0, 1.0 };
   static constexpr CompressorParameter showInput{
      &CompressorSettings::showInput, L"showInput", showInputDefault,
      0.0, 1.0, 1.0 };
   static constexpr CompressorParameter showOutput{
      &CompressorSettings::showOutput, L"showOutput", showOutputDefault,
      0.0, 1.0, 1.0 };
   static constexpr CompressorParameter showActual{
      &CompressorSettings::showActual, L"showActual", showActualDefault,
      0.0, 1.0, 1.0 };
   static constexpr CompressorParameter showTarget{
      &CompressorSettings::showTarget, L"showTarget", showTargetDefault,
      0.0, 1.0, 1.0 };

   CompressorFx() { SetLinearEffectFlag(false); }

   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
   TranslatableString GetDescription() const override
   {
      return XO(
         "Reduces \"dynamic range\", or differences between loud and quiet parts.");
   }
   ManualPageID ManualPage() const override { return L""; }
   EffectType GetType() const override { return EffectTypeProcess; }
   RealtimeSince RealtimeSupport() const override
   { return RealtimeSince::Always; }

   RegistryPaths GetFactoryPresets() const override
   {
      const auto presets = DynamicRangeProcessorUtils::GetCompressorPresets();
      RegistryPaths paths(presets.size());
      std::transform(presets.begin(), presets.end(), paths.begin(),
         [](const auto &preset) {
            return RegistryPath{ preset.name.Translation() };
         });
      return paths;
   }
   OptionalMessage
   LoadFactoryPreset(int id, EffectSettings &settings) const override
   {
      const auto presets = DynamicRangeProcessorUtils::GetCompressorPresets();
      if (id < 0 || id >= static_cast<int>(presets.size()))
         return {};
      GetSettings(settings) = presets[id].preset;
      return { nullptr };
   }
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<CompressorInstance>(*this); }
   std::unique_ptr<EffectOutputs> MakeOutputs() const override
   { return std::make_unique<DrpDummyOutputs>(); }
   bool CheckWhetherSkipEffect(const EffectSettings &settings) const override
   {
      const auto &s = GetSettings(settings);
      // Also look-ahead, as this adds delay when used on a real-time input
      return s.compressionRatio == 1 && s.makeupGainDb == 0 &&
         s.lookaheadMs == 0;
   }
   const EffectParameterMethods &Parameters() const override
   {
      static CapturedParameters<CompressorFx, thresholdDb, makeupGainDb,
         kneeWidthDb, compressionRatio, lookaheadMs, attackMs, releaseMs,
         showInput, showOutput, showActual, showTarget>
         parameters;
      return parameters;
   }
};
const ComponentInterfaceSymbol CompressorFx::Symbol{ XO("Compressor") };

struct LimiterFx final : EffectWithSettings<LimiterSettings, PerTrackEffect> {
   static const ComponentInterfaceSymbol Symbol;
   // Keys of src/effects/LimiterEditor.h, real ranges (see CompressorFx)
   static constexpr LimiterParameter thresholdDb{
      &LimiterSettings::thresholdDb, L"thresholdDb",
      limiterThresholdDbDefault, -30.0, 0.0, 10.0 };
   static constexpr LimiterParameter makeupTargetDb{
      &LimiterSettings::makeupTargetDb, L"makeupTargetDb",
      limiterMakeupTargetDbDefault, -30.0, 0.0, 10.0 };
   static constexpr LimiterParameter kneeWidthDb{
      &LimiterSettings::kneeWidthDb, L"kneeWidthDb",
      limiterKneeWidthDbDefault, 0.0, 10.0, 10.0 };
   static constexpr LimiterParameter lookaheadMs{
      &LimiterSettings::lookaheadMs, L"lookaheadMs",
      limiterLookaheadMsDefault, 0.0, limiterMaxLookaheadMs, 1.0 };
   static constexpr LimiterParameter releaseMs{
      &LimiterSettings::releaseMs, L"releaseMs",
      limiterReleaseMsDefault, 0.0, 1000.0, 1.0 };
   static constexpr LimiterParameter showInput{
      &LimiterSettings::showInput, L"showInput", showInputDefault,
      0.0, 1.0, 1.0 };
   static constexpr LimiterParameter showOutput{
      &LimiterSettings::showOutput, L"showOutput", showOutputDefault,
      0.0, 1.0, 1.0 };
   static constexpr LimiterParameter showActual{
      &LimiterSettings::showActual, L"showActual", showActualDefault,
      0.0, 1.0, 1.0 };
   static constexpr LimiterParameter showTarget{
      &LimiterSettings::showTarget, L"showTarget", showTargetDefault,
      0.0, 1.0, 1.0 };

   LimiterFx() { SetLinearEffectFlag(false); }

   ComponentInterfaceSymbol GetSymbol() const override { return Symbol; }
   TranslatableString GetDescription() const override
   { return XO("Augments loudness while minimizing distortion."); }
   ManualPageID ManualPage() const override { return L""; }
   EffectType GetType() const override { return EffectTypeProcess; }
   RealtimeSince RealtimeSupport() const override
   { return RealtimeSince::Always; }

   RegistryPaths GetFactoryPresets() const override
   {
      const auto presets = DynamicRangeProcessorUtils::GetLimiterPresets();
      RegistryPaths paths(presets.size());
      std::transform(presets.begin(), presets.end(), paths.begin(),
         [](const auto &preset) {
            return RegistryPath{ preset.name.Translation() };
         });
      return paths;
   }
   OptionalMessage
   LoadFactoryPreset(int id, EffectSettings &settings) const override
   {
      const auto presets = DynamicRangeProcessorUtils::GetLimiterPresets();
      if (id < 0 || id >= static_cast<int>(presets.size()))
         return {};
      GetSettings(settings) = presets[id].preset;
      return { nullptr };
   }
   std::shared_ptr<EffectInstance> MakeInstance() const override
   { return std::make_shared<CompressorInstance>(*this); }
   std::unique_ptr<EffectOutputs> MakeOutputs() const override
   { return std::make_unique<DrpDummyOutputs>(); }
   bool CheckWhetherSkipEffect(const EffectSettings &) const override
   { return false; }
   const EffectParameterMethods &Parameters() const override
   {
      static CapturedParameters<LimiterFx, thresholdDb, makeupTargetDb,
         kneeWidthDb, lookaheadMs, releaseMs, showInput, showOutput,
         showActual, showTarget>
         parameters;
      return parameters;
   }
};
const ComponentInterfaceSymbol LimiterFx::Symbol{ XO("Limiter") };

} // namespace

// ---------------------------------------------------------------------------
// Registration (before PluginManager::Initialize; once per process: the
// registry of BuiltinEffectsModule is a static vector and the provider
// asserts when entries arrive after its first Initialize())
// ---------------------------------------------------------------------------
void RegisterBuiltinEffects()
{
   using M = BuiltinEffectsModule;
   // Effect menu (EffectTypeProcess)
   static M::Registration<AmplifyFx> r01;
   static M::Registration<AutoDuckBase> r02;
   static M::Registration<BassTrebleFx> r03;
   static M::Registration<ChangePitchBase> r04;
   static M::Registration<ChangeSpeedBase> r05;
   static M::Registration<ChangeTempoBase> r06;
   static M::Registration<ClickRemovalBase> r07;
   static M::Registration<CompressorFx> r08;
   static M::Registration<DistortionBase> r09;
   static M::Registration<EchoFx> r10;
   static M::Registration<FilterCurveEqFx> r11;
   static M::Registration<GraphicEqFx> r12;
   static M::Registration<FadeIn> r13;
   static M::Registration<FadeOut> r14;
   static M::Registration<Invert> r15;
   static M::Registration<LegacyCompressorBase> r16;
   static M::Registration<LimiterFx> r17;
   static M::Registration<LoudnessBase> r18;
   static M::Registration<NoiseReductionFx> r19;
   static M::Registration<NormalizeBase> r20;
   static M::Registration<PaulstretchBase> r21;
   static M::Registration<PhaserBase> r22;
   static M::Registration<Repair> r23;
   static M::Registration<RepeatBase> r24;
   static M::Registration<ReverbFx> r25;
   static M::Registration<Reverse> r26;
   static M::Registration<ScienFilterFx> r27;
   static M::Registration<StereoToMono> r28;   // EffectTypeHidden
   static M::Registration<TimeScaleBase> r29;
   static M::Registration<TruncSilenceBase> r30;
   static M::Registration<WahWahBase> r31;
   // Generate menu
   static M::Registration<ChirpFx> g01;
   static M::Registration<DtmfBase> g02;
   static M::Registration<NoiseBase> g03;
   static M::Registration<SilenceBase> g04;
   static M::Registration<ToneFx> g05;
   // Analyze menu
   static M::Registration<FindClippingBase> a01;
}

// ---------------------------------------------------------------------------
// Library hooks that src/ installs on desktop
// ---------------------------------------------------------------------------
void InstallLibraryHooks()
{
   // Intentionally leaked: they must outlive every effect object and must
   // not run their destructors after the hooks' libraries at process exit
   static std::once_flag once;
   std::call_once(once, [] {
      // src/effects/EffectUI.cpp: realtime effect states (also needed to
      // load projects that contain realtime effect stacks)
      new RealtimeEffectState::EffectFactory::Scope{
         &EffectManager::GetInstanceFactory };
      // src/effects/nyquist/Nyquist.cpp: without it every Nyquist effect
      // "could not be loaded"
      new NyquistBase::GetEffectHook::Scope{
         [](const wxString &path) {
            return std::make_unique<NyquistBase>(path);
         } };
      // Spectral Nyquist effects require a spectrogram view.  The bridge has
      // no per-track view state (Kotlin draws), so every track counts as one;
      // the effect still needs a frequency selection and reports that.
      new NyquistBase::GetDisplaysHook::Scope{
         [](const WaveTrack *) {
            return std::vector<WaveChannelSubViewType>{
               WaveChannelSubViewType{ WaveChannelViewConstants::Spectrum, {} } };
         } };
      // The Debug window of the Nyquist Prompt: part of the command result
      new NyquistBase::ShowDebugOutputHook::Scope{
         [](const TranslatableString &title, const TranslatableString &message) {
            const auto text = Translated(message);
            if (text.empty())
               return;
            if (!CaptureMessage(text))
               Events::Log(Events::LogLevel::Info,
                  Translated(title) + ": " + text);
         } };
   });
}

void RegisterNewPlugins()
{
   auto &pm = PluginManager::Get();
   bool changed = false;
   try {
      for (auto &[path, providers] : pm.CheckPluginUpdates()) {
         // Only plug-in files (the built-in provider lists its own effects,
         // which are registered already)
         if (path.StartsWith(BUILTIN_EFFECT_PREFIX))
            continue;
         for (auto &providerId : providers) {
            TranslatableString errMsg;
            if (ModuleManager::Get().RegisterEffectPlugin(providerId, path, errMsg))
               changed = true;
            else if (!errMsg.empty())
               Events::Log(Events::LogLevel::Warning,
                  "plug-in " + ToUtf8(path) + ": " + Translated(errMsg));
         }
      }
   }
   catch (const std::exception &e) {
      Events::Log(Events::LogLevel::Warning,
         std::string("plug-in scan failed: ") + e.what());
   }
   if (changed)
      pm.Save();
}

void UnloadEffects()
{
   // EffectManager caches EffectPlugin pointers owned by PluginManager's
   // loaded interfaces; PluginManager::Terminate() destroys those objects,
   // so a later Start() in the same process would use dangling pointers.
   // UnregisterEffect() drops both caches; the registry file is not saved
   // afterwards, so nothing persistent changes.
   auto &pm = PluginManager::Get();
   std::vector<PluginID> ids;
   for (auto &plugin : pm.AllPlugins())
      if (plugin.GetPluginType() == PluginTypeEffect)
         ids.push_back(plugin.GetID());
   auto &em = EffectManager::Get();
   for (auto &id : ids)
      em.UnregisterEffect(id);
}

// ---------------------------------------------------------------------------
// Special effects
// ---------------------------------------------------------------------------
Special SpecialOf(const EffectPlugin &effect)
{
   const auto *p = &effect;
   if (dynamic_cast<const NoiseReductionFx *>(p))
      return Special::NoiseReduction;
   if (dynamic_cast<const FilterCurveEqFx *>(p))
      return Special::FilterCurveEq;
   if (dynamic_cast<const GraphicEqFx *>(p))
      return Special::GraphicEq;
   if (dynamic_cast<const AutoDuckBase *>(p))
      return Special::AutoDuck;
   if (dynamic_cast<const AmplifyFx *>(p))
      return Special::Amplify;
   if (dynamic_cast<const ScienFilterFx *>(p))
      return Special::ScienFilter;
   if (dynamic_cast<const DtmfBase *>(p))
      return Special::Dtmf;
   if (dynamic_cast<const PhaserBase *>(p))
      return Special::Phaser;
   if (dynamic_cast<const NyquistBase *>(p))
      return Special::Nyquist;
   return Special::None;
}

std::string SpecialName(Special special)
{
   switch (special) {
   case Special::NoiseReduction: return "noiseReduction";
   case Special::FilterCurveEq: return "equalization";
   case Special::GraphicEq: return "graphicEq";
   case Special::AutoDuck: return "autoDuck";
   default: return {};
   }
}

std::string SpecialNameForPath(const wxString &path)
{
   const wxString prefix = BUILTIN_EFFECT_PREFIX;
   if (!path.StartsWith(prefix))
      return {};
   const auto symbol = path.Mid(prefix.length());
   if (symbol == NoiseReductionBase::Symbol.Internal())
      return SpecialName(Special::NoiseReduction);
   if (symbol == FilterCurveEqFx::Symbol.Internal())
      return SpecialName(Special::FilterCurveEq);
   if (symbol == GraphicEqFx::Symbol.Internal())
      return SpecialName(Special::GraphicEq);
   if (symbol == AutoDuckBase::Symbol.Internal())
      return SpecialName(Special::AutoDuck);
   return {};
}

std::optional<std::string> PostInitFixups(EffectPlugin &effect,
   EffectSettings &settings)
{
   if (auto pFilter = dynamic_cast<ScienFilterFx *>(&effect))
      // Without it: SIGSEGV in Biquad::Reset from ProcessInitialize
      pFilter->Recalc();
   else if (auto pDtmf = dynamic_cast<DtmfBase *>(&effect)) {
      // Tone and silence lengths depend on the final duration
      (void)pDtmf;
      DtmfBase::GetSettings(settings).Recalculate(settings);
   }
   else if (auto pPhaser = dynamic_cast<PhaserBase *>(&effect)) {
      // PostSet of the LoadSettings path: "must be even"
      (void)pPhaser;
      PhaserBase::GetSettings(settings).mStages &= ~1;
   }
   else if (auto pAmplify = dynamic_cast<AmplifyFx *>(&effect)) {
      // The desktop dialog disables Apply when the result would clip and
      // clipping is not allowed (src/effects/Amplify.cpp); ProcessBlock
      // does not clamp
      const double peak = pAmplify->Peak();
      if (!pAmplify->CanClip() && peak > 0.0 &&
          pAmplify->Ratio() * peak > 1.0 + 1e-6)
         return Translated(XO(
            "Amplification would clip the audio. Allow clipping or reduce the amplification."));
   }
   return std::nullopt;
}

bool NoiseReductionHasProfile(EffectPlugin &effect)
{
   auto p = dynamic_cast<NoiseReductionFx *>(&effect);
   return p && p->HasProfile();
}

void NoiseReductionSetProfileMode(EffectPlugin &effect, bool doProfile)
{
   if (auto p = dynamic_cast<NoiseReductionFx *>(&effect))
      p->NrSettings().mDoProfile = doProfile;
}

NoiseReductionValues NoiseReductionGet(EffectPlugin &effect)
{
   NoiseReductionValues values;
   if (auto p = dynamic_cast<NoiseReductionFx *>(&effect)) {
      auto &s = p->NrSettings();
      values.gain = s.mNoiseGain;
      values.sensitivity = s.mNewSensitivity;
      values.freqSmoothing = static_cast<int>(std::lround(s.mFreqSmoothingBands));
      values.reductionChoice = s.mNoiseReductionChoice;
   }
   return values;
}

void NoiseReductionSet(EffectPlugin &effect, const NoiseReductionValues &values)
{
   auto p = dynamic_cast<NoiseReductionFx *>(&effect);
   if (!p)
      return;
   auto &s = p->NrSettings();
   s.mNoiseGain = values.gain;
   s.mNewSensitivity = values.sensitivity;
   s.mFreqSmoothingBands = values.freqSmoothing;
   s.mNoiseReductionChoice = values.reductionChoice;
   // Persist like the dialog does (NoiseReduction.cpp: PrefsIO(false))
   s.PrefsIO(false);
}

double AmplifyPeak(EffectPlugin &effect)
{
   auto p = dynamic_cast<AmplifyFx *>(&effect);
   return p ? p->Peak() : 0.0;
}

} // namespace effects
} // namespace aubridge
