/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  Engine.cpp

  Headless replacement of AudacityApp::OnInit / OnExit (Audacity 3.7.9
  src/AudacityApp.cpp), in the order of init-and-project.md §6.2, adjusted
  for the port's build which defines __WXGTK__:

   * FileNames derives ConfigDir/DataDir/CacheDir/StateDir from the XDG
     environment variables in that configuration (lib-files/FileNames.cpp
     GetXDGTargetDir), so the bridge sets XDG_* (and HOME, TMPDIR,
     SQLITE_TMPDIR, WX_AUDACITY_DATA_DIR) from the start configuration
     before wx and FileNames are touched -- no PlatformCompatibility
     replacement and no lib-files patch is needed.  Consequence: those
     directories are cached per process (FileNames statics); a restart in
     the same process must use the same configuration paths.
   * FileNames::InitializePathList() is not called: with __WXGTK__ it adds
     the current directory, the executable's directories and
     INSTALL_PREFIX/share and overrides the default temp dir with
     $TMPDIR/audacity-<user>.  The bridge sets the path list (Nyquist
     runtime, plug-ins, DataDir) and the temp dir (noBackupDir/SessionData)
     itself.

**********************************************************************/
#include "Engine.h"

#include <atomic>
#include <cerrno>
#include <clocale>
#include <cstdlib>
#include <ctime>
#include <dlfcn.h>
#include <future>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>

// The Audacity build defines _WX_APP_H_BASE_ for every target ("don't use
// app.h", upstream apply_wxbase_restrictions); the bridge is the application
// and needs wxTheApp (console app object created by wxInitialize)
#ifdef _WX_APP_H_BASE_
#undef _WX_APP_H_BASE_
#endif
#include <wx/app.h>
#include <wx/filename.h>
#include <wx/init.h>
#include <wx/log.h>

#include "AppEvents.h"
#include "AudacityLogger.h"
#include "AudioIO.h"
#include "BasicSettings.h"
#include "BasicUI.h"
#include "Clipboard.h"
#include "EngineThread.h"
#include "Events.h"
#include "ExportPluginRegistry.h"
#include "FFT.h"
#include "FileNames.h"
#include "FileSettings.h"
#include "Hooks.h"
#include "Import.h"
#include "Internat.h"
#include "Languages.h"
#include "ModuleRegistry.h"
#include "Modules.h"
#include "PluginManager.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "ProjectSession.h"
#include "SampleBlock.h"
#include "SampleFormat.h"
#include "Session.h"
#include "SpineCommands.h"
#include "TempDirectory.h"
#include "UiServices.h"
#include "aubridge/Bridge.h"
#include "aubridge_build_info.h"

namespace aubridge {
namespace Engine {

namespace {

struct AppEventsProvider final : AppEvents::ProviderBase {
   using ProviderBase::HandleAppInitialized;
   using ProviderBase::HandleAppIdle;
   using ProviderBase::HandleAppClosing;
};

struct State {
   std::atomic<bool> ready{ false };
   std::mutex lifecycleMutex;

   // Bootstrap progress (engine thread), for a partial shutdown
   bool wxInitialized = false;
   bool uiInstalled = false;
   bool prefsInitialized = false;
   bool pluginsInitialized = false;
   bool audioInitialized = false;
   bool importerInitialized = false;

   std::unique_ptr<WindowPlacementFactory::Scope> placementScope;
   std::unique_ptr<audacity::ApplicationSettings::Scope> settingsScope;
   AppEventsProvider appEvents;
   json selfChecks = json::array();
   std::string argv0;
   char *argv[2] = { nullptr, nullptr };
};

State &TheState()
{
   static State state;
   return state;
}

// Process-wide: ExportPluginRegistry::Initialize() is not idempotent
bool sExportersInitialized = false;

struct Fatal : std::runtime_error {
   using std::runtime_error::runtime_error;
};

// ---------------------------------------------------------------------------
// Logging: wxLog -> `log` events (+ logcat)
// ---------------------------------------------------------------------------
class BridgeLog final : public wxLog {
protected:
   void DoLogRecord(wxLogLevel level, const wxString &msg,
      const wxLogRecordInfo &) override
   {
      using Events::LogLevel;
      LogLevel l = LogLevel::Info;
      switch (level) {
      case wxLOG_FatalError:
      case wxLOG_Error: l = LogLevel::Error; break;
      case wxLOG_Warning: l = LogLevel::Warning; break;
      case wxLOG_Message:
      case wxLOG_Status:
      case wxLOG_Info: l = LogLevel::Info; break;
      default: l = LogLevel::Debug; break;
      }
      Events::Log(l, ToUtf8(msg));
   }
};

void InstallLogger()
{
   // AudacityLogger::Get() replaces (and deletes) the active wx log target
   // the first time it is called (DBConnection calls it on errors).  Let it
   // do so now, then install the bridge's target, which forwards every
   // record with its level.  Afterwards AudacityLogger::Get() returns null,
   // which its callers handle.
   (void)AudacityLogger::Get();
   delete wxLog::SetActiveTarget(new BridgeLog);
   wxLog::SetLogLevel(wxLOG_Max);
}

void AssertHandler(const wxString &file, int line, const wxString &func,
   const wxString &cond, const wxString &msg)
{
   Events::Log(Events::LogLevel::Warning,
      "wx assertion failed: " + ToUtf8(cond) + " " + ToUtf8(msg) + " (" +
      ToUtf8(file) + ":" + std::to_string(line) + " " + ToUtf8(func) + ")");
}

// ---------------------------------------------------------------------------
// Directories and process environment
// ---------------------------------------------------------------------------
void MakeDirs(const std::string &path, mode_t mode = 0755)
{
   std::string partial;
   size_t pos = 0;
   while (pos != std::string::npos) {
      pos = path.find('/', pos + 1);
      partial = path.substr(0, pos);
      if (partial.empty())
         continue;
      if (::mkdir(partial.c_str(), mode) != 0 && errno != EEXIST)
         throw Fatal("cannot create directory " + partial);
   }
   struct stat st {};
   if (::stat(path.c_str(), &st) != 0 || !S_ISDIR(st.st_mode))
      throw Fatal("not a directory: " + path);
}

void PrepareEnvironment(const Paths &paths)
{
   MakeDirs(paths.configDir);
   MakeDirs(paths.sessionDir, 0700);
   MakeDirs(paths.tmpDir);
   MakeDirs(paths.importDir);
   MakeDirs(paths.exportDir);
   MakeDirs(paths.projectsDir);

   const auto set = [](const char *key, const std::string &value) {
      ::setenv(key, value.c_str(), 1);
   };
   // HOME: wxGetHomeDir, FileNames' legacy ~/.audacity-data check
   set("HOME", paths.filesDir);
   // FileNames::ConfigDir() == DataDir() == filesDir/audacity
   set("XDG_CONFIG_HOME", paths.filesDir);
   set("XDG_DATA_HOME", paths.filesDir);
   // FileNames::CacheDir() / StateDir() (unused by the compiled libraries)
   set("XDG_CACHE_HOME", paths.cacheDir);
   set("XDG_STATE_HOME", paths.noBackupDir);
   // SQLite and wx temporary files (must precede the first SQLite temp file)
   set("TMPDIR", paths.tmpDir);
   set("SQLITE_TMPDIR", paths.tmpDir);
   // wxStandardPaths::GetDataDir() -> FileNames::ResourcesDir()
   set("WX_AUDACITY_DATA_DIR", paths.configDir);
}

std::string LibraryPath()
{
   Dl_info info{};
   if (dladdr(reinterpret_cast<const void *>(&aubridge::Start), &info) &&
       info.dli_fname && info.dli_fname[0] == '/')
      return info.dli_fname;
   return {};
}

void InitWx(const StartConfig &config)
{
   auto &state = TheState();
   // argv[0] feeds wxStandardPaths::GetExecutablePath (without __LINUX__
   // on Android); FileNames looks for "Portable Settings" next to it
   state.argv0 = LibraryPath();
   if (state.argv0.empty())
      state.argv0 = config.paths.configDir + "/audacity";
   state.argv[0] = state.argv0.data();
   state.argv[1] = nullptr;
   int argc = 1;
   if (!wxInitialize(argc, state.argv))
      throw Fatal("wxInitialize failed");
   state.wxInitialized = true;
   wxTheApp->SetAppName(wxT("audacity"));
   wxTheApp->SetVendorName(wxT("audacity"));
   wxSetAssertHandler(&AssertHandler);
}

// ---------------------------------------------------------------------------
// Preferences (replacement of AudacityApp::PopulatePreferences)
// ---------------------------------------------------------------------------
wxString LanguageCode(const std::string &locale)
{
   // Java Locale.toString(): "ko_KR", "zh_CN_#Hans", "pt_BR"; legacy codes
   auto code = locale.substr(0, locale.find('#'));
   while (!code.empty() && code.back() == '_')
      code.pop_back();
   for (auto &c : code)
      if (c == '-')
         c = '_';
   if (code.rfind("iw", 0) == 0)
      code = "he" + code.substr(2);
   else if (code.rfind("in", 0) == 0 && (code.size() == 2 || code[2] == '_'))
      code = "id" + code.substr(2);
   if (code.empty())
      code = "en";
   return FromUtf8(code);
}

void WriteMobileDefaults(const StartConfig &config)
{
   auto &p = *gPrefs;
   // Avoid probing the device in ProjectRate's constructor; the device's
   // native rate avoids resampling in the audio path
   p.Write(wxT("/SamplingRate/DefaultProjectSampleRate"),
      config.audioOutputSampleRate > 0 ? config.audioOutputSampleRate : 44100);
   // Phone microphones are mono
   p.Write(wxT("/AudioIO/RecordChannels"), 1);
   // Desktop-tuned -130 ms; the audio module measures the real value
   p.Write(wxT("/AudioIO/LatencyCorrection"), 0.0);
   // Most intuitive solo behaviour on touch (API.md §5.1)
   p.Write(wxT("/GUI/Solo"), wxT("Simple"));
   // No updater / telemetry
   p.Write(wxT("/Update/DefaultUpdatesChecking"), false);
   p.Write(wxT("/Update/UpdatesChecking"), false);
   // FindDefaultPath fallbacks would be <home>/Documents
   const auto projects = FromUtf8(config.paths.projectsDir);
   using namespace FileNames;
   for (auto op : { Operation::Open, Operation::Save, Operation::Import,
                    Operation::Export })
      p.Write(PreferenceKey(op, PathType::User), projects);
}

void PopulatePreferences(const StartConfig &config)
{
   auto &p = *gPrefs;
   const bool firstRun = !p.HasEntry(wxT("/Version/Major"));
   long vMajor = 0, vMinor = 0, vMicro = 0;
   p.Read(wxT("/Version/Major"), &vMajor);
   p.Read(wxT("/Version/Minor"), &vMinor);
   p.Read(wxT("/Version/Micro"), &vMicro);
   SetPreferencesVersion(int(vMajor), int(vMinor), int(vMicro));
   p.Write(wxT("/Locale/Language"), LanguageCode(config.locale));
   if (firstRun)
      WriteMobileDefaults(config);
   p.Write(wxT("/PrefsVersion"), wxString(wxT(AUDACITY_PREFS_VERSION_STRING)));
   p.Write(wxT("/Version/Major"), long(AUDACITY_VERSION));
   p.Write(wxT("/Version/Minor"), long(AUDACITY_RELEASE));
   p.Write(wxT("/Version/Micro"), long(AUDACITY_REVISION));
   p.Flush();
}

// Port of AudacityApp::InitTempDir without dialogs
void InitTempDir(const std::string &sessionDir)
{
   const auto wanted = FromUtf8(sessionDir);
   struct stat st {};
   if (::lstat(sessionDir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode) ||
       st.st_uid != ::geteuid())
      throw Fatal("unusable temporary directory " + sessionDir);
   ::chmod(sessionDir.c_str(), 0700);
   FileNames::UpdateDefaultPath(FileNames::Operation::Temp, wanted);
   TempDirectory::ResetTempDir();
   if (!wxFileName::DirName(TempDirectory::TempDir())
          .SameAs(wxFileName::DirName(wanted)))
      throw Fatal("temporary directory could not be set to " + sessionDir);
}

//! Identifies this build of the engine (changes with every rebuild of this
//! file, which is part of every engine build)
const char *EngineBuildId()
{
   return AUBRIDGE_ENGINE_VERSION " " __DATE__ " " __TIME__;
}

void ResetPluginRegistryOnUpgrade()
{
   const wxString key = wxT("/Android/EngineBuild");
   const wxString build = FromUtf8(EngineBuildId());
   if (gPrefs->Read(key, wxString{}) == build)
      return;
   const auto registry = FileNames::PluginRegistry();
   if (wxFileName::FileExists(registry))
      wxRemoveFile(registry);
   gPrefs->Write(key, build);
   gPrefs->Flush();
}

// ---------------------------------------------------------------------------
// Self checks (init-and-project.md §6.3)
// ---------------------------------------------------------------------------
void Check(json &checks, const char *name, bool ok, const std::string &message = {})
{
   json c{ { "name", name }, { "ok", ok } };
   if (!message.empty())
      c["message"] = message;
   checks.push_back(std::move(c));
}

json RunSelfChecks(const StartConfig &config)
{
   json checks = json::array();
   Check(checks, "sampleBlockFactory",
      static_cast<bool>(SampleBlockFactory::Factory::Get()),
      "SqliteSampleBlock factory registered");
   {
      const auto types = Importer::Get().GetFileTypes();
      Check(checks, "importers", types.size() > 3,
         std::to_string(types.size() > 3 ? types.size() - 3 : 0) + " import plug-ins");
   }
   {
      size_t n = 0;
      for (auto entry : ExportPluginRegistry::Get()) {
         (void)entry;
         ++n;
      }
      Check(checks, "exporters", n > 0, std::to_string(n) + " export formats");
   }
   {
      size_t n = 0;
      for (auto &plugin : PluginManager::Get().PluginsOfType(PluginTypeEffect)) {
         (void)plugin;
         ++n;
      }
      Check(checks, "effects", n > 0, std::to_string(n) + " effects");
   }
   {
      const bool found = wxFileName::FileExists(
         FromUtf8(config.paths.nyquistDir + "/nyquist.lsp"));
      Check(checks, "nyquistRuntime", found, config.paths.nyquistDir);
   }
   Check(checks, "tempDir",
      wxFileName::DirName(TempDirectory::TempDir()).SameAs(
         wxFileName::DirName(FromUtf8(config.paths.sessionDir))),
      ToUtf8(TempDirectory::TempDir()));
   Check(checks, "configDir",
      wxFileName::DirName(FileNames::ConfigDir()).SameAs(
         wxFileName::DirName(FromUtf8(config.paths.configDir))),
      ToUtf8(FileNames::ConfigDir()));
   try {
      // Builds every attached object once; throws if a factory is missing
      InvisibleTemporaryProject probe;
      Check(checks, "projectAttachments", true);
   }
   catch (const std::exception &e) {
      Check(checks, "projectAttachments", false, e.what());
   }
   catch (...) {
      Check(checks, "projectAttachments", false, "exception");
   }
   return checks;
}

void EngineTick()
{
   if (wxTheApp) {
      wxTheApp->ProcessPendingEvents();
      // wxIdleEvent, wxLog::FlushActive (log records of other threads),
      // pending object deletion
      wxTheApp->ProcessIdle();
   }
   wxLog::FlushActive();
   HandleIdle();
   // ≤ 5 Hz snapshots for model changes outside commands (recording, ...)
   Session::Get().FlushPendingSnapshot(200);
}

// ---------------------------------------------------------------------------
// Bootstrap / shutdown (engine thread)
// ---------------------------------------------------------------------------
void Bootstrap(const std::string &configJson)
{
   auto &state = TheState();
   state.selfChecks = json::array();
   try {
      auto config = StartConfig::Parse(configJson);
      const auto &paths = config.paths;

      // 0. Process environment: before wx, SQLite and FileNames caching
      PrepareEnvironment(paths);
      Session::Get().SetConfig(config);
      std::srand(unsigned(std::time(nullptr)));

      // 1. wxBase on THIS thread (wxIsMainThread() is true here only)
      InitWx(config);

      // 2. UI services (AudacityApp::OnInit0)
      BasicUI::Install(&UiServices::Instance());
      state.uiInstalled = true;
      state.placementScope = std::make_unique<WindowPlacementFactory::Scope>(
         [](AudacityProject &) -> std::unique_ptr<const BasicUI::WindowPlacement> {
            return std::make_unique<BasicUI::WindowPlacement>();
         });

      // 3. SQLite (fatal on failure)
      if (!ProjectFileIO::InitializeSQL())
         throw Fatal("SQLite failed to initialize");

      // 4. Logger
      InstallLogger();

      // 5. Search paths + default temp dir (instead of InitializePathList)
      {
         FilePaths pathList;
         const auto add = [&](const std::string &dir) {
            FileNames::AddUniquePathToPathList(FromUtf8(dir), pathList);
         };
         // <entry>/nyquist/nyquist.lsp and <entry>/plug-ins/*.ny
         add(ToUtf8(wxPathOnly(FromUtf8(paths.nyquistDir))));
         add(ToUtf8(wxPathOnly(FromUtf8(paths.pluginsDir))));
         FileNames::AddUniquePathToPathList(FileNames::DataDir(), pathList);
         add(paths.configDir + "/locale");
         FileNames::SetAudacityPathList(std::move(pathList));
      }
      TempDirectory::SetDefaultTempDir(FromUtf8(paths.sessionDir));

      // 6. Preferences (audacity.cfg in FileNames::Configuration())
      state.settingsScope =
         std::make_unique<audacity::ApplicationSettings::Scope>([] {
            return MakeFileSettings(FileNames::Configuration());
         });
      InitPreferences(audacity::ApplicationSettings::Call());
      state.prefsInitialized = true;
      PopulatePreferences(config);
      Languages::SetLang(FileNames::AudacityPathList(),
         LanguageCode(config.locale));
      // wxLocale set LC_ALL for the UI language (on bionic only C/C.UTF-8
      // exist).  The engine formats no UI numbers: keep "." as the decimal
      // separator everywhere (config files, Internat), and UTF-8 multibyte
      // conversions on Android.
#if defined(__ANDROID__)
      std::setlocale(LC_CTYPE, "C.UTF-8");
#endif
      std::setlocale(LC_NUMERIC, "C");
      Internat::Init();

      // 7. TempDir
      InitTempDir(paths.sessionDir);

      // 7b. Spine commands and feature modules
      auto &registry = ModuleRegistry::Get();
      registry.Clear();
      RegisterSpineCommands(registry);
      RegisterEditModule(registry);
      RegisterEffectsModule(registry);
      RegisterIoModule(registry);
      RegisterAudioModule(registry);
      RegisterDisplayModule(registry);

      // 8. Plug-ins (effects).  The plug-in registry caches effect
      // descriptors; drop it when the engine build changed (the set of
      // built-in effects may differ)
      ResetPluginRegistryOnUpgrade();
      registry.RunBeforePluginManagerInit();
      PluginManager::Get().Initialize([](const FilePath &localFileName) {
         return MakeFileSettings(localFileName);
      });
      state.pluginsInitialized = true;

      // 9. Audio
      InitDitherers();
      AudioIO::Init();
      state.audioInitialized = true;

      // 10. Import/export registries
      registry.RunBeforeImportExportInit();
      Importer::Get().Initialize();
      state.importerInitialized = true;
      if (!sExportersInitialized) {
         ExportPluginRegistry::Get().Initialize();
         sExportersInitialized = true;
      }

      // 11. Self checks, app-initialized hooks, early CallAfter work
      state.selfChecks = RunSelfChecks(config);
      state.appEvents.HandleAppInitialized();
      BasicUI::Yield();
      gPrefs->Flush();
      EngineThread::Get().AddTickHandler(&EngineTick);
      registry.RunAfterBootstrap();

      // 12. Recovery candidates: Kotlin decides; otherwise an empty project
      const auto recoverable = ProjectSession::ScanRecoverable();
      if (recoverable.empty()) {
         try {
            Session::Get().SetCurrent(ProjectSession::CreateNew());
         }
         catch (const std::exception &e) {
            Check(state.selfChecks, "initialProject", false, e.what());
         }
      }
      // Pending CallAfter work of the project creation
      EngineThread::Get().DrainInternal();
      state.ready = true;
      Events::Emit("engine.ready", json{
         { "audacityVersion", "3.7.9" },
         { "selfChecks", state.selfChecks },
         { "recoverable", recoverable.size() } });
      Session::Get().EmitSnapshot();
   }
   catch (const std::exception &e) {
      Events::Emit("engine.failed", json{ { "message", e.what() } });
   }
   catch (...) {
      Events::Emit("engine.failed", json{ { "message", "unknown error" } });
   }
}

void Shutdown()
{
   auto &state = TheState();
   state.ready = false;
   auto &registry = ModuleRegistry::Get();
   try {
      Session::Get().Reset();
      registry.RunBeforeShutdown();
      Clipboard::Get().Clear();
      EngineThread::Get().DrainInternal();
   }
   catch (...) {
   }

   // AudacityApp::OnExit order
   try {
      if (state.prefsInitialized)
         state.appEvents.HandleAppClosing();
      if (state.importerInitialized)
         Importer::Get().Terminate();
      if (state.audioInitialized)
         AudioIO::Deinit();
      if (state.pluginsInitialized)
         PluginManager::Get().Terminate();
      DeinitFFT();
      EngineThread::Get().DrainInternal();
      if (state.prefsInitialized)
         FinishPreferences();
   }
   catch (...) {
   }
   state.importerInitialized = state.audioInitialized =
      state.pluginsInitialized = state.prefsInitialized = false;

   registry.Clear();
   ResetHooks();
   state.settingsScope.reset();
   state.placementScope.reset();
   if (state.uiInstalled) {
      BasicUI::Install(nullptr);
      state.uiInstalled = false;
   }
   if (state.wxInitialized) {
      wxSetAssertHandler(nullptr);
      wxUninitialize();
      state.wxInitialized = false;
   }
}

} // namespace

bool Start(const std::string &configJson, std::shared_ptr<EventSink> sink)
{
   auto &state = TheState();
   std::lock_guard lock{ state.lifecycleMutex };
   auto &engine = EngineThread::Get();
   if (engine.IsRunning())
      return false;
   Events::SetSink(std::move(sink));
   if (!engine.Start([configJson] { Bootstrap(configJson); },
                     [] { Shutdown(); })) {
      Events::SetSink(nullptr);
      return false;
   }
   return true;
}

void Stop()
{
   auto &state = TheState();
   auto &engine = EngineThread::Get();
   if (EngineThread::IsCurrent()) {
      // Cannot join ourselves: just ask the loop to exit
      state.ready = false;
      engine.RequestStop();
      return;
   }
   std::lock_guard lock{ state.lifecycleMutex };
   state.ready = false;
   engine.RequestStop();
   // Release a blocked engine thread (questions, long operations)
   Dialogs::CancelAll();
   UiServices::CancelAllProgress();
   engine.Join();
   Events::SetSink(nullptr);
}

bool IsReady()
{
   return TheState().ready.load();
}

void HandleIdle()
{
   TheState().appEvents.HandleAppIdle();
}

const json &SelfChecks()
{
   return TheState().selfChecks;
}

json ErrorEnvelope(const std::string &code, const std::string &message)
{
   return json{ { "ok", false },
      { "error", json{ { "code", code }, { "message", message } } },
      { "generation", Session::Get().Generation() } };
}

std::string Invoke(const std::string &command, const std::string &argsJson)
{
   if (EngineThread::IsCurrent())
      return Dump(ErrorEnvelope(ErrorCode::INTERNAL,
         "Invoke() must not be called on the engine thread"));
   if (!IsReady())
      return Dump(ErrorEnvelope(ErrorCode::NOT_READY,
         "the engine is not ready"));
   auto promise = std::make_shared<std::promise<std::string>>();
   auto future = promise->get_future();
   if (!EngineThread::Get().PostCommand([promise, command, argsJson] {
          promise->set_value(Dispatch(command, argsJson));
       }))
      return Dump(ErrorEnvelope(ErrorCode::NOT_READY, "the engine is stopping"));
   try {
      return future.get();
   }
   catch (const std::future_error &) {
      return Dump(ErrorEnvelope(ErrorCode::NOT_READY, "the engine stopped"));
   }
}

} // namespace Engine
} // namespace aubridge
