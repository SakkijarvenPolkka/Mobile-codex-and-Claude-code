# Android port: changes to the vendored Audacity sources

Every modification of a file under `native/audacity/` (compared with upstream
tag `Audacity-3.7.9`, see `PROVENANCE.md`) is listed here with a one-line
reason.  Rules: patches are minimal, never reformat, and Android-specific
changes are guarded with `#ifdef __ANDROID__` when the code is valid on
desktop.

## Modified files

| File | Change | Reason |
|---|---|---|
| `libraries/lib-files/FileNames.cpp` | `#ifdef __ANDROID__` include `<linux/magic.h>` instead of `"/usr/include/linux/magic.h"` | the absolute path pulls the *build host's* kernel header into the Android build (and does not exist on macOS/Windows hosts); the NDK sysroot has the same header |

All other vendored files are byte-identical to upstream.

## How the port avoids patching

The deviations the native build needs are applied from outside the vendored
tree (see `native/BUILDING.md` and `native/cmake/PortFixups.cmake`):

| Upstream file / target | Problem on Android / without the wx GUI | Handled by |
|---|---|---|
| all `libraries/lib-*/CMakeLists.txt`, `modules/import-export/mod-*/CMakeLists.txt`, `cmake-proxies/*/CMakeLists.txt` | call upstream helper functions | `native/cmake/AudacityShim.cmake` re-implements them |
| `lib-strings/Internat.cpp`, `lib-files/*`, ... | use `__WXGTK__` as the "Linux" switch | `__WXGTK__` defined for all Audacity code (as desktop Linux and Audacity 4 do) |
| `lib-note-track/MIDIPlay.cpp` | needs `USE_MIDI` (upstream does not compile with MIDI off) | not compiled when `USE_MIDI` is off |
| `mod-mp3/MP3Prefs.cpp` | wx preference-page control | not compiled |
| `mod-mp3/ExportMP3.cpp` | includes wx GUI headers (LAME locate dialog, resample prompt) | headless stand-in headers `native/compat/wx-gui-stubs` (dialogs act as "Cancel") |
| `mod-lof/ImportLOF.cpp` | includes `<wx/frame.h>` (unused) | stand-in `native/compat/wx-gui-stubs/wx/frame.h` |
| `mod-aup`, `mod-lof` | link the wx application target `Audacity` (ProjectFileManager, ProjectManager, ProjectWindows) | `native/compat/app-services` (`lib-app-services`), target name remapped |
| `lib-uuid` (Linux branch) | needs libuuid | `native/cmake/deps/libuuid` (`uuid_generate` only) |
