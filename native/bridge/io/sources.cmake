# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "io" module (see native/bridge/MODULES.md).
# STUB written by the spine; the module owner replaces this file.
set(AUBRIDGE_IO_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/RegisterIoModule.cpp
)
# Android codec plug-ins (NDK media): sets AUBRIDGE_IO_ANDROID_SOURCES
include(${CMAKE_CURRENT_LIST_DIR}/android/sources.cmake OPTIONAL)
