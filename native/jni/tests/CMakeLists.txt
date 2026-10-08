# SPDX-License-Identifier: GPL-2.0-or-later
#
# Host test of libaudacity-jni without Gradle/Kotlin: Java mirrors of
# NativeBridge/EngineListener (same class names and signatures as
# engine/.../NativeBridge.kt -- keep them in sync) and a driver that loads
# the library with System.load and runs the JVM with -Xcheck:jni.
#
#    flock /tmp/claude-0/locks/build-host.lock ninja -C native/build-host bridge-test-jni
#    ctest --test-dir native/build-host -L jni --output-on-failure

find_package(Java 17 COMPONENTS Development Runtime QUIET)
if(NOT Java_FOUND OR NOT Java_JAVAC_EXECUTABLE OR NOT Java_JAVA_EXECUTABLE)
   message(STATUS "JNI glue: no javac/java found, ctest bridge-jni skipped")
   return()
endif()
include(UseJava)

set(_aujni_java "${CMAKE_CURRENT_SOURCE_DIR}/java/io/github/sakkijarvenpolkka/audacity/engine")
set(CMAKE_JAVA_COMPILE_FLAGS -encoding UTF-8 -Xlint:all)
add_jar(aujni-test-classes
   SOURCES
      "${_aujni_java}/EngineListener.java"
      "${_aujni_java}/NativeBridge.java"
      "${_aujni_java}/JniBridgeTest.java"
   OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}")

add_custom_target(bridge-test-jni)
add_dependencies(bridge-test-jni audacity-jni aujni-test-classes)

add_test(NAME bridge-jni
   COMMAND "${Java_JAVA_EXECUTABLE}" -Xcheck:jni
      "-Daudacity.jni.library=$<TARGET_FILE:audacity-jni>"
      -cp "$<TARGET_PROPERTY:aujni-test-classes,JAR_FILE>"
      io.github.sakkijarvenpolkka.audacity.engine.JniBridgeTest)
set_tests_properties(bridge-jni PROPERTIES LABELS jni TIMEOUT 300)
