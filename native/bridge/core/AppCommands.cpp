/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  AppCommands.cpp

  app.info, view.set, debug.makeTestTrack; RegisterSpineCommands().

**********************************************************************/
#include "SpineCommands.h"

#include <chrono>
#include <cmath>
#include <thread>
#include <vector>

#include <wx/filename.h>
#include <wx/version.h>

#include "BasicUI.h"
#include "ExportPlugin.h"
#include "ExportPluginRegistry.h"
#include "Edit.h"
#include "Import.h"
#include "Language.h"
#include "PluginManager.h"
#include "Project.h"
#include "ProjectRate.h"
#include "QualitySettings.h"
#include "Session.h"
#include "Track.h"
#include "ViewInfo.h"
#include "WaveTrack.h"
#include "UiServices.h"
#include "UserException.h"
#include "aubridge_build_info.h"
#include "sqlite3.h"

namespace aubridge {

namespace {

json AppInfo(const json &)
{
   json libraries = json::array();
   static const char *const names[] = { AUBRIDGE_LIBRARY_NAMES };
   for (auto name : names)
      if (name)
         libraries.push_back(name);

   json importers = json::array();
   {
      const auto types = Importer::Get().GetFileTypes();
      // The first three are "All files", "All supported files", projects
      for (size_t i = 3; i < types.size(); ++i)
         importers.push_back(Translated(types[i].description));
   }
   json exporters = json::array();
   for (auto [plugin, index] : ExportPluginRegistry::Get())
      exporters.push_back(ToUtf8(plugin->GetFormatInfo(index).format));

   size_t effects = 0;
   for (auto &plugin : PluginManager::Get().PluginsOfType(PluginTypeEffect)) {
      (void)plugin;
      ++effects;
   }
   // English is built in; the others are the installed catalogs
   json languages = json::array({ "en" });
   for (const auto &code : Language::Installed())
      if (code != "en")
         languages.push_back(code);

   const bool nyquist = wxFileName::FileExists(
      FromUtf8(Session::Get().GetPaths().nyquistDir + "/nyquist.lsp"));

   return json{
      { "audacityVersion", "3.7.9" },
      { "engineVersion", AUBRIDGE_ENGINE_VERSION },
      { "wxVersion", wxVERSION_NUM_DOT_STRING },
      { "sqliteVersion", sqlite3_libversion() },
      { "abi", AUBRIDGE_ABI },
      { "libraries", std::move(libraries) },
      { "importers", std::move(importers) },
      { "exporters", std::move(exporters) },
      { "effectsCount", effects },
      { "nyquist", nyquist },
      { "language", Language::Current() },
      { "languages", std::move(languages) } };
}

json ViewSet(const json &args)
{
   auto &project = Session::Get().RequireProject();
   auto &viewInfo = ViewInfo::Get(project);
   if (auto zoom = OptDouble(args, "zoom")) {
      if (!(*zoom > 0))
         Fail(ErrorCode::INVALID_ARGS, "argument 'zoom' must be positive");
      viewInfo.SetZoom(*zoom);   // clamps to [0.001, 6e6]
   }
   if (auto hpos = OptDouble(args, "hpos"))
      viewInfo.hpos = *hpos;
   return json::object();
}

//! Appends a WaveTrack with a sine tone (one undo state).  For tests and
//! for phase-2 modules before the generators exist.
json MakeTestTrack(const json &args)
{
   auto &project = Session::Get().RequireProject();
   const double seconds = OptDouble(args, "seconds").value_or(1.0);
   const double rate = OptDouble(args, "rate")
      .value_or(ProjectRate::Get(project).GetRate());
   const double frequency = OptDouble(args, "frequency").value_or(440.0);
   const auto channels = OptInt(args, "channels").value_or(1);
   const double amplitude = OptDouble(args, "amplitude").value_or(0.5);
   RequireRange("seconds", seconds, 1e-3, 3600);
   RequireRange("rate", rate, 1000, 768000);
   RequireRange("frequency", frequency, 0, rate / 2);
   RequireRange("channels", double(channels), 1, 2);
   RequireRange("amplitude", amplitude, 0, 1);

   auto &tracks = TrackList::Get(project);
   int64_t id = -1;
   RunEdit(project, XO("Created test tone"), XO("Test Tone"), [&] {
      auto track = WaveTrackFactory::Get(project).Create(size_t(channels),
         QualitySettings::SampleFormatChoice(), rate);
      track->SetName(tracks.MakeUniqueTrackName(wxT("Test Tone")));
      const size_t total = size_t(std::llround(seconds * rate));
      constexpr size_t chunk = 65536;
      std::vector<float> buffer(chunk);
      for (size_t start = 0; start < total; start += chunk) {
         const size_t len = std::min(chunk, total - start);
         for (size_t ii = 0; ii < size_t(channels); ++ii) {
            for (size_t i = 0; i < len; ++i)
               buffer[i] = float(amplitude * std::sin(2 * M_PI * frequency *
                  double(start + i) / rate + ii * M_PI / 2));
            track->Append(ii, reinterpret_cast<constSamplePtr>(buffer.data()),
               floatSample, len);
         }
      }
      track->Flush();
      auto pTrack = tracks.Add(track);
      id = TrackIdValue(*pTrack);
   });
   return json{ { "id", id } };
}

//! Asks a question through BasicUI (blocking `dialog` event): for tests and
//! UI development.  {message, title?, cancel?:bool, choices?:[..]}; with
//! multiChoice:true (+ defaultChecked?:[bool]) it asks through
//! Dialogs::ChooseMany -> {choices:[int]} or {result:"cancel"}
json DebugAsk(const json &args)
{
   const auto message = FromUtf8(OptString(args, "message").value_or("?"));
   const auto title = FromUtf8(OptString(args, "title").value_or("Audacity"));
   if (OptBool(args, "multiChoice").value_or(false)) {
      std::vector<bool> defaultChecked;
      if (auto d = args.find("defaultChecked"); d != args.end() && !d->is_null()) {
         if (!d->is_array())
            Fail(ErrorCode::INVALID_ARGS, "argument 'defaultChecked' must be an array");
         for (const auto &b : *d) {
            if (!b.is_boolean())
               Fail(ErrorCode::INVALID_ARGS, "defaultChecked must hold booleans");
            defaultChecked.push_back(b.get<bool>());
         }
      }
      auto chosen = Dialogs::ChooseMany(ToUtf8(title), ToUtf8(message),
         ArgStringArray(args, "choices"), defaultChecked);
      if (!chosen)
         return json{ { "result", "cancel" } };
      return json{ { "choices", *chosen } };
   }
   auto it = args.find("choices");
   if (it != args.end() && it->is_array()) {
      TranslatableStrings choices;
      for (const auto &c : ArgStringArray(args, "choices"))
         choices.push_back(Verbatim(FromUtf8(c)));
      const int index = BasicUI::ShowMultiDialog(Verbatim(message),
         Verbatim(title), choices, {}, {}, false);
      return json{ { "choice", index } };
   }
   using namespace BasicUI;
   auto options = MessageBoxOptions{}.Caption(Verbatim(title))
      .IconStyle(Icon::Question).ButtonStyle(Button::YesNo);
   if (OptBool(args, "cancel").value_or(false))
      options = std::move(options).CancelButton();
   const auto result = ShowMessageBox(Verbatim(message), std::move(options));
   const char *name = result == MessageBoxResult::Yes ? "yes"
      : result == MessageBoxResult::No ? "no"
      : result == MessageBoxResult::Cancel ? "cancel" : "none";
   return json{ { "result", name } };
}

//! Runs a BasicUI progress for `seconds` (polling every 10 ms); throws
//! UserException when cancelled (-> CANCELLED), returns {stopped:true} when
//! stopped.  For tests and UI development.
json DebugProgress(const json &args)
{
   const double seconds = OptDouble(args, "seconds").value_or(1.0);
   RequireRange("seconds", seconds, 0, 60);
   auto progress = BasicUI::MakeProgress(Verbatim(wxT("Debug")),
      Verbatim(wxT("Working...")));
   const unsigned long long total = (unsigned long long)(seconds * 100);
   for (unsigned long long i = 0; i <= total; ++i) {
      const auto result = progress->Poll(i, total ? total : 1);
      if (result == BasicUI::ProgressResult::Cancelled)
         throw UserException{};
      if (result == BasicUI::ProgressResult::Stopped)
         return json{ { "stopped", true } };
      if (i < total)
         std::this_thread::sleep_for(std::chrono::milliseconds(10));
   }
   return json{ { "stopped", false } };
}

} // namespace

void RegisterAppCommands(ModuleRegistry &registry)
{
   registry.AddCommand("app.info", AppInfo);
   registry.AddCommand("view.set", ViewSet, NeedsProject | SelectionOnly);
   registry.AddCommand("debug.makeTestTrack", MakeTestTrack,
      NeedsProject | NeedsIdleAudio | Mutates);
   registry.AddCommand("debug.ask", DebugAsk);
   registry.AddCommand("debug.progress", DebugProgress, LongRunning);
}

void RegisterSpineCommands(ModuleRegistry &registry)
{
   RegisterAppCommands(registry);
   RegisterSettingsCommands(registry);
   RegisterProjectCommands(registry);
   RegisterHistoryCommands(registry);
}

} // namespace aubridge
