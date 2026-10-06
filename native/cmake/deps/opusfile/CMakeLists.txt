# Minimal CMake build of opusfile 0.12 (the release tarball only ships
# autotools).  HTTP streaming is disabled (no OpenSSL), as in Audacity's
# Conan recipe (opusfile:http=False).
# Inputs: OPUSFILE_SOURCE_DIR, OPUS_SOURCE_DIR (set by Dependencies.cmake).
#
# Audacity includes <opus/opus.h> and <opus/opusfile.h> (the installed
# layout), so an "installed-like" include tree is generated here.
set(_inc "${CMAKE_CURRENT_BINARY_DIR}/include")
file(GLOB _opus_headers "${OPUS_SOURCE_DIR}/include/*.h")
foreach(h ${_opus_headers} "${OPUSFILE_SOURCE_DIR}/include/opusfile.h")
   get_filename_component(n "${h}" NAME)
   configure_file("${h}" "${_inc}/opus/${n}" COPYONLY)
endforeach()

add_library(opusfile STATIC
   ${OPUSFILE_SOURCE_DIR}/src/info.c
   ${OPUSFILE_SOURCE_DIR}/src/internal.c
   ${OPUSFILE_SOURCE_DIR}/src/opusfile.c
   ${OPUSFILE_SOURCE_DIR}/src/stream.c
)
target_include_directories(opusfile
   PUBLIC ${OPUSFILE_SOURCE_DIR}/include ${_inc}
   PRIVATE ${OPUSFILE_SOURCE_DIR}/src)
include(CheckSymbolExists)
check_symbol_exists(lrintf "math.h" OPUSFILE_HAVE_LRINTF)
target_compile_definitions(opusfile PRIVATE
   $<$<BOOL:${OPUSFILE_HAVE_LRINTF}>:OP_HAVE_LRINTF=1>)
# opusfile.h includes <opus_multistream.h>, i.e. expects the opus include dir
target_link_libraries(opusfile PUBLIC Opus::opus Ogg::ogg)
if(NOT WIN32)
   target_link_libraries(opusfile PRIVATE m)
endif()
set_target_properties(opusfile PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(opusfile::opusfile ALIAS opusfile)
