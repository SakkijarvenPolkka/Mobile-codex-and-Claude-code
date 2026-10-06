# Host tests of the Audacity Android port (added by native/CMakeLists.txt when
# AUDACITY_ANDROID_BUILD_TESTS is ON; never built for Android).
#
# Add further test directories below.  Tests link `audacity-core` (and the
# bridge, once it exists) and run from the build tree, e.g.
#    ctest --test-dir native/build-host --output-on-failure

add_subdirectory(smoke)
