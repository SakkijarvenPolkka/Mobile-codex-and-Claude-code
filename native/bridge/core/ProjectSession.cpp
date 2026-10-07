/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ProjectSession.cpp

  Adapted from Audacity 3.7.9 src/ProjectManager.cpp,
  src/ProjectFileManager.cpp (Paul Licameli split from AudacityProject.cpp;
  Dominic Mazzoni et al.) and src/AutoRecoveryDialog.cpp (Leland Lucius):
  the dialogs are replaced by return values / events, everything that
  touches the library is kept in the upstream order.

**********************************************************************/
#include "ProjectSession.h"

#include <wx/dir.h>
#include <wx/filename.h>
#include <wx/log.h>
#include <wx/utils.h>

#include "ActiveProjects.h"
#include "AudioIO.h"
#include "BasicUI.h"
#include "Clipboard.h"
#include "CodeConversions.h"
#include "EngineThread.h"
#include "Events.h"
#include "FileException.h"
#include "FileNames.h"
#include "ModuleRegistry.h"
#include "PendingTracks.h"
#include "Project.h"
#include "ProjectAudioIO.h"
#include "ProjectFileIO.h"
#include "ProjectFileIOExtension.h"
#include "ProjectFileManager.h"   // lib-app-services: upstream FixTracks
#include "ProjectHistory.h"
#include "ProjectTimeSignature.h"
#include "Session.h"
#include "TempDirectory.h"
#include "TempoChange.h"
#include "Track.h"
#include "TrackFocus.h"
#include "UiServices.h"
#include "UndoManager.h"
#include "WaveTrack.h"
#include "WaveTrackUtilities.h"

namespace aubridge {

namespace {

const char *const defaultHelpUrl =
   "FAQ:Errors_on_opening_or_recovering_an_Audacity_project";

using Pair = std::pair< const char *, const char * >;
const Pair helpURLTable[] = {
   {
      "not well-formed (invalid token)",
      "Error:_not_well-formed_(invalid_token)_at_line_x"
   },
   {
      "reference to invalid character number",
      "Error_Opening_Project:_Reference_to_invalid_character_number_at_line_x"
   },
   {
      "mismatched tag",
      "#mismatched"
   },
};

// ProjectFileManager.cpp: FindHelpUrl
wxString FindHelpUrl( const TranslatableString &libraryError )
{
   wxString helpUrl;
   if ( !libraryError.empty() ) {
      helpUrl = defaultHelpUrl;

      auto msgid = libraryError.MSGID().GET();
      auto found = std::find_if( std::begin(helpURLTable), std::end(helpURLTable),
         [&]( const Pair &pair ) {
            return msgid.Contains( pair.first ); }
      );
      if (found != std::end(helpURLTable)) {
         auto url = found->second;
         if (url[0] == '#')
            helpUrl += url;
         else
            helpUrl = url;
      }
   }

   return helpUrl;
}

bool CheckDiskSpace(const FilePath &target, const FilePath &current,
   std::string &error)
{
   const wxULongLong fileSize = wxFileName::GetSize(current);
   wxDiskspaceSize_t freeSpace;
   if (wxGetDiskSpace(wxPathOnly(target), nullptr, &freeSpace) &&
       fileSize != wxInvalidSize &&
       freeSpace.GetValue() <= fileSize.GetValue()) {
      error = Translated(XO(
"The project size exceeds the available free space on the target disk.\n\n"
"Please select a different disk with more free space."));
      return false;
   }
   return true;
}

} // namespace

ProjectSession::ProjectSession(std::shared_ptr<AudacityProject> project)
   : mProject{ std::move(project) }
{
}

ProjectSession::~ProjectSession()
{
   Close();
}

std::unique_ptr<ProjectSession> ProjectSession::CreateNew()
{
   // ProjectManager::New()
   auto sp = AudacityProject::Create();
   AllProjects{}.Add(sp);
   std::unique_ptr<ProjectSession> session{ new ProjectSession(sp) };
   auto &project = *sp;

   // ProjectFileManager::OpenNewProject(): the temporary database that
   // receives sample blocks and autosaves
   if (!ProjectFileIO::Get(project).OpenProject()) {
      session->Abandon();
      Fail(ErrorCode::FAILED, Translated(XO(
         "Audacity could not create a temporary project file in %s.")
            .Format(TempDirectory::TempDir())));
   }
   ProjectHistory::Get(project).InitialState();
   session->Subscribe();
   return session;
}

void ProjectSession::Abandon()
{
   if (!mProject)
      return;
   auto &project = *mProject;
   auto &fileIO = ProjectFileIO::Get(project);
   fileIO.SetBypass();
   UndoManager::Get(project).ClearStates();
   TrackList::Get(project).Clear();
   fileIO.CloseProject();
   WaveTrackFactory::Destroy(project);
   AllProjects{}.Remove(project);
   EngineThread::Get().DrainInternal();
   mProject.reset();
}

void ProjectSession::Subscribe()
{
   std::weak_ptr<AudacityProject> wProject = mProject;
   mFileIOSubscription = ProjectFileIO::Get(*mProject).Subscribe(
      [wProject](ProjectFileIOMessage message) {
         switch (message) {
         case ProjectFileIOMessage::ReconnectionFailure:
            // ProjectManager::OnReconnectionFailure closes the window;
            // do it later, not inside the library call that failed
            EngineThread::Get().PostInternal([wProject] {
               auto pProject = wProject.lock();
               if (pProject && Session::Get().Project() == pProject.get()) {
                  Session::Get().CloseCurrent();
                  Dialogs::Show(Dialogs::Style::Error,
                     Translated(XO("Error")),
                     Translated(XO(
"Failed to reconnect to the project file; the project was closed.")));
               }
            });
            break;
         case ProjectFileIOMessage::CheckpointFailure:
            Events::Log(Events::LogLevel::Error,
               "Project database checkpoint failed");
            break;
         default:
            Session::Get().ScheduleSnapshot();
            break;
         }
      });
}

// ProjectFileManager::ReadProjectFile
auto ProjectSession::ReadProjectFile(AudacityProject &project,
   std::shared_ptr<TrackList> &lastSavedTracks, const FilePath &fileName,
   bool discardAutosave) -> ReadResult
{
   auto &projectFileIO = ProjectFileIO::Get( project );

   ///
   /// Parse project file
   ///
   auto parseResult = projectFileIO.LoadProject(fileName, discardAutosave);
   const bool bParseSuccess = parseResult.has_value();

   bool err = false;
   std::optional<TranslatableString> linkTypeChangeReason;

   TranslatableString otherError;

   if (bParseSuccess)
   {
      auto& tracks = TrackList::Get(project);
      // By making a duplicate set of pointers to the existing blocks
      // on disk, we add one to their reference count, guaranteeing
      // that their reference counts will never reach zero and thus
      // the version saved on disk will be preserved until the
      // user selects Save().
      // Do this before FixTracks might delete zero-length clips!
      lastSavedTracks = TrackList::Create( nullptr );
      WaveTrack *leader{};
      tracks.Any().Visit(
         [&](WaveTrack& track) {
            // A rare place where TrackList::Channels remains necessary, to
            // visit the right channels of stereo tracks not yet "zipped",
            // otherwise later, CloseLock() will be missed for some sample
            // blocks and corrupt the project
            for (const auto pChannel : TrackList::Channels(&track))
            {
               auto left = leader;
               auto newTrack =
                  pChannel->Duplicate(Track::DuplicateOptions {}.Backup());
               leader = left ? nullptr // now visiting the right channel
                        :
                        (pChannel->GetLinkType() == Track::LinkType::None) ?
                               nullptr // now visiting a mono channel
                               :
                               static_cast<WaveTrack*>(newTrack.get())
                  // now visiting a left channel
                  ;
               lastSavedTracks->Add(newTrack);
               if (left)
                  // Zip clips allowing misalignment -- this may be a legacy
                  // project.  This duplicate track will NOT be used for normal
                  // editing, but only later to visit all the sample blocks that
                  // existed at last save time.
                  left->ZipClips(false);
            }
         },
         [&](Track& track) {
            lastSavedTracks->Add(
               track.Duplicate(Track::DuplicateOptions {}.Backup()));
         });

      ProjectFileManager::FixTracks(
         tracks,
         // Keep at most one of the error messages
         [&](const auto& errorMessage) { otherError = errorMessage; err = true; },
         [&](const auto& unlinkReason) { linkTypeChangeReason = unlinkReason; });

      if (!err) {
         if(linkTypeChangeReason && !discardAutosave)
         {
            BasicUI::ShowMessageBox(XO(
//i18n-hint: Text of the message dialog that may appear on attempt
//to open a project created by Audacity version prior to 3.4.
//%s will be replaced with an explanation of the actual reason of
//project modification.
"%s\n"
"This feature is not supported in Audacity versions past 3.3.3.\n"
"These stereo tracks have been split into mono tracks.\n"
"As a result, some realtime effects may be missing.\n"
"Please verify that everything works as intended before saving."
            ).Format(linkTypeChangeReason->Translation()));
         }

         parseResult->Commit();
         if (discardAutosave)
            // REVIEW: Failure OK?
            projectFileIO.AutoSaveDelete();
         else if (projectFileIO.IsRecovered()) {
            bool resaved = false;

            if (!projectFileIO.IsTemporary() &&
               !linkTypeChangeReason)
            {
               // Re-save non-temporary project to its own path.  This
               // might fail to update the document blob in the database.
               resaved = projectFileIO.SaveProject(fileName, nullptr);
            }

            Dialogs::Show(Dialogs::Style::Warning,
               Translated(XO("Project Recovered")),
               Translated(resaved
                  ? XO(
"This project was not saved properly the last time Audacity ran.\n\n"
"It has been recovered to the last snapshot.")
                  : XO(
"This project was not saved properly the last time Audacity ran.\n\n"
"It has been recovered to the last snapshot, but you must save it\n"
"to preserve its contents.")));
         }
      }

      ProjectFileIOExtensionRegistry::OnLoad(project);
   }

   return {
      bParseSuccess,
      err,
      (bParseSuccess ? otherError : projectFileIO.GetLastError()),
      FindHelpUrl(projectFileIO.GetLibraryError())
   };
}

// ProjectFileManager::OpenProjectFile + ProjectManager::OpenProject
bool ProjectSession::OpenProjectFile(const FilePath &fileName,
   std::string &error)
{
   auto &project = *mProject;
   // Allow extensions to update the project before opening it.
   if (ProjectFileIOExtensionRegistry::OnOpen(
          project, audacity::ToUTF8(fileName)) == OnOpenAction::Cancel) {
      error = "Opening was cancelled";
      return false;
   }

   auto &history = ProjectHistory::Get( project );
   auto &tracks = TrackList::Get( project );
   auto &projectFileIO = ProjectFileIO::Get( project );

   auto results = ReadProjectFile( project, mLastSavedTracks, fileName, false );
   const bool bParseSuccess = results.parseSuccess;
   const auto &errorStr = results.errorString;
   const bool err = results.trackError;

   if (bParseSuccess && !err) {
      // Set clip's project tempo - this is a fix for issue #11337
      const auto projectTempo = ProjectTimeSignature::Get(project).GetTempo();
      const std::optional<double> oldTempo{};
      for (auto track : tracks)
         OnProjectTempoChange::Call(*track, oldTempo, projectTempo);

      ProjectHistory::Get( project ).InitialState();
      if (!tracks.empty())
         TrackFocus::Get(project).Set(*tracks.begin());

      if (projectFileIO.IsRecovered())
      {
         // PushState calls AutoSave(), so no longer need to do so here.
         history.PushState(XO("Project was recovered"), XO("Recover"));
         // ProjectManager::OpenProject: "Project was recovered" replaces
         // "Create new project" in Undo History.
         UndoManager::Get( project ).RemoveStates(0, 1);
      }
      return true;
   }

   // Like what happens when the project closes: don't delete the blocks
   for (auto pTrack : tracks.Any<WaveTrack>())
      WaveTrackUtilities::CloseLock(*pTrack);

   tracks.Clear();

   wxLogError(wxT("Could not parse file \"%s\". \nError: %s"), fileName,
      errorStr.Debug());
   error = errorStr.empty()
      ? Translated(XO("Error Opening Project"))
      : Translated(errorStr);
   if (!results.helpUrl.empty())
      error += "\n(" + Dialogs::HelpUrl(ToUtf8(results.helpUrl)) + ")";
   return false;
}

bool ProjectSession::Save(std::string &error)
{
   auto &project = *mProject;
   auto &projectFileIO = ProjectFileIO::Get(project);

   if (auto action = ProjectFileIOExtensionRegistry::OnSave(
          project, [this, &error](auto& path, bool rename)
          { return DoSave(audacity::ToWXString(path), rename, error); });
      action != OnSaveAction::Continue) {
      if (action != OnSaveAction::Handled && error.empty())
         error = "Saving was cancelled";
      return action == OnSaveAction::Handled;
   }

   if (projectFileIO.IsTemporary()) {
      error = "The project was never saved";
      return false;
   }

   return DoSave(projectFileIO.GetFileName(), false, error);
}

// ProjectFileManager::DoSave
bool ProjectSession::DoSave(const FilePath &fileName, bool fromSaveAs,
   std::string &error)
{
   auto &proj = *mProject;
   auto &projectFileIO = ProjectFileIO::Get( proj );

   // Some confirmations (desktop: dialogs; here: error results)
   if (TempDirectory::FATFilesystemDenied(fileName,
         XO("Projects cannot be saved to FAT drives."))) {
      error = Translated(XO("Projects cannot be saved to FAT drives."));
      return false;
   }
   if (!CheckDiskSpace(fileName, projectFileIO.GetFileName(), error))
      return false;

   // Always save a backup of the original project file
   std::optional<ProjectFileIO::BackupProject> pBackupProject;
   if (fromSaveAs && wxFileExists(fileName))
   {
      pBackupProject.emplace(projectFileIO, fileName);
      if (!pBackupProject->IsOk()) {
         error = Translated(XO("Error Saving Project"));
         return false;
      }
   }

   bool success = projectFileIO.SaveProject(fileName, mLastSavedTracks.get());
   if (!success)
   {
      const auto &last = projectFileIO.GetLastError();
      error = Translated(last.empty()
         ? FileException::WriteFailureMessage(fileName) : last);
      return false;
   }

   proj.SetProjectName(wxFileName(fileName).GetName());
   projectFileIO.SetProjectTitle();

   UndoManager::Get(proj).StateSaved();

   if (mLastSavedTracks)
   {
      mLastSavedTracks->Clear();
   }
   mLastSavedTracks = TrackList::Create(nullptr);

   auto &tracks = TrackList::Get(proj);
   for (auto t : tracks)
      mLastSavedTracks->Add(t->Duplicate(Track::DuplicateOptions{}.Backup()));

   // If we get here, saving the project was successful, so we can DELETE
   // any backup project.
   if (pBackupProject)
      pBackupProject->Discard();

   return true;
}

// ProjectFileManager::SaveAs(const FilePath &, bool)
bool ProjectSession::SaveAs(const FilePath &newFileName, std::string &error)
{
   auto &projectFileIO = ProjectFileIO::Get( *mProject );
   auto oldFileName = projectFileIO.GetFileName();

   bool bOwnsNewName =
      !projectFileIO.IsTemporary() && (oldFileName == newFileName);
   //check to see if the NEW project file already exists.
   //We should only overwrite it if this project already has the same name,
   //where the user simply chose to use the save as command although the save
   //command would have the effect.
   if( !bOwnsNewName && wxFileExists(newFileName)) {
      error = Translated(XO(
"The project was not saved because the file name provided would overwrite another project.\nPlease try again and select an original name."));
      return false;
   }

   return DoSave(newFileName, !bOwnsNewName, error);
}

// Non-interactive part of ProjectFileManager::SaveCopy
bool ProjectSession::SaveCopy(const FilePath &fileName, std::string &error)
{
   auto &projectFileIO = ProjectFileIO::Get(*mProject);
   if (TempDirectory::FATFilesystemDenied(fileName,
         XO("Projects cannot be saved to FAT drives."))) {
      error = Translated(XO("Projects cannot be saved to FAT drives."));
      return false;
   }
   if (wxFileExists(fileName)) {
      // Saving a copy of the project should never overwrite an existing
      // project.
      error = Translated(XO(
"Saving a copy must not overwrite an existing saved project.\nPlease try again and select an original name."));
      return false;
   }
   if (!CheckDiskSpace(fileName, projectFileIO.GetFileName(), error))
      return false;
   if (!projectFileIO.SaveCopy(fileName)) {
      error = Translated(FileException::WriteFailureMessage(fileName));
      return false;
   }
   return true;
}

// Non-GUI part of ProjectManager::OnCloseWindow, with
// ProjectFileManager::CompactProjectOnClose and ::CloseProject
void ProjectSession::Close()
{
   if (!mProject)
      return;
   auto &project = *mProject;
   auto &projectFileIO = ProjectFileIO::Get( project );
   auto &tracks = TrackList::Get( project );

   if (mAnnounced)
      ModuleRegistry::Get().RunProjectClosing(project);

   // Stop audio for this project (normally done by the audio module's
   // closing hook already)
   if (auto gAudioIO = AudioIO::Get()) {
      auto &projectAudioIO = ProjectAudioIO::Get( project );
      if (projectAudioIO.GetAudioIOToken() > 0 &&
          gAudioIO->IsStreamActive(projectAudioIO.GetAudioIOToken())) {
         gAudioIO->StopStream();
         projectAudioIO.SetAudioIOToken(0);
      }
      else if (gAudioIO->IsMonitoring())
         gAudioIO->StopStream();
   }
   PendingTracks::Get(project).ClearPendingTracks();

   // Extensions cannot veto: Kotlin asked the user before closing
   (void)ProjectFileIOExtensionRegistry::OnClose(project);

   // Closing the project from which content was cut or copied: clear the
   // clipboard, its tracks depend on this project's database connection
   auto &clipboard = Clipboard::Get();
   if (clipboard.Project().lock().get() == &project)
      clipboard.Clear();

   auto& undoManager = UndoManager::Get(project);
   if (undoManager.GetSavedState() >= 0)
   {
      constexpr auto doAutoSave = false;
      ProjectHistory::Get(project).SetStateTo(
         undoManager.GetSavedState(), doAutoSave);
   }

   // CompactProjectOnClose: lock all blocks in all tracks of the last saved
   // version, so that the sample blocks aren't deleted from the database
   // when we destroy the sample block objects in memory.
   if (mLastSavedTracks)
   {
      for (auto wt : mLastSavedTracks->Any<WaveTrack>())
         WaveTrackUtilities::CloseLock(*wt);

      // Attempt to compact the project
      projectFileIO.Compact({ mLastSavedTracks.get() });

      if (
         !projectFileIO.WasCompacted() &&
         undoManager.UnsavedChanges())
      {
         // If compaction failed, we must do some work in case of close
         // without save.  Don't leave the document blob from the last
         // push of undo history, when that undo state may get purged
         // with deletion of some new sample blocks.
         projectFileIO.UpdateSaved(mLastSavedTracks.get());
      }
   }

   // Set (or not) the bypass flag to indicate that deletes that would happen
   // during undoManager.ClearStates() below are not necessary. Must be called
   // between `CompactProjectOnClose()` and `undoManager.ClearStates()`.
   projectFileIO.SetBypass();

   // This can reduce reference counts of sample blocks in the project's
   // tracks.
   undoManager.ClearStates();

   // Delete all the tracks to free up memory
   tracks.Clear();

   // ProjectFileManager::CloseProject: we're all done with the project file
   projectFileIO.CloseProject();
   // Blocks were locked in CompactProjectOnClose, so DELETE the data
   // structure so that there's no memory leak.
   if (mLastSavedTracks)
   {
      mLastSavedTracks->Clear();
      mLastSavedTracks.reset();
   }

   WaveTrackFactory::Destroy( project );

   mFileIOSubscription.Reset();
   AllProjects{}.Remove( project );

   // Run delayed work that may still refer to the project
   EngineThread::Get().DrainInternal();
   mProject.reset();
}

// ProjectFileManager::DiscardAutosave
void ProjectSession::DiscardAutosave(const FilePath &filename)
{
   InvisibleTemporaryProject tempProject;
   auto &project = tempProject.Project();
   std::shared_ptr<TrackList> lastSavedTracks;
   // Read the project, discarding autosave
   ReadProjectFile(project, lastSavedTracks, filename, true);

   if (lastSavedTracks) {
      for (auto wt : lastSavedTracks->Any<WaveTrack>())
         WaveTrackUtilities::CloseLock(*wt);
      lastSavedTracks.reset();
   }

   // Side-effect on database is done, and destructor of tempProject
   // closes the temporary project properly
}

// AutoRecoveryDialog::PopulateList
std::vector<FilePath> ProjectSession::ScanRecoverable(const FilePath &exclude)
{
   wxString tempdir = TempDirectory::TempDir();
   wxString pattern = wxT("*.") + FileNames::UnsavedProjectExtension();
   FilePaths files;

   if (wxDirExists(tempdir)) {
      wxLogNull noLog;
      wxDir::GetAllFiles(tempdir, &files, pattern, wxDIR_FILES);
   }

   FilePaths active = ActiveProjects::GetAll();

   for (auto file : active)
   {
      wxFileName fn = file;
      if (fn.FileExists())
      {
         FilePath fullPath = fn.GetFullPath();
         if (files.Index(fullPath) == wxNOT_FOUND)
         {
            files.push_back(fullPath);
         }
      }
      else
      {
         ActiveProjects::Remove(file);
      }
   }

   std::vector<FilePath> result;
   const wxFileName excluded{ exclude };
   for (auto file : files)
   {
      wxFileName fn = file;
      if (exclude.empty() || !fn.SameAs(excluded))
         result.push_back(fn.GetFullPath());
   }
   return result;
}

// AutoRecoveryDialog::OnDiscardSelected + DiscardAllProjects
void ProjectSession::DiscardRecoverable(const FilePath &fileName)
{
   // Only remove it from disk if it appears to be a temporary file.
   wxFileName file(fileName);
   if (file.GetExt().IsSameAs(FileNames::UnsavedProjectExtension()))
   {
      file.SetFullName(wxT(""));

      wxFileName temp(TempDirectory::TempDir(), wxT(""));
      if (file == temp)
         ProjectFileIO::RemoveProject(fileName);
   }
   else
      // Don't remove from disk, but do open the database of this saved
      // file, and discard edits
      DiscardAutosave(fileName);

   // Forget all about it
   ActiveProjects::Remove(fileName);
}

} // namespace aubridge
