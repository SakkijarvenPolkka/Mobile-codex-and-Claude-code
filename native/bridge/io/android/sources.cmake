# SPDX-License-Identifier: GPL-2.0-or-later
#
# Android NDK media plug-ins of the bridge "io" module (see README.md here
# and native/bridge/MODULES.md).  Included by ../sources.cmake; the bridge
# CMakeLists.txt compiles AUBRIDGE_IO_ANDROID_SOURCES into aubridge-io on
# Android only.
#
# Link requirements: libmediandk (AMediaExtractor, AMediaCodec, AMediaMuxer,
# API 21/28 symbols only) and liblog.  native/bridge/CMakeLists.txt links
# mediandk to aubridge-io automatically when AUBRIDGE_IO_ANDROID_SOURCES is
# not empty, and liblog through aubridge-headers; the list below states the
# requirement explicitly as well (appending a library twice is harmless).
#
# The files are self-registering (static Importer / ExportPluginRegistry
# registrars); aubridge-io is linked as a whole archive into
# libaudacity-bridge.so, so nothing has to reference them.

set(AUBRIDGE_IO_ANDROID_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/AndroidCodecs.h
   ${CMAKE_CURRENT_LIST_DIR}/AndroidMediaCommon.h
   ${CMAKE_CURRENT_LIST_DIR}/AndroidMediaImport.cpp
   ${CMAKE_CURRENT_LIST_DIR}/AndroidAacExport.cpp
)

# Needs: mediandk (+ log)
set(AUBRIDGE_IO_ANDROID_REQUIRED_LIBRARIES mediandk log)
list(APPEND AUBRIDGE_IO_ANDROID_LIBRARIES ${AUBRIDGE_IO_ANDROID_REQUIRED_LIBRARIES})
