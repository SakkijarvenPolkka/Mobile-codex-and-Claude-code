/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  RegisterIoModule.cpp

  Entry point of the bridge "io" module (native/bridge/io): the import.*
  and export.* commands of API.md §3.3, the bridge's import routine for
  lib-app-services (mod-aup <import> elements, mod-lof), diagnostics of the
  import/export registries, and (Android) the pre-warm of the AAC encoder
  probe of io/android.

**********************************************************************/
#include "IoModule.h"

#include <algorithm>
#include <string>
#include <thread>

#include "Events.h"
#include "Modules.h"

#ifdef __ANDROID__
#include "android/AndroidCodecs.h"
#endif

namespace aubridge {

namespace {

bool Contains(const std::vector<std::string> &list, const std::string &item)
{
   return std::find(list.begin(), list.end(), item) != list.end();
}

//! Diagnostics of the registries (the spine's `importers` / `exporters`
//! self checks only count them): every plug-in the port ships is there,
//! export format keys are unique, the Android media importer probes last.
//! Problems are `log` warnings; they never fail the bootstrap.
void LogRegistryChecks()
{
   std::string problems;
   auto problem = [&](const std::string &text) {
      if (!problems.empty())
         problems += "; ";
      problems += text;
   };

   const auto importers = io::ImporterMsgids();
   for (const char *msgid : { "WAV, AIFF, and other uncompressed types",
           "Ogg Vorbis files", "FLAC files", "MP3 files", "WavPack files",
           "Opus files" })
      if (!Contains(importers, msgid))
         problem(std::string("import plug-in missing: ") + msgid);
#ifdef __ANDROID__
   if (importers.empty() ||
       importers.back() != android_media::kImporterDescription)
      problem("the Android media importer is not the last import plug-in");
#endif

   const auto keys = io::ExportFormatKeys();
   std::vector<std::string> expected{ "WAV (Microsoft)",
      "Other uncompressed files", "MP3 Files", "Ogg Vorbis Files",
      "Opus Files", "FLAC Files", "WavPack Files", "MP2 Files" };
#ifdef __ANDROID__
   expected.push_back(android_media::kExportFormatKey);
#endif
   for (const auto &key : expected) {
      const auto n = std::count(keys.begin(), keys.end(), key);
      if (n != 1)
         problem("export format '" + key + "' registered " +
            std::to_string(n) + " times");
   }

   std::string summary = "io: " + std::to_string(importers.size()) +
      " import plug-ins, " + std::to_string(keys.size()) + " export formats";
   if (problems.empty())
      Events::Log(Events::LogLevel::Info, summary);
   else
      Events::Log(Events::LogLevel::Warning, summary + " -- " + problems);
}

#ifdef __ANDROID__
//! Runs the AAC encoder probe (0.1 - 0.5 s, at most ~5 s) on a worker once
//! per process, so that the first M4A export dialog does not wait for it.
//! The probe only uses the NDK media API and process-wide state, so the
//! thread is detached; AacEncoderCapabilities() is thread-safe
//! (std::call_once) and the options editor simply waits if it runs first.
void PrefetchAacCapabilities()
{
   static bool started = false;
   if (started)
      return;
   started = true;
   try {
      std::thread([] {
         try {
            android_media::AacEncoderCapabilities();
         }
         catch (...) {
         }
      }).detach();
   }
   catch (...) {
      // No thread: the probe runs on first use instead
   }
}
#endif

} // namespace

void RegisterIoModule(ModuleRegistry &registry)
{
   io::RegisterImportCommands(registry);
   io::RegisterExportCommands(registry);

   registry.AddAfterBootstrap([] {
      io::InstallImportHandler();
      LogRegistryChecks();
#ifdef __ANDROID__
      PrefetchAacCapabilities();
#endif
   });

   registry.AddBeforeShutdown([] {
      io::ResetExportSessions();
      io::UninstallImportHandler();
   });
}

} // namespace aubridge
