/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  RegisterEffectsModule.cpp

  Entry point of the bridge "effects" module (MODULES.md): effects.* and
  analyze.* commands (API.md §3.3, §5.4, §5.5) and the hooks that replace
  what src/ does for effects on desktop:

   * before PluginManager::Initialize: register the built-in effects and
     install the library hooks (Nyquist factory, realtime factory, ...)
   * after the bootstrap: register .ny files that the Nyquist provider
     does not auto-register (e.g. rms.ny "Measure RMS"), like desktop's
     PluginStartupRegistration
   * snapshot: lastEffect / lastGenerator / lastAnalyzer / lastTool
   * tick / project closing / shutdown: effect preview bookkeeping
   * shutdown: drop the loaded effect objects (EffectManager would keep
     dangling pointers across Stop()/Start())

**********************************************************************/
#include "Modules.h"

#include "EffectsInternal.h"

namespace aubridge {

void RegisterEffectsModule(ModuleRegistry &registry)
{
   registry.AddBeforePluginManagerInit([] {
      effects::RegisterBuiltinEffects();
      effects::InstallLibraryHooks();
   });
   registry.AddAfterBootstrap([] { effects::RegisterNewPlugins(); });

   effects::RegisterMenuCommands(registry);
   effects::RegisterSchemaCommands(registry);
   effects::RegisterApplyCommands(registry);
   effects::RegisterPreviewCommands(registry);
   effects::RegisterAnalyzerCommands(registry);

   // After the preview's own shutdown hook (registered above)
   registry.AddBeforeShutdown([] { effects::UnloadEffects(); });
}

} // namespace aubridge
