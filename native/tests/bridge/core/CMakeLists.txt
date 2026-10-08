# SPDX-License-Identifier: GPL-2.0-or-later
# Host tests of the bridge spine (native/bridge/core) through Bridge.h.
add_executable(bridge-test-core BridgeCoreTest.cpp BridgeTestSupport.h)
target_link_libraries(bridge-test-core PRIVATE bridge-test-support)
# The Korean catalog (native/audacity/locale) for the translation test
target_compile_definitions(bridge-test-core PRIVATE
   "AUBRIDGE_TEST_LOCALE_DIR=\"${CMAKE_SOURCE_DIR}/audacity/locale\"")
set_target_properties(bridge-test-core PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-core COMMAND bridge-test-core)
set_tests_properties(bridge-core PROPERTIES TIMEOUT 600 LABELS core)

# The committed catalog is what native/scripts/po2mo.py makes of ko.po
find_package(Python3 COMPONENTS Interpreter)
if(Python3_Interpreter_FOUND)
   add_test(NAME bridge-core.locale-ko
      COMMAND "${Python3_EXECUTABLE}" -I "${CMAKE_SOURCE_DIR}/scripts/po2mo.py"
         --check "${CMAKE_SOURCE_DIR}/audacity/locale/ko.po"
         "${CMAKE_SOURCE_DIR}/audacity/locale/ko/LC_MESSAGES/audacity.mo")
   add_test(NAME bridge-core.locale-ko-verify
      COMMAND "${Python3_EXECUTABLE}" -I "${CMAKE_SOURCE_DIR}/scripts/po2mo.py"
         --verify "${CMAKE_SOURCE_DIR}/audacity/locale/ko.po"
         "${CMAKE_SOURCE_DIR}/audacity/locale/ko/LC_MESSAGES/audacity.mo")
   set_tests_properties(bridge-core.locale-ko bridge-core.locale-ko-verify
      PROPERTIES TIMEOUT 120 LABELS core)
endif()
