# Link-model smoke test: static registration of importers/exporters in the
# mod-* shared libraries, wxBase init, SQLite through lib-sqlite-helpers.
add_executable(audacity-smoke smoke.cpp)
target_link_libraries(audacity-smoke PRIVATE audacity-core)
# The modules actually built (AUDACITY_SKIPPED_MODULES may leave some out);
# the test expects an importer/exporter from each of them.
get_property(_smoke_modules GLOBAL PROPERTY AUDACITY_ALL_MODULES)
list(JOIN _smoke_modules " " _smoke_modules)
target_compile_definitions(audacity-smoke PRIVATE
   "SMOKE_BUILT_MODULES=\" ${_smoke_modules} \"")
set_target_properties(audacity-smoke PROPERTIES
   RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME smoke COMMAND audacity-smoke)
