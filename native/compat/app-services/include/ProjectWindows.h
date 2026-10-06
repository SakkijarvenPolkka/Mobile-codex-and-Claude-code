/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port: stand-in for src/ProjectWindows.h.

  There are no project windows.  mod-aup only binds the result of
  GetProjectFrame() to an unused `auto &` variable, so an empty placeholder
  type is returned instead of wxFrame (which does not exist in wxBase).

**********************************************************************/
#ifndef __AUDACITY_PROJECT_WINDOWS__
#define __AUDACITY_PROJECT_WINDOWS__

#include "ClientData.h"

class AudacityProject;

//! Placeholder for the (non-existent) top-level window of a project
struct APP_SERVICES_API NoProjectFrame final {};

APP_SERVICES_API NoProjectFrame &GetProjectFrame( AudacityProject &project );
APP_SERVICES_API const NoProjectFrame &GetProjectFrame( const AudacityProject &project );

#endif
