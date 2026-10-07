# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge spine (native/bridge/core) through Bridge.h.
add_executable(bridge-test-core BridgeCoreTest.cpp BridgeTestSupport.h)
target_link_libraries(bridge-test-core PRIVATE bridge-test-support)
set_target_properties(bridge-test-core PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-core COMMAND bridge-test-core)
set_tests_properties(bridge-core PROPERTIES TIMEOUT 600)
