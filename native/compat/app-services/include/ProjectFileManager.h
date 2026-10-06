/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port: application-layer stand-in for
  src/ProjectFileManager.h (Audacity 3.7.9).

  The desktop class lives in the wxWidgets application (src/), which the port
  does not build.  The import modules mod-aup and mod-lof call exactly the
  members declared here, with the upstream signatures.  Behaviour that needs
  the application (dialogs, history, view) is delegated to handlers the
  bridge can install; the defaults are toolkit neutral.

**********************************************************************/
#ifndef __AUDACITY_PROJECT_FILE_MANAGER__
#define __AUDACITY_PROJECT_FILE_MANAGER__

#include <functional>
#include <memory>

#include "ClientData.h"
#include "FileNames.h" // for FilePath

class AudacityProject;
class TrackList;
class TranslatableString;

class APP_SERVICES_API ProjectFileManager final
   : public ClientData::Base
{
public:
   static ProjectFileManager &Get( AudacityProject &project );
   static const ProjectFileManager &Get( const AudacityProject &project );

   explicit ProjectFileManager( AudacityProject &project );
   ProjectFileManager( const ProjectFileManager & ) = delete;
   ProjectFileManager &operator=( const ProjectFileManager & ) = delete;
   ~ProjectFileManager() override;

   //! Import a file into the project (upstream signature).
   /*! Calls the installed ImportHandler, or DefaultImport() if none. */
   bool Import(const FilePath& fileName, bool addToHistory = true);

   //! Same as upstream: repair stereo linking after loading legacy projects
   static void FixTracks(TrackList& tracks,
      const std::function<void(const TranslatableString&/*errorMessage*/)>& onError,
      const std::function<void(const TranslatableString&/*unlinkReason*/)>& onUnlink);

   // ---- Android port additions --------------------------------------
   using ImportHandler = std::function<
      bool(AudacityProject &project, const FilePath &fileName, bool addToHistory)>;
   //! Install the application's import routine; returns the previous one.
   static ImportHandler SetImportHandler(ImportHandler handler);

   //! Toolkit-neutral import: Importer::Import, then add the new tracks to
   //! the project's TrackList and (optionally) push an undo state.
   static bool DefaultImport(
      AudacityProject &project, const FilePath &fileName, bool addToHistory);

private:
   AudacityProject &mProject;
};

#endif
