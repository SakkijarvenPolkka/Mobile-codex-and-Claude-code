/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  SpineCommands.h

  Commands implemented by the spine (API.md: app / settings, project,
  history, view, debug).

**********************************************************************/
#pragma once

#include "ModuleRegistry.h"

namespace aubridge {
void RegisterSpineCommands(ModuleRegistry &registry);

// One per source file
void RegisterAppCommands(ModuleRegistry &registry);
void RegisterSettingsCommands(ModuleRegistry &registry);
void RegisterProjectCommands(ModuleRegistry &registry);
void RegisterHistoryCommands(ModuleRegistry &registry);
}
