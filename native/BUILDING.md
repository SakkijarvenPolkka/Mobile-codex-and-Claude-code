# Building the native Audacity core

`native/` is a CMake superbuild that compiles the toolkit-neutral Audacity
3.7.9 libraries (`audacity/libraries/lib-*`), the import/export modules
(`audacity/modules/import-export/mod-*`) and all their third-party
dependencies from source, for

* **Android** -- `arm64-v8a` and `x86_64` (also `armeabi-v7a`/`x86` in
  principle), `minSdk 28`, `ANDROID_STL=c++_shared`, NDK r28c, CMake 3.31.6;
* the **Linux host** (x86_64) -- for unit tests in this container (there is
  no emulator).

The wxWidgets GUI layer of Audacity (`src/`) is not built; a Kotlin UI talks to
the libraries through a C++ bridge (`native/bridge/`, `native/jni/`).

## Quick start

```sh
native/scripts/build-host.sh --test        # host build + ctest (smoke test)
native/scripts/build-android.sh arm64-v8a  # -> native/build-android-arm64/lib
native/scripts/build-android.sh x86_64     # -> native/build-android-x86_64/lib
```

or by hand:

```sh
cmake -S native -B native/build-host -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo
ninja -C native/build-host
native/build-host/bin/audacity-smoke

NDK=/opt/android-sdk/ndk/28.2.13676358
/opt/android-sdk/cmake/3.31.6/bin/cmake -S native -B native/build-android-arm64 -G Ninja \
   -DCMAKE_MAKE_PROGRAM=/opt/android-sdk/cmake/3.31.6/bin/ninja \
   -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
   -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
   -DCMAKE_BUILD_TYPE=Release
/opt/android-sdk/cmake/3.31.6/bin/ninja -C native/build-android-arm64
native/scripts/check-android-libs.sh native/build-android-arm64/lib
```

All shared libraries end up in `<build>/lib` (or in
`CMAKE_LIBRARY_OUTPUT_DIRECTORY` when Gradle sets it).  Requirements: CMake
>= 3.24 (3.28 on the host and 3.31.6 from the SDK are tested), Ninja, git, a
C/C++17 compiler; network access on the first configure only.  An Android
configure stops with an error unless `ANDROID_STL=c++_shared` (the NDK
default is `c++_static`, which would give each of the ~70 libraries its own
C++ runtime) and `ANDROID_PLATFORM` >= `android-28`.

### From Gradle

```kotlin
android {
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake {
            arguments += listOf("-DANDROID_STL=c++_shared")
        } }
    }
    externalNativeBuild { cmake {
        path = file("../native/CMakeLists.txt")
        version = "3.31.6"
    } }
}
```

Gradle passes the toolchain, `ANDROID_ABI`, `ANDROID_PLATFORM` and
`CMAKE_LIBRARY_OUTPUT_DIRECTORY`; everything else is automatic.  The bridge
library (`native/bridge`) links `audacity-core`; loading it with
`System.loadLibrary` loads every Audacity library and module through
`DT_NEEDED`.  `libc++_shared.so` is packaged by Gradle.

## Layout

| Path | What |
|---|---|
| `CMakeLists.txt` | superbuild: options, USE_* features, library/module lists, `audacity-core`, hooks for `bridge/`, `jni/` (Android), `tests/` (host) |
| `cmake/AudacityShim.cmake` | re-implementation of upstream `audacity_library`, `audacity_module`, `audacity_header_only_library`, `addlib`, `def_vars`, `make_interface_alias`, `collect_edges`, ... |
| `cmake/PortFixups.cmake` | every per-target deviation from upstream, with reasons |
| `cmake/Dependencies.cmake`, `cmake/DepsCache.cmake`, `cmake/ApplyPatches.cmake` | pinned third-party sources, download cache, patching |
| `cmake/deps/*` | small CMake builds for projects without a usable one (LAME, opusfile, PortAudio, PortMidi) and tiny compat pieces (libuuid, sndfile wrapper, Nyquist on bionic) |
| `cmake/patches/*.patch` | patches to third-party sources (wxWidgets only) |
| `compat/app-services` | `lib-app-services`: stand-in for the application calls of mod-aup/mod-lof |
| `compat/wx-gui-stubs` | headless stand-ins for wx GUI headers included by mod-mp3/mod-lof |
| `portaudio-android/` | PortAudio host-API table for Android and the AAudio host API (`PA_USE_AAUDIO`, `PA_USE_NULL`) |
| `bridge/` | the C++ bridge (`libaudacity-bridge.so`, see `bridge/MODULES.md`, `bridge/API.md`) |
| `tests/` | host tests: `tests/smoke` (link-model smoke test), `tests/portaudio` (AAudio host API on the Null device), `tests/bridge/<module>` (the bridge through `Bridge.h`) |
| `scripts/` | build helpers, `check-android-libs.sh`, `po2mo.py` (gettext catalogs) |
| `audacity/` | vendored Audacity sources (see `audacity/ANDROID_CHANGES.md`); `audacity/locale` holds the shipped translations |

## Link model

* Every Audacity library and module is a **shared library**, as in the
  desktop build: `lib-strings.so`, ..., `libmod-pcm.so` (modules get a `lib`
  prefix because Android only packages `lib*.so`).
* wxBase (`libwx_baseu-3.2-Android.so`), `libsndfile.so`, `libportaudio.so`
  and SQLite (`libaudacity-sqlite3.so` on Android, `libsqlite.so` on the host)
  are shared because several Audacity libraries use them; the other codecs
  (FLAC, Ogg/Vorbis, Opus, mpg123, LAME, WavPack, TwoLAME, soxr, SoundTouch,
  SBSMS, pffft, expat, Nyquist) are static and linked into the one library or
  module that uses them.
* Every shared library links with `-Wl,--no-undefined` (NDK default; added on
  the host too).  Android libraries have unversioned SONAMEs equal to their
  file names, 16 KB-aligned segments (64-bit ABIs) and no private copy of
  the C++ runtime (all checked by `scripts/check-android-libs.sh`).
* `audacity-core` links everything and adds `-Wl,--no-as-needed`: modules and
  several libraries (e.g. `lib-project-file-io`, which installs the SQLite
  sample-block factory) only register things from static initializers.
* Default symbol visibility, as upstream Linux (see "Symbol visibility").

## Runtime bootstrap (what a client must do)

`tests/smoke/smoke.cpp` is the reference.  In order:

1. `wxInitializer` (wxBase).
2. `BasicUI::Install(&services)` -- **required**: without services
   `MakeGenericProgress()` returns null and closing a project database can
   crash (`DBConnection::Close`).
3. `InitPreferences(std::make_unique<SomeBasicSettings>())`.
4. `TempDirectory::SetDefaultTempDir(dir)` +
   `FileNames::UpdateDefaultPath(FileNames::Operation::Temp, dir)`.
5. `Importer::Get().Initialize()`, `ExportPluginRegistry::Get().Initialize()`
   (the modules registered themselves when the libraries were loaded; nothing
   calls `ModuleDispatch`, `ModuleManager` is not needed).
6. Per project: `AudacityProject::Create()`,
   `ProjectFileIO::Get(p).OpenProject()`; close with `SetBypass()`,
   `UndoManager::Get(p).ClearStates()`, drop all tracks,
   `ProjectFileIO::Get(p).CloseProject()`, `WaveTrackFactory::Destroy(p)`.

mod-aup/mod-lof call back into the "application" through
`lib-app-services` (`ProjectFileManager::SetImportHandler`,
`ProjectManager::SetOpenProjectHandler`; a toolkit-neutral default import is
built in).  MP3 export at a rate MP3 does not support needs
`project.mBatchMode` set (the interactive resample prompt is headless and
answers "Cancel").

## Options

| Option | Default | Meaning |
|---|---|---|
| `AUDACITY_ANDROID_BUILD_TESTS` | ON (host), forced OFF (Android) | build `native/tests` |
| `AUDACITY_ANDROID_BUILD_UPSTREAM_TESTS` | OFF | add upstream `lib-*/tests` (needs `tests/UnitTestSupport.cmake` to define `add_unit_test`) |
| `AUDACITY_USE_NYQUIST` | ON | libnyquist + lib-nyquist-effects |
| `AUDACITY_USE_MIDI` | OFF | USE_MIDI (NoteTrack, MIDI playback via PortMidi null back end) |
| `AUDACITY_HIDDEN_VISIBILITY` | OFF | compile Audacity libraries with `-fvisibility=hidden` (not like upstream; breaks cross-library template statics) |
| `AUDACITY_SKIPPED_MODULES` | `mod-lof` (Android), empty (host) | modules to leave out (`;`-separated). mod-lof imports `.lof` lists that name other files by path, which an app never sees (it only gets copies of picked files); the host keeps it for `tests/smoke`. Existing build trees keep their cached value: reconfigure with `-DAUDACITY_SKIPPED_MODULES=mod-lof` once. |
| `PA_USE_AAUDIO` | ON (Android), OFF (host) | build the PortAudio AAudio host API (`portaudio-android/`, links `libaaudio`) |
| `PA_USE_NULL` | OFF (Android), ON (host) | the same host API on a simulated "Null" AAudio device, for host tests (`portaudio-android/README.md`) |
| `AUDACITY_DEPS_CACHE_DIR` | `native/_deps/cache` | shared download cache |
| `audacity_use_sbsms`, `audacity_use_soundtouch`, `audacity_use_twolame` | local | `off` disables them |

## Host tests

```sh
flock /tmp/claude-0/locks/build-host.lock ninja -C native/build-host   # or build-host.sh
ctest --test-dir native/build-host --output-on-failure            # everything
ctest --test-dir native/build-host -L core                        # one label
```

Labels: `smoke`, `portaudio`, and one per bridge module (`core`, `edit`,
`effects`, `io`, `audio`, `display`).  The timing-sensitive PortAudio tests
(`portaudio.drift`, `portaudio.fixed_buffer`, `portaudio.audioio_monitor`)
have `RUN_SERIAL`: `ctest -j` runs them alone, but they can still fail when
other processes load every CPU.

## Translations

The engine's strings (effect names, history labels, library messages) come
from Audacity's gettext catalogs.  `audacity/locale/<lang>.po` is vendored
unchanged from Audacity 3.7.9 `locale/`; the compiled catalog
`audacity/locale/<lang>/LC_MESSAGES/audacity.mo` is committed and made with
the pure-Python `scripts/po2mo.py` (no gettext needed; msgfmt semantics:
fuzzy and untranslated entries are left out, msgctxt and plural forms kept):

```sh
python3 native/scripts/po2mo.py native/audacity/locale/ko.po native/audacity/locale/ko/LC_MESSAGES/audacity.mo
python3 native/scripts/po2mo.py --verify native/audacity/locale/ko.po native/audacity/locale/ko/LC_MESSAGES/audacity.mo
```

The ctest `bridge-core.locale-ko` fails when the committed `.mo` is not what
`po2mo.py` makes of the `.po`.  The app packages
`audacity/locale/<lang>/LC_MESSAGES/audacity.mo` as
`assets/audacity/locale/<lang>/LC_MESSAGES/audacity.mo` and extracts it to
`filesDir/audacity/locale/` (a directory of the engine's path list).  Only
Korean (`ko`) is shipped so far; adding a language = vendoring its `.po`,
running `po2mo.py`, and listing it in the Gradle asset task.

## Adding a library or target

* **Another upstream library**: copy `libraries/lib-foo` (unchanged) from
  upstream into `native/audacity/libraries/`, add `lib-foo` to
  `AUDACITY_LIBRARIES` in `CMakeLists.txt` after its dependencies.  Its
  `CMakeLists.txt` calls `audacity_library(...)`, which gives it `FOO_API`,
  `lib-foo-interface`, the config header, and puts it into `audacity-core`.
* **Another module**: same with `modules/import-export/mod-foo` and
  `AUDACITY_MODULES`.
* **A port-only library** (like `compat/app-services`): write a
  `CMakeLists.txt` that calls `audacity_library(lib-foo "${SOURCES}" "${LIBRARIES}" "" "")`
  and `add_subdirectory()` it.
* **Code that uses Audacity** (bridge, tests): `target_link_libraries(x PRIVATE audacity-core)`.
  This brings include paths, `-include configunix.h`, the `*_API` import
  macros, `__WXGTK__`, the wxBase restrictions and `--no-as-needed`.
* **A per-target deviation**: `cmake/PortFixups.cmake`
  (`audacity_port_exclude_sources`, `audacity_port_target_hook`,
  `audacity_remap_target`) -- never edit the vendored CMakeLists.txt.

## Dependencies (pinned)

| Dependency | Version | Source | Built as |
|---|---|---|---|
| wxWidgets (base only) | 3.2.8 | release tarball, SHA-256 `c74784904109d7229e6894c85cfa068f1106a4a07c144afd78af41f373ee0fe6`, 3 patches | shared |
| expat | 2.7.1 | `354552544b8f99012e5062f7d570ec77f14b412a3ff5c7d8d0dae62c0d217c30` | static |
| rapidjson | git `24b5e7a8b27f42fa16b96fc70aade9106cf7102f` | GitHub | headers |
| libsndfile | 1.2.2 | `3799ca9924d3125038880367bf1468e53a1b7e3686a934f098b7e1d286cdb80e` | shared (wrapper) |
| libogg | 1.3.5 | `c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705` | static |
| libvorbis | 1.3.7 | `b33cc4934322bcbf6efcbacf49e3ca01aadbea4114ec9589d1b1e9d20f72954b` | static |
| FLAC + FLAC++ | 1.4.3 | `6c58e69cd22348f441b861092b825e591d0b822e106de6eb0ee4d05d27205b70` | static |
| opus | 1.5.2 | `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1` | static |
| opusfile | 0.12 | `118d8601c12dd6a44f52423e68ca9083cc9f2bfe72da7a8c1acb22a80ae3550b` | static (our CMake) |
| mpg123 | 1.32.10 | `87b2c17fe0c979d3ef38eeceff6362b35b28ac8589fbf1854b5be75c9ab6557c` | static |
| LAME | 3.100 | `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e` | static (our CMake) |
| WavPack | 5.7.0 | `e81510fd9ec5f309f58d5de83e9af6c95e267a13753d7e0bbfe7b91273a88bee` | static |
| PortAudio | git `873e3c83fbe2f57ebcf59083e627a3f8fa051ffe` | GitHub | shared (our CMake) |
| PortMidi (only with `AUDACITY_USE_MIDI`) | 2.0.8, git `101dac9455e2718512c94e24cbcae6a6f34b908b` | GitHub | static |
| soxr, SoundTouch, SBSMS, pffft, portsmf, SQLite, TwoLAME, libnyquist | as bundled in Audacity 3.7.9 `lib-src` | `native/audacity/lib-src` | upstream build descriptions |

Archives are downloaded once into `native/_deps/cache` (verified by SHA-256)
and git dependencies are mirrored there; afterwards builds work offline.  To
build without network, copy a populated `_deps/cache` directory.

## Symbol visibility

Upstream computes `<NAME>_API` with `export_symbol_define()` /
`import_symbol_define()`; the port does the same with `HAVE_VISIBILITY` on, so
the macros expand to `__attribute__((visibility("default")))`.  Upstream Linux
and macOS builds do **not** use `-fvisibility=hidden` (their `HAVE_VISIBILITY`
is only set after the libraries are configured, so the macros are even
empty), and the libraries depend on that: header templates with function-local
statics, e.g. `ClientData::Site<AudacityProject,...>::GetFactories()` ("linker
eliminates duplicates"), must be one object across all shared libraries.
With hidden visibility each `.so` would get its own copy.  Moreover the
export annotations are only complete for the Windows dllexport model: a
host build with `AUDACITY_HIDDEN_VISIBILITY=ON` fails to link `lib-wave-track`
(`staffpad::TimeAndPitch::~TimeAndPitch()` is not exported).  The port
therefore keeps default visibility; the option exists for experiments only.
