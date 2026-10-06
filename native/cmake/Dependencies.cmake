#[[
Third-party dependencies of the Audacity core libraries, built from source for
both the host (Linux, unit tests) and Android.  Nothing is taken from host
system packages except the C/C++ runtime (and zlib from the NDK sysroot /
host, which wxBase needs and every Android device ships as a stable NDK API).

All versions are pinned (archive + SHA-256, or git commit).  See
native/BUILDING.md and the build-system notes for the list.

Exported (Audacity-facing) target names -- these are the names the vendored
upstream CMakeLists.txt files use:
   wxBase                      wxWidgets base library (SHARED, via make_wxBase)
   expat::expat                STATIC  (only lib-xml uses it)
   rapidjson::rapidjson        header only
   SndFile::sndfile            -> audacity-sndfile, SHARED (see below)
   Ogg::ogg Vorbis::vorbis Vorbis::vorbisfile Vorbis::vorbisenc   STATIC
   FLAC::FLAC FLAC::FLAC++     STATIC
   Opus::opus opusfile::opusfile                                   STATIC
   mpg123::libmpg123           STATIC
   libmp3lame::libmp3lame      STATIC
   wavpack::wavpack            STATIC
   portaudio::portaudio        SHARED (two Audacity libraries call Pa_* and must
                               share one PortAudio instance)
   libuuid::libuuid            STATIC (tiny compat implementation)
   portmidi::portmidi          empty INTERFACE (USE_MIDI off); with USE_MIDI on:
                               STATIC PortMidi with the null back end
   + the bundled lib-src libraries via addlib() (sqlite SHARED, the rest STATIC)

Codec libraries that are used by exactly one module are linked statically into
that module (same as having a private copy); libraries that are used by more
than one Audacity library (wxBase, sndfile, portaudio, sqlite) are SHARED so
the process has exactly one instance of their global state, like the desktop
Linux build that uses the distribution's shared libraries.
]]

include_guard(GLOBAL)
include(${CMAKE_CURRENT_LIST_DIR}/DepsCache.cmake)

set(AUDACITY_PORT_CMAKE_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(AUDACITY_PORT_PATCH_DIR "${CMAKE_CURRENT_LIST_DIR}/patches")

# Third-party projects: honour normal variables in option() (CMP0077), accept
# old cmake_minimum_required() values with newer CMake, always PIC.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Nothing of this superbuild is ever installed (Gradle packages the .so files
# itself).  Skipping install rules also avoids clashes between the global
# export-set names of different projects (FLAC and mpg123 both use "targets").
set(CMAKE_SKIP_INSTALL_RULES ON)

find_package(Threads REQUIRED)

# ---------------------------------------------------------------------------
# Pinned sources
# ---------------------------------------------------------------------------
audacity_fetch_archive(wxwidgets
   URL https://github.com/wxWidgets/wxWidgets/releases/download/v3.2.8/wxWidgets-3.2.8.tar.bz2
   SHA256 c74784904109d7229e6894c85cfa068f1106a4a07c144afd78af41f373ee0fe6
   PATCHES "${AUDACITY_PORT_PATCH_DIR}/wxWidgets-3.2.8-android-lp64.patch"
           "${AUDACITY_PORT_PATCH_DIR}/wxWidgets-3.2.8-android-base-features.patch"
           "${AUDACITY_PORT_PATCH_DIR}/wxWidgets-3.2.8-android-export-wcsto.patch")

audacity_fetch_archive(expat
   URL https://github.com/libexpat/libexpat/releases/download/R_2_7_1/expat-2.7.1.tar.xz
   SHA256 354552544b8f99012e5062f7d570ec77f14b412a3ff5c7d8d0dae62c0d217c30)

# rapidjson: pinned master commit (the 1.1.0 release does not compile with
# clang >= 19, which NDK r28 uses).
audacity_fetch_git(rapidjson
   REPOSITORY https://github.com/Tencent/rapidjson.git
   COMMIT 24b5e7a8b27f42fa16b96fc70aade9106cf7102f)

audacity_fetch_archive(sndfile
   URL https://github.com/libsndfile/libsndfile/releases/download/1.2.2/libsndfile-1.2.2.tar.xz
   SHA256 3799ca9924d3125038880367bf1468e53a1b7e3686a934f098b7e1d286cdb80e)

audacity_fetch_archive(ogg
   URL https://github.com/xiph/ogg/releases/download/v1.3.5/libogg-1.3.5.tar.xz
   MIRRORS https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.xz
   SHA256 c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705)

audacity_fetch_archive(vorbis
   URL https://github.com/xiph/vorbis/releases/download/v1.3.7/libvorbis-1.3.7.tar.xz
   MIRRORS https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz
   SHA256 b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b)

audacity_fetch_archive(flac
   URL https://github.com/xiph/flac/releases/download/1.4.3/flac-1.4.3.tar.xz
   MIRRORS https://downloads.xiph.org/releases/flac/flac-1.4.3.tar.xz
   SHA256 6c58e69cd22348f441b861092b825e591d0b822e106de6eb0ee4d05d27205b70)

audacity_fetch_archive(opus
   URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
   MIRRORS https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
   SHA256 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)

# opusfile 0.12 has no CMake build; native/cmake/deps/opusfile provides one.
audacity_fetch_archive(opusfile
   URL https://github.com/xiph/opusfile/releases/download/v0.12/opusfile-0.12.tar.gz
   MIRRORS https://downloads.xiph.org/releases/opus/opusfile-0.12.tar.gz
   SHA256 118d8601c12dd6a44f52423e68ca9083cc9f2bfe72da7a8c1acb22a80ae3550b)

audacity_fetch_archive(mpg123
   URL https://www.mpg123.de/download/mpg123-1.32.10.tar.bz2
   MIRRORS https://downloads.sourceforge.net/project/mpg123/mpg123/1.32.10/mpg123-1.32.10.tar.bz2
   SHA256 87b2c17fe0c979d3ef38eeceff6362b35b28ac8589fbf1854b5be75c9ab6557c)

# LAME has no CMake build; native/cmake/deps/lame provides one.
audacity_fetch_archive(lame
   URL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
   MIRRORS http://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz
   FILENAME lame-3.100.tar.gz
   SHA256 ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e)

audacity_fetch_archive(wavpack
   URL https://github.com/dbry/WavPack/releases/download/5.7.0/wavpack-5.7.0.tar.xz
   SHA256 e81510fd9ec5f309f58d5de83e9af6c95e267a13753d7e0bbfe7b91273a88bee)

# PortAudio: pinned master commit.  Its own CMake build is not used: PortAudio
# has no Android host API, so native/cmake/deps/portaudio compiles the
# portable core plus the host-API table from native/portaudio-android.
audacity_fetch_git(portaudio
   REPOSITORY https://github.com/PortAudio/portaudio.git
   COMMIT 873e3c83fbe2f57ebcf59083e627a3f8fa051ffe)

# PortMidi v2.0.8 (only fetched when USE_MIDI is ON; built with the PMNULL
# back end, i.e. no MIDI devices).  audacity_fetch_git() clones the mirror
# when it is declared, so do not declare it at all without USE_MIDI.
if(USE_MIDI)
   audacity_fetch_git(portmidi
      REPOSITORY https://github.com/PortMidi/portmidi.git
      COMMIT 101dac9455e2718512c94e24cbcae6a6f34b908b)
endif()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Build a third-party project quietly (-w) and with the given cache-like
# variables, scoped to this function call.
macro(_audacity_quiet_flags)
   string(APPEND CMAKE_C_FLAGS " -w")
   string(APPEND CMAKE_CXX_FLAGS " -w")
endmacro()

# ---------------------------------------------------------------------------
# wxWidgets (base only)
# ---------------------------------------------------------------------------
function(_audacity_add_wxwidgets)
   set(wxBUILD_SHARED ON)
   set(wxBUILD_MONOLITHIC OFF)
   set(wxBUILD_SAMPLES OFF)
   set(wxBUILD_TESTS OFF)
   set(wxBUILD_DEMOS OFF)
   set(wxBUILD_BENCHMARKS OFF)
   set(wxBUILD_INSTALL OFF)
   set(wxBUILD_PRECOMP OFF)
   set(wxUSE_GUI OFF)
   set(wxUSE_ZLIB sys)
   set(wxUSE_LIBLZMA OFF)
   set(wxUSE_LIBICONV OFF)
   set(wxUSE_SECRETSTORE OFF)
   set(wxUSE_WEBREQUEST OFF)
   set(wxUSE_LIBMSPACK OFF)
   set(wxUSE_LIBSDL OFF)
   set(wxUSE_REGEX builtin)
   set(wxUSE_EXPAT builtin)
   # No wx networking (Audacity uses none of it; lib-ipc has its own sockets).
   # This also means wxBase is the only wx shared library target, so Gradle,
   # which builds/packages every shared-library target of the project, does
   # not pick up libwx_baseu_net.
   set(wxUSE_SOCKETS OFF)
   set(wxUSE_IPC OFF)
   set(wxUSE_URL OFF)
   set(wxUSE_PROTOCOL OFF)
   set(wxUSE_FS_INET OFF)
   # Make the cache agree with what we force (wx reads some of these as
   # cache entries through its own wx_option()).
   foreach(v wxBUILD_SHARED wxBUILD_MONOLITHIC wxBUILD_SAMPLES wxBUILD_TESTS
         wxBUILD_DEMOS wxBUILD_BENCHMARKS wxBUILD_INSTALL wxBUILD_PRECOMP wxUSE_GUI
         wxUSE_ZLIB wxUSE_LIBLZMA wxUSE_LIBICONV wxUSE_SECRETSTORE wxUSE_WEBREQUEST
         wxUSE_LIBMSPACK wxUSE_LIBSDL wxUSE_REGEX wxUSE_EXPAT
         wxUSE_SOCKETS wxUSE_IPC wxUSE_URL wxUSE_PROTOCOL wxUSE_FS_INET)
      set(${v} "${${v}}" CACHE STRING "Forced by the Audacity Android superbuild" FORCE)
   endforeach()
   _audacity_quiet_flags()
   audacity_add_fetched(wxwidgets)
endfunction()
_audacity_add_wxwidgets()

audacity_unversion_shared_library(wxbase)
audacity_set_output_dir(wxbase)

# Upstream make_wxBase(): an interface target exposing only the toolkit-neutral
# subset of wxWidgets.  (wxUSE_GUI is 0 in our wx build anyway.)
#
# __WXGTK__: on desktop Linux every Audacity library is compiled with the
# flags of the GTK wx port (wx-config adds -D__WXGTK__), while libwx_baseu
# itself is toolkit independent (wx/platform.h undefines __WXGTK__ when
# building a GUI-less library).  The toolkit-neutral Audacity code uses
# __WXGTK__ as its "Linux" switch (file paths, forbidden file-name chars,
# ...).  Android is Linux, so the port keeps exactly the desktop Linux
# definition -- as Audacity 4 does for its au3 libraries (au3defs.cmake).
add_library(wxBase INTERFACE)
target_link_libraries(wxBase INTERFACE wx::base Threads::Threads ${CMAKE_DL_LIBS})
target_compile_definitions(wxBase INTERFACE __WXGTK__)

# ---------------------------------------------------------------------------
# expat (lib-xml)
# ---------------------------------------------------------------------------
function(_audacity_add_expat)
   set(EXPAT_BUILD_TOOLS OFF)
   set(EXPAT_BUILD_EXAMPLES OFF)
   set(EXPAT_BUILD_TESTS OFF)
   set(EXPAT_BUILD_DOCS OFF)
   set(EXPAT_BUILD_FUZZERS OFF)
   set(EXPAT_BUILD_PKGCONFIG OFF)
   set(EXPAT_ENABLE_INSTALL OFF)
   set(EXPAT_SHARED_LIBS OFF)
   _audacity_quiet_flags()
   audacity_add_fetched(expat)
endfunction()
_audacity_add_expat()

# ---------------------------------------------------------------------------
# rapidjson (header only)
# ---------------------------------------------------------------------------
FetchContent_MakeAvailable(rapidjson)
add_library(audacity-rapidjson INTERFACE)
target_include_directories(audacity-rapidjson SYSTEM INTERFACE "${rapidjson_SOURCE_DIR}/include")
add_library(rapidjson::rapidjson ALIAS audacity-rapidjson)

# ---------------------------------------------------------------------------
# libsndfile: built static by its own CMake, then wrapped into ONE shared
# library (libsndfile.so).  Its own shared build needs Python and a
# GNU version script (which breaks with the NDK's --no-undefined-version).
# The Audacity CMakeLists name "SndFile::sndfile"; the shim remaps that name
# to audacity-sndfile (see AUDACITY_TARGET_REMAP in AudacityShim.cmake).
# ---------------------------------------------------------------------------
function(_audacity_add_sndfile)
   set(BUILD_SHARED_LIBS OFF)
   set(BUILD_PROGRAMS OFF)
   set(BUILD_EXAMPLES OFF)
   set(BUILD_TESTING OFF)
   set(BUILD_REGTEST OFF)
   set(ENABLE_EXTERNAL_LIBS OFF)
   set(ENABLE_MPEG OFF)
   set(ENABLE_CPACK OFF)
   set(ENABLE_PACKAGE_CONFIG OFF)
   set(INSTALL_PKGCONFIG_MODULE OFF)
   set(INSTALL_MANPAGES OFF)
   set(ENABLE_EXPERIMENTAL OFF)
   _audacity_quiet_flags()
   audacity_add_fetched(sndfile)
endfunction()
_audacity_add_sndfile()

add_library(audacity-sndfile SHARED "${AUDACITY_PORT_CMAKE_DIR}/deps/sndfile/sndfile_shared.c")
target_link_libraries(audacity-sndfile PRIVATE "$<LINK_LIBRARY:WHOLE_ARCHIVE,sndfile>")
target_include_directories(audacity-sndfile PUBLIC
   "$<TARGET_PROPERTY:sndfile,INTERFACE_INCLUDE_DIRECTORIES>")
set_target_properties(audacity-sndfile PROPERTIES OUTPUT_NAME sndfile)
audacity_set_output_dir(audacity-sndfile)

# ---------------------------------------------------------------------------
# Xiph: ogg, vorbis, FLAC, opus, opusfile
# ---------------------------------------------------------------------------
function(_audacity_add_xiph)
   set(BUILD_SHARED_LIBS OFF)
   set(BUILD_TESTING OFF)
   set(INSTALL_DOCS OFF)
   set(INSTALL_PKG_CONFIG_MODULE OFF)
   set(INSTALL_CMAKE_PACKAGE_MODULE OFF)
   _audacity_quiet_flags()

   # ogg; make find_package(Ogg) in vorbis/flac resolve to this target.
   # Pre-seeding the cache variables makes vorbis' FindOgg.cmake accept the
   # in-tree target (FLAC checks for TARGET Ogg::ogg itself).
   audacity_add_fetched(ogg)
   set(OGG_INCLUDE_DIR "${ogg_SOURCE_DIR}/include;${ogg_BINARY_DIR}/include" CACHE INTERNAL "")
   set(OGG_LIBRARY Ogg::ogg CACHE INTERNAL "")

   # vorbis
   set(BUILD_FRAMEWORK OFF)
   audacity_add_fetched(vorbis)
   # vorbis 1.3.7 defines no namespaced aliases
   add_library(Vorbis::vorbis ALIAS vorbis)
   add_library(Vorbis::vorbisenc ALIAS vorbisenc)
   add_library(Vorbis::vorbisfile ALIAS vorbisfile)

   # FLAC (+ FLAC++ which mod-flac uses)
   set(BUILD_CXXLIBS ON)
   set(BUILD_PROGRAMS OFF)
   set(BUILD_EXAMPLES OFF)
   set(BUILD_DOCS OFF)
   set(INSTALL_MANPAGES OFF)
   set(INSTALL_PKGCONFIG_MODULES OFF)
   set(INSTALL_CMAKE_CONFIG_MODULE OFF)
   set(WITH_OGG ON)
   set(WITH_FORTIFY_SOURCE OFF)   # NDK already passes -D_FORTIFY_SOURCE=2
   set(WITH_STACK_PROTECTOR OFF)  # NDK already passes -fstack-protector-strong
   set(ENABLE_MULTITHREADING ON)
   audacity_add_fetched(flac)

   # opus
   set(OPUS_BUILD_SHARED_LIBRARY OFF)
   set(OPUS_BUILD_TESTING OFF)
   set(OPUS_BUILD_PROGRAMS OFF)
   set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF)
   set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF)
   set(OPUS_FORTIFY_SOURCE OFF)
   set(OPUS_STACK_PROTECTOR OFF)
   audacity_add_fetched(opus)

   # opusfile (our CMake)
   FetchContent_MakeAvailable(opusfile)
   set(OPUSFILE_SOURCE_DIR "${opusfile_SOURCE_DIR}")
   set(OPUS_SOURCE_DIR "${opus_SOURCE_DIR}")
   add_subdirectory("${AUDACITY_PORT_CMAKE_DIR}/deps/opusfile" "${CMAKE_BINARY_DIR}/_deps/opusfile-port" EXCLUDE_FROM_ALL)
endfunction()
_audacity_add_xiph()

# ---------------------------------------------------------------------------
# mpg123 (decoder only)
# ---------------------------------------------------------------------------
function(_audacity_add_mpg123)
   set(BUILD_SHARED_LIBS OFF)
   set(BUILD_LIBOUT123 OFF)
   set(BUILD_PROGRAMS OFF)
   set(NETWORK OFF)
   set(USE_MODULES OFF)
   set(NO_FEATURE_REPORT OFF)
   _audacity_quiet_flags()
   audacity_add_fetched(mpg123 ports/cmake)
endfunction()
_audacity_add_mpg123()
# libmpg123's build interface only exports its binary dir; mpg123.h lives in
# src/include of the source tree.
FetchContent_GetProperties(mpg123)
add_library(audacity-mpg123 INTERFACE)
target_link_libraries(audacity-mpg123 INTERFACE libmpg123)
target_include_directories(audacity-mpg123 INTERFACE "${mpg123_SOURCE_DIR}/src/include")
add_library(mpg123::libmpg123 ALIAS audacity-mpg123)

# ---------------------------------------------------------------------------
# LAME (encoder only, our CMake)
# ---------------------------------------------------------------------------
FetchContent_MakeAvailable(lame)
set(LAME_SOURCE_DIR "${lame_SOURCE_DIR}")
add_subdirectory("${AUDACITY_PORT_CMAKE_DIR}/deps/lame" "${CMAKE_BINARY_DIR}/_deps/lame-port" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# WavPack
# ---------------------------------------------------------------------------
function(_audacity_add_wavpack)
   set(BUILD_SHARED_LIBS OFF)
   set(BUILD_TESTING OFF)
   set(WAVPACK_BUILD_PROGRAMS OFF)
   set(WAVPACK_BUILD_DOCS OFF)
   set(WAVPACK_INSTALL_DOCS OFF)
   set(WAVPACK_INSTALL_CMAKE_MODULE OFF)
   set(WAVPACK_INSTALL_PKGCONFIG_MODULE OFF)
   set(WAVPACK_BUILD_COOLEDIT_PLUGIN OFF)
   set(WAVPACK_BUILD_WINAMP_PLUGIN OFF)
   set(WAVPACK_ENABLE_LEGACY OFF)
   _audacity_quiet_flags()
   audacity_add_fetched(wavpack)
endfunction()
_audacity_add_wavpack()
# Audacity includes <wavpack/wavpack.h> (installed layout).
FetchContent_GetProperties(wavpack)
configure_file("${wavpack_SOURCE_DIR}/include/wavpack.h"
   "${CMAKE_BINARY_DIR}/_deps/wavpack-include/wavpack/wavpack.h" COPYONLY)
add_library(audacity-wavpack INTERFACE)
target_link_libraries(audacity-wavpack INTERFACE wavpack)
target_include_directories(audacity-wavpack INTERFACE "${CMAKE_BINARY_DIR}/_deps/wavpack-include")
add_library(wavpack::wavpack ALIAS audacity-wavpack)

# ---------------------------------------------------------------------------
# PortAudio (our CMake; Android host-API hook in native/portaudio-android)
# ---------------------------------------------------------------------------
FetchContent_MakeAvailable(portaudio)
set(PORTAUDIO_SOURCE_DIR "${portaudio_SOURCE_DIR}")
add_subdirectory("${AUDACITY_PORT_CMAKE_DIR}/deps/portaudio" "${CMAKE_BINARY_DIR}/_deps/portaudio-port" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# Small compatibility pieces
# ---------------------------------------------------------------------------
add_subdirectory("${AUDACITY_PORT_CMAKE_DIR}/deps/libuuid" "${CMAKE_BINARY_DIR}/_deps/libuuid-port" EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# PortMidi -- only needed with USE_MIDI (MIDI playback); null back end, see
# native/cmake/deps/portmidi.  With USE_MIDI off nothing calls PortMidi, and
# lib-note-track's portmidi::portmidi link item is an empty interface.
# ---------------------------------------------------------------------------
if(USE_MIDI)
   FetchContent_MakeAvailable(portmidi)
   set(PORTMIDI_SOURCE_DIR "${portmidi_SOURCE_DIR}")
   add_subdirectory("${AUDACITY_PORT_CMAKE_DIR}/deps/portmidi" "${CMAKE_BINARY_DIR}/_deps/portmidi-port" EXCLUDE_FROM_ALL)
else()
   add_library(audacity-portmidi-disabled INTERFACE)
   add_library(portmidi::portmidi ALIAS audacity-portmidi-disabled)
endif()
