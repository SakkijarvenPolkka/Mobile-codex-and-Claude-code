# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge "edit" module (select, playRegion, edit, tracks,
# clips, labels) through Bridge.h.
add_executable(bridge-test-edit EditTest.cpp)
target_link_libraries(bridge-test-edit PRIVATE bridge-test-support)
set_target_properties(bridge-test-edit PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-edit COMMAND bridge-test-edit)
set_tests_properties(bridge-edit PROPERTIES TIMEOUT 600 LABELS edit)
