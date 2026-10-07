# Bridge internals: spine and modules

`native/bridge` is split into a **spine** (engine thread, bootstrap, command
dispatch, project session, snapshot, undo/history, settings) and **feature
modules** that only register commands and hooks. The wire protocol is in
`API.md`; this file fixes the C++ structure so modules can be written in
parallel.

## Layout

```
native/bridge/
  API.md, MODULES.md
  CMakeLists.txt            (spine owner) targets below
  include/aubridge/Bridge.h public entry points (JNI + host tests)
  third_party/nlohmann/json.hpp
  core/                     spine (namespace aubridge)
  edit/                     selection, edit, tracks, clips, labels      -> API.md "edit module"
  effects/                  effects, presets, preview, analyzers        -> "effects module"
  io/                       import, export, Android codec plug-ins      -> "io module"
  audio/                    transport, meters, devices                  -> "audio module"
  display/                  waveform/envelope/sample/spectrogram data   -> "display module"
native/portaudio-android/   PortAudio AAudio host API (+ host "null" test device)
native/jni/                 JNI glue (Android only)
native/tests/bridge/        host tests driving the bridge through Bridge.h
```

## CMake targets

* `aubridge-core` — STATIC, PIC: `core/*.cpp`. Links `audacity-core`.
* `aubridge-<module>` — STATIC, PIC, one per module directory, sources listed
  in `<module>/sources.cmake` (`set(AUBRIDGE_<MODULE>_SOURCES ...)`), linking
  `aubridge-core`. Android-only sources (NDK media) go in
  `AUBRIDGE_<MODULE>_ANDROID_SOURCES`.
* `audacity-bridge` — SHARED (`libaudacity-bridge.so`): whole-archive of the
  core and every module + `audacity-core`. This is what JNI and the host tests
  link.
* Each module may add host tests under `native/tests/bridge/` (one executable
  per module, e.g. `bridge-test-edit`), registered with `add_test`.

## Module hooks

A module provides exactly one function, declared in `core/Modules.h`:

```cpp
namespace aubridge {
void RegisterEditModule(ModuleRegistry &);      // edit/
void RegisterEffectsModule(ModuleRegistry &);   // effects/
void RegisterIoModule(ModuleRegistry &);        // io/
void RegisterAudioModule(ModuleRegistry &);     // audio/
void RegisterDisplayModule(ModuleRegistry &);   // display/
}
```

The spine calls them once, on the engine thread, during bootstrap. Through
`ModuleRegistry` a module can:

* `AddCommand(name, handler, flags)` — `handler` is
  `nlohmann::json(const nlohmann::json &args)`; flags:
  `NeedsProject`, `NeedsIdleAudio` (rejects with `AUDIO_BUSY` when AudioIO is
  busy for the project), `Mutates` (the spine bumps the generation and emits a
  snapshot afterwards, also on failure after a rollback), `SelectionOnly`
  (snapshot without undo entry), `LongRunning` (informational).
* add bootstrap hooks: `BeforePluginManagerInit` (effects registration),
  `AfterBootstrap` (e.g. register Android codec plug-ins *before* the spine
  calls `Importer::Initialize` / `ExportPluginRegistry::Initialize` — so this
  one is actually `BeforeImportExportInit`), `AfterBootstrap`.
* add project hooks: `OnProjectOpened(AudacityProject&)`,
  `OnProjectClosing(AudacityProject&)`.
* add a tick handler (called every ~50 ms on the engine thread).
* contribute to the snapshot: `SnapshotContributor(json &snapshot,
  AudacityProject&)` (e.g. effects sets `lastEffect`).

## Spine services used by modules (all on the engine thread)

* `BridgeError{code, message}` — throw from a handler to return an error
  envelope (`code` from API.md §3.1).
* `Session::Get()` — `AudacityProject *Project()`, `AudacityProject
  &RequireProject()`, `uint64_t Generation()`, `void Touch()` (bump generation +
  schedule a snapshot event), `const Paths &GetPaths()`.
* `RunEdit(project, longDesc, shortDesc, fn)` — runs `fn`, then
  `ProjectHistory::PushState`; on exception `RollbackState()` and rethrow
  (port of AudacityApp's exception handling). `ModifyState(project)` for
  selection-only changes.
* `TrackById(project, int64 id)` / `TrackIdValue(const Track&)` — numeric
  TrackId mapping (stable across undo thanks to the UndoTracks patch).
* `ClipRef` parsing/validation (`{trackId, clipIndex, generation}` → `WaveClip*`,
  throws `STALE`/`NOT_FOUND`).
* `Events::Emit(type, json)`.
* `AudioBusy(project)` — `ProjectAudioIO::Get(p).GetAudioIOToken() > 0 &&
  AudioIO::Get()->IsStreamActive(token)`.
* `Clipboard` (port of `src/Clipboard.{h,cpp}`) — global clipboard used by
  edit commands (lives in the spine because the snapshot reports it).
* `EngineThread::IsCurrent()`, `EngineThread::Get().PostInternal(fn)`,
  `WaitModal(future)` (for work on helper threads, e.g. export).
* Progress for module-driven work: the spine's `BasicUI::Services`
  implementation maps `BasicUI::MakeProgress` / `MakeGenericProgress` to
  `progress` events and the `CancelProgress` atomics, so library code that uses
  BasicUI just works. Modules with their own listener (import) use
  `ProgressScope` from the spine to emit the same events.
