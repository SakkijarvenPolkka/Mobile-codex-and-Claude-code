# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge "audio" module (transport.*, audio.*, meters,
# readTransport) through Bridge.h, on the simulated "Null" AAudio device
# (pa_null.h: loopback, xruns).  Real-time: runs alone (RUN_SERIAL).
if(NOT PA_USE_NULL)
   message(STATUS "bridge-test-audio skipped (PA_USE_NULL is OFF)")
   return()
endif()
add_executable(bridge-test-audio AudioTest.cpp)
target_link_libraries(bridge-test-audio PRIVATE bridge-test-support portaudio::android)
set_target_properties(bridge-test-audio PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-audio COMMAND bridge-test-audio)
set_tests_properties(bridge-audio PROPERTIES TIMEOUT 900 LABELS audio
   RUN_SERIAL TRUE)
