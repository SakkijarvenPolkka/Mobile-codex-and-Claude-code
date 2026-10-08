/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  RegisterEditModule.cpp

  The bridge "edit" module (MODULES.md): selection, play region, edit,
  tracks, clips and labels commands of API.md §3.3.

**********************************************************************/
#include "Modules.h"

#include "EditUtil.h"

namespace aubridge {

void RegisterEditModule(ModuleRegistry &registry)
{
   edit::RegisterSelectCommands(registry);
   edit::RegisterEditCommands(registry);
   edit::RegisterTrackCommands(registry);
   edit::RegisterClipCommands(registry);
   edit::RegisterLabelCommands(registry);
}

} // namespace aubridge
