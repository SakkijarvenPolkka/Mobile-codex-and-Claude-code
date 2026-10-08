# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "io" module (see native/bridge/MODULES.md):
# import.* / export.* commands (API.md §3.3, §5.6).
set(AUBRIDGE_IO_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/IoModule.h
   ${CMAKE_CURRENT_LIST_DIR}/RegisterIoModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/ImportCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/ExportCommands.cpp
)
# Android codec plug-ins (NDK media): sets AUBRIDGE_IO_ANDROID_SOURCES
include(${CMAKE_CURRENT_LIST_DIR}/android/sources.cmake OPTIONAL)
