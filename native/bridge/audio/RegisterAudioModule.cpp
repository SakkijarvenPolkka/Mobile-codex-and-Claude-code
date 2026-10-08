/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  RegisterAudioModule.cpp

  Entry point of the bridge "audio" module (MODULES.md): transport.* and
  audio.* commands, the transport/meter readers of Bridge.h, the engine
  tick, the stream finalizer and the AAudio defaults that must be in place
  before AudioIO::Init (BeforePluginManagerInit runs before it, see
  core/README.md §6).

**********************************************************************/
#include "Modules.h"

#include "AudioModule.h"
#include "Hooks.h"

namespace aubridge {

void RegisterAudioModule(ModuleRegistry &registry)
{
   ResetDeviceState();
   ResetTransportState();

   SetTransportReader(&ReadTransportImpl);
   SetMetersReader(&ReadMetersImpl);

   // PaAAudio_SetDefaults(rate, burst) from the start configuration before
   // AudioIO::Init() (Pa_Initialize) -- the hook runs before it
   registry.AddBeforePluginManagerInit([] { ApplyStartConfigDefaults(); });
   registry.AddBeforeShutdown([] {
      ResetTransportState();
      ResetDeviceState();
   });

   RegisterTransportCommands(registry);
   RegisterDeviceCommands(registry);
}

} // namespace aubridge
