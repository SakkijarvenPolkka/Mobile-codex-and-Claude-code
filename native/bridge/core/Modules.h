/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Modules.h

  The one entry point of each feature module (MODULES.md).  The spine calls
  them once per Start(), on the engine thread, during bootstrap (after the
  preferences are initialized, before PluginManager::Initialize).

**********************************************************************/
#pragma once

#include "ModuleRegistry.h"

namespace aubridge {
void RegisterEditModule(ModuleRegistry &);      // edit/
void RegisterEffectsModule(ModuleRegistry &);   // effects/
void RegisterIoModule(ModuleRegistry &);        // io/
void RegisterAudioModule(ModuleRegistry &);     // audio/
void RegisterDisplayModule(ModuleRegistry &);   // display/
}
