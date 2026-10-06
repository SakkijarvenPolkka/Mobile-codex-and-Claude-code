# Tiny libuuid replacement (uuid_generate only), see uuid/uuid.h.
add_library(audacity-uuid STATIC uuid.c)
target_include_directories(audacity-uuid PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(audacity-uuid PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(libuuid::libuuid ALIAS audacity-uuid)
