# PortAudio (portable core + platform host-API table) for the Audacity
# Android port.  Input: PORTAUDIO_SOURCE_DIR (pinned upstream checkout).
#
# Host-API selection (sources listed in native/portaudio-android/sources.cmake):
#   Android:    portaudio-android/pa_android_hostapis.c
#               + AAudio host API (src/pa_aaudio.c, links libaaudio) when
#                 PA_USE_AAUDIO=ON (the default on Android)
#   Linux host: with PA_USE_NULL=ON (the default): the same table and the
#               same AAudio host API, compiled against a simulated AAudio
#               device (src/pa_aaudio_null.c), registered as host API "Null"
#               -- real-time paced, for AudioIO tests without hardware.
#               With PA_USE_NULL=OFF: upstream pa_unix_hostapis.c with no
#               back end (zero devices).
# Extension headers for the bridge come from the separate INTERFACE target
# portaudio::android (pa_android_aaudio.h, and pa_null.h for host tests; it
# defines PA_HAS_AAUDIO_EXTENSIONS=1 when the functions exist and
# PA_HAS_NULL_DEVICE=1 on the host).  They are not PUBLIC on portaudio itself so
# that the ~400 objects which see PortAudio through lib-audio-devices keep their
# compile flags.
if(ANDROID)
   set(_pa_aaudio_default ON)
   set(_pa_null_default OFF)
else()
   set(_pa_aaudio_default OFF)
   set(_pa_null_default ON)
endif()
option(PA_USE_AAUDIO "PortAudio: build the AAudio host API (Android only, links libaaudio)" ${_pa_aaudio_default})
option(PA_USE_NULL "PortAudio: build the simulated 'Null' AAudio device (Linux host tests only)" ${_pa_null_default})
option(PA_USE_SKELETON "PortAudio: build the skeleton host API" OFF)
set(PA_ANDROID_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../portaudio-android"
   CACHE PATH "Directory with the Android PortAudio host API sources")
get_filename_component(PA_ANDROID_DIR "${PA_ANDROID_DIR}" ABSOLUTE)
include(${PA_ANDROID_DIR}/sources.cmake)

set(_pa ${PORTAUDIO_SOURCE_DIR})
# Upstream sources (compiled without warnings)
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
# Our sources (compiled with warnings)
set(PA_PORT_SOURCES)
set(PA_INCLUDES ${_pa}/include ${_pa}/src/common ${_pa}/src/os/unix)
# monotonic PaUtil_GetTime (CPU load) and nanosleep-based Pa_Sleep
set(PA_DEFINES HAVE_CLOCK_GETTIME HAVE_NANOSLEEP)
set(PA_PUBLIC_DEFINES)
set(PA_LIBS Threads::Threads)

if(ANDROID)
   if(PA_USE_NULL)
      message(FATAL_ERROR "PA_USE_NULL is for the Linux host build only")
   endif()
   list(APPEND PA_PORT_SOURCES ${PA_HOSTAPIS_TABLE})
   if(PA_USE_AAUDIO)
      list(APPEND PA_PORT_SOURCES ${PA_AAUDIO_SOURCES})
      list(APPEND PA_DEFINES PA_USE_AAUDIO=1)
      list(APPEND PA_PUBLIC_DEFINES PA_HAS_AAUDIO_EXTENSIONS=1)
      list(APPEND PA_LIBS aaudio)
   endif()
else()
   if(PA_USE_AAUDIO)
      message(FATAL_ERROR "PA_USE_AAUDIO needs Android (use PA_USE_NULL on the host)")
   endif()
   if(PA_USE_NULL)
      list(APPEND PA_PORT_SOURCES ${PA_HOSTAPIS_TABLE} ${PA_AAUDIO_SOURCES} ${PA_NULL_SOURCES})
      list(APPEND PA_INCLUDES ${PA_NULL_PRIVATE_INCLUDE_DIRS})
      list(APPEND PA_DEFINES PA_USE_AAUDIO=1 PA_AAUDIO_NULL_BACKEND=1)
      list(APPEND PA_PUBLIC_DEFINES PA_HAS_AAUDIO_EXTENSIONS=1 PA_HAS_NULL_DEVICE=1)
   else()
      list(APPEND PA_SOURCES ${_pa}/src/os/unix/pa_unix_hostapis.c)
   endif()
endif()
if(PA_USE_SKELETON)
   list(APPEND PA_SOURCES ${_pa}/src/hostapi/skeleton/pa_hostapi_skeleton.c)
   list(APPEND PA_DEFINES PA_USE_SKELETON=1)
endif()

set_source_files_properties(${PA_SOURCES} PROPERTIES COMPILE_OPTIONS "-w")
set_source_files_properties(${PA_PORT_SOURCES} PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Wshadow;-Wstrict-prototypes")

add_library(portaudio SHARED ${PA_SOURCES} ${PA_PORT_SOURCES})
set_target_properties(portaudio PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON C_EXTENSIONS ON)
target_include_directories(portaudio PRIVATE ${PA_INCLUDES})
target_include_directories(portaudio PRIVATE ${PA_AAUDIO_INCLUDE_DIRS})
target_include_directories(portaudio PUBLIC ${_pa}/include)
target_compile_definitions(portaudio PRIVATE ${PA_DEFINES}
   $<$<CONFIG:Debug>:PA_ENABLE_DEBUG_OUTPUT>)
target_link_libraries(portaudio PRIVATE ${PA_LIBS} m)
if(ANDROID)
   target_link_libraries(portaudio PRIVATE log)
endif()
audacity_set_output_dir(portaudio)
add_library(portaudio::portaudio ALIAS portaudio)

# portaudio::android -- PortAudio + the extension API of our host API
add_library(portaudio-android INTERFACE)
target_include_directories(portaudio-android INTERFACE ${PA_AAUDIO_INCLUDE_DIRS})
target_compile_definitions(portaudio-android INTERFACE ${PA_PUBLIC_DEFINES})
target_link_libraries(portaudio-android INTERFACE portaudio)
add_library(portaudio::android ALIAS portaudio-android)
