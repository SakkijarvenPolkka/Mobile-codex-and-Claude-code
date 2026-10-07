# SPDX-License-Identifier: GPL-2.0-or-later
#
# Host tests of the PortAudio host API of the Audacity Android port: the real
# AAudio host API (portaudio-android/src/pa_aaudio.c) running on the
# simulated "Null" AAudio device (PA_USE_NULL, host only).  Each test case
# runs in its own process (the simulator and PortAudio keep global state).
#    ctest --test-dir native/build-host -L portaudio --output-on-failure
if(NOT PA_USE_NULL)
   message(STATUS "PortAudio host tests skipped (PA_USE_NULL is OFF)")
   return()
endif()

add_executable(portaudio-null-test pa_null_test.c)
target_link_libraries(portaudio-null-test PRIVATE portaudio::android Threads::Threads m)
set_target_properties(portaudio-null-test PROPERTIES
   C_STANDARD 11 C_EXTENSIONS ON
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
target_compile_options(portaudio-null-test PRIVATE -Wall -Wextra -Wshadow)

set(_pa_tests
   stats_before_start enumerate rates devices output input input_int16 duplex
   loopback complete abort_return idempotence blocked_callback disconnect
   duplex_disconnect xrun open_errors mono_mic warmup_timeout drift gain
   terminate_open)
foreach(_t IN LISTS _pa_tests)
   add_test(NAME portaudio.${_t} COMMAND portaudio-null-test ${_t})
   set_tests_properties(portaudio.${_t} PROPERTIES TIMEOUT 60 LABELS portaudio)
endforeach()
