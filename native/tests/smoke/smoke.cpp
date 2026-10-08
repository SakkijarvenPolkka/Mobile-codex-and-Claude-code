/*  SPDX-License-Identifier: GPL-2.0-or-later */
/*
 * Audacity Android port -- host smoke test for the native build.
 *
 * Proves that the link model works:
 *  - wxBase initializes (wxInitializer),
 *  - import plug-ins registered by static initializers in the mod-* shared
 *    libraries are visible through Importer (lib-import-export),
 *  - export plug-ins registered the same way are visible through
 *    ExportPluginRegistry,
 *  - an in-memory SQLite connection opens and closes through
 *    lib-sqlite-helpers,
 *  - ClientData attachments registered in different shared libraries
 *    (ProjectRate, TrackList, Tags, WaveTrackFactory, ...) all resolve on one
 *    AudacityProject (i.e. template statics are unified across .so files),
 *  - a WAV file written with libsndfile imports through mod-pcm into a
 *    project (sample blocks go to the SQLite project database), and a .lof
 *    file naming it imports through mod-lof + lib-app-services.
 *
 * It also shows the minimum bootstrap a client (the bridge) needs: wxBase
 * initialization, BasicUI services, preferences, temp directory, importer and
 * exporter registry initialization, an open project database.
 *
 * Exit code 0 on success.
 */

#include <wx/filename.h>
#include <wx/init.h>
#include <wx/string.h>
#include <wx/utils.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <optional>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <variant>
#include <vector>

#include "BasicSettings.h"
#include "BasicUI.h"
#include "ExportPlugin.h"
#include "ExportPluginRegistry.h"
#include "FileNames.h"
#include "Import.h"
#include "Prefs.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "ProjectRate.h"
#include "Tags.h"
#include "Track.h"
#include "UndoManager.h"
#include "WaveTrack.h"
#include "AcidizerTags.h"
#include "TempDirectory.h"
#include "sqlite/Connection.h"

#include <sndfile.h>

namespace {

std::string Utf8(const wxString &s) { return std::string(s.utf8_str()); }

//! Minimal in-memory audacity::BasicSettings (the app uses a persistent one)
class MemorySettings final : public audacity::BasicSettings
{
   using Value = std::variant<wxString, bool, int, long, long long, double>;
   std::map<wxString, Value> mStorage;
   std::vector<wxString> mGroups{ "/" };

   wxString Path(const wxString &key) const
   {
      if (key.StartsWith("/"))
         return key;
      wxString group = mGroups.back();
      if (!group.EndsWith("/"))
         group += "/";
      return group + key;
   }

   template<typename T> bool DoRead(const wxString &key, T *value) const
   {
      auto it = mStorage.find(Path(key));
      if (it == mStorage.end())
         return false;
      if (auto p = std::get_if<T>(&it->second)) {
         *value = *p;
         return true;
      }
      return false;
   }

   bool DoWrite(const wxString &key, Value value)
   {
      mStorage[Path(key)] = std::move(value);
      return true;
   }

public:
   wxString GetGroup() const override { return mGroups.back(); }
   wxArrayString GetChildGroups() const override { return {}; }
   wxArrayString GetChildKeys() const override { return {}; }
   bool HasEntry(const wxString &key) const override
   { return mStorage.count(Path(key)) > 0; }
   bool HasGroup(const wxString &) const override { return false; }
   bool Remove(const wxString &key) override
   { return mStorage.erase(Path(key)) > 0; }
   void Clear() override { mStorage.clear(); }

   bool Read(const wxString &k, bool *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, int *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, long *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, long long *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, double *v) const override { return DoRead(k, v); }
   bool Read(const wxString &k, wxString *v) const override { return DoRead(k, v); }

   bool Write(const wxString &k, bool v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, int v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, long v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, long long v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, double v) override { return DoWrite(k, v); }
   bool Write(const wxString &k, const wxString &v) override { return DoWrite(k, v); }

   bool Flush() noexcept override { return true; }

protected:
   void DoBeginGroup(const wxString &prefix) override
   { mGroups.push_back(Path(prefix)); }
   void DoEndGroup() noexcept override { mGroups.pop_back(); }
};

//! Headless audacity BasicUI services (the app installs real ones).
//! Without any installed services, code such as DBConnection::Close() gets a
//! null progress dialog from MakeGenericProgress() and crashes.
class HeadlessServices final : public BasicUI::Services
{
   struct Progress final : BasicUI::ProgressDialog {
      BasicUI::ProgressResult Poll(unsigned long long, unsigned long long,
         const TranslatableString &) override
      { return BasicUI::ProgressResult::Success; }
      void SetMessage(const TranslatableString &) override {}
      void SetDialogTitle(const TranslatableString &) override {}
      void Reinit() override {}
   };
   struct GenericProgress final : BasicUI::GenericProgressDialog {
      BasicUI::ProgressResult Pulse() override
      { return BasicUI::ProgressResult::Success; }
   };
   std::vector<BasicUI::Action> mPending;
   const std::thread::id mMainThread = std::this_thread::get_id();
public:
   void DoCallAfter(const BasicUI::Action &action) override
   { mPending.push_back(action); }
   void DoYield() override { DoProcessIdle(); }
   void DoProcessIdle() override
   {
      auto pending = std::move(mPending);
      mPending.clear();
      for (auto &action : pending)
         if (action)
            action();
   }
   void DoShowErrorDialog(const BasicUI::WindowPlacement &,
      const TranslatableString &title, const TranslatableString &message,
      const ManualPageID &, const BasicUI::ErrorDialogOptions &) override
   {
      std::fprintf(stderr, "[error dialog] %s: %s\n",
         Utf8(title.Translation()).c_str(), Utf8(message.Translation()).c_str());
   }
   BasicUI::MessageBoxResult DoMessageBox(const TranslatableString &message,
      BasicUI::MessageBoxOptions) override
   {
      std::fprintf(stderr, "[message box] %s\n",
         Utf8(message.Translation()).c_str());
      return BasicUI::MessageBoxResult::Ok;
   }
   std::unique_ptr<BasicUI::ProgressDialog> DoMakeProgress(
      const TranslatableString &, const TranslatableString &, unsigned,
      const TranslatableString &) override
   { return std::make_unique<Progress>(); }
   std::unique_ptr<BasicUI::GenericProgressDialog> DoMakeGenericProgress(
      const BasicUI::WindowPlacement &, const TranslatableString &,
      const TranslatableString &, int) override
   { return std::make_unique<GenericProgress>(); }
   int DoMultiDialog(const TranslatableString &, const TranslatableString &,
      const TranslatableStrings &, const ManualPageID &,
      const TranslatableString &, bool) override
   { return 0; }
   bool DoOpenInDefaultBrowser(const wxString &) override { return false; }
   std::unique_ptr<BasicUI::WindowPlacement> DoFindFocus() override
   { return std::make_unique<BasicUI::WindowPlacement>(); }
   void DoSetFocus(const BasicUI::WindowPlacement &) override {}
   bool IsUsingRtlLayout() const override { return false; }
   bool IsUiThread() const override
   { return std::this_thread::get_id() == mMainThread; }
};

//! Whether the build included the named module (AUDACITY_SKIPPED_MODULES)
bool ModuleBuilt(const char *name)
{
   return std::string(SMOKE_BUILT_MODULES).find(
      std::string(" ") + name + " ") != std::string::npos;
}

int failures = 0;
void Check(bool ok, const char *what)
{
   std::printf("[%s] %s\n", ok ? " OK " : "FAIL", what);
   if (!ok)
      ++failures;
}

} // namespace

int main(int argc, char **argv)
{
   wxInitializer initializer(argc, argv);
   Check(initializer.IsOk(), "wxInitializer (wxBase " wxVERSION_NUM_DOT_STRING ")");
   if (!initializer.IsOk())
      return 1;

   HeadlessServices uiServices;
   BasicUI::Install(&uiServices);
   InitPreferences(std::make_unique<MemorySettings>());

   // ---- Import plug-ins -------------------------------------------------
   Importer::Get().Initialize();
   const auto fileTypes = Importer::Get().GetFileTypes();
   std::printf("\nImport file types (Importer::GetFileTypes, %zu entries):\n",
      fileTypes.size());
   for (const auto &type : fileTypes) {
      std::string exts;
      for (const auto &e : type.extensions)
         exts += (exts.empty() ? "" : " ") + Utf8(e);
      if (exts.size() > 70)
         exts = exts.substr(0, 67) + "...";
      std::printf("  %-38s %s\n", Utf8(type.description.Translation()).c_str(),
         exts.c_str());
   }
   // "All files", "All supported files", "Audacity projects" + one per plug-in
   Check(fileTypes.size() > 3, "import plug-ins registered by modules");
   {
      // Every import module must have registered its plug-in: look for one
      // characteristic extension per module in the per-plug-in entries.
      const char *const expected[][2] = {
         { "mod-pcm", "wav" }, { "mod-ogg", "ogg" }, { "mod-flac", "flac" },
         { "mod-mpg123", "mp3" }, { "mod-lof", "lof" }, { "mod-wavpack", "wv" },
         { "mod-opus", "opus" }, { "mod-aup", "aup" },
      };
      for (const auto &e : expected) {
         if (!ModuleBuilt(e[0]))
            continue;
         bool found = false;
         for (size_t i = 3; i < fileTypes.size() && !found; ++i)
            for (const auto &ext : fileTypes[i].extensions)
               if (ext.IsSameAs(e[1], false))
                  found = true;
         const std::string what =
            std::string("importer of ") + e[0] + " (." + e[1] + ")";
         Check(found, what.c_str());
      }
   }

   // ---- Export plug-ins -------------------------------------------------
   auto &exporters = ExportPluginRegistry::Get();
   exporters.Initialize();
   std::printf("\nExport formats (ExportPluginRegistry):\n");
   int nFormats = 0;
   std::vector<std::string> formatNames;
   for (auto [plugin, index] : exporters) {
      const auto info = plugin->GetFormatInfo(index);
      formatNames.push_back(Utf8(info.format));
      std::string exts;
      for (const auto &e : info.extensions)
         exts += (exts.empty() ? "" : " ") + Utf8(e);
      std::printf("  %-10s %-44s [%s] max %u ch\n", Utf8(info.format).c_str(),
         Utf8(info.description.Translation()).c_str(), exts.c_str(),
         info.maxChannels);
      ++nFormats;
   }
   Check(nFormats > 0, "export plug-ins registered by modules");
   {
      // Every export module must have registered its plug-in
      const char *const expected[][2] = {
         { "mod-pcm", "WAV" }, { "mod-mp3", "MP3" }, { "mod-ogg", "OGG" },
         { "mod-opus", "Opus" }, { "mod-flac", "FLAC" },
         { "mod-wavpack", "WavPack" }, { "mod-mp2", "MP2" },
      };
      for (const auto &e : expected) {
         if (!ModuleBuilt(e[0]))
            continue;
         const bool found = std::find(formatNames.begin(), formatNames.end(),
            std::string(e[1])) != formatNames.end();
         const std::string what =
            std::string("exporter of ") + e[0] + " (" + e[1] + ")";
         Check(found, what.c_str());
      }
   }

   // ---- SQLite through lib-sqlite-helpers ---------------------------------
   {
      namespace sqlite = audacity::sqlite;
      auto connection =
         sqlite::Connection::Open(":memory:", sqlite::OpenMode::Memory);
      Check(bool(connection), "sqlite: open in-memory connection");
      if (connection) {
         Check(!!connection->Execute(
            "CREATE TABLE smoke (id INTEGER PRIMARY KEY, name TEXT);"
            "INSERT INTO smoke (name) VALUES ('audacity');"),
            "sqlite: create table + insert");
         Check(connection->CheckTableExists("smoke"), "sqlite: table exists");
         {
            // Statements must be finalized before the connection closes
            auto stmt = connection->CreateStatement("SELECT sqlite_version();");
            if (stmt) {
               auto run = stmt->Prepare().Run();
               for (auto row : run) {
                  std::string version;
                  if (row.Get(0, version))
                     std::printf("  SQLite version %s\n", version.c_str());
               }
            }
         }
         Check(!connection->Close(true).IsError(), "sqlite: close connection");
      }
   }

   // ---- ClientData attachments across shared libraries --------------------
   {
      auto project = AudacityProject::Create();
      auto &rate = ProjectRate::Get(*project);      // lib-project-rate
      auto &tracks = TrackList::Get(*project);      // lib-track
      auto &tags = Tags::Get(*project);             // lib-tags
      auto &factory = WaveTrackFactory::Get(*project); // lib-wave-track
      auto track = factory.Create();
      tracks.Add(track);
      std::printf("\nProject: rate %.0f Hz, %zu track(s), tags %s\n",
         rate.GetRate(), tracks.Size(), tags.IsEmpty() ? "empty" : "set");
      Check(&ProjectRate::Get(*project) == &rate &&
            &TrackList::Get(*project) == &tracks &&
            &Tags::Get(*project) == &tags &&
            tracks.Size() == 1,
         "attached objects from several .so resolve on one project");
      tracks.Clear();
   }

   // ---- Import through the modules ----------------------------------------
   {
      // The application normally sets this up (AudacityApp::InitTempDir)
      const wxString tempDir = wxFileName::CreateTempFileName("audacity-smoke");
      wxRemoveFile(tempDir);
      wxFileName::Mkdir(tempDir, 0700, wxPATH_MKDIR_FULL);
      TempDirectory::SetDefaultTempDir(tempDir);
      FileNames::UpdateDefaultPath(FileNames::Operation::Temp, tempDir);

      // 0.5 s of a 440 Hz sine, mono, 44.1 kHz, 16-bit WAV
      const wxString wavPath = tempDir + "/tone.wav";
      SF_INFO info{};
      info.samplerate = 44100;
      info.channels = 1;
      info.format = SF_FORMAT_WAV | SF_FORMAT_PCM_16;
      if (auto sf = sf_open(wavPath.utf8_str(), SFM_WRITE, &info)) {
         std::vector<float> samples(22050);
         for (size_t i = 0; i < samples.size(); ++i)
            samples[i] = 0.5f * std::sin(2 * M_PI * 440.0 * i / 44100.0);
         sf_writef_float(sf, samples.data(), samples.size());
         sf_close(sf);
      }

      auto project = AudacityProject::Create();
      // Like ProjectManager::New(): open the (temporary) project database
      // that receives the sample blocks
      Check(ProjectFileIO::Get(*project).OpenProject(),
         "open temporary project database (lib-project-file-io)");
      TrackHolders newTracks;
      std::optional<LibFileFormats::AcidizerTags> acidTags;
      TranslatableString errorMessage;
      const bool ok = Importer::Get().Import(*project, wavPath, nullptr,
         &WaveTrackFactory::Get(*project), newTracks, &Tags::Get(*project),
         acidTags, errorMessage);
      double duration = 0;
      for (auto &t : newTracks)
         duration = std::max(duration, t->GetEndTime());
      std::printf("\nImported %s: %s, %zu track(s), %.3f s %s\n",
         Utf8(wavPath).c_str(), ok ? "ok" : "FAILED", newTracks.size(), duration,
         Utf8(errorMessage.Translation()).c_str());
      Check(ok && newTracks.size() == 1 && std::fabs(duration - 0.5) < 1e-6,
         "import WAV through mod-pcm");

      // A .lof ("list of files") naming the WAV: mod-lof asks the
      // application (lib-app-services) to open it into the project.
      // (mod-lof is left out of Android builds by default.)
      auto &tracks = TrackList::Get(*project);
      TrackHolders lofTracks;
      if (ModuleBuilt("mod-lof")) {
         const wxString lofPath = tempDir + "/list.lof";
         if (auto f = std::fopen(lofPath.utf8_str(), "w")) {
            std::fprintf(f, "file \"%s\" offset 1.0\n", (const char*)wavPath.utf8_str());
            std::fclose(f);
         }
         const bool lofOk = Importer::Get().Import(*project, lofPath, nullptr,
            &WaveTrackFactory::Get(*project), lofTracks, &Tags::Get(*project),
            acidTags, errorMessage);
         std::printf("Imported %s: %s, project now has %zu track(s), end %.3f s\n",
            Utf8(lofPath).c_str(), lofOk ? "ok" : "FAILED", tracks.Size(),
            tracks.GetEndTime());
         Check(lofOk && tracks.Size() == 1 && std::fabs(tracks.GetEndTime() - 1.5) < 1e-6,
            "import LOF through mod-lof + lib-app-services (offset applied)");
      }
      // Close like ProjectManager::OnCloseWindow(): drop undo states and
      // tracks (they own sample blocks) before closing the database
      ProjectFileIO::Get(*project).SetBypass();
      UndoManager::Get(*project).ClearStates();
      newTracks.clear();
      lofTracks.clear();
      tracks.Clear();
      ProjectFileIO::Get(*project).CloseProject();
      WaveTrackFactory::Destroy(*project);
      project.reset();
      wxFileName::Rmdir(tempDir, wxPATH_RMDIR_RECURSIVE);
   }

   uiServices.DoProcessIdle();
   FinishPreferences();
   BasicUI::Install(nullptr);

   std::printf("\nsmoke: %s\n", failures ? "FAILED" : "PASSED");
   return failures ? 1 : 0;
}
