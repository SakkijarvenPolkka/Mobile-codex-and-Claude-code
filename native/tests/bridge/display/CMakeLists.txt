# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge "display" module (waveform / envelope / sample /
# spectrogram data, display.* commands) through Bridge.h.
add_executable(bridge-test-display DisplayTest.cpp)
target_link_libraries(bridge-test-display PRIVATE bridge-test-support)
set_target_properties(bridge-test-display PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-display COMMAND bridge-test-display)
set_tests_properties(bridge-display PROPERTIES TIMEOUT 600 LABELS display)
