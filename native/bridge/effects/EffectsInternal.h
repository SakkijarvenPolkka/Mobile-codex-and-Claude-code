/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  EffectsInternal.h

  Shared declarations of the bridge "effects" module (API.md effects.* and
  analyze.*).  Everything here runs on the engine thread.

  Files:
   * BuiltinEffects.cpp   registration of the 3.7.9 built-ins (the
                          lib-builtin-effects Base classes + bridge
                          subclasses, Compressor/Limiter), library hooks
                          (Nyquist, realtime factory), post-Init fixups
   * EffectLabels.cpp     per-effect label/unit/display table (3.7.9 UI
                          wording, translated through the catalog)
   * EffectSchema.cpp     parameter schema, setParams, presets, describe
   * EffectMenus.cpp      effects.list and the menu sections
   * EffectApply.cpp      apply, repeat, last effects, Noise Reduction
                          profile, message capture
   * EffectPreview.cpp    effects.preview / stopPreview
   * Analyzers.cpp        analyze.spectrum / analyze.contrast

**********************************************************************/
#pragma once

#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "Json.h"

#include "ComponentInterfaceSymbol.h"
#include "EffectInterface.h"
#include "Identifier.h"
#include "PluginProvider.h"   // PluginID
#include "TranslatableString.h"

class AudacityProject;
class Effect;
class EffectPlugin;
class EffectSettings;
class PluginDescriptor;
class NyquistBase;

namespace aubridge {
class ModuleRegistry;

namespace effects {

// ---------------------------------------------------------------------------
// BuiltinEffects.cpp
// ---------------------------------------------------------------------------

//! Registers every built-in with BuiltinEffectsModule (once per process;
//! must run before PluginManager::Initialize)
void RegisterBuiltinEffects();
//! Installs the hooks src/ normally installs (Nyquist effect factory,
//! realtime effect factory, Nyquist displays/debug output); idempotent
void InstallLibraryHooks();
//! Registers .ny files found in the search path that are not registered
//! yet (e.g. rms.ny) -- desktop's PluginStartupRegistration, in-process
void RegisterNewPlugins();
//! Drops every loaded effect object (EffectManager keeps raw pointers into
//! PluginManager, which Terminate() destroys); BeforeShutdown
void UnloadEffects();

//! Effects that need special treatment
enum class Special {
   None,
   Amplify,
   AutoDuck,
   NoiseReduction,
   FilterCurveEq,
   GraphicEq,
   ScienFilter,
   Dtmf,
   Phaser,
   Nyquist,
};
Special SpecialOf(const EffectPlugin &effect);
//! API.md §5.5 `special` value, or empty
std::string SpecialName(Special special);
//! The same, from a plug-in path (no loading)
std::string SpecialNameForPath(const wxString &path);

//! What the wx dialogs did after Instance::Init (effects.md §5.4).
//! Returns a (translated) message when the effect must not run.
std::optional<std::string> PostInitFixups(EffectPlugin &effect,
   EffectSettings &settings);

//! Noise Reduction accessors (null / false for other effects)
struct NoiseReductionValues {
   double gain = 6.0;          //!< "Gain", dB [0, 48]
   double sensitivity = 6.0;   //!< "Sensitivity" [0.01, 24]
   int freqSmoothing = 6;      //!< "FreqSmoothing" bands [0, 12]
   int reductionChoice = 0;    //!< 0 = Reduce, 2 = Residue (NRC_*)
};
bool NoiseReductionHasProfile(EffectPlugin &effect);
void NoiseReductionSetProfileMode(EffectPlugin &effect, bool doProfile);
NoiseReductionValues NoiseReductionGet(EffectPlugin &effect);
//! Writes the values into the effect and the preferences
void NoiseReductionSet(EffectPlugin &effect, const NoiseReductionValues &values);

//! Amplify: peak of the selection after Init (0 when unknown)
double AmplifyPeak(EffectPlugin &effect);

//! PluginID of the built-in with that symbol (empty when not registered)
PluginID BuiltinId(const ComponentInterfaceSymbol &symbol);
//! PluginID of Noise Reduction
PluginID NoiseReductionId();

// ---------------------------------------------------------------------------
// EffectLabels.cpp
// ---------------------------------------------------------------------------
struct ParamLabel {
   std::string label;      //!< translated, mnemonics stripped
   std::string unit;       //!< translated
   std::string display;    //!< "" | "dB"
   //! int parameters that are really a choice (Loudness NormalizeTo):
   //! translated labels of values 0..n-1
   std::vector<std::string> intChoices;
};
//! @param symbolInternal ComponentInterfaceSymbol::Internal() of the effect
std::optional<ParamLabel> LookupParamLabel(const wxString &symbolInternal,
   const wxString &key);
//! Removes "&" mnemonics (also the CJK "(&X)" form) and a trailing colon
std::string StripMnemonics(const wxString &text);

// ---------------------------------------------------------------------------
// EffectSchema.cpp
// ---------------------------------------------------------------------------

//! The loaded effect + its EffectManager default settings
struct Loaded {
   const PluginDescriptor *descriptor = nullptr;
   EffectPlugin *plugin = nullptr;
   Effect *effect = nullptr;              //!< every effect we register is one
   EffectSettings *settings = nullptr;    //!< EffectManager default settings
   PluginID id;
   EffectType type = EffectTypeNone;
   Special special = Special::None;
};
//! @throws BridgeError NOT_FOUND (unknown/disabled/unloadable)
Loaded LoadEffect(const std::string &id);

//! effects.describe
json Describe(const Loaded &fx);
//! Applies `params`, `duration`, `curve` of `args` (effects.setParams
//! semantics: validate everything first, then write; partial merge).
//! @throws BridgeError INVALID_ARGS, nothing changed
void ApplyParamArgs(const Loaded &fx, const json &args);
//! The current automation string
wxString GetParameterString(const Loaded &fx);
//! Last used / default generator duration (CurrentSettings/LastUsedDuration)
double LastUsedDuration(const Loaded &fx);
//! Throws INVALID_ARGS unless 0 < d <= limit
double ValidateDuration(double d);

void RegisterSchemaCommands(ModuleRegistry &registry);

// ---------------------------------------------------------------------------
// EffectMenus.cpp
// ---------------------------------------------------------------------------
void RegisterMenuCommands(ModuleRegistry &registry);
//! True for effects shipped with the app (built-ins, Nyquist Prompt and the
//! .ny files of the bundled plug-ins directory)
bool IsBundled(const PluginDescriptor &plug);
std::string TypeName(EffectType type);

// ---------------------------------------------------------------------------
// EffectApply.cpp
// ---------------------------------------------------------------------------

//! Captures OK-only message boxes, error dialogs and Nyquist debug output
//! (critic.md C18: they become `result.message` instead of `dialog`
//! events) and notices cancel/stop of the progress dialogs, while alive.
//! Interposes a forwarding BasicUI::Services (one per process, never
//! deleted); nests; engine thread only.
class CaptureScope final {
public:
   CaptureScope();
   ~CaptureScope();
   CaptureScope(const CaptureScope &) = delete;
   CaptureScope &operator=(const CaptureScope &) = delete;

   bool Cancelled() const;     //!< a progress returned Cancelled
   bool Stopped() const;       //!< a progress returned Stopped
   //! Captured texts joined with blank lines (empty when none)
   std::string Message() const;
   bool HasMessage() const;
   struct State;
private:
   std::shared_ptr<State> mState;
   void *mPreviousServices = nullptr;   //!< the services we replaced
   void *mPreviousState = nullptr;      //!< enclosing capture's state
   bool mInstalled = false;             //!< outermost scope
};
//! Appends a message to the innermost active capture (engine thread);
//! returns false when no capture is active
bool CaptureMessage(const std::string &text);

void RegisterApplyCommands(ModuleRegistry &registry);

//! Sets the context fields EffectBase::DoEffect sets before its dialog
//! (factory, project rate, the project's TrackList, mT0/mT1 from the
//! quantized selection, track counts) for work outside DoEffect (preview,
//! Amplify's peak); drops the track list again on destruction
class EffectContext final {
public:
   EffectContext(Effect &effect, AudacityProject &project);
   ~EffectContext();
   EffectContext(const EffectContext &) = delete;
   EffectContext &operator=(const EffectContext &) = delete;
private:
   Effect &mEffect;
};

//! Amplify: linear peak of the selected audio (nullopt for other effects
//! or without a time selection on wave tracks)
std::optional<double> SelectionPeak(const Loaded &fx);
//! Amplify "Defaults": ratio = 1/peak of the selection (LoadFactoryDefaults
//! with the effect context); false without an audio selection
bool LoadAmplifyDefaults(const Loaded &fx);

// ---------------------------------------------------------------------------
// EffectPreview.cpp
// ---------------------------------------------------------------------------
void RegisterPreviewCommands(ModuleRegistry &registry);
//! Stops a running preview (StopStream when its stream still runs) and
//! emits the `transport` event; no-op without a preview
void StopPreview();
bool PreviewActive();

// ---------------------------------------------------------------------------
// Analyzers.cpp
// ---------------------------------------------------------------------------
void RegisterAnalyzerCommands(ModuleRegistry &registry);

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------
//! Shortest round-trip text of a double, C locale
std::string FormatNumber(double value);
//! Requires a time selection on selected wave tracks (menu flags
//! TimeSelected | WaveTracksSelected); honours /GUI/SelectAllOnNone
//! @param name the command, for 3.7.9's message
//! @param noiseReduction Noise Reduction's own message
//! @throws BridgeError NO_SELECTION
void RequireAudioSelection(AudacityProject &project,
   const TranslatableString &name, bool noiseReduction = false);
void RequireAudioSelection(AudacityProject &project);

} // namespace effects
} // namespace aubridge
