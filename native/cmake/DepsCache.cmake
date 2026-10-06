#[[
Download cache + FetchContent helpers for the Android port superbuild.

Every third-party archive is downloaded ONCE into AUDACITY_DEPS_CACHE_DIR
(default: native/_deps/cache, git-ignored), verified against a pinned SHA-256,
and then handed to FetchContent as a local file.  Each build tree (host,
android-arm64, android-x86_64, Gradle's .cxx trees...) extracts and builds its
own copy under ${CMAKE_BINARY_DIR}/_deps (the default FETCHCONTENT_BASE_DIR),
so build trees never share mutable state but never re-download either.

Git dependencies (pinned commits) are cloned once as bare mirrors into
${AUDACITY_DEPS_CACHE_DIR}/git and FetchContent clones from that mirror.

Once the cache is populated the build works offline.  Pre-seeding the cache
directory (copying the files) is enough for air-gapped builders.
]]

include_guard(GLOBAL)
include(FetchContent)

set(AUDACITY_DEPS_CACHE_DIR "${CMAKE_CURRENT_LIST_DIR}/../_deps/cache"
   CACHE PATH "Shared download cache for third-party archives (safe to share between build trees)")
get_filename_component(AUDACITY_DEPS_CACHE_DIR "${AUDACITY_DEPS_CACHE_DIR}" ABSOLUTE)
file(MAKE_DIRECTORY "${AUDACITY_DEPS_CACHE_DIR}")

find_package(Git REQUIRED)

# CMake's archive extraction needs a UTF-8 locale for archives with non-ASCII
# member names (flac-1.4.3.tar.xz has some).  Extraction runs in child
# processes of this configure step, which inherit this environment.
if(NOT "$ENV{LC_ALL}$ENV{LC_CTYPE}$ENV{LANG}" MATCHES "UTF-8|utf8")
   if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
      set(ENV{LC_ALL} "C.UTF-8")
   elseif(CMAKE_HOST_APPLE)
      set(ENV{LC_ALL} "en_US.UTF-8")
   endif()
endif()

# Cache mutations below run under an inter-process lock
# (file(LOCK ... GUARD FUNCTION)) so that parallel configures (Gradle
# configures several ABIs at once) do not race.

#[[
audacity_fetch_archive(<name>
   URL <url> [MIRRORS <url>...]
   SHA256 <hash>
   [PATCHES <patch-file>...])      # unified diffs applied with `git apply`

Declares (does not populate) the FetchContent dependency <name>.  The
declaration is "populate only": use audacity_add_fetched() to add the
project's own CMake build (EXCLUDE_FROM_ALL), or read <name>_SOURCE_DIR after
FetchContent_MakeAvailable(<name>) to build it with our own CMake files.
]]
function(audacity_fetch_archive name)
   cmake_parse_arguments(A "" "URL;SHA256;FILENAME" "MIRRORS;PATCHES" ${ARGN})
   if(NOT A_URL OR NOT A_SHA256)
      message(FATAL_ERROR "audacity_fetch_archive(${name}): URL and SHA256 are required")
   endif()
   if(A_FILENAME)
      set(fname "${A_FILENAME}")
   else()
      get_filename_component(fname "${A_URL}" NAME)
   endif()
   set(cached "${AUDACITY_DEPS_CACHE_DIR}/${fname}")

   if(EXISTS "${cached}")
      file(SHA256 "${cached}" have)
      if(NOT have STREQUAL A_SHA256)
         message(WARNING "Cached ${fname} has a wrong hash; downloading it again")
         file(REMOVE "${cached}")
      endif()
   endif()

   if(NOT EXISTS "${cached}")
      file(LOCK "${AUDACITY_DEPS_CACHE_DIR}/.lock" GUARD FUNCTION TIMEOUT 1800)
      if(NOT EXISTS "${cached}")
         set(ok FALSE)
         foreach(url IN LISTS A_URL A_MIRRORS)
            message(STATUS "Downloading ${fname} from ${url}")
            file(DOWNLOAD "${url}" "${cached}.part"
               STATUS st TLS_VERIFY ON SHOW_PROGRESS INACTIVITY_TIMEOUT 120)
            list(GET st 0 code)
            if(code EQUAL 0)
               file(SHA256 "${cached}.part" got)
               if(got STREQUAL A_SHA256)
                  file(RENAME "${cached}.part" "${cached}")
                  set(ok TRUE)
                  break()
               endif()
               message(WARNING "${url}: SHA256 mismatch (got ${got}, expected ${A_SHA256})")
            else()
               message(WARNING "${url}: download failed: ${st}")
            endif()
            file(REMOVE "${cached}.part")
         endforeach()
         if(NOT ok)
            message(FATAL_ERROR "Could not download ${fname}. Put a copy with SHA256 ${A_SHA256} into ${AUDACITY_DEPS_CACHE_DIR}.")
         endif()
      endif()
   endif()

   set(extra)
   if(A_PATCHES)
      # Idempotent `git apply` of each patch, see ApplyPatches.cmake
      string(REPLACE ";" "@@" patch_list "${A_PATCHES}")
      list(APPEND extra PATCH_COMMAND ${CMAKE_COMMAND}
         "-DGIT_EXECUTABLE=${GIT_EXECUTABLE}"
         "-DPATCHES=${patch_list}"
         -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/ApplyPatches.cmake")
   endif()
   FetchContent_Declare(${name}
      URL "${cached}"
      SOURCE_SUBDIR audacity-populate-only
      URL_HASH SHA256=${A_SHA256}
      DOWNLOAD_EXTRACT_TIMESTAMP TRUE
      ${extra}
   )
endfunction()

#[[
audacity_fetch_git(<name> REPOSITORY <url> COMMIT <sha1>)

Pinned-commit git dependency, cloned once into a local bare mirror.
]]
function(audacity_fetch_git name)
   cmake_parse_arguments(A "" "REPOSITORY;COMMIT" "" ${ARGN})
   set(mirror "${AUDACITY_DEPS_CACHE_DIR}/git/${name}.git")
   file(LOCK "${AUDACITY_DEPS_CACHE_DIR}/.lock" GUARD FUNCTION TIMEOUT 1800)
   if(NOT EXISTS "${mirror}/HEAD")
      message(STATUS "Cloning ${A_REPOSITORY} into ${mirror}")
      file(MAKE_DIRECTORY "${AUDACITY_DEPS_CACHE_DIR}/git")
      execute_process(
         COMMAND "${GIT_EXECUTABLE}" clone --mirror --quiet "${A_REPOSITORY}" "${mirror}"
         RESULT_VARIABLE rc)
      if(rc)
         file(REMOVE_RECURSE "${mirror}")
         message(FATAL_ERROR "git clone ${A_REPOSITORY} failed")
      endif()
   endif()
   execute_process(
      COMMAND "${GIT_EXECUTABLE}" --git-dir "${mirror}" cat-file -e "${A_COMMIT}^{commit}"
      RESULT_VARIABLE missing OUTPUT_QUIET ERROR_QUIET)
   if(missing)
      execute_process(
         COMMAND "${GIT_EXECUTABLE}" --git-dir "${mirror}" fetch --quiet origin
         RESULT_VARIABLE rc)
      if(rc)
         message(FATAL_ERROR "git fetch for ${name} failed and ${A_COMMIT} is not cached")
      endif()
   endif()

   FetchContent_Declare(${name}
      GIT_REPOSITORY "${mirror}"
      GIT_TAG "${A_COMMIT}"
      GIT_SHALLOW FALSE
      GIT_PROGRESS FALSE
      # No submodules: they would be cloned from their upstream URLs on every
      # fresh build tree, bypassing the mirror (rapidjson's thirdparty/gtest),
      # and none is needed.
      GIT_SUBMODULES ""
      UPDATE_DISCONNECTED TRUE
      SOURCE_SUBDIR audacity-populate-only
   )
endfunction()

# On Android every shared object must be called lib<name>.so (the package
# manager only extracts such files, and DT_NEEDED must match the file name).
# Third-party projects set VERSION/SOVERSION, which would produce
# libfoo.so.1.2.3 + SONAME libfoo.so.1; drop them.
function(audacity_unversion_shared_library target)
   if(ANDROID)
      set_property(TARGET ${target} PROPERTY VERSION)
      set_property(TARGET ${target} PROPERTY SOVERSION)
   endif()
endfunction()

# Put a shared library next to the Audacity libraries.
function(audacity_set_output_dir target)
   set_target_properties(${target} PROPERTIES
      LIBRARY_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}"
      RUNTIME_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}")
   foreach(cfg Debug Release RelWithDebInfo MinSizeRel)
      string(TOUPPER ${cfg} CFG)
      set_target_properties(${target} PROPERTIES
         LIBRARY_OUTPUT_DIRECTORY_${CFG} "${AUDACITY_LIB_OUTPUT_DIR}"
         RUNTIME_OUTPUT_DIRECTORY_${CFG} "${AUDACITY_LIB_OUTPUT_DIR}")
   endforeach()
endfunction()

# Populate <name> (download/extract/patch) and add its own CMake project
# (optionally from a sub directory) with EXCLUDE_FROM_ALL, so that only the
# targets Audacity links are built.  Call it inside a function to scope the
# option variables set for that project.
macro(audacity_add_fetched name)
   FetchContent_MakeAvailable(${name})
   set(_audacity_sub "${ARGN}")
   add_subdirectory("${${name}_SOURCE_DIR}/${_audacity_sub}" "${${name}_BINARY_DIR}" EXCLUDE_FROM_ALL)
endmacro()
