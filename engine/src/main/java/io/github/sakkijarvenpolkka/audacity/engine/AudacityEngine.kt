/*
 * Audacity Android port — engine facade used by the UI.
 *
 * The UI talks only to this interface. The real implementation
 * (NativeAudacityEngine) forwards to NativeBridge / native/bridge (see
 * native/bridge/API.md); FakeAudacityEngine is an in-memory implementation
 * for Compose previews, Robolectric tests and builds without the native core.
 *
 * Every suspend function maps to exactly one engine command (named in the
 * KDoc) and throws [EngineException] when the engine answers with an error
 * envelope. Model state is NOT returned by mutating calls: observe [snapshot].
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.AppInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipTrimResult
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipboardInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.CompactInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.ContrastResult
import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectApplyResult
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryList
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportFormats
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportResult
import io.github.sakkijarvenpolkka.audacity.engine.model.LatencyInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.MeterSample
import io.github.sakkijarvenpolkka.audacity.engine.model.ProgressEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.SpectrumResult
import io.github.sakkijarvenpolkka.audacity.engine.model.SplitResult
import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Thrown when the engine answers with `{"ok": false, ...}`. [code] is one of
 *  [io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes]. */
class EngineException(val code: String, message: String) : Exception(message) {
    override fun toString(): String = "EngineException($code): $message"
}

/** Lifecycle of the native engine. */
sealed interface EngineStatus {
    data object Starting : EngineStatus
    data class Ready(val recoverableProjects: Int) : EngineStatus
    data class Failed(val message: String) : EngineStatus
    /** The native engine is not part of this build. (A native build whose
     *  library fails to load reports [Failed] with the reason.) */
    data object Unavailable : EngineStatus
}

/** Waveform tile returned by [AudacityEngine.waveColumns]. */
class WaveTile(
    val firstColumn: Long,
    val count: Int,
    /** min[count], max[count], rms[count]; NaN where there is no audio. */
    val data: FloatArray,
    val waveVersion: Long,
    val partial: Boolean,
)

/** Individual samples of one clip (deep zoom), API.md §7.4. */
class SampleRun(
    val clipIndex: Int,
    val firstSampleTime: Double,
    val samplePeriod: Double,
    val values: FloatArray,
    val envelope: FloatArray,
)

interface AudacityEngine {
    // ----- state ---------------------------------------------------------
    val status: StateFlow<EngineStatus>
    val snapshot: StateFlow<Snapshot>
    /** Active progress operations keyed by id (begin/update; removed on end). */
    val progress: StateFlow<Map<Int, ProgressEvent>>
    /** Dialog requests (blocking ones must be answered with [replyDialog]).
     *  Hot flow without replay: use [pendingDialogs] to (re)render dialogs. */
    val dialogs: SharedFlow<DialogEvent>
    val transportEvents: SharedFlow<TransportEvent>
    val logs: SharedFlow<LogEvent>

    /** True for [FakeAudacityEngine] (previews, tests, builds without the
     *  native core). The fake reports [EngineStatus.Ready]. */
    val isFake: Boolean get() = false

    /** Last `transport` event (API.md §4.5); "stopped" initially. */
    val transportState: StateFlow<TransportEvent> get() = DefaultFlows.STOPPED

    /** Dialogs shown by the engine and not answered yet, oldest first. A
     *  dialog stays here until [replyDialog] is called for its id, so it
     *  survives Activity recreation (the engine thread waits for blocking
     *  ones). */
    val pendingDialogs: StateFlow<List<DialogEvent>> get() = DefaultFlows.NO_DIALOGS

    // ----- lifecycle -----------------------------------------------------
    /** Extracts assets if needed and calls NativeBridge.start (idempotent). */
    suspend fun start()

    /** Answers a dialog: `button` indexes `buttons` (or `choices` for
     *  `kind:"choice"`), -1 = dismissed/cancelled. Call it for non-blocking
     *  dialogs too (they are only removed from [pendingDialogs]; the engine
     *  does not expect a reply for them). For `kind:"multiChoice"`, -1
     *  cancels and `button >= 0` accepts the `defaultChecked` choices. */
    fun replyDialog(dialogId: Int, button: Int)

    /** Answers a `kind:"multiChoice"` dialog with the checked choice indices
     *  (API.md §4.4; the engine sorts them and drops duplicates/out-of-range
     *  values; empty = "none"). Cancel with `replyDialog(id, -1)`. */
    fun replyDialogChoices(dialogId: Int, indices: List<Int>)
    fun cancelProgress(progressId: Int, stop: Boolean = false)

    /** Lock-free; safe on the UI thread every frame. */
    fun readTransport(): TransportSample
    /** Lock-free; safe on the UI thread every frame. */
    fun readMeters(): MeterSample?

    // ----- app / settings ------------------------------------------------
    suspend fun appInfo(): AppInfo                                   // app.info
    suspend fun getSettings(): Settings                              // settings.get
    suspend fun setSettings(partial: Settings): Settings             // settings.set

    // ----- project ---------------------------------------------------------
    suspend fun newProject()                                         // project.new
    suspend fun openProject(path: String)                            // project.open
    /** @throws EngineException with code NEEDS_PATH when never saved. */
    suspend fun saveProject(): String                                // project.save
    suspend fun saveProjectAs(path: String): String                  // project.saveAs
    suspend fun saveProjectCopy(path: String): String                // project.saveCopy
    suspend fun closeProject()                                       // project.close
    suspend fun projectInfo(): ProjectInfo                           // project.info
    suspend fun refreshSnapshot()                                    // project.snapshot
    suspend fun setProjectRate(rate: Int)                            // project.setRate
    suspend fun recoverableProjects(): List<ProjectFileEntry>        // project.recoverable
    suspend fun recoverProject(path: String)                         // project.recover
    suspend fun discardRecoverable(paths: List<String>)              // project.discardRecoverable
    suspend fun getTags(): List<Tag>                                 // project.tags.get
    suspend fun setTags(tags: List<Tag>)                             // project.tags.set
    suspend fun listProjects(): List<ProjectFileEntry>               // project.list
    suspend fun deleteProject(path: String)                          // project.delete
    /** Renames a project of `filesDir/Projects` that is not open; returns the new path. */
    suspend fun renameProject(path: String, newName: String): String // project.rename
    /** File ▸ Compact Project (ask first with [compactInfo]); returns the freed bytes. */
    suspend fun compactProject(): Long                               // project.compact
    suspend fun compactInfo(): CompactInfo                           // project.compactInfo

    // ----- history -------------------------------------------------------
    suspend fun undo()                                               // history.undo
    suspend fun redo()                                               // history.redo
    suspend fun history(): HistoryList                               // history.list
    suspend fun historyGoto(index: Int)                              // history.goto
    suspend fun historyPurge(keepFrom: Int)                          // history.purge

    // ----- view ------------------------------------------------------------
    suspend fun setView(zoom: Double? = null, hpos: Double? = null)  // view.set

    // ----- selection -------------------------------------------------------
    /** Time selection only (t0 == t1 = cursor). */
    suspend fun select(t0: Double, t1: Double)                       // select.set
    /** Time selection plus the track part in one command (a waveform tap):
     *  [trackIds] (when not null) replaces the track selection, [focus]
     *  moves the focus; unknown ids fail with NOT_FOUND and change nothing. */
    suspend fun select(t0: Double, t1: Double, trackIds: List<Long>?, focus: Long? = null) // select.set
    suspend fun selectAll()                                          // select.all
    suspend fun selectNone()                                         // select.none
    suspend fun selectTracks(ids: List<Long>, mode: String = "set")  // select.tracks
    suspend fun selectTrackHeader(id: Long, shift: Boolean, ctrl: Boolean) // select.trackHeader
    /** Selection commands without arguments: "select.allTracks",
     *  "select.startToCursor", "select.cursorToEnd", "select.trackStartToEnd",
     *  "select.toProjectStart", "select.toProjectEnd",
     *  "select.cursorToTrackStart", "select.cursorToTrackEnd",
     *  "select.prevClip", "select.nextClip",
     *  "select.prevClipBoundary", "select.nextClipBoundary",
     *  "select.zeroCrossing". */
    suspend fun selectCommand(command: String)
    suspend fun selectClip(trackId: Long, clipIndex: Int, generation: Long) // select.clip
    suspend fun focusTrack(id: Long)                                 // select.focus
    suspend fun setPlayRegion(t0: Double, t1: Double, active: Boolean) // playRegion.set
    suspend fun clearPlayRegion()                                    // playRegion.clear
    suspend fun togglePlayRegion()                                   // playRegion.toggle

    // ----- edit --------------------------------------------------------------
    /** Argument-less edit commands: "edit.cut", "edit.copy", "edit.paste",
     *  "edit.delete", "edit.splitCut", "edit.splitDelete", "edit.silence",
     *  "edit.trim", "edit.duplicate", "edit.split", "edit.splitNew",
     *  "edit.join", "edit.detachAtSilences". */
    suspend fun edit(command: String)
    suspend fun clipboardInfo(): ClipboardInfo                       // edit.clipboardInfo
    /**
     * Splits at one point (the mobile "split" button at the cursor/play head,
     * the razor tool) in one "Split" entry; the cursor moves to the split
     * point. [trackIds] null = the selected wave tracks, or, with no track
     * selected, every wave track with a clip at [t]. Nothing to split →
     * `splits == 0` and nothing changes.
     */
    suspend fun splitAt(t: Double, trackIds: List<Long>? = null): SplitResult // edit.splitAt

    // ----- tracks ------------------------------------------------------------
    suspend fun addTrack(kind: String): Long                         // tracks.add
    suspend fun removeTracks(ids: List<Long>)                        // tracks.remove
    suspend fun mixAndRender(toNewTrack: Boolean)                    // tracks.mixAndRender
    suspend fun resample(rate: Int)                                  // tracks.resample
    /** `final = false` while dragging: no history entry, no generation
     *  bump; `final = true` on release: one consolidated "Volume" entry. */
    suspend fun setTrackGain(id: Long, gain: Double, final: Boolean) // tracks.setGain
    suspend fun setTrackPan(id: Long, pan: Double, final: Boolean)   // tracks.setPan
    /** Updates the current undo state (no history entry), like 3.7.9. */
    suspend fun setTrackMute(id: Long, mute: Boolean)                // tracks.setMute
    suspend fun setTrackSolo(id: Long, solo: Boolean)                // tracks.setSolo
    /** Tracks ▸ Mute/Unmute ▸ Mute/Unmute All Tracks. */
    suspend fun muteAllTracks(mute: Boolean)                         // tracks.muteAll
    suspend fun renameTrack(id: Long, name: String)                  // tracks.rename
    suspend fun moveTrack(id: Long, to: String)                      // tracks.move
    /** "tracks.makeStereo" | "tracks.splitStereo" | "tracks.splitStereoToMono" | "tracks.swapChannels" */
    suspend fun trackChannelCommand(command: String, id: Long)
    suspend fun setTrackRate(id: Long, rate: Int)                    // tracks.setRate
    suspend fun setTrackFormat(id: Long, format: String)             // tracks.setFormat
    /** [mode]: "startToZero" | "startToCursor" | "startToSelEnd" |
     *  "endToCursor" | "endToSelEnd" | "endToEnd" | "together";
     *  [moveSelection] null = the `moveSelectionWithTracks` setting. */
    suspend fun alignTracks(mode: String, moveSelection: Boolean? = null) // tracks.align
    /** [by]: "time" | "name". */
    suspend fun sortTracks(by: String)                               // tracks.sort

    // ----- clips / labels ----------------------------------------------------
    suspend fun moveClip(trackId: Long, clipIndex: Int, generation: Long, newStart: Double, toTrackId: Long? = null) // clips.move
    suspend fun renameClip(trackId: Long, clipIndex: Int, generation: Long, name: String) // clips.rename
    /**
     * Non-destructive trim of a clip's borders: [trimLeft]/[trimRight] are the
     * absolute hidden lengths in seconds (like [io.github.sakkijarvenpolkka.audacity.engine.model.ClipState]),
     * at least one of them; clamped to the clip's audio and its neighbours.
     * `final = false` while dragging (no history entry, no generation bump,
     * so [generation] stays valid), `final = true` on release: one
     * "Adjust left/right trim" entry for the whole drag (none when the clip
     * ends where the drag started, so sending the initial values cancels).
     */
    suspend fun trimClip(trackId: Long, clipIndex: Int, generation: Long, trimLeft: Double? = null,
                         trimRight: Double? = null, final: Boolean = true): ClipTrimResult // clips.trim
    suspend fun addLabel(title: String = ""): Pair<Long, Int>        // labels.add
    /** Adds a label at [t0, t1 ?: t0] (e.g. the play head while playing or
     *  recording) without using or changing the time selection. */
    suspend fun addLabel(title: String = "", t0: Double, t1: Double? = null): Pair<Long, Int> // labels.add
    /** Returns the label's index after the edit (time edits re-sort the
     *  labels). A [generation] older than the current one fails with STALE. */
    suspend fun editLabel(trackId: Long, index: Int, title: String? = null, t0: Double? = null, t1: Double? = null,
                          generation: Long? = null): Int            // labels.edit
    suspend fun removeLabel(trackId: Long, index: Int, generation: Long? = null) // labels.remove
    /** File ▸ Import ▸ Labels: a text or SubRip file in app storage; returns the new label track. */
    suspend fun importLabels(path: String): Long                     // labels.import
    /** File ▸ Export Other ▸ Export Labels: [format] "text" | "subrip" | "webvtt" | "podcastChapters";
     *  returns the number of labels written. */
    suspend fun exportLabels(path: String, format: String = "text"): Int // labels.export

    // ----- effects / analyze ---------------------------------------------------
    suspend fun effects(): EffectList                                // effects.list
    suspend fun describeEffect(id: String): EffectDescription        // effects.describe
    suspend fun setEffectParams(id: String, params: Map<String, JsonElement>, duration: Double? = null, curve: EqCurve? = null): EffectDescription // effects.setParams
    suspend fun loadEffectPreset(id: String, kind: String, name: String? = null, index: Int? = null): EffectDescription // effects.loadPreset
    suspend fun saveEffectPreset(id: String, name: String)           // effects.savePreset
    suspend fun deleteEffectPreset(id: String, name: String)         // effects.deletePreset
    suspend fun applyEffect(id: String, params: Map<String, JsonElement>? = null, duration: Double? = null,
                            curve: EqCurve? = null): EffectApplyResult // effects.apply
    suspend fun previewEffect(id: String, params: Map<String, JsonElement>? = null, duration: Double? = null,
                              curve: EqCurve? = null)               // effects.preview
    suspend fun stopPreview()                                        // effects.stopPreview
    suspend fun repeatLastEffect(): EffectApplyResult                // effects.repeatLast
    suspend fun captureNoiseProfile()                                // effects.noiseReduction.captureProfile
    /** [algorithm]: "spectrum" | "autocorrelation" | "cubeRootAutocorrelation" |
     *  "enhancedAutocorrelation" | "cepstrum"; [size] a power of two 128 … 131072. */
    suspend fun plotSpectrum(algorithm: String, window: String, size: Int): SpectrumResult // analyze.spectrum
    suspend fun contrast(fg0: Double, fg1: Double, bg0: Double, bg1: Double): ContrastResult // analyze.contrast

    // ----- import / export -----------------------------------------------------
    suspend fun importFormats(): ImportFormats                       // import.formats
    suspend fun importFiles(paths: List<String>, newProject: Boolean = false): ImportResult // import.files
    suspend fun exportFormats(): List<ExportFormat>                  // export.formats
    suspend fun exportDefaults(formatKey: String): ExportDefaults    // export.defaults
    suspend fun exportOptions(formatKey: String): ExportOptions      // export.options
    suspend fun setExportOption(formatKey: String, id: Int, value: ExportValue): ExportOptions // export.setOption
    suspend fun export(path: String, formatKey: String, range: String, channels: Int, rate: Int, skipSilenceAtStart: Boolean = false): String // export.run

    // ----- transport / audio ---------------------------------------------------
    suspend fun play(loop: Boolean = false, t0: Double? = null, t1: Double? = null) // transport.play
    suspend fun stop()                                               // transport.stop
    suspend fun pause()                                              // transport.pause
    suspend fun record(newTrack: Boolean)                            // transport.record
    suspend fun seek(t: Double)                                      // transport.seek
    suspend fun skipToStart()                                        // transport.skipToStart
    suspend fun skipToEnd()                                          // transport.skipToEnd
    suspend fun monitor(enabled: Boolean)                            // transport.monitor
    suspend fun audioDevices(): AudioDevices                         // audio.devices
    /** Injects the Android device list (applied now when idle, else when the
     *  stream stops); returns true when applied at once. */
    suspend fun setAudioDevices(devices: List<AudioDeviceSpec>): Boolean // audio.setDevices
    suspend fun setRecordPermission(granted: Boolean)                // audio.permission
    suspend fun latency(): LatencyInfo                               // audio.latency

    // ----- display ---------------------------------------------------------------
    suspend fun setViewportWidth(px: Int)                            // display.setViewportWidth
    /** Runs off the main thread; null on error. */
    suspend fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int): WaveTile?
    suspend fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int): FloatArray?
    suspend fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): List<SampleRun>?
    suspend fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int): ByteArray?

    /**
     * Allocation-free variant of [waveColumns]: fills [out] (size ≥ 3·count:
     * min[count], max[count], rms[count]) and returns the raw status of
     * API.md §7.2 (waveVersion ≥ 0, bit 62 = partial; or a negative
     * [DisplayStatus] code such as SAMPLE_MODE or NOT_READY).
     */
    suspend fun waveColumnsInto(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        val tile = waveColumns(trackId, channel, zoomLevel, firstColumn, count) ?: return DisplayStatus.NOT_READY
        tile.data.copyInto(out, 0, 0, minOf(out.size, tile.data.size))
        return if (tile.partial) tile.waveVersion or DisplayStatus.PARTIAL_BIT else tile.waveVersion
    }

    /** Allocation-free variant of [envelopeColumns] (`out` size ≥ count); the
     *  return value follows API.md §7.2. */
    suspend fun envelopeColumnsInto(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        val data = envelopeColumns(trackId, zoomLevel, firstColumn, count) ?: return DisplayStatus.NOT_READY
        data.copyInto(out, 0, 0, minOf(out.size, data.size))
        return 0L
    }

    /** display.trimCaches — e.g. from `onTrimMemory`. */
    suspend fun trimDisplayCaches(budgetBytes: Long) {}

    /**
     * Runs any engine command by name (API.md §3.3) and returns its `result`
     * object; for the debug screen and for commands without a typed wrapper
     * (e.g. `debug.makeTestTrack`, `effects.lastApplied`).
     * @throws EngineException like the typed calls.
     */
    suspend fun invokeCommand(command: String, args: JsonObject = JsonObject(emptyMap())): JsonElement =
        throw EngineException("UNKNOWN_COMMAND", "$command is not supported by this engine")
}

/** Suspends until the engine left [EngineStatus.Starting]; returns that status. */
suspend fun AudacityEngine.awaitStarted(): EngineStatus = status.first { it !is EngineStatus.Starting }

internal object DefaultFlows {
    val STOPPED: StateFlow<TransportEvent> = MutableStateFlow(TransportEvent("stopped")).asStateFlow()
    val NO_DIALOGS: StateFlow<List<DialogEvent>> = MutableStateFlow(emptyList<DialogEvent>()).asStateFlow()
}
