/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port: application-layer stand-in for
  src/ProjectManager.h (Audacity 3.7.9) -- only what mod-lof uses.

**********************************************************************/
#ifndef __AUDACITY_PROJECT_MANAGER__
#define __AUDACITY_PROJECT_MANAGER__

#include <functional>

#include "FileNames.h" // for FilePath

class AudacityProject;

class APP_SERVICES_API ProjectManager final
{
public:
   //! Upstream: open a project file or import a file, possibly into a new
   //! project.  Delegates to the installed handler; without one it imports
   //! into pGivenProject (via ProjectFileManager::Import) and returns it.
   static AudacityProject *OpenProject(
      AudacityProject *pGivenProject,
      const FilePath &fileNameArg, bool addtohistory, bool reuseNonemptyProject);

   // ---- Android port addition ---------------------------------------
   using OpenProjectHandler = std::function<AudacityProject *(
      AudacityProject *pGivenProject, const FilePath &fileName,
      bool addtohistory, bool reuseNonemptyProject)>;
   static OpenProjectHandler SetOpenProjectHandler(OpenProjectHandler handler);
};

#endif
