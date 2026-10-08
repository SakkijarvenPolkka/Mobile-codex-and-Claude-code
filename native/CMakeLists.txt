#[[
Audacity (3.7.9 core libraries) -- native superbuild for the Android port.

Builds the toolkit-neutral Audacity libraries (native/audacity/libraries),
the import/export modules (native/audacity/modules/import-export) and all
their third-party dependencies, for

  * Android (arm64-v8a, x86_64; minSdk 28; ANDROID_STL=c++_shared), either
    from Gradle (externalNativeBuild) or standalone with the NDK toolchain
    file, and
  * the Linux host (x86_64), for unit tests run without an emulator.

The upstream per-library CMakeLists.txt files are used unchanged; the
helper functions they call are re-implemented in cmake/AudacityShim.cmake.
See BUILDING.md.

Targets for consumers:
  audacity-core   INTERFACE target linking every Audacity library and every
                  import/export module (modules with --no-as-needed so that
                  their static registrations run when the consumer is loaded)
  lib-*           individual libraries (and lib-*-interface aliases)
  mod-*           individual modules

Hooks for other components (added when the directory exists):
  bridge/   C++ bridge (all platforms)
  jni/      JNI glue (Android only)
  tests/    host tests (AUDACITY_ANDROID_BUILD_TESTS, host only)
]]

cmake_minimum_required(VERSION 3.24...3.31)

project(AudacityAndroidNative
   VERSION 3.7.9
   DESCRIPTION "Audacity core libraries for Android"
   LANGUAGES C CXX)

if(CMAKE_SOURCE_DIR STREQUAL CMAKE_BINARY_DIR)
   message(FATAL_ERROR "In-source builds are not allowed; use e.g. cmake -S native -B native/build-host")
endif()

# Android: the ~70 shared libraries pass C++ objects, exceptions and RTTI
# across library boundaries, so they must share ONE C++ runtime
# (c++_shared).  The NDK toolchain file silently defaults to c++_static,
# which would give every .so its own libc++ copy.  minSdk 28 is needed by
# getrandom() (expat) and is the app's minSdk.
if(ANDROID)
   set(_audacity_stl "${ANDROID_STL}")
   if(NOT _audacity_stl)
      set(_audacity_stl "${CMAKE_ANDROID_STL_TYPE}")
   endif()
   if(NOT _audacity_stl STREQUAL "c++_shared")
      message(FATAL_ERROR "The Audacity core libraries must be built with "
         "-DANDROID_STL=c++_shared (got '${_audacity_stl}').")
   endif()
   set(_audacity_api "${ANDROID_PLATFORM_LEVEL}")
   if(NOT _audacity_api)
      set(_audacity_api "${CMAKE_SYSTEM_VERSION}")
   endif()
   if(_audacity_api LESS 28)
      message(FATAL_ERROR "The Audacity core libraries need ANDROID_PLATFORM "
         "android-28 or later (got API level '${_audacity_api}').")
   endif()
endif()

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
   set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "Build type" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set_property(GLOBAL PROPERTY USE_FOLDERS ON)

set(AUDACITY_SOURCE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/audacity")
set(AUDACITY_PORT_CMAKE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
set(AUDACITY_PORT_COMPAT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/compat")

# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------
if(ANDROID)
   set(AUDACITY_ANDROID_BUILD_TESTS OFF CACHE BOOL "Host-only option" FORCE)
else()
   option(AUDACITY_ANDROID_BUILD_TESTS "Build the host tests in native/tests (host only)" ON)
endif()
option(AUDACITY_ANDROID_BUILD_UPSTREAM_TESTS
   "Also add upstream lib-*/tests directories (needs native/tests/UnitTestSupport.cmake to provide add_unit_test)" OFF)

# Upstream Linux/macOS builds do NOT compile with -fvisibility=hidden (their
# HAVE_VISIBILITY is only set after the libraries are configured), and the
# libraries rely on vague-linkage template statics being unified across
# shared objects (e.g. ClientData::Site<...>::GetFactories(), whose comment
# says "linker eliminates duplicates").  Hiding them would give every .so its
# own registry, and the export annotations are only complete for Windows'
# dllexport model (with ON, lib-wave-track fails to link).  Hence OFF; the
# <NAME>_API macros still expand to visibility("default") exactly as
# export_symbol_define() computes them.
option(AUDACITY_HIDDEN_VISIBILITY "Compile Audacity libraries with -fvisibility=hidden" OFF)

option(AUDACITY_USE_NYQUIST "Build libnyquist + lib-nyquist-effects" ON)

# Shared libraries must be fully resolved at link time (Android's loader is
# strict).  The NDK toolchain already adds -Wl,--no-undefined; do the same on
# the host so problems show up there first.
if(NOT ANDROID AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
   string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,--no-undefined")
endif()

option(AUDACITY_USE_MIDI "USE_MIDI: NoteTrack/MIDI playback (needs PortMidi; off in the port)" OFF)

# ---------------------------------------------------------------------------
# Features (upstream USE_* switches).  These feed configunix.h and the
# conditions in the upstream CMakeLists.txt files.
# ---------------------------------------------------------------------------
set(USE_LIBFLAC ON)
set(USE_LIBOGG ON)
set(USE_LIBVORBIS ON)
set(USE_LIBOPUS ON)
set(USE_OPUSFILE ON)
set(USE_LIBMPG123 ON)
set(USE_LIBMP3LAME ON)
set(USE_WAVPACK ON)
set(USE_LIBSNDFILE ON)
set(USE_LIBID3TAG OFF)
set(USE_MIDI ${AUDACITY_USE_MIDI})
set(USE_PORTMIXER OFF)
set(USE_FFMPEG OFF)
set(USE_VAMP OFF)
set(USE_LV2 OFF)
set(USE_VST OFF)
set(USE_LADSPA OFF)
set(USE_AUDIO_UNITS OFF)
set(DISABLE_DYNAMIC_LOADING_LAME ON)   # LAME is linked, not dlopen()ed

# ---------------------------------------------------------------------------
# Upstream helper functions, dependencies
# ---------------------------------------------------------------------------
include(${AUDACITY_PORT_CMAKE_DIR}/AudacityShim.cmake)
include(${AUDACITY_PORT_CMAKE_DIR}/Dependencies.cmake)

# Bundled third-party code (upstream cmake-proxies/CMakeLists.txt)
addlib(libsbsms   sbsms      SBSMS      NO  YES "sbsms >= 2.2.0")
addlib(soundtouch soundtouch SOUNDTOUCH NO  YES "soundtouch >= 1.7.1")
addlib(twolame    twolame    LIBTWOLAME NO  YES "twolame >= 0.3.13")
addlib(pffft      pffft      PFFFT      YES NO  "")

if(AUDACITY_USE_NYQUIST)
   set(USE_NYQUIST ON)
   add_subdirectory("${libsrc}/libnyquist" "${CMAKE_BINARY_DIR}/lib-src/libnyquist" EXCLUDE_FROM_ALL)
endif()

# configunix.h (force-included into every Audacity translation unit)
set(AUDACITY_NAME "audacity")
set(PKGLIBDIR "${CMAKE_INSTALL_PREFIX}/lib/audacity")
set(LIBDIR "${CMAKE_INSTALL_PREFIX}/lib")
configure_file("${AUDACITY_SOURCE_ROOT}/src/audacity_config.h.in" "${AUDACITY_CONFIG_HEADER}")

# Per-target deviations needed by the port (GUI-only sources etc.)
include(${AUDACITY_PORT_CMAKE_DIR}/PortFixups.cmake)

# Test-support hook (may define add_unit_test() and Catch2) -- host only
if(AUDACITY_ANDROID_BUILD_TESTS AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/UnitTestSupport.cmake")
   include("${CMAKE_CURRENT_SOURCE_DIR}/tests/UnitTestSupport.cmake")
endif()

# ---------------------------------------------------------------------------
# Audacity libraries -- upstream order (libraries/CMakeLists.txt), minus the
# wx-GUI / desktop-plug-in libraries that are not part of the port.
# ---------------------------------------------------------------------------
set(AUDACITY_LIBRARIES
   lib-string-utils
   lib-strings
   lib-utility
   lib-uuid
   lib-components
   lib-basic-ui
   lib-exceptions
   lib-preferences
   lib-math
   lib-files
   lib-import-export
   lib-ipc
   lib-registries
   lib-xml
   lib-audio-devices
   lib-project
   lib-screen-geometry
   lib-project-rate
   lib-file-formats
   lib-track
   lib-transactions
   lib-sample-track
   lib-module-manager
   lib-project-history
   lib-graphics
   lib-audio-graph
   lib-tags
   lib-realtime-effects
   lib-audio-io
   lib-wave-track
   lib-wave-track-settings
   lib-wave-track-paint
   lib-track-selection
   lib-project-file-io
   lib-command-parameters
   lib-numeric-formats
   lib-effects
   lib-time-frequency-selection
   lib-time-track
   lib-snapping
   lib-playable-track
   lib-time-and-pitch
   lib-mixer
   lib-channel
   lib-stretching-sequence
   lib-note-track
   lib-viewport
   lib-music-information-retrieval
   lib-crypto
   lib-fft
   lib-concurrency
   lib-sqlite-helpers
   lib-dynamic-range-processor
   lib-builtin-effects
   lib-label-track
   lib-wave-track-fft
)
if(USE_NYQUIST)
   list(APPEND AUDACITY_LIBRARIES lib-nyquist-effects)
endif()
# If Sentry reporting is disabled (it is), this is an INTERFACE library
list(APPEND AUDACITY_LIBRARIES lib-sentry-reporting)

foreach(lib IN LISTS AUDACITY_LIBRARIES)
   add_subdirectory("${AUDACITY_SOURCE_ROOT}/libraries/${lib}" "${CMAKE_BINARY_DIR}/libraries/${lib}")
endforeach()

# Port library standing in for the application-layer calls of mod-aup and
# mod-lof (ProjectFileManager, ProjectManager, GetProjectFrame); links all of
# the above, like the upstream application target.
get_property(AUDACITY_APP_SERVICES_LIBRARIES GLOBAL PROPERTY AUDACITY_ALL_LIBRARIES)
add_subdirectory("${AUDACITY_PORT_COMPAT_DIR}/app-services" "${CMAKE_BINARY_DIR}/compat/app-services")

# ---------------------------------------------------------------------------
# Import/export modules (upstream modules/import-export/CMakeLists.txt order)
# ---------------------------------------------------------------------------
set(AUDACITY_MODULES mod-mp3 mod-pcm mod-lof mod-aup)
if(USE_LIBOGG AND USE_LIBVORBIS)
   list(APPEND AUDACITY_MODULES mod-ogg)
endif()
if(USE_LIBFLAC)
   list(APPEND AUDACITY_MODULES mod-flac)
endif()
if(USE_LIBTWOLAME)
   list(APPEND AUDACITY_MODULES mod-mp2)
endif()
if(USE_WAVPACK)
   list(APPEND AUDACITY_MODULES mod-wavpack)
endif()
if(USE_LIBMPG123)
   list(APPEND AUDACITY_MODULES mod-mpg123)
endif()
if(USE_LIBOPUS AND USE_OPUSFILE AND USE_LIBOGG)
   list(APPEND AUDACITY_MODULES mod-opus)
endif()
# mod-lof imports .lof "list of files" documents that name other files by
# path: an Android app only sees copies of the files the user picked (staged
# in cacheDir/import), so those paths never resolve, and Importer::Import
# cannot disable a registered plug-in at run time.  Leave it out on Android
# by default; the host keeps it (tests/smoke imports a .lof).
if(ANDROID)
   set(_audacity_default_skipped_modules "mod-lof")
else()
   set(_audacity_default_skipped_modules "")
endif()
set(AUDACITY_SKIPPED_MODULES "${_audacity_default_skipped_modules}" CACHE STRING
   "Modules to leave out, e.g. mod-aup;mod-lof (default: mod-lof on Android, none on the host)")
list(REMOVE_ITEM AUDACITY_MODULES ${AUDACITY_SKIPPED_MODULES})
message(STATUS "Skipped modules:    ${AUDACITY_SKIPPED_MODULES}")

foreach(mod IN LISTS AUDACITY_MODULES)
   add_subdirectory("${AUDACITY_SOURCE_ROOT}/modules/import-export/${mod}"
      "${CMAKE_BINARY_DIR}/modules/${mod}")
endforeach()

# Executables that upstream builds as part of a library directory are not
# wanted on Android.
if(ANDROID AND TARGET riff-test-util)
   set_target_properties(riff-test-util PROPERTIES EXCLUDE_FROM_ALL ON)
endif()

# ---------------------------------------------------------------------------
# audacity-core: one target to link everything
# ---------------------------------------------------------------------------
# Several shared objects are only "registrars" from a client's point of view:
# the modules register importers/exporters, and libraries such as
# lib-project-file-io (installs the SQLite SampleBlockFactory),
# lib-builtin-effects, lib-nyquist-effects, ... register factories from
# static initializers and are referenced by no other library.  They must all
# stay in the client's DT_NEEDED so that loading the client runs those
# initializers, even when the linker uses --as-needed (Ubuntu's GCC passes it
# by default).  Hence consumers of audacity-core link with --no-as-needed
# (a link option placed before all libraries on the link line).
get_property(AUDACITY_ALL_LIBRARIES GLOBAL PROPERTY AUDACITY_ALL_LIBRARIES)
get_property(AUDACITY_ALL_MODULES GLOBAL PROPERTY AUDACITY_ALL_MODULES)
add_library(audacity-core INTERFACE)
target_link_libraries(audacity-core INTERFACE
   ${AUDACITY_ALL_LIBRARIES}
   ${AUDACITY_ALL_MODULES}
   wxBase)
target_link_options(audacity-core INTERFACE "LINKER:--no-as-needed")
# Compile settings a consumer needs to include Audacity headers the same way
# the libraries are compiled (config header, version macros, wxBase subset).
set(_core_opts)
audacity_append_common_compiler_options(_core_opts NO)
list(REMOVE_ITEM _core_opts PRIVATE)
target_compile_options(audacity-core INTERFACE ${_core_opts})
target_compile_definitions(audacity-core INTERFACE ${_core_opts_DEFINITIONS} ${WXBASE_RESTRICTIONS})
add_library(Audacity::core ALIAS audacity-core)

message(STATUS "Audacity libraries: ${AUDACITY_ALL_LIBRARIES}")
message(STATUS "Audacity modules:   ${AUDACITY_ALL_MODULES}")
message(STATUS "Output directory:   ${AUDACITY_LIB_OUTPUT_DIR}")

# A convenience target that builds every library and module
add_custom_target(audacity-all)
foreach(t IN LISTS AUDACITY_ALL_LIBRARIES AUDACITY_ALL_MODULES)
   get_target_property(_type ${t} TYPE)
   if(NOT _type STREQUAL "INTERFACE_LIBRARY")
      add_dependencies(audacity-all ${t})
   endif()
endforeach()

# ---------------------------------------------------------------------------
# Hooks for the other components of the port
# ---------------------------------------------------------------------------
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/bridge/CMakeLists.txt")
   add_subdirectory(bridge)
endif()
if(ANDROID AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/jni/CMakeLists.txt")
   add_subdirectory(jni)
endif()
if(AUDACITY_ANDROID_BUILD_TESTS AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/CMakeLists.txt")
   enable_testing()
   add_subdirectory(tests)
endif()
# PortAudio host-API tests on the simulated "Null" AAudio device (host only;
# owned by the PortAudio port, see native/portaudio-android/README.md)
if(AUDACITY_ANDROID_BUILD_TESTS AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests/portaudio/CMakeLists.txt")
   enable_testing()
   add_subdirectory(tests/portaudio)
endif()

audacity_port_finalize()
