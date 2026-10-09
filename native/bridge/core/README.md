# Bridge spine (`native/bridge/core`) — guide for module authors

The spine owns the engine thread, the bootstrap, the command dispatcher, the
project session, undo/history, settings and the snapshot. Feature modules
(`edit/ effects/ io/ audio/ display/`) only register commands and hooks.
Contract: `../API.md` (wire protocol) and `../MODULES.md` (C++ structure).
All code is `namespace aubridge`, GPL-2.0-or-later.

## 1. Your module's entry point

Replace the stub `<module>/Register<Module>Module.cpp` and
`<module>/sources.cmake`:

```cmake
# <module>/sources.cmake
set(AUBRIDGE_EDIT_SOURCES
   ${CMAKE_CURRENT_LIST_DIR}/RegisterEditModule.cpp
   ${CMAKE_CURRENT_LIST_DIR}/SelectCommands.cpp)
# optional: AUBRIDGE_EDIT_ANDROID_SOURCES, AUBRIDGE_EDIT_LIBRARIES,
#           AUBRIDGE_EDIT_ANDROID_LIBRARIES
```

```cpp
#include "Modules.h"      // ModuleRegistry, CommandFlag
#include "Edit.h"         // RunEdit, ModifyState, TrackById, ClipRef, AudioBusy
#include "Session.h"      // Session::Get()

namespace aubridge {
void RegisterEditModule(ModuleRegistry &registry)
{
   registry.AddCommand("select.all", [](const json &args) -> json {
      auto &project = Session::Get().RequireProject();
      ...                                  // library calls
      ModifyState(project, false);         // selection-only: keep it in the undo state
      return json::object();               // the envelope's "result"
   }, NeedsProject | SelectionOnly);
}
}
```

`Register*Module` runs once per `Start()` on the engine thread, after the
preferences exist and before `PluginManager::Initialize`. Everything you
register is cleared at `Stop()`.

## 2. Commands

* Handler: `json handler(const json &args)`; `args` is always an object.
* Flags (`ModuleRegistry.h`): `NeedsProject` (else `NO_PROJECT`),
  `NeedsIdleAudio` (else `AUDIO_BUSY`; give it to every command the API marks
  without **I**), `Mutates` (**M**: generation bump + snapshot after the
  handler), `SelectionOnly` (**S**: snapshot, no bump), `LongRunning` (**L**).
* Arguments: `ArgDouble/ArgInt/ArgBool/ArgString/ArgStringArray/ArgIntArray`
  (required) and `Opt*` (optional) from `Json.h` throw
  `BridgeError{INVALID_ARGS}` with a readable message; `RequireRange`.
  The bridge compiles with `JSON_USE_IMPLICIT_CONVERSIONS=0`: write
  `j.get<double>()`, not `double d = j;`.
* Errors: `Fail(ErrorCode::NOT_FOUND, "...")` (= `throw BridgeError`).
  `AudacityException` → `FAILED` (its message), `UserException` →
  `CANCELLED`, other exceptions → `INTERNAL`; for `Mutates` commands the spine
  rolls the project back in those three cases.
* Strings: `ToUtf8(wxString)`, `FromUtf8(std::string)`,
  `Translated(TranslatableString)`. Never `ToStdString()`/`c_str()` (locale
  encoding). Undo descriptions are `XO("...")`.
* The spine drains the CallAfter queue and emits the `snapshot` event
  *before* the response is returned (API.md §3.1).

## 3. Helpers (engine thread only)

| Helper | Header | Use |
|---|---|---|
| `Session::Get().RequireProject()` / `Project()` | `Session.h` | the open project |
| `Session::Get().Touch()` / `ScheduleSnapshot()` | `Session.h` | model changed outside a command (recording, import progress): bump + snapshot (≤ 5 Hz from the tick) / snapshot only |
| `Session::Get().GetPaths()`, `Config()` | `Session.h` | app directories, the start configuration (`Config().raw` = the JSON) |
| `RunEdit(p, XO(long), XO(short), fn [, UndoPush])` | `Edit.h` | mutation + `PushState`; `fn` may return `bool` (false = no undo state); rollback + rethrow on any exception |
| `RunEditSelf(p, fn)` | `Edit.h` | `fn` pushes itself (CONSOLIDATE, several states, effects' kSkipState) and returns whether the model changed |
| `ModifyState(p, wantsAutoSave)` | `Edit.h` | no history entry: selection (`false`), mute/solo/rate (`true`) |
| `TrackIdValue(track)`, `TrackById(p, id)`, `RequireTrack`, `RequireWaveTrack` | `Edit.h` | int64 track ids (stable across undo: `lib-track/UndoTracks.cpp` patch) |
| `ResolveClipRef(p, args)` | `Edit.h` | `{trackId, clipIndex, generation}` → `ClipRef{track, clip, index}` (STALE / NOT_FOUND) |
| `AudioBusy(p)` | `Edit.h` | the AudioIONotBusy predicate (token active or any stream open) |
| `Clipboard::Get()` | `Clipboard.h` | port of `src/Clipboard` (cleared when its project closes) |
| `ProgressScope`, `Dialogs::Show/Ask/Choose/ChooseMany` | `UiServices.h` | `progress` / `dialog` events for work that does not go through BasicUI (see below) |
| `Events::Emit(type, json)`, `Events::Log(level, text)` | `Events.h` | raw events / `log` events (+ logcat) |
| `BuildSnapshot()`, `ComputeCommandFlags(p)`, `FormatName`, `ParseFormat`, `TrackKind` | `Snapshot.h` | snapshot pieces |
| `EngineThread::Get().PostInternal(fn)`, `WaitModal(future)`, `IsCurrent()` | `EngineThread.h` | helper threads (export): post back to the engine, wait without blocking CallAfter work |
| `AudioUserLatencyTrimMs` | `BridgePrefs.h` | the user's latency trim (ms, `settings.latencyCorrectionMs`, pref `/Android/AAudio/UserLatencyTrimMs`); read it through this object or `gPrefs`, never through a second `DoubleSetting` (stale cache) |
| `Language::Current()` | `Language.h` | language of the engine strings (`"en"`, `"ko"`) |

Multiple choice (API.md §4.4 `kind:"multiChoice"`), e.g. the streams of a
multi-stream file on import:

```cpp
#include "UiServices.h"
// Blocks the engine thread (nested loop, internal work only) until Kotlin
// answers with replyDialogChoices / replyDialog
std::optional<std::vector<int>> Dialogs::ChooseMany(
   const std::string &title, const std::string &message,
   const std::vector<std::string> &choices,
   const std::vector<bool> &defaultChecked = {},   // missing = unchecked
   const std::string &helpPage = {});
// -> checked indices (ascending, unique, possibly empty), or std::nullopt
//    when cancelled / no UI / engine stopping
```

Library code that uses `BasicUI` (progress dialogs, message boxes, error
dialogs) already produces the right events: OK-only boxes and error dialogs
are non-blocking `dialog` events; Yes/No/Cancel questions block the engine
thread until Kotlin calls `ReplyDialog`; progress `Poll()` returns
`Cancelled`/`Stopped` after `CancelProgress`. `MakeProgress` never returns
null.

Already linked into the spine (do not duplicate): `ProjectTempoListener`
(applies the project tempo to every added track — Paste/effects/import throw
without it), the `TimeSignatureRestorer` undo extension, the `Clipboard`.

## 4. Hooks (`ModuleRegistry.h`, `Hooks.h`)

* Bootstrap: `AddBeforePluginManagerInit` (register effects),
  `AddBeforeImportExportInit` (Android codec plug-ins), `AddAfterBootstrap`,
  `AddBeforeShutdown`.
* Projects: `AddProjectOpened(fn(AudacityProject&))` (the session is
  current), `AddProjectClosing(fn)` (stop streams for that project here).
* `AddTickHandler(fn)`: on the outermost loop, every ~50 ms while the engine
  is active (a task ran in the last 2 s, an audio stream is open or a
  snapshot is pending), else every ~2 s (transport polling, meters, throttled
  snapshots).  Work that must run promptly while idle posts a task instead.
* `AddSnapshotContributor(fn(json&, AudacityProject&))`: runs after the spine
  filled the snapshot (incl. `flags`; you may OR bits). Set `lastEffect`,
  `lastGenerator`, `lastAnalyzer`, `lastTool` (the spine derives the
  `LAST_*` flag bits from them). Pending recording tracks carry TrackId −1 in
  the library: the audio module should replace their `id` with its synthetic
  id (≤ −2) here.
* `SetTransportReader(fn)`, `SetMetersReader(fn)`: plain function pointers
  called on ANY thread by `ReadTransport`/`ReadMeters` once the engine is
  ready — lock-free, no library objects.
* `SetDisplayProviders({waveColumns, envelopeColumns, waveSamples,
  spectrogramColumns})`: called on the engine thread from the display lane;
  write only into the buffer you are given (it belongs to the task, the spine
  copies it to the caller). Return codes: `DisplayStatus::*`.
* `SetWaveVersionProvider(fn)`: replace the default `waveVersion` hash
  (clip geometry + sample block ids + envelope points, masked to 62 bits).
* `SetStreamFinalizer(fn(AudacityProject&))`: the spine calls it before a
  `NeedsIdleAudio` command when the project's stream drained but was not
  stopped/finalized yet.
* `SetRecordingPreparer(fn(AudacityProject&))` (display module) /
  `RunRecordingPreparer(project)` (audio module, after the pending tracks of
  a recording are registered and before `AudioIO::StartStream`): attach
  per-clip objects to the recording targets' clips while no other thread
  uses them. Once AudioIO captures, its thread iterates the attachments of
  those clips (`WaveTrack::Append` → `WaveClip::MarkChanged`): never create a
  `WaveClip` attachment of a capture target then.

## 5. Threading rules

* Every library call happens on the engine thread (`EngineThread::IsCurrent()`).
  Handlers, hooks, tick handlers, contributors and display providers already
  run there.
* Queues: internal (`BasicUI::CallAfter`) > commands (`Invoke`) > display.
  Nested loops (`BasicUI::Yield`, blocking dialogs) run internal work only, so
  a command never runs inside another command. Do not drain queues from a
  progress `Poll()`.
* Helper threads must not touch library objects; post results back with
  `EngineThread::Get().PostInternal(...)`.
* Never call `Invoke()` or the display entry points on the engine thread.
* Do not call `FileNames::SetAudacityPathList` (the spine sets it once:
  `<nyquistDir parent>`, `<pluginsDir parent>`, `DataDir`, `<configDir>/locale`).
* Do not call `Languages::SetLang`: the spine applies the `language` setting
  (`Language::Apply`, also at run time from `settings.set`).  Translate when
  you produce output (`Translated(XO(...))`, `.Translation()`), never cache
  translated strings: the language can change while the engine runs.

## 6. Bootstrap order (Engine.cpp)

`Start()` (caller's thread, before the engine thread exists): env (`HOME`,
`XDG_*_HOME`, `TMPDIR`, `SQLITE_TMPDIR`, `WX_AUDACITY_DATA_DIR`), published as
a new `environ` array instead of `setenv()` (other app threads may `getenv()`
concurrently) → engine thread: directories → `wxInitialize` → BasicUI services → `InitializeSQL` →
logger (wxLog → `log` events) → path list, default temp dir → preferences
(`filesDir/audacity/audacity.cfg`, mobile defaults on first run) → language
(`Language::Apply`: catalog from `<configDir>/locale`, C locale fixups) →
TempDir (`noBackupDir/SessionData`) → spine commands + `Register*Module` →
`BeforePluginManagerInit` → `PluginManager::Initialize` (registry reset when
the engine build changed) → `InitDitherers`, `AudioIO::Init` →
`BeforeImportExportInit` → `Importer`/`ExportPluginRegistry::Initialize` →
self checks → `AfterBootstrap` → recovery scan → empty project (unless
recoverable) → `engine.ready` + `snapshot`.

## 7. Testing

Host tests drive the engine through `include/aubridge/Bridge.h` only. Add
`native/tests/bridge/<module>/CMakeLists.txt` (picked up automatically):

```cmake
add_executable(bridge-test-edit EditTest.cpp)
target_link_libraries(bridge-test-edit PRIVATE bridge-test-support)
set_target_properties(bridge-test-edit PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
add_test(NAME bridge-edit COMMAND bridge-test-edit)
```

`BridgeTestSupport.h` (in `native/tests/bridge/core`) gives `TempDirs`
(temporary filesDir/noBackupDir/cacheDir + `ConfigJson()`), a recording
`Sink` (`WaitFor`, `Last`, auto-reply callbacks for blocking dialogs and
progress), `Call(command, args)` and `CHECK`. Use `debug.makeTestTrack`
(`{seconds, frequency, channels, rate}`) to get audio before the effects
module exists. One engine per process; `Stop()` + `Start()` again works with
the same directories.

```sh
flock /tmp/claude-0/locks/build-host.lock ninja -C native/build-host bridge-test-core
native/build-host/bin/bridge-test-core          # BRIDGE_TEST_VERBOSE=1 prints events
ctest --test-dir native/build-host -R bridge --output-on-failure
```

`AUBRIDGE_LOG_STDERR=1` copies `log` events to stderr on the host.
