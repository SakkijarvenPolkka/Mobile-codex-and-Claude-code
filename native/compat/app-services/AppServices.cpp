/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port: implementation of the application-layer
  stand-ins (ProjectFileManager.h, ProjectManager.h, ProjectWindows.h).

  ProjectFileManager::FixTracks() is the upstream implementation from
  src/ProjectFileManager.cpp (Audacity 3.7.9), unchanged.

**********************************************************************/
#include "ProjectFileManager.h"
#include "ProjectManager.h"
#include "ProjectWindows.h"

#include <optional>
#include <wx/log.h>

#include "AcidizerTags.h"
#include "Import.h"
#include "Internat.h"
#include "Project.h"
#include "ProjectHistory.h"
#include "RealtimeEffectList.h"
#include "Tags.h"
#include "Track.h"
#include "WaveTrack.h"

// ---------------------------------------------------------------------------
// ProjectFileManager
// ---------------------------------------------------------------------------
static const AudacityProject::AttachedObjects::RegisteredFactory sFileManagerKey{
   []( AudacityProject &parent ){
      return std::make_shared< ProjectFileManager >( parent );
   }
};

ProjectFileManager &ProjectFileManager::Get( AudacityProject &project )
{
   return project.AttachedObjects::Get< ProjectFileManager >( sFileManagerKey );
}

const ProjectFileManager &ProjectFileManager::Get( const AudacityProject &project )
{
   return Get( const_cast< AudacityProject & >( project ) );
}

ProjectFileManager::ProjectFileManager( AudacityProject &project )
   : mProject{ project }
{
}

ProjectFileManager::~ProjectFileManager() = default;

namespace {
ProjectFileManager::ImportHandler &TheImportHandler()
{
   static ProjectFileManager::ImportHandler handler;
   return handler;
}
ProjectManager::OpenProjectHandler &TheOpenProjectHandler()
{
   static ProjectManager::OpenProjectHandler handler;
   return handler;
}
}

auto ProjectFileManager::SetImportHandler(ImportHandler handler) -> ImportHandler
{
   auto &current = TheImportHandler();
   auto previous = std::move(current);
   current = std::move(handler);
   return previous;
}

bool ProjectFileManager::Import(const FilePath& fileName, bool addToHistory)
{
   if (auto &handler = TheImportHandler())
      return handler(mProject, fileName, addToHistory);
   return DefaultImport(mProject, fileName, addToHistory);
}

bool ProjectFileManager::DefaultImport(
   AudacityProject &project, const FilePath &fileName, bool)
{
   TrackHolders newTracks;
   TranslatableString errorMessage;
   std::optional<LibFileFormats::AcidizerTags> acidTags;
   auto &tags = Tags::Get(project);
   const bool success = Importer::Get().Import(project, fileName,
      nullptr, &WaveTrackFactory::Get(project), newTracks, &tags,
      acidTags, errorMessage);
   if (!success) {
      if (!errorMessage.empty())
         wxLogError(wxT("Import of %s failed: %s"),
            fileName, errorMessage.Translation());
      return false;
   }
   auto &tracks = TrackList::Get(project);
   for (auto &track : newTracks)
      tracks.Add(track);
   if (!newTracks.empty())
      ProjectHistory::Get(project).PushState(
         XO("Imported '%s'").Format(fileName), XO("Import"));
   return true;
}

// Upstream src/ProjectFileManager.cpp, unchanged
void ProjectFileManager::FixTracks(TrackList& tracks,
   const std::function<void(const TranslatableString&)>& onError,
   const std::function<void(const TranslatableString&)>& onUnlink)
{
   // This is successively assigned the left member of each pair that
   // becomes unlinked
   Track::Holder unlinkedTrack;
   // Beware iterator invalidation, because stereo channels get zipped,
   // replacing WaveTracks
   for (auto iter = tracks.begin(); iter != tracks.end();) {
      auto t = (*iter++)->SharedPointer();
      const auto linkType = t->GetLinkType();
      // Note, the next function may have an important upgrading side effect,
      // and return no error; or it may find a real error and repair it, but
      // that repaired track won't be used because opening will fail.
      if (!t->LinkConsistencyFix()) {
         onError(XO("A channel of a stereo track was missing."));
         unlinkedTrack = nullptr;
      }
      if (!unlinkedTrack) {
         if (linkType != ChannelGroup::LinkType::None &&
            t->NChannels() == 1) {
            // The track became unlinked.
            // It should NOT have been replaced with a "zip"
            assert(t->GetOwner().get() == &tracks);
            // Wait until LinkConsistencyFix is called on the second track
            unlinkedTrack = t;
            // Fix the iterator, which skipped the right channel before the
            // unlinking
            iter = tracks.Find(t.get());
            ++iter;
         }
      }
      else {
         //Not an elegant way to deal with stereo wave track linking
         //compatibility between versions
         if (const auto left = dynamic_cast<WaveTrack*>(unlinkedTrack.get())) {
            if (const auto right = dynamic_cast<WaveTrack*>(t.get())) {
               // As with the left, it should not have vanished from the list
               assert(right->GetOwner().get() == &tracks);
               left->SetPan(-1.0f);
               right->SetPan(1.0f);
               RealtimeEffectList::Get(*left).Clear();
               RealtimeEffectList::Get(*right).Clear();

               if(left->GetRate() != right->GetRate())
                  //i18n-hint: explains why opened project was auto-modified
                  onUnlink(XO("This project contained stereo tracks with different sample rates per channel."));
               if(left->GetSampleFormat() != right->GetSampleFormat())
                  //i18n-hint: explains why opened project was auto-modified
                  onUnlink(XO("This project contained stereo tracks with different sample formats in channels."));
               //i18n-hint: explains why opened project was auto-modified
               onUnlink(XO("This project contained stereo tracks with non-aligned content."));
            }
         }
         unlinkedTrack = nullptr;
      }

      if (const auto message = t->GetErrorOpening()) {
         wxLogWarning(
            wxT("Track %s had error reading clip values from project file."),
            t->GetName());
         onError(*message);
      }
   }
}

// ---------------------------------------------------------------------------
// ProjectManager
// ---------------------------------------------------------------------------
auto ProjectManager::SetOpenProjectHandler(OpenProjectHandler handler)
   -> OpenProjectHandler
{
   auto &current = TheOpenProjectHandler();
   auto previous = std::move(current);
   current = std::move(handler);
   return previous;
}

AudacityProject *ProjectManager::OpenProject(
   AudacityProject *pGivenProject, const FilePath &fileNameArg,
   bool addtohistory, bool reuseNonemptyProject)
{
   if (auto &handler = TheOpenProjectHandler())
      return handler(pGivenProject, fileNameArg, addtohistory,
         reuseNonemptyProject);
   // Default: no project windows, so import into the given project
   if (!pGivenProject)
      return nullptr;
   if (!ProjectFileManager::Get(*pGivenProject).Import(fileNameArg, addtohistory))
      return nullptr;
   return pGivenProject;
}

// ---------------------------------------------------------------------------
// ProjectWindows
// ---------------------------------------------------------------------------
NoProjectFrame &GetProjectFrame( AudacityProject & )
{
   static NoProjectFrame theFrame;
   return theFrame;
}

const NoProjectFrame &GetProjectFrame( const AudacityProject &project )
{
   return GetProjectFrame( const_cast< AudacityProject & >( project ) );
}
