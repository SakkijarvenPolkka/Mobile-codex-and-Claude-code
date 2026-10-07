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
  in `<module>/sources.cmake` (`set(AUBRIDGE_<MODULE>_SOURCES ...)`, prefer
  `${CMAKE_CURRENT_LIST_DIR}/x.cpp`), linking `aubridge-headers` (the core's
  include dirs + `audacity-core`; not `aubridge-core` itself, which must only
  appear in the whole-archive link). Android-only sources (NDK media) go in
  `AUBRIDGE_<MODULE>_ANDROID_SOURCES`; extra link libraries in
  `AUBRIDGE_<MODULE>_LIBRARIES` / `AUBRIDGE_<MODULE>_ANDROID_LIBRARIES`
  (`io` gets `mediandk` automatically when it has Android sources).
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
  snapshot afterwards, also on failure after a rollback; a `BridgeError`
  thrown outside `RunEdit` is treated as "nothing changed"), `SelectionOnly`
  (snapshot without undo entry and without generation bump), `LongRunning`
  (informational). The handler returns the `result` object.
* add bootstrap hooks: `AddBeforePluginManagerInit` (effects registration),
  `AddBeforeImportExportInit` (e.g. register Android codec plug-ins *before*
  the spine calls `Importer::Initialize` / `ExportPluginRegistry::Initialize`),
  `AddAfterBootstrap` (before `engine.ready`), `AddBeforeShutdown` (at
  `Stop()`, after the project was closed).
* add project hooks: `AddProjectOpened(fn(AudacityProject&))` (after the
  session is current), `AddProjectClosing(fn(AudacityProject&))` (still fully
  usable; stop streams here).
* add a tick handler (called every ~50 ms on the engine thread).
* contribute to the snapshot: `SnapshotContributor(json &snapshot,
  AudacityProject&)` (e.g. effects sets `lastEffect`).

## Spine services used by modules (all on the engine thread)

* `BridgeError{code, message}` — throw from a handler to return an error
  envelope (`code` from API.md §3.1).
* `Session::Get()` — `AudacityProject *Project()`, `AudacityProject
  &RequireProject()`, `uint64_t Generation()`, `void Touch()` (bump generation +
  schedule a snapshot event), `void ScheduleSnapshot()` (no bump),
  `const Paths &GetPaths()`, `Config()`, `RecordPermission()` /
  `SetRecordPermission(bool)`.
* `RunEdit(project, XO(long), XO(short), fn [, UndoPush])` — runs `fn`
  (returning `void`, or `bool`: false = nothing changed, no undo state), then
  `ProjectHistory::PushState`; on exception `RollbackState()` and rethrow
  (port of AudacityApp's exception handling). `RunEditSelf(project, fn)` when
  `fn` pushes (or not) itself and returns whether it changed the model.
  `ModifyState(project, wantsAutoSave)` for changes without a history entry
  (selection: false; mute/solo/project rate: true).
* `TrackById(project, int64 id)` / `TrackIdValue(const Track&)` — numeric
  TrackId mapping (stable across undo thanks to the UndoTracks patch).
* `ClipRef` parsing/validation (`{trackId, clipIndex, generation}` → `WaveClip*`,
  throws `STALE`/`NOT_FOUND`).
* `Events::Emit(type, json)`.
* `AudioBusy(project)` — `AudioIOBase::IsAudioTokenActive(projectToken) ||
  AudioIOBase::IsBusy()` (3.7.9's `AudioIONotBusyFlag` predicate plus effect
  preview streams; monitoring does not count). Before a `NeedsIdleAudio`
  command the spine runs the audio module's `SetStreamFinalizer` hook when the
  project's stream drained without being finalized.
* `Clipboard` (port of `src/Clipboard.{h,cpp}`) — global clipboard used by
  edit commands (lives in the spine because the snapshot reports it). The
  spine also links the ports of `src/ProjectTempoListener.cpp` and the
  `TimeSignatureRestorer` undo extension (every module depends on them).
* `EngineThread::IsCurrent()`, `EngineThread::Get().PostInternal(fn)`,
  `WaitModal(future)` (for work on helper threads, e.g. export).
* Progress for module-driven work: the spine's `BasicUI::Services`
  implementation maps `BasicUI::MakeProgress` / `MakeGenericProgress` to
  `progress` events and the `CancelProgress` atomics, so library code that uses
  BasicUI just works. Modules with their own listener (import) use
  `ProgressScope` from the spine to emit the same events; `Dialogs::Show/Ask/
  Choose` emit `dialog` events directly.
* Registration points for the Bridge.h entry points (`core/Hooks.h`):
  `SetTransportReader` / `SetMetersReader` (lock-free function pointers,
  any thread), `SetDisplayProviders` (called on the engine thread's display
  lane), `SetWaveVersionProvider` (snapshot `waveVersion`), and
  `SetStreamFinalizer`. Until installed, the entry points answer "not ready".
* `FileNames::SetAudacityPathList` is called once by the spine (it replaces
  the list); modules must not call it.

See `core/README.md` for a walk-through (adding a command, helpers,
threading rules, tests).
