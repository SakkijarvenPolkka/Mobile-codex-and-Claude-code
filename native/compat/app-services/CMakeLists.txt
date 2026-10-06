#[[
lib-app-services -- Android-port stand-in for the parts of Audacity's wx
application (src/) that the import modules mod-aup and mod-lof call:
ProjectFileManager::{Get,Import,FixTracks}, ProjectManager::OpenProject,
GetProjectFrame.  The upstream mod-aup/mod-lof CMakeLists.txt link the
application target "Audacity"; the port remaps that name to this library
(see native/cmake/PortFixups.cmake).  Like the upstream application target
it links all Audacity libraries publicly.

Application behaviour is delegated to handlers the bridge installs:
   ProjectFileManager::SetImportHandler(...)
   ProjectManager::SetOpenProjectHandler(...)
Without handlers, a toolkit-neutral default imports via Importer.
]]

set( SOURCES
   AppServices.cpp
   include/ProjectFileManager.h
   include/ProjectManager.h
   include/ProjectWindows.h
   include/widgets/NumericTextCtrl.h
)
set( LIBRARIES ${AUDACITY_APP_SERVICES_LIBRARIES} )

audacity_library( lib-app-services "${SOURCES}" "${LIBRARIES}" "" "" )

# Headers are in include/, not next to CMakeLists.txt
target_include_directories( lib-app-services PUBLIC
   "${CMAKE_CURRENT_SOURCE_DIR}/include" )
