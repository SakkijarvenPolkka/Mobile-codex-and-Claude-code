/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  ProjectSession.h

  Lifecycle of one AudacityProject without project windows: the non-GUI
  parts of Audacity 3.7.9 src/ProjectManager.cpp (New, OnCloseWindow),
  src/ProjectFileManager.cpp (ReadProjectFile, OpenProjectFile, Save,
  DoSave, SaveAs, SaveCopy, CompactProjectOnClose, CloseProject,
  DiscardAutosave) and src/AutoRecoveryDialog.cpp (recovery scan and
  discard).  See init-and-project.md §7.

  Engine thread only.

**********************************************************************/
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "Observer.h"
#include "TranslatableString.h"
#include "Identifier.h"

class AudacityProject;
class TrackList;

namespace aubridge {

class ProjectSession final {
public:
   //! port of ProjectManager::New(): project with a temporary database and
   //! the initial undo state.  @throws BridgeError{FAILED}
   static std::unique_ptr<ProjectSession> CreateNew();

   ~ProjectSession();
   ProjectSession(const ProjectSession &) = delete;
   ProjectSession &operator=(const ProjectSession &) = delete;

   AudacityProject &Project() { return *mProject; }
   std::shared_ptr<AudacityProject> ProjectPtr() { return mProject; }

   struct ReadResult {
      bool parseSuccess = false;
      bool trackError = false;
      TranslatableString errorString;
      wxString helpUrl;
   };

   //! port of ProjectFileManager::OpenProjectFile (+ the recovered-project
   //! handling of ProjectManager::OpenProject).
   //! @param error receives the message on failure
   bool OpenProjectFile(const FilePath &fileName, std::string &error);

   //! port of ProjectFileManager::Save; the caller handles temporary
   //! projects (NEEDS_PATH)
   bool Save(std::string &error);
   //! port of ProjectFileManager::SaveAs(newFileName) (no dialog)
   bool SaveAs(const FilePath &newFileName, std::string &error);
   //! port of the non-interactive part of ProjectFileManager::SaveCopy
   bool SaveCopy(const FilePath &fileName, std::string &error);

   //! port of the non-GUI part of ProjectManager::OnCloseWindow (no save
   //! prompt: Kotlin asks first).  Idempotent.
   void Close();
   bool IsClosed() const { return !mProject; }

   //! Session::SetCurrent ran the modules' ProjectOpened hooks; Close() runs
   //! the ProjectClosing hooks only for such sessions
   void MarkAnnounced() { mAnnounced = true; }

   //! port of ProjectFileManager::DiscardAutosave
   static void DiscardAutosave(const FilePath &fileName);

   //! port of AutoRecoveryDialog::PopulateList: unsaved projects in the
   //! temp dir + ActiveProjects entries; excludes `exclude` (the open one)
   static std::vector<FilePath> ScanRecoverable(const FilePath &exclude = {});
   //! port of AutoRecoveryDialog's discard: remove temporary projects,
   //! discard the autosave of saved ones; forget them in ActiveProjects
   static void DiscardRecoverable(const FilePath &fileName);

private:
   explicit ProjectSession(std::shared_ptr<AudacityProject> project);
   static ReadResult ReadProjectFile(AudacityProject &project,
      std::shared_ptr<TrackList> &lastSavedTracks, const FilePath &fileName,
      bool discardAutosave);
   bool DoSave(const FilePath &fileName, bool fromSaveAs, std::string &error);
   void Subscribe();
   void Abandon();

   std::shared_ptr<AudacityProject> mProject;
   //! Keeps the on-disk sample blocks of the last save alive
   std::shared_ptr<TrackList> mLastSavedTracks;
   Observer::Subscription mFileIOSubscription;
   bool mAnnounced = false;
};

} // namespace aubridge
