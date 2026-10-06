#[[
Per-target deviations of the Android port from the upstream build, applied
by name from AudacityShim.cmake (audacity_module_fn) so that the vendored
CMakeLists.txt files stay unchanged.  Every entry says why.
]]

include_guard(GLOBAL)

# SndFile::sndfile is the static archive from libsndfile's own build; the
# Audacity libraries must all use the single shared libsndfile.so instead.
audacity_remap_target(SndFile::sndfile audacity-sndfile)

# mod-aup and mod-lof link the wx application target "Audacity" (src/) for
# ProjectFileManager / ProjectManager / ProjectWindows.  The port provides
# those entry points in lib-app-services (native/compat/app-services).
audacity_remap_target(Audacity lib-app-services)

# ---------------------------------------------------------------------------
# mod-mp3: MP3 export through LAME.
#  * MP3Prefs.cpp only adds a wxWidgets preference-page control (GUI).
#  * ExportMP3.cpp includes GUI headers for its "Locate LAME" dialog, which is
#    compiled out with DISABLE_DYNAMIC_LOADING_LAME (LAME is linked).  The
#    remaining declarations need only stub headers (native/compat/wx-gui-stubs).
#  * lib-preference-pages / lib-wx-init are wx GUI libraries that the port
#    does not build; empty stand-in interface targets keep the upstream
#    LIBRARIES list valid.
# ---------------------------------------------------------------------------
audacity_port_exclude_sources(mod-mp3 MP3Prefs.cpp)

add_library(audacity-port-gui-stubs INTERFACE)
target_include_directories(audacity-port-gui-stubs INTERFACE "${AUDACITY_PORT_COMPAT_DIR}/wx-gui-stubs")
foreach(_stub lib-preference-pages-interface lib-wx-init-interface)
   if(NOT TARGET ${_stub})
      add_library(${_stub} INTERFACE)
      target_link_libraries(${_stub} INTERFACE audacity-port-gui-stubs)
   endif()
endforeach()

function(_audacity_fixup_mod_mp3 target)
   # The stubs must win over the real <wx/textctrl.h> etc. of wxBase
   target_include_directories(${target} BEFORE PRIVATE "${AUDACITY_PORT_COMPAT_DIR}/wx-gui-stubs")
endfunction()
audacity_port_target_hook(mod-mp3 _audacity_fixup_mod_mp3)

# mod-lof includes <wx/frame.h> (unused).  It is a GUI header; give it the
# stub from wx-gui-stubs/wx/frame.h.
function(_audacity_fixup_mod_lof target)
   target_include_directories(${target} BEFORE PRIVATE "${AUDACITY_PORT_COMPAT_DIR}/wx-gui-stubs")
endfunction()
audacity_port_target_hook(mod-lof _audacity_fixup_mod_lof)

# ---------------------------------------------------------------------------
# lib-note-track: MIDIPlay.cpp (MIDI playback through PortMidi) needs the
# NoteTrack class, which NoteTrack.h only declares when USE_MIDI is defined.
# Upstream 3.7.9 therefore does not compile with MIDI disabled; with USE_MIDI
# off the port leaves MIDIPlay.cpp out (nothing else refers to it).
# ---------------------------------------------------------------------------
if(NOT USE_MIDI)
   audacity_port_exclude_sources(lib-note-track MIDIPlay.cpp MIDIPlay.h)
endif()

# ---------------------------------------------------------------------------
# Called once at the end of the top-level CMakeLists.txt
# ---------------------------------------------------------------------------
function(audacity_port_finalize)
   # libnyquist (lib-src, plain CMake) links SndFile::sndfile directly.
   if(TARGET libnyquist)
      foreach(prop LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
         get_target_property(libs libnyquist ${prop})
         if(libs)
            list(TRANSFORM libs REPLACE "^SndFile::sndfile$" "audacity-sndfile")
            list(TRANSFORM libs REPLACE "LINK_ONLY:SndFile::sndfile>" "LINK_ONLY:audacity-sndfile>")
            set_target_properties(libnyquist PROPERTIES ${prop} "${libs}")
         endif()
      endforeach()
   endif()

   # libnyquist (lib-src, plain CMake) on Android: Nyquist's sys/unix/
   # switches.h assumes glibc's <sys/types.h> provides ulong/ushort, and
   # cmt/midifns.c includes <sys/timeb.h>; bionic has neither.  A force-
   # included header and a stub sys/timeb.h (HAS_FTIME is not set, so ftime()
   # is never called) supply them.
   if(ANDROID AND TARGET libnyquist)
      target_compile_options(libnyquist PRIVATE
         "SHELL:-include ${AUDACITY_PORT_CMAKE_DIR}/deps/libnyquist/android-compat.h")
      target_include_directories(libnyquist BEFORE PRIVATE
         "${AUDACITY_PORT_CMAKE_DIR}/deps/libnyquist/include")
   endif()

   # Android ships a private /system/lib64/libsqlite.so; avoid any confusion
   # with it by giving the bundled SQLite a distinct file name.
   if(ANDROID AND TARGET sqlite)
      set_target_properties(sqlite PROPERTIES OUTPUT_NAME "audacity-sqlite3")
   endif()
endfunction()
