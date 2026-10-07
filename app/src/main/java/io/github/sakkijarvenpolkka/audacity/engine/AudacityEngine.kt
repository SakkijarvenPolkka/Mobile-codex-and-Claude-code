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
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipboardInfo
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
import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/** Thrown when the engine answers with `{"ok": false, ...}`. */
class EngineException(val code: String, message: String) : Exception(message)

/** Lifecycle of the native engine. */
sealed interface EngineStatus {
    data object Starting : EngineStatus
    data class Ready(val recoverableProjects: Int) : EngineStatus
    data class Failed(val message: String) : EngineStatus
    /** Built with -Paudacity.buildNative=false or the library failed to load. */
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
    /** Dialog requests (blocking ones must be answered with [replyDialog]). */
    val dialogs: SharedFlow<DialogEvent>
    val transportEvents: SharedFlow<TransportEvent>
    val logs: SharedFlow<LogEvent>

    // ----- lifecycle -----------------------------------------------------
    /** Extracts assets if needed and calls NativeBridge.start (idempotent). */
    suspend fun start()

    fun replyDialog(dialogId: Int, button: Int)
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

    // ----- history -------------------------------------------------------
    suspend fun undo()                                               // history.undo
    suspend fun redo()                                               // history.redo
    suspend fun history(): HistoryList                               // history.list
    suspend fun historyGoto(index: Int)                              // history.goto
    suspend fun historyPurge(keepFrom: Int)                          // history.purge

    // ----- view ------------------------------------------------------------
    suspend fun setView(zoom: Double? = null, hpos: Double? = null)  // view.set

    // ----- selection -------------------------------------------------------
    suspend fun select(t0: Double, t1: Double)                       // select.set
    suspend fun selectAll()                                          // select.all
    suspend fun selectNone()                                         // select.none
    suspend fun selectTracks(ids: List<Long>, mode: String = "set")  // select.tracks
    suspend fun selectTrackHeader(id: Long, shift: Boolean, ctrl: Boolean) // select.trackHeader
    /** Simple selection commands without arguments, e.g. "select.allTracks",
     *  "select.startToCursor", "select.cursorToEnd", "select.trackStartToEnd",
     *  "select.cursorToTrackStart", "select.cursorToTrackEnd",
     *  "select.prevClipBoundary", "select.nextClipBoundary". */
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

    // ----- tracks ------------------------------------------------------------
    suspend fun addTrack(kind: String): Long                         // tracks.add
    suspend fun removeTracks(ids: List<Long>)                        // tracks.remove
    suspend fun mixAndRender(toNewTrack: Boolean)                    // tracks.mixAndRender
    suspend fun resample(rate: Int)                                  // tracks.resample
    suspend fun setTrackGain(id: Long, gain: Double, final: Boolean) // tracks.setGain
    suspend fun setTrackPan(id: Long, pan: Double, final: Boolean)   // tracks.setPan
    suspend fun setTrackMute(id: Long, mute: Boolean)                // tracks.setMute
    suspend fun setTrackSolo(id: Long, solo: Boolean)                // tracks.setSolo
    suspend fun renameTrack(id: Long, name: String)                  // tracks.rename
    suspend fun moveTrack(id: Long, to: String)                      // tracks.move
    /** "tracks.makeStereo" | "tracks.splitStereo" | "tracks.splitStereoToMono" | "tracks.swapChannels" */
    suspend fun trackChannelCommand(command: String, id: Long)
    suspend fun setTrackRate(id: Long, rate: Int)                    // tracks.setRate
    suspend fun setTrackFormat(id: Long, format: String)             // tracks.setFormat
    suspend fun alignTracks(mode: String)                            // tracks.align

    // ----- clips / labels ----------------------------------------------------
    suspend fun moveClip(trackId: Long, clipIndex: Int, generation: Long, newStart: Double, toTrackId: Long? = null) // clips.move
    suspend fun renameClip(trackId: Long, clipIndex: Int, generation: Long, name: String) // clips.rename
    suspend fun addLabel(title: String = ""): Pair<Long, Int>        // labels.add
    suspend fun editLabel(trackId: Long, index: Int, title: String? = null, t0: Double? = null, t1: Double? = null) // labels.edit
    suspend fun removeLabel(trackId: Long, index: Int)               // labels.remove

    // ----- effects / analyze ---------------------------------------------------
    suspend fun effects(): EffectList                                // effects.list
    suspend fun describeEffect(id: String): EffectDescription        // effects.describe
    suspend fun setEffectParams(id: String, params: Map<String, JsonElement>, duration: Double? = null, curve: EqCurve? = null): EffectDescription // effects.setParams
    suspend fun loadEffectPreset(id: String, kind: String, name: String? = null, index: Int? = null): EffectDescription // effects.loadPreset
    suspend fun saveEffectPreset(id: String, name: String)           // effects.savePreset
    suspend fun deleteEffectPreset(id: String, name: String)         // effects.deletePreset
    suspend fun applyEffect(id: String, params: Map<String, JsonElement>? = null, duration: Double? = null): EffectApplyResult // effects.apply
    suspend fun previewEffect(id: String, params: Map<String, JsonElement>? = null, duration: Double? = null) // effects.preview
    suspend fun stopPreview()                                        // effects.stopPreview
    suspend fun repeatLastEffect(): EffectApplyResult                // effects.repeatLast
    suspend fun captureNoiseProfile()                                // effects.noiseReduction.captureProfile
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
    suspend fun setRecordPermission(granted: Boolean)                // audio.permission
    suspend fun latency(): LatencyInfo                               // audio.latency

    // ----- display ---------------------------------------------------------------
    suspend fun setViewportWidth(px: Int)                            // display.setViewportWidth
    /** Runs off the main thread; null on error. */
    suspend fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int): WaveTile?
    suspend fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int): FloatArray?
    suspend fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): List<SampleRun>?
    suspend fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int): ByteArray?
}
