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
| `libraries/lib-track/UndoTracks.cpp` | `TrackListRestorer` adds the duplicated tracks with `TrackList::Add(track, false)` (no new TrackId) when taking and when restoring an undo snapshot (2 lines, not `__ANDROID__`-guarded: the bridge also runs on the Linux host) | stable TrackIds across undo/redo/rollback: the bridge protocol (`native/bridge/API.md` §3.2) addresses tracks by TrackId; upstream 3.7.9 gives every track a new id on each undo/redo/rollback. Same fix as Audacity 4 (`au3-track/UndoTracks.cpp`, `DoAssignId::No`); `Track::Duplicate` keeps `mId`, and the global id counter stays monotonic, so no collisions |

All other vendored files are byte-identical to upstream.

## Added files

| Path | Upstream | Notes |
|---|---|---|
| `locale/ko.po` | `locale/ko.po` of tag `Audacity-3.7.9` | unmodified (SHA-256 `9c58e918108ac70f728f019f4fc9edb6672774fe3baa1a1dfdebbeb9c78744d4`) |
| `locale/ko/LC_MESSAGES/audacity.mo` | generated | `native/scripts/po2mo.py locale/ko.po locale/ko/LC_MESSAGES/audacity.mo` (pure-Python msgfmt; ctest `bridge-core.locale-ko` checks it is up to date). The app ships it as `assets/audacity/locale/ko/LC_MESSAGES/audacity.mo`, extracted to `filesDir/audacity/locale/`. |

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
| `mod-lof` | `.lof` lists name other files by path; an app only gets copies of picked files | left out of Android builds by default (`AUDACITY_SKIPPED_MODULES=mod-lof`); kept on the host |
| `lib-uuid` (Linux branch) | needs libuuid | `native/cmake/deps/libuuid` (`uuid_generate` only) |
