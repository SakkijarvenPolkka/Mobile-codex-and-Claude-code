#[[
AudacityShim.cmake -- re-implementation of the helper functions that
Audacity 3.7.9's per-library CMakeLists.txt files call
(cmake-proxies/cmake-modules/AudacityFunctions.cmake upstream; a copy is kept
for reference in native/audacity/cmake-proxies/AudacityFunctions.reference.cmake),
so that the vendored upstream CMakeLists.txt files of the libraries, modules
and bundled lib-src projects can be used unchanged.

Differences from upstream, all deliberate (see native/BUILDING.md):
 * Modules (mod-*) are SHARED libraries, not MODULE libraries, so that the
   bridge can link them (on Android every module must be loaded through
   DT_NEEDED of the bridge to share one symbol-lookup group; see notes).
   Upstream does the same on Windows.
 * Libraries keep upstream's file names (lib-strings.so); modules get a "lib"
   prefix (libmod-pcm.so) because Android only packages/extracts lib*.so.
 * Everything is written to one directory, AUDACITY_LIB_OUTPUT_DIR.
 * No post-build `strip` (Gradle strips Android libraries itself).
 * Third-party target names can be remapped (AUDACITY_TARGET_REMAP).
 * Per-target port fixups (excluded GUI-only sources, stub include dirs) live
   in native/cmake/PortFixups.cmake and are applied by name.
 * Unit-test subdirectories (lib-*/tests) are only added when the host
   test option is on and a test-support hook defines add_unit_test().

The symbol export macros (<NAME>_API) are computed exactly as upstream:
export_symbol_define()/import_symbol_define() with HAVE_VISIBILITY set, i.e.
__attribute__((visibility("default"))).  Whether the default symbol visibility
is hidden is controlled by AUDACITY_HIDDEN_VISIBILITY (see the top-level
CMakeLists.txt for why it defaults to OFF).

Upstream code in this file is GPL-2.0-or-later (Audacity); so is this file.
]]

include_guard(GLOBAL)

# ---------------------------------------------------------------------------
# Global variables upstream's root CMakeLists.txt defines
# ---------------------------------------------------------------------------
set(_OPT "audacity_")
set(AUDACITY_VERSION 3)
set(AUDACITY_RELEASE 7)
set(AUDACITY_REVISION 9)
set(AUDACITY_MODLEVEL 0)
if(NOT DEFINED AUDACITY_BUILD_LEVEL)
   set(AUDACITY_BUILD_LEVEL 2 CACHE STRING "0 for alpha, 1 for beta, 2 for release builds")
endif()
if(AUDACITY_BUILD_LEVEL EQUAL 0)
   string(TIMESTAMP __TDATE__ "%Y%m%d")
   set(AUDACITY_SUFFIX "-alpha-${__TDATE__}")
elseif(AUDACITY_BUILD_LEVEL EQUAL 1)
   string(TIMESTAMP __TDATE__ "%Y%m%d")
   set(AUDACITY_SUFFIX "-beta-${__TDATE__}")
else()
   set(AUDACITY_SUFFIX "")
endif()

set(topdir "${AUDACITY_SOURCE_ROOT}")
set(libsrc "${topdir}/lib-src")

if(CMAKE_SIZEOF_VOID_P EQUAL 8)
   set(IS_64BIT ON)
endif()

# Upstream only adds -mmmx/-msse/-msse2 on 32-bit x86.
set(MMX_FLAG "")
set(SSE_FLAG "")
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64|i.86|x86)$")
   set(HAVE_MMX ON)
   set(HAVE_SSE ON)
   set(HAVE_SSE2 ON)
endif()

include(TestBigEndian)
test_big_endian(WORDS_BIGENDIAN)

include(CheckIncludeFile)
include(CheckSymbolExists)
include(CheckLibraryExists)
# As upstream: the math (and dl) libraries take part in the symbol checks
check_library_exists(m pow "" HAVE_LIBM)
if(HAVE_LIBM)
   list(APPEND CMAKE_REQUIRED_LIBRARIES m)
endif()
list(APPEND CMAKE_REQUIRED_LIBRARIES ${CMAKE_DL_LIBS})
check_include_file("alloca.h" HAVE_ALLOCA_H)
check_include_file("dlfcn.h" HAVE_DLFCN_H)
check_symbol_exists(lrint "math.h" HAVE_LRINT)
check_symbol_exists(lrintf "math.h" HAVE_LRINTF)
check_symbol_exists(mlock "sys/mman.h" HAVE_MLOCK)
check_symbol_exists(gmtime_r "time.h" HAVE_GMTIME_R)
check_symbol_exists(localtime_r "time.h" HAVE_LOCALTIME_R)
check_symbol_exists(fdatasync "unistd.h" HAVE_FDATASYNC)
check_symbol_exists(isnan "math.h" HAVE_ISNAN)

# Upstream sets this (Linux/macOS) when it generates the config header.
set(HAVE_VISIBILITY 1)

# Output layout.  Gradle passes CMAKE_LIBRARY_OUTPUT_DIRECTORY; keep it.
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
   set(AUDACITY_LIB_OUTPUT_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
else()
   set(AUDACITY_LIB_OUTPUT_DIR "${CMAKE_BINARY_DIR}/lib")
endif()
# Upstream composes "${_DESTDIR}/${_PKGLIB}" and "${_DESTDIR}/${_MODDIR}".
set(_DESTDIR "${AUDACITY_LIB_OUTPUT_DIR}")
set(_DEST "${_DESTDIR}")
set(_PKGLIB ".")
set(_MODDIR ".")
set(_LIBDIR "lib")
set(_DATADIR "share")
set(_PKGDATA "share/audacity/")
set(_EXEDIR "bin")
set(INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# ---------------------------------------------------------------------------
# Generic helpers (as upstream)
# ---------------------------------------------------------------------------
macro(def_vars)
   set(_SRCDIR "${CMAKE_CURRENT_SOURCE_DIR}")
   set(_INTDIR "${CMAKE_CURRENT_BINARY_DIR}")
   set(_PRVDIR "${CMAKE_CURRENT_BINARY_DIR}/private")
   set(_PUBDIR "${CMAKE_CURRENT_BINARY_DIR}/public")
endmacro()

macro(organize_source root prefix sources)
   set(cleaned)
   foreach(source ${sources})
      string(REGEX REPLACE ".*>:(.*)>*" "\\1" source "${source}")
      string(REPLACE ">" "" source "${source}")
      string(REGEX REPLACE "^[A-Z]+$" "" source "${source}")
      list(APPEND cleaned "${source}")
   endforeach()
   if("${prefix}" STREQUAL "")
      source_group(TREE "${root}" FILES ${cleaned})
   else()
      source_group(TREE "${root}" PREFIX ${prefix} FILES ${cleaned})
   endif()
endmacro()

function(set_dir_folder dir folder)
   get_property(subdirs DIRECTORY "${dir}" PROPERTY SUBDIRECTORIES)
   foreach(sub ${subdirs})
      set_dir_folder("${sub}" "${folder}")
   endforeach()
   get_property(targets DIRECTORY "${dir}" PROPERTY BUILDSYSTEM_TARGETS)
   foreach(target ${targets})
      get_target_property(type "${target}" TYPE)
      if(NOT "${type}" STREQUAL "INTERFACE_LIBRARY")
         set_target_properties(${target} PROPERTIES FOLDER ${folder})
      endif()
   endforeach()
endfunction()

macro(set_cache_value var value)
   set(${var} "${value}")
   set_property(CACHE ${var} PROPERTY VALUE "${value}")
endmacro()

macro(set_from_env var)
   if(NOT DEFINED ${var} AND NOT "$ENV{${var}}" STREQUAL "")
      set(${var} "$ENV{${var}}" ${ARGN})
   endif()
endmacro()

function(set_target_property_all target property value)
   set_target_properties("${target}" PROPERTIES "${property}" "${value}")
   foreach(type Debug Release RelWithDebInfo MinSizeRel ${CMAKE_CONFIGURATION_TYPES})
      string(TOUPPER "${property}_${type}" prop)
      set_target_properties("${target}" PROPERTIES "${prop}" "${value}")
   endforeach()
endfunction()

function(cmd_option name desc)
   cmake_parse_arguments(OPTION "" "" "STRINGS" ${ARGN})
   if(ARGC EQUAL 2)
      if(OPTION_STRINGS)
         list(GET OPTION_STRINGS 1 default)
      else()
         set(default ON)
      endif()
   else()
      set(default ${OPTION_UNPARSED_ARGUMENTS})
   endif()
   if(OPTION_STRINGS)
      set(cache_type STRING)
   else()
      set(cache_type BOOL)
   endif()
   set(${name} "${default}" CACHE ${cache_type} "${desc}")
   if(OPTION_STRINGS)
      set_property(CACHE ${name} PROPERTY STRINGS ${OPTION_STRINGS})
      if(NOT "${${name}}" IN_LIST OPTION_STRINGS)
         message(FATAL_ERROR "Invalid value \"${${name}}\" for option ${name}. Valid values are: ${OPTION_STRINGS}")
      endif()
   endif()
   set(${name} "${${name}}" PARENT_SCOPE)
endfunction()

function(copy_target_properties src dest)
   foreach(property ${ARGN})
      get_target_property(value ${src} ${property})
      if(value)
         set_target_properties(${dest} PROPERTIES ${property} "${value}")
      endif()
   endforeach()
endfunction()

function(make_interface_library new old)
   add_library(${new} INTERFACE)
   copy_target_properties(${old} ${new}
      INTERFACE_COMPILE_DEFINITIONS
      INTERFACE_COMPILE_OPTIONS
      INTERFACE_INCLUDE_DIRECTORIES
      INTERFACE_LINK_DIRECTORIES
      INTERFACE_LINK_LIBRARIES)
endfunction()

function(fix_bundle target_name)
   # macOS only upstream
endfunction()

# ---------------------------------------------------------------------------
# wxBase restrictions (upstream dependencies/wxwidgets.cmake)
# ---------------------------------------------------------------------------
set(WXBASE_RESTRICTIONS
   "wxUSE_GUI=0"
   _WX_APP_H_BASE_      # Don't use app.h
   _WX_EVTLOOP_H_       # Don't use evtloop.h
   _WX_IMAGE_H          # Don't use image.h
   _WX_COLOUR_H_BASE_   # Don't use colour.h
   _WX_BRUSH_H_BASE_    # Don't use brush.h
   _WX_PEN_H_BASE_      # Don't use pen.h
)
function(apply_wxbase_restrictions target)
   target_compile_definitions(${target} PRIVATE ${WXBASE_RESTRICTIONS})
endfunction()

# ---------------------------------------------------------------------------
# Compiler options and symbol export macros (as upstream)
# ---------------------------------------------------------------------------
set(AUDACITY_CONFIG_HEADER "${CMAKE_BINARY_DIR}/src/private/configunix.h")

# Upstream: audacity_append_common_compiler_options(var use_pch).  The -D
# items are returned in a separate variable <var>_DEFINITIONS so that CMake
# quotes them properly (upstream passes them as raw options).
function(audacity_append_common_compiler_options var use_pch)
   if(NOT use_pch)
      list(APPEND ${var} PRIVATE "SHELL:-include ${AUDACITY_CONFIG_HEADER}")
   endif()
   list(APPEND ${var}
      $<$<CXX_COMPILER_ID:AppleClang,Clang>:-Wno-underaligned-exception-object>
      $<$<CXX_COMPILER_ID:AppleClang,Clang>:-Werror=return-type>
      $<$<CXX_COMPILER_ID:AppleClang,Clang>:-Werror=dangling-else>
      $<$<CXX_COMPILER_ID:AppleClang,Clang>:-Werror=return-stack-address>
      $<$<CXX_COMPILER_ID:AppleClang,Clang>:-Werror=defaulted-function-deleted>
      ${MMX_FLAG}
      ${SSE_FLAG}
   )
   set(defs
      AUDACITY_VERSION=${AUDACITY_VERSION}
      AUDACITY_RELEASE=${AUDACITY_RELEASE}
      AUDACITY_REVISION=${AUDACITY_REVISION}
      AUDACITY_MODLEVEL=${AUDACITY_MODLEVEL}
      "AUDACITY_VERSION_STRING=L\"${AUDACITY_VERSION}.${AUDACITY_RELEASE}.${AUDACITY_REVISION}${AUDACITY_SUFFIX}\""
      "AUDACITY_FILE_VERSION=L\"${AUDACITY_VERSION},${AUDACITY_RELEASE},${AUDACITY_REVISION},${AUDACITY_MODLEVEL}\""
      safenew=new
      WXINTL_NO_GETTEXT_MACRO
      $<IF:$<CONFIG:Debug>,_DEBUG=1,>
   )
   if(AUDACITY_BUILD_LEVEL EQUAL 0)
      list(APPEND defs IS_ALPHA)
   elseif(AUDACITY_BUILD_LEVEL EQUAL 1)
      list(APPEND defs IS_BETA)
   else()
      list(APPEND defs IS_RELEASE)
   endif()
   set(${var} "${${var}}" PARENT_SCOPE)
   set(${var}_DEFINITIONS "${defs}" PARENT_SCOPE)
endfunction()

function(import_export_symbol var module_name)
   string(REGEX REPLACE "^mod-" "" symbol "${module_name}")
   string(REGEX REPLACE "^lib-" "" symbol "${symbol}")
   string(TOUPPER "${symbol}" symbol)
   string(REPLACE "-" "_" symbol "${symbol}")
   string(APPEND symbol "_API")
   set("${var}" "${symbol}" PARENT_SCOPE)
endfunction()

function(import_symbol_define var module_name)
   import_export_symbol(symbol "${module_name}")
   if(CMAKE_SYSTEM_NAME MATCHES "Windows")
      set(value "__declspec(dllimport)")
   elseif(HAVE_VISIBILITY)
      set(value "__attribute__((visibility(\"default\")))")
   else()
      set(value "")
   endif()
   set("${var}" "${symbol}=${value}" PARENT_SCOPE)
endfunction()

function(export_symbol_define var module_name)
   import_export_symbol(symbol "${module_name}")
   if(CMAKE_SYSTEM_NAME MATCHES "Windows")
      set(value "__declspec(dllexport)")
   elseif(HAVE_VISIBILITY)
      set(value "__attribute__((visibility(\"default\")))")
   else()
      set(value "")
   endif()
   set("${var}" "${symbol}=${value}" PARENT_SCOPE)
endfunction()

# ---------------------------------------------------------------------------
# Dependency-graph bookkeeping (as upstream; also applies wxBase restrictions)
# ---------------------------------------------------------------------------
function(canonicalize_node_name var node)
   string(REGEX REPLACE ".*>:(.*)>" "\\1" node "${node}")
   string(REGEX REPLACE "-interface\$" "" node "${node}")
   string(REGEX REPLACE "^-(l|framework )" "" node "${node}")
   get_filename_component(node "${node}" NAME_WE)
   set("${var}" "${node}" PARENT_SCOPE)
endfunction()

define_property(TARGET PROPERTY AUDACITY_GRAPH_DEPENDENCIES
   BRIEF_DOCS "Propagates information used in generating a target dependency diagram"
   FULL_DOCS "Audacity uses this at configuration time only, not generation time.")

function(append_node_attributes var target)
   get_target_property(dependencies ${target} AUDACITY_GRAPH_DEPENDENCIES)
   set(color "lightpink")
   if(NOT "wxwidgets::wxwidgets" IN_LIST dependencies)
      set(color "lightgreen")
      get_target_property(type ${target} TYPE)
      if(NOT ${type} STREQUAL "INTERFACE_LIBRARY")
         apply_wxbase_restrictions(${target})
      endif()
   endif()
   string(APPEND "${var}" " style=filled fillcolor=${color}")
   set("${var}" "${${var}}" PARENT_SCOPE)
endfunction()

function(set_edge_attributes var access)
   if(access STREQUAL "PRIVATE")
      set(value " [style=dashed]")
   else()
      set(value)
   endif()
   set("${var}" "${value}" PARENT_SCOPE)
endfunction()

function(propagate_interesting_dependencies target direct_dependencies)
   set(interesting_dependencies)
   foreach(direct_dependency ${direct_dependencies})
      if(NOT TARGET "${direct_dependency}")
         continue()
      endif()
      get_target_property(more_dependencies ${direct_dependency} AUDACITY_GRAPH_DEPENDENCIES)
      if(more_dependencies)
         list(APPEND interesting_dependencies ${more_dependencies})
      endif()
      if(direct_dependency STREQUAL "wxwidgets::wxwidgets")
         list(APPEND interesting_dependencies "wxwidgets::wxwidgets")
      endif()
   endforeach()
   list(REMOVE_DUPLICATES interesting_dependencies)
   set_target_properties(${target} PROPERTIES AUDACITY_GRAPH_DEPENDENCIES "${interesting_dependencies}")
endfunction()

function(collect_edges TARGET IMPORT_TARGETS LIBTYPE)
   if(LIBTYPE STREQUAL "MODULE")
      set(ATTRIBUTES "shape=box")
   else()
      set(ATTRIBUTES "shape=octagon")
   endif()
   propagate_interesting_dependencies(${TARGET} "${IMPORT_TARGETS}")
   append_node_attributes(ATTRIBUTES ${TARGET})
   list(APPEND GRAPH_EDGES "\"${TARGET}\" [${ATTRIBUTES}]")
   set(accesses PUBLIC PRIVATE INTERFACE)
   set(access PUBLIC)
   foreach(IMPORT ${IMPORT_TARGETS})
      if(IMPORT IN_LIST accesses)
         set(access "${IMPORT}")
         continue()
      endif()
      canonicalize_node_name(IMPORT "${IMPORT}")
      set_edge_attributes(attributes "${access}")
      list(APPEND GRAPH_EDGES "\"${TARGET}\" -> \"${IMPORT}\" ${attributes}")
   endforeach()
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)
endfunction()

function(make_interface_alias TARGET REAL_LIBTYPE)
   set(INTERFACE_TARGET "${TARGET}-interface")
   if(NOT REAL_LIBTYPE STREQUAL "MODULE")
      add_library("${INTERFACE_TARGET}" ALIAS "${TARGET}")
   else()
      add_library("${INTERFACE_TARGET}" INTERFACE)
      foreach(PROP INTERFACE_INCLUDE_DIRECTORIES INTERFACE_COMPILE_DEFINITIONS
            INTERFACE_LINK_LIBRARIES AUDACITY_GRAPH_DEPENDENCIES)
         get_target_property(PROPS "${TARGET}" "${PROP}")
         if(PROPS)
            set_target_properties("${INTERFACE_TARGET}" PROPERTIES "${PROP}" "${PROPS}")
         endif()
      endforeach()
   endif()
endfunction()

# ---------------------------------------------------------------------------
# Port-specific hooks
# ---------------------------------------------------------------------------

# "Old=New" pairs: third-party target names used by the upstream
# CMakeLists.txt files that the port provides under another name.
set_property(GLOBAL PROPERTY AUDACITY_TARGET_REMAP "")
function(audacity_remap_target old new)
   set_property(GLOBAL APPEND PROPERTY AUDACITY_TARGET_REMAP "${old}=${new}")
endfunction()

function(_audacity_apply_remap var)
   get_property(remap GLOBAL PROPERTY AUDACITY_TARGET_REMAP)
   set(out)
   foreach(item IN LISTS ${var})
      foreach(pair IN LISTS remap)
         string(REPLACE "=" ";" pair "${pair}")
         list(GET pair 0 old)
         list(GET pair 1 new)
         if(item STREQUAL old)
            set(item "${new}")
         endif()
      endforeach()
      list(APPEND out "${item}")
   endforeach()
   set(${var} "${out}" PARENT_SCOPE)
endfunction()

# Per-target fixups: sources to leave out, extra include dirs etc.
# Filled by native/cmake/PortFixups.cmake via audacity_port_exclude_sources()
# and audacity_port_target_hook().
function(audacity_port_exclude_sources target)
   set_property(GLOBAL APPEND PROPERTY AUDACITY_PORT_EXCLUDE_${target} ${ARGN})
endfunction()

function(_audacity_filter_sources target var)
   get_property(excluded GLOBAL PROPERTY AUDACITY_PORT_EXCLUDE_${target})
   if(excluded)
      list(REMOVE_ITEM ${var} ${excluded})
      message(STATUS "   (port) ${target}: not compiling ${excluded}")
   endif()
   set(${var} "${${var}}" PARENT_SCOPE)
endfunction()

# A hook is the name of a function taking the target name; called after the
# target is fully defined.
function(audacity_port_target_hook target fn)
   set_property(GLOBAL APPEND PROPERTY AUDACITY_PORT_HOOKS_${target} ${fn})
endfunction()

function(_audacity_run_hooks target)
   get_property(hooks GLOBAL PROPERTY AUDACITY_PORT_HOOKS_${target})
   foreach(fn IN LISTS hooks)
      cmake_language(CALL ${fn} ${target})
   endforeach()
endfunction()

# Lists of everything built (for the audacity-core aggregate target).
set_property(GLOBAL PROPERTY AUDACITY_ALL_LIBRARIES "")
set_property(GLOBAL PROPERTY AUDACITY_ALL_MODULES "")

# ---------------------------------------------------------------------------
# audacity_library / audacity_module / audacity_header_only_library
# ---------------------------------------------------------------------------
function(audacity_module_fn NAME SOURCES IMPORT_TARGETS
   ADDITIONAL_DEFINES ADDITIONAL_LIBRARIES LIBTYPE)

   set(TARGET ${NAME})
   set(TARGET_ROOT ${CMAKE_CURRENT_SOURCE_DIR})

   message(STATUS "========== Configuring ${TARGET} ==========")

   def_vars()

   # Port: modules are linkable shared libraries (as upstream on Windows).
   set(REAL_LIBTYPE SHARED)
   add_library(${TARGET} ${REAL_LIBTYPE})

   _audacity_filter_sources(${TARGET} SOURCES)
   _audacity_apply_remap(IMPORT_TARGETS)
   _audacity_apply_remap(ADDITIONAL_LIBRARIES)

   set(DEFINES)
   list(APPEND DEFINES ${ADDITIONAL_DEFINES})

   if(LIBTYPE STREQUAL "MODULE")
      set_target_properties(${TARGET} PROPERTIES PREFIX "lib" FOLDER "modules")
      set_property(GLOBAL APPEND PROPERTY AUDACITY_ALL_MODULES ${TARGET})
   else()
      set_target_properties(${TARGET} PROPERTIES PREFIX "" FOLDER "libraries")
      set_property(GLOBAL APPEND PROPERTY AUDACITY_ALL_LIBRARIES ${TARGET})
   endif()
   set_target_property_all(${TARGET} LIBRARY_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}")
   set_target_property_all(${TARGET} RUNTIME_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}")
   if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME MATCHES "Windows|Darwin")
      set_target_property_all(${TARGET} INSTALL_RPATH "$ORIGIN")
      set_target_property_all(${TARGET} BUILD_RPATH "$ORIGIN")
   endif()

   export_symbol_define(export_symbol "${TARGET}")
   import_symbol_define(import_symbol "${TARGET}")
   list(APPEND DEFINES
      PRIVATE "${export_symbol}"
      INTERFACE "${import_symbol}"
   )

   set(LIBRARIES)
   foreach(IMPORT ${IMPORT_TARGETS})
      list(APPEND LIBRARIES "${IMPORT}")
   endforeach()
   list(APPEND LIBRARIES ${ADDITIONAL_LIBRARIES})

   set(OPTIONS)
   audacity_append_common_compiler_options(OPTIONS NO)

   organize_source("${TARGET_ROOT}" "" "${SOURCES}")
   target_sources(${TARGET} PRIVATE ${SOURCES})
   target_compile_definitions(${TARGET} PRIVATE ${DEFINES})
   target_compile_definitions(${TARGET} PRIVATE ${OPTIONS_DEFINITIONS})
   target_compile_options(${TARGET} ${OPTIONS})
   target_include_directories(${TARGET} PUBLIC ${TARGET_ROOT})
   target_link_libraries(${TARGET} PUBLIC ${LIBRARIES})
   if(AUDACITY_HIDDEN_VISIBILITY)
      set_target_properties(${TARGET} PROPERTIES
         CXX_VISIBILITY_PRESET hidden C_VISIBILITY_PRESET hidden)
   endif()

   make_interface_alias(${TARGET} ${REAL_LIBTYPE})

   collect_edges(${TARGET} "${IMPORT_TARGETS}" ${LIBTYPE})
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)

   _audacity_run_hooks(${TARGET})

   if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/tests" AND AUDACITY_ANDROID_BUILD_UPSTREAM_TESTS)
      add_subdirectory(tests)
   endif()
endfunction()

macro(audacity_module NAME SOURCES IMPORT_TARGETS ADDITIONAL_DEFINES ADDITIONAL_LIBRARIES)
   audacity_module_fn("${NAME}" "${SOURCES}" "${IMPORT_TARGETS}"
      "${ADDITIONAL_DEFINES}" "${ADDITIONAL_LIBRARIES}" "MODULE")
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)
endmacro()

macro(audacity_library NAME SOURCES IMPORT_TARGETS ADDITIONAL_DEFINES ADDITIONAL_LIBRARIES)
   audacity_module_fn("${NAME}" "${SOURCES}" "${IMPORT_TARGETS}"
      "${ADDITIONAL_DEFINES}" "${ADDITIONAL_LIBRARIES}" "SHARED")
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)
endmacro()

macro(audacity_header_only_library NAME SOURCES IMPORT_TARGETS ADDITIONAL_DEFINES)
   add_library(${NAME} INTERFACE)
   target_include_directories(${NAME} INTERFACE ${CMAKE_CURRENT_SOURCE_DIR})
   target_sources(${NAME} INTERFACE ${SOURCES})
   target_link_libraries(${NAME} INTERFACE ${IMPORT_TARGETS})
   target_compile_definitions(${NAME} INTERFACE ${ADDITIONAL_DEFINES})
   make_interface_alias(${NAME} "SHARED")
   collect_edges(${NAME} "${IMPORT_TARGETS}" "SHARED")
   set_property(GLOBAL APPEND PROPERTY AUDACITY_ALL_LIBRARIES ${NAME})
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)
endmacro()

# ---------------------------------------------------------------------------
# addlib(): bundled third-party code in lib-src, built with the (unchanged)
# upstream build descriptions in native/audacity/cmake-proxies/<dir>, or with
# an adapted copy in native/cmake/lib-src/<dir> when one exists.
# Only "local" builds are supported (no system packages).
# ---------------------------------------------------------------------------
function(addlib dir name symbol required check)
   set(subdir "${AUDACITY_PORT_CMAKE_DIR}/lib-src/${dir}")
   if(NOT EXISTS "${subdir}/CMakeLists.txt")
      set(subdir "${AUDACITY_SOURCE_ROOT}/cmake-proxies/${dir}")
   endif()
   set(bindir "${CMAKE_BINARY_DIR}/cmake-proxies/${dir}")

   set(TARGET ${dir})
   set(TARGET_ROOT ${libsrc}/${dir})

   set(use ${_OPT}use_${name})

   if(NOT check)
      add_subdirectory(${subdir} ${bindir} EXCLUDE_FROM_ALL)
      return()
   endif()

   if(required)
      set(${use} "local")
   else()
      cmd_option(${use} "Use ${name} library [local, off]" "local" STRINGS "local" "off")
   endif()

   if(${use} STREQUAL "off")
      message(STATUS "========== ${name} disabled ==========")
      set(USE_${symbol} OFF CACHE INTERNAL "" FORCE)
      return()
   endif()

   set(USE_${symbol} ON CACHE INTERNAL "" FORCE)

   if(TARGET "${TARGET}")
      return()
   endif()

   message(STATUS "========== Configuring ${name} (local) ==========")
   add_subdirectory(${subdir} ${bindir} EXCLUDE_FROM_ALL)
endfunction()

# Upstream cmake-proxies/CMakeLists.txt equivalent of audacity_module_subdirectory
macro(audacity_module_subdirectory modules)
   foreach(MODULE ${MODULES})
      set(EXTRA_CLUSTER_NODES)
      add_subdirectory("${MODULE}")
   endforeach()
   set(GRAPH_EDGES "${GRAPH_EDGES}" PARENT_SCOPE)
endmacro()

# Placeholder; native/tests may provide a real implementation (see the
# top-level CMakeLists.txt, AUDACITY_ANDROID_BUILD_UPSTREAM_TESTS).
if(NOT COMMAND add_unit_test)
   function(add_unit_test)
   endfunction()
endif()
