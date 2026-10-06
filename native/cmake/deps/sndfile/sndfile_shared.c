/*
 * Audacity Android port: libsndfile is compiled as a static archive by its
 * own CMake build and linked with --whole-archive into one shared library
 * (libsndfile.so) built from this otherwise empty translation unit.
 * See native/cmake/Dependencies.cmake.
 */
typedef int audacity_sndfile_shared_unused;
