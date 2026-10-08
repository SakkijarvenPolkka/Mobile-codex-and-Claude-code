# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge "effects" module (effects.*, analyze.*) through
# Bridge.h.  The start configuration points at the vendored Nyquist runtime
# and plug-ins (native/audacity/nyquist, native/audacity/plug-ins); the
# preview plays on the simulated "Null" AAudio device (real time, so the
# test runs alone).
add_executable(bridge-test-effects EffectsTest.cpp)
target_link_libraries(bridge-test-effects PRIVATE bridge-test-support)
target_compile_definitions(bridge-test-effects PRIVATE
   "AUBRIDGE_TEST_AUDACITY_DIR=\"${CMAKE_SOURCE_DIR}/audacity\"")
target_compile_features(bridge-test-effects PRIVATE cxx_std_17)
set_target_properties(bridge-test-effects PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-effects COMMAND bridge-test-effects)
set_tests_properties(bridge-effects PROPERTIES TIMEOUT 900 LABELS effects
   RUN_SERIAL TRUE)
