# Sources of the PortAudio AAudio host API (Audacity Android port).
#
# Included by native/cmake/deps/portaudio/CMakeLists.txt when PA_USE_AAUDIO is
# ON.  The agent that writes the AAudio back end lists its files here, e.g.
#
#   set(PA_AAUDIO_SOURCES
#      ${CMAKE_CURRENT_LIST_DIR}/src/pa_aaudio.c
#   )
#   set(PA_AAUDIO_INCLUDE_DIRS ${CMAKE_CURRENT_LIST_DIR}/include)
#
# The back end must define PaAAudio_Initialize() (see pa_android_hostapis.c)
# and is linked against the NDK's libaaudio (API level >= 26; minSdk is 28).
set(PA_AAUDIO_SOURCES)
set(PA_AAUDIO_INCLUDE_DIRS)
