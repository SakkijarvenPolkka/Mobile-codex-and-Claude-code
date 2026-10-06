# Apply unified-diff patches to the current directory (a FetchContent source
# tree) with `git apply`, idempotently: a patch that is already applied (its
# reverse applies cleanly) is skipped, so re-running the patch step after the
# list of patches changed works on an already patched tree.
#
# cmake -DGIT_EXECUTABLE=... -DPATCHES="a.patch@@b.patch" -P ApplyPatches.cmake
#
# GIT_CEILING_DIRECTORIES keeps `git apply` from discovering an enclosing
# repository (build trees may live inside this repository's work tree), which
# would make it silently skip files.
get_filename_component(_parent "${CMAKE_CURRENT_BINARY_DIR}/.." ABSOLUTE)
set(ENV{GIT_CEILING_DIRECTORIES} "${_parent}")
string(REPLACE "@@" ";" PATCHES "${PATCHES}")
foreach(p IN LISTS PATCHES)
   get_filename_component(name "${p}" NAME)
   execute_process(
      COMMAND "${GIT_EXECUTABLE}" apply --reverse --check --whitespace=nowarn "${p}"
      RESULT_VARIABLE already OUTPUT_QUIET ERROR_QUIET)
   if(already EQUAL 0)
      message(STATUS "Patch ${name}: already applied")
      continue()
   endif()
   execute_process(
      COMMAND "${GIT_EXECUTABLE}" apply --whitespace=nowarn "${p}"
      RESULT_VARIABLE rc)
   if(NOT rc EQUAL 0)
      message(FATAL_ERROR "Patch ${name} does not apply")
   endif()
   message(STATUS "Patch ${name}: applied")
endforeach()
