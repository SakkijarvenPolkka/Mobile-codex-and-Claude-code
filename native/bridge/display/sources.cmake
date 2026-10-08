# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "display" module (see native/bridge/MODULES.md):
# waveform / envelope / sample / spectrogram data (API.md §7),
# display.setViewportWidth, display.trimCaches.
set(AUBRIDGE_DISPLAY_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/RegisterDisplayModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/DisplayInternal.h
   ${CMAKE_CURRENT_LIST_DIR}/DisplayTracks.h
   ${CMAKE_CURRENT_LIST_DIR}/DisplayTracks.cpp
   ${CMAKE_CURRENT_LIST_DIR}/ClipDisplayCache.h
   ${CMAKE_CURRENT_LIST_DIR}/ClipDisplayCache.cpp
   ${CMAKE_CURRENT_LIST_DIR}/WaveDisplay.cpp
   ${CMAKE_CURRENT_LIST_DIR}/Spectrogram.cpp
   ${CMAKE_CURRENT_LIST_DIR}/DebugCommands.cpp
)
