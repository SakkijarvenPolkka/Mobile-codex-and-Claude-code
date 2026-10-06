# PortAudio (portable core + platform host-API table) for the Audacity
# Android port.  Input: PORTAUDIO_SOURCE_DIR (pinned upstream checkout).
#
# Host-API selection:
#   Android: native/portaudio-android/pa_android_hostapis.c
#            + AAudio back end when PA_USE_AAUDIO=ON (sources listed in
#              native/portaudio-android/sources.cmake)
#   Linux host: upstream pa_unix_hostapis.c with no back end enabled
#            (the host build is for headless unit tests).
option(PA_USE_AAUDIO "PortAudio: build the AAudio host API (Android)" OFF)
option(PA_USE_SKELETON "PortAudio: build the skeleton host API" OFF)
set(PA_ANDROID_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../portaudio-android"
   CACHE PATH "Directory with the Android PortAudio host API sources")
get_filename_component(PA_ANDROID_DIR "${PA_ANDROID_DIR}" ABSOLUTE)

set(_pa ${PORTAUDIO_SOURCE_DIR})
set(PA_SOURCES
   ${_pa}/src/common/pa_allocation.c
   ${_pa}/src/common/pa_converters.c
   ${_pa}/src/common/pa_cpuload.c
   ${_pa}/src/common/pa_debugprint.c
   ${_pa}/src/common/pa_dither.c
   ${_pa}/src/common/pa_front.c
   ${_pa}/src/common/pa_process.c
   ${_pa}/src/common/pa_ringbuffer.c
   ${_pa}/src/common/pa_stream.c
   ${_pa}/src/common/pa_trace.c
   ${_pa}/src/os/unix/pa_unix_util.c
   ${_pa}/src/os/unix/pa_pthread_util.c
)
set(PA_INCLUDES ${_pa}/include ${_pa}/src/common ${_pa}/src/os/unix)
set(PA_DEFINES)
set(PA_LIBS Threads::Threads)

if(ANDROID)
   list(APPEND PA_SOURCES ${PA_ANDROID_DIR}/pa_android_hostapis.c)
   if(PA_USE_AAUDIO)
      include(${PA_ANDROID_DIR}/sources.cmake)
      if(NOT PA_AAUDIO_SOURCES)
         message(FATAL_ERROR "PA_USE_AAUDIO=ON but ${PA_ANDROID_DIR}/sources.cmake lists no PA_AAUDIO_SOURCES")
      endif()
      list(APPEND PA_SOURCES ${PA_AAUDIO_SOURCES})
      list(APPEND PA_INCLUDES ${PA_AAUDIO_INCLUDE_DIRS})
      list(APPEND PA_DEFINES PA_USE_AAUDIO=1)
      list(APPEND PA_LIBS aaudio log)
   endif()
else()
   list(APPEND PA_SOURCES ${_pa}/src/os/unix/pa_unix_hostapis.c)
endif()
if(PA_USE_SKELETON)
   list(APPEND PA_SOURCES ${_pa}/src/hostapi/skeleton/pa_hostapi_skeleton.c)
   list(APPEND PA_DEFINES PA_USE_SKELETON=1)
endif()

add_library(portaudio SHARED ${PA_SOURCES})
target_include_directories(portaudio PRIVATE ${PA_INCLUDES})
target_include_directories(portaudio PUBLIC ${_pa}/include)
target_compile_definitions(portaudio PRIVATE ${PA_DEFINES}
   $<$<CONFIG:Debug>:PA_ENABLE_DEBUG_OUTPUT>)
target_compile_options(portaudio PRIVATE -w)
target_link_libraries(portaudio PRIVATE ${PA_LIBS} m)
if(ANDROID)
   target_link_libraries(portaudio PRIVATE log)
endif()
audacity_set_output_dir(portaudio)
add_library(portaudio::portaudio ALIAS portaudio)
