/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ImportCommands.cpp

  import.formats and import.files (API.md §3.3 "import / export"), and the
  bridge's import routine for lib-app-services (mod-aup / mod-lof).

  The import itself is a port of the non-GUI parts of Audacity 3.7.9
  src/ProjectFileManager.cpp -- the ImportProgress listener,
  ProjectFileManager::Import / ImportWithoutTempoDetection / DoImport /
  AddImportedTracks -- and of src/menus/FileMenus.cpp DoImport (file
  order).  Its original header:

     Audacity: A Digital Audio Editor
     ProjectFileManager.cpp
     Paul Licameli split from AudacityProject.cpp

  Differences from the desktop (see API.md "import / export"):
   * the ImportStreamDialog is a `multiChoice` dialog (Dialogs::ChooseMany);
   * progress is a `progress` event (cancel / stop through CancelProgress);
   * errors are returned in the response instead of error dialogs; a batch
     goes on after a file that fails (its message is in `messages`);
   * tempo detection (MIR) is not ported (3.7.9's "non-tempo" path);
   * the first import into an empty project adopts the imported rate as the
     project rate (as Audacity 2.x did; 3.7.9 keeps the project rate);
   * imports nested in another import (mod-aup <import>, mod-lof) add their
     tracks without an undo state of their own: every file named in
     import.files is exactly one undo state.

**********************************************************************/
#include "IoModule.h"

#include <algorithm>
#include <memory>
#include <optional>
#include <set>

#include <wx/filename.h>
#include <wx/log.h>

#include "AcidizerTags.h"
#include "BasicUI.h"
#include "Edit.h"
#include "Import.h"
#include "ImportPlugin.h"
#include "ImportProgressListener.h"
#include "MemoryX.h"
#include "ModuleRegistry.h"
#include "PlayableTrack.h"
#include "Project.h"
#include "ProjectFileIO.h"
#include "ProjectFileManager.h"   // lib-app-services stand-in (handler)
#include "ProjectHistory.h"
#include "ProjectRate.h"
#include "ProjectSession.h"
#include "ProjectTimeSignature.h"
#include "Session.h"
#include "Tags.h"
#include "TempoChange.h"
#include "Track.h"
#include "TrackFocus.h"
#include "UiServices.h"
#include "WaveClip.h"
#include "WaveTrack.h"

#ifdef __ANDROID__
#include "android/AndroidCodecs.h"
#endif

namespace aubridge {
namespace io {

namespace {

//! Depth of Importer::Import calls made by this file (nested imports of
//! mod-aup / mod-lof come back through the lib-app-services handler)
int sImportDepth = 0;

struct DepthGuard {
   DepthGuard() { ++sImportDepth; }
   ~DepthGuard() { --sImportDepth; }
   DepthGuard(const DepthGuard &) = delete;
   DepthGuard &operator=(const DepthGuard &) = delete;
};

ProjectFileManager::ImportHandler sPreviousHandler;
bool sHandlerInstalled = false;

std::string Lower(std::string s)
{
   std::transform(s.begin(), s.end(), s.begin(),
      [](unsigned char c) { return char(std::tolower(c)); });
   return s;
}

std::set<int64_t> TrackIds(AudacityProject &project)
{
   std::set<int64_t> ids;
   for (auto track : TrackList::Get(project))
      ids.insert(TrackIdValue(*track));
   return ids;
}

// ---------------------------------------------------------------------------
// The listener: port of ImportProgress (ProjectFileManager.cpp) with the
// ImportStreamDialog replaced by a multiChoice dialog and the progress
// dialog by a ProgressScope
// ---------------------------------------------------------------------------
class BridgeImportListener final : public ImportProgressListener
{
public:
   BridgeImportListener(AudacityProject &project, wxString displayName)
      : mProject{ project }, mDisplayName{ std::move(displayName) }
   {}

   bool OnImportFileOpened(ImportFileHandle &handle) override
   {
      mHandle = &handle;
      mScope.reset();

      // Refuse a file that cannot fit into the project's database (a lower
      // bound: the project may store wider samples than the file)
      if (const auto bytes = handle.GetFileUncompressedBytes(); bytes > 0) {
         const auto free = ProjectFileIO::Get(mProject).GetFreeDiskSpace();
         if (free >= 0 && wxULongLong(bytes) > wxULongLong(free.GetValue())) {
            mNoSpace = true;
            return false;
         }
      }

      const wxInt32 count = handle.GetStreamCount();
      if (count > 1) {
         // ImportStreamDialog: every stream unused, then the chosen ones
         std::vector<std::string> choices;
         const auto &infos = handle.GetStreamInfo();
         for (wxInt32 i = 0; i < count; ++i)
            choices.push_back(size_t(i) < infos.size()
               ? Translated(infos[size_t(i)])
               : std::to_string(i + 1));
         auto chosen = Dialogs::ChooseMany(
            Translated(XO("Select stream(s) to import")),
            ToUtf8(mDisplayName), choices,
            std::vector<bool>(size_t(count), true));
         // An empty choice imports nothing: same as cancelling
         if (!chosen || chosen->empty()) {
            mUserCancelled = true;
            return false;
         }
         for (wxInt32 i = 0; i < count; ++i)
            handle.SetStreamUsage(i, false);
         for (const int i : *chosen)
            if (i >= 0 && i < count)
               handle.SetStreamUsage(i, true);
      }
      else
         // One stream: import it (the OGG importer defaults to "unused")
         handle.SetStreamUsage(0, true);

      mScope = std::make_unique<ProgressScope>(
         Translated(XO("Importing %s").Format(handle.GetFileDescription())),
         ToUtf8(mDisplayName), true, true);
      return true;
   }

   void OnImportProgress(double progress) override
   {
      if (!mHandle || !mScope)
         return;
      switch (mScope->Update(std::clamp(progress, 0.0, 1.0))) {
      case BasicUI::ProgressResult::Cancelled:
         mUserCancelled = true;
         mHandle->Cancel();
         break;
      case BasicUI::ProgressResult::Stopped:
         mHandle->Stop();
         break;
      default:
         break;
      }
   }

   void OnImportResult(ImportResult result) override
   {
      mScope.reset();
      if (result == ImportResult::Cancelled)
         mUserCancelled = true;
      if (result == ImportResult::Error && mHandle) {
         auto message = mHandle->GetErrorMessage();
         if (!message.empty())
            mErrors.push_back(std::move(message));
      }
   }

   bool UserCancelled() const { return mUserCancelled; }
   bool NoSpace() const { return mNoSpace; }
   const std::vector<TranslatableString> &Errors() const { return mErrors; }

private:
   AudacityProject &mProject;
   const wxString mDisplayName;
   ImportFileHandle *mHandle{};   // valid only inside Importer::Import
   std::unique_ptr<ProgressScope> mScope;
   std::vector<TranslatableString> mErrors;
   bool mUserCancelled = false;
   bool mNoSpace = false;
};

//! The message of a failed import: the importers' own error messages, then
//! Importer::Import's.  The staged path is shown as the file name.
std::string FailureMessage(const BridgeImportListener &listener,
   const TranslatableString &errorMessage, const FilePath &path,
   const wxString &displayName)
{
   if (listener.NoSpace())
      return Translated(XO("Insufficient Disk Space"));
   wxString text;
   for (const auto &error : listener.Errors()) {
      if (!text.empty())
         text += wxT("\n\n");
      text += error.Translation();
   }
   auto library = errorMessage.Translation();
#ifdef __ANDROID__
   // Importer::Import's text for aac/m4a/m4r/mp4 is about FFmpeg, which the
   // port replaces with the platform codecs (io/android)
   const auto ext = path.AfterLast(wxT('.')).Lower();
   if (ext == wxT("aac") || ext == wxT("m4a") || ext == wxT("m4r") ||
       ext == wxT("mp4"))
      library = text.empty()
         ? wxString::Format(
              wxT("The Android media codecs could not decode \"%s\"."), path)
         : wxString{};
#endif
   if (!library.empty()) {
      if (!text.empty())
         text += wxT("\n\n");
      text += library;
   }
   if (text.empty())
      text = XO("Import").Translation() + wxT(": ") + path;
   text.Replace(path, displayName);
   return ToUtf8(text);
}

//! port of ProjectFileManager::AddImportedTracks; `pushState` false for
//! nested imports (their file's import pushes)
void AddImportedTracks(AudacityProject &project, const FilePath &fileName,
   const wxString &displayName, TrackHolders &&newTracks, bool pushState)
{
   auto &tracks = TrackList::Get(project);
   auto &projectFileIO = ProjectFileIO::Get(project);

   std::vector<WaveTrack *> results;

   // SelectUtilities::SelectNone (src) without the TrackPanel refresh
   if (pushState)
      for (auto track : tracks)
         track->SetSelected(false);

   const wxFileName fn{ fileName };
   const bool initiallyEmpty = tracks.empty();
   const wxString trackNameBase = fn.GetName();

   // Fix the bug 2109.
   // In case the project had soloed tracks before importing,
   // all newly imported tracks are muted.
   const bool projectHasSolo =
      !(tracks.Any<PlayableTrack>() + &PlayableTrack::GetSolo).empty();
   if (projectHasSolo) {
      for (auto &group : newTracks)
         if (auto pTrack = dynamic_cast<PlayableTrack *>(group.get()))
            pTrack->SetMute(true);
   }

   for (auto &group : newTracks) {
      if (auto pTrack = dynamic_cast<WaveTrack *>(group.get()))
         results.push_back(pTrack);
      tracks.Add(group);
   }
   newTracks.clear();

   // Now name them

   // Add numbers to track names only if there is more than one (mono or
   // stereo) track (not necessarily, more than one channel)
   const bool useSuffix = results.size() > 1;

   int i = -1;
   for (const auto &newTrack : results) {
      ++i;
      newTrack->SetSelected(true);
      if (useSuffix)
         //i18n-hint Name default name assigned to a clip on track import
         newTrack->SetName(XC("%s %d", "clip name template")
            .Format(trackNameBase, i + 1).Translation());
      else
         newTrack->SetName(trackNameBase);

      const auto trackName = newTrack->GetName();
      for (const auto &interval : newTrack->Intervals())
         interval->SetName(trackName);
   }

   if (!pushState)
      return;

   // Android port: the first import into an empty project sets the project
   // rate to the rate of the imported audio ("Automatically assign rate of
   // imported file to whole project, if this is the first file that is
   // imported" -- Audacity 2.x AddImportedTracks; 3.7.9 dropped it).  The
   // rate is saved with the undo state's autosave below.
   if (initiallyEmpty && !results.empty()) {
      const double newRate = results.front()->GetRate();
      auto &projectRate = ProjectRate::Get(project);
      if (newRate > 0 && newRate != projectRate.GetRate())
         projectRate.SetRate(newRate);
   }

   ProjectHistory::Get(project).PushState(
      XO("Imported '%s'").Format(displayName), XO("Import"));

   // If the project was clean and temporary (not permanently saved), then set
   // the filename to the just imported path.
   if (initiallyEmpty && projectFileIO.IsTemporary()) {
      project.SetProjectName(fn.GetName());
      projectFileIO.SetProjectTitle();
   }
}

//! Checks that the name can be imported at all; throws the error envelope
void CheckImportable(const FilePath &path)
{
   const auto name = wxFileName(path).GetFullName();
   // ProjectFileManager::DoImport (without EXPERIMENTAL_IMPORT_AUP3)
   if (path.AfterLast(wxT('.')).IsSameAs(wxT("aup3"), false))
      Fail(ErrorCode::INVALID_ARGS, Translated(
         XO("Cannot import AUP3 format.  Use File > Open instead")));
   if (wxDirExists(path))
      Fail(ErrorCode::INVALID_ARGS, ToUtf8(name) + " is a directory");
   if (!wxFileExists(path))
      Fail(ErrorCode::NOT_FOUND,
         Translated(XO("File \"%s\" not found.").Format(name)));
}

//! port of ProjectFileManager::DoImport for one file of import.files (or a
//! top-level call of the lib-app-services handler): one undo state.
//! @return the ids of the tracks the project gained
//! @throws BridgeError (CANCELLED, FAILED, NOT_FOUND, INVALID_ARGS) or a
//!   library exception; the project is rolled back in every case
std::vector<int64_t> ImportOne(AudacityProject &project, const FilePath &path)
{
   CheckImportable(path);
   const wxString displayName = wxFileName(path).GetFullName();
   const wxString extension = path.AfterLast(wxT('.'));
   const auto before = TrackIds(project);
   const bool initiallyEmpty = TrackList::Get(project).empty();

   RunEditSelf(project, [&]() -> bool {
      DepthGuard depth;
      auto &projectFileIO = ProjectFileIO::Get(project);

      // Backup Tags, before the import.  Be prepared to roll back changes.
      auto oldTags = Tags::Get(project).shared_from_this();
      bool committed = false;
      auto cleanup = finally([&] {
         if (!committed)
            Tags::Set(project, oldTags);
      });
      auto newTags = oldTags->Duplicate();
      Tags::Set(project, newTags);

      TrackHolders newTracks;
      TranslatableString errorMessage;
      std::optional<LibFileFormats::AcidizerTags> acidTags;
      BridgeImportListener listener{ project, displayName };
      const bool success = Importer::Get().Import(project, path, &listener,
         &WaveTrackFactory::Get(project), newTracks, newTags.get(), acidTags,
         errorMessage);
      if (!success) {
         // Thrown inside RunEditSelf: rolls back what mod-aup or a nested
         // import may have added already
         if (listener.UserCancelled() && errorMessage.empty())
            Fail(ErrorCode::CANCELLED, Translated(XO("Cancelled")));
         Fail(ErrorCode::FAILED,
            FailureMessage(listener, errorMessage, path, displayName));
      }

      const auto projectTempo = ProjectTimeSignature::Get(project).GetTempo();
      for (auto &track : newTracks)
         DoProjectTempoChange(*track, projectTempo);

      // no more errors, commit
      committed = true;

      // LOF ("list of files"): the listed files were imported (nested)
      if (extension.IsSameAs(wxT("lof"), false)) {
         if (TrackIds(project) == before)
            return false;
         ProjectHistory::Get(project).PushState(
            XO("Imported '%s'").Format(displayName), XO("Import"));
         return true;
      }

      // AUP ("legacy project"): mod-aup added the tracks itself
      if (extension.IsSameAs(wxT("aup"), false)) {
         if (initiallyEmpty && projectFileIO.IsTemporary()) {
            project.SetProjectName(wxFileName(path).GetName());
            projectFileIO.SetProjectTitle();
         }
         ProjectHistory::Get(project).PushState(
            XO("Imported '%s'").Format(displayName), XO("Import"));
         return true;
      }

      // Undo history is incremented inside this:
      AddImportedTracks(project, path, displayName, std::move(newTracks), true);
      return true;
   });

   std::vector<int64_t> added;
   for (auto track : TrackList::Get(project)) {
      const auto id = TrackIdValue(*track);
      if (!before.count(id))
         added.push_back(id);
   }
   return added;
}

//! An import requested by a library (mod-aup <import>, mod-lof) while one
//! of our imports runs: the tracks join that file's undo state
bool NestedImport(AudacityProject &project, const FilePath &path)
{
   if (!wxFileExists(path) ||
       path.AfterLast(wxT('.')).IsSameAs(wxT("aup3"), false))
      return false;
   DepthGuard depth;
   const wxString displayName = wxFileName(path).GetFullName();
   TrackHolders newTracks;
   TranslatableString errorMessage;
   std::optional<LibFileFormats::AcidizerTags> acidTags;
   BridgeImportListener listener{ project, displayName };
   // The outer import's pending Tags object receives the metadata
   const bool success = Importer::Get().Import(project, path, &listener,
      &WaveTrackFactory::Get(project), newTracks, &Tags::Get(project),
      acidTags, errorMessage);
   if (!success) {
      if (!listener.UserCancelled() || !errorMessage.empty())
         BasicUI::ShowErrorDialog({}, XO("Error Importing"),
            Verbatim(FromUtf8(
               FailureMessage(listener, errorMessage, path, displayName))),
            wxT("Importing_Audio"));
      return false;
   }
   const auto projectTempo = ProjectTimeSignature::Get(project).GetTempo();
   for (auto &track : newTracks)
      DoProjectTempoChange(*track, projectTempo);
   AddImportedTracks(project, path, displayName, std::move(newTracks), false);
   return true;
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

//! import.formats -> {groups:[{description, extensions}], extensions}
json ImportFormats(const json &)
{
   const auto types = Importer::Get().GetFileTypes();
   json groups = json::array();
   // [0] all files, [1] all supported files, [2] Audacity projects, then one
   // entry per import plug-in in probing order
   for (size_t i = 3; i < types.size(); ++i) {
      json extensions = json::array();
      std::set<std::string> seen;
      for (const auto &ext : types[i].extensions) {
         auto e = Lower(ToUtf8(ext));
         if (!e.empty() && seen.insert(e).second)
            extensions.push_back(e);
      }
      groups.push_back(json{
         { "description", Translated(types[i].description) },
         { "extensions", std::move(extensions) } });
   }
   json all = json::array();
   if (types.size() > 1) {
      std::set<std::string> seen;
      for (const auto &ext : types[1].extensions) {
         auto e = Lower(ToUtf8(ext));
         // .aup3 is opened with project.open, never imported
         if (!e.empty() && e != "aup3" && seen.insert(e).second)
            all.push_back(e);
      }
   }
   return json{ { "groups", std::move(groups) }, { "extensions", std::move(all) } };
}

//! import.files {paths:[..], newProject?:bool}
json ImportFiles(const json &args)
{
   const auto paths = ArgStringArray(args, "paths");
   if (paths.empty())
      Fail(ErrorCode::INVALID_ARGS, "argument 'paths' must not be empty");
   const bool newProject = OptBool(args, "newProject").value_or(false);

   std::vector<FilePath> files;
   for (const auto &p : paths) {
      const auto path = FromUtf8(p);
      if (path.empty() || !wxFileName(path).IsAbsolute())
         Fail(ErrorCode::INVALID_ARGS, "paths must be absolute: '" + p + "'");
      files.push_back(wxFileName(path).GetFullPath());
   }
   // FileMenus.cpp DoImport / ProjectFileManager::Import: sorted by name
   std::stable_sort(files.begin(), files.end(),
      [](const FilePath &a, const FilePath &b) {
         return wxFileName(a).GetFullName().CmpNoCase(
            wxFileName(b).GetFullName()) < 0;
      });

   // "Open as new project": import into a fresh project that replaces the
   // current one only when something was imported (desktop: the new window
   // is closed again when the import fails)
   std::unique_ptr<ProjectSession> fresh;
   AudacityProject *project = nullptr;
   if (newProject) {
      fresh = ProjectSession::CreateNew();
      project = &fresh->Project();
   }
   else
      project = &Session::Get().RequireProject();

   json trackIds = json::array();
   json messages = json::array();
   std::optional<BridgeError> firstError;
   size_t imported = 0;
   try {
      for (const auto &file : files) {
         try {
            for (const auto id : ImportOne(*project, file))
               trackIds.push_back(id);
            ++imported;
         }
         catch (const BridgeError &e) {
            if (e.code == ErrorCode::CANCELLED)
               throw;
            if (!firstError)
               firstError = e;
            messages.push_back(std::string{ e.what() });
         }
      }
      if (imported == 0)
         throw firstError.value_or(
            BridgeError{ ErrorCode::FAILED, "nothing was imported" });
   }
   catch (...) {
      if (fresh)
         fresh->Close();
      throw;
   }

   // ProjectFileManager::Import's CallAfter: focus the last track (Kotlin
   // owns zooming and scrolling)
   const auto range = TrackList::Get(*project).Any<Track>();
   if (!range.empty())
      TrackFocus::Get(*project).Set(*range.rbegin(), true);

   if (fresh)
      Session::Get().SetCurrent(std::move(fresh));
   return json{ { "trackIds", std::move(trackIds) },
      { "messages", std::move(messages) } };
}

} // namespace

void RegisterImportCommands(ModuleRegistry &registry)
{
   registry.AddCommand("import.formats", ImportFormats);
   // NeedsProject is checked by the handler: newProject works without one
   registry.AddCommand("import.files", ImportFiles,
      NeedsIdleAudio | Mutates | LongRunning);
}

void InstallImportHandler()
{
   if (sHandlerInstalled)
      return;
   sPreviousHandler = ProjectFileManager::SetImportHandler(
      [](AudacityProject &project, const FilePath &fileName, bool) -> bool {
         if (sImportDepth > 0)
            return NestedImport(project, fileName);
         // Not inside one of our imports (no caller does this today): a
         // complete import with its own undo state
         try {
            ImportOne(project, fileName);
            return true;
         }
         catch (const BridgeError &e) {
            if (e.code != ErrorCode::CANCELLED)
               BasicUI::ShowErrorDialog({}, XO("Error Importing"),
                  Verbatim(FromUtf8(e.what())), wxT("Importing_Audio"));
            return false;
         }
      });
   sHandlerInstalled = true;
}

void UninstallImportHandler()
{
   if (!sHandlerInstalled)
      return;
   ProjectFileManager::SetImportHandler(std::move(sPreviousHandler));
   sPreviousHandler = nullptr;
   sHandlerInstalled = false;
}

std::vector<std::string> ImporterMsgids()
{
   std::vector<std::string> result;
   const auto types = Importer::Get().GetFileTypes();
   for (size_t i = 3; i < types.size(); ++i)
      result.push_back(ToUtf8(types[i].description.MSGID().GET()));
   return result;
}

} // namespace io
} // namespace aubridge
