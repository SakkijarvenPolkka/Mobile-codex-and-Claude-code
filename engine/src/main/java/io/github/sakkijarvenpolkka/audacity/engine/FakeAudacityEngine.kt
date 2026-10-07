/*
 * Audacity Android port — in-memory AudacityEngine.
 *
 * Used by Compose previews, Robolectric/JVM tests and builds without the
 * native core. It implements the whole interface with believable behaviour:
 * tracks with deterministic procedural audio, clips, labels, selection, the
 * command-flag bitset of API.md §4.2, an undo/redo stack of model
 * snapshots, clipboard, mixer controls, the 3.7.9 effect catalogue with real
 * parameter schemas, export formats/options, a clock-driven transport
 * (play head, recording that appends audio, animated meters) and display
 * data computed from the audio with the zoom-level contract of API.md §7.
 *
 * Commands are serialized (like the native engine thread); realtime and
 * display reads only take a short lock.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.fake.Dsp
import io.github.sakkijarvenpolkka.audacity.engine.fake.Edits
import io.github.sakkijarvenpolkka.audacity.engine.fake.ExportEditor
import io.github.sakkijarvenpolkka.audacity.engine.fake.FClip
import io.github.sakkijarvenpolkka.audacity.engine.fake.FLabel
import io.github.sakkijarvenpolkka.audacity.engine.fake.FModel
import io.github.sakkijarvenpolkka.audacity.engine.fake.FTrack
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeClipboard
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeDemo
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeFormats
import io.github.sakkijarvenpolkka.audacity.engine.fake.FxCatalog
import io.github.sakkijarvenpolkka.audacity.engine.fake.FxContext
import io.github.sakkijarvenpolkka.audacity.engine.fake.FxDef
import io.github.sakkijarvenpolkka.audacity.engine.fake.FxImpl
import io.github.sakkijarvenpolkka.audacity.engine.fake.Mixer
import io.github.sakkijarvenpolkka.audacity.engine.fake.P
import io.github.sakkijarvenpolkka.audacity.engine.fake.Stats
import io.github.sakkijarvenpolkka.audacity.engine.fake.Versions
import io.github.sakkijarvenpolkka.audacity.engine.fake.Wav
import io.github.sakkijarvenpolkka.audacity.engine.model.AppInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioBusyState
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevice
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipboardInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipboardState
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.ContrastResult
import io.github.sakkijarvenpolkka.audacity.engine.model.CurrentDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectApplyResult
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectPresets
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryList
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryState
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportFormats
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportResult
import io.github.sakkijarvenpolkka.audacity.engine.model.LabelState
import io.github.sakkijarvenpolkka.audacity.engine.model.LastEffect
import io.github.sakkijarvenpolkka.audacity.engine.model.LatencyInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.MeterSample
import io.github.sakkijarvenpolkka.audacity.engine.model.PlayRegionState
import io.github.sakkijarvenpolkka.audacity.engine.model.ProgressEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectState
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.SpectrumResult
import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import io.github.sakkijarvenpolkka.audacity.engine.model.ViewState
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FakeAudacityEngine(private val config: FakeConfig = FakeConfig()) : AudacityEngine {

    override val isFake: Boolean get() = true

    private val hub = EngineEventHub(EngineStatus.Ready(config.recoverable.size))
    private val lock = Any()
    private val commandMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ----- project state (guarded by lock) ----------------------------------
    private class HistEntry(val model: FModel, val description: String, val short: String)

    private class StoredProject(val name: String, val modifiedMs: Long, factory: () -> FModel) {
        val model: FModel by lazy(factory)
        val sizeBytes: Long get() = 65536L + model.tracks.sumOf { t -> t.clips.sumOf { 4L * it.length * it.channels } }
    }

    private var projectOpen = false
    private var projectName = ""
    private var projectPath: String? = null
    private var temporary = true
    private var recovered = false
    private var projectRate = 44100.0
    private var work = FModel()
    private val history = ArrayList<HistEntry>()
    private var historyIndex = 0
    private var savedIndex = 0
    private var generation = 0L
    private var zoom = DEFAULT_ZOOM
    private var hpos = 0.0
    private var playRegion = PlayRegionState()
    private var clipboard: FakeClipboard? = null
    private var nextTrackId = 1L
    private var lastPickedTrack: Long? = null
    private var lastEffect: FxDef? = null
    private var lastGenerator: FxDef? = null
    private var lastAnalyzer: FxDef? = null
    private var lastTool: FxDef? = null
    private var settings = DEFAULT_SETTINGS
    private var recordPermission = config.recordPermission
    private var viewportWidth = 0
    private val storage = LinkedHashMap<String, StoredProject>()
    private val recoverable = config.recoverable.toMutableList()

    private val effects: List<FxDef> = FxCatalog.build(config.pluginsDir)
    private val effectsById = effects.associateBy { it.id }
    private val effectValues = HashMap<String, MutableMap<String, JsonPrimitive>>()
    private val effectDurations = HashMap<String, Double>()
    private val userPresets = HashMap<String, LinkedHashMap<String, Pair<Map<String, JsonPrimitive>, EqCurve?>>>()
    private val eqCurves = HashMap<String, EqCurve>()
    private var noiseProfileRms: Double? = null
    private val exportEditors = HashMap<String, ExportEditor>()

    private var nextProgressId = 1
    private var nextDialogId = 1
    private val cancelRequests = ConcurrentHashMap<Int, Boolean>()
    private val dialogWaiters = ConcurrentHashMap<Int, CompletableDeferred<Int>>()

    // ----- transport simulation (guarded by lock) ----------------------------
    private enum class TState(val code: Int, val wire: String) {
        STOPPED(0, "stopped"), PLAYING(1, "playing"), RECORDING(2, "recording"),
        PAUSED_PLAY(3, "paused"), PAUSED_RECORD(4, "paused"), MONITORING(5, "monitoring"),
    }

    private class Recording(
        val targetId: Long,
        val newTrack: Boolean,
        val trackName: String,
        val clipName: String,
        val channels: Int,
        val rate: Double,
        val start: Double,
        var buffers: Array<FloatArray>,
        var frames: Int = 0,
        val version: Long = Versions.next(),
        var lastSnapshotNanos: Long = 0,
    )

    private var tState = TState.STOPPED
    private var anchorPos = 0.0
    private var anchorNanos = 0L
    private var playEnd = 0.0
    private var looping = false
    private var loopT0 = 0.0
    private var loopT1 = 0.0
    private var previewTracks: List<FTrack>? = null
    private var recording: Recording? = null
    private var micFrame = 0L
    private var playClip = BooleanArray(2)
    private var recClip = BooleanArray(2)
    private var lastMeterNanos = 0L
    private var tickerJob: Job? = null

    init {
        synchronized(lock) {
            if (config.demoProject) seedStorage()
            if (recoverable.isEmpty()) {
                if (config.demoProject) openModel(demoModel(), "Demo", null, "Created new project")
                else openModel(FModel(), "Untitled", null, "Created new project")
            }
            publish()
        }
    }

    /** Stops the background ticker (call when a test or preview is done). */
    fun dispose() {
        scope.cancel()
    }

    // ----- flows ---------------------------------------------------------------
    override val status: StateFlow<EngineStatus> get() = hub.status
    override val snapshot: StateFlow<Snapshot> get() = hub.snapshot
    override val progress: StateFlow<Map<Int, ProgressEvent>> get() = hub.progress
    override val dialogs: SharedFlow<DialogEvent> get() = hub.dialogs
    override val transportEvents: SharedFlow<TransportEvent> get() = hub.transportEvents
    override val logs: SharedFlow<LogEvent> get() = hub.logs
    override val transportState: StateFlow<TransportEvent> get() = hub.transportState
    override val pendingDialogs: StateFlow<List<DialogEvent>> get() = hub.pendingDialogs

    override suspend fun start() {
        hub.log(LogEvent("info", "fake engine: ${effects.size} effects, ${FakeFormats.FORMATS.size} export formats"))
    }

    override fun replyDialog(dialogId: Int, button: Int) {
        hub.resolveDialog(dialogId)
        dialogWaiters.remove(dialogId)?.complete(button)
    }

    override fun cancelProgress(progressId: Int, stop: Boolean) {
        cancelRequests[progressId] = stop
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    private fun now(): Long = config.clock()

    private fun fail(code: String, message: String): Nothing = throw EngineException(code, message)

    private fun isBusy() = tState != TState.STOPPED && tState != TState.MONITORING

    private suspend fun <T> cmd(needsProject: Boolean = true, allowBusy: Boolean = false, block: () -> T): T =
        commandMutex.withLock { locked(needsProject, allowBusy, block) }

    private fun <T> locked(needsProject: Boolean = true, allowBusy: Boolean = false, block: () -> T): T = synchronized(lock) {
        advance(now())
        if (needsProject && !projectOpen) fail(ErrorCodes.NO_PROJECT, "No project is open")
        if (!allowBusy && isBusy()) {
            fail(ErrorCodes.AUDIO_BUSY, "You can only do this when playing and recording are stopped. (Pausing is not sufficient.)")
        }
        block()
    }

    /** Emits progress events for a long operation; returns true when the
     *  user asked to stop (keep the partial result), throws CANCELLED. */
    private suspend fun simulateProgress(title: String, stoppable: Boolean = false, millis: Long = config.longOperationMillis): Boolean {
        val id = synchronized(lock) { nextProgressId++ }
        hub.beginProgress(ProgressEvent(id, "begin", title, "", 0.0, cancellable = true, stoppable = stoppable))
        try {
            val steps = 10
            for (s in 1..steps) {
                if (millis > 0) delay(millis / steps)
                val request = cancelRequests.remove(id)
                if (request != null) {
                    if (request && stoppable) return true
                    fail(ErrorCodes.CANCELLED, "$title cancelled")
                }
                hub.updateProgress(id, s.toDouble() / steps)
            }
            return false
        } finally {
            hub.endProgress(id)
            cancelRequests.remove(id)
        }
    }

    private fun fmt(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)

    // ----- history ---------------------------------------------------------------
    private fun openModel(model: FModel, name: String, path: String?, description: String) {
        work = model
        history.clear()
        history += HistEntry(model, description, description)
        historyIndex = 0
        savedIndex = 0
        projectOpen = true
        projectName = name
        projectPath = path
        temporary = path == null
        recovered = false
        zoom = DEFAULT_ZOOM
        hpos = 0.0
        playRegion = PlayRegionState()
        nextTrackId = max(nextTrackId, (model.tracks.maxOfOrNull { it.id } ?: 0L) + 1)
        generation++
    }

    private fun pushState(description: String, short: String, consolidate: Boolean = false) {
        while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
        if (consolidate && history.size > 1 && history[historyIndex].short == short && historyIndex > 0 && savedIndex != historyIndex) {
            history[historyIndex] = HistEntry(work, description, short)
        } else {
            history += HistEntry(work, description, short)
            historyIndex++
        }
        if (savedIndex > historyIndex) savedIndex = -1
        generation++
        publish()
    }

    /** Changes the current undo state without a new entry (mute, solo). */
    private fun modifyState() {
        val e = history[historyIndex]
        history[historyIndex] = HistEntry(work, e.description, e.short)
        generation++
        publish()
    }

    private fun publish() {
        hub.setSnapshot(buildSnapshot())
    }

    private fun newTrackId(): Long = nextTrackId++

    private fun uniqueTrackName(base: String, extra: Collection<String> = emptyList()): String {
        val used = work.tracks.map { it.name }.toHashSet() + extra
        var n = 1
        while ("$base $n" in used) n++
        return "$base $n"
    }

    // ----- snapshot ----------------------------------------------------------------
    /** Tracks including the pending recording (synthetic id -2 for a new track). */
    private fun effectiveTracks(): List<FTrack> {
        val rec = recording ?: return work.tracks
        val clip = recordingClip(rec)
        return if (rec.newTrack) {
            work.tracks.map { if (it.selected) it.copy(selected = false) else it } +
                FTrack(rec.targetId, FTrack.WAVE, rec.trackName, selected = true, channels = rec.channels, rate = rec.rate,
                    clips = if (clip != null) listOf(clip) else emptyList())
        } else {
            work.tracks.map { if (it.id == rec.targetId && clip != null) it.withClips(it.clips + clip) else it }
        }
    }

    private fun recordingClip(rec: Recording): FClip? =
        if (rec.frames <= 0) null else FClip(rec.clipName, rec.start, rec.rate, rec.buffers, rec.frames, rec.version)

    private fun buildSnapshot(): Snapshot {
        val cb = clipboard
        val clipboardState = ClipboardState(cb == null, cb?.duration ?: 0.0)
        if (!projectOpen) {
            return Snapshot(
                generation = generation,
                project = ProjectState(open = false, name = "", path = null, temporary = true, dirty = false,
                    rate = (settings.defaultRate ?: 44100).toDouble(), defaultFormat = settings.defaultFormat ?: "float"),
                clipboard = clipboardState,
                flags = computeFlags(emptyList()),
            )
        }
        val tracks = effectiveTracks()
        val canUndo = historyIndex > 0 && recording == null
        val canRedo = historyIndex < history.size - 1 && recording == null
        return Snapshot(
            generation = generation,
            project = ProjectState(open = true, name = projectName, path = projectPath, temporary = temporary,
                dirty = historyIndex != savedIndex || (temporary && recovered), rate = projectRate,
                defaultFormat = settings.defaultFormat ?: "float"),
            tracks = tracks.map { trackState(it) },
            selection = work.selection,
            playRegion = playRegion,
            history = HistoryState(canUndo, canRedo,
                if (canUndo) history[historyIndex].short else "", if (canRedo) history[historyIndex + 1].short else ""),
            view = ViewState(zoom, hpos),
            audio = AudioBusyState(isBusy()),
            clipboard = clipboardState,
            lastEffect = lastEffect?.let { LastEffect(it.id, it.name) },
            lastGenerator = lastGenerator?.let { LastEffect(it.id, it.name) },
            lastAnalyzer = lastAnalyzer?.let { LastEffect(it.id, it.name) },
            lastTool = lastTool?.let { LastEffect(it.id, it.name) },
            flags = computeFlags(tracks),
        )
    }

    private fun trackState(t: FTrack): TrackState = if (t.isWave) {
        TrackState(
            id = t.id, kind = t.kind, name = t.name, selected = t.selected, focused = t.id == work.focusedId,
            channels = t.channels, rate = t.rate, format = t.format, gain = t.gain, pan = t.pan, mute = t.mute, solo = t.solo,
            start = if (t.clips.isEmpty()) 0.0 else t.start, end = if (t.clips.isEmpty()) 0.0 else t.end,
            waveVersion = t.waveVersion,
            clips = t.clips.mapIndexed { i, c -> ClipState(i, c.name, c.start, c.end, 0.0, 0.0, 1.0, c.rate) },
        )
    } else {
        TrackState(
            id = t.id, kind = t.kind, name = t.name, selected = t.selected, focused = t.id == work.focusedId,
            labels = t.labels.mapIndexed { i, l -> LabelState(i, l.t0, l.t1, l.title) },
        )
    }

    /** Port of the bridge's ComputeCommandFlags (API.md §4.2). */
    private fun computeFlags(tracks: List<FTrack>): Long {
        var f = CommandFlags.FOC or CommandFlags.CS
        if (recordPermission) f = f or CommandFlags.RECORD_PERMISSION
        if (clipboard != null) f = f or CommandFlags.CLIPBOARD
        if (tState == TState.PAUSED_PLAY || tState == TState.PAUSED_RECORD) f = f or CommandFlags.PAUSED
        if (!(tState == TState.RECORDING || tState == TState.PAUSED_RECORD)) f = f or CommandFlags.CNB
        if (!projectOpen) return f or CommandFlags.NB or CommandFlags.NSL or CommandFlags.NO_TIMETRACK
        f = f or CommandFlags.PROJECT_OPEN
        val busy = isBusy()
        f = f or if (busy) CommandFlags.BUSY else CommandFlags.NB
        val sel = work.selection
        val ts = sel.t1 > sel.t0
        if (ts) f = f or CommandFlags.TS
        val selected = tracks.filter { it.selected }
        val selectedWaves = selected.filter { it.isWave }
        if (selectedWaves.isNotEmpty()) f = f or CommandFlags.WS
        if (tracks.isNotEmpty()) f = f or CommandFlags.TE
        if (selected.isNotEmpty()) f = f or CommandFlags.ES or CommandFlags.AS
        if (tracks.any { it.isLabel }) f = f or CommandFlags.LE
        if (historyIndex > 0 && recording == null) f = f or CommandFlags.UA
        if (historyIndex < history.size - 1 && recording == null) f = f or CommandFlags.RA
        if (tracks.isNotEmpty()) {
            if (zoom < 6_000_000.0) f = f or CommandFlags.ZI
            if (zoom > 0.001) f = f or CommandFlags.ZO
        }
        if (tracks.any { it.isWave }) f = f or CommandFlags.WE or CommandFlags.PLAYABLE
        f = f or CommandFlags.NSL
        if (selectedWaves.any { it.channels > 1 }) f = f or CommandFlags.ST
        if (ts && selected.isNotEmpty()) f = f or CommandFlags.CC
        if (!busy && ts && selectedWaves.any { it.clipsIntersecting(sel.t0, sel.t1).size > 1 }) f = f or CommandFlags.JC
        if (selected.any { t -> t.isLabel && t.labels.any { it.t0 >= sel.t0 && it.t1 <= sel.t1 } }) f = f or CommandFlags.LS
        if (tracks.any { it.isWave && it.clips.isNotEmpty() && it.end > it.start }) f = f or CommandFlags.HW
        if (lastEffect != null) f = f or CommandFlags.LAST_EFF
        if (lastGenerator != null) f = f or CommandFlags.LAST_GEN
        if (lastAnalyzer != null) f = f or CommandFlags.LAST_ANA
        if (lastTool != null) f = f or CommandFlags.LAST_TOOL
        if (tracks.any { it.id == work.focusedId }) f = f or CommandFlags.TFOCUS
        f = f or CommandFlags.NO_TIMETRACK
        return f
    }

    // ----- demo content ----------------------------------------------------------------
    private fun demoModel(): FModel {
        val rate = 44100.0
        val a1 = FTrack(newTrackId(), FTrack.WAVE, "Audio 1", selected = true, channels = 1, rate = rate, clips = listOf(
            FClip("Audio 1 #1", 0.0, rate, arrayOf(FakeDemo.melody(rate, 8.0))),
            FClip("Audio 1 #2", 10.0, rate, arrayOf(FakeDemo.voice(rate, 4.0, 7))),
        ))
        val a2 = FTrack(newTrackId(), FTrack.WAVE, "Audio 2", channels = 2, rate = rate, gain = 0.8,
            clips = listOf(FClip("Audio 2 #1", 0.0, rate, FakeDemo.drums(rate, 12.0))))
        val labels = FTrack(newTrackId(), FTrack.LABEL, "Label 1",
            labels = listOf(FLabel(0.0, 2.0, "Intro"), FLabel(8.0, 8.0, "Break"), FLabel(10.0, 14.0, "Voice")))
        return FModel(listOf(a1, a2, labels), TimeRange(2.0, 4.0), a1.id,
            listOf(Tag("TITLE", "Demo"), Tag("ARTIST", "Audacity for Android")))
    }

    private fun interviewModel(): FModel {
        val rate = 44100.0
        val v = FTrack(newTrackId(), FTrack.WAVE, "Interview", channels = 1, rate = rate,
            clips = listOf(FClip("Interview #1", 0.0, rate, arrayOf(FakeDemo.voice(rate, 20.0, 3)))))
        return FModel(listOf(v), TimeRange(), v.id, listOf(Tag("TITLE", "Interview")))
    }

    private fun seedStorage() {
        val dir = config.projectsDir
        storage["$dir/Demo song.aup3"] = StoredProject("Demo song", 1_767_225_600_000L) { demoModel() }
        storage["$dir/Interview.aup3"] = StoredProject("Interview", 1_767_312_000_000L) { interviewModel() }
    }

    // =========================================================================
    // Transport simulation
    // =========================================================================

    private fun position(nowNanos: Long): Double {
        if (tState == TState.PAUSED_PLAY || tState == TState.PAUSED_RECORD) return anchorPos
        var p = anchorPos + (nowNanos - anchorNanos) / 1e9
        if (looping && loopT1 > loopT0 && p >= loopT1) p = loopT0 + (p - loopT0) % (loopT1 - loopT0)
        return p
    }

    private fun anchor(pos: Double, nowNanos: Long) {
        anchorPos = pos
        anchorNanos = nowNanos
    }

    /** Advances the simulation to [nowNanos] (lock held). */
    private fun advance(nowNanos: Long) {
        when (tState) {
            TState.PLAYING -> if (!looping && position(nowNanos) >= playEnd) {
                anchor(playEnd, nowNanos)
                finishTransport("end")
            }
            TState.RECORDING -> {
                val rec = recording ?: return
                appendRecording(rec, position(nowNanos))
                if (nowNanos - rec.lastSnapshotNanos >= 200_000_000L) {
                    rec.lastSnapshotNanos = nowNanos
                    generation++
                    publish()
                }
            }
            else -> {}
        }
    }

    private fun appendRecording(rec: Recording, pos: Double) {
        val target = floor((pos - rec.start) * rec.rate).toLong().coerceIn(0L, Int.MAX_VALUE / 2L).toInt()
        if (target <= rec.frames) return
        if (target > rec.buffers[0].size) {
            val cap = max(target, rec.buffers[0].size * 2)
            rec.buffers = Array(rec.channels) { rec.buffers[it].copyOf(cap) }
        }
        for (i in rec.frames until target) {
            for (ch in 0 until rec.channels) rec.buffers[ch][i] = FakeDemo.microphone(micFrame + i, rec.rate, ch)
        }
        rec.frames = target
    }

    private fun startTransport(state: TState, pos: Double, nowNanos: Long) {
        tState = state
        anchor(pos, nowNanos)
        lastMeterNanos = nowNanos
        hub.setTransport(TransportEvent(state.wire, "user"))
        publish()
        ensureTicker()
    }

    /** Ends playback/preview/monitoring/recording (lock held). */
    private fun finishTransport(reason: String) {
        val rec = recording
        if (rec != null) {
            recording = null
            micFrame += rec.frames
            tState = TState.STOPPED
            commitRecording(rec)
        }
        tState = TState.STOPPED
        previewTracks = null
        looping = false
        hub.setTransport(TransportEvent("stopped", reason))
        publish()
    }

    private fun commitRecording(rec: Recording) {
        if (rec.frames <= 0) return
        val clip = FClip(rec.clipName, rec.start, rec.rate, Array(rec.channels) { rec.buffers[it].copyOf(rec.frames) })
        work = if (rec.newTrack) {
            val id = newTrackId()
            work.copy(
                tracks = work.tracks.map { it.copy(selected = false) } +
                    FTrack(id, FTrack.WAVE, rec.trackName, selected = true, channels = rec.channels, rate = rec.rate, clips = listOf(clip)),
                focusedId = id,
            )
        } else {
            work.mapTracks { if (it.id == rec.targetId) it.withClips(it.clips + clip) else it }
        }
        pushState("Recorded Audio", "Record")
    }

    private fun ensureTicker() {
        if (!config.autoTick || tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val active = synchronized(lock) {
                    advance(now())
                    tState == TState.PLAYING || tState == TState.RECORDING
                }
                if (!active) break
            }
        }
    }

    override fun readTransport(): TransportSample = synchronized(lock) {
        val t = now()
        advance(t)
        val active = tState != TState.STOPPED && tState != TState.MONITORING
        val pos = if (active) position(t) else Double.NaN
        TransportSample(
            state = tState.code,
            streamTime = pos,
            displayTime = pos,
            sampledAtNanos = t,
            loopT0 = loopT0,
            loopT1 = loopT1,
            looping = looping,
            deviceRate = if (tState == TState.STOPPED) 0.0 else DEVICE_RATE,
            outputLatency = OUTPUT_LATENCY,
            inputLatency = INPUT_LATENCY,
            capturing = tState == TState.RECORDING,
            speed = 1.0,
            recordingStart = recording?.start ?: 0.0,
            generation = generation,
        )
    }

    override fun readMeters(): MeterSample? = synchronized(lock) {
        val t = now()
        advance(t)
        val window = ((t - lastMeterNanos) / 1e9).coerceIn(0.01, 0.1)
        lastMeterNanos = t
        val a = FloatArray(MeterSample.SIZE)
        val playing = tState == TState.PLAYING
        if (playing) {
            val pos = position(t)
            val tracks = previewTracks ?: Mixer.audible(work.tracks)
            val frames = (window * METER_RATE).toInt().coerceAtLeast(1)
            val mix = Mixer.mix(tracks, max(0.0, pos - window), METER_RATE, frames, 2)
            for (ch in 0..1) {
                val pk = Dsp.peak(mix[ch])
                a[ch] = pk
                a[2 + ch] = Dsp.rms(mix[ch]).toFloat()
                if (pk >= 1f) playClip[ch] = true
                a[4 + ch] = if (playClip[ch]) 1f else 0f
            }
        }
        if (tState == TState.PLAYING || tState == TState.PAUSED_PLAY) a[12] = 2f
        val capturing = tState == TState.RECORDING || tState == TState.MONITORING
        if (capturing) {
            val channels = (settings.recordChannels ?: 1).coerceIn(1, 2)
            val frames = (window * METER_RATE).toInt().coerceAtLeast(1)
            val startFrame = ((t / 1e9) * METER_RATE).toLong()
            for (ch in 0 until channels) {
                var pk = 0f
                var ss = 0.0
                for (i in 0 until frames) {
                    val v = abs(FakeDemo.microphone(startFrame + i, METER_RATE, ch))
                    if (v > pk) pk = v
                    ss += v.toDouble() * v
                }
                a[6 + ch] = pk
                a[8 + ch] = sqrt(ss / frames).toFloat()
                if (pk >= 1f) recClip[ch] = true
                a[10 + ch] = if (recClip[ch]) 1f else 0f
            }
            if (channels == 1) { a[7] = a[6]; a[9] = a[8]; a[11] = a[10] }
            a[13] = channels.toFloat()
        } else if (tState == TState.PAUSED_RECORD) {
            a[13] = (settings.recordChannels ?: 1).toFloat()
        }
        MeterSample.decode(a)
    }

    // =========================================================================
    // app / settings
    // =========================================================================

    override suspend fun appInfo(): AppInfo = cmd(needsProject = false, allowBusy = true) {
        AppInfo(
            audacityVersion = "3.7.9",
            engineVersion = "fake-1",
            wxVersion = "3.2.8",
            sqliteVersion = "3.46.1",
            abi = System.getProperty("os.arch") ?: "",
            libraries = listOf("lib-audio-io", "lib-builtin-effects", "lib-import-export", "lib-project-file-io",
                "lib-wave-track", "lib-label-track", "lib-nyquist-effects"),
            importers = FakeFormats.IMPORT.groups.map { it.description },
            exporters = FakeFormats.FORMATS.map { it.key },
            effectsCount = effects.size,
            nyquist = true,
        )
    }

    override suspend fun getSettings(): Settings = cmd(needsProject = false, allowBusy = true) { settings }

    override suspend fun setSettings(partial: Settings): Settings = cmd(needsProject = false, allowBusy = true) {
        partial.defaultRate?.let { if (it < 1 || it > 1_000_000) fail(ErrorCodes.INVALID_ARGS, "defaultRate out of range") }
        partial.defaultFormat?.let { if (it !in FORMATS) fail(ErrorCodes.INVALID_ARGS, "unknown sample format '$it'") }
        partial.recordChannels?.let { if (it !in 1..2) fail(ErrorCodes.INVALID_ARGS, "recordChannels must be 1 or 2") }
        partial.soloMode?.let { if (it != "Simple" && it != "Multi") fail(ErrorCodes.INVALID_ARGS, "soloMode must be Simple or Multi") }
        for (d in listOfNotNull(partial.realtimeDither, partial.hqDither)) {
            if (d !in DITHERS) fail(ErrorCodes.INVALID_ARGS, "unknown dither '$d'")
        }
        partial.effectsGroupBy?.let { if (it !in GROUP_BY) fail(ErrorCodes.INVALID_ARGS, "unknown effectsGroupBy '$it'") }
        for (v in listOfNotNull(partial.latencyMs, partial.latencyCorrectionMs, partial.preRollSec, partial.crossfadeMs)) {
            if (!v.isFinite()) fail(ErrorCodes.INVALID_ARGS, "non-finite setting")
        }
        val s = settings
        settings = Settings(
            defaultRate = partial.defaultRate ?: s.defaultRate,
            defaultFormat = partial.defaultFormat ?: s.defaultFormat,
            recordChannels = partial.recordChannels ?: s.recordChannels,
            outputDevice = partial.outputDevice ?: s.outputDevice,
            inputDevice = partial.inputDevice ?: s.inputDevice,
            latencyMs = partial.latencyMs ?: s.latencyMs,
            latencyCorrectionMs = partial.latencyCorrectionMs ?: s.latencyCorrectionMs,
            overdub = partial.overdub ?: s.overdub,
            swPlaythrough = partial.swPlaythrough ?: s.swPlaythrough,
            preRollSec = partial.preRollSec ?: s.preRollSec,
            crossfadeMs = partial.crossfadeMs ?: s.crossfadeMs,
            realtimeDither = partial.realtimeDither ?: s.realtimeDither,
            hqDither = partial.hqDither ?: s.hqDither,
            effectsGroupBy = partial.effectsGroupBy ?: s.effectsGroupBy,
            soloMode = partial.soloMode ?: s.soloMode,
            editClipsCanMove = partial.editClipsCanMove ?: s.editClipsCanMove,
            selectAllOnNone = partial.selectAllOnNone ?: s.selectAllOnNone,
        )
        publish()
        settings
    }

    // =========================================================================
    // project
    // =========================================================================

    private fun closeLocked() {
        projectOpen = false
        projectName = ""
        projectPath = null
        temporary = true
        recovered = false
        work = FModel()
        history.clear()
        historyIndex = 0
        savedIndex = 0
        clipboard = null
        playRegion = PlayRegionState()
        lastPickedTrack = null
        generation++
    }

    private fun newProjectLocked() {
        if (projectOpen) closeLocked()
        openModel(FModel(), "Untitled", null, "Created new project")
        projectRate = (settings.defaultRate ?: 44100).toDouble()
    }

    override suspend fun newProject() = cmd(needsProject = false) {
        newProjectLocked()
        publish()
    }

    private fun baseName(path: String) = File(path).name.removeSuffix(".aup3")

    override suspend fun openProject(path: String) {
        commandMutex.withLock {
            val stored = locked(needsProject = false) {
                if (!path.endsWith(".aup3")) fail(ErrorCodes.INVALID_ARGS, "'${File(path).name}' is not an Audacity project (use import)")
                if (path.endsWith("~.aup3")) fail(ErrorCodes.INVALID_ARGS, "'${File(path).name}' is a safety backup")
                if (projectOpen && path == projectPath) fail(ErrorCodes.FAILED, "The project is already open")
                storage[path] ?: if (File(path).exists()) fail(ErrorCodes.INVALID_ARGS, "'${File(path).name}' cannot be read by the fake engine")
                else fail(ErrorCodes.NOT_FOUND, "Could not open '${File(path).name}': file not found")
            }
            simulateProgress("Loading ${stored.name}")
            synchronized(lock) {
                if (projectOpen) closeLocked()
                openModel(stored.model, stored.name, path, "Project opened")
                publish()
            }
        }
    }

    override suspend fun saveProject(): String = commandMutex.withLock {
        val path = locked { projectPath ?: fail(ErrorCodes.NEEDS_PATH, "The project was never saved: use Save As") }
        simulateProgress("Saving project")
        synchronized(lock) { storeLocked(path, current = true); path }
    }

    private fun storeLocked(path: String, current: Boolean) {
        val model = work
        storage[path] = StoredProject(baseName(path), System.currentTimeMillis()) { model }
        if (current) {
            projectPath = path
            projectName = baseName(path)
            temporary = false
            recovered = false
            savedIndex = historyIndex
            publish()
        }
    }

    override suspend fun saveProjectAs(path: String): String = commandMutex.withLock {
        locked {
            if (!path.endsWith(".aup3")) fail(ErrorCodes.INVALID_ARGS, "the project path must end with .aup3")
            if (path != projectPath && storage.containsKey(path)) {
                fail(ErrorCodes.FAILED, "The project was not saved because the file name provided would overwrite another project.\nPlease try again and select an original name.")
            }
        }
        simulateProgress("Saving project")
        synchronized(lock) { storeLocked(path, current = true); path }
    }

    override suspend fun saveProjectCopy(path: String): String = commandMutex.withLock {
        locked {
            if (!path.endsWith(".aup3")) fail(ErrorCodes.INVALID_ARGS, "the project path must end with .aup3")
            if (path == projectPath) fail(ErrorCodes.FAILED, "Cannot save a copy over the open project")
        }
        simulateProgress("Saving a copy of the project")
        synchronized(lock) { storeLocked(path, current = false); path }
    }

    override suspend fun closeProject() = cmd(needsProject = false) {
        if (projectOpen) closeLocked()
        publish()
    }

    override suspend fun projectInfo(): ProjectInfo = cmd(needsProject = false, allowBusy = true) { projectInfoLocked() }

    private fun projectInfoLocked(): ProjectInfo {
        val s = buildSnapshot().project
        return ProjectInfo(s.open, s.name, s.path, s.temporary, s.dirty, s.rate, s.defaultFormat,
            if (projectOpen) work.end else 0.0, if (projectOpen) work.tracks.size else 0)
    }

    override suspend fun refreshSnapshot() = cmd(needsProject = false, allowBusy = true) { publish() }

    override suspend fun setProjectRate(rate: Int) = cmd(allowBusy = true) {
        if (rate < 1 || rate > 1_000_000) fail(ErrorCodes.INVALID_ARGS, "rate out of range")
        projectRate = rate.toDouble()
        publish()
    }

    override suspend fun recoverableProjects(): List<ProjectFileEntry> = cmd(needsProject = false) { recoverable.toList() }

    override suspend fun recoverProject(path: String) {
        commandMutex.withLock {
            val entry = locked(needsProject = false) {
                recoverable.firstOrNull { it.path == path } ?: fail(ErrorCodes.NOT_FOUND, "No recoverable project at $path")
            }
            simulateProgress("Recovering ${entry.name}")
            synchronized(lock) {
                if (projectOpen) closeLocked()
                openModel(demoModel(), entry.name.ifEmpty { "Recovered" }, null, "Project recovered")
                recovered = true
                recoverable.remove(entry)
                publish()
            }
        }
    }

    override suspend fun discardRecoverable(paths: List<String>) = cmd(needsProject = false) {
        recoverable.removeAll { it.path in paths }
        Unit
    }

    override suspend fun getTags(): List<Tag> = cmd(allowBusy = true) { work.tags }

    override suspend fun setTags(tags: List<Tag>) = cmd {
        if (tags.any { it.name.isBlank() }) fail(ErrorCodes.INVALID_ARGS, "tag names must not be empty")
        work = work.copy(tags = tags)
        pushState("Modified Project Tags", "Edit Metadata")
    }

    override suspend fun listProjects(): List<ProjectFileEntry> = cmd(needsProject = false, allowBusy = true) {
        storage.filter { (path, _) -> path.startsWith(config.projectsDir + "/") && !path.endsWith("~.aup3") }
            .map { (path, p) -> ProjectFileEntry(path, p.name, p.modifiedMs, p.sizeBytes) }
            .sortedByDescending { it.modifiedMs }
    }

    override suspend fun deleteProject(path: String) = cmd(needsProject = false) {
        if (projectOpen && path == projectPath) fail(ErrorCodes.FAILED, "The project is open")
        storage.remove(path) ?: fail(ErrorCodes.NOT_FOUND, "No project at $path")
        Unit
    }

    // =========================================================================
    // history / view
    // =========================================================================

    override suspend fun undo() = cmd {
        if (historyIndex == 0) fail(ErrorCodes.FAILED, "Nothing to undo")
        restore(historyIndex - 1)
    }

    override suspend fun redo() = cmd {
        if (historyIndex >= history.size - 1) fail(ErrorCodes.FAILED, "Nothing to redo")
        restore(historyIndex + 1)
    }

    private fun restore(index: Int) {
        historyIndex = index
        work = history[index].model
        generation++
        publish()
    }

    override suspend fun history(): HistoryList = cmd(allowBusy = true) {
        HistoryList(historyIndex, history.mapIndexed { i, e ->
            HistoryEntry(i, e.description, e.short, e.model.tracks.sumOf { t -> t.clips.sumOf { 4L * it.length * it.channels } })
        })
    }

    override suspend fun historyGoto(index: Int) = cmd {
        if (index !in history.indices) fail(ErrorCodes.INVALID_ARGS, "history index $index out of range")
        restore(index)
    }

    override suspend fun historyPurge(keepFrom: Int) = cmd {
        if (keepFrom < 1 || keepFrom > historyIndex) fail(ErrorCodes.INVALID_ARGS, "keepFrom must be in 1..$historyIndex")
        repeat(keepFrom) { history.removeAt(0) }
        historyIndex -= keepFrom
        savedIndex = if (savedIndex >= keepFrom) savedIndex - keepFrom else -1
        generation++
        publish()
    }

    override suspend fun setView(zoom: Double?, hpos: Double?) = cmd(allowBusy = true) {
        if (zoom != null) {
            if (!zoom.isFinite() || zoom <= 0) fail(ErrorCodes.INVALID_ARGS, "zoom must be positive")
            this.zoom = zoom.coerceIn(0.001, 6_000_000.0)
        }
        if (hpos != null) {
            if (!hpos.isFinite()) fail(ErrorCodes.INVALID_ARGS, "hpos must be finite")
            this.hpos = hpos
        }
        publish()
    }

    // =========================================================================
    // selection
    // =========================================================================

    private fun setSelection(t0: Double, t1: Double) {
        if (!t0.isFinite() || !t1.isFinite()) fail(ErrorCodes.INVALID_ARGS, "times must be finite")
        work = work.copy(selection = TimeRange(min(t0, t1), max(t0, t1)))
    }

    private fun selectOnly(ids: Set<Long>) {
        work = work.mapTracks { it.copy(selected = it.id in ids) }
    }

    override suspend fun select(t0: Double, t1: Double) = cmd(allowBusy = true) {
        setSelection(t0, t1)
        publish()
    }

    override suspend fun selectAll() = cmd(allowBusy = true) {
        work = work.mapTracks { it.copy(selected = true) }
        setSelection(work.start, work.end)
        publish()
    }

    override suspend fun selectNone() = cmd(allowBusy = true) {
        work = work.mapTracks { it.copy(selected = false) }
        setSelection(work.selection.t0, work.selection.t0)
        publish()
    }

    override suspend fun selectTracks(ids: List<Long>, mode: String) = cmd(allowBusy = true) {
        for (id in ids) if (work.track(id) == null) fail(ErrorCodes.NOT_FOUND, "no track $id")
        val set = ids.toSet()
        work = when (mode) {
            "set" -> work.mapTracks { it.copy(selected = it.id in set) }
            "add" -> work.mapTracks { if (it.id in set) it.copy(selected = true) else it }
            "remove" -> work.mapTracks { if (it.id in set) it.copy(selected = false) else it }
            "toggle" -> work.mapTracks { if (it.id in set) it.copy(selected = !it.selected) else it }
            else -> fail(ErrorCodes.INVALID_ARGS, "mode must be set, add, remove or toggle")
        }
        publish()
    }

    override suspend fun selectTrackHeader(id: Long, shift: Boolean, ctrl: Boolean) = cmd(allowBusy = true) {
        val track = work.track(id) ?: fail(ErrorCodes.NOT_FOUND, "no track $id")
        val last = lastPickedTrack?.let { work.track(it) }
        when {
            ctrl -> work = work.replace(track.copy(selected = !track.selected))
            shift && last != null -> {
                val a = work.tracks.indexOfFirst { it.id == last.id }
                val b = work.tracks.indexOfFirst { it.id == id }
                val range = min(a, b)..max(a, b)
                work = work.copy(tracks = work.tracks.mapIndexed { i, t -> t.copy(selected = i in range) })
            }
            else -> {
                selectOnly(setOf(id))
                if (!track.isEmpty) setSelection(track.start, track.end)
            }
        }
        lastPickedTrack = id
        work = work.copy(focusedId = id)
        publish()
    }

    override suspend fun selectCommand(command: String) = cmd(allowBusy = true) {
        val sel = work.selection
        val selected = work.selectedTracks.filter { !it.isEmpty }
        val pool = selected.ifEmpty { work.tracks.filter { !it.isEmpty } }
        when (command) {
            "select.allTracks" -> work = work.mapTracks { it.copy(selected = true) }
            "select.startToCursor" -> if (selected.isNotEmpty()) setSelection(min(selected.minOf { it.start }, sel.t1), sel.t1)
            "select.cursorToEnd" -> if (selected.isNotEmpty()) setSelection(sel.t0, max(sel.t0, selected.maxOf { it.end }))
            "select.trackStartToEnd" -> if (selected.isNotEmpty()) setSelection(selected.minOf { it.start }, selected.maxOf { it.end })
            "select.cursorToTrackStart" -> if (selected.isNotEmpty()) selected.minOf { it.start }.let { setSelection(it, it) }
            "select.cursorToTrackEnd" -> if (selected.isNotEmpty()) selected.maxOf { it.end }.let { setSelection(it, it) }
            "select.toProjectStart" -> setSelection(0.0, sel.t1)
            "select.toProjectEnd" -> setSelection(sel.t0, max(sel.t0, work.end))
            "select.prevClipBoundary", "select.nextClipBoundary" -> {
                val bounds = pool.filter { it.isWave }.flatMap { t -> t.clips.flatMap { listOf(it.start, it.end) } }.distinct().sorted()
                val t = if (command == "select.prevClipBoundary") bounds.lastOrNull { it < sel.t0 - 1e-9 }
                else bounds.firstOrNull { it > sel.t0 + 1e-9 }
                if (t != null) setSelection(t, t)
            }
            "select.prevClip", "select.nextClip" -> {
                val clips = pool.filter { it.isWave }.flatMap { it.clips }.sortedBy { it.start }
                val c = if (command == "select.prevClip") clips.lastOrNull { it.start < sel.t0 - 1e-9 }
                else clips.firstOrNull { it.start > sel.t0 + 1e-9 }
                if (c != null) setSelection(c.start, c.end)
            }
            else -> fail(ErrorCodes.UNKNOWN_COMMAND, "unknown selection command '$command'")
        }
        publish()
    }

    /** Resolves a clip reference (API.md §3.2). */
    private fun clipRef(trackId: Long, clipIndex: Int, gen: Long): Pair<FTrack, FClip> {
        if (gen != generation) fail(ErrorCodes.STALE, "clip reference of generation $gen is stale (current $generation)")
        val track = work.track(trackId) ?: fail(ErrorCodes.NOT_FOUND, "no track $trackId")
        if (!track.isWave) fail(ErrorCodes.INVALID_ARGS, "track $trackId is not a wave track")
        val clip = track.clips.getOrNull(clipIndex) ?: fail(ErrorCodes.NOT_FOUND, "no clip $clipIndex in track $trackId")
        return track to clip
    }

    override suspend fun selectClip(trackId: Long, clipIndex: Int, generation: Long) = cmd(allowBusy = true) {
        val (_, clip) = clipRef(trackId, clipIndex, generation)
        selectOnly(setOf(trackId))
        setSelection(clip.start, clip.end)
        work = work.copy(focusedId = trackId)
        publish()
    }

    override suspend fun focusTrack(id: Long) = cmd(allowBusy = true) {
        if (work.track(id) == null) fail(ErrorCodes.NOT_FOUND, "no track $id")
        work = work.copy(focusedId = id)
        publish()
    }

    override suspend fun setPlayRegion(t0: Double, t1: Double, active: Boolean) = cmd(allowBusy = true) {
        if (!t0.isFinite() || !t1.isFinite()) fail(ErrorCodes.INVALID_ARGS, "times must be finite")
        playRegion = PlayRegionState(active, min(t0, t1), max(t0, t1))
        publish()
    }

    override suspend fun clearPlayRegion() = cmd(allowBusy = true) {
        playRegion = PlayRegionState()
        publish()
    }

    override suspend fun togglePlayRegion() = cmd(allowBusy = true) {
        playRegion = if (playRegion.active) playRegion.copy(active = false) else activePlayRegion()
        publish()
    }

    private fun activePlayRegion(): PlayRegionState = when {
        playRegion.t1 > playRegion.t0 -> playRegion.copy(active = true)
        work.selection.t1 > work.selection.t0 -> PlayRegionState(true, work.selection.t0, work.selection.t1)
        else -> PlayRegionState(true, 0.0, work.end)
    }

    // =========================================================================
    // edit
    // =========================================================================

    private fun requireTimeAndTracks(waveOnly: Boolean = false): List<FTrack> {
        val sel = work.selection
        val tracks = work.selectedTracks.filter { !waveOnly || it.isWave }
        if (sel.t1 <= sel.t0 || tracks.isEmpty()) {
            fail(ErrorCodes.NO_SELECTION, "Select the audio to use (for example, Ctrl + A to Select All) then try again.")
        }
        return tracks
    }

    private fun copyToClipboard(tracks: List<FTrack>) {
        val sel = work.selection
        clipboard = FakeClipboard(tracks.map { Edits.copy(it, sel.t0, sel.t1) }, sel.t0, sel.t1)
    }

    override suspend fun edit(command: String) = cmd {
        val sel = work.selection
        val len = sel.t1 - sel.t0
        when (command) {
            "edit.cut", "edit.splitCut" -> {
                val tracks = requireTimeAndTracks()
                val shift = command == "edit.cut"
                copyToClipboard(tracks)
                val ids = tracks.map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.clear(it, sel.t0, sel.t1, shift) else it }
                if (shift) setSelection(sel.t0, sel.t0)
                if (shift) pushState("Cut to the clipboard", "Cut") else pushState("Split-cut to the clipboard", "Split Cut")
            }
            "edit.copy" -> {
                copyToClipboard(requireTimeAndTracks())
                publish()
            }
            "edit.paste" -> paste()
            "edit.delete", "edit.splitDelete" -> {
                val tracks = requireTimeAndTracks()
                val shift = command == "edit.delete"
                val ids = tracks.map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.clear(it, sel.t0, sel.t1, shift) else it }
                if (shift) {
                    setSelection(sel.t0, sel.t0)
                    pushState("Delete ${fmt(len)} seconds at t=${fmt(sel.t0)}", "Delete")
                } else pushState("Split-deleted ${fmt(len)} seconds at t=${fmt(sel.t0)}", "Split Delete")
            }
            "edit.silence" -> {
                val ids = requireTimeAndTracks(waveOnly = true).map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.silence(it, sel.t0, sel.t1) else it }
                pushState("Silenced selected tracks for ${fmt(len)} seconds at ${fmt(sel.t0)}", "Silence")
            }
            "edit.trim" -> {
                val ids = requireTimeAndTracks().map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids && it.isWave) Edits.trim(it, sel.t0, sel.t1) else it }
                pushState("Trim selected audio tracks from ${fmt(sel.t0)} seconds to ${fmt(sel.t1)} seconds", "Trim Audio")
            }
            "edit.duplicate" -> {
                val tracks = requireTimeAndTracks()
                val dups = tracks.map { t ->
                    val cb = Edits.copy(t, sel.t0, sel.t1)
                    t.copy(id = newTrackId(), selected = false, clips = cb.clips.map { it.withStart(it.start + sel.t0) },
                        labels = cb.labels.map { it.copy(t0 = it.t0 + sel.t0, t1 = it.t1 + sel.t0) })
                }
                work = work.copy(tracks = work.tracks + dups)
                pushState("Duplicated", "Duplicate")
            }
            "edit.split" -> {
                val ids = work.selectedTracks.filter { it.isWave }.map { it.id }.toSet()
                if (ids.isEmpty()) fail(ErrorCodes.NO_SELECTION, "Select an audio track first")
                work = work.mapTracks {
                    if (it.id !in ids) it else {
                        val once = Edits.splitAt(it, sel.t0)
                        if (sel.t1 > sel.t0) Edits.splitAt(once, sel.t1) else once
                    }
                }
                pushState("Split", "Split")
            }
            "edit.splitNew" -> {
                val tracks = requireTimeAndTracks(waveOnly = true)
                val news = tracks.map { t ->
                    val cb = Edits.copy(t, sel.t0, sel.t1)
                    t.copy(id = newTrackId(), selected = false, clips = cb.clips.map { it.withStart(it.start + sel.t0) })
                }
                val ids = tracks.map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.clear(it, sel.t0, sel.t1, shift = false) else it }
                work = work.copy(tracks = work.tracks + news)
                pushState("Split to new track", "Split New")
            }
            "edit.join" -> {
                val tracks = requireTimeAndTracks(waveOnly = true)
                if (tracks.none { it.clipsIntersecting(sel.t0, sel.t1).size > 1 }) {
                    fail(ErrorCodes.NO_SELECTION, "Select at least two clips of one track to join")
                }
                val ids = tracks.map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.join(it, sel.t0, sel.t1) else it }
                pushState("Join", "Join")
            }
            "edit.detachAtSilences" -> {
                val ids = requireTimeAndTracks(waveOnly = true).map { it.id }.toSet()
                work = work.mapTracks { if (it.id in ids) Edits.detachAtSilences(it, sel.t0, sel.t1) else it }
                pushState("Detach", "Detach")
            }
            else -> fail(ErrorCodes.UNKNOWN_COMMAND, "unknown edit command '$command'")
        }
    }

    private fun paste() {
        val cb = clipboard ?: fail(ErrorCodes.FAILED, "The clipboard is empty")
        val sel = work.selection
        val t0 = sel.t0
        val duration = cb.duration
        val selected = work.selectedTracks
        var tracks = work.tracks
        if (sel.t1 > sel.t0) {
            val ids = selected.map { it.id }.toSet()
            tracks = tracks.map { if (it.id in ids) Edits.clear(it, sel.t0, sel.t1, shift = true) else it }
        }
        val pending = cb.tracks.toMutableList()
        val paired = HashSet<Long>()
        tracks = tracks.map { t ->
            if (!t.selected) return@map t
            val src = pending.firstOrNull { it.kind == t.kind } ?: return@map t
            pending.remove(src)
            paired += t.id
            Edits.paste(t, t0, src, duration)
        }
        if (paired.isEmpty()) {
            // Nothing selected (or no matching kinds): paste into new tracks
            val names = tracks.map { it.name }.toMutableSet()
            val news = cb.tracks.map { c ->
                val base = if (c.kind == FTrack.WAVE) FTrack(newTrackId(), FTrack.WAVE, c.name, selected = true, channels = c.channels, rate = c.rate)
                else FTrack(newTrackId(), FTrack.LABEL, c.name, selected = true)
                names += c.name
                Edits.paste(base, t0, c, duration)
            }
            tracks = tracks.map { it.copy(selected = false) } + news
        }
        work = work.copy(tracks = tracks, selection = TimeRange(t0, t0 + duration))
        pushState("Pasted from the clipboard", "Paste")
    }

    override suspend fun clipboardInfo(): ClipboardInfo = cmd(needsProject = false, allowBusy = true) {
        val cb = clipboard
        if (cb == null) ClipboardInfo() else ClipboardInfo(false, cb.t0, cb.t1, cb.tracks.size)
    }

    // =========================================================================
    // tracks
    // =========================================================================

    private fun requireTrack(id: Long): FTrack = work.track(id) ?: fail(ErrorCodes.NOT_FOUND, "no track $id")
    private fun requireWave(id: Long): FTrack = requireTrack(id).also { if (!it.isWave) fail(ErrorCodes.INVALID_ARGS, "track $id is not a wave track") }

    override suspend fun addTrack(kind: String): Long = cmd {
        val id = newTrackId()
        val track = when (kind) {
            "mono" -> FTrack(id, FTrack.WAVE, uniqueTrackName("Audio"), selected = true, channels = 1, rate = projectRate,
                format = settings.defaultFormat ?: "float")
            "stereo" -> FTrack(id, FTrack.WAVE, uniqueTrackName("Audio"), selected = true, channels = 2, rate = projectRate,
                format = settings.defaultFormat ?: "float")
            "label" -> FTrack(id, FTrack.LABEL, uniqueTrackName("Label"), selected = true)
            else -> fail(ErrorCodes.INVALID_ARGS, "kind must be mono, stereo or label")
        }
        work = work.copy(tracks = work.tracks.map { it.copy(selected = false) } + track, focusedId = id)
        when (kind) {
            "mono" -> pushState("Created new audio track", "New Track")
            "stereo" -> pushState("Created new stereo audio track", "New Track")
            else -> pushState("Created new label track", "New Track")
        }
        id
    }

    override suspend fun removeTracks(ids: List<Long>) = cmd {
        if (ids.isEmpty()) fail(ErrorCodes.INVALID_ARGS, "no track ids")
        val tracks = ids.map { requireTrack(it) }
        val set = ids.toSet()
        val focusIndex = work.tracks.indexOfFirst { it.id == work.focusedId }
        val remaining = work.tracks.filter { it.id !in set }
        val focus = if (work.focusedId in set) remaining.getOrNull(min(focusIndex, remaining.size - 1).coerceAtLeast(0))?.id else work.focusedId
        work = work.copy(tracks = remaining, focusedId = focus)
        if (tracks.size == 1) pushState("Removed track '${tracks[0].name}.'", "Track Remove")
        else pushState("Removed ${tracks.size} tracks", "Track Remove")
    }

    override suspend fun mixAndRender(toNewTrack: Boolean) {
        commandMutex.withLock {
            locked { requireSelectedWaves() }
            simulateProgress("Mix and Render")
            locked {
                val sources = requireSelectedWaves()
                val start = sources.minOf { it.start }
                val end = sources.maxOf { it.end }
                val stereo = sources.any { it.channels > 1 || it.pan != 0.0 }
                val frames = ((end - start) * projectRate).roundToInt().coerceAtLeast(0)
                val data = Mixer.mix(sources, start, projectRate, frames, if (stereo) 2 else 1)
                val name = if (sources.size == 1) sources[0].name else "Mix"
                val id = newTrackId()
                val mixed = FTrack(id, FTrack.WAVE, name, selected = true, channels = data.size, rate = projectRate,
                    clips = if (frames > 0) listOf(FClip("$name #1", start, projectRate, data)) else emptyList())
                val ids = sources.map { it.id }.toSet()
                work = if (toNewTrack) {
                    work.copy(tracks = work.tracks.map { it.copy(selected = false) } + mixed, focusedId = id)
                } else {
                    val at = work.tracks.indexOfFirst { it.id in ids }
                    val rest = work.tracks.filter { it.id !in ids }.map { it.copy(selected = false) }.toMutableList()
                    rest.add(at.coerceIn(0, rest.size), mixed)
                    work.copy(tracks = rest, focusedId = id)
                }
                if (sources.size == 1 && !toNewTrack) pushState("Rendered all audio in track '$name'", "Render")
                else pushState("Mixed and rendered ${sources.size} tracks into one new ${if (stereo) "stereo" else "mono"} track", "Mix and Render")
            }
        }
    }

    private fun requireSelectedWaves(): List<FTrack> {
        val waves = work.selectedTracks.filter { it.isWave && it.clips.isNotEmpty() }
        if (waves.isEmpty()) fail(ErrorCodes.NO_SELECTION, "Select one or more audio tracks first")
        return waves
    }

    override suspend fun resample(rate: Int) {
        commandMutex.withLock {
            locked {
                if (rate < 1 || rate > 1_000_000) fail(ErrorCodes.INVALID_ARGS, "rate out of range")
                requireSelectedWaves()
            }
            simulateProgress("Resampling")
            locked {
                val ids = requireSelectedWaves().map { it.id }.toSet()
                val r = rate.toDouble()
                work = work.mapTracks { t ->
                    if (t.id !in ids) t else t.copy(rate = r).withClips(t.clips.map { c ->
                        val n = (c.length * r / c.rate).roundToInt()
                        FClip(c.name, c.start, r, Array(c.channels) { Dsp.stretch(c.channelCopy(it), n) })
                    })
                }
                pushState("Resampled audio track(s)", "Resample Track")
            }
        }
    }

    override suspend fun setTrackGain(id: Long, gain: Double, final: Boolean) = cmd(allowBusy = true) {
        if (!gain.isFinite() || gain < 0 || gain > 64) fail(ErrorCodes.INVALID_ARGS, "gain out of range")
        work = work.replace(requireWave(id).copy(gain = gain))
        if (final) pushState("Moved volume slider", "Volume", consolidate = true) else { generation++; publish() }
    }

    override suspend fun setTrackPan(id: Long, pan: Double, final: Boolean) = cmd(allowBusy = true) {
        if (!pan.isFinite() || pan < -1 || pan > 1) fail(ErrorCodes.INVALID_ARGS, "pan must be in [-1, 1]")
        work = work.replace(requireWave(id).copy(pan = pan))
        if (final) pushState("Moved pan slider", "Pan", consolidate = true) else { generation++; publish() }
    }

    override suspend fun setTrackMute(id: Long, mute: Boolean) = cmd(allowBusy = true) {
        work = work.replace(requireWave(id).copy(mute = mute))
        modifyState()
    }

    override suspend fun setTrackSolo(id: Long, solo: Boolean) = cmd(allowBusy = true) {
        val track = requireWave(id)
        if (track.solo != solo) {
            if (settings.soloMode == "Multi") {
                work = work.replace(track.copy(solo = solo))
            } else {
                // Port of TrackUtilities::DoTrackSolo, "Simple" (radio-button) behaviour
                val wasSolo = track.solo
                work = work.mapTracks { t ->
                    when {
                        !t.isWave -> t
                        t.id == id -> t.copy(solo = !wasSolo, mute = false)
                        else -> t.copy(solo = false, mute = !wasSolo)
                    }
                }
            }
        }
        modifyState()
    }

    override suspend fun renameTrack(id: Long, name: String) = cmd {
        val t = requireTrack(id)
        work = work.replace(t.copy(name = name))
        pushState("Renamed '${t.name}' to '$name'", "Name Change")
    }

    override suspend fun moveTrack(id: Long, to: String) = cmd {
        val t = requireTrack(id)
        val list = work.tracks.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        list.removeAt(i)
        val j = when (to) {
            "up" -> max(0, i - 1)
            "down" -> min(list.size, i + 1)
            "top" -> 0
            "bottom" -> list.size
            else -> fail(ErrorCodes.INVALID_ARGS, "to must be up, down, top or bottom")
        }
        list.add(j, t)
        work = work.copy(tracks = list)
        val dir = when (to) { "up" -> "Up"; "down" -> "Down"; "top" -> "to Top"; else -> "to Bottom" }
        pushState("Moved '${t.name}' $dir", "Move Track")
    }

    override suspend fun trackChannelCommand(command: String, id: Long) = cmd {
        val t = requireWave(id)
        when (command) {
            "tracks.makeStereo" -> {
                val i = work.tracks.indexOfFirst { it.id == id }
                val below = work.tracks.getOrNull(i + 1)
                if (t.channels != 1 || below == null || !below.isWave || below.channels != 1 || below.rate != t.rate) {
                    fail(ErrorCodes.INVALID_ARGS, "Make Stereo needs two mono tracks with the same rate, the second directly below")
                }
                val start = min(t.start, below.start)
                val end = max(t.end, below.end)
                val frames = ((end - start) * t.rate).roundToInt()
                val left = Mixer.render(t, start, t.rate, frames)[0]
                val right = Mixer.render(below, start, t.rate, frames)[0]
                val merged = t.copy(channels = 2, pan = 0.0).withClips(
                    if (frames > 0) listOf(FClip(t.newClipName(), start, t.rate, arrayOf(left, right))) else emptyList())
                work = work.copy(tracks = work.tracks.filter { it.id != below.id }.map { if (it.id == id) merged else it })
                pushState("Made '${t.name}' a stereo track", "Make Stereo")
            }
            "tracks.splitStereo", "tracks.splitStereoToMono" -> {
                if (t.channels != 2) fail(ErrorCodes.INVALID_ARGS, "track $id is not stereo")
                val toMono = command == "tracks.splitStereoToMono"
                val left = t.copy(channels = 1, pan = if (toMono) 0.0 else -1.0)
                    .withClips(t.clips.map { FClip(it.name, it.start, it.rate, arrayOf(it.channelCopy(0))) })
                val right = t.copy(id = newTrackId(), selected = t.selected, channels = 1, pan = if (toMono) 0.0 else 1.0)
                    .withClips(t.clips.map { FClip(it.name, it.start, it.rate, arrayOf(it.channelCopy(1))) })
                work = work.copy(tracks = work.tracks.flatMap { if (it.id == id) listOf(left, right) else listOf(it) })
                if (toMono) pushState("Split Stereo to Mono '${t.name}'", "Split to Mono")
                else pushState("Split stereo track '${t.name}'", "Split")
            }
            "tracks.swapChannels" -> {
                if (t.channels != 2) fail(ErrorCodes.INVALID_ARGS, "track $id is not stereo")
                work = work.replace(t.withClips(t.clips.map { FClip(it.name, it.start, it.rate, arrayOf(it.channelCopy(1), it.channelCopy(0))) }))
                pushState("Swapped Channels in '${t.name}'", "Swap Channels")
            }
            else -> fail(ErrorCodes.UNKNOWN_COMMAND, "unknown track command '$command'")
        }
    }

    override suspend fun setTrackRate(id: Long, rate: Int) = cmd {
        if (rate < 1 || rate > 1_000_000) fail(ErrorCodes.INVALID_ARGS, "rate out of range")
        val t = requireWave(id)
        val r = rate.toDouble()
        work = work.replace(t.copy(rate = r).withClips(t.clips.map { FClip(it.name, it.start, r, it.data, it.length) }))
        pushState("Changed '${t.name}' to $rate Hz", "Rate Change")
    }

    override suspend fun setTrackFormat(id: Long, format: String) {
        commandMutex.withLock {
            locked {
                if (format !in FORMATS) fail(ErrorCodes.INVALID_ARGS, "unknown sample format '$format'")
                requireWave(id)
            }
            simulateProgress("Changing sample format")
            locked {
                val t = requireWave(id)
                val q = when (format) { "int16" -> 32768f; "int24" -> 8388608f; else -> 0f }
                val clips = if (q == 0f) t.clips else t.clips.map { c ->
                    FClip(c.name, c.start, c.rate, Array(c.channels) { ch ->
                        c.channelCopy(ch).also { a -> for (i in a.indices) a[i] = (a[i] * q).roundToInt().coerceIn(-q.toInt(), q.toInt() - 1) / q }
                    })
                }
                work = work.replace(t.copy(format = format).withClips(clips))
                pushState("Changed '${t.name}' to ${FORMAT_NAMES[format]}", "Format Change")
            }
        }
    }

    override suspend fun alignTracks(mode: String) = cmd {
        val sel = work.selection
        val tracks = work.selectedTracks.filter { !it.isEmpty }
        if (tracks.isEmpty()) fail(ErrorCodes.NO_SELECTION, "Select one or more tracks first")
        val groupStart = tracks.minOf { it.start }
        fun shiftTrack(t: FTrack, d: Double) = if (t.isWave) t.withClips(t.clips.map { it.withStart(it.start + d) })
        else t.withLabels(t.labels.map { it.copy(t0 = it.t0 + d, t1 = it.t1 + d) })
        val ids = tracks.map { it.id }.toSet()
        work = work.mapTracks { t ->
            if (t.id !in ids) t else {
                val d = when (mode) {
                    "startToZero" -> -t.start
                    "startToCursor" -> sel.t0 - t.start
                    "startToSelEnd" -> sel.t1 - t.start
                    "endToCursor" -> sel.t0 - t.end
                    "endToSelEnd" -> sel.t1 - t.end
                    "together" -> groupStart - t.start
                    else -> fail(ErrorCodes.INVALID_ARGS, "unknown align mode '$mode'")
                }
                shiftTrack(t, d)
            }
        }
        pushState("Aligned/Moved tracks ($mode)", "Align/Move")
    }

    // =========================================================================
    // clips / labels
    // =========================================================================

    override suspend fun moveClip(trackId: Long, clipIndex: Int, generation: Long, newStart: Double, toTrackId: Long?) = cmd {
        if (!newStart.isFinite()) fail(ErrorCodes.INVALID_ARGS, "newStart must be finite")
        val (src, clip) = clipRef(trackId, clipIndex, generation)
        val dstId = toTrackId ?: trackId
        val dst0 = if (dstId == trackId) src else requireWave(dstId)
        if (dst0.channels != clip.channels) fail(ErrorCodes.INVALID_ARGS, "the destination track has another channel count")
        if (dstId != trackId && dst0.rate != src.rate) fail(ErrorCodes.INVALID_ARGS, "the destination track has another sample rate")
        val moved = clip.withStart(newStart)
        val others = (if (dstId == trackId) src.clips.filter { it !== clip } else dst0.clips)
        if (others.any { it.start < moved.end - 1e-9 && it.end > moved.start + 1e-9 }) {
            fail(ErrorCodes.FAILED, "There is not enough room available to place the clip")
        }
        work = if (dstId == trackId) {
            work.replace(src.withClips(others + moved))
        } else {
            work.replace(src.withClips(src.clips.filter { it !== clip })).let { m ->
                m.replace(m.track(dstId)!!.let { it.withClips(it.clips + moved) })
            }
        }
        val d = newStart - clip.start
        pushState("Time shifted clips ${if (d >= 0) "right" else "left"} ${fmt(abs(d))} seconds", "Time-Shift")
    }

    override suspend fun renameClip(trackId: Long, clipIndex: Int, generation: Long, name: String) = cmd {
        val (t, clip) = clipRef(trackId, clipIndex, generation)
        work = work.replace(t.copy(clips = t.clips.map { if (it === clip) it.renamed(name) else it }))
        pushState("Renamed clip to '$name'", "Rename Clip")
    }

    override suspend fun addLabel(title: String): Pair<Long, Int> = cmd(allowBusy = true) {
        val sel = work.selection
        var track = work.selectedTracks.firstOrNull { it.isLabel }
        if (track == null) {
            track = FTrack(newTrackId(), FTrack.LABEL, uniqueTrackName("Label"), selected = true)
            work = work.copy(tracks = work.tracks + track)
        }
        val label = FLabel(sel.t0, sel.t1, title)
        val updated = track.withLabels(track.labels + label)
        work = work.replace(updated).copy(focusedId = updated.id)
        pushState("Added label", "Label")
        updated.id to updated.labels.indexOf(label)
    }

    override suspend fun editLabel(trackId: Long, index: Int, title: String?, t0: Double?, t1: Double?) = cmd {
        val t = requireTrack(trackId)
        if (!t.isLabel) fail(ErrorCodes.INVALID_ARGS, "track $trackId is not a label track")
        val old = t.labels.getOrNull(index) ?: fail(ErrorCodes.NOT_FOUND, "no label $index")
        val n0 = t0 ?: old.t0
        val n1 = t1 ?: old.t1
        if (!n0.isFinite() || !n1.isFinite() || n1 < n0) fail(ErrorCodes.INVALID_ARGS, "label times must satisfy t0 <= t1")
        val updated = FLabel(n0, n1, title ?: old.title)
        work = work.replace(t.withLabels(t.labels.toMutableList().also { it[index] = updated }))
        pushState("Edited labels", "Label")
    }

    override suspend fun removeLabel(trackId: Long, index: Int) = cmd {
        val t = requireTrack(trackId)
        if (!t.isLabel) fail(ErrorCodes.INVALID_ARGS, "track $trackId is not a label track")
        if (index !in t.labels.indices) fail(ErrorCodes.NOT_FOUND, "no label $index")
        work = work.replace(t.withLabels(t.labels.filterIndexed { i, _ -> i != index }))
        pushState("Deleted Label", "Delete Label")
    }

    // =========================================================================
    // effects
    // =========================================================================

    private fun effect(id: String): FxDef = effectsById[id] ?: fail(ErrorCodes.NOT_FOUND, "no effect '$id'")

    private fun values(def: FxDef): MutableMap<String, JsonPrimitive> = effectValues.getOrPut(def.id) { def.defaults().toMutableMap() }

    private fun curveOf(def: FxDef): EqCurve? =
        if (def.special == "equalization" || def.special == "graphicEq") eqCurves[def.id] ?: FxCatalog.DEFAULT_EQ_CURVE else null

    private fun describe(def: FxDef): EffectDescription {
        val v = values(def)
        return EffectDescription(
            id = def.id, name = def.name, type = def.type,
            params = def.params.map { it.toParam(v[it.key] ?: it.default) },
            presets = EffectPresets(def.factoryPresets.map { it.first }, userPresets[def.id]?.keys?.toList() ?: emptyList()),
            supportsDuration = def.supportsDuration,
            duration = effectDurations[def.id] ?: 30.0,
            special = def.special,
            profileCaptured = def.special == "noiseReduction" && noiseProfileRms != null,
            curve = curveOf(def),
            curves = when (def.special) {
                "equalization" -> FxCatalog.EQ_CURVES.map { it.first }
                "graphicEq" -> FxCatalog.EQ_CURVES.filter { it.second }.map { it.first }
                else -> emptyList()
            },
            nyquist = if (def.isNyquist) JsonObject(mapOf("controls" to kotlinx.serialization.json.JsonArray(def.params.map { it.nyquistControl() }))) else null,
            help = def.name,
        )
    }

    /** effects.setParams semantics: validate everything, then commit. */
    private fun setParamsLocked(def: FxDef, params: Map<String, JsonElement>, duration: Double?, curve: EqCurve?) {
        val staged = HashMap<String, JsonPrimitive>()
        for ((key, value) in params) {
            val p = def.params.firstOrNull { it.key == key } ?: fail(ErrorCodes.INVALID_ARGS, "unknown parameter '$key' for ${def.name}")
            var v = p.validate(value)
            if (def.internalName == "Phaser" && key == "Stages") v = JsonPrimitive(v.intOrNull!! / 2 * 2)
            if (def.internalName == "DTMF Tones" && key == "Sequence" && !v.content.all { it in "0123456789*#ABCD" || it in 'a'..'z' }) {
                fail(ErrorCodes.INVALID_ARGS, "invalid DTMF sequence '${v.content}'")
            }
            staged[key] = v
        }
        if (duration != null && (!duration.isFinite() || duration <= 0.0)) fail(ErrorCodes.INVALID_ARGS, "duration must be positive")
        if (curve != null) {
            if (def.special != "equalization" && def.special != "graphicEq") fail(ErrorCodes.INVALID_ARGS, "${def.name} has no curve")
            if (curve.points.size > 200) fail(ErrorCodes.INVALID_ARGS, "at most 200 curve points")
            if (curve.points.any { it.f <= 0 || !it.f.isFinite() || !it.dB.isFinite() }) fail(ErrorCodes.INVALID_ARGS, "invalid curve point")
            if (curve.points.zipWithNext().any { (a, b) -> b.f <= a.f }) fail(ErrorCodes.INVALID_ARGS, "curve frequencies must ascend")
        }
        values(def).putAll(staged)
        if (duration != null) effectDurations[def.id] = duration
        if (curve != null) eqCurves[def.id] = curve
    }

    override suspend fun effects(): EffectList = cmd(needsProject = false, allowBusy = true) {
        EffectList(effects.map { it.info() }, FxCatalog.menus(effects))
    }

    override suspend fun describeEffect(id: String): EffectDescription = cmd(needsProject = false, allowBusy = true) { describe(effect(id)) }

    override suspend fun setEffectParams(id: String, params: Map<String, JsonElement>, duration: Double?, curve: EqCurve?): EffectDescription =
        cmd(needsProject = false, allowBusy = true) {
            val def = effect(id)
            setParamsLocked(def, params, duration, curve)
            describe(def)
        }

    override suspend fun loadEffectPreset(id: String, kind: String, name: String?, index: Int?): EffectDescription =
        cmd(needsProject = false, allowBusy = true) {
            val def = effect(id)
            when (kind) {
                "factory" -> {
                    val preset = when {
                        name != null -> def.factoryPresets.firstOrNull { it.first == name }
                        index != null -> def.factoryPresets.getOrNull(index)
                        else -> fail(ErrorCodes.INVALID_ARGS, "name or index required")
                    } ?: fail(ErrorCodes.NOT_FOUND, "no factory preset '${name ?: index}'")
                    values(def).putAll(def.defaults())
                    values(def).putAll(FxCatalog.presetValues(def, preset.second))
                    FxCatalog.EQ_CURVES.firstOrNull { it.first == preset.first }?.let { eqCurves[def.id] = EqCurve(it.third) }
                }
                "user" -> {
                    val n = name ?: fail(ErrorCodes.INVALID_ARGS, "name required")
                    val (v, c) = userPresets[def.id]?.get(n) ?: fail(ErrorCodes.NOT_FOUND, "no user preset '$n'")
                    values(def).putAll(v)
                    if (c != null) eqCurves[def.id] = c
                }
                "defaults" -> {
                    values(def).clear()
                    values(def).putAll(def.defaults())
                    eqCurves.remove(def.id)
                }
                else -> fail(ErrorCodes.INVALID_ARGS, "kind must be factory, user or defaults")
            }
            describe(def)
        }

    override suspend fun saveEffectPreset(id: String, name: String) = cmd(needsProject = false, allowBusy = true) {
        val def = effect(id)
        if (name.isBlank()) fail(ErrorCodes.INVALID_ARGS, "preset name must not be empty")
        userPresets.getOrPut(def.id) { LinkedHashMap() }[name] = values(def).toMap() to eqCurves[def.id]
        Unit
    }

    override suspend fun deleteEffectPreset(id: String, name: String) = cmd(needsProject = false, allowBusy = true) {
        val def = effect(id)
        userPresets[def.id]?.remove(name) ?: fail(ErrorCodes.NOT_FOUND, "no user preset '$name'")
        Unit
    }

    /** Checks the menu preconditions of the effect (lock held). */
    private fun checkEffectPreconditions(def: FxDef) {
        val needsSelection = def.needsSelection || (def.type == "tool" && def.impl is FxImpl.Analyze)
        if (needsSelection) {
            val sel = work.selection
            if (sel.t1 <= sel.t0 || work.selectedTracks.none { it.isWave }) {
                fail(ErrorCodes.NO_SELECTION, "Select the audio for ${def.name} to use (for example, Ctrl + A to Select All) then try again.")
            }
        }
        if (def.special == "noiseReduction" && noiseProfileRms == null) {
            fail(ErrorCodes.FAILED, "Select a few seconds of just noise and use Get Noise Profile first.")
        }
    }

    override suspend fun applyEffect(id: String, params: Map<String, JsonElement>?, duration: Double?): EffectApplyResult =
        commandMutex.withLock { applyLocked(id, params, duration) }

    private suspend fun applyLocked(id: String, params: Map<String, JsonElement>?, duration: Double?): EffectApplyResult {
        val def = locked {
            val d = effect(id)
            if (params != null || duration != null) setParamsLocked(d, params ?: emptyMap(), duration, null)
            checkEffectPreconditions(d)
            d
        }
        simulateProgress("Applying ${def.name}...")
        return locked { performEffect(def) }
    }

    private fun performEffect(def: FxDef): EffectApplyResult {
        val p = P(values(def))
        val sel = work.selection
        return when (val impl = def.impl) {
            is FxImpl.Process, FxImpl.Identity -> {
                val targets = work.selectedTracks.filter { it.isWave }
                var maxDelta = 0.0
                if (impl is FxImpl.Process) {
                    val ctx = effectContext(def, targets)
                    val updated = HashMap<Long, FTrack>()
                    for (t in targets) {
                        val (nt, delta) = Edits.process(t, sel.t0, sel.t1) { data, rate -> impl.fn(data, rate, p, ctx) }
                        updated[t.id] = nt
                        if (abs(delta) > abs(maxDelta)) maxDelta = delta
                    }
                    work = work.mapTracks { updated[it.id] ?: it }
                    if (maxDelta != 0.0) setSelection(sel.t0, max(sel.t0, sel.t1 + maxDelta))
                }
                remember(def)
                pushState("Applied effect: ${def.name}", def.name)
                EffectApplyResult(true, null)
            }
            is FxImpl.Generate -> {
                var targets = work.selectedTracks.filter { it.isWave }
                if (targets.isEmpty()) {
                    val t = FTrack(newTrackId(), FTrack.WAVE, uniqueTrackName("Audio"), selected = true, channels = 1, rate = projectRate)
                    work = work.copy(tracks = work.tracks.map { it.copy(selected = false) } + t, focusedId = t.id)
                    targets = listOf(t)
                }
                val seconds = if (sel.t1 > sel.t0) sel.t1 - sel.t0 else effectDurations[def.id] ?: 30.0
                var generated = 0.0
                val updated = HashMap<Long, FTrack>()
                for (t in targets) {
                    val data = impl.fn((seconds * t.rate).roundToInt().coerceAtLeast(1), t.channels, t.rate, p)
                    generated = max(generated, data[0].size / t.rate)
                    val replaceEnd = if (sel.t1 > sel.t0) sel.t1 else sel.t0
                    updated[t.id] = Edits.insertGenerated(t, sel.t0, replaceEnd, data)
                }
                work = work.mapTracks { updated[it.id] ?: it }
                setSelection(sel.t0, sel.t0 + generated)
                remember(def)
                pushState("Applied effect: ${def.name}", def.name)
                EffectApplyResult(true, null)
            }
            is FxImpl.Analyze -> {
                val tracks = work.selectedTracks.filter { it.isWave }
                val rate = tracks.firstOrNull()?.rate ?: projectRate
                val frames = ((sel.t1 - sel.t0) * rate).roundToInt().coerceAtLeast(0)
                val mono = Mixer.mix(tracks.map { it.copy(gain = 1.0, pan = 0.0) }, sel.t0, rate, frames, 1)[0]
                val result = impl.fn(mono, rate, sel.t0, p)
                remember(def)
                if (result.labels.isNotEmpty()) {
                    val track = FTrack(newTrackId(), FTrack.LABEL, result.trackName).withLabels(result.labels)
                    work = work.copy(tracks = work.tracks + track)
                    pushState("Applied effect: ${def.name}", def.name)
                } else publish()
                EffectApplyResult(true, result.message)
            }
            is FxImpl.Tool -> {
                val (applied, message) = impl.fn(p)
                remember(def)
                publish()
                EffectApplyResult(applied, message)
            }
        }
    }

    private fun remember(def: FxDef) {
        when (def.type) {
            "process" -> lastEffect = def
            "generate" -> lastGenerator = def
            "analyze" -> lastAnalyzer = def
            else -> lastTool = def
        }
    }

    private fun effectContext(def: FxDef, targets: List<FTrack>): FxContext {
        if (def.special == "autoDuck") {
            val last = work.tracks.indexOfLast { it.selected }
            val control = work.tracks.drop(last + 1).firstOrNull { it.isWave && !it.selected }
            if (control == null || work.selectedTracks.any { !it.isWave }) {
                fail(ErrorCodes.FAILED, "You selected a track which does not contain audio. AutoDuck can only process audio tracks.\n" +
                    "Auto Duck needs a control track which must be placed below the selected track(s).")
            }
            val sel = work.selection
            val rate = targets.firstOrNull()?.rate ?: projectRate
            val frames = ((sel.t1 - sel.t0) * rate).roundToInt().coerceAtLeast(0)
            return FxContext(control = Mixer.mix(listOf(control.copy(gain = 1.0, pan = 0.0)), sel.t0, rate, frames, 1)[0])
        }
        return FxContext(noiseProfileRms = noiseProfileRms, curve = curveOf(def))
    }

    override suspend fun previewEffect(id: String, params: Map<String, JsonElement>?, duration: Double?) = cmd {
        val def = effect(id)
        if (params != null || duration != null) setParamsLocked(def, params ?: emptyMap(), duration, null)
        checkEffectPreconditions(def)
        val sel = work.selection
        val p = P(values(def))
        val previewLen = 6.0
        val tracks: List<FTrack>
        val t0: Double
        val t1: Double
        when (val impl = def.impl) {
            is FxImpl.Generate -> {
                val seconds = min(previewLen, if (sel.t1 > sel.t0) sel.t1 - sel.t0 else effectDurations[def.id] ?: 30.0)
                val data = impl.fn((seconds * projectRate).roundToInt().coerceAtLeast(1), 1, projectRate, p)
                tracks = listOf(FTrack(-100, FTrack.WAVE, "preview", channels = 1, rate = projectRate,
                    clips = listOf(FClip("preview", 0.0, projectRate, data))))
                t0 = 0.0
                t1 = data[0].size / projectRate
            }
            is FxImpl.Process -> {
                t0 = sel.t0
                t1 = min(sel.t1, sel.t0 + previewLen)
                val ctx = effectContext(def, work.selectedTracks.filter { it.isWave })
                tracks = Mixer.audible(work.tracks).filter { it.selected }.map { t ->
                    Edits.process(Edits.trim(t, t0, t1), t0, t1) { data, rate -> impl.fn(data, rate, p, ctx) }.first
                }
            }
            else -> {
                t0 = sel.t0
                t1 = min(max(sel.t1, sel.t0), sel.t0 + previewLen)
                tracks = Mixer.audible(work.tracks).filter { it.selected }
            }
        }
        previewTracks = tracks
        looping = false
        playEnd = t1
        startTransport(TState.PLAYING, t0, now())
    }

    override suspend fun stopPreview() = cmd(needsProject = false, allowBusy = true) {
        if (previewTracks != null && tState != TState.STOPPED) finishTransport("user")
    }

    override suspend fun repeatLastEffect(): EffectApplyResult = commandMutex.withLock {
        val last = locked { lastEffect ?: fail(ErrorCodes.FAILED, "There is no effect to repeat") }
        applyLocked(last.id, null, null)
    }

    override suspend fun captureNoiseProfile() = cmd {
        val tracks = requireTimeAndTracks(waveOnly = true)
        val sel = work.selection
        val rate = tracks[0].rate
        val frames = ((sel.t1 - sel.t0) * rate).roundToInt()
        if (frames < 2048) fail(ErrorCodes.FAILED, "Selected noise profile is too short.")
        val mono = Mixer.mix(tracks.map { it.copy(gain = 1.0, pan = 0.0) }, sel.t0, rate, frames, 1)[0]
        noiseProfileRms = max(Dsp.rms(mono), 1e-6)
        publish()
    }

    override suspend fun plotSpectrum(algorithm: String, window: String, size: Int): SpectrumResult = cmd {
        if (algorithm !in SPECTRUM_ALGORITHMS) fail(ErrorCodes.INVALID_ARGS, "unknown algorithm '$algorithm'")
        if (window !in Dsp.WINDOWS) fail(ErrorCodes.INVALID_ARGS, "unknown window '$window'")
        if (!Dsp.isPowerOfTwo(size) || size < 128 || size > 65536) fail(ErrorCodes.INVALID_ARGS, "size must be a power of two in 128..65536")
        val tracks = requireTimeAndTracks(waveOnly = true)
        val sel = work.selection
        val rate = tracks[0].rate
        var frames = ((sel.t1 - sel.t0) * rate).roundToInt()
        var warning: String? = null
        if (frames > MAX_SPECTRUM_FRAMES) { frames = MAX_SPECTRUM_FRAMES; warning = "Too much audio was selected: only the first ${fmt(frames / rate, 1)} seconds were analyzed." }
        val mono = Mixer.mix(tracks.map { it.copy(gain = 1.0, pan = 0.0) }, sel.t0, rate, frames, 1)[0]
        if (frames < size) warning = "Not enough data selected: the analysis is zero-padded."
        val win = Dsp.window(window, size)
        val half = size / 2
        val acc = DoubleArray(half)
        var windows = 0
        var start = 0
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        do {
            for (i in 0 until size) { re[i] = (mono.getOrElse(start + i) { 0f }) * win[i]; im[i] = 0.0 }
            Dsp.fft(re, im)
            if (algorithm == "spectrum") {
                for (k in 0 until half) acc[k] += re[k] * re[k] + im[k] * im[k]
            } else {
                for (k in 0 until size) {
                    var pw = re[k] * re[k] + im[k] * im[k]
                    if (algorithm == "cubeRootAutocorrelation") pw = cbrt(pw)
                    re[k] = pw; im[k] = 0.0
                }
                Dsp.fft(re, im, inverse = true)
                for (k in 0 until half) acc[k] += if (algorithm == "enhancedAutocorrelation") max(0.0, re[k]) else re[k]
            }
            windows++
            start += size / 2
        } while (start + size <= frames)
        val values = if (algorithm == "spectrum") {
            val norm = win.sum().let { it * it }
            List(half) { k -> (10 * log10(max(acc[k] / windows / norm * 4, 1e-20))).toFloat() }
        } else List(half) { k -> (acc[k] / windows).toFloat() }
        SpectrumResult(rate = rate,
            binHz = if (algorithm == "spectrum") rate / size else null,
            binSeconds = if (algorithm == "spectrum") null else 1.0 / rate,
            values = values, minValue = values.minOrNull()?.toDouble() ?: 0.0, maxValue = values.maxOrNull()?.toDouble() ?: 0.0,
            warning = warning)
    }

    override suspend fun contrast(fg0: Double, fg1: Double, bg0: Double, bg1: Double): ContrastResult = cmd {
        val tracks = work.selectedTracks.filter { it.isWave }.ifEmpty { Mixer.audible(work.tracks) }
        if (tracks.isEmpty()) fail(ErrorCodes.NO_SELECTION, "Select an audio track first")
        fun level(a: Double, b: Double): Double {
            if (!a.isFinite() || !b.isFinite() || b <= a) fail(ErrorCodes.INVALID_ARGS, "invalid time range [$a, $b]")
            val rate = tracks[0].rate
            val frames = ((b - a) * rate).roundToInt().coerceAtLeast(1)
            val mono = Mixer.mix(tracks.map { it.copy(gain = 1.0, pan = 0.0) }, a, rate, frames, 1)[0]
            return Dsp.linToDb(max(Dsp.rms(mono), 1e-10))
        }
        val fg = level(fg0, fg1)
        val bg = level(bg0, bg1)
        ContrastResult(fg, bg, fg - bg, fg - bg >= 20.0)
    }

    // =========================================================================
    // import / export
    // =========================================================================

    override suspend fun importFormats(): ImportFormats = cmd(needsProject = false, allowBusy = true) { FakeFormats.IMPORT }

    override suspend fun importFiles(paths: List<String>, newProject: Boolean): ImportResult = commandMutex.withLock {
        locked(needsProject = !newProject) {
            if (paths.isEmpty()) fail(ErrorCodes.INVALID_ARGS, "no paths")
            if (newProject) {
                newProjectLocked()
                publish()
            }
        }
        val ids = ArrayList<Long>()
        val messages = ArrayList<String>()
        var firstError: EngineException? = null
        for (path in paths) {
            val file = File(path)
            val name = file.nameWithoutExtension.ifEmpty { file.name }
            try {
                simulateProgress("Importing ${file.name}")
                locked {
                    val ext = file.extension.lowercase()
                    if (!file.isFile) fail(ErrorCodes.NOT_FOUND, "Could not open '${file.name}': file not found")
                    if (ext !in FakeFormats.IMPORT.extensions) fail(ErrorCodes.FAILED, "Audacity did not recognize the type of the file '${file.name}'.")
                    val (rate, channels) = decodeForImport(file, ext)
                    val wasEmpty = work.tracks.isEmpty()
                    val groups = if (channels.size <= 2) listOf(channels) else channels.map { arrayOf(it) }
                    val newTracks = groups.mapIndexed { k, data ->
                        val trackName = if (groups.size == 1) name else "$name ${k + 1}"
                        FTrack(newTrackId(), FTrack.WAVE, trackName, selected = true, channels = data.size, rate = rate.toDouble(),
                            clips = listOf(FClip("$trackName #1", 0.0, rate.toDouble(), data)))
                    }
                    work = work.copy(tracks = work.tracks.map { if (it.id in ids) it else it.copy(selected = false) } + newTracks,
                        focusedId = newTracks.first().id)
                    ids += newTracks.map { it.id }
                    if (wasEmpty && temporary && projectName == "Untitled") projectName = name
                    pushState("Imported '${file.name}'", "Import")
                }
            } catch (e: EngineException) {
                if (e.code == ErrorCodes.CANCELLED) throw e
                if (firstError == null) firstError = e
                messages += e.message ?: e.code
            }
        }
        if (ids.isEmpty()) throw firstError ?: EngineException(ErrorCodes.FAILED, "Nothing was imported")
        ImportResult(ids, messages)
    }

    private fun decodeForImport(file: File, ext: String): Pair<Int, Array<FloatArray>> {
        if (ext == "wav" || ext == "wave") {
            val wav = Wav.read(file) ?: fail(ErrorCodes.FAILED, "'${file.name}' is not a supported WAV file")
            if (wav.channels.isEmpty() || wav.channels[0].isEmpty()) fail(ErrorCodes.FAILED, "'${file.name}' contains no audio")
            return wav.rate to wav.channels
        }
        // The fake cannot decode compressed formats: deterministic stand-in audio
        val seconds = (file.length() / 24_000.0).coerceIn(1.0, 30.0)
        val channels = if (ext == "amr" || ext == "awb") 1 else 2
        val rate = if (ext == "amr") 8000 else if (ext == "opus") 48000 else 44100
        return rate to FakeDemo.procedural(file.name.hashCode().toLong(), rate.toDouble(), seconds, channels)
    }

    private fun editor(formatKey: String): ExportEditor =
        exportEditors.getOrPut(formatKey) { FakeFormats.newEditor(formatKey) ?: fail(ErrorCodes.NOT_FOUND, "no export format '$formatKey'") }

    override suspend fun exportFormats(): List<ExportFormat> = cmd(needsProject = false, allowBusy = true) {
        FakeFormats.FORMATS.map { exportEditors[it.key]?.format ?: it }
    }

    private fun hasSelectedAudio(): Boolean {
        val sel = work.selection
        return sel.t1 > sel.t0 && Mixer.audible(work.tracks, selectedOnly = true).isNotEmpty()
    }

    override suspend fun exportDefaults(formatKey: String): ExportDefaults = cmd(allowBusy = true) {
        val ed = editor(formatKey)
        val exported = Mixer.audible(work.tracks)
        val maxCh = ed.format.maxChannels
        val defCh = (if (exported.any { it.channels >= 2 || it.pan != 0.0 }) 2 else 1).coerceAtMost(max(1, maxCh))
        val rates = ed.sampleRates.ifEmpty { FakeFormats.DEFAULT_RATES }.sorted()
        val wanted = (work.tracks.filter { it.isWave }.maxOfOrNull { it.rate } ?: projectRate).roundToInt()
        val rate = if (wanted in rates) wanted else rates.firstOrNull { it >= wanted } ?: rates.last()
        ExportDefaults(hasSelectedAudio(), defCh, maxCh, rate, rates)
    }

    override suspend fun exportOptions(formatKey: String): ExportOptions = cmd(needsProject = false, allowBusy = true) { editor(formatKey).snapshot() }

    override suspend fun setExportOption(formatKey: String, id: Int, value: ExportValue): ExportOptions =
        cmd(needsProject = false, allowBusy = true) {
            val ed = editor(formatKey)
            ed.set(id, value)
            ed.snapshot()
        }

    override suspend fun export(path: String, formatKey: String, range: String, channels: Int, rate: Int, skipSilenceAtStart: Boolean): String =
        commandMutex.withLock {
            class Plan(val tracks: List<FTrack>, val t0: Double, val t1: Double, val encoding: Int)
            val plan = locked {
                val ed = editor(formatKey)
                if (path.isBlank() || !File(path).isAbsolute) fail(ErrorCodes.INVALID_ARGS, "path must be absolute")
                if (File(path).exists()) fail(ErrorCodes.INVALID_ARGS, "the staging path must not exist yet")
                if (range != "project" && range != "selection") fail(ErrorCodes.INVALID_ARGS, "range must be project or selection")
                if (channels < 1 || channels > ed.format.maxChannels) fail(ErrorCodes.INVALID_ARGS, "channels must be in 1..${ed.format.maxChannels}")
                val rates = ed.sampleRates
                if (rate < 1 || (rates.isNotEmpty() && rate !in rates)) fail(ErrorCodes.INVALID_ARGS, "Unsupported sample rate $rate for $formatKey")
                val selectionOnly = range == "selection"
                if (selectionOnly && !hasSelectedAudio()) fail(ErrorCodes.NO_SELECTION, "Select the audio to export first")
                val tracks = Mixer.audible(work.tracks, selectedOnly = selectionOnly)
                if (tracks.isEmpty()) fail(ErrorCodes.FAILED, if (selectionOnly) "All selected audio is muted." else "All audio is muted.")
                var t0 = if (selectionOnly) max(0.0, work.selection.t0) else 0.0
                val t1 = if (selectionOnly) min(work.end, work.selection.t1) else work.end
                if (skipSilenceAtStart) t0 = max(t0, tracks.filter { it.clips.isNotEmpty() }.minOfOrNull { it.start } ?: t0)
                if (t1 <= t0) fail(ErrorCodes.FAILED, "There is no audio to export")
                Plan(tracks, t0, t1, ed.wavEncoding())
            }
            simulateProgress("Exporting the audio as ${formatKey.removeSuffix(" Files")}", stoppable = true)
            synchronized(lock) {
                val frames = ((plan.t1 - plan.t0) * rate).roundToInt()
                val data = Mixer.mix(plan.tracks, plan.t0, rate.toDouble(), frames, channels)
                try {
                    Wav.write(File(path), data, frames, rate, plan.encoding)
                } catch (e: IOException) {
                    fail(ErrorCodes.FAILED, "Cannot write ${File(path).name}: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    fail(ErrorCodes.FAILED, "Cannot export: ${e.message}")
                }
                path
            }
        }

    // =========================================================================
    // transport / audio
    // =========================================================================

    override suspend fun play(loop: Boolean, t0: Double?, t1: Double?) = cmd(allowBusy = true) {
        if (tState == TState.MONITORING) tState = TState.STOPPED
        if (isBusy()) fail(ErrorCodes.AUDIO_BUSY, "Audio is already playing or recording")
        val sel = work.selection
        val end = work.end
        var start: Double
        looping = false
        previewTracks = null
        when {
            t0 != null -> {
                if (!t0.isFinite() || (t1 != null && !t1.isFinite())) fail(ErrorCodes.INVALID_ARGS, "times must be finite")
                start = t0
                playEnd = t1 ?: end
            }
            loop || playRegion.active -> {
                if (!playRegion.active || playRegion.t1 <= playRegion.t0) playRegion = activePlayRegion()
                looping = playRegion.t1 > playRegion.t0
                loopT0 = playRegion.t0
                loopT1 = playRegion.t1
                start = loopT0
                playEnd = loopT1
            }
            sel.t1 > sel.t0 -> { start = sel.t0; playEnd = sel.t1 }
            else -> { start = sel.t0; playEnd = end }
        }
        playClip = BooleanArray(2)
        startTransport(TState.PLAYING, start, now())
    }

    override suspend fun stop() = cmd(needsProject = false, allowBusy = true) {
        if (tState != TState.STOPPED) finishTransport("user")
    }

    override suspend fun pause() = cmd(needsProject = false, allowBusy = true) {
        val t = now()
        when (tState) {
            TState.PLAYING -> { anchor(position(t), t); tState = TState.PAUSED_PLAY }
            TState.PAUSED_PLAY -> { anchor(anchorPos, t); tState = TState.PLAYING; ensureTicker() }
            TState.RECORDING -> { recording?.let { appendRecording(it, position(t)) }; anchor(position(t), t); tState = TState.PAUSED_RECORD }
            TState.PAUSED_RECORD -> { anchor(anchorPos, t); tState = TState.RECORDING; ensureTicker() }
            else -> return@cmd
        }
        hub.setTransport(TransportEvent(tState.wire, "user"))
        publish()
    }

    override suspend fun record(newTrack: Boolean) = cmd(allowBusy = true) {
        if (!recordPermission) fail(ErrorCodes.UNSUPPORTED, "Recording needs the microphone permission")
        if (tState == TState.MONITORING) tState = TState.STOPPED
        if (isBusy()) fail(ErrorCodes.AUDIO_BUSY, "Audio is already playing or recording")
        val channels = (settings.recordChannels ?: 1).coerceIn(1, 2)
        val cursor = work.selection.t0
        val target = if (newTrack) null else work.selectedTracks.firstOrNull { it.isWave && it.channels == channels }
        val rec = if (target != null) {
            Recording(target.id, false, target.name, target.newClipName(), channels, target.rate,
                max(cursor, if (target.clips.isEmpty()) cursor else target.end), Array(channels) { FloatArray(RECORD_CHUNK) })
        } else {
            val name = uniqueTrackName("Audio")
            Recording(PENDING_TRACK_ID, true, name, "$name #1", channels, projectRate, cursor, Array(channels) { FloatArray(RECORD_CHUNK) })
        }
        recording = rec
        recClip = BooleanArray(2)
        rec.lastSnapshotNanos = now()
        startTransport(TState.RECORDING, rec.start, now())
    }

    override suspend fun seek(t: Double) = cmd(allowBusy = true) {
        if (!t.isFinite()) fail(ErrorCodes.INVALID_ARGS, "t must be finite")
        when (tState) {
            TState.PLAYING, TState.PAUSED_PLAY -> anchor(if (looping) t.coerceIn(loopT0, loopT1) else t, now())
            TState.RECORDING, TState.PAUSED_RECORD -> {}
            else -> { setSelection(max(0.0, t), max(0.0, t)); publish() }
        }
    }

    override suspend fun skipToStart() = cmd(allowBusy = true) {
        if (tState == TState.PLAYING || tState == TState.PAUSED_PLAY) anchor(0.0, now()) else { setSelection(0.0, 0.0); publish() }
    }

    override suspend fun skipToEnd() = cmd(allowBusy = true) {
        val end = work.end
        if (tState == TState.PLAYING || tState == TState.PAUSED_PLAY) anchor(end, now()) else { setSelection(end, end); publish() }
    }

    override suspend fun monitor(enabled: Boolean) = cmd(needsProject = false, allowBusy = true) {
        if (enabled && !recordPermission) fail(ErrorCodes.UNSUPPORTED, "Monitoring needs the microphone permission")
        if (enabled && tState == TState.STOPPED) {
            recClip = BooleanArray(2)
            startTransport(TState.MONITORING, 0.0, now())
        } else if (!enabled && tState == TState.MONITORING) {
            finishTransport("user")
        }
    }

    override suspend fun audioDevices(): AudioDevices = cmd(needsProject = false, allowBusy = true) {
        AudioDevices(
            outputs = listOf(
                AudioDevice(0, "Default Output", maxOutputChannels = 2, defaultRate = DEVICE_RATE, isDefault = true),
                AudioDevice(1, "Built-in speaker", maxOutputChannels = 2, defaultRate = DEVICE_RATE),
            ),
            inputs = listOf(
                AudioDevice(0, "Default Input", maxInputChannels = 2, defaultRate = DEVICE_RATE, isDefault = true),
                AudioDevice(1, "Built-in microphone", maxInputChannels = 2, defaultRate = DEVICE_RATE),
            ),
            current = CurrentDevices(settings.outputDevice ?: "Default Output", settings.inputDevice ?: "Default Input", settings.recordChannels ?: 1),
        )
    }

    override suspend fun setRecordPermission(granted: Boolean) = cmd(needsProject = false, allowBusy = true) {
        recordPermission = granted
        if (!granted && tState == TState.MONITORING) finishTransport("device")
        publish()
    }

    override suspend fun latency(): LatencyInfo = cmd(needsProject = false, allowBusy = true) {
        LatencyInfo(OUTPUT_LATENCY * 1000, INPUT_LATENCY * 1000, settings.latencyCorrectionMs ?: 0.0)
    }

    // =========================================================================
    // display
    // =========================================================================

    override suspend fun setViewportWidth(px: Int) = cmd(needsProject = false, allowBusy = true) {
        if (px < 0) fail(ErrorCodes.INVALID_ARGS, "px must not be negative")
        viewportWidth = px
    }

    override suspend fun trimDisplayCaches(budgetBytes: Long) {}

    private fun displayTrack(trackId: Long): FTrack? = effectiveTracks().firstOrNull { it.id == trackId && it.isWave }

    /** Version with the partial bit when the tile covers the live recording tail. */
    private fun tileVersion(track: FTrack, pps: Double, firstColumn: Long, count: Int): Long {
        val rec = recording
        var v = track.waveVersion
        if (rec != null && rec.targetId == track.id) {
            val endColumn = floor((rec.start + rec.frames / rec.rate) * pps).toLong()
            if (endColumn >= firstColumn - 1 && endColumn < firstColumn + count + 1) v = v or DisplayStatus.PARTIAL_BIT
        }
        return v
    }

    override suspend fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int): WaveTile? {
        if (count <= 0) return null
        val out = FloatArray(3 * count)
        val r = waveColumnsInto(trackId, channel, zoomLevel, firstColumn, count, out)
        return if (r < 0) null else WaveTile(firstColumn, count, out, DisplayStatus.version(r), DisplayStatus.isPartial(r))
    }

    override suspend fun waveColumnsInto(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long =
        synchronized(lock) {
            advance(now())
            require(count >= 0 && out.size >= 3 * count) { "out must hold 3 * count floats" }
            if (zoomLevel !in Zoom.MIN_LEVEL..Zoom.MAX_LEVEL) return@synchronized DisplayStatus.NO_TRACK
            val track = displayTrack(trackId) ?: return@synchronized DisplayStatus.NO_TRACK
            if (channel !in 0 until track.channels) return@synchronized DisplayStatus.NO_TRACK
            val pps = Zoom.ppsForLevel(zoomLevel)
            out.fill(Float.NaN, 0, 3 * count)
            val tStart = firstColumn / pps
            val tEnd = (firstColumn + count) / pps
            val clips = track.clipsIntersecting(tStart, tEnd)
            if (clips.isNotEmpty() && clips.all { Zoom.needsSampleMode(pps, it.rate) }) return@synchronized DisplayStatus.SAMPLE_MODE
            val st = Stats()
            for (clip in clips) {
                if (Zoom.needsSampleMode(pps, clip.rate)) continue
                val cFirst = max(firstColumn, floor(clip.start * pps).toLong())
                val cLast = min(firstColumn + count - 1, ceil(clip.end * pps).toLong() - 1)
                for (c in cFirst..cLast) {
                    val i0 = floor((c / pps - clip.start) * clip.rate).toLong().coerceIn(0L, clip.length.toLong()).toInt()
                    val i1 = floor(((c + 1) / pps - clip.start) * clip.rate).toLong().coerceIn(0L, clip.length.toLong()).toInt()
                    if (i1 <= i0) continue
                    st.reset()
                    clip.accumulate(channel, i0, i1, st)
                    if (st.count == 0L) continue
                    val k = (c - firstColumn).toInt()
                    if (out[k].isNaN()) {
                        out[k] = st.min; out[count + k] = st.max; out[2 * count + k] = st.rms
                    } else {
                        out[k] = min(out[k], st.min); out[count + k] = max(out[count + k], st.max)
                        out[2 * count + k] = max(out[2 * count + k], st.rms)
                    }
                }
            }
            tileVersion(track, pps, firstColumn, count)
        }

    override suspend fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int): FloatArray? {
        if (count <= 0) return null
        val out = FloatArray(count)
        return if (envelopeColumnsInto(trackId, zoomLevel, firstColumn, count, out) < 0) null else out
    }

    override suspend fun envelopeColumnsInto(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long =
        synchronized(lock) {
            require(count >= 0 && out.size >= count) { "out must hold count floats" }
            if (zoomLevel !in Zoom.MIN_LEVEL..Zoom.MAX_LEVEL) return@synchronized DisplayStatus.NO_TRACK
            val track = displayTrack(trackId) ?: return@synchronized DisplayStatus.NO_TRACK
            val pps = Zoom.ppsForLevel(zoomLevel)
            for (k in 0 until count) {
                val tc = (firstColumn + k + 0.5) / pps
                out[k] = if (track.clips.any { tc >= it.start && tc < it.end }) 1f else Float.NaN
            }
            tileVersion(track, pps, firstColumn, count)
        }

    override suspend fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): List<SampleRun>? = synchronized(lock) {
        val track = displayTrack(trackId) ?: return@synchronized null
        if (channel !in 0 until track.channels || !t0.isFinite() || !t1.isFinite() || t1 < t0) return@synchronized null
        var total = 0L
        val runs = ArrayList<SampleRun>()
        track.clips.forEachIndexed { index, clip ->
            if (clip.end <= t0 || clip.start >= t1) return@forEachIndexed
            val i0 = floor((t0 - clip.start) * clip.rate).toLong().coerceIn(0L, clip.length.toLong()).toInt()
            val i1 = ceil((t1 - clip.start) * clip.rate).toLong().coerceIn(0L, clip.length.toLong()).toInt()
            if (i1 <= i0) return@forEachIndexed
            total += i1 - i0
            if (total > MAX_SAMPLE_RUN) return@synchronized null
            runs += SampleRun(index, clip.start + i0 / clip.rate, 1.0 / clip.rate, clip.channelCopy(channel, i0, i1), FloatArray(i1 - i0) { 1f })
        }
        runs
    }

    override suspend fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int): ByteArray? {
        if (count <= 0 || rows <= 0) return null
        val (clips, version) = synchronized(lock) {
            val track = displayTrack(trackId) ?: return null
            if (channel !in 0 until track.channels || zoomLevel !in Zoom.MIN_LEVEL..Zoom.MAX_LEVEL) return null
            track.clips to track.waveVersion
        }
        val pps = Zoom.ppsForLevel(zoomLevel)
        val size = SPECTROGRAM_WINDOW
        val win = Dsp.window("hann", size)
        val winSum = win.sum()
        val out = ByteArray(count * rows)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (k in 0 until count) {
            val tc = (firstColumn + k + 0.5) / pps
            val clip = clips.firstOrNull { tc >= it.start && tc < it.end } ?: continue
            val center = ((tc - clip.start) * clip.rate).toInt()
            val data = clip.data[channel]
            for (i in 0 until size) {
                val j = center - size / 2 + i
                re[i] = if (j in 0 until clip.length) data[j] * win[i] else 0.0
                im[i] = 0.0
            }
            Dsp.fft(re, im)
            for (r in 0 until rows) {
                val bin = ((r + 0.5) / rows * (size / 2)).toInt().coerceIn(0, size / 2 - 1)
                val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin]) * 2 / winSum
                val db = 20 * log10(max(mag, 1e-12)) + SPECTROGRAM_GAIN
                val v = ((db + SPECTROGRAM_RANGE) / SPECTROGRAM_RANGE).coerceIn(0.0, 1.0)
                out[k * rows + r] = (v * 255).roundToInt().toByte()
            }
        }
        if (version < 0) return null
        return out
    }

    // =========================================================================
    // raw commands (debug screen)
    // =========================================================================

    override suspend fun invokeCommand(command: String, args: JsonObject): JsonElement {
        fun num(key: String): Double? = (args[key] as? JsonPrimitive)?.doubleOrNull
        fun str(key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (command) {
            "debug.makeTestTrack" -> cmd {
                val seconds = num("seconds") ?: 1.0
                val frequency = num("frequency") ?: 440.0
                val channels = (num("channels") ?: 1.0).toInt()
                val rate = num("rate") ?: projectRate
                val amplitude = num("amplitude") ?: 0.5
                if (seconds <= 0 || channels !in 1..2 || rate <= 0 || frequency <= 0) fail(ErrorCodes.INVALID_ARGS, "invalid test track arguments")
                val tone = FakeDemo.tone(rate, (seconds * rate).roundToInt(), frequency, frequency, amplitude, amplitude, 0, false)
                val count = work.tracks.count { it.name.startsWith("Test Tone") } + 1
                val track = FTrack(newTrackId(), FTrack.WAVE, "Test Tone $count", channels = channels, rate = rate,
                    clips = listOf(FClip("Test Tone $count #1", 0.0, rate, Array(channels) { tone.copyOf() })))
                work = work.copy(tracks = work.tracks + track)
                pushState("Made a test track", "Test Track")
                buildJsonObject { put("id", track.id) }
            }
            "debug.ask" -> commandMutex.withLock {
                val choices = (args["choices"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
                val id = synchronized(lock) { nextDialogId++ }
                val cancel = (args["cancel"] as? JsonPrimitive)?.booleanOrNull ?: false
                val buttons = if (choices.isNotEmpty()) listOf("OK", "Cancel") else if (cancel) listOf("Yes", "No", "Cancel") else listOf("Yes", "No")
                val waiter = CompletableDeferred<Int>()
                dialogWaiters[id] = waiter
                hub.showDialog(DialogEvent(id, if (choices.isNotEmpty()) "choice" else "message", "question", str("title") ?: "Audacity",
                    str("message") ?: "", buttons, choices, 0, blocking = true))
                val answer = waiter.await()
                if (choices.isNotEmpty()) buildJsonObject { put("choice", answer) }
                else buildJsonObject {
                    put("result", when { answer == 0 -> "yes"; answer == 1 -> "no"; answer == 2 || answer < 0 -> "cancel"; else -> "none" })
                }
            }
            "debug.progress" -> commandMutex.withLock {
                val stopped = simulateProgress("Progress test", stoppable = true, millis = ((num("seconds") ?: 1.0) * 1000).toLong())
                buildJsonObject { put("stopped", stopped) }
            }
            "effects.lastApplied" -> cmd(needsProject = false, allowBusy = true) {
                buildJsonObject { lastEffect?.let { put("id", it.id); put("name", it.name) } }
            }
            "display.trimCaches" -> JsonObject(emptyMap())
            "edit.clipboardInfo" -> EngineJson.json.encodeToJsonElement(ClipboardInfo.serializer(), clipboardInfo())
            "project.info" -> EngineJson.json.encodeToJsonElement(ProjectInfo.serializer(), projectInfo())
            "project.snapshot" -> { refreshSnapshot(); EngineJson.json.encodeToJsonElement(Snapshot.serializer(), snapshot.value) }
            "app.info" -> EngineJson.json.encodeToJsonElement(AppInfo.serializer(), appInfo())
            "settings.get" -> buildJsonObject { put("settings", EngineJson.json.encodeToJsonElement(Settings.serializer(), getSettings())) }
            "history.list" -> EngineJson.json.encodeToJsonElement(HistoryList.serializer(), history())
            "history.undo" -> { undo(); JsonObject(emptyMap()) }
            "history.redo" -> { redo(); JsonObject(emptyMap()) }
            "select.set" -> { select(num("t0") ?: 0.0, num("t1") ?: num("t0") ?: 0.0); JsonObject(emptyMap()) }
            "select.all" -> { selectAll(); JsonObject(emptyMap()) }
            "select.none" -> { selectNone(); JsonObject(emptyMap()) }
            "transport.stop" -> { stop(); JsonObject(emptyMap()) }
            else -> when {
                command.startsWith("edit.") -> { edit(command); JsonObject(emptyMap()) }
                command.startsWith("select.") -> { selectCommand(command); JsonObject(emptyMap()) }
                else -> throw EngineException(ErrorCodes.UNKNOWN_COMMAND, "$command is not supported by the fake engine")
            }
        }
    }

    /** Snapshot of the clip list, for tests: frames of channel 0 of a clip. */
    internal fun clipSamples(trackId: Long, clipIndex: Int, channel: Int = 0): FloatArray? = synchronized(lock) {
        work.track(trackId)?.clips?.getOrNull(clipIndex)?.channelCopy(channel)
    }

    companion object {
        const val DEFAULT_ZOOM = 86.1328125
        const val PENDING_TRACK_ID = -2L
        private const val TICK_MS = 50L
        private const val DEVICE_RATE = 48000.0
        private const val OUTPUT_LATENCY = 0.020
        private const val INPUT_LATENCY = 0.010
        private const val METER_RATE = 11025.0
        private const val RECORD_CHUNK = 44100
        private const val MAX_SAMPLE_RUN = 1L shl 20
        private const val MAX_SPECTRUM_FRAMES = 1 shl 22
        private const val SPECTROGRAM_WINDOW = 2048
        private const val SPECTROGRAM_GAIN = 20.0
        private const val SPECTROGRAM_RANGE = 80.0
        private val FORMATS = setOf("int16", "int24", "float")
        private val FORMAT_NAMES = mapOf("int16" to "16-bit PCM", "int24" to "24-bit PCM", "float" to "32-bit float")
        private val DITHERS = setOf("none", "rectangle", "triangle", "shaped")
        private val GROUP_BY = setOf("sortby:name", "sortby:publisher:name", "sortby:type:name", "groupby:publisher",
            "groupby:type", "default", "groupby:type:publisher")
        private val SPECTRUM_ALGORITHMS = setOf("spectrum", "autocorrelation", "cubeRootAutocorrelation", "enhancedAutocorrelation")

        /** Settings of API.md §5.1 as the engine writes them on first run. */
        val DEFAULT_SETTINGS = Settings(
            defaultRate = 44100, defaultFormat = "float", recordChannels = 1,
            outputDevice = "Default Output", inputDevice = "Default Input",
            latencyMs = 100.0, latencyCorrectionMs = -130.0, overdub = true, swPlaythrough = false,
            preRollSec = 5.0, crossfadeMs = 10.0, realtimeDither = "none", hqDither = "shaped",
            effectsGroupBy = "default", soloMode = "Simple", editClipsCanMove = true, selectAllOnNone = false,
        )
    }
}
