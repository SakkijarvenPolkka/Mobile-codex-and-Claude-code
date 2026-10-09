/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  RegisterDisplayModule.cpp

  The bridge "display" module (MODULES.md, API.md §7): installs the
  display providers (waveform columns, envelope columns, samples,
  spectrogram), the waveVersion provider and the recording preparer
  (display::PrepareForRecording, run by the audio module before
  AudioIO::StartStream), and registers
  display.setViewportWidth, display.trimCaches and the test-only debug
  commands (DebugCommands.cpp).

**********************************************************************/
#include "Modules.h"

#include "ClipDisplayCache.h"
#include "DisplayInternal.h"
#include "DisplayTracks.h"
#include "Hooks.h"

namespace aubridge {

void RegisterDisplayModule(ModuleRegistry &registry)
{
   SetDisplayProviders({ display::WaveColumns, display::EnvelopeColumns,
      display::WaveSamples, display::SpectrogramColumns });
   SetWaveVersionProvider(display::DisplayWaveVersion);
   SetRecordingPreparer(display::PrepareForRecording);

   registry.AddCommand("display.setViewportWidth", [](const json &args) {
      const auto px = ArgInt(args, "px");
      RequireRange("px", double(px), 1, 65536);
      display::SetViewportWidth(px);
      return json::object();
   });
   registry.AddCommand("display.trimCaches", [](const json &args) {
      const double budget = ArgDouble(args, "budgetBytes");
      RequireRange("budgetBytes", budget, 0, 1e18);
      const auto bytes = display::TrimDisplayCaches(size_t(budget));
      return json{ { "bytes", bytes } };
   });
   display::RegisterDebugCommands(registry);

   registry.AddBeforeShutdown([] {
      // The project (and every clip) is closed by now
      display::ReleaseAllDisplayCaches();
      display::ResetSpectrogram();
      display::SetViewportWidth(0);
   });
}

} // namespace aubridge
