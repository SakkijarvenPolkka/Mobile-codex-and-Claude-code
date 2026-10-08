# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "edit" module (see native/bridge/MODULES.md):
# select.*, playRegion.*, edit.*, tracks.*, clips.*, labels.* (API.md §3.3).
set(AUBRIDGE_EDIT_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/RegisterEditModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EditUtil.h
   ${CMAKE_CURRENT_LIST_DIR}/EditUtil.cpp
   ${CMAKE_CURRENT_LIST_DIR}/SelectCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/EditCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/TrackCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/ClipCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/LabelCommands.cpp
)
