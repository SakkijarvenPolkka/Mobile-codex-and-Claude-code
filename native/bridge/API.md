# Audacity Android bridge — engine API contract

This document is the **single source of truth** for the interface between the
native engine (`native/bridge`, C++, drives the Audacity 3.7.9 libraries) and
the Android app (`app/`, Kotlin). Both sides implement exactly what is written
here. If something must change, change this file first.

Design notes with the library-level details live outside the repository in
the porting notes; the parts that matter for the contract are summarised here.

## 1. Architecture in one picture

```
 Compose UI ──► EngineClient (Kotlin, coroutines) ──JNI──► libaudacity-bridge.so ──► lib-*.so / libmod-*.so
     ▲                │ invoke(cmd, json) blocks a background thread            │
     │                │ lock-free reads: transport / meters                     │ all library calls run on
     └── StateFlows ◄─┘ events: snapshot / progress / dialog / transport ◄──────┘ ONE native "engine" thread
```

* **Engine thread.** Audacity's "main thread" is a dedicated native thread
  (8 MiB stack) created by `aubridge::Start`. `wxInitialize()` runs on it, so
  `wxIsMainThread()` is true only there. Every call into the libraries
  (projects, tracks, undo, effects, import/export, AudioIO start/stop, waveform
  caches) happens on this thread. JNI posts closures to it.
* **Kotlin never calls a blocking native function on the Android main
  thread.** `EngineClient` runs `invoke` on a dedicated single-thread
  dispatcher (or `Dispatchers.IO`).
* **Lock-free reads** (`readTransport`, `readMeters`) may be called from any
  thread, including the UI thread every frame.
* **Events** are delivered from the engine thread through
  `EngineListener.onEvent`; Kotlin must not block in it (copy and hand off).

## 2. JNI surface

Kotlin: `object io.github.sakkijarvenpolkka.audacity.engine.NativeBridge`.
Native: `native/jni/` → `libaudacity-jni.so` (its only export is
`JNI_OnLoad`, which binds the functions below with `RegisterNatives`; it
links `libaudacity-bridge.so`, whose DT_NEEDED closure holds every Audacity
library and module; also built on the host for the JVM tests, see
`native/jni/README.md`). All strings cross JNI as **UTF-8
`ByteArray`s** (JNI's `NewStringUTF` uses *modified* UTF-8 and breaks file
names with emoji).

| Kotlin declaration | Thread | Meaning |
|---|---|---|
| `external fun start(config: ByteArray, listener: EngineListener): Boolean` | any | Starts the engine thread (once per process). `config` = JSON §2.1. Returns false if already started or the thread could not be created. Bootstrap runs asynchronously; completion is the `engine.ready` / `engine.failed` event. |
| `external fun invoke(command: ByteArray, args: ByteArray): ByteArray` | background only | Posts the command to the engine thread and blocks until it finished. Returns the response envelope §3.1 (UTF-8 JSON). Calls are executed in order of arrival. |
| `external fun replyDialog(dialogId: Int, button: Int)` | any | Answers a blocking `dialog` event (§4.4). |
| `external fun replyDialogChoices(dialogId: Int, indices: IntArray)` | any | Answers a blocking `kind:"multiChoice"` dialog with the checked indices (§4.4). `replyDialog(id, -1)` cancels it. |
| `external fun cancelProgress(progressId: Int, stop: Boolean)` | any | Requests cancel (`stop=false`) or stop (`stop=true`, keep partial result where supported, e.g. import) of a running progress (§4.3). Sets an atomic; never blocks. |
| `external fun readTransport(out: DoubleArray): Boolean` | any, lock-free | Fills `out` (size ≥ 16) with the transport snapshot §6.4. Returns false before the engine is ready. |
| `external fun readMeters(out: FloatArray): Boolean` | any, lock-free | Fills `out` (size ≥ 14) with meter values §6.5 and resets the peak accumulators. |
| `external fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long` | background only | Waveform column data §7.2. |
| `external fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long` | background only | Clip-envelope gain per column §7.3. |
| `external fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): ByteArray?` | background only | Individual samples for deep zoom §7.4. |
| `external fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int, out: ByteArray): Long` | background only | Spectrogram §7.5. |

`interface EngineListener { fun onEvent(type: String, payload: ByteArray) }`
(`type` is plain ASCII, `payload` UTF-8 JSON).

Outside the app surface, `external fun stop()` stops the engine thread and
waits for it (closing the project without saving); `start` may be called
again afterwards. Host tests only (background thread); the app never stops
the engine.

Argument errors of the JNI layer itself: a null/too small array or `count`,
`rows` outside §7's limits return `-1` from the display functions (the Java
array is left untouched, as for every negative status except `-2`), `false`
from `readTransport`/`readMeters`; `invoke` with a null command answers an
`INVALID_ARGS` envelope (`generation` 0); `replyDialogChoices` with a null
array cancels. A Java exception thrown by `onEvent` is logged and cleared
(the engine goes on).

Kotlin implementation notes (`engine/`, binding for the JNI glue):

* `EngineListener` is a Kotlin `fun interface` in package
  `io.github.sakkijarvenpolkka.audacity.engine`; JNI calls
  `onEvent(Ljava/lang/String;[B)V` on the object passed to `start`.
* The `external` functions are plain members of `object NativeBridge` (no
  `@JvmStatic`): the JNI functions
  `Java_io_github_sakkijarvenpolkka_audacity_engine_NativeBridge_<name>` receive
  the object instance as second argument (`jobject thiz`), not a `jclass`.
* `System.loadLibrary("audacity-jni")` is attempted once, guarded
  (`NativeBridge.isLoaded`); without the library (or when `JNI_OnLoad` finds
  a declaration that does not match) the app falls back to the in-memory
  `FakeAudacityEngine`. The system property `audacity.jni.library` (an
  absolute path, host JVM tests) makes it `System.load` that file instead.
* `NativeAudacityEngine` runs `invoke` on one "audacity-invoke" thread and the
  display functions on a separate "audacity-display" thread; events are
  decoded on an "audacity-events" thread, and a typed call returns only after
  the events that arrived before its response were applied (so the `snapshot`
  of a command is already visible in `AudacityEngine.snapshot`).

### 2.1 Start configuration

```json
{
  "filesDir":     "/data/user/0/<pkg>/files",          // Context.filesDir
  "noBackupDir":  "/data/user/0/<pkg>/no_backup",      // Context.noBackupFilesDir
  "cacheDir":     "/data/user/0/<pkg>/cache",          // Context.cacheDir
  "nyquistDir":   "/data/user/0/<pkg>/files/audacity/nyquist",   // extracted from assets/audacity/nyquist
  "pluginsDir":   "/data/user/0/<pkg>/files/audacity/plug-ins",  // extracted from assets/audacity/plug-ins
  "locale":       "ko_KR",                              // java.util.Locale.getDefault().toString()
  "deviceModel":  "Pixel 8",                            // informational
  "audioOutputSampleRate": 48000,                       // AudioManager PROPERTY_OUTPUT_SAMPLE_RATE (0 if unknown)
  "audioFramesPerBuffer": 192,                          // AudioManager PROPERTY_OUTPUT_FRAMES_PER_BUFFER (0 if unknown)
  "recordPermission": true                              // RECORD_AUDIO currently granted
}
```

Directory use (engine side): preferences `filesDir/audacity/audacity.cfg`,
plug-in settings next to it, saved projects default to `filesDir/Projects`,
unsaved/temporary projects and autosave in `noBackupDir/SessionData`, SQLite
temp files and staging for import/export in `cacheDir/tmp`, `cacheDir/import`,
`cacheDir/export`.

Kotlin extracts `assets/audacity/{nyquist,plug-ins,locale}` to
`filesDir/audacity/` before calling `start` (re-extract when the app version
changes; replace only `nyquist/`, `plug-ins/` and `locale/`, never the whole
`filesDir/audacity`, which also holds the preferences). `locale/` holds the
engine's gettext catalogs, `locale/<lang>/LC_MESSAGES/audacity.mo` (packaged
from `native/audacity/locale/<lang>/LC_MESSAGES/audacity.mo`; v1: `ko`); the
engine picks the catalog from `locale` and the `language` setting (§5.1).
`nyquistDir`/`pluginsDir` are optional (defaults
`filesDir/audacity/nyquist` and `.../plug-ins`); the engine searches
`<parent>/nyquist/nyquist.lsp` and `<parent>/plug-ins/*.ny`, so the last path
components must be `nyquist` and `plug-ins`. The engine creates the
directories above. Native implementation note: the directories are derived
through `HOME`, `XDG_*_HOME`, `TMPDIR`, `SQLITE_TMPDIR` set by the engine and
cached per process, so a restart in the same process must use the same paths.

The engine reports `recordPermission` changes through the
`audio.permission` command (§6.6).

## 3. Commands

### 3.1 Envelope

Request: `invoke(commandName, argsJson)`; `argsJson` is a JSON object (`{}` when
there are no arguments).

Response (always a JSON object):

```json
{ "ok": true,  "result": { ... }, "generation": 42 }
{ "ok": false, "error": { "code": "AUDIO_BUSY", "message": "human readable" }, "generation": 42 }
```

`message` is English, or in the engine language (§5.1 `language`) when it
comes from the libraries' translated strings; Kotlin shows it but never
parses it (use `code`).

* `generation` is the project-model generation after the command (0 when no
  project is open). It increases after every mutation, undo, redo, rollback and
  whenever recording/import changes tracks, and when a project is opened or
  created (it never restarts, so references from a closed project are stale).
  It is **not** bumped by selection/view-only (**S**) commands, so they do not
  invalidate clip references.
* Before `engine.ready` (and after `Stop()`) every command answers
  `NOT_READY`. Arguments that are not a JSON object answer `INVALID_ARGS`.
* A command that changed the model also causes a `snapshot` event (§4.2)
  **before** its response is returned. Kotlin must not rely on the response
  for model state; use the snapshot flow.

Error codes:

| code | meaning |
|---|---|
| `NOT_READY` | engine not bootstrapped yet |
| `NO_PROJECT` | command needs an open project |
| `AUDIO_BUSY` | command not allowed while playing/recording (Audacity's `AudioIONotBusy` flag) |
| `NO_SELECTION` | needs a time selection and/or selected tracks |
| `INVALID_ARGS` | missing/ill-typed argument, or value out of range |
| `NOT_FOUND` | unknown track/clip/effect/format/preset/file |
| `STALE` | a clip reference carries an old `generation` (UI must refresh) |
| `NEEDS_PATH` | `project.save` on a never-saved project: use `project.saveAs` |
| `CANCELLED` | user cancelled a long operation |
| `UNSUPPORTED` | not available in this build/device |
| `FAILED` | the library reported a failure (message has details) |
| `INTERNAL` | unexpected exception (bug) |
| `UNKNOWN_COMMAND` | no such command |

### 3.2 Conventions

* Times are seconds (double), absolute project time.
* Track ids are the Audacity `TrackId` values (int64, stable across undo/redo
  thanks to the UndoTracks patch; valid within one engine session). Real ids
  are `>= 0`; `-1` is the library's "unassigned" value and never appears;
  synthetic ids of pending tracks during recording are `<= -2`.
* Clip references are `{"trackId": .., "clipIndex": .., "generation": ..}`
  where `clipIndex` indexes the clips of that track ordered by start time
  (`WaveTrack::SortedIntervalArray()`), exactly as in the snapshot of that
  generation. A reference with an older generation fails with `STALE`.
* Gain is linear amplitude (1.0 = 0 dB); pan in [-1, 1].
* Sample formats: `"int16"`, `"int24"`, `"float"`.
* Unless noted, commands act on the current project and on its selection
  (time selection + selected tracks), like the desktop menu item of the same
  name.

### 3.3 Command catalogue

Legend: **M** = mutates the model (pushes an undo state, emits `snapshot`);
**S** = selection/view change only (emits `snapshot`, no undo entry);
**L** = long-running (emits `progress` events, cancellable);
**I** = allowed while audio is busy (otherwise `AUDIO_BUSY`);
**U** = updates the current undo state without a new history entry
(`ProjectHistory::ModifyState(true)`), bumps the generation and emits a
snapshot (3.7.9 treats mute/solo this way).

#### app / settings (spine)

| command | args | result | flags |
|---|---|---|---|
| `app.info` | – | `{audacityVersion:"3.7.9", engineVersion, wxVersion, sqliteVersion, abi, libraries:[...], importers:[...], exporters:[...], effectsCount, nyquist:bool, language, languages:[..]}` — `language`: the language of the engine's strings now (`"en"`, `"ko"`); `languages`: `"en"` + the installed catalogs (valid `language` setting values besides `"system"`) | I |
| `settings.get` | – | `{settings: Settings}` (§5.1) | I |
| `settings.set` | `{settings: {partial Settings}}` | `{settings: Settings}` | I (device changes apply on next stream) |

#### project (spine)

| command | args | result | flags |
|---|---|---|---|
| `project.new` | – | `{}` | – Opens an empty project (closing the current one; unsaved changes are discarded — Kotlin asks the user first using `project.info.dirty`). One project at a time. |
| `project.open` | `{path}` (.aup3 in app storage) | `{}` | L. The current project is closed only when opening succeeded. `NOT_FOUND` for a missing file, `INVALID_ARGS` for a file that is not an SQLite database (use `import.files`) and for safety backups (`*.aup3.bak`, `*~.aup3`), `FAILED` when the project is already open or cannot be read. |
| `project.save` | – | `{path}` | L, fails `NEEDS_PATH` when the project was never saved |
| `project.saveAs` | `{path}` | `{path}` | L. Path must end with `.aup3`. |
| `project.saveCopy` | `{path}` | `{path}` | L. Writes a compact copy (used to share a project via SAF). |
| `project.close` | – | `{}` | Closes without saving. |
| `project.info` | – | `ProjectInfo` (§5.2) | I |
| `project.snapshot` | – | `Snapshot` (§4.2) — also re-emits the `snapshot` event | I |
| `project.setRate` | `{rate}` | `{}` | S (project rate is not an undoable state in 3.7.9; it updates the current undo state so autosave keeps it) |
| `project.recoverable` | – | `{projects:[{path,name,modifiedMs,sizeBytes}]}` | I. Autosaved projects left by a previous process. Only projects with something to recover: temporary projects without autosave or whose autosave has no track are deleted silently, saved projects without autosave (unmodified) are forgotten (the file is kept). Same rule for `recoverable` in `engine.ready`. |
| `project.recover` | `{path}` | `{}` | L Opens the recovered project (it stays temporary/dirty: `dirty` is true for a recovered unsaved project until it is saved). |
| `project.discardRecoverable` | `{paths:[..]}` | `{}` | – |
| `project.tags.get` | – | `{tags:[{name,value}]}` | I |
| `project.tags.set` | `{tags:[{name,value}]}` | `{}` | M |
| `project.list` | – | `{projects:[{path,name,modifiedMs,sizeBytes}]}` in `filesDir/Projects` (safety backups `*~.aup3` hidden; `sizeBytes` includes the `-wal` file) | I |
| `project.delete` | `{path}` | `{}` | – (not the open one; also deletes `-wal`/`-shm`) |
| `project.rename` | `{path, newName}` | `{path}` (new path) | – Renames `<name>.aup3` (+ `-wal`/`-shm`) in `filesDir/Projects`; `FAILED` for the open project or when the target exists. `newName` is trimmed and a trailing `.aup3` dropped; `INVALID_ARGS` when it is empty, contains `/`, `\` or control characters, starts with `.` or ends with `~` (hidden by `project.list`); `NOT_FOUND` for a missing `path`. Same name = no-op. |
| `project.compact` | – | `{freedBytes}` | M, L. Port of `ProjectFileManager::Compact`: discards the undo states except the current and the last saved one (the current one is renamed "Compacted project file"), clears the clipboard if it holds this project's audio, and vacuums the database (desktop *File ▸ Compact Project*; Kotlin asks first with `compactInfo`). `dirty` is unchanged. `freedBytes` ≥ 0 (file + `-wal`). |
| `project.compactInfo` | – | `{totalBytes, usedBytes, fileBytes, freeBytes}` | I. The numbers of the desktop's question: `totalBytes` = all sample blocks in the database, `usedBytes` = the blocks compaction keeps (≈ `totalBytes − usedBytes` can be recovered), `fileBytes` = `.aup3` + `-wal` size, `freeBytes` = free space on its file system (`-1` unknown). |

#### history (spine)

| command | args | result | flags |
|---|---|---|---|
| `history.undo` | – | `{}` | M |
| `history.redo` | – | `{}` | M |
| `history.list` | – | `{current:int, states:[{index, description, shortDescription, sizeBytes}]}` | I |
| `history.goto` | `{index}` | `{}` | M |
| `history.purge` | `{keepFrom:int}` | `{}` | M. Discards the states with `index < keepFrom` ("Discard" in the history window); `keepFrom` must be ≤ the current index. |

#### view (spine)

| command | args | result | flags |
|---|---|---|---|
| `view.set` | `{zoom?: pps, hpos?: seconds}` | `{}` | S, I. Persists ViewInfo zoom/scroll (saved in the project). Kotlin owns gesture math. |

#### debug (spine; development and tests, not for production UI)

| command | args | result | flags |
|---|---|---|---|
| `debug.makeTestTrack` | `{seconds?=1, frequency?=440, channels?=1, rate?=project rate, amplitude?=0.5}` | `{id}` | M. Appends a sine-tone WaveTrack ("Test Tone N"), one undo state. |
| `debug.ask` | `{message?, title?, cancel?:bool, choices?:[..], multiChoice?:bool, defaultChecked?:[bool]}` | `{result:"yes"\|"no"\|"cancel"\|"none"}` or `{choice}`; with `multiChoice:true` (+ `choices`) `{choices:[int]}` or `{result:"cancel"}` | I. Asks through BasicUI (blocking `dialog`), or `kind:"multiChoice"` through the spine's `Dialogs::ChooseMany`. |
| `debug.progress` | `{seconds?=1}` | `{stopped:bool}` | I, L. Runs a BasicUI progress; `CANCELLED` when cancelled. |
| `debug.addEnvelopePoint` | `{trackId, t, value}` (absolute time inside a clip; value 0 … 2) | `{clipIndex}` | M (display module). Adds/replaces a point of that clip's gain envelope (`Envelope::InsertOrReplace`), "Adjusted envelope." / "Envelope". `NOT_FOUND` when no clip is at `t`. |
| `debug.stretchClip` | `{trackId, clipIndex, ratio}` (0.1 … 10) | `{}` | M (display module). `WaveClip::StretchBy(ratio)`: the clip keeps its start (it may overlap the next clip), "Stretched clip" / "Stretch Clip". |
| `debug.recording` | `{action:"start", trackId?, newChannels?=1, t0?=0}` \| `{action:"append", seconds, frequency?=440}` \| `{action:"commit"}` \| `{action:"cancel"}` | `{}` | M (display module). Simulates a recording on the engine thread, without audio: `start` registers pending tracks like 3.7.9's `DoRecord` (the pending copy of `trackId` and/or `newChannels` new tracks — 2 = one stereo track — each with an empty clip at `t0`), `append` appends a sine (amplitude 0.5) to every target without flushing (the tail stays in the append buffer), `commit` flushes and applies the pending tracks ("Recorded Audio" / "Record"), `cancel` drops them. `INVALID_ARGS` when a recording is (not) being simulated. |

#### select / playRegion (edit module)

| command | args | flags |
|---|---|---|
| `select.set` | `{t0, t1, trackIds?:[..], focus?:id}` (time selection; t0==t1 = cursor; optional track part replaces the track selection — a waveform tap is one command) | S, I |
| `select.all` | – (all tracks, whole project) | S, I |
| `select.none` | – | S, I |
| `select.tracks` | `{ids:[..], mode:"set"\|"add"\|"remove"\|"toggle"}` | S, I |
| `select.trackHeader` | `{id, shift:bool, ctrl:bool}` (track-header tap semantics, `SelectionState::HandleListSelection`) | S, I |
| `select.allTracks` | – | S, I |
| `select.startToCursor` | – = 3.7.9 `SelTrackStartToCursor` (min start of selected tracks → t0) | S, I |
| `select.cursorToEnd` | – = `SelCursorToTrackEnd` | S, I |
| `select.trackStartToEnd` | – = `SelTrackStartToEnd` (extent of selected tracks) | S, I |
| `select.toProjectStart` / `select.toProjectEnd` | – = `SelStart` / `SelEnd` (Shift+Home/End: extend to 0 / project end) | S, I |
| `select.cursorToTrackStart` / `select.cursorToTrackEnd` | – = `CursTrackStart` / `CursTrackEnd` (moves the cursor, J/K) | S, I |
| `select.clip` | `{trackId, clipIndex, generation}` | S, I |
| `select.prevClip` / `select.nextClip` | – = `SelPrevClip` / `SelNextClip` | S, I |
| `select.prevClipBoundary` / `select.nextClipBoundary` | – = `CursPrevClipBoundary` / `CursNextClipBoundary` (cursor moves) | S, I |
| `select.zeroCrossing` | – = `ZeroCross` (moves the selection edges to nearby zero crossings, `SelectMenus.cpp`) | S |
| `select.focus` | `{id}` | S, I |
| `playRegion.set` | `{t0, t1, active:bool}` | S, I |
| `playRegion.clear` | – | S, I |
| `playRegion.toggle` | – | S, I |

Notes (edit module): every selection command ends with
`ProjectHistory::ModifyState(false)` (the selection is part of the undo
state; `select.trackHeader` too, where 3.7.9 autosaves); `select.focus` and
`playRegion.*` do not touch the undo state. Commands whose 3.7.9 menu item
needs `EditableTracksSelected` (`select.cursorToTrackStart/End`,
`select.zeroCrossing`) fail with `NO_SELECTION` (with `selectAllOnNone`
they first select all audio, like the edit commands); the always-enabled ones
(`select.startToCursor`, `cursorToEnd`, `trackStartToEnd`, the clip
navigation) silently do nothing when there is nothing to do. Clip navigation
searches the selected wave tracks, or all wave tracks when none is selected.
`select.clip` = a tap on a clip's title bar: only that track selected and
focused, the time selection = the clip's play region. `select.zeroCrossing`
fails with `FAILED` when a stretched clip lies in a search window (3.7.9
message). `select.set`/`playRegion.set` order `t0`/`t1`; `playRegion.set`
rejects `t0 < 0` (`INVALID_ARGS`); unknown ids are `NOT_FOUND` (nothing is
changed).

#### edit (edit module) — all **M**, no args, results `{}`

`edit.cut`, `edit.copy` (**S**: no undo entry, no generation bump; the clipboard is in the snapshot), `edit.paste`, `edit.delete`,
`edit.splitCut`, `edit.splitDelete`, `edit.silence`, `edit.trim`,
`edit.duplicate`, `edit.split`, `edit.splitNew`, `edit.join`,
`edit.detachAtSilences`.

`edit.clipboardInfo` → `{empty, t0, t1, trackCount}` (I).

Preconditions are the 3.7.9 menu flags; when they are missing the command
fails with `NO_SELECTION`: cut, delete, copy, duplicate, splitCut,
splitDelete, detachAtSilences need a time selection and selected editable
tracks; silence, trim, splitNew a time selection and selected wave tracks;
split selected wave tracks; join a time selection that intersects ≥ 2 clips
of a selected wave track. With `selectAllOnNone` (`/GUI/SelectAllOnNone`)
every command except cut, delete and join first selects all audio (3.7.9
`DoSelectAllAudio`) instead. `edit.paste`: `FAILED` when the clipboard is
empty, when the clipboard has more tracks than the selected tracks (3.7.9
message), and when `editClipsCanMove` is off and there is no room after the
clip at the cursor (3.7.9 "There is not enough room available to paste the
selection", rolled back); with no track selected it pastes into new tracks
at 0 (selected, the first one focused). Undo descriptions are the 3.7.9
strings ("Cut", "Paste", "Delete", "Cut and leave gap", "Split Delete",
"Silence", "Trim Audio", "Duplicate", "Split", "Split New", "Join",
"Detach").

#### tracks (edit module)

| command | args | flags |
|---|---|---|
| `tracks.add` | `{kind:"mono"\|"stereo"\|"label"}` → `{id}` | M |
| `tracks.remove` | `{ids:[..]}` | M. One id: "Removed track '%s'." / "Track Remove" (focus moves to the next track), several: "Removed audio track(s)" / "Remove Track". `NOT_FOUND` for an unknown id, `INVALID_ARGS` for `[]`. |
| `tracks.mixAndRender` | `{toNewTrack:bool}` → `{id}` (the new track) | M, L. Selected wave tracks (`NO_SELECTION` without); `CANCELLED` when the progress is cancelled. |
| `tracks.resample` | `{rate}` (selected wave tracks; 1 … 1000000) | M, L. One consolidated history entry for all tracks. |
| `tracks.setGain` | `{id, gain, final:bool}` (gain 0 … 63.1 = the ±36 dB slider; `final` defaults to true) | I. `final:false` (while dragging): model change only, snapshot throttled ≤ 10 Hz (a trailing snapshot follows), no history entry, no generation bump; `final:true`: `PushState("Moved volume slider", "Volume", CONSOLIDATE)` (M). `NOT_FOUND` for a non-wave track. |
| `tracks.setPan` | `{id, pan, final:bool}` (pan −1 … 1) | I, same as setGain ("Moved pan slider", "Pan"). |
| `tracks.setMute` | `{id, mute}` (sets the value; when it changes, 3.7.9's mute button logic runs: with `soloMode` Simple the solo indicators follow) | U, I |
| `tracks.setSolo` | `{id, solo}` (semantics from the `/GUI/Solo` pref, `soloMode`: Simple = radio buttons that mute the others, Multi = independent; nothing happens when the value does not change) | U, I |
| `tracks.muteAll` | `{mute:bool}` (Tracks ▸ Mute/Unmute ▸ Mute/Unmute All Tracks) | U, I |
| `tracks.rename` | `{id, name}` | M (no history entry when the name is unchanged) |
| `tracks.move` | `{id, to:"up"\|"down"\|"top"\|"bottom"}` | M (no history entry when the track cannot move) |
| `tracks.makeStereo` | `{id}` (with the track directly below; both mono wave tracks, else `INVALID_ARGS`) → `{id}` (the stereo track keeps the upper track's id) | M. When the clips do not match (or realtime effects exist) the engine asks first (blocking Yes/No `dialog`, 3.7.9 text); "No" → `CANCELLED`. |
| `tracks.splitStereo` | `{id}` (stereo, else `INVALID_ARGS`) → `{ids:[left, right]}` (left keeps the id; pans −1/+1) | M |
| `tracks.splitStereoToMono` | `{id}` → `{ids:[left, right]}` (pans unchanged) | M |
| `tracks.swapChannels` | `{id}` (stereo, else `INVALID_ARGS`) | M |
| `tracks.setRate` | `{id, rate}` (no resampling, like the track menu "Rate"; 1 … 1000000) | M |
| `tracks.setFormat` | `{id, format}` | M, L (no history entry when unchanged) |
| `tracks.align` | `{mode:"startToZero"\|"startToCursor"\|"startToSelEnd"\|"endToCursor"\|"endToSelEnd"\|"endToEnd"\|"together", moveSelection?:bool}` (`moveSelection` default = pref `/GUI/MoveSelectionWithTracks`; `endToEnd`/`together` never move the selection, like 3.7.9's `OnAlignNoSync`) | M. Selected audio tracks (`NO_SELECTION` without). |
| `tracks.sort` | `{by:"time"\|"name"}` | M |

#### clips / labels (edit module)

| command | args | flags |
|---|---|---|
| `clips.move` | `{trackId, clipIndex, generation, newStart, toTrackId?}` → `{trackId, clipIndex, start}` (where the clip ended up) | M (time shift; snaps nothing). Port of 3.7.9's clip drag for one clip: the offset is a whole number of samples; in the same track the clip stops at its neighbours (`start` tells where; no history entry when it cannot move); into another wave track with the same channel count (else `INVALID_ARGS`) it fits within a 20 px tolerance at the current zoom or fails with `FAILED`, and is resampled to that track's rate. "Time shifted tracks/clips right/left %.02f seconds" / "Moved clips to another track", "Move Clip". |
| `clips.rename` | `{trackId, clipIndex, generation, name}` | M ("Modified Clip Name" / "Clip Name Edit"; nothing when unchanged) |
| `labels.add` | `{title?:string}` at the selection — at the play position while this project plays or records (3.7.9 *Add Label at Playback Position*) — in the focused label track, else the first selected label track, else a new label track (selected and focused) → `{trackId, index}` | M, I |
| `labels.edit` | `{trackId, index, generation?, title?, t0?, t1?}` → `{index}` (new position: time edits re-sort with `LabelTrack::SortLabels`) | M ("Modified Label" / "Label Edit"; no entry when nothing changes; `t1 < t0` → `INVALID_ARGS`) |
| `labels.remove` | `{trackId, index, generation?}` | M ("Deleted Label" / "Label Edit") |
| `labels.import` | `{path}` (text or SubRip `.srt` file in app storage) → `{trackId}` | M. Port of *File ▸ Import ▸ Labels* (new label track named after the file, the only selected track). `NOT_FOUND` for a missing file; `.vtt`/`.json` → `UNSUPPORTED` (3.7.9's `LabelTrack::Import` cannot read them); unreadable lines are skipped with a non-blocking `dialog`. |
| `labels.export` | `{path, format:"text"\|"subrip"\|"webvtt"\|"podcastChapters"}` → `{path, labels}` (label count) | – Port of *File ▸ Export ▸ Export Labels* (all label tracks; an existing file is replaced; UTF-8). `FAILED` without label tracks or when the file cannot be written. |

Label references with a `generation` older than the current one fail with
`STALE` (indices shift on every add/sort).

#### effects / analyze (effects module)

| command | args | result | flags |
|---|---|---|---|
| `effects.list` | – | `{effects:[EffectInfo], menus:{generate:[MenuSection], effect:[MenuSection], analyze:[MenuSection], tools:[MenuSection]}}` (§5.4) | I |
| `effects.describe` | `{id}` | `EffectDescription` (§5.5) — current parameter values | I |
| `effects.setParams` | `{id, params:{key:value,...}, duration?:seconds, curve?:{points:[{f,dB}], linearFreq}}` | `EffectDescription` (validated; unknown/invalid → `INVALID_ARGS`, nothing changed). `params` may be partial: the engine merges it into the current parameter string (missing keys would otherwise reset to defaults). Keys with spaces may also be sent with `_` (automation-string form). `duration` (0 < d ≤ 86400) only for generators (it becomes the "last used duration"), `curve` (≤ 200 points, f in (0, 10⁶] Hz, dB in [−10⁴, 10⁴], sorted by f) only for the EQ effects; otherwise `INVALID_ARGS`. | I |
| `effects.loadPreset` | `{id, kind:"factory"\|"user"\|"defaults", name?, index?}` | `EffectDescription`. For the EQ effects the built-in curves (`curves` of §5.5) are loaded with `kind:"factory", name`. Amplify `"defaults"` with an audio selection = ratio 1/peak of the selection (3.7.9's dialog), else the stored factory defaults. `NOT_FOUND` for an unknown preset name/index; Noise Reduction has only `"defaults"`. | I |
| `effects.savePreset` | `{id, name}` | `{}` | I. `INVALID_ARGS` for an empty name or one with `/`, `\`, `=` or control characters; an existing preset of that name is replaced. `UNSUPPORTED` for Noise Reduction. |
| `effects.deletePreset` | `{id, name}` | `{}` | I. `NOT_FOUND` when there is no such user preset. |
| `effects.apply` | `{id, params?:{...}, duration?:seconds, curve?}` | `{applied:bool, message?:string}` | M, L. `params`/`curve`/`duration` (if present) are applied with `effects.setParams` semantics first. Generators insert `duration` seconds at the cursor (or, with a time selection and no `duration`, replace the selection); without a selected wave track a new track is created. Process effects and analyzers need a time selection on selected wave tracks (`NO_SELECTION` with 3.7.9's message; with `selectAllOnNone` all audio is selected first). `applied:false` + `message` = the effect refused or found nothing to do (nothing changed, e.g. Click Removal "not effective", Amplify that would clip without "Allow clipping", Auto Duck without a control track or with a selection not longer than both outer fades, Noise Reduction without a captured profile). Analyzers/Nyquist may add label tracks and/or return their result as `message` (e.g. Measure RMS: no history entry then). Cancel through `cancelProgress` → `CANCELLED`, the project is rolled back. Library exceptions → `FAILED`. The Nyquist Prompt runs `Command` like a macro step: the code's `;type` decides the type, its `;control`s are set from the nested `Parameters` string. |
| `effects.preview` | `{id, params?, duration?, curve?}` | `{message?}` | L, not M. Port of 3.7.9's `EffectPreview`, non-blocking: renders ≈ `/AudioIO/EffectsPreviewLen` (6 s) of the selection into temporary tracks (progress "Preparing preview" with Stop → `CANCELLED`), starts playback and returns; `params`… as for `apply`. Same selection rules as `apply` (generators need none). A running preview is replaced; any other stream → `AUDIO_BUSY`. Emits `transport` `{state:"playing", reason:"preview"}` and, when it ends by itself, through `effects.stopPreview`, `transport.stop`, closing the project or `Stop()`, `{state:"stopped", reason:"preview"}`. The project is not changed. `FAILED` when the device cannot be opened (3.7.9 text). |
| `effects.stopPreview` | – | `{}` | I. No-op without a preview. |
| `effects.repeatLast` | – | `{applied, message?}` | M, L. Repeats the last applied **process** effect (Effect ▸ Repeat Last Effect) with its current settings; `NOT_FOUND` when none was applied in this project. Generators/analyzers/tools are repeated with `effects.apply {id}` of `lastGenerator`/`lastAnalyzer`/`lastTool`. |
| `effects.lastApplied` | – | `{id?, name?}` | I. The last successfully applied effect of any type in this project (`{}` when none). |
| `effects.noiseReduction.captureProfile` | – (uses the current selection) | `{message?}` | – step 1 of Noise Reduction ("Get Noise Profile"): tracks unchanged, no history entry, not repeatable. `NO_SELECTION` (3.7.9's Noise Reduction text), `FAILED` (e.g. selection too short). The profile lives in memory until the engine stops (as on desktop). |
| `analyze.spectrum` | `{algorithm?:"spectrum"\|"autocorrelation"\|"cubeRootAutocorrelation"\|"enhancedAutocorrelation"\|"cepstrum" = "spectrum", window?:"rectangular"\|"bartlett"\|"hamming"\|"hann"\|"blackman"\|"blackmanHarris"\|"welch"\|"gaussian25"\|"gaussian35"\|"gaussian45" = "hann", size?:int (power of two 128…131072) = 1024}` | `{rate, binHz (spectrum) or binSeconds (autocorr/cepstrum), values:[float], minValue, maxValue, algorithm, size, warning?}`. `values[i]` is at `i·binHz` Hz (spectrum, dB, size/2 values, clamped to −max(90, `/GUI/EnvdBRange`) dB like the desktop plot) or lag/quefrency `i·binSeconds` (size/2 values); `minValue/maxValue` = the desktop plot's y range. The sum of all channels of the selected wave tracks (same rate required, else `FAILED`) is analysed, capped by the engine at 2²³ samples (the desktop allows 2²⁷ samples = 1.5 GB of buffers); `warning` (3.7.9's text) says when the selection was truncated. `NO_SELECTION` without a time selection on wave tracks, `FAILED` "Not enough data selected." when the selection is shorter than `size`. | L |
| `analyze.contrast` | `{foreground:{t0,t1}, background:{t0,t1}}` | `{foregroundDb, backgroundDb, differenceDb, passes:bool, verdict, foregroundSilent, backgroundSilent}` | – Measures the RMS of the two ranges of the **one** selected wave track (`NO_SELECTION` "Please select an audio track." / "You can only measure one track at a time."; `FAILED` with 3.7.9's message for an empty range). `differenceDb` = fg − bg; `passes` and the translated `verdict` follow `src/effects/Contrast.cpp` (fg and bg ≤ 0 dB, bg ≤ fg, \|difference\| > 20 dB). Digital silence (−∞ dB, which JSON cannot carry) is reported as −1000 dB with `…Silent:true`. |

#### import / export (io module)

| command | args | result | flags |
|---|---|---|---|
| `import.formats` | – | `{groups:[{description, extensions:[..]}], extensions:[..]}` (one group per import plug-in in probing order; `extensions` = all of them, lower case, without `aup3`) | I (no project needed) |
| `import.files` | `{paths:[..], newProject:bool=false}` (absolute real paths in `cacheDir/import/...`, original file names kept: they name the tracks, the undo entry and an empty temporary project) | `{trackIds:[..], messages:[..]}` | M, L. Port of 3.7.9 `ProjectFileManager::Import`/`DoImport`/`AddImportedTracks` (no tempo detection). Files are imported in name order (case-insensitive); each one is one undo entry "Imported '<file name>'" (all its tracks selected, earlier tracks deselected, the last track focused; muted when the project has a soloed track; the project's tags are merged like 3.7.9: PCM/MP3 add tags, FLAC/Ogg/WavPack replace them, undone with the entry). The first import into an empty project also sets the project rate to the rate of the first imported track (Audacity ≤ 3.3 behaviour; 3.7.9 keeps the project rate) and, for a temporary project, its name. A file that fails is skipped: its message (the importers' own messages + 3.7.9's "not recognized" text, file name instead of the staging path) goes to `messages`, and the command fails with that error only when nothing was imported (`FAILED`; `NOT_FOUND` missing file; `INVALID_ARGS` `.aup3`, directories, relative paths, empty `paths`). Multi-stream files (chained Ogg, several audio tracks of an MP4/MKV) ask with a `multiChoice` dialog "Select stream(s) to import" (all checked); cancel or an empty choice = `CANCELLED`. `progress` per file (cancellable, stoppable: Stop keeps the audio decoded so far). `CANCELLED` ends the batch (files before it stay imported unless `newProject`). With `newProject` the files go into a fresh project that replaces the current one (closed without saving, like `project.new`) only when something was imported; otherwise the current project is untouched. Needs a project unless `newProject`. |
| `export.formats` | – | `{formats:[ExportFormat]}` (§5.6), registry order, keys unique | I (no project needed) |
| `export.defaults` | `{formatKey}` | `{hasSelection, defaultChannels, maxChannels, defaultRate, rates:[..]}` | I. Port of `ExportFilePanel`/`ExportAudioDialog`: `hasSelection` = `ExportUtils::HasSelectedAudio` (time selection and selected audible tracks); `defaultChannels` 2 when an exported track is stereo or panned, else 1; `maxChannels` = min(2, format's maxChannels); `rates` = the options session's list, or (format without a list) 8000 … 384000 plus the wanted rate; `defaultRate` = the project's preferred export rate (last successful export), else the highest track rate, else the project rate — taken when offered, else the smallest offered rate above it, else the highest. |
| `export.options` | `{formatKey}` | `ExportOptions` (§5.6) — (re)opens the format's options session from the preferences | I (no project needed). `NOT_FOUND` for an unknown key. |
| `export.setOption` | `{formatKey, id, value:{t,v}}` | `ExportOptions` (full refresh: other options, the rate list and the format's extension may change, e.g. MP3 bit-rate mode, PCM "Other" header) | I (no project needed). `INVALID_ARGS` for an unknown `id`, a malformed value, a tag other than the option's, an enum value that is not a choice, a range value outside `[min, max]`, a `readOnly` option. Stored in the preferences at once (survives restarts). |
| `export.run` | `{path, formatKey, range:"project"\|"selection", channels:int, rate:int, skipSilenceAtStart?:bool}` | `{path, stopped:bool}` | L. `path` is an absolute staging path (`cacheDir/export/<uuid>/<name>.<ext>`, directory existing) that must not exist yet (`INVALID_ARGS`); Kotlin copies the result to the SAF Uri. Uses the format's options session (`export.options` state). `channels` 1 … `maxChannels` of `export.defaults` (mono/stereo mix-down; no custom channel mapping); `rate` must be in the session's rate list (any rate 1000 … 768000 when it has none), else `INVALID_ARGS`. `range:"selection"` = `ExportAudioDialog`'s selection range (`NO_SELECTION` without selected audio); `skipSilenceAtStart` starts at the first exported track's start. `FAILED` "All audio is muted." / "All selected audio is muted.", "There is no audio to export" (no wave tracks / empty range), and the exporters' messages. Progress `"Export"` with the exporter's status; Cancel → `CANCELLED`, Stop → a valid shorter file (`stopped:true`). The output is deleted on every failure and on cancel. Tags = the project's tags (MP3 gets none: `canMetaData:false`, no libid3tag). WavPack's correction file is always off. On success the rate becomes the project's preferred export rate. |

#### transport / audio (audio module)

| command | args | result | flags |
|---|---|---|---|
| `transport.play` | `{loop?:bool=false, t0?, t1?}` | `{started:bool}` (false: nothing to play, e.g. no audio tracks) | No args = Space (`PlayCurrentRegion` with the `DefaultPlaybackPolicy`): loops the play region if it is active, else plays the selection, or cursor → end. The inactive play region follows the selection (3.7.9 ruler), so the snapshot's inactive `playRegion` equals the selection. `loop:true` = if the play region is inactive, set it to the selection (or the whole project for a point selection) and activate it, then play looped (Transport ▸ Looping ▸ Enable + Space). `t0` (and optional `t1`) = Quick-Play: plays `[t0, t1 or project end]` once, never loops, does not change the play region (`t1` without `t0` or `t1 < t0` → `INVALID_ARGS`). `AUDIO_BUSY` while this project plays/records (use `transport.seek`); a monitoring stream is replaced. `FAILED` when the device cannot be opened (3.7.9 text + the AAudio error). |
| `transport.stop` | – | `{}` | I. Stops playback/recording/effect preview/monitoring. Recording is finalised (one undo entry "Recorded Audio"; with dropouts also a "Dropouts" label track in the same entry and a non-blocking warning `dialog`, like 3.7.9). |
| `transport.pause` | – | `{toggled:bool}` | I. Toggles pause of this project's playback/recording (paused recording discards input; the clip continues). Nothing playing → no-op (`toggled:false`). |
| `transport.record` | `{newTrack:bool}` | `{}` | Port of 3.7.9 *OnRecord*: `newTrack:false` = desktop *Record* (R): records into the selected wave tracks (all one rate, else `FAILED` "…same sampling rate"; channel count = `recordChannels`) starting at max(cursor, end of those tracks); if none of the selected fit, into the first fitting wave track(s) of the project at that rate (`ChooseExistingRecordingTracks`), else into new tracks at the cursor; with a time selection that ends after that start, stops at the selection end. `newTrack:true` = *Record New Track* (Shift+R): new track(s) (`recordChannels` 2 = one stereo track) at the selection start, limited to a time selection. `preferNewTrackRecord` swaps the two (3.7.9). With `overdub` the other wave tracks play. `UNSUPPORTED` without microphone permission, `FAILED` when the device cannot be opened. Before each recording the engine writes `/AudioIO/LatencyCorrection` = −(measured duplex offset of the current output/input devices) + `latencyCorrectionMs` when other tracks play, else 0. During recording pending new tracks have synthetic ids ≤ −2 (−(2 + index among them)), tracks recorded into show their growing pending copy, and snapshots follow at ≤ 5 Hz (generation bumped). |
| `transport.seek` | `{t}` | `{}` | I, S. While playing (also paused): jump (applied by the audio callback; readTransport shows `t` at once); while recording: ignored; while stopped: moves the cursor (= `select.set {t, t}`). `t < 0` → 0. |
| `transport.skipToStart` / `transport.skipToEnd` | – | `{}` | S. Stopped: cursor to 0 / to the project end (3.7.9 *Cursor to Project Start/End*, no scrolling). While this project plays (or is paused): moves the play head like `transport.seek` (the fake engine's behaviour); `AUDIO_BUSY` while recording or while another stream (effect preview) runs. |
| `transport.monitor` | `{enabled:bool}` | `{}` | I (needs a project). Input level monitoring without recording (capture meter). `UNSUPPORTED` without microphone permission; no-op while playing/recording; `FAILED` when the input cannot be opened. Not "busy" (edits are allowed); `transport.play/record` replace it. |
| `audio.devices` | – | `{outputs:[AudioDevice], inputs:[AudioDevice], current:{output, input, recordChannels}, pending:bool}` (`pending`: an `audio.setDevices` list waits for the stream to stop) | I |
| `audio.permission` | `{recordPermission:bool}` | `{}` | I. Revoking stops monitoring and a recording (committed; `transport` reason `"device"`). Snapshot flag `RECORD_PERMISSION`. |
| `audio.latency` | – | `{outputLatencyMs, inputLatencyMs, correctionMs, duplexOffsetMs, measured:bool, userTrimMs}` — latencies of the running/last stream (else the device defaults); `correctionMs` = the `/AudioIO/LatencyCorrection` an overdub would use now = −`duplexOffsetMs` + `userTrimMs` (Audacity's sign: negative shifts the recording earlier); `duplexOffsetMs` is measured by the AAudio host API during every recording with playback and stored per output/input device pair (`measured:false` = estimate: another route's measurement or the device latencies) | I |
| `audio.setDevices` | `{devices:[{id, name, type, isSource, isSink, channelCounts:[..], sampleRates:[..]}]}` | `{applied:bool}` | I (applied now when no stream is open, else when it stops: `PaAAudio_SetDeviceList` + `Pa_Terminate/Pa_Initialize` + `HandleDeviceChange`). Kotlin passes `AudioManager.getDevices()`; native code cannot enumerate Android devices. Without it only "Default Output/Input" exist. Device names are `"<type label>: <name>"` (`type` = `AudioDeviceInfo.TYPE_*`; a string `type` is used as the label), made unique with `" (id N)"`; telephony/SCO/earpiece/HDMI-ARC/tuner/internal types are not offered. `channelCounts` empty = 2, `sampleRates` pick the native rate. |
| `audio.debugSamples` | `{trackId, channel?=0, t0, t1}` | `{rate, t0, values:[float]}` (≤ 2 000 000 samples) | Tests/diagnostics only (not for production UI). |

#### display (display module, besides the binary JNI calls)

| command | args | flags |
|---|---|---|
| `display.setViewportWidth` | `{px}` (1 … 65536: the widest track view in pixels; sizes the per-clip column caches) → `{}` | I |
| `display.trimCaches` | `{budgetBytes}` (≥ 0) → `{bytes}`: frees the least recently used display caches (waveform columns, spectrogram elements) until their estimated size is ≤ `budgetBytes` (0 frees all; e.g. from `onTrimMemory`); `bytes` = the estimate afterwards. Independently the engine keeps the caches under 32 MiB after every display call. | I |

## 4. Events (native → Kotlin)

`EngineListener.onEvent(type, payloadJson)`, always from the engine thread.

### 4.1 `engine.ready` / `engine.failed`

```json
{ "audacityVersion": "3.7.9", "selfChecks": [{"name":"sampleBlockFactory","ok":true}, ...] }
{ "message": "why bootstrap failed" }
```

After `engine.ready` the engine has an empty project open (or none, if Kotlin
should first offer recovery: the ready payload has `"recoverable": n`; when
`n > 0` no project is opened automatically; or none when it could not be
created: self-check `initialProject` failed, `project.new` reports why).
Android ends an app by killing its process, so the previous launch's empty
project and the projects it had open are always left behind: only those with
something to recover count (see `project.recoverable`). A `snapshot` event follows
`engine.ready`. `selfChecks` names: `sampleBlockFactory`, `importers`,
`exporters`, `effects`, `nyquistRuntime`, `tempDir`, `configDir`,
`projectAttachments` (and `initialProject` when the empty project could not be
created); each has `ok` and an optional `message`. They are diagnostics: the
engine is usable even when `effects`/`nyquistRuntime` fail.

### 4.2 `snapshot`

Emitted after every command that changed the model, after undo/redo, and
periodically (≤ 5 Hz) while recording or importing changes tracks.

```json
{
  "generation": 42,
  "project": { "open": true, "name": "Untitled", "path": null, "temporary": true, "dirty": true,
               "rate": 44100, "defaultFormat": "float" },
  "tracks": [
    { "id": 3, "kind": "wave", "name": "Audio 1", "selected": true, "focused": true,
      "channels": 2, "rate": 44100, "format": "float",
      "gain": 1.0, "pan": 0.0, "mute": false, "solo": false,
      "start": 0.0, "end": 12.5, "waveVersion": 9182736455,
      "clips": [ { "index": 0, "name": "Audio 1 #1", "start": 0.0, "end": 12.5,
                   "trimLeft": 0.0, "trimRight": 0.0, "stretchRatio": 1.0, "rate": 44100 } ],
      "labels": [] },
    { "id": 7, "kind": "label", "name": "Label 1", "selected": false, "focused": false,
      "labels": [ { "index": 0, "t0": 1.0, "t1": 2.5, "title": "Intro" } ] }
  ],
  "selection": { "t0": 1.0, "t1": 4.0 },
  "playRegion": { "active": false, "t0": 0.0, "t1": 0.0 },
  "history": { "canUndo": true, "canRedo": false, "undo": "Cut", "redo": "" },
  "view": { "zoom": 86.1328125, "hpos": 0.0 },
  "audio": { "busy": false },
  "clipboard": { "empty": false, "duration": 3.0 },
  "lastEffect": { "id": "Effect_Audacity_Audacity_Amplify_...", "name": "Amplify" },
  "lastGenerator": null, "lastAnalyzer": null, "lastTool": null,
  "flags": 1234567
}
```

`flags` is the menu-enable bitset (port of 3.7.9 `CommonCommandFlags.cpp`;
a menu item is enabled when `(required & ~flags) == 0`). Bit positions are ABI
— never reorder, only append:

| bit | name | predicate (3.7.9) |
|---|---|---|
| 0 | `NB` AudioIONotBusy | no active stream for this project |
| 1 | `BUSY` AudioIOBusy | negation of NB |
| 2 | `TS` TimeSelected | `!selectedRegion.isPoint()` |
| 3 | `WS` WaveTracksSelected | a selected WaveTrack exists |
| 4 | `TE` TracksExist | any track |
| 5 | `ES` EditableTracksSelected | selected tracks that support basic editing |
| 6 | `AS` AnyTracksSelected | any selected track |
| 7 | `CNB` CaptureNotBusy | not recording |
| 8 | `LE` LabelTracksExist | |
| 9 | `UA` UndoAvailable | |
| 10 | `RA` RedoAvailable | |
| 11 | `ZI` ZoomInAvailable | zoom < 6e6 and TE |
| 12 | `ZO` ZoomOutAvailable | zoom > 0.001 and TE |
| 13 | `WE` WaveTracksExist | |
| 14 | `SL` IsSyncLocked | |
| 15 | `NSL` IsNotSyncLocked | |
| 16 | `ST` StereoRequired | a selected WaveTrack with 2 channels |
| 17 | `PAUSED` | `AudioIOBase::IsPaused()` |
| 18 | `CS` CanStopAudioStream | always 1 (single project) |
| 19 | `CC` CutCopyAvailable | (TS and ES) or label text selected |
| 20 | `JC` JoinClipsAvailable | NB, TS and a selected WaveTrack has > 1 clip in the selection |
| 21 | `LS` LabelsSelected | a selected label track has a label inside the selection |
| 22 | `HW` HasWaveData | a WaveTrack with end > start |
| 23 | `LAST_EFF` HasLastEffect | |
| 24 | `LAST_GEN` HasLastGenerator | |
| 25 | `LAST_ANA` HasLastAnalyzer | |
| 26 | `LAST_TOOL` HasLastTool | |
| 27 | `TFOCUS` HasTrackFocus | |
| 28 | `CLIPSEL` SomeClipIsSelected | (reserved: 0 in v1) |
| 29 | `STRETCHSEL` StretchedClipIsSelected | (reserved: 0 in v1) |
| 30 | `PLAYABLE` PlayableTracksExist | |
| 31 | `NO_TIMETRACK` TimeTrackDoesNotExist | |
| 32 | `FOC` TrackPanelHasFocus | always 1 |
| 33 | `LABEL_TEXT_SEL` | (reserved: 0 in v1) |
| 34 | `PROJECT_OPEN` | a project is open (Android addition) |
| 35 | `CLIPBOARD` | clipboard not empty (Android addition) |
| 36 | `RECORD_PERMISSION` | microphone permission granted (Android addition) |

* `kind`: `"wave"`, `"label"`, `"time"`, `"note"`, `"other"`. Only `channels`..`clips`
  are present for wave tracks and `labels` for label tracks.
* `waveVersion` changes whenever the sample data or clip geometry of the track
  changes (Kotlin invalidates its waveform tiles when it changes).

### 4.3 `progress`

```json
{ "id": 5, "phase": "begin", "title": "Applying Amplify...", "message": "", "fraction": 0.0,
  "cancellable": true, "stoppable": false }
{ "id": 5, "phase": "update", "fraction": 0.37, "message": "..." }      // ≤ 20 Hz
{ "id": 5, "phase": "end" }
```

`fraction < 0` = indeterminate. Kotlin shows a modal progress dialog with
Cancel (and Stop when `stoppable`) calling `NativeBridge.cancelProgress`.

### 4.4 `dialog`

```json
{ "id": 9, "kind": "message", "style": "info"|"warning"|"error"|"question",
  "title": "Audacity", "message": "text", "buttons": ["OK"], "defaultButton": 0,
  "blocking": false, "helpPage": "" }
```

* `blocking:false` — informational; Kotlin shows it and does not reply.
* `blocking:true` — the engine thread waits; Kotlin must call
  `replyDialog(id, buttonIndex)` exactly once (index into `buttons`; `-1` =
  dismissed/cancel). `kind:"choice"` dialogs (BasicUI multi-dialog) carry
  `choices:[..]` and the reply is the chosen index.
* `kind:"multiChoice"` (e.g. choosing the streams of a multi-stream file on
  import) carries `choices:[..]` and `defaultChecked:[bool]` (same length)
  and `buttons:["OK","Cancel"]` (translated); Kotlin answers
  with `replyDialogChoices(id, indices)` or cancels with `replyDialog(id, -1)`.
  The engine sorts the indices and drops duplicates and out-of-range values;
  an empty array is a valid answer ("none"). `replyDialog(id, b ≥ 0)` accepts
  the `defaultChecked` choices.

### 4.5 `transport`

```json
{ "state": "stopped"|"playing"|"recording"|"paused"|"monitoring", "reason": "user"|"end"|"error"|"device",
  "message": "optional", "dropouts": 0 }
```

Sent on every state change. The play head is not sent; read it with
`readTransport` (§6.4).

### 4.6 `log`

`{ "level": "debug"|"info"|"warning"|"error", "message": "..." }` — engine log
lines (wxLog), for a debug screen. Rate limited.

## 5. Payload schemas

### 5.1 Settings

```json
{
  "defaultRate": 44100,               // /SamplingRate/DefaultProjectSampleRate
  "defaultFormat": "float",           // /SamplingRate/DefaultProjectSampleFormatChoice
  "recordChannels": 1,                // /AudioIO/RecordChannels (1 or 2)
  "outputDevice": "Default Output",   // device name, /AudioIO/PlaybackDevice
  "inputDevice": "Default Input",     // /AudioIO/RecordingDevice
  "latencyMs": 100,                   // /AudioIO/LatencyDuration (buffer)
  "latencyCorrectionMs": 0,           // user trim added to the measured duplex offset
                                      // (/Android/AAudio/UserLatencyTrimMs); the engine writes
                                      // /AudioIO/LatencyCorrection itself before each recording
  "overdub": true,                    // /AudioIO/Duplex (play other tracks while recording)
  "swPlaythrough": false,             // /AudioIO/SWPlaythrough
  "preRollSec": 5.0, "crossfadeMs": 10.0,
  "realtimeDither": "none", "hqDither": "shaped",   // "none"|"rectangle"|"triangle"|"shaped"
                                      // (/Quality/...; InitDitherers() is re-run after a change)
  "effectsGroupBy": "default",        // /Effects/GroupBy
  "soloMode": "Simple",               // /GUI/Solo: "Simple" | "Multi" (3.7.9 default Multi; the engine writes Simple on first run)
  "editClipsCanMove": true,           // /GUI/EditClipCanMove
  "selectAllOnNone": false,           // /GUI/SelectAllOnNone
  "syncLock": false,                  // /GUI/SyncLockTracks
  "pasteAsNewClips": false,           // /GUI/PasteAsNewClips
  "moveSelectionWithTracks": false,   // /GUI/MoveSelectionWithTracks (desktop default false)
  "preferNewTrackRecord": false,      // /GUI/PreferNewTrackRecord (R behaves like Shift+R)
  "dropoutDetection": true,           // /Warnings/DropoutDetected
  "language": "system"                // /Android/Language: "system" | "en" | "ko" (or another code of
                                      // app.info.languages); language of the engine's strings
                                      // (effect names, history labels, messages); "system" = the
                                      // start config's locale ("ko_KR" -> "ko"), English when no
                                      // catalog matches. Applied immediately (snapshot re-emitted).
}
```

`settings.set` validates every key first and writes nothing when one is
invalid (`INVALID_ARGS`). `syncLock` also switches the open project
(snapshot flags `SL`/`NSL`).

### 5.2 ProjectInfo

`{open, name, path|null, temporary, dirty, rate, defaultFormat, durationSec, tracks:int}`

### 5.3 AudioDevice

`{index, name, hostApi:"AAudio", maxInputChannels, maxOutputChannels, defaultRate, isDefault}`

### 5.4 EffectInfo / MenuSection

```json
{ "id": "Effect_Audacity_Audacity_Amplify_Built-in Effect: Amplify",
  "name": "Amplify", "type": "process"|"generate"|"analyze"|"tool",
  "family": "Audacity"|"Nyquist", "vendor": "Audacity", "description": "...",
  "interactive": true, "realtime": false, "isDefault": true, "special": null }
```

`MenuSection = { "title": "Volume and Compression" | null, "ids": [effect ids in display order] }`
— the port of `MenuHelper::PopulateEffectsMenu` for the `effectsGroupBy`
setting (default: the `EffectsMenuDefaults.xml` sections, then the rest
grouped by publisher; Generate/Analyze/Tools: the bundled effects sorted by
name, then the rest by publisher). A titled section is a desktop submenu;
`title:null` = items directly in the menu (Kotlin separates consecutive
sections). Titles of the `EffectsMenuDefaults.xml` groups are their English
msgids (Kotlin translates them); publisher/type titles are names. Every
listed effect appears exactly once in the menus of its type. `name` and
`description` are translated (engine language); `id` is the desktop
PluginID (Nyquist ids contain the `.ny` path). Hidden effects (Stereo To
Mono) are not listed. Bundled = built-in, Nyquist Prompt, or a `.ny` below
`pluginsDir`/`nyquistDir`.

### 5.5 EffectDescription

```json
{ "id": "...", "name": "Amplify", "type": "process",
  "params": [
    { "key": "Ratio", "label": "Amplification", "kind": "double",
      "min": 0.003162, "max": 316.227766, "default": 0.9, "scale": 1.0, "value": 1.25,
      "unit": "", "display": "dB" },
    { "key": "AllowClipping", "label": "Allow clipping", "kind": "bool", "default": false, "value": false },
    { "key": "Type", "label": "Type", "kind": "enum", "choices": ["Sine","Square",...],
      "choiceLabels": ["Sine","Square",...], "default": 0, "value": 0 },
    { "key": "Text", "label": "Text", "kind": "string", "default": "", "value": "" }
  ],
  "presets": { "factory": ["..."], "user": ["..."] },
  "supportsDuration": false, "duration": 30.0,
  "special": null,
  "nyquist": null,
  "help": "Amplify"
}
```

* `kind`: `"bool"`, `"int"`, `"double"`, `"enum"`, `"string"`. For enums,
  `value`/`default` are choice **indices**; `choices` are the internal names
  (what automation strings use) and `choiceLabels` the translated labels.
  `effects.setParams` accepts for each kind: bool → JSON bool, int → integer,
  double → number, enum → index *or* internal name string, string → string.
* `label`, `unit`, `display` come from a per-effect table in the bridge (the
  libraries do not carry labels); fall back to `key`. `display:"dB"` means the
  UI should show/edit `20·log10(value)` (e.g. Amplify ratio).
* `special` values and their extra fields:
  * `"noiseReduction"` — two-step effect; `params` are its preference-backed
    settings (Noise reduction dB, Sensitivity, Frequency smoothing bands,
    Noise: Reduce/Residue); `profileCaptured: bool`.
  * `"equalization"` (Filter Curve EQ) / `"graphicEq"` — `params` holds the
    scalar parameters; extra `curve: {points:[{f, dB}], linearFreq:bool}` and
    `curves:[names]` (built-in curves); `effects.setParams` accepts `curve`.
  * `"autoDuck"` — needs a control track below the selected tracks.
* `nyquist` (Nyquist plug-ins): `{controls:[{key,label,kind,type,...}]}`
  richer metadata in `.ny` order (labels from the `.ny` header, translated
  when the file was loaded): `type` is the Nyquist control type (`"int"`,
  `"float"`, `"int-text"`, `"float-text"`, `"time"`, `"choice"`,
  `"string"`, `"file"`, `"text"`), plus `ticks` (slider steps) and
  `fileTypes:[{description, extensions}]`; `"text"` rows are static text
  (`label` only) and are not in `params`, which mirrors the other controls.
  The Nyquist Prompt has `nyquist:{controls:[], prompt:true}` and the string
  params `Command` (the code) and `Parameters` (nested automation string
  for the code's `;control`s).
* Amplify adds `peak`: the linear peak of the selected audio (null without
  an audio selection); the desktop dialog shows "New Peak Amplitude" =
  ratio·peak and refuses to apply a clipping ratio unless `AllowClipping`.
* `min`/`max` are left out when the library's bound means "unbounded"
  (FLT_MAX, INT_MAX, Nyquist `nil`); numbers declared as float literals are
  reported in their short form (0.003162).
* Generators: `supportsDuration:true` (built-in generators; Nyquist
  generators set their own length) and `duration` = the selection length
  with a time selection, else the last used duration (default 30 s).

### 5.6 ExportFormat / ExportOptions

```json
{ "key": "MP3 Files", "description": "MP3 Files", "extensions": ["mp3"],
  "maxChannels": 2, "canMetaData": false }
```

```json
{ "format": ExportFormat,
  "sampleRates": [8000, 11025, ...],      // [] = any rate
  "options": [
    { "id": 0, "title": "Bit Rate Mode", "type": "enum"|"range"|"bool"|"int"|"double"|"string",
      "readOnly": false, "hidden": false,
      "value": {"t":"s","v":"SET"},
      "values": [{"t":"s","v":"SET"}, ...],    // enum: choices; range: [min, max]
      "names": ["Preset", ...] } ] }
```

Value tags: `"i"` int, `"d"` double, `"b"` bool, `"s"` string. Editors reject
type mismatches, so always send back the tag you received.

`key` is the description msgid (stable, English); `description` its
translation. Option `id`s are not 0…n−1 (PCM uses libsndfile major types:
WAV "Encoding" is `0x10000`, "Other uncompressed files" has the "Header" `0`
plus one "Encoding" per header, only the selected header's visible). Hidden
options are still exported with their values. WavPack's "Create
Correction(.wvc) File" (`id` 3) is reported `hidden`, `readOnly`, `false`.

## 6. Realtime data

### 6.4 `readTransport(out: DoubleArray)` layout

| index | value |
|---|---|
| 0 | state: 0 stopped, 1 playing, 2 recording, 3 paused (play), 4 paused (record), 5 monitoring, 6 stopping |
| 1 | stream time (track time of the last frame given to the device), NaN if none |
| 2 | display time (latency compensated play/record head) |
| 3 | sampledAt: `CLOCK_MONOTONIC` nanoseconds when 1–2 were sampled (compare with `System.nanoTime()`) |
| 4, 5 | loop t0, loop t1 (valid when 6 = 1) |
| 6 | looping (0/1) |
| 7 | device sample rate |
| 8, 9 | output latency, input latency (seconds) |
| 10 | capturing (0/1; 0 during pre-roll) |
| 11 | playback speed (1.0) |
| 12 | recording start time (when recording) |
| 13 | generation of the model being recorded into |
| 14, 15 | reserved (0) |

Kotlin extrapolates `display time + (now − sampledAt)/1e9` while playing or
recording (not paused), clamped to the loop end / play end.

Engine side (audio module): published by the engine tick (~20 Hz) and after
every transport command. Display time = stream time − the measured output
latency while output plays (never left of where play started or a seek
landed, never backwards except at a loop wrap; frozen while paused), = stream
time for recording without playback. Stream/display time are NaN for a
stream this project did not start through the transport (effect preview,
state 1) and for monitoring. 4–6 are 0 unless looping; 7 is 0 when stopped;
12 is 0 unless recording.

### 6.5 `readMeters(out: FloatArray)` layout

`readMeters` resets the accumulators, so there must be exactly **one**
consumer per process (the editor's shared meter reader).


`[playPeakL, playPeakR, playRmsL, playRmsR, playClipL, playClipR,
  recPeakL, recPeakR, recRmsL, recRmsR, recClipL, recClipR,
  playChannels, recChannels]` — linear amplitudes since the previous call
(peaks are maxima, RMS over the interval; clip flags 0/1 are sticky until
`transport.play`/`transport.record`/`transport.monitor` starts; a clip is 3
consecutive samples at full scale, like 3.7.9's meter). The playback meter
sees the post-volume output (2 channels), the capture meter the input
(`recordChannels`). `playChannels`/`recChannels` are 0 while that side has no
stream. Ballistics (decay, peak hold) are done in Kotlin.

### 6.6 Microphone permission

Kotlin requests `RECORD_AUDIO` before `transport.record` / `transport.monitor`
and informs the engine with `audio.permission`.

## 7. Display data

### 7.1 Coordinates

* Zoom levels: `pps(level) = 2^(level / 8.0)` pixels per second,
  level ∈ [-160, 160]. Kotlin always sends integer levels; native computes
  `pps` with exactly this expression (cache keys compare doubles).
  `ViewInfo.zoom` (snapshot `view.zoom`) keeps the exact zoom; Kotlin draws the
  level just below it scaled horizontally by `zoom / pps(level)` ∈ [1, 1.09).
* Absolute columns: column `c` covers time `[c/pps, (c+1)/pps)`. Kotlin asks
  for 256-column tiles (`firstColumn` multiple of 256, `count` = 256) and
  translates by `−hpos·pps` when drawing. Any `firstColumn` and
  `1 ≤ count ≤ 65536` work; a column has the same value whatever tiles were
  requested before (adjacent tiles join seamlessly).
* A clip occupies the columns `[floor(0.5 + pps·start), max(first + 1,
  floor(0.5 + pps·(end − 0.99·stretchRatio/rate))))` (snapshot `clips[]`;
  port of `ClipParameters::GetClipRect`). Its samples are cached on the clip's
  own sequence-local grid, placed at `floor(0.5 + pps·sequenceStart)`: like
  3.7.9, the waveform may be drawn up to ½ column early or late.
* Column vs sample mode is decided **per clip**: a clip is drawn from columns
  while `pps ≤ 0.5 · clip.rate / clip.stretchRatio` (snapshot `clips[]`,
  `WaveformView.cpp:860`), otherwise from individual samples (§7.4) for that
  clip's visible range.
* Track ids: the snapshot's `id`. While recording into a NEW track the
  library keeps the track in the list with the unassigned id (−1); the
  snapshot and the display calls name it with the synthetic id `−(2 + k)`,
  k = its position among those pending new tracks in track order (the order
  of `tracks[]`), stable until the recording is committed (then it gets a
  real id). Display calls for a real id draw the track's pending recording
  copy while recording into it (`PendingTracks::SubstitutePendingChangedTrack`).
  A synthetic id with no such track, `−1`, or a non-wave track → `-1`.
  (3.7.9 gives a new *stereo* recording track a real id at once; it is drawn
  as a recording target too, like every track with uncommitted samples.)
  (Native: `display/DisplayTracks.h`.)

### 7.2 `waveColumns(trackId, channel, zoomLevel, firstColumn, count, out)`

* `out` has size ≥ `3·count`: `[min_0..min_{count-1}, max_0.., rms_0..]`,
  linear, **before** the clip envelope (multiply with §7.3 when drawing).
  `NaN` where there is no audio (gaps between clips, outside the track).
  Values are 3.7.9's `WaveDataCache` columns: extremes and RMS over the
  column's samples (at ≥ 256 samples per column over whole 256 / 65536-sample
  summary frames), adjacent columns extended to touch (filled bars).
* Returns the track's `waveVersion` (≥ 0, the snapshot value; for the
  pending copy while recording, its own) on success, or a negative status:
  `-1` no such track/channel or invalid arguments (level outside
  [−160, 160], `count` ∉ [1, 65536], `|firstColumn|` > 2^60, `out` too
  small), `-2` partial data
  (recording tail, re-request soon; data in `out` is valid where not NaN —
  returned as `-(2)` only when nothing could be filled), `-3` engine not
  ready (or busy for > 250 ms), `-4` only when **every** clip intersecting
  the range needs sample mode. Columns of sample-mode clips are `NaN`; a
  range with no clip at all returns the version (all `NaN`), not `-4`.
  `waveVersion` is always in `[0, 2^62)`; bit 62 of a non-negative return
  is set when the tile is partial: while recording, the tile holds the
  growing (rightmost) clip's uncommitted tail or reaches past its current
  end. Kotlin re-requests it on the next poll.

### 7.3 `envelopeColumns(trackId, zoomLevel, firstColumn, count, out)`

Gain of the clip envelope at the centre of each column (`out` size ≥ count),
`NaN` outside clips (same clip columns as §7.2, for sample-mode clips too).
Same return convention as §7.2 (never `-4`).

### 7.4 `waveSamples(trackId, channel, t0, t1)`

Returns `null` on error, else little-endian binary:
`int32 runCount; runCount × { int32 clipIndex; float64 firstSampleTime; float64 samplePeriod; int32 n; float32 values[n]; float32 envelope[n] }`.
One run per clip intersecting `[t0, t1]` (`clipIndex` as in `clips[]`):
the clip's samples `floor((t0 − start)·r) … ceil((t1 − start)·r)` (play
relative, r = rate / stretchRatio, clamped to the clip), raw values and the
envelope gain at each sample time; `samplePeriod` = 1/r. While recording the
uncommitted tail is included. Errors (`null`): unknown track/channel,
`t1 < t0`, non-finite times, or more than 2^20 samples in total (not a
sample-mode range). No clip in the range = zero runs.

### 7.5 `spectrogramColumns(trackId, channel, zoomLevel, firstColumn, count, rows, out)`

`out` size ≥ `count·rows`, column-major (`out[c·rows + r]`, r = 0 lowest
frequency), unsigned 8-bit normalised magnitude using Audacity's spectrogram
settings (defaults: window 2048 Hann, zero padding 2, range 80 dB, gain
20 dB; STFT) with a **linear** frequency scale 0 … rate/2 (row r covers
`[r, r+1)·rate/(2·rows)`; a v1 simplification of 3.7.9's Mel default; the
editor draws a linear axis): `255·clamp((dB + gain + range)/range, 0, 1)`
of the strongest FFT bin of the row (3.7.9 `findValue`), so 0 = ≤ −100 dB and
255 = ≥ −20 dB with the defaults (a full-scale sine is about −6 dB). The
window is centred one sample after the column's left edge
(`fillWhere` with its half-sample bias) and zero-padded outside the clip's
audio. Columns outside clips are 0. `1 ≤ rows ≤ 4096`. Colour mapping is
done in Kotlin. Returns as §7.2 (`-5` = spectrogram unsupported, not used in
v1; never `-4`). While recording only committed audio is analysed (the tail
is partial).

## 8. Threading rules summary (native)

* Command handlers run on the engine thread. They may block it for long
  operations (effects, export, import) — progress and dialogs are delivered as
  events and cancel requests arrive through atomics.
* Display functions run on the engine thread in a separate low-priority queue
  that is drained only by the outermost engine loop (never inside a nested
  `Yield`/modal wait). A display call waits at most 250 ms for the engine to
  *start* serving it (e.g. while a long export runs); otherwise it returns
  `-3` (`NOT_READY`) / `null` and Kotlin retries later. Once started it runs
  to completion.
* `readTransport` / `readMeters` never take locks and never touch library
  objects.
