# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge "io" module (import.*, export.*) through Bridge.h.
add_executable(bridge-test-io IoTest.cpp)
target_link_libraries(bridge-test-io PRIVATE bridge-test-support)
set_target_properties(bridge-test-io PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-io COMMAND bridge-test-io)
set_tests_properties(bridge-io PROPERTIES TIMEOUT 900 LABELS io)
