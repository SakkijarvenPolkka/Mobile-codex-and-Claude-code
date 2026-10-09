/*
 * Audacity Android port — engine data model.
 *
 * Kotlin mirror of the JSON payloads specified in native/bridge/API.md.
 * Field names match the JSON keys exactly. Unknown keys are ignored when
 * decoding (EngineJson.json has ignoreUnknownKeys = true), so the engine may
 * add fields without breaking the app.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Shared JSON configuration for everything that crosses the bridge. */
object EngineJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
        allowSpecialFloatingPointValues = true
    }
}

// ---------------------------------------------------------------------------
// §3.1 Response envelope
// ---------------------------------------------------------------------------

@Serializable
data class EngineErrorInfo(
    val code: String,
    val message: String = "",
)

@Serializable
data class Envelope(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: EngineErrorInfo? = null,
    val generation: Long = 0,
)

/** Error codes of API.md §3.1. */
object ErrorCodes {
    const val NOT_READY = "NOT_READY"
    const val NO_PROJECT = "NO_PROJECT"
    const val AUDIO_BUSY = "AUDIO_BUSY"
    const val NO_SELECTION = "NO_SELECTION"
    const val INVALID_ARGS = "INVALID_ARGS"
    const val NOT_FOUND = "NOT_FOUND"
    const val STALE = "STALE"
    const val NEEDS_PATH = "NEEDS_PATH"
    const val CANCELLED = "CANCELLED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val FAILED = "FAILED"
    const val INTERNAL = "INTERNAL"
    const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
}

// ---------------------------------------------------------------------------
// §2.1 Start configuration
// ---------------------------------------------------------------------------

@Serializable
data class StartConfig(
    val filesDir: String,
    val noBackupDir: String,
    val cacheDir: String,
    val nyquistDir: String,
    val pluginsDir: String,
    val locale: String,
    val deviceModel: String = "",
    val audioOutputSampleRate: Int = 0,
    val audioFramesPerBuffer: Int = 0,
    val recordPermission: Boolean = false,
)

// ---------------------------------------------------------------------------
// §4 Events
// ---------------------------------------------------------------------------

@Serializable
data class SelfCheck(val name: String, val ok: Boolean, val message: String = "")

@Serializable
data class EngineReady(
    val audacityVersion: String = "",
    val selfChecks: List<SelfCheck> = emptyList(),
    val recoverable: Int = 0,
)

@Serializable
data class EngineFailed(val message: String = "")

@Serializable
data class ProjectState(
    val open: Boolean = false,
    val name: String = "",
    val path: String? = null,
    val temporary: Boolean = true,
    val dirty: Boolean = false,
    val rate: Double = 44100.0,
    val defaultFormat: String = "float",
)

@Serializable
data class ClipState(
    val index: Int,
    val name: String = "",
    val start: Double,
    val end: Double,
    val trimLeft: Double = 0.0,
    val trimRight: Double = 0.0,
    val stretchRatio: Double = 1.0,
    val rate: Double = 44100.0,
)

@Serializable
data class LabelState(
    val index: Int,
    val t0: Double,
    val t1: Double,
    val title: String = "",
)

@Serializable
data class TrackState(
    val id: Long,
    val kind: String,                 // "wave" | "label" | "time" | "note" | "other"
    val name: String = "",
    val selected: Boolean = false,
    val focused: Boolean = false,
    val channels: Int = 1,
    val rate: Double = 44100.0,
    val format: String = "float",
    val gain: Double = 1.0,
    val pan: Double = 0.0,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val waveVersion: Long = 0,
    val clips: List<ClipState> = emptyList(),
    val labels: List<LabelState> = emptyList(),
) {
    val isWave: Boolean get() = kind == "wave"
    val isLabel: Boolean get() = kind == "label"
}

@Serializable
data class TimeRange(val t0: Double = 0.0, val t1: Double = 0.0) {
    val isPoint: Boolean get() = t0 == t1
    val duration: Double get() = t1 - t0
}

@Serializable
data class PlayRegionState(val active: Boolean = false, val t0: Double = 0.0, val t1: Double = 0.0)

@Serializable
data class HistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undo: String = "",
    val redo: String = "",
)

@Serializable
data class ViewState(val zoom: Double = 86.1328125, val hpos: Double = 0.0)

@Serializable
data class AudioBusyState(val busy: Boolean = false)

@Serializable
data class ClipboardState(val empty: Boolean = true, val duration: Double = 0.0)

@Serializable
data class LastEffect(val id: String? = null, val name: String? = null)

/** The `snapshot` event (API.md §4.2). */
@Serializable
data class Snapshot(
    val generation: Long = 0,
    val project: ProjectState = ProjectState(),
    val tracks: List<TrackState> = emptyList(),
    val selection: TimeRange = TimeRange(),
    val playRegion: PlayRegionState = PlayRegionState(),
    val history: HistoryState = HistoryState(),
    val view: ViewState = ViewState(),
    val audio: AudioBusyState = AudioBusyState(),
    val clipboard: ClipboardState = ClipboardState(),
    val lastEffect: LastEffect? = null,
    val lastGenerator: LastEffect? = null,
    val lastAnalyzer: LastEffect? = null,
    val lastTool: LastEffect? = null,
    /** Menu-enable bitset, see [CommandFlags] and API.md §4.2. */
    val flags: Long = 0,
) {
    fun has(flag: Long): Boolean = (flags and flag) == flag

    val selectedTracks: List<TrackState> get() = tracks.filter { it.selected }
    val hasTimeSelection: Boolean get() = selection.t1 > selection.t0
    val projectEnd: Double get() = tracks.maxOfOrNull { trackEnd(it) } ?: 0.0

    /** The track with this id, or null. */
    fun track(id: Long): TrackState? = tracks.firstOrNull { it.id == id }

    /** True when a menu item requiring [required] flags is enabled. */
    fun enabled(required: Long): Boolean = CommandFlags.enabled(required, flags)

    private fun trackEnd(t: TrackState): Double =
        if (t.isLabel) t.labels.maxOfOrNull { it.t1 } ?: 0.0 else t.end

    companion object {
        val EMPTY = Snapshot()
    }
}

/** Bits of [Snapshot.flags] (API.md §4.2). A menu item is enabled when
 *  `(required and flags.inv()) == 0L`. */
object CommandFlags {
    const val NB = 1L shl 0
    const val BUSY = 1L shl 1
    const val TS = 1L shl 2
    const val WS = 1L shl 3
    const val TE = 1L shl 4
    const val ES = 1L shl 5
    const val AS = 1L shl 6
    const val CNB = 1L shl 7
    const val LE = 1L shl 8
    const val UA = 1L shl 9
    const val RA = 1L shl 10
    const val ZI = 1L shl 11
    const val ZO = 1L shl 12
    const val WE = 1L shl 13
    const val SL = 1L shl 14
    const val NSL = 1L shl 15
    const val ST = 1L shl 16
    const val PAUSED = 1L shl 17
    const val CS = 1L shl 18
    const val CC = 1L shl 19
    const val JC = 1L shl 20
    const val LS = 1L shl 21
    const val HW = 1L shl 22
    const val LAST_EFF = 1L shl 23
    const val LAST_GEN = 1L shl 24
    const val LAST_ANA = 1L shl 25
    const val LAST_TOOL = 1L shl 26
    const val TFOCUS = 1L shl 27
    const val CLIPSEL = 1L shl 28
    const val STRETCHSEL = 1L shl 29
    const val PLAYABLE = 1L shl 30
    const val NO_TIMETRACK = 1L shl 31
    const val FOC = 1L shl 32
    const val LABEL_TEXT_SEL = 1L shl 33
    const val PROJECT_OPEN = 1L shl 34
    const val CLIPBOARD = 1L shl 35
    const val RECORD_PERMISSION = 1L shl 36

    fun enabled(required: Long, flags: Long): Boolean = (required and flags.inv()) == 0L
}

/** The `progress` event (API.md §4.3). */
@Serializable
data class ProgressEvent(
    val id: Int,
    val phase: String,                // "begin" | "update" | "end"
    val title: String = "",
    val message: String = "",
    val fraction: Double = 0.0,
    val cancellable: Boolean = true,
    val stoppable: Boolean = false,
)

/** The `dialog` event (API.md §4.4). */
@Serializable
data class DialogEvent(
    val id: Int,
    val kind: String = KIND_MESSAGE,  // "message" | "choice" | "multiChoice"
    val style: String = "info",       // "info" | "warning" | "error" | "question"
    val title: String = "",
    val message: String = "",
    val buttons: List<String> = listOf("OK"),
    val choices: List<String> = emptyList(),
    /** `kind:"multiChoice"`: initially checked choices (same length as [choices]). */
    val defaultChecked: List<Boolean> = emptyList(),
    val defaultButton: Int = 0,
    val blocking: Boolean = false,
    val helpPage: String = "",
) {
    val isMultiChoice: Boolean get() = kind == KIND_MULTI_CHOICE

    /** Indices checked initially (a multi-choice dialog). */
    val defaultIndices: List<Int> get() = choices.indices.filter { defaultChecked.getOrElse(it) { false } }

    companion object {
        const val KIND_MESSAGE = "message"
        const val KIND_CHOICE = "choice"
        const val KIND_MULTI_CHOICE = "multiChoice"
    }
}

/** The `transport` event (API.md §4.5). */
@Serializable
data class TransportEvent(
    val state: String,                // "stopped" | "playing" | "recording" | "paused" | "monitoring"
    val reason: String = "user",
    val message: String = "",
    val dropouts: Int = 0,
)

@Serializable
data class LogEvent(val level: String = "info", val message: String = "")

// ---------------------------------------------------------------------------
// §5 Payloads
// ---------------------------------------------------------------------------

/** §5.1. All fields optional when sent to `settings.set` (partial update). */
@Serializable
data class Settings(
    val defaultRate: Int? = null,
    val defaultFormat: String? = null,
    val recordChannels: Int? = null,
    val outputDevice: String? = null,
    val inputDevice: String? = null,
    val latencyMs: Double? = null,
    val latencyCorrectionMs: Double? = null,
    val overdub: Boolean? = null,
    val swPlaythrough: Boolean? = null,
    val preRollSec: Double? = null,
    val crossfadeMs: Double? = null,
    val realtimeDither: String? = null,
    val hqDither: String? = null,
    val effectsGroupBy: String? = null,
    val soloMode: String? = null,
    val editClipsCanMove: Boolean? = null,
    val selectAllOnNone: Boolean? = null,
    /** /GUI/SyncLockTracks (also switches the open project: flags SL/NSL). */
    val syncLock: Boolean? = null,
    val pasteAsNewClips: Boolean? = null,
    val moveSelectionWithTracks: Boolean? = null,
    /** R behaves like Shift+R (Record New Track). */
    val preferNewTrackRecord: Boolean? = null,
    /** /Warnings/DropoutDetected */
    val dropoutDetection: Boolean? = null,
    /** "system" | "en" | another code of [AppInfo.languages]; the engine's string language. */
    val language: String? = null,
)

@Serializable
data class SettingsResult(val settings: Settings)

/** §5.2 */
@Serializable
data class ProjectInfo(
    val open: Boolean = false,
    val name: String = "",
    val path: String? = null,
    val temporary: Boolean = true,
    val dirty: Boolean = false,
    val rate: Double = 44100.0,
    val defaultFormat: String = "float",
    val durationSec: Double = 0.0,
    val tracks: Int = 0,
)

@Serializable
data class ProjectFileEntry(
    val path: String,
    val name: String = "",
    val modifiedMs: Long = 0,
    val sizeBytes: Long = 0,
)

@Serializable
data class ProjectFileList(val projects: List<ProjectFileEntry> = emptyList())

/** `project.compactInfo` (API.md §3.3): the numbers of the desktop's Compact question. */
@Serializable
data class CompactInfo(
    /** All sample blocks in the database. */
    val totalBytes: Long = 0,
    /** The blocks compaction keeps (the current and the last saved state). */
    val usedBytes: Long = 0,
    /** `.aup3` + `-wal` size. */
    val fileBytes: Long = 0,
    /** Free space on the project's file system, -1 = unknown. */
    val freeBytes: Long = -1,
) {
    /** ≈ what compaction can recover. */
    val reclaimableBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0)
}

@Serializable
data class CompactResult(val freedBytes: Long = 0)

/** `labels.export` result: the file and the number of labels written. */
@Serializable
data class LabelExportResult(val path: String = "", val labels: Int = 0)

@Serializable
data class Tag(val name: String, val value: String)

@Serializable
data class TagList(val tags: List<Tag> = emptyList())

@Serializable
data class HistoryEntry(
    val index: Int,
    val description: String = "",
    val shortDescription: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class HistoryList(val current: Int = 0, val states: List<HistoryEntry> = emptyList())

/** Result of `edit.splitAt`: the tracks that were split (none = nothing changed). */
@Serializable
data class SplitResult(val splits: Int = 0, val trackIds: List<Long> = emptyList())

/** Result of `clips.trim`: the clip after the (clamped) trim. */
@Serializable
data class ClipTrimResult(
    val trimLeft: Double = 0.0,
    val trimRight: Double = 0.0,
    val start: Double = 0.0,
    val end: Double = 0.0,
)

@Serializable
data class ClipboardInfo(
    val empty: Boolean = true,
    val t0: Double = 0.0,
    val t1: Double = 0.0,
    val trackCount: Int = 0,
)

/** §5.3 */
@Serializable
data class AudioDevice(
    val index: Int,
    val name: String,
    val hostApi: String = "AAudio",
    val maxInputChannels: Int = 0,
    val maxOutputChannels: Int = 0,
    val defaultRate: Double = 48000.0,
    val isDefault: Boolean = false,
)

@Serializable
data class CurrentDevices(val output: String = "", val input: String = "", val recordChannels: Int = 1)

@Serializable
data class AudioDevices(
    val outputs: List<AudioDevice> = emptyList(),
    val inputs: List<AudioDevice> = emptyList(),
    val current: CurrentDevices = CurrentDevices(),
    /** An `audio.setDevices` list waits for the stream to stop. */
    val pending: Boolean = false,
)

/**
 * One `android.media.AudioDeviceInfo` for `audio.setDevices` (API.md §3.3):
 * native code cannot enumerate Android devices, Kotlin passes
 * `AudioManager.getDevices(GET_DEVICES_ALL)`. [type] is
 * `AudioDeviceInfo.TYPE_*`; empty [channelCounts]/[sampleRates] = any.
 */
@Serializable
data class AudioDeviceSpec(
    val id: Int,
    val name: String,
    val type: Int,
    val isSource: Boolean,
    val isSink: Boolean,
    val channelCounts: List<Int> = emptyList(),
    val sampleRates: List<Int> = emptyList(),
)

@Serializable
data class SetDevicesResult(val applied: Boolean = false)

/** `audio.latency` (API.md §3.3). */
@Serializable
data class LatencyInfo(
    val outputLatencyMs: Double = 0.0,
    val inputLatencyMs: Double = 0.0,
    /** The /AudioIO/LatencyCorrection an overdub would use now (= −duplexOffsetMs + userTrimMs). */
    val correctionMs: Double = 0.0,
    val duplexOffsetMs: Double = 0.0,
    /** false = an estimate (another route's measurement or the device latencies). */
    val measured: Boolean = false,
    /** The user trim (settings `latencyCorrectionMs`). */
    val userTrimMs: Double = 0.0,
)

/** §5.4 */
@Serializable
data class EffectInfo(
    val id: String,
    val name: String,
    val type: String,                 // "process" | "generate" | "analyze" | "tool"
    val family: String = "Audacity",
    val vendor: String = "",
    val description: String = "",
    val interactive: Boolean = true,
    val realtime: Boolean = false,
    val isDefault: Boolean = true,
    val special: String? = null,
)

@Serializable
data class MenuSection(val title: String? = null, val ids: List<String> = emptyList())

@Serializable
data class EffectMenus(
    val generate: List<MenuSection> = emptyList(),
    val effect: List<MenuSection> = emptyList(),
    val analyze: List<MenuSection> = emptyList(),
    val tools: List<MenuSection> = emptyList(),
)

@Serializable
data class EffectList(
    val effects: List<EffectInfo> = emptyList(),
    val menus: EffectMenus = EffectMenus(),
)

/** One parameter of §5.5. `value`/`default` are numbers, booleans or strings
 *  depending on [kind]; for enums they are choice indices. */
@Serializable
data class EffectParam(
    val key: String,
    val label: String = "",
    val kind: String,                 // "bool" | "int" | "double" | "enum" | "string"
    val min: Double? = null,
    val max: Double? = null,
    val scale: Double? = null,
    @SerialName("default") val defaultValue: JsonElement? = null,
    val value: JsonElement? = null,
    val unit: String = "",
    /** "" | [EffectParam.DISPLAY_DB] | [EffectParam.DISPLAY_RATIO] (API.md §5.5). */
    val display: String = "",
    val choices: List<String> = emptyList(),
    val choiceLabels: List<String> = emptyList(),
    /** A pitch ratio (`display == "ratio"`): the UI may also show 12·log2(ratio) semitones. */
    val semitones: Boolean = false,
) {
    /** The UI shows and edits a multiplier `1 + value/100` (1.25 ⇔ +25 %). */
    val isRatio: Boolean get() = display == DISPLAY_RATIO

    companion object {
        /** The UI shows and edits 20·log10(value). */
        const val DISPLAY_DB = "dB"
        /** A percent change the UI shows and edits as the multiplier `1 + value/100`. */
        const val DISPLAY_RATIO = "ratio"

        /** Percent change → multiplier (25 → 1.25). */
        fun percentToRatio(percent: Double): Double = 1.0 + percent / 100.0
        /** Multiplier → percent change (0.65 → -35). */
        fun ratioToPercent(ratio: Double): Double = (ratio - 1.0) * 100.0
        /** Multiplier → semitones (2 → 12). NaN for ratio <= 0. */
        fun ratioToSemitones(ratio: Double): Double =
            if (ratio > 0) 12.0 * kotlin.math.ln(ratio) / kotlin.math.ln(2.0) else Double.NaN
        /** Semitones → multiplier (12 → 2). */
        fun semitonesToRatio(semitones: Double): Double = Math.pow(2.0, semitones / 12.0)
    }
}

@Serializable
data class EffectPresets(val factory: List<String> = emptyList(), val user: List<String> = emptyList())

@Serializable
data class EqPoint(val f: Double, val dB: Double)

@Serializable
data class EqCurve(val points: List<EqPoint> = emptyList(), val linearFreq: Boolean = false)

/** §5.5 */
@Serializable
data class EffectDescription(
    val id: String,
    val name: String = "",
    val type: String = "process",
    val params: List<EffectParam> = emptyList(),
    val presets: EffectPresets = EffectPresets(),
    val supportsDuration: Boolean = false,
    val duration: Double = 30.0,
    val special: String? = null,
    val profileCaptured: Boolean = false,
    val curve: EqCurve? = null,
    val curves: List<String> = emptyList(),
    val nyquist: JsonObject? = null,
    val help: String = "",
)

@Serializable
data class EffectApplyResult(val applied: Boolean = false, val message: String? = null)

@Serializable
data class SpectrumResult(
    val rate: Double = 44100.0,
    val binHz: Double? = null,
    val binSeconds: Double? = null,
    val values: List<Float> = emptyList(),
    val minValue: Double = 0.0,
    val maxValue: Double = 0.0,
    val algorithm: String = "spectrum",
    val size: Int = 0,
    val warning: String? = null,
)

/** `analyze.contrast`; digital silence is −1000 dB with `…Silent:true`. */
@Serializable
data class ContrastResult(
    val foregroundDb: Double = 0.0,
    val backgroundDb: Double = 0.0,
    val differenceDb: Double = 0.0,
    val passes: Boolean = false,
    /** Translated WCAG verdict of src/effects/Contrast.cpp (engine language). */
    val verdict: String = "",
    val foregroundSilent: Boolean = false,
    val backgroundSilent: Boolean = false,
)

@Serializable
data class ImportGroup(val description: String, val extensions: List<String> = emptyList())

@Serializable
data class ImportFormats(
    val groups: List<ImportGroup> = emptyList(),
    val extensions: List<String> = emptyList(),
)

@Serializable
data class ImportResult(val trackIds: List<Long> = emptyList(), val messages: List<String> = emptyList())

/** §5.6 */
@Serializable
data class ExportFormat(
    val key: String,
    val description: String = "",
    val extensions: List<String> = emptyList(),
    val maxChannels: Int = 2,
    val canMetaData: Boolean = false,
)

@Serializable
data class ExportFormatList(val formats: List<ExportFormat> = emptyList())

/** Tagged variant value of an export option: t = "i" | "d" | "b" | "s". */
@Serializable
data class ExportValue(val t: String, val v: JsonElement)

@Serializable
data class ExportOption(
    val id: Int,
    val title: String = "",
    val type: String = "int",          // "enum" | "range" | "bool" | "int" | "double" | "string"
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
    val value: ExportValue? = null,
    val values: List<ExportValue> = emptyList(),
    val names: List<String> = emptyList(),
)

@Serializable
data class ExportOptions(
    val format: ExportFormat,
    val sampleRates: List<Int> = emptyList(),
    val options: List<ExportOption> = emptyList(),
)

@Serializable
data class ExportDefaults(
    val hasSelection: Boolean = false,
    val defaultChannels: Int = 2,
    val maxChannels: Int = 2,
    val defaultRate: Int = 44100,
    val rates: List<Int> = emptyList(),
)

@Serializable
data class PathResult(val path: String = "")

@Serializable
data class AppInfo(
    val audacityVersion: String = "",
    val engineVersion: String = "",
    val wxVersion: String = "",
    val sqliteVersion: String = "",
    val abi: String = "",
    val libraries: List<String> = emptyList(),
    val importers: List<String> = emptyList(),
    val exporters: List<String> = emptyList(),
    val effectsCount: Int = 0,
    val nyquist: Boolean = false,
    /** Language of the engine's strings now ("en", "ko"). */
    val language: String = "en",
    /** "en" + the installed catalogs (valid `language` settings besides "system"). */
    val languages: List<String> = listOf("en"),
)

// ---------------------------------------------------------------------------
// §6 Realtime data
// ---------------------------------------------------------------------------

/** Decoded `readTransport` array (API.md §6.4). */
data class TransportSample(
    val state: Int,
    val streamTime: Double,
    val displayTime: Double,
    val sampledAtNanos: Long,
    val loopT0: Double,
    val loopT1: Double,
    val looping: Boolean,
    val deviceRate: Double,
    val outputLatency: Double,
    val inputLatency: Double,
    val capturing: Boolean,
    val speed: Double,
    val recordingStart: Double,
    val generation: Long,
) {
    val isPlaying: Boolean get() = state == STATE_PLAYING
    val isRecording: Boolean get() = state == STATE_RECORDING
    val isPaused: Boolean get() = state == STATE_PAUSED_PLAY || state == STATE_PAUSED_RECORD
    val isActive: Boolean get() = state in STATE_PLAYING..STATE_PAUSED_RECORD
    val isMonitoring: Boolean get() = state == STATE_MONITORING

    /**
     * Play/record head for drawing at [nowNanos] (`System.nanoTime()`, the
     * same clock as `CLOCK_MONOTONIC`): [displayTime] extrapolated by the time
     * elapsed since [sampledAtNanos] while playing or recording (not paused),
     * clamped to the loop end while looping and to [playEnd] while playing
     * (API.md §6.4). NaN when there is no stream time.
     */
    fun headTime(nowNanos: Long, playEnd: Double = Double.POSITIVE_INFINITY): Double {
        if (displayTime.isNaN()) return displayTime
        if (state != STATE_PLAYING && state != STATE_RECORDING) return displayTime
        val elapsed = (nowNanos - sampledAtNanos).coerceAtLeast(0L) / 1e9
        val t = displayTime + elapsed * (if (speed > 0.0) speed else 1.0)
        if (state == STATE_RECORDING) return t
        val end = if (looping && loopT1 > loopT0) loopT1 else playEnd
        return if (t > end) end else t
    }

    companion object {
        const val SIZE = 16
        const val STATE_STOPPED = 0
        const val STATE_PLAYING = 1
        const val STATE_RECORDING = 2
        const val STATE_PAUSED_PLAY = 3
        const val STATE_PAUSED_RECORD = 4
        const val STATE_MONITORING = 5
        const val STATE_STOPPING = 6

        val IDLE = TransportSample(
            STATE_STOPPED, Double.NaN, Double.NaN, 0, 0.0, 0.0, false,
            0.0, 0.0, 0.0, false, 1.0, 0.0, 0,
        )

        fun decode(a: DoubleArray): TransportSample = TransportSample(
            state = a[0].toInt(),
            streamTime = a[1],
            displayTime = a[2],
            sampledAtNanos = a[3].toLong(),
            loopT0 = a[4],
            loopT1 = a[5],
            looping = a[6] != 0.0,
            deviceRate = a[7],
            outputLatency = a[8],
            inputLatency = a[9],
            capturing = a[10] != 0.0,
            speed = a[11],
            recordingStart = a[12],
            generation = a[13].toLong(),
        )
    }
}

/** Decoded `readMeters` array (API.md §6.5). Linear amplitudes. */
data class MeterSample(
    val playPeak: FloatArray,
    val playRms: FloatArray,
    val playClip: BooleanArray,
    val recPeak: FloatArray,
    val recRms: FloatArray,
    val recClip: BooleanArray,
    val playChannels: Int,
    val recChannels: Int,
) {
    companion object {
        const val SIZE = 14

        fun decode(a: FloatArray): MeterSample = MeterSample(
            playPeak = floatArrayOf(a[0], a[1]),
            playRms = floatArrayOf(a[2], a[3]),
            playClip = booleanArrayOf(a[4] != 0f, a[5] != 0f),
            recPeak = floatArrayOf(a[6], a[7]),
            recRms = floatArrayOf(a[8], a[9]),
            recClip = booleanArrayOf(a[10] != 0f, a[11] != 0f),
            playChannels = a[12].toInt(),
            recChannels = a[13].toInt(),
        )
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

// ---------------------------------------------------------------------------
// §7 Display
// ---------------------------------------------------------------------------

object Zoom {
    const val MIN_LEVEL = -160
    const val MAX_LEVEL = 160
    const val TILE_COLUMNS = 256

    /** pps(level) = 2^(level / 8.0) — must match the native expression. */
    fun ppsForLevel(level: Int): Double = Math.pow(2.0, level / 8.0)

    /** The highest level whose pps does not exceed [pps]. */
    fun levelAtOrBelow(pps: Double): Int {
        if (!(pps > 0.0)) return MIN_LEVEL
        var level = Math.floor(8.0 * (Math.log(pps) / Math.log(2.0)))
            .coerceIn(MIN_LEVEL.toDouble(), MAX_LEVEL.toDouble()).toInt()
        while (level < MAX_LEVEL && ppsForLevel(level + 1) <= pps) level++
        while (level > MIN_LEVEL && ppsForLevel(level) > pps) level--
        return level.coerceIn(MIN_LEVEL, MAX_LEVEL)
    }

    /** Horizontal scale for drawing tiles of [levelAtOrBelow] at the exact
     *  [zoom] (`zoom / pps(level)`, in [1, 1.09) inside the level range). */
    fun drawScale(zoom: Double): Double = zoom / ppsForLevel(levelAtOrBelow(zoom))

    /** Absolute column containing time [t] at [level] (column c covers
     *  `[c/pps, (c+1)/pps)`). */
    fun columnAt(t: Double, level: Int): Long = Math.floor(t * ppsForLevel(level)).toLong()

    /** Start time of column [column] at [level]. */
    fun timeOfColumn(column: Long, level: Int): Double = column / ppsForLevel(level)

    /** First column of the 256-column tile that contains [column]. */
    fun tileStart(column: Long): Long = Math.floorDiv(column, TILE_COLUMNS.toLong()) * TILE_COLUMNS

    /** Column mode vs sample mode for a clip (API.md §7.1, per clip:
     *  sample mode when `pps > 0.5 · rate / stretchRatio`). */
    fun needsSampleMode(pps: Double, rate: Double, stretchRatio: Double = 1.0): Boolean =
        pps > 0.5 * rate / (if (stretchRatio > 0.0) stretchRatio else 1.0)
}

/** Return codes of the display functions (API.md §7.2). */
object DisplayStatus {
    const val NO_TRACK = -1L
    const val PARTIAL_EMPTY = -2L
    const val NOT_READY = -3L
    const val SAMPLE_MODE = -4L
    const val UNSUPPORTED = -5L
    const val PARTIAL_BIT = 1L shl 62

    fun isPartial(v: Long): Boolean = v >= 0 && (v and PARTIAL_BIT) != 0L
    fun version(v: Long): Long = v and PARTIAL_BIT.inv()
}
