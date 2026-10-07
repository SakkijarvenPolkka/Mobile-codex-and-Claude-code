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
Native: `native/jni/` (Android only). All strings cross JNI as **UTF-8
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

Kotlin implementation notes (`engine/`, binding for the JNI glue):

* `EngineListener` is a Kotlin `fun interface` in package
  `io.github.sakkijarvenpolkka.audacity.engine`; JNI calls
  `onEvent(Ljava/lang/String;[B)V` on the object passed to `start`.
* The `external` functions are plain members of `object NativeBridge` (no
  `@JvmStatic`): the JNI functions
  `Java_io_github_sakkijarvenpolkka_audacity_engine_NativeBridge_<name>` receive
  the object instance as second argument (`jobject thiz`), not a `jclass`.
* `System.loadLibrary("audacity-bridge")` is attempted once, guarded
  (`NativeBridge.isLoaded`); without the library the app falls back to the
  in-memory `FakeAudacityEngine`.
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

Kotlin extracts `assets/audacity/{nyquist,plug-ins}` to `filesDir/audacity/`
before calling `start` (re-extract when the app version changes; replace only
`nyquist/` and `plug-ins/`, never the whole `filesDir/audacity`, which also
holds the preferences). `nyquistDir`/`pluginsDir` are optional (defaults
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
{ "ok": false, "error": { "code": "AUDIO_BUSY", "message": "human readable (English)" }, "generation": 42 }
```

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
| `app.info` | – | `{audacityVersion:"3.7.9", engineVersion, wxVersion, sqliteVersion, abi, libraries:[...], importers:[...], exporters:[...], effectsCount, nyquist:bool}` | I |
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
| `project.recoverable` | – | `{projects:[{path,name,modifiedMs,sizeBytes}]}` | I. Autosaved projects left by a previous process. |
| `project.recover` | `{path}` | `{}` | L Opens the recovered project (it stays temporary/dirty: `dirty` is true for a recovered unsaved project until it is saved). |
| `project.discardRecoverable` | `{paths:[..]}` | `{}` | – |
| `project.tags.get` | – | `{tags:[{name,value}]}` | I |
| `project.tags.set` | `{tags:[{name,value}]}` | `{}` | M |
| `project.list` | – | `{projects:[{path,name,modifiedMs,sizeBytes}]}` in `filesDir/Projects` (safety backups `*~.aup3` hidden; `sizeBytes` includes the `-wal` file) | I |
| `project.delete` | `{path}` | `{}` | – (not the open one; also deletes `-wal`/`-shm`) |
| `project.rename` | `{path, newName}` | `{path}` (new path) | – Renames `<name>.aup3` (+ `-wal`/`-shm`) in `filesDir/Projects`; `FAILED` for the open project or when the target exists. |
| `project.compact` | – | `{freedBytes}` | M, L. Port of `ProjectFileManager::Compact`: discards undo history and vacuums the database (desktop *File ▸ Compact Project*). |
| `project.compactInfo` | – | `{totalBytes, usedBytes, fileBytes, freeBytes}` | I |

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
| `debug.ask` | `{message?, title?, cancel?:bool, choices?:[..]}` | `{result:"yes"\|"no"\|"cancel"\|"none"}` or `{choice}` | I. Asks through BasicUI (blocking `dialog`). |
| `debug.progress` | `{seconds?=1}` | `{stopped:bool}` | I, L. Runs a BasicUI progress; `CANCELLED` when cancelled. |

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

#### edit (edit module) — all **M**, no args, results `{}`

`edit.cut`, `edit.copy` (**S**: no undo entry, no generation bump; the clipboard is in the snapshot), `edit.paste`, `edit.delete`,
`edit.splitCut`, `edit.splitDelete`, `edit.silence`, `edit.trim`,
`edit.duplicate`, `edit.split`, `edit.splitNew`, `edit.join`,
`edit.detachAtSilences`.

`edit.clipboardInfo` → `{empty, t0, t1, trackCount}` (I).

#### tracks (edit module)

| command | args | flags |
|---|---|---|
| `tracks.add` | `{kind:"mono"\|"stereo"\|"label"}` → `{id}` | M |
| `tracks.remove` | `{ids:[..]}` | M |
| `tracks.mixAndRender` | `{toNewTrack:bool}` | M, L |
| `tracks.resample` | `{rate}` (selected wave tracks) | M, L |
| `tracks.setGain` | `{id, gain, final:bool}` | I. `final:false` (while dragging): model change only, snapshot throttled ≤ 10 Hz, no history entry; `final:true`: `PushState("Moved volume slider", "Volume", CONSOLIDATE)` (M). |
| `tracks.setPan` | `{id, pan, final:bool}` | I, same as setGain ("Moved pan slider", "Pan"). |
| `tracks.setMute` | `{id, mute}` | U, I |
| `tracks.setSolo` | `{id, solo}` (semantics from the `/GUI/Solo` pref, `soloMode`) | U, I |
| `tracks.muteAll` | `{mute:bool}` (Tracks ▸ Mute/Unmute ▸ Mute/Unmute All Tracks) | U, I |
| `tracks.rename` | `{id, name}` | M |
| `tracks.move` | `{id, to:"up"\|"down"\|"top"\|"bottom"}` | M |
| `tracks.makeStereo` | `{id}` (with the track below) | M |
| `tracks.splitStereo` | `{id}` | M |
| `tracks.splitStereoToMono` | `{id}` | M |
| `tracks.swapChannels` | `{id}` | M |
| `tracks.setRate` | `{id, rate}` (no resampling, like the track menu "Rate") | M |
| `tracks.setFormat` | `{id, format}` | M, L |
| `tracks.align` | `{mode:"startToZero"\|"startToCursor"\|"startToSelEnd"\|"endToCursor"\|"endToSelEnd"\|"endToEnd"\|"together", moveSelection?:bool}` (`moveSelection` default = pref `/GUI/MoveSelectionWithTracks`) | M |
| `tracks.sort` | `{by:"time"\|"name"}` | M |

#### clips / labels (edit module)

| command | args | flags |
|---|---|---|
| `clips.move` | `{trackId, clipIndex, generation, newStart, toTrackId?}` | M (time shift; snaps nothing) |
| `clips.rename` | `{trackId, clipIndex, generation, name}` | M |
| `labels.add` | `{title?:string}` at the selection (first selected label track, or a new label track) → `{trackId, index}` | M, I |
| `labels.edit` | `{trackId, index, generation?, title?, t0?, t1?}` → `{index}` (new position: time edits re-sort with `LabelTrack::SortLabels`) | M |
| `labels.remove` | `{trackId, index, generation?}` | M |
| `labels.import` | `{path}` (text/SRT/WebVTT file in app storage) → `{trackId}` | M. Port of *File ▸ Import ▸ Labels* (new label track named after the file). |
| `labels.export` | `{path, format:"text"\|"subrip"\|"webvtt"}` | – Port of *File ▸ Export ▸ Export Labels* (all label tracks). |

Label references with a `generation` older than the current one fail with
`STALE` (indices shift on every add/sort).

#### effects / analyze (effects module)

| command | args | result | flags |
|---|---|---|---|
| `effects.list` | – | `{effects:[EffectInfo], menus:{generate:[MenuSection], effect:[MenuSection], analyze:[MenuSection], tools:[MenuSection]}}` (§5.4) | I |
| `effects.describe` | `{id}` | `EffectDescription` (§5.5) — current parameter values | I |
| `effects.setParams` | `{id, params:{key:value,...}, duration?:seconds, curve?:{points:[{f,dB}], linearFreq}}` | `EffectDescription` (validated; unknown/invalid → `INVALID_ARGS`, nothing changed). `params` may be partial: the engine merges it into the current parameter string (missing keys would otherwise reset to defaults). `curve` only for the EQ effects. | I |
| `effects.loadPreset` | `{id, kind:"factory"\|"user"\|"defaults", name?, index?}` | `EffectDescription`. For the EQ effects the built-in curves (`curves` of §5.5) are loaded with `kind:"factory", name`. | I |
| `effects.savePreset` | `{id, name}` | `{}` | I |
| `effects.deletePreset` | `{id, name}` | `{}` | I |
| `effects.apply` | `{id, params?:{...}, duration?:seconds, curve?}` | `{applied:bool, message?:string}` | M, L. `params`/`curve` (if present) are applied with `effects.setParams` semantics first. Generators with a point selection insert `duration` seconds at the cursor. Analyzers may add label tracks and/or return `message`. |
| `effects.preview` | `{id, params?, duration?}` | `{}` | starts asynchronous preview playback (≈6 s, Audacity's `/AudioIO/EffectsPreviewLen`); ends with a `transport` event |
| `effects.stopPreview` | – | `{}` | I |
| `effects.repeatLast` | – | `{applied, message?}` | M, L |
| `effects.lastApplied` | – | `{id?, name?}` | I |
| `effects.noiseReduction.captureProfile` | – (uses the current selection) | `{}` | – step 1 of Noise Reduction |
| `analyze.spectrum` | `{algorithm:"spectrum"\|"autocorrelation"\|"cubeRootAutocorrelation"\|"enhancedAutocorrelation"\|"cepstrum", window:"rectangular"\|"bartlett"\|"hamming"\|"hann"\|"blackman"\|"blackmanHarris"\|"welch"\|"gaussian25"\|"gaussian35"\|"gaussian45", size:int (power of two 128…131072)}` | `{rate, binHz (spectrum) or binSeconds (autocorr/cepstrum), values:[float], minValue, maxValue, warning?}` (dB for spectrum). The analysed length is capped by the engine (phones: Audacity allows up to 2^27 samples = 1.5 GB of buffers); `warning` says when the selection was truncated. | L |
| `analyze.contrast` | `{foreground:{t0,t1}, background:{t0,t1}}` | `{foregroundDb, backgroundDb, differenceDb, passes:bool}` | – |

#### import / export (io module)

| command | args | result | flags |
|---|---|---|---|
| `import.formats` | – | `{groups:[{description, extensions:[..]}], extensions:[..]}` | I |
| `import.files` | `{paths:[..], newProject:bool=false}` (real paths in `cacheDir/import/...`, original file names kept) | `{trackIds:[..], messages:[..]}` | M, L. With `newProject` a new project is created first. Each file is one undo entry ("Imported 'name'"). |
| `export.formats` | – | `{formats:[ExportFormat]}` (§5.6) | I |
| `export.defaults` | `{formatKey}` | `{hasSelection, defaultChannels, maxChannels, defaultRate, rates:[..]}` | I |
| `export.options` | `{formatKey}` | `ExportOptions` (§5.6) — opens/refreshes the options session | I |
| `export.setOption` | `{formatKey, id, value:{t,v}}` | `ExportOptions` | I |
| `export.run` | `{path, formatKey, range:"project"\|"selection", channels:int, rate:int, skipSilenceAtStart?:bool}` | `{path}` | L. `path` is a staging path (`cacheDir/export/<uuid>/<name>.<ext>`) that must not exist yet; Kotlin copies the result to the SAF Uri. |

#### transport / audio (audio module)

| command | args | result | flags |
|---|---|---|---|
| `transport.play` | `{loop?:bool=false, t0?, t1?}` | `{}` | No args = Space (`PlayCurrentRegion`): loops the play region if it is active, else plays the selection, or cursor → end. `loop:true` = if the play region is inactive, set it to the selection (or the whole project for a point selection) and activate it, then play looped (Transport ▸ Looping ▸ Enable + Space). `t0` (and optional `t1`) = Quick-Play: plays `[t0, t1 or project end]` once, never loops, does not change the play region. |
| `transport.stop` | – | `{}` | I. Stops playback/recording/preview/monitoring. Recording is finalised (one undo entry "Recorded Audio"). |
| `transport.pause` | – | `{}` | I. Toggles pause. |
| `transport.record` | `{newTrack:bool}` | `{}` | `newTrack:false` = desktop *Record* (R): records into the selected wave tracks (same rate, channel count = `recordChannels`) starting at max(cursor, end of those tracks); if none fit, into new tracks at the cursor; with a time selection after that start, stops at the selection end. `newTrack:true` = *Record New Track* (Shift+R). Fails `UNSUPPORTED` without microphone permission. |
| `transport.seek` | `{t}` | `{}` | I. While playing: jump; while stopped: moves the cursor (= `select.set`). |
| `transport.skipToStart` / `transport.skipToEnd` | – | `{}` | S |
| `transport.monitor` | `{enabled:bool}` | `{}` | I. Input level monitoring without recording. |
| `audio.devices` | – | `{outputs:[AudioDevice], inputs:[AudioDevice], current:{output, input, recordChannels}}` | I |
| `audio.permission` | `{recordPermission:bool}` | `{}` | I |
| `audio.latency` | – | `{outputLatencyMs, inputLatencyMs, correctionMs}` (`correctionMs` = value actually used: measured duplex offset + user trim) | I |
| `audio.setDevices` | `{devices:[{id, name, type, isSource, isSink, channelCounts:[..], sampleRates:[..]}]}` | `{}` | I (applied when idle). Kotlin passes `AudioManager.getDevices()`; native code cannot enumerate Android devices. Without it only "Default Output/Input" exist. |

#### display (display module, besides the binary JNI calls)

| command | args | flags |
|---|---|---|
| `display.setViewportWidth` | `{px}` | I |
| `display.trimCaches` | `{budgetBytes}` | I |

## 4. Events (native → Kotlin)

`EngineListener.onEvent(type, payloadJson)`, always from the engine thread.

### 4.1 `engine.ready` / `engine.failed`

```json
{ "audacityVersion": "3.7.9", "selfChecks": [{"name":"sampleBlockFactory","ok":true}, ...] }
{ "message": "why bootstrap failed" }
```

After `engine.ready` the engine has an empty project open (or none, if Kotlin
should first offer recovery: the ready payload has `"recoverable": n`; when
`n > 0` no project is opened automatically). A `snapshot` event follows
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
  import) carries `choices:[..]` and `defaultChecked:[bool]`; Kotlin answers
  with `replyDialogChoices(id, indices)` or cancels with `replyDialog(id, -1)`.

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
  "moveSelectionWithTracks": true,    // /GUI/MoveSelectionWithTracks
  "preferNewTrackRecord": false,      // /GUI/PreferNewTrackRecord (R behaves like Shift+R)
  "dropoutDetection": true            // /Warnings/DropoutDetected
}
```

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
— the port of Audacity's default effects-menu grouping
(`EffectsMenuDefaults.xml` sections, then the rest grouped by publisher).
`title:null` = no header.

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
* `nyquist` (Nyquist plug-ins): `{controls:[{key,label,kind,...}]}` richer
  metadata (labels from the `.ny` header); `params` mirrors it.

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

### 6.5 `readMeters(out: FloatArray)` layout

`readMeters` resets the accumulators, so there must be exactly **one**
consumer per process (the editor's shared meter reader).


`[playPeakL, playPeakR, playRmsL, playRmsR, playClipL, playClipR,
  recPeakL, recPeakR, recRmsL, recRmsR, recClipL, recClipR,
  playChannels, recChannels]` — linear amplitudes since the previous call
(peaks are maxima, RMS over the interval; clip flags 0/1 are sticky until
`transport.play`/`transport.record` starts). Ballistics (decay, peak hold) are
done in Kotlin.

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
  translates by `−hpos·pps` when drawing.
* Column vs sample mode is decided **per clip**: a clip is drawn from columns
  while `pps ≤ 0.5 · clip.rate / clip.stretchRatio` (snapshot `clips[]`,
  `WaveformView.cpp:860`), otherwise from individual samples (§7.4) for that
  clip's visible range.

### 7.2 `waveColumns(trackId, channel, zoomLevel, firstColumn, count, out)`

* `out` has size ≥ `3·count`: `[min_0..min_{count-1}, max_0.., rms_0..]`,
  linear, **before** the clip envelope (multiply with §7.3 when drawing).
  `NaN` where there is no audio (gaps between clips).
* Returns the track's `waveVersion` (≥ 0) on success, or a negative status:
  `-1` no such track/channel, `-2` partial data (recording tail, re-request
  soon; data in `out` is valid where not NaN — returned as `-(2)` only when
  nothing could be filled), `-3` engine not ready (or busy for > 250 ms),
  `-4` only when **every** clip intersecting the range needs sample mode.
  Columns of sample-mode clips are `NaN`. `waveVersion` is always in
  `[0, 2^62)`; bit 62 of a non-negative return is set when the tile is
  partial (recording tail): Kotlin re-requests it on the next poll.

### 7.3 `envelopeColumns(trackId, zoomLevel, firstColumn, count, out)`

Gain of the clip envelope at the centre of each column (`out` size ≥ count),
`NaN` outside clips. Same return convention as §7.2.

### 7.4 `waveSamples(trackId, channel, t0, t1)`

Returns `null` on error, else little-endian binary:
`int32 runCount; runCount × { int32 clipIndex; float64 firstSampleTime; float64 samplePeriod; int32 n; float32 values[n]; float32 envelope[n] }`.

### 7.5 `spectrogramColumns(trackId, channel, zoomLevel, firstColumn, count, rows, out)`

`out` size ≥ `count·rows`, column-major (`out[c·rows + r]`, r = 0 lowest
frequency), unsigned 8-bit normalised magnitude (0 = ≤ −range dB, 255 = 0 dB)
using Audacity's default spectrogram settings (window 2048 Hann, range 80 dB,
gain 20 dB, **linear** frequency scale 0 … rate/2 — a v1 simplification of
3.7.9's Mel default; the editor draws a linear axis). Colour mapping is done in
Kotlin. Returns as §7.2 (`-5` = spectrogram unsupported).

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
