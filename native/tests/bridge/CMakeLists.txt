# SPDX-License-Identifier: GPL-2.0-or-later
#
# Host tests of the bridge (native/bridge), driving it ONLY through
# include/aubridge/Bridge.h.  One executable per module directory, e.g.
# core/ -> bridge-test-core, edit/ -> bridge-test-edit; each registered with
# add_test().  Test helpers for every module: target `bridge-test-support`
# (core/BridgeTestSupport.h: event sink, temp dirs, Invoke helpers).

if(NOT TARGET audacity-bridge)
   return()
endif()

find_package(Threads REQUIRED)
add_library(bridge-test-support INTERFACE)
target_include_directories(bridge-test-support INTERFACE
   "${CMAKE_CURRENT_SOURCE_DIR}/core"
   "${CMAKE_SOURCE_DIR}/bridge/third_party")
target_link_libraries(bridge-test-support INTERFACE audacity-bridge Threads::Threads)
target_compile_definitions(bridge-test-support INTERFACE JSON_USE_IMPLICIT_CONVERSIONS=0)

foreach(_dir core edit effects io audio display)
   if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${_dir}/CMakeLists.txt")
      add_subdirectory(${_dir})
   endif()
endforeach()
