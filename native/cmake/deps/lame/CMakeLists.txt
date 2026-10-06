# Minimal CMake build of the LAME 3.100 MP3 encoder library (libmp3lame).
# LAME ships only autotools; this mirrors libmp3lame/Makefile.am
# (libmp3lame_la_SOURCES) without the optional mpglib decoder (HAVE_MPGLIB
# off: Audacity decodes MP3 with mpg123) and without NASM/SSE code.
# Input: LAME_SOURCE_DIR (set by native/cmake/Dependencies.cmake).
set(_src ${LAME_SOURCE_DIR}/libmp3lame)
add_library(mp3lame STATIC
   ${_src}/VbrTag.c
   ${_src}/bitstream.c
   ${_src}/encoder.c
   ${_src}/fft.c
   ${_src}/gain_analysis.c
   ${_src}/id3tag.c
   ${_src}/lame.c
   ${_src}/newmdct.c
   ${_src}/presets.c
   ${_src}/psymodel.c
   ${_src}/quantize.c
   ${_src}/quantize_pvt.c
   ${_src}/reservoir.c
   ${_src}/set_get.c
   ${_src}/tables.c
   ${_src}/takehiro.c
   ${_src}/util.c
   ${_src}/vbrquantize.c
   ${_src}/version.c
   ${_src}/mpglib_interface.c
)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_include_directories(mp3lame PRIVATE
   ${CMAKE_CURRENT_SOURCE_DIR}      # config.h
   ${LAME_SOURCE_DIR}/include
   ${_src})
if(NOT WIN32)
   target_link_libraries(mp3lame PRIVATE m)
endif()
set_target_properties(mp3lame PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Audacity includes <lame/lame.h> (installed layout)
configure_file(${LAME_SOURCE_DIR}/include/lame.h
   ${CMAKE_CURRENT_BINARY_DIR}/include/lame/lame.h COPYONLY)
target_include_directories(mp3lame INTERFACE ${CMAKE_CURRENT_BINARY_DIR}/include)
add_library(libmp3lame::libmp3lame ALIAS mp3lame)
