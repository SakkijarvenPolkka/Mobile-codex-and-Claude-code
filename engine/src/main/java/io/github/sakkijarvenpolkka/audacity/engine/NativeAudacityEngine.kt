/*
 * Audacity Android port — AudacityEngine backed by libaudacity-bridge.
 *
 * Threads (API.md §1, §8; init notes §3):
 *  - "audacity-invoke": every NativeBridge.invoke runs here, in call order
 *    (never on the main thread; the native side serializes on its engine
 *    thread anyway).
 *  - "audacity-display": waveform/envelope/sample/spectrogram calls, so tile
 *    requests never queue behind a long command on the invoke thread (the
 *    native display lane waits at most 250 ms and then answers NOT_READY).
 *  - "audacity-events": the EngineListener only hands the payload over; this
 *    thread decodes events in arrival order and updates the flows.
 *  After every invoke the caller also waits until the event thread has
 *  applied the events that arrived before the response, so a `snapshot`
 *  emitted by a command is visible in [snapshot] when the call returns.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import android.content.Context
import io.github.sakkijarvenpolkka.audacity.engine.model.AppInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipboardInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.CompactInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.CompactResult
import io.github.sakkijarvenpolkka.audacity.engine.model.ContrastResult
import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectApplyResult
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineReady
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormatList
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryList
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportFormats
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportResult
import io.github.sakkijarvenpolkka.audacity.engine.model.LabelExportResult
import io.github.sakkijarvenpolkka.audacity.engine.model.LatencyInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.MeterSample
import io.github.sakkijarvenpolkka.audacity.engine.model.PathResult
import io.github.sakkijarvenpolkka.audacity.engine.model.ProgressEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileList
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.SetDevicesResult
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.SettingsResult
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.SpectrumResult
import io.github.sakkijarvenpolkka.audacity.engine.model.StartConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.engine.model.TagList
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

class NativeAudacityEngine internal constructor(
    private val bridge: BridgeApi,
    /** Runs on Dispatchers.IO before NativeBridge.start: extracts the assets
     *  and builds the configuration; the argument logs a warning. */
    private val prepareStart: suspend (warn: (String) -> Unit) -> StartConfig,
) : AudacityEngine {

    /** Engine for the app process. Prefer [Engines.create], which returns a
     *  process-wide instance (the native engine can be started only once). */
    constructor(context: Context) : this(NativeBridgeApi, contextStarter(context.applicationContext))

    // Starting until start() loaded the library: constructing the engine
    // must not load it (that maps ~75 libraries; Engines.create runs on the
    // main thread)
    private val hub = EngineEventHub(EngineStatus.Starting)

    private val invokeExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemon("audacity-invoke"))
    private val invokeDispatcher = invokeExecutor.asCoroutineDispatcher()
    private val displayDispatcher = Executors.newSingleThreadExecutor(daemon("audacity-display")).asCoroutineDispatcher()
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor(daemon("audacity-events"))
    private val eventDispatcher = eventExecutor.asCoroutineDispatcher()

    private val listener = EngineListener { type, payload ->
        // Engine thread: hand over only (the payload array is ours)
        eventExecutor.execute { hub.dispatch(type, payload) }
    }

    private val startMutex = Mutex()
    @Volatile private var started = false
    /** The library is loaded (set by [start], off the main thread); until
     *  then nothing touches [BridgeApi.isLoaded] or a JNI function. */
    @Volatile private var loaded = false

    private val transportBuffer = ThreadLocal.withInitial { DoubleArray(TransportSample.SIZE) }
    private val meterBuffer = ThreadLocal.withInitial { FloatArray(MeterSample.SIZE) }

    // ----- state ---------------------------------------------------------
    override val status: StateFlow<EngineStatus> get() = hub.status
    override val snapshot: StateFlow<Snapshot> get() = hub.snapshot
    override val progress: StateFlow<Map<Int, ProgressEvent>> get() = hub.progress
    override val dialogs: SharedFlow<DialogEvent> get() = hub.dialogs
    override val transportEvents: SharedFlow<TransportEvent> get() = hub.transportEvents
    override val logs: SharedFlow<LogEvent> get() = hub.logs
    override val transportState: StateFlow<TransportEvent> get() = hub.transportState
    override val pendingDialogs: StateFlow<List<DialogEvent>> get() = hub.pendingDialogs

    /** Payload of `engine.ready` (self-checks), null before. */
    val readyInfo: StateFlow<EngineReady?> get() = hub.readyInfo

    // ----- lifecycle -----------------------------------------------------
    /**
     * Loads the library (on [Dispatchers.IO]), extracts the assets and starts
     * the engine thread; the outcome is [status]. Not cancellable once begun
     * (the caller's scope, e.g. a view model closed during the first-run
     * extraction, must not leave a started engine marked as not started).
     */
    override suspend fun start() = withContext(NonCancellable) {
        startMutex.withLock { startLocked() }
    }

    private suspend fun startLocked() {
        if (started) return
        hub.setStatus(EngineStatus.Starting)
        if (!withContext(Dispatchers.IO) { bridge.isLoaded }) {
            // BuildConfig.NATIVE_ENGINE builds never fall back to the fake
            // engine (it would only simulate audio and keep saves in memory)
            val error = bridge.loadError
            fail("the native engine library could not be loaded: ${error ?: "unknown error"}", error)
            return
        }
        loaded = true
        val config = try {
            withContext(Dispatchers.IO) { prepareStart { hub.log(LogEvent("warning", it)) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("cannot prepare the engine start: ${e.message}", e)
            return
        }
        val json = EngineJson.json.encodeToString(StartConfig.serializer(), config).encodeToByteArray()
        val ok = try {
            withContext(invokeDispatcher) {
                // Marked at once: the engine thread runs from here on
                bridge.start(json, listener).also { if (it) started = true }
            }
        } catch (e: LinkageError) {
            // The library loaded but lacks the JNI entry points (API.md §2)
            fail("JNI entry point missing: ${e.message}", e)
            return
        }
        started = true
        if (!ok) fail("the engine thread could not be started (already started in this process?)", null)
    }

    /** Failed status + an error line in [logs] and in logcat (with the stack). */
    private fun fail(message: String, cause: Throwable?) {
        hub.setStatus(EngineStatus.Failed(message))
        hub.log(LogEvent("error", if (cause != null) "$message\n${cause.stackTraceToString()}" else message))
        try {
            android.util.Log.e(TAG, message, cause)
        } catch (_: RuntimeException) {
            // JVM unit tests: android.jar stubs
        }
    }

    override fun replyDialog(dialogId: Int, button: Int) {
        val dialog = hub.resolveDialog(dialogId)
        // Unknown ids are forwarded too: the native side ignores ids it does not wait for
        if ((dialog == null || dialog.blocking) && loaded) bridge.replyDialog(dialogId, button)
    }

    override fun replyDialogChoices(dialogId: Int, indices: List<Int>) {
        val dialog = hub.resolveDialog(dialogId)
        if ((dialog == null || dialog.blocking) && loaded) {
            try {
                bridge.replyDialogChoices(dialogId, indices.toIntArray())
            } catch (e: LinkageError) {
                // A library without the entry point: cancel instead of leaving the engine waiting
                bridge.replyDialog(dialogId, -1)
            }
        }
    }

    override fun cancelProgress(progressId: Int, stop: Boolean) {
        if (loaded) bridge.cancelProgress(progressId, stop)
    }

    override fun readTransport(): TransportSample {
        if (!loaded) return TransportSample.IDLE
        val a = transportBuffer.get()!!
        return try {
            if (bridge.readTransport(a)) TransportSample.decode(a) else TransportSample.IDLE
        } catch (e: LinkageError) {
            TransportSample.IDLE
        }
    }

    override fun readMeters(): MeterSample? {
        if (!loaded) return null
        val a = meterBuffer.get()!!
        return try {
            if (bridge.readMeters(a)) MeterSample.decode(a) else null
        } catch (e: LinkageError) {
            null
        }
    }

    // ----- command plumbing ------------------------------------------------
    private suspend fun callJson(command: String, args: JsonObject): JsonElement {
        if (!loaded) throw EngineException(ErrorCodes.NOT_READY, "the native engine is not available")
        val commandBytes = command.encodeToByteArray()
        val argBytes = EngineProtocol.encodeArgs(args)
        val response = try {
            withContext(invokeDispatcher) { bridge.invoke(commandBytes, argBytes) }
        } catch (e: LinkageError) {
            throw EngineException(ErrorCodes.INTERNAL, "JNI entry point missing: ${e.message}")
        }
        // Events emitted before the response (e.g. its snapshot) are queued
        // on the event thread already: wait until they are applied.
        withContext(eventDispatcher) {}
        return EngineProtocol.unwrap(EngineProtocol.decodeEnvelope(response))
    }

    private suspend fun call(command: String, vararg args: Pair<String, Any?>): JsonElement =
        callJson(command, EngineProtocol.args(*args))

    private suspend inline fun <reified T> callFor(command: String, vararg args: Pair<String, Any?>): T =
        EngineProtocol.decodeResult(command, callJson(command, EngineProtocol.args(*args)))

    override suspend fun invokeCommand(command: String, args: JsonObject): JsonElement = callJson(command, args)

    // ----- app / settings ------------------------------------------------
    override suspend fun appInfo(): AppInfo = callFor("app.info")
    override suspend fun getSettings(): Settings = callFor<SettingsResult>("settings.get").settings
    override suspend fun setSettings(partial: Settings): Settings =
        callFor<SettingsResult>("settings.set", "settings" to EngineJson.json.encodeToJsonElement(Settings.serializer(), partial)).settings

    // ----- project -------------------------------------------------------
    override suspend fun newProject() { call("project.new") }
    override suspend fun openProject(path: String) { call("project.open", "path" to path) }
    override suspend fun saveProject(): String = callFor<PathResult>("project.save").path
    override suspend fun saveProjectAs(path: String): String = callFor<PathResult>("project.saveAs", "path" to path).path
    override suspend fun saveProjectCopy(path: String): String = callFor<PathResult>("project.saveCopy", "path" to path).path
    override suspend fun closeProject() { call("project.close") }
    override suspend fun projectInfo(): ProjectInfo = callFor("project.info")
    override suspend fun refreshSnapshot() { call("project.snapshot") }
    override suspend fun setProjectRate(rate: Int) { call("project.setRate", "rate" to rate) }
    override suspend fun recoverableProjects(): List<ProjectFileEntry> = callFor<ProjectFileList>("project.recoverable").projects
    override suspend fun recoverProject(path: String) { call("project.recover", "path" to path) }
    override suspend fun discardRecoverable(paths: List<String>) { call("project.discardRecoverable", "paths" to paths) }
    override suspend fun getTags(): List<Tag> = callFor<TagList>("project.tags.get").tags
    override suspend fun setTags(tags: List<Tag>) {
        call("project.tags.set", "tags" to tags.map { mapOf("name" to it.name, "value" to it.value) })
    }
    override suspend fun listProjects(): List<ProjectFileEntry> = callFor<ProjectFileList>("project.list").projects
    override suspend fun deleteProject(path: String) { call("project.delete", "path" to path) }
    override suspend fun renameProject(path: String, newName: String): String =
        callFor<PathResult>("project.rename", "path" to path, "newName" to newName).path
    override suspend fun compactProject(): Long = callFor<CompactResult>("project.compact").freedBytes
    override suspend fun compactInfo(): CompactInfo = callFor("project.compactInfo")

    // ----- history -------------------------------------------------------
    override suspend fun undo() { call("history.undo") }
    override suspend fun redo() { call("history.redo") }
    override suspend fun history(): HistoryList = callFor("history.list")
    override suspend fun historyGoto(index: Int) { call("history.goto", "index" to index) }
    override suspend fun historyPurge(keepFrom: Int) { call("history.purge", "keepFrom" to keepFrom) }

    // ----- view ------------------------------------------------------------
    override suspend fun setView(zoom: Double?, hpos: Double?) { call("view.set", "zoom" to zoom, "hpos" to hpos) }

    // ----- selection -------------------------------------------------------
    override suspend fun select(t0: Double, t1: Double) = select(t0, t1, null, null)
    override suspend fun select(t0: Double, t1: Double, trackIds: List<Long>?, focus: Long?) {
        call("select.set", "t0" to t0, "t1" to t1, "trackIds" to trackIds, "focus" to focus)
    }
    override suspend fun selectAll() { call("select.all") }
    override suspend fun selectNone() { call("select.none") }
    override suspend fun selectTracks(ids: List<Long>, mode: String) { call("select.tracks", "ids" to ids, "mode" to mode) }
    override suspend fun selectTrackHeader(id: Long, shift: Boolean, ctrl: Boolean) {
        call("select.trackHeader", "id" to id, "shift" to shift, "ctrl" to ctrl)
    }
    override suspend fun selectCommand(command: String) { call(command) }
    override suspend fun selectClip(trackId: Long, clipIndex: Int, generation: Long) {
        call("select.clip", "trackId" to trackId, "clipIndex" to clipIndex, "generation" to generation)
    }
    override suspend fun focusTrack(id: Long) { call("select.focus", "id" to id) }
    override suspend fun setPlayRegion(t0: Double, t1: Double, active: Boolean) {
        call("playRegion.set", "t0" to t0, "t1" to t1, "active" to active)
    }
    override suspend fun clearPlayRegion() { call("playRegion.clear") }
    override suspend fun togglePlayRegion() { call("playRegion.toggle") }

    // ----- edit --------------------------------------------------------------
    override suspend fun edit(command: String) { call(command) }
    override suspend fun clipboardInfo(): ClipboardInfo = callFor("edit.clipboardInfo")

    // ----- tracks ------------------------------------------------------------
    override suspend fun addTrack(kind: String): Long = callFor<IdResult>("tracks.add", "kind" to kind).id
    override suspend fun removeTracks(ids: List<Long>) { call("tracks.remove", "ids" to ids) }
    override suspend fun mixAndRender(toNewTrack: Boolean) { call("tracks.mixAndRender", "toNewTrack" to toNewTrack) }
    override suspend fun resample(rate: Int) { call("tracks.resample", "rate" to rate) }
    override suspend fun setTrackGain(id: Long, gain: Double, final: Boolean) {
        call("tracks.setGain", "id" to id, "gain" to gain, "final" to final)
    }
    override suspend fun setTrackPan(id: Long, pan: Double, final: Boolean) {
        call("tracks.setPan", "id" to id, "pan" to pan, "final" to final)
    }
    override suspend fun setTrackMute(id: Long, mute: Boolean) { call("tracks.setMute", "id" to id, "mute" to mute) }
    override suspend fun setTrackSolo(id: Long, solo: Boolean) { call("tracks.setSolo", "id" to id, "solo" to solo) }
    override suspend fun muteAllTracks(mute: Boolean) { call("tracks.muteAll", "mute" to mute) }
    override suspend fun renameTrack(id: Long, name: String) { call("tracks.rename", "id" to id, "name" to name) }
    override suspend fun moveTrack(id: Long, to: String) { call("tracks.move", "id" to id, "to" to to) }
    override suspend fun trackChannelCommand(command: String, id: Long) { call(command, "id" to id) }
    override suspend fun setTrackRate(id: Long, rate: Int) { call("tracks.setRate", "id" to id, "rate" to rate) }
    override suspend fun setTrackFormat(id: Long, format: String) { call("tracks.setFormat", "id" to id, "format" to format) }
    override suspend fun alignTracks(mode: String, moveSelection: Boolean?) {
        call("tracks.align", "mode" to mode, "moveSelection" to moveSelection)
    }
    override suspend fun sortTracks(by: String) { call("tracks.sort", "by" to by) }

    // ----- clips / labels ----------------------------------------------------
    override suspend fun moveClip(trackId: Long, clipIndex: Int, generation: Long, newStart: Double, toTrackId: Long?) {
        call("clips.move", "trackId" to trackId, "clipIndex" to clipIndex, "generation" to generation,
            "newStart" to newStart, "toTrackId" to toTrackId)
    }
    override suspend fun renameClip(trackId: Long, clipIndex: Int, generation: Long, name: String) {
        call("clips.rename", "trackId" to trackId, "clipIndex" to clipIndex, "generation" to generation, "name" to name)
    }
    override suspend fun addLabel(title: String): Pair<Long, Int> =
        callFor<LabelRef>("labels.add", "title" to title).let { it.trackId to it.index }
    override suspend fun editLabel(trackId: Long, index: Int, title: String?, t0: Double?, t1: Double?, generation: Long?): Int =
        callFor<LabelIndex>("labels.edit", "trackId" to trackId, "index" to index, "generation" to generation,
            "title" to title, "t0" to t0, "t1" to t1).index
    override suspend fun removeLabel(trackId: Long, index: Int, generation: Long?) {
        call("labels.remove", "trackId" to trackId, "index" to index, "generation" to generation)
    }
    override suspend fun importLabels(path: String): Long = callFor<TrackIdResult>("labels.import", "path" to path).trackId
    override suspend fun exportLabels(path: String, format: String): Int =
        callFor<LabelExportResult>("labels.export", "path" to path, "format" to format).labels

    // ----- effects / analyze ---------------------------------------------------
    override suspend fun effects(): EffectList = callFor("effects.list")
    override suspend fun describeEffect(id: String): EffectDescription = callFor("effects.describe", "id" to id)
    override suspend fun setEffectParams(id: String, params: Map<String, JsonElement>, duration: Double?, curve: EqCurve?): EffectDescription =
        callFor("effects.setParams", "id" to id, "params" to JsonObject(params), "duration" to duration,
            "curve" to curve?.let { EngineJson.json.encodeToJsonElement(EqCurve.serializer(), it) })
    override suspend fun loadEffectPreset(id: String, kind: String, name: String?, index: Int?): EffectDescription =
        callFor("effects.loadPreset", "id" to id, "kind" to kind, "name" to name, "index" to index)
    override suspend fun saveEffectPreset(id: String, name: String) { call("effects.savePreset", "id" to id, "name" to name) }
    override suspend fun deleteEffectPreset(id: String, name: String) { call("effects.deletePreset", "id" to id, "name" to name) }
    override suspend fun applyEffect(id: String, params: Map<String, JsonElement>?, duration: Double?, curve: EqCurve?): EffectApplyResult =
        callFor("effects.apply", "id" to id, "params" to params?.let { JsonObject(it) }, "duration" to duration,
            "curve" to curve?.let { EngineJson.json.encodeToJsonElement(EqCurve.serializer(), it) })
    override suspend fun previewEffect(id: String, params: Map<String, JsonElement>?, duration: Double?, curve: EqCurve?) {
        call("effects.preview", "id" to id, "params" to params?.let { JsonObject(it) }, "duration" to duration,
            "curve" to curve?.let { EngineJson.json.encodeToJsonElement(EqCurve.serializer(), it) })
    }
    override suspend fun stopPreview() { call("effects.stopPreview") }
    override suspend fun repeatLastEffect(): EffectApplyResult = callFor("effects.repeatLast")
    override suspend fun captureNoiseProfile() { call("effects.noiseReduction.captureProfile") }
    override suspend fun plotSpectrum(algorithm: String, window: String, size: Int): SpectrumResult =
        callFor("analyze.spectrum", "algorithm" to algorithm, "window" to window, "size" to size)
    override suspend fun contrast(fg0: Double, fg1: Double, bg0: Double, bg1: Double): ContrastResult =
        callFor("analyze.contrast", "foreground" to mapOf("t0" to fg0, "t1" to fg1), "background" to mapOf("t0" to bg0, "t1" to bg1))

    // ----- import / export -----------------------------------------------------
    override suspend fun importFormats(): ImportFormats = callFor("import.formats")
    override suspend fun importFiles(paths: List<String>, newProject: Boolean): ImportResult =
        callFor("import.files", "paths" to paths, "newProject" to newProject)
    override suspend fun exportFormats(): List<ExportFormat> = callFor<ExportFormatList>("export.formats").formats
    override suspend fun exportDefaults(formatKey: String): ExportDefaults = callFor("export.defaults", "formatKey" to formatKey)
    override suspend fun exportOptions(formatKey: String): ExportOptions = callFor("export.options", "formatKey" to formatKey)
    override suspend fun setExportOption(formatKey: String, id: Int, value: ExportValue): ExportOptions =
        callFor("export.setOption", "formatKey" to formatKey, "id" to id,
            "value" to EngineJson.json.encodeToJsonElement(ExportValue.serializer(), value))
    override suspend fun export(path: String, formatKey: String, range: String, channels: Int, rate: Int, skipSilenceAtStart: Boolean): String =
        callFor<PathResult>("export.run", "path" to path, "formatKey" to formatKey, "range" to range,
            "channels" to channels, "rate" to rate, "skipSilenceAtStart" to skipSilenceAtStart).path

    // ----- transport / audio ---------------------------------------------------
    override suspend fun play(loop: Boolean, t0: Double?, t1: Double?) { call("transport.play", "loop" to loop, "t0" to t0, "t1" to t1) }
    override suspend fun stop() { call("transport.stop") }
    override suspend fun pause() { call("transport.pause") }
    override suspend fun record(newTrack: Boolean) { call("transport.record", "newTrack" to newTrack) }
    override suspend fun seek(t: Double) { call("transport.seek", "t" to t) }
    override suspend fun skipToStart() { call("transport.skipToStart") }
    override suspend fun skipToEnd() { call("transport.skipToEnd") }
    override suspend fun monitor(enabled: Boolean) { call("transport.monitor", "enabled" to enabled) }
    override suspend fun audioDevices(): AudioDevices = callFor("audio.devices")
    override suspend fun setAudioDevices(devices: List<AudioDeviceSpec>): Boolean =
        callFor<SetDevicesResult>("audio.setDevices",
            "devices" to EngineJson.json.encodeToJsonElement(ListSerializer(AudioDeviceSpec.serializer()), devices)).applied
    override suspend fun setRecordPermission(granted: Boolean) { call("audio.permission", "recordPermission" to granted) }
    override suspend fun latency(): LatencyInfo = callFor("audio.latency")

    // ----- display ---------------------------------------------------------------
    override suspend fun setViewportWidth(px: Int) { call("display.setViewportWidth", "px" to px) }
    override suspend fun trimDisplayCaches(budgetBytes: Long) { call("display.trimCaches", "budgetBytes" to budgetBytes) }

    override suspend fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int): WaveTile? {
        if (!loaded || count <= 0) return null
        return display(null) {
            val out = FloatArray(3 * count)
            val r = bridge.waveColumns(trackId, channel, zoomLevel, firstColumn, count, out)
            if (r < 0) null else WaveTile(firstColumn, count, out, DisplayStatus.version(r), DisplayStatus.isPartial(r))
        }
    }

    override suspend fun waveColumnsInto(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        if (!loaded) return DisplayStatus.NOT_READY
        require(count >= 0 && out.size >= 3 * count) { "out must hold 3 * count floats" }
        return display(DisplayStatus.NOT_READY) { bridge.waveColumns(trackId, channel, zoomLevel, firstColumn, count, out) }
    }

    override suspend fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int): FloatArray? {
        if (!loaded || count <= 0) return null
        return display(null) {
            val out = FloatArray(count)
            if (bridge.envelopeColumns(trackId, zoomLevel, firstColumn, count, out) < 0) null else out
        }
    }

    override suspend fun envelopeColumnsInto(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        if (!loaded) return DisplayStatus.NOT_READY
        require(count >= 0 && out.size >= count) { "out must hold count floats" }
        return display(DisplayStatus.NOT_READY) { bridge.envelopeColumns(trackId, zoomLevel, firstColumn, count, out) }
    }

    override suspend fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): List<SampleRun>? {
        if (!loaded) return null
        val bytes = display(null) { bridge.waveSamples(trackId, channel, t0, t1) } ?: return null
        return EngineProtocol.decodeWaveSamples(bytes)
    }

    override suspend fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int): ByteArray? {
        if (!loaded || count <= 0 || rows <= 0) return null
        return display(null) {
            val out = ByteArray(count * rows)
            if (bridge.spectrogramColumns(trackId, channel, zoomLevel, firstColumn, count, rows, out) < 0) null else out
        }
    }

    /** Runs [block] on the display thread; a missing JNI entry point yields [fallback]. */
    private suspend fun <T> display(fallback: T, block: () -> T): T = withContext(displayDispatcher) {
        try {
            block()
        } catch (e: LinkageError) {
            fallback
        }
    }

    private companion object {
        const val TAG = "AudacityEngine"

        fun daemon(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

        fun contextStarter(context: Context): suspend ((String) -> Unit) -> StartConfig = { warn ->
            try {
                val installer = AssetInstaller.forContext(context)
                installer.installIfNeeded(AssetInstaller.versionStamp(context))
            } catch (e: java.io.IOException) {
                // Nyquist effects are optional: the engine reports the
                // nyquistRuntime self-check as failed and still starts.
                warn("extracting the Nyquist runtime and plug-ins failed: ${e.message}")
            }
            StartConfigs.fromContext(context)
        }
    }
}
