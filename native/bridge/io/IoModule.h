/*  SPDX-License-Identifier: GPL-2.0-or-later */
/**********************************************************************

  Audacity Android port

  IoModule.h

  Internal interface of the bridge "io" module (native/bridge/io): the
  import.* and export.* commands of API.md §3.3 and the hooks that
  RegisterIoModule.cpp installs.  Engine thread only unless noted.

**********************************************************************/
#pragma once

#include <string>
#include <vector>

#include "Json.h"

namespace aubridge {
class ModuleRegistry;

namespace io {

// ---- ImportCommands.cpp ---------------------------------------------------

//! import.formats, import.files
void RegisterImportCommands(ModuleRegistry &registry);

//! Installs the bridge's import routine as lib-app-services'
//! ProjectFileManager::ImportHandler (used by mod-aup <import> elements and
//! mod-lof), remembering the previous handler
void InstallImportHandler();
//! Restores the handler that was installed before InstallImportHandler()
void UninstallImportHandler();

//! Descriptions (msgids) of the registered import plug-ins, in probing order
std::vector<std::string> ImporterMsgids();

// ---- ExportCommands.cpp ---------------------------------------------------

//! export.formats, export.defaults, export.options, export.setOption,
//! export.run
void RegisterExportCommands(ModuleRegistry &registry);

//! Forgets every export options session (engine shutdown)
void ResetExportSessions();

//! API.md format keys (FormatInfo::description msgids) of the registered
//! export formats, in registry order (duplicates included)
std::vector<std::string> ExportFormatKeys();

} // namespace io
} // namespace aubridge
