/*
 * Audacity Android port — hoisted editor view state (zoom, scroll, track heights).
 *
 * Zoom/scroll math is a Kotlin port of Audacity 3.7.9 lib-viewport
 * (Viewport::ZoomAboutSelection / ZoomAboutCenter / ZoomFitHorizontally /
 * ScrollIntoView) and src/menus/ViewMenus.cpp (GetZoomOfSelection), with
 * ZoomInfo's conversions (TimeToPosition / PositionToTime). Audacity, the
 * Audacity Team; GPL-2.0-or-later.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * View state of the editor, hoisted so that toolbars, menus and the track
 * panel share it. It survives recomposition and configuration changes
 * ([rememberEditorState]).
 *
 * Units: [pps] is in density-independent pixels per second (1 desktop px =
 * 1 dp, so a project saved on the desktop opens at the same visual scale),
 * [hpos] is the project time at the left edge of the waveform area. The
 * editor persists both with `engine.setView` (debounced) and adopts the
 * project's saved view when a project is opened.
 */
@Stable
class EditorState(initialPps: Double = DEFAULT_ZOOM, initialHpos: Double = 0.0) {

    /** Zoom: dp per second (ViewInfo zoom). */
    var pps: Double by mutableDoubleStateOf(clampZoom(initialPps))
        private set

    /** Time at the left edge of the waveform area (ViewInfo hpos), ≥ 0. */
    var hpos: Double by mutableDoubleStateOf(max(0.0, initialHpos))
        private set

    /** Page the view while playing/recording when the head leaves it
     *  (Audacity "Auto-scroll if head unpinned", default on). */
    var followPlayhead: Boolean by mutableStateOf(true)

    /** Draw the RMS band inside the min/max column (`/GUI/ShowRMS`). */
    var showRms: Boolean by mutableStateOf(true)

    /** Draw clipped columns in the clipping colour (`/GUI/ShowClipping`). */
    var showClipping: Boolean by mutableStateOf(false)

    /** Width of the waveform area in dp (set by [EditorScreen]; 0 before layout). */
    var viewportWidthDp: Float by mutableFloatStateOf(0f)
        internal set

    /** Vertical scroll of the track panel in dp. */
    var vposDp: Float by mutableFloatStateOf(0f)
        internal set

    /**
     * Stop drags at the end of the audio: a time selection, the loop (play
     * region) band, the cursor handle and typed selection times are clamped
     * to [0, end] and snap exactly to the end within 16 dp of it (end = the
     * dragged track's end for a single-track selection, else the project
     * end). Default on (mobile).
     */
    var stopAtTrackEnd: Boolean by mutableStateOf(true)

    /** Snapping of drags to clip/label edges, track ends, cursor, play head (and grid). */
    var snapping: SnapMode by mutableStateOf(SnapMode.EDGES)

    /** Tool of the track panel: [EditTool.SPLIT] = a tap on a clip splits it (razor). */
    var tool: EditTool by mutableStateOf(EditTool.SELECT)

    /** Time of the snap guide line shown while a drag is snapped (NaN = none). */
    internal var snapGuide: Double by mutableDoubleStateOf(Double.NaN)

    /** Tracks shown as spectrogram (Waveform / Spectrogram view of the track menu). */
    var spectrogramTracks: Set<Long> by mutableStateOf(emptySet())
        private set

    private val heights = mutableStateMapOf<Long, Float>()
    private val collapsed = mutableStateMapOf<Long, Boolean>()
    private val mixerOpen = mutableStateMapOf<Long, Boolean>()

    /** Bumped on every zoom/scroll made through this object (view persistence). */
    internal var viewChangeCount by mutableIntStateOf(0)
        private set

    // Context fed by EditorScreen from the latest snapshot / transport (not
    // observable state: only read when a command runs).
    internal var selectionT0: Double = 0.0
    internal var selectionT1: Double = 0.0
    internal var projectEnd: Double = 0.0
    /** Play/record head while audio is streaming and not paused, else NaN. */
    internal var streamingHeadTime: Double = Double.NaN
    /** Displayed play/record head while the transport is active (also paused), else NaN. */
    internal var headTime: Double = Double.NaN
    /** True while a touch gesture owns the view (suppresses auto-scrolling). */
    internal var gestureActive: Boolean = false
    /** Last view sent with `engine.setView` (to tell our echo from an engine change). */
    internal var lastSentZoom: Double = Double.NaN
    internal var lastSentHpos: Double = Double.NaN
    internal var totalContentHeightDp: Float = 0f
    internal var viewportHeightDp: Float = 0f

    /** Effective width used by the zoom math (360 dp before the first layout). */
    val usableWidthDp: Double
        get() = if (viewportWidthDp > 0f) viewportWidthDp.toDouble() else FALLBACK_WIDTH_DP

    /** Visible duration in seconds. */
    val screenDuration: Double get() = usableWidthDp / pps

    /** Time at the right edge of the waveform area (ViewInfo::GetScreenEndTime). */
    val screenEndTime: Double get() = hpos + screenDuration

    /** ZoomInfo::TimeToPosition with origin 0, in dp (not rounded). */
    fun timeToX(t: Double): Double = pps * (t - hpos)

    /** ZoomInfo::PositionToTime with origin 0, x in dp. */
    fun xToTime(x: Double): Double = hpos + x / pps

    // ------------------------------------------------------------------
    // Zoom commands (View ▸ Zoom)
    // ------------------------------------------------------------------

    /** View ▸ Zoom ▸ Zoom In: ×2 about the selection (OnZoomIn); while
     *  audio streams, ×2 and scroll the head into view. */
    fun zoomIn() {
        val head = streamingHeadTime
        if (!head.isNaN()) {
            zoomBy(2.0)
            scrollIntoView(head)
        } else {
            zoomAboutSelection(2.0)
        }
    }

    /** View ▸ Zoom ▸ Zoom Out: ×0.5 about the centre (OnZoomOut). */
    fun zoomOut() = zoomAboutCenter(0.5)

    /** View ▸ Zoom ▸ Zoom Normal: 44100/512 px/s (OnZoomNormal). */
    fun zoomNormal() = zoomTo(DEFAULT_ZOOM)

    /** View ▸ Zoom ▸ Fit to Width (Viewport::ZoomFitHorizontally). */
    fun zoomToFit(projectEnd: Double) {
        if (!(projectEnd > 0.0)) return
        val w = usableWidthDp - 10.0
        if (w <= 0.0) return
        setView(w / projectEnd, 0.0)
    }

    /** View ▸ Zoom ▸ Zoom to Selection (OnZoomSel + GetZoomOfSelection). */
    fun zoomToSelection(t0: Double, t1: Double) {
        val lower = max(t0, 0.0)
        val denom = t1 - lower
        if (!(denom > 0.0)) return
        setView((usableWidthDp - 1.0) / denom, t0)
    }

    /** Ensures [t] is visible: when it is outside the view, centres it
     *  (Viewport::ScrollIntoView). */
    fun scrollToTime(t: Double) = scrollIntoView(t)

    /** Zoom about a focus point [focusX] (dp from the left of the waveform
     *  area), keeping the time under it fixed — pinch and Ctrl+wheel. */
    fun zoomBy(multiplier: Double, focusX: Double? = null) {
        if (!(multiplier > 0.0)) return
        val fx = focusX ?: 0.0
        val tFocus = xToTime(fx)
        val newPps = clampZoom(pps * multiplier)
        val newH = if (focusX == null) hpos else tFocus - fx / newPps
        setView(newPps, newH)
    }

    /** Sets zoom (clamped to Audacity's 0.001…6e6) and scroll (≥ 0). */
    fun setView(zoom: Double, newHpos: Double) {
        val z = clampZoom(zoom)
        val h = if (newHpos.isNaN()) hpos else max(0.0, newHpos)
        if (z == pps && h == hpos) return
        pps = z
        hpos = h
        viewChangeCount++
    }

    /** Scrolls by [dxDp] dp (positive = later times), clamped to the
     *  scrollable range of Viewport::UpdateScrollbarsForTracks. */
    fun scrollByDp(dxDp: Double) {
        if (dxDp == 0.0) return
        val target = hpos + dxDp / pps
        val upper = max(maxScrollTime(), hpos)
        setView(pps, target.coerceIn(0.0, max(upper, 0.0)))
    }

    /** Upper bound for [hpos]: last time + 1/4 screen − screen. */
    internal fun maxScrollTime(): Double {
        val last = max(projectEnd, selectionT1)
        return max(0.0, last + screenDuration / 4.0 - screenDuration)
    }

    /** Scrolls vertically by [dyDp]; returns the amount actually scrolled. */
    fun scrollVerticallyByDp(dyDp: Float): Float {
        val maxV = max(0f, totalContentHeightDp - viewportHeightDp + 32f)
        val old = vposDp
        vposDp = (vposDp + dyDp).coerceIn(0f, maxV)
        return vposDp - old
    }

    internal fun clampVertical() {
        val maxV = max(0f, totalContentHeightDp - viewportHeightDp + 32f)
        if (vposDp > maxV) vposDp = maxV
    }

    // ------------------------------------------------------------------
    // Track heights
    // ------------------------------------------------------------------

    /** Sets the expanded height of a track (dp, ≥ 40), like dragging its bottom edge. */
    fun setTrackHeight(trackId: Long, heightDp: Float) {
        heights[trackId] = heightDp.coerceIn(MIN_TRACK_HEIGHT_DP, MAX_TRACK_HEIGHT_DP)
    }

    /** Collapses / expands a track (TCP chevron). */
    fun toggleCollapsed(trackId: Long) {
        collapsed[trackId] = !(collapsed[trackId] ?: false)
    }

    /** Explicit height set with [setTrackHeight], or null (layout default). */
    fun trackHeightDp(trackId: Long): Float? = heights[trackId]

    fun isCollapsed(trackId: Long): Boolean = collapsed[trackId] == true

    /** Track view: true = spectrogram, false = waveform (default). */
    fun isSpectrogram(trackId: Long): Boolean = trackId in spectrogramTracks

    fun setSpectrogram(trackId: Long, on: Boolean) {
        if (on == isSpectrogram(trackId)) return
        spectrogramTracks = if (on) spectrogramTracks + trackId else spectrogramTracks - trackId
    }

    /** Compact (phone) layout: whether the gain/pan row of a track is shown. */
    fun isMixerOpen(trackId: Long): Boolean = mixerOpen[trackId] == true

    fun toggleMixer(trackId: Long) {
        mixerOpen[trackId] = !(mixerOpen[trackId] ?: false)
    }

    /** Drops per-track UI state of tracks that no longer exist. */
    internal fun retainTracks(ids: Set<Long>) {
        heights.keys.retainAll(ids)
        collapsed.keys.retainAll(ids)
        mixerOpen.keys.retainAll(ids)
        if (!ids.containsAll(spectrogramTracks)) spectrogramTracks = spectrogramTracks.filterTo(HashSet()) { it in ids }
    }

    // ------------------------------------------------------------------
    // Engine view adoption / follow play head
    // ------------------------------------------------------------------

    /** Adopts a view saved in the project (snapshot `view`), without counting
     *  it as a user change. */
    internal fun adoptView(zoom: Double, newHpos: Double) {
        if (!(zoom > 0.0)) return
        pps = clampZoom(zoom)
        hpos = max(0.0, if (newHpos.isNaN()) 0.0 else newHpos)
    }

    /** Pages the view when the play head left it (PlayIndicatorOverlay::OnTimer). */
    internal fun followHead(head: Double) {
        if (!followPlayhead || gestureActive || head.isNaN() || head < 0.0) return
        val end = screenEndTime
        if (head >= hpos && head < end) return
        val newH = if (head < hpos) max(0.0, head - screenDuration) else head
        setView(pps, newH)
    }

    // ------------------------------------------------------------------
    // lib-viewport ports
    // ------------------------------------------------------------------

    private fun zoomTo(zoom: Double) {
        // Viewport::Zoom: set the zoom, then centre the selection if it fits.
        val z = clampZoom(zoom)
        val tAvailable = usableWidthDp / z
        val t0 = selectionT0
        val t1 = selectionT1
        val len = t1 - t0
        val newH = if (len <= tAvailable) (t0 + t1) / 2.0 - tAvailable / 2.0 else hpos
        setView(z, newH)
    }

    private fun zoomAboutSelection(multiplier: Double) {
        val endTime = screenEndTime
        val t0 = selectionT0
        val t1 = selectionT1
        val onscreen = t0 < endTime && t1 >= hpos
        val fills = t0 < hpos && t1 > endTime
        if (onscreen && !fills) {
            var center = (t0 + t1) / 2.0
            if (center < hpos) center = hpos + (t1 - hpos) / 2.0
            if (center > endTime) center = endTime - (endTime - t0) / 2.0
            val newPps = clampZoom(pps * multiplier)
            val newDuration = usableWidthDp / newPps
            setView(newPps, center - newDuration / 2.0)
            return
        }
        zoomAboutCenter(multiplier)
    }

    private fun zoomAboutCenter(multiplier: Double) {
        val origLeft = hpos
        val origWidth = screenDuration
        val newPps = clampZoom(pps * multiplier)
        val newWidth = usableWidthDp / newPps
        setView(newPps, origLeft + (origWidth - newWidth) / 2.0)
    }

    private fun scrollIntoView(t: Double) {
        if (t.isNaN()) return
        val x = floor(0.5 + timeToX(t))
        if (x < 0.0 || x >= usableWidthDp) {
            setView(pps, t - screenDuration / 2.0)
        }
    }

    companion object {
        /** ZoomInfo::GetDefaultZoom(): 44100 / 512 px per second. */
        const val DEFAULT_ZOOM: Double = 44100.0 / 512.0
        /** ZoomInfo.cpp:17-18 limits. */
        const val MIN_ZOOM: Double = 0.001
        const val MAX_ZOOM: Double = 6_000_000.0
        const val MIN_TRACK_HEIGHT_DP: Float = 40f
        const val MAX_TRACK_HEIGHT_DP: Float = 2000f
        internal const val FALLBACK_WIDTH_DP: Double = 360.0

        fun clampZoom(z: Double): Double =
            if (z.isNaN()) DEFAULT_ZOOM else z.coerceIn(MIN_ZOOM, MAX_ZOOM)

        /**
         * Tile zoom level for drawing at [pps] dp/s on a screen with [density]
         * px per dp: tiles are fetched at physical-pixel resolution, so
         * the level is the one just below `pps·density` (API.md §7.1).
         */
        fun tileLevel(pps: Double, density: Float): Int = Zoom.levelAtOrBelow(pps * density)

        /** Horizontal draw scale for [tileLevel]: `pps·density / pps(level)`. */
        fun tileScale(pps: Double, density: Float): Double =
            pps * density / Zoom.ppsForLevel(tileLevel(pps, density))

        /** True when [a] and [b] are equal within a relative epsilon. */
        internal fun near(a: Double, b: Double): Boolean =
            abs(a - b) <= 1e-9 * max(1.0, min(abs(a), abs(b)))

        val Saver: Saver<EditorState, Any> = Saver(
            save = { s ->
                arrayListOf<Any>(
                    s.pps, s.hpos, s.followPlayhead, s.showRms, s.showClipping, s.vposDp,
                    LongArray(s.heights.size).also { a -> s.heights.keys.forEachIndexed { i, k -> a[i] = k } },
                    FloatArray(s.heights.size).also { a -> s.heights.values.forEachIndexed { i, v -> a[i] = v } },
                    s.collapsed.filterValues { it }.keys.toLongArray(),
                    s.mixerOpen.filterValues { it }.keys.toLongArray(),
                    s.lastSentZoom,
                    s.lastSentHpos,
                    s.spectrogramTracks.toLongArray(),
                    s.stopAtTrackEnd,
                    s.snapping.name,
                    s.tool.name,
                )
            },
            restore = { v ->
                @Suppress("UNCHECKED_CAST")
                val l = v as List<Any>
                EditorState(l[0] as Double, l[1] as Double).apply {
                    followPlayhead = l[2] as Boolean
                    showRms = l[3] as Boolean
                    showClipping = l[4] as Boolean
                    vposDp = l[5] as Float
                    val ids = l[6] as LongArray
                    val hs = l[7] as FloatArray
                    for (i in ids.indices) heights[ids[i]] = hs[i]
                    for (id in l[8] as LongArray) collapsed[id] = true
                    for (id in l[9] as LongArray) mixerOpen[id] = true
                    lastSentZoom = l[10] as Double
                    lastSentHpos = l[11] as Double
                    spectrogramTracks = (l[12] as LongArray).toSet()
                    if (l.size > 15) {
                        stopAtTrackEnd = l[13] as Boolean
                        snapping = SnapMode.entries.firstOrNull { it.name == l[14] } ?: SnapMode.EDGES
                        tool = EditTool.entries.firstOrNull { it.name == l[15] } ?: EditTool.SELECT
                    }
                }
            },
        )
    }
}

/** Remembers an [EditorState] across recomposition and configuration changes. */
@Composable
fun rememberEditorState(): EditorState = rememberSaveable(saver = EditorState.Saver) { EditorState() }
