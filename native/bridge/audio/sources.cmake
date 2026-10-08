# SPDX-License-Identifier: GPL-2.0-or-later
# Sources of the bridge "audio" module (see native/bridge/MODULES.md):
# transport.* / audio.* (API.md), meters, transport snapshot, devices.
set(AUBRIDGE_AUDIO_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/AudioModule.h
   ${CMAKE_CURRENT_LIST_DIR}/AudioDevices.cpp
   ${CMAKE_CURRENT_LIST_DIR}/BridgeMeter.cpp
   ${CMAKE_CURRENT_LIST_DIR}/DefaultPlaybackPolicy.cpp
   ${CMAKE_CURRENT_LIST_DIR}/DefaultPlaybackPolicy.h
   ${CMAKE_CURRENT_LIST_DIR}/RegisterAudioModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/TransportCommands.cpp
   ${CMAKE_CURRENT_LIST_DIR}/TransportManager.cpp
   ${CMAKE_CURRENT_LIST_DIR}/TransportManager.h
   ${CMAKE_CURRENT_LIST_DIR}/TransportSnapshot.cpp
)
# PaAAudio_* extension API (pa_android_aaudio.h; the simulated device on the
# host)
set(AUBRIDGE_AUDIO_LIBRARIES portaudio::android)
