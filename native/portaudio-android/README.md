# PortAudio for Android (Audacity port)

PortAudio upstream has no Android host API.  The superbuild compiles
PortAudio's portable core (`src/common`, `src/os/unix`) from a pinned upstream
commit (see `native/cmake/Dependencies.cmake`) with
`native/cmake/deps/portaudio/CMakeLists.txt`, and on Android replaces
`pa_unix_hostapis.c` with `pa_android_hostapis.c` from this directory.

* `pa_android_hostapis.c` - host-API table. Registers `PaAAudio_Initialize`
  when compiled with `PA_USE_AAUDIO=1`, and `PaSkeleton_Initialize` with
  `PA_USE_SKELETON=1`.
* `sources.cmake` - list the AAudio back-end sources here
  (`PA_AAUDIO_SOURCES`, `PA_AAUDIO_INCLUDE_DIRS`).

CMake options (cache variables of the superbuild):

| Option | Default | Meaning |
|---|---|---|
| `PA_USE_AAUDIO` | `OFF` | Compile the AAudio host API from `sources.cmake` and link `libaaudio` (Android only). |
| `PA_USE_SKELETON` | `OFF` | Compile PortAudio's skeleton host API (a template, no devices). |

Without any host API `Pa_Initialize()` succeeds and reports zero devices.
The library is built as `libportaudio.so` (one shared instance, because both
`lib-audio-devices` and `lib-audio-io` call `Pa_*`).
