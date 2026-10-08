#[[
SPDX-License-Identifier: GPL-2.0-or-later

Audacity Android port -- the JNI glue (native/jni), see README.md.

Target
  audacity-jni   SHARED (libaudacity-jni.so): the natives of the Kotlin
                 `object NativeBridge` (API.md §2), registered from
                 JNI_OnLoad (its only exported symbol); links
                 libaudacity-bridge.so, whose DT_NEEDED closure holds every
                 Audacity library and module.  NativeBridge.kt loads it with
                 System.loadLibrary("audacity-jni").

Android: jni.h comes from the NDK sysroot.  Host: built when a JDK's jni.h
is found ($JAVA_HOME/include, else the usual /usr/lib/jvm locations), for
the JVM integration test (engine/.../NativeHostIntegrationTest.kt) and the
ctest `bridge-jni` (tests/).  The library never links libjvm: every JNI
function is reached through the JNIEnv/JavaVM tables.
]]

if(NOT TARGET audacity-bridge)
   message(STATUS "JNI glue: no audacity-bridge target, skipped")
   return()
endif()

set(_aujni_include_dirs)
if(NOT ANDROID)
   set(_aujni_hints)
   if(DEFINED ENV{JAVA_HOME} AND NOT "$ENV{JAVA_HOME}" STREQUAL "")
      list(APPEND _aujni_hints "$ENV{JAVA_HOME}/include")
   endif()
   list(APPEND _aujni_hints
      /usr/lib/jvm/java-21-openjdk-amd64/include
      /usr/lib/jvm/default-java/include
      /usr/lib/jvm/java-17-openjdk-amd64/include)
   find_path(AUDACITY_JNI_INCLUDE_DIR jni.h HINTS ${_aujni_hints}
      DOC "Directory of the JDK's jni.h (host build of libaudacity-jni)")
   if(AUDACITY_JNI_INCLUDE_DIR)
      find_path(AUDACITY_JNI_MD_INCLUDE_DIR jni_md.h
         HINTS "${AUDACITY_JNI_INCLUDE_DIR}/linux" "${AUDACITY_JNI_INCLUDE_DIR}"
         DOC "Directory of the JDK's jni_md.h")
   endif()
   if(NOT AUDACITY_JNI_INCLUDE_DIR OR NOT AUDACITY_JNI_MD_INCLUDE_DIR)
      message(STATUS "JNI glue: no JDK headers found (set JAVA_HOME), libaudacity-jni not built on the host")
      return()
   endif()
   set(_aujni_include_dirs "${AUDACITY_JNI_INCLUDE_DIR}" "${AUDACITY_JNI_MD_INCLUDE_DIR}")
   message(STATUS "JNI glue: host build with ${AUDACITY_JNI_INCLUDE_DIR}")
endif()

add_library(audacity-jni SHARED
   AudacityJni.cpp
   JniEventSink.cpp
   JniEventSink.h
   JniSupport.cpp
   JniSupport.h)
target_include_directories(audacity-jni PRIVATE ${_aujni_include_dirs})
# Bridge.h only: the JNI code uses no Audacity headers
target_link_libraries(audacity-jni PRIVATE audacity-bridge)
if(ANDROID)
   target_link_libraries(audacity-jni PRIVATE log)
else()
   find_package(Threads REQUIRED)
   target_link_libraries(audacity-jni PRIVATE Threads::Threads)
endif()
target_compile_features(audacity-jni PRIVATE cxx_std_17)
set_target_properties(audacity-jni PROPERTIES
   # JNI_OnLoad (JNIEXPORT) is the only exported symbol; the natives are
   # registered by pointer
   CXX_VISIBILITY_PRESET hidden
   VISIBILITY_INLINES_HIDDEN ON
   LIBRARY_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}"
   RUNTIME_OUTPUT_DIRECTORY "${AUDACITY_LIB_OUTPUT_DIR}")
if(NOT ANDROID)
   # System.load(<lib dir>/libaudacity-jni.so) finds libaudacity-bridge.so
   # and the Audacity libraries next to it
   set_target_properties(audacity-jni PROPERTIES
      BUILD_RPATH "$ORIGIN" INSTALL_RPATH "$ORIGIN")
endif()
if(NOT ANDROID AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
   target_link_options(audacity-jni PRIVATE "LINKER:--no-undefined")
endif()

# Host test of the glue alone (Java stubs of NativeBridge/EngineListener,
# run with -Xcheck:jni): ctest -L jni
if(NOT ANDROID AND AUDACITY_ANDROID_BUILD_TESTS)
   enable_testing()
   add_subdirectory(tests)
endif()
