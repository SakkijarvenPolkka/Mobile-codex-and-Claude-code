# PortMidi (portable core + Linux "null" back end) for the Audacity Android
# port.  Input: PORTMIDI_SOURCE_DIR (pinned upstream checkout).
#
# lib-note-track always compiles MIDIPlay.cpp, which calls Pm_*/Pt_*, and
# links portmidi::portmidi (upstream builds it on Linux).  The port keeps
# USE_MIDI off; PortMidi is built with PMNULL (no MIDI drivers), so it
# compiles and links everywhere and reports zero MIDI devices -- the same
# configuration PortMidi itself uses on Linux systems without ALSA.
set(_pm ${PORTMIDI_SOURCE_DIR})
add_library(portmidi STATIC
   ${_pm}/pm_common/portmidi.c
   ${_pm}/pm_common/pmutil.c
   ${_pm}/porttime/porttime.c
   ${_pm}/porttime/ptlinux.c
   ${_pm}/pm_linux/pmlinux.c
   ${_pm}/pm_linux/pmlinuxnull.c
)
target_include_directories(portmidi
   PUBLIC ${_pm}/pm_common ${_pm}/porttime
   PRIVATE ${_pm}/pm_linux)
target_compile_definitions(portmidi PRIVATE PMNULL)
target_compile_options(portmidi PRIVATE -w)
target_link_libraries(portmidi PRIVATE Threads::Threads)
set_target_properties(portmidi PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_library(portmidi::portmidi ALIAS portmidi)
