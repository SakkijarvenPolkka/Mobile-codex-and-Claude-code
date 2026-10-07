# SPDX-License-Identifier: GPL-2.0-or-later
#
# Sources of the PortAudio host API for Android (Audacity Android port).
# Included by native/cmake/deps/portaudio/CMakeLists.txt.
#
#   PA_HOSTAPIS_TABLE        host-API table used instead of upstream
#                            pa_unix_hostapis.c (Android always; Linux host
#                            when PA_USE_NULL is ON)
#   PA_AAUDIO_SOURCES        the AAudio host API (PaAAudio_Initialize)
#   PA_AAUDIO_INCLUDE_DIRS   public headers (pa_android_aaudio.h, pa_null.h)
#   PA_NULL_SOURCES          host only: simulated AAudio ("Null" device)
#   PA_NULL_PRIVATE_INCLUDE_DIRS
#                            host only: stand-in <aaudio/AAudio.h>
set(PA_HOSTAPIS_TABLE ${CMAKE_CURRENT_LIST_DIR}/pa_android_hostapis.c)
set(PA_AAUDIO_SOURCES ${CMAKE_CURRENT_LIST_DIR}/src/pa_aaudio.c)
set(PA_AAUDIO_INCLUDE_DIRS ${CMAKE_CURRENT_LIST_DIR}/include)
set(PA_NULL_SOURCES ${CMAKE_CURRENT_LIST_DIR}/src/pa_aaudio_null.c)
set(PA_NULL_PRIVATE_INCLUDE_DIRS ${CMAKE_CURRENT_LIST_DIR}/src/null)
