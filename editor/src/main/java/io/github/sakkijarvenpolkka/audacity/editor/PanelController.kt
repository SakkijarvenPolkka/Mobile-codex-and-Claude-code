/*
 * Audacity Android port — track panel interaction logic (hit testing and the
 * gesture → engine command mapping of notes/ui-reference.md §9.6).
 *
 * Desktop equivalents: SelectHandle (tap = cursor + select only this track,
 * drag = time selection with edge adjusting, double click = select clip),
 * TrackSelectHandle (TCP click = select track and its whole length),
 * TimeShiftHandle / clip affordance (drag title bar = move clip, click =
 * select clip), right click = context menus. Audacity, the Audacity Team;
 * GPL-2.0-or-later.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a touch landed on. */
internal enum class HitKind {
    /** Wide layout: track control panel column. */
    TCP,
    /** Wide layout: vertical ruler column. */
    VR,
    /** Compact layout: header / mixer row above a track body. */
    HEADER,
    /** Clip title bar. */
    CLIP_BAR,
    /** Overflow ("⋯") button of a clip title bar. */
    CLIP_MENU,
    /** Inside a clip's waveform area. */
    CLIP_BODY,
    /** Wave track area outside clips. */
    WAVE_BLANK,
    /** A label's text box or bar. */
    LABEL,
    /** Label track outside labels. */
    LABEL_BLANK,
    /** Other track kinds (time / note). */
    OTHER_TRACK,
    /** Below the last track. */
    BELOW,
}

internal class Hit(
    val kind: HitKind,
    val geom: TrackGeom?,
    /** Project time under the touch (may be < 0 left of zero). */
    val time: Double,
    val clip: ClipState?,
    val labelIndex: Int,
    /** Position in dp relative to the panel. */
    val xDp: Float,
    val yDp: Float,
) {
    val trackId: Long get() = geom?.track?.id ?: Long.MIN_VALUE
    val isWaveArea: Boolean
        get() = kind == HitKind.CLIP_BODY || kind == HitKind.WAVE_BLANK || kind == HitKind.LABEL ||
            kind == HitKind.LABEL_BLANK || kind == HitKind.OTHER_TRACK || kind == HitKind.CLIP_BAR ||
            kind == HitKind.CLIP_MENU
}

/** Clip being time-shifted. */
internal class ClipGhost(
    val trackId: Long,
    val clip: ClipState,
    val generation: Long,
    val downTime: Double,
    val dt: Double,
    val targetTrackId: Long,
)

@Stable
internal class PanelController(
    val engine: AudacityEngine,
    val state: EditorState,
    val scope: CoroutineScope,
) {
    var callbacks: EditorCallbacks = NoCallbacks
    var snapshot: Snapshot = Snapshot.EMPTY
    var layout: PanelLayout = PanelLayout(emptyList(), 0f, EditorMetrics(compact = true, large = false))
    var density: Float = 1f
    var painter: TrackPainter? = null
    var haptic: () -> Unit = {}

    /** Selection preview while dragging / until the engine confirms. */
    var previewSelection: TimeRange? by mutableStateOf(null)
    var previewTracks: Set<Long>? by mutableStateOf(null)
    var ghost: ClipGhost? by mutableStateOf(null)
    /** Clip whose title bar is pressed: (trackId, clipIndex). */
    var pressedClip: Pair<Long, Int>? by mutableStateOf(null)

    private val metrics: EditorMetrics get() = layout.metrics

    // ------------------------------------------------------------------
    // Hit testing
    // ------------------------------------------------------------------

    /** Hit test at [pos] (px, relative to the panel). */
    fun hitTest(pos: Offset): Hit {
        val xd = pos.x / density
        val yd = pos.y / density
        val cy = yd + state.vposDp
        val waveX = xd - metrics.waveLeft
        val t = state.xToTime(waveX.toDouble())
        val g = layout.trackAt(cy) ?: return Hit(HitKind.BELOW, null, t, null, -1, xd, yd)
        if (metrics.compact && cy < g.bodyTop) return Hit(HitKind.HEADER, g, t, null, -1, xd, yd)
        if (!metrics.compact && xd < metrics.tcpWidth) return Hit(HitKind.TCP, g, t, null, -1, xd, yd)
        if (!metrics.compact && xd < metrics.waveLeft) return Hit(HitKind.VR, g, t, null, -1, xd, yd)
        val track = g.track
        if (track.isWave) {
            if (cy < g.bodyTop + g.titleBar) {
                for (c in track.clips) {
                    val x0 = state.timeToX(c.start)
                    val x1 = state.timeToX(c.end)
                    if (waveX >= x0 && waveX < x1) {
                        val wide = (x1 - x0) >= 50.0 + TrackPainter.OVERFLOW_W
                        val kind = if (wide && waveX >= x1 - TrackPainter.OVERFLOW_W) HitKind.CLIP_MENU else HitKind.CLIP_BAR
                        return Hit(kind, g, t, c, -1, xd, yd)
                    }
                }
                return Hit(HitKind.WAVE_BLANK, g, t, null, -1, xd, yd)
            }
            val c = track.clipAt(t)
            return Hit(if (c != null) HitKind.CLIP_BODY else HitKind.WAVE_BLANK, g, t, c, -1, xd, yd)
        }
        if (track.isLabel) {
            val li = labelAt(g, waveX * density, yd * density, t)
            return Hit(if (li >= 0) HitKind.LABEL else HitKind.LABEL_BLANK, g, t, null, li, xd, yd)
        }
        return Hit(HitKind.OTHER_TRACK, g, t, null, -1, xd, yd)
    }

    /** Label under canvas px ([cx], [cyPx] relative to the wave canvas), or −1. */
    private fun labelAt(g: TrackGeom, cx: Float, cyPx: Float, t: Double): Int {
        val boxes = painter?.labelBoxes?.get(g.track.id)
        val labels = g.track.labels
        if (boxes != null) {
            for (i in labels.indices) {
                val o = i * 4
                if (o + 3 >= boxes.size) break
                val l = boxes[o]
                if (l.isNaN()) continue
                if (cx >= l && cx <= boxes[o + 2] && cyPx >= boxes[o + 1] && cyPx <= boxes[o + 3]) return i
            }
        }
        val tol = metrics.edgeGrab / state.pps
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in labels.indices) {
            val l = labels[i]
            if (t >= l.t0 - tol && t <= l.t1 + tol) {
                val dist = if (t in l.t0..l.t1) 0.0 else min(abs(t - l.t0), abs(t - l.t1))
                if (dist < bestDist) {
                    bestDist = dist
                    best = i
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // Taps
    // ------------------------------------------------------------------

    fun tap(hit: Hit) {
        val g = hit.geom
        when (hit.kind) {
            HitKind.TCP, HitKind.HEADER, HitKind.VR -> if (g != null) {
                launchCommand { engine.selectTrackHeader(g.track.id, shift = false, ctrl = false) }
            }
            HitKind.CLIP_MENU -> hit.clip?.let {
                callbacks.onContextMenu(ContextTarget.Clip(hit.trackId, it.index, snapshot.generation))
            }
            HitKind.CLIP_BAR -> hit.clip?.let { selectClip(hit.trackId, it) }
            HitKind.LABEL -> if (g != null) selectLabel(g, hit.labelIndex)
            HitKind.CLIP_BODY, HitKind.WAVE_BLANK, HitKind.LABEL_BLANK, HitKind.OTHER_TRACK ->
                if (g != null) setCursor(g.track.id, max(0.0, hit.time))
            HitKind.BELOW -> launchCommand { engine.selectTracks(emptyList(), "set") }
        }
    }

    fun doubleTap(hit: Hit) {
        val g = hit.geom ?: return
        when (hit.kind) {
            HitKind.CLIP_BODY -> hit.clip?.let { selectClip(g.track.id, it) }
            HitKind.CLIP_BAR -> hit.clip?.let { callbacks.onRenameClip(g.track.id, it.index, snapshot.generation) }
            HitKind.LABEL -> if (hit.labelIndex >= 0) callbacks.onEditLabel(g.track.id, hit.labelIndex)
            else -> tap(hit)
        }
    }

    fun longPress(hit: Hit) {
        haptic()
        val g = hit.geom
        when (hit.kind) {
            HitKind.TCP, HitKind.HEADER, HitKind.VR -> if (g != null) callbacks.onTrackMenu(g.track.id)
            HitKind.CLIP_BAR, HitKind.CLIP_MENU, HitKind.CLIP_BODY -> hit.clip?.let {
                callbacks.onContextMenu(ContextTarget.Clip(hit.trackId, it.index, snapshot.generation))
            }
            HitKind.LABEL -> if (g != null && hit.labelIndex >= 0) callbacks.onEditLabel(g.track.id, hit.labelIndex)
            HitKind.WAVE_BLANK, HitKind.LABEL_BLANK, HitKind.OTHER_TRACK ->
                if (g != null) callbacks.onContextMenu(ContextTarget.Track(g.track.id))
            HitKind.BELOW -> callbacks.onContextMenu(ContextTarget.Empty)
        }
    }

    /** SelectHandle unmodified click: point selection at [t], only this track selected. */
    fun setCursor(trackId: Long, t: Double) {
        previewSelection = TimeRange(t, t)
        previewTracks = setOf(trackId)
        launchCommand(clearPreview = true) {
            engine.select(t, t)
            engine.selectTracks(listOf(trackId), "set")
        }
    }

    fun selectClip(trackId: Long, clip: ClipState) {
        previewSelection = TimeRange(clip.start, clip.end)
        previewTracks = setOf(trackId)
        val gen = snapshot.generation
        launchCommand(clearPreview = true) { engine.selectClip(trackId, clip.index, gen) }
    }

    private fun selectLabel(g: TrackGeom, index: Int) {
        val l = g.track.labels.getOrNull(index) ?: return
        previewSelection = TimeRange(l.t0, l.t1)
        previewTracks = setOf(g.track.id)
        launchCommand(clearPreview = true) {
            engine.select(l.t0, l.t1)
            engine.selectTracks(listOf(g.track.id), "set")
        }
    }

    // ------------------------------------------------------------------
    // Selection drag
    // ------------------------------------------------------------------

    private var anchor = 0.0
    private var dragStartIndex = 0
    private var selJob: Job? = null
    private var selDirty = false
    private var lastCommittedTracks: List<Long>? = null

    fun selectionDragStart(hit: Hit) {
        val g = hit.geom ?: return
        val sel = snapshot.selection
        val t = max(0.0, hit.time)
        val waveX = hit.xDp - metrics.waveLeft
        val selected = g.track.selected
        anchor = t
        if (selected && sel.t1 > sel.t0) {
            val x0 = state.timeToX(sel.t0)
            val x1 = state.timeToX(sel.t1)
            val d0 = abs(waveX - x0)
            val d1 = abs(waveX - x1)
            if (min(d0, d1) <= metrics.edgeGrab) anchor = if (d0 <= d1) sel.t1 else sel.t0
        }
        dragStartIndex = g.index
        lastCommittedTracks = null
        selectionDragMove(hit)
    }

    fun selectionDragMove(hit: Hit) {
        val t = max(0.0, hit.time)
        val t0 = min(anchor, t)
        val t1 = max(anchor, t)
        previewSelection = TimeRange(t0, t1)
        // Track range from the drag start to the track under the finger.
        val cy = hit.yDp + state.vposDp
        val endIndex = when {
            hit.geom != null -> hit.geom.index
            cy < 0f -> 0
            else -> layout.tracks.size - 1
        }
        val a = min(dragStartIndex, endIndex)
        val b = max(dragStartIndex, endIndex)
        val ids = HashSet<Long>()
        for (g in layout.tracks) if (g.index in a..b) ids.add(g.track.id)
        previewTracks = ids
        selDirty = true
        if (selJob?.isActive != true) commitSelection(final = false)
    }

    fun selectionDragEnd() {
        selDirty = true
        commitSelection(final = true)
    }

    private fun commitSelection(final: Boolean) {
        val previous = selJob
        selJob = scope.launch {
            if (final) previous?.join()
            while (selDirty) {
                selDirty = false
                val sel = previewSelection ?: break
                val ids = previewTracks?.toList()?.sorted()
                try {
                    engine.select(sel.t0, sel.t1)
                    if (ids != null && ids != lastCommittedTracks) {
                        engine.selectTracks(ids, "set")
                        lastCommittedTracks = ids
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    report(e)
                }
                if (!final) kotlinx.coroutines.delay(SELECTION_COMMIT_MS)
            }
            if (final) {
                previewSelection = null
                previewTracks = null
            }
        }
    }

    // ------------------------------------------------------------------
    // Clip drag (time shift)
    // ------------------------------------------------------------------

    fun clipDragStart(hit: Hit) {
        val clip = hit.clip ?: return
        pressedClip = hit.trackId to clip.index
        ghost = ClipGhost(hit.trackId, clip, snapshot.generation, hit.time, 0.0, hit.trackId)
    }

    fun clipDragMove(hit: Hit) {
        val g0 = ghost ?: return
        var dt = hit.time - g0.downTime
        if (g0.clip.start + dt < 0.0) dt = -g0.clip.start
        var target = g0.targetTrackId
        val over = hit.geom?.track
        val src = snapshot.track(g0.trackId)
        if (over != null && over.isWave && src != null && over.channels == src.channels) target = over.id
        ghost = ClipGhost(g0.trackId, g0.clip, g0.generation, g0.downTime, dt, target)
    }

    fun clipDragEnd() {
        val g0 = ghost
        pressedClip = null
        if (g0 == null) return
        if (g0.dt == 0.0 && g0.targetTrackId == g0.trackId) {
            ghost = null
            return
        }
        val newStart = g0.clip.start + g0.dt
        val to = if (g0.targetTrackId != g0.trackId) g0.targetTrackId else null
        scope.launch {
            try {
                engine.moveClip(g0.trackId, g0.clip.index, g0.generation, newStart, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report(e)
            } finally {
                ghost = null
            }
        }
    }

    fun cancelDrags() {
        ghost = null
        pressedClip = null
    }

    // ------------------------------------------------------------------
    // Scroll / zoom
    // ------------------------------------------------------------------

    /** Pans the view by a finger movement of ([dxPx], [dyPx]). */
    fun pan(dxPx: Float, dyPx: Float, horizontal: Boolean = true, vertical: Boolean = true) {
        if (horizontal && dxPx != 0f) state.scrollByDp(-dxPx / density.toDouble())
        if (vertical && dyPx != 0f) state.scrollVerticallyByDp(-dyPx / density)
    }

    /** Horizontal zoom by [factor] about the panel x [focusPx]. */
    fun zoom(factor: Double, focusPx: Float) {
        val fx = focusPx / density - metrics.waveLeft
        state.zoomBy(factor, fx.toDouble().coerceAtLeast(0.0))
    }

    /** Vertical pinch: resizes the track at panel y [focusYPx]. */
    fun resizeTrack(factor: Float, focusYPx: Float) {
        val g = layout.trackAt(focusYPx / density + state.vposDp) ?: return
        if (g.collapsed) return
        state.setTrackHeight(g.track.id, g.body * factor)
    }

    // ------------------------------------------------------------------

    /** Clears the previews once the engine reported a new snapshot. */
    fun onSnapshot(s: Snapshot) {
        snapshot = s
        if (selJob?.isActive != true && ghost == null) {
            previewSelection = null
            previewTracks = null
        }
    }

    private fun launchCommand(clearPreview: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report(e)
            } finally {
                if (clearPreview && selJob?.isActive != true) {
                    previewSelection = null
                    previewTracks = null
                }
            }
        }
    }

    private fun report(e: Throwable) {
        val msg = (e as? EngineException)?.message ?: e.message ?: e.toString()
        callbacks.onMessage(msg)
    }

    companion object {
        const val SELECTION_COMMIT_MS = 33L
    }
}

/** No-op callbacks (before the first composition). */
internal object NoCallbacks : EditorCallbacks {
    override fun onTrackMenu(trackId: Long) {}
    override fun onContextMenu(target: ContextTarget) {}
    override fun onRecord(newTrack: Boolean) {}
    override fun onEditLabel(trackId: Long, index: Int) {}
    override fun onMessage(text: String) {}
}
