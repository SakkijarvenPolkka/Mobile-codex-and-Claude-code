/*
 * Audacity Android port — snapping of drags (selection, loop band, clip
 * edges, cursor) for touch.
 *
 * Point snapping follows Audacity 3.7.9 lib-snapping SnapManager (Snap.cpp):
 * the candidates are t = 0, the clip edges and the label edges of all
 * tracks; the nearest point within a pixel tolerance wins, and the grid
 * (SnapMode.EDGES_AND_GRID) applies when no point is near. Mobile changes: a
 * 16 dp tolerance instead of 4 px, the cursor and the play head are snap
 * points too, the grid is the ruler's minor tick step at the current zoom
 * (instead of the Snapping toolbar's beats/time formats), and "stop at the
 * track end" clamps a drag to [0, end] with the end as a magnet even when
 * snapping is off. Audacity, the Audacity Team; GPL-2.0-or-later.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Snapping of time drags (exposed for the app's Snapping toggle). */
enum class SnapMode {
    /** No snapping (the track end and 0 still stop a drag when [EditorState.stopAtTrackEnd] is on). */
    OFF,

    /** Snap to clip and label edges, track ends, the cursor and the play head. */
    EDGES,

    /** Like [EDGES], and to the ruler's minor ticks (the grid) when no edge is near. */
    EDGES_AND_GRID,
}

/** Tool of the track panel. */
enum class EditTool {
    /** Taps place the cursor, drags select (Audacity's Selection tool). */
    SELECT,

    /** Razor: a tap on a clip splits it at the tapped time. */
    SPLIT,
}

/** What a snapped time stuck to. */
internal enum class SnapKind {
    NONE,
    /** Time zero (or clamped to it). */
    ZERO,
    /** The end that limits the drag (or clamped to it). */
    END,
    /** A clip/label edge, another track's end, the cursor or the play head. */
    POINT,
    /** A grid line. */
    GRID,
}

/** A snapped time. [magnet] = it stuck to a point (guide line + haptic tick). */
internal class SnapResult(val time: Double, val kind: SnapKind) {
    val magnet: Boolean get() = kind == SnapKind.ZERO || kind == SnapKind.END || kind == SnapKind.POINT
}

/**
 * Snaps times of one drag. [points] are the snap points (sorted, any order
 * accepted); built once per drag from a snapshot with [forSnapshot].
 */
internal class Snapper(
    private val pps: Double,
    private val mode: SnapMode,
    points: DoubleArray,
    private val toleranceDp: Double = SNAP_TOLERANCE_DP,
) {
    private val points: DoubleArray = points.copyOf().also { it.sort() }

    /** Tolerance in seconds at the current zoom. */
    val tolerance: Double get() = toleranceDp / pps

    /**
     * Snaps [raw]: clamps to ≥ 0 and, with [clampToEnd], to ≤ [limitEnd]
     * (`limitEnd ≤ 0` = no audio, no upper clamp); magnets within the
     * tolerance: 0, [limitEnd] (when clamping or snapping), and with snapping
     * on the snap points; then the grid for [SnapMode.EDGES_AND_GRID].
     */
    fun snap(raw: Double, limitEnd: Double, clampToEnd: Boolean): SnapResult {
        val t = if (raw.isNaN()) 0.0 else raw
        val hasEnd = limitEnd > 0.0 && limitEnd.isFinite()
        if (t <= 0.0) return SnapResult(0.0, SnapKind.ZERO)
        if (clampToEnd && hasEnd && t >= limitEnd) return SnapResult(limitEnd, SnapKind.END)
        val tol = tolerance
        var bestKind = SnapKind.NONE
        var bestTime = t
        var bestD = Double.MAX_VALUE
        if (t <= tol) {
            bestKind = SnapKind.ZERO; bestTime = 0.0; bestD = t
        }
        if (hasEnd && (clampToEnd || mode != SnapMode.OFF)) {
            val d = abs(t - limitEnd)
            if (d <= tol && d < bestD) {
                bestKind = SnapKind.END; bestTime = limitEnd; bestD = d
            }
        }
        if (mode != SnapMode.OFF && points.isNotEmpty()) {
            val i = nearestIndex(t)
            val p = points[i]
            val d = abs(t - p)
            if (d <= tol && d < bestD && !(clampToEnd && hasEnd && p > limitEnd)) {
                bestKind = if (p == 0.0) SnapKind.ZERO else SnapKind.POINT
                bestTime = p; bestD = d
            }
        }
        if (bestKind != SnapKind.NONE) return SnapResult(bestTime, bestKind)
        if (mode == SnapMode.EDGES_AND_GRID) {
            val step = gridStep(pps)
            var g = (t / step).roundToLong() * step
            if (g < 0.0) g = 0.0
            if (clampToEnd && hasEnd && g > limitEnd) g = limitEnd
            return SnapResult(g, SnapKind.GRID)
        }
        return SnapResult(t, SnapKind.NONE)
    }

    /** Index of the point nearest to [t] ([points] not empty). */
    private fun nearestIndex(t: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid] < t) lo = mid + 1 else hi = mid
        }
        // lo = first point ≥ t (or the last one)
        return if (lo > 0 && abs(points[lo - 1] - t) <= abs(points[lo] - t)) lo - 1 else lo
    }

    companion object {
        /** Snap tolerance (desktop kPixelTolerance = 4 px, bigger for fingers). */
        const val SNAP_TOLERANCE_DP: Double = 16.0

        /** Grid step: the ruler's minor tick step at [pps] dp/s. */
        fun gridStep(pps: Double): Double = RulerTicks.minorStep(RulerTicks.majorStep(pps, RULER_LABEL_SPACING_DP))

        /** Minimum spacing of the ruler's major labels (TimelineRuler). */
        const val RULER_LABEL_SPACING_DP: Double = 64.0

        /**
         * Snap points of [s] (lib-snapping FindCandidates: every clip's start
         * and end, every label's t0 and t1, plus the track ends) and the
         * [extra] times (cursor, play head; NaN entries are skipped). The
         * edges of [excludeTrackId]'s clip [excludeClipIndex] are left out
         * (the clip being dragged or trimmed).
         */
        fun forSnapshot(
            s: Snapshot,
            pps: Double,
            mode: SnapMode,
            extra: DoubleArray = DoubleArray(0),
            excludeTrackId: Long = Long.MIN_VALUE,
            excludeClipIndex: Int = -1,
        ): Snapper {
            var n = extra.size
            for (t in s.tracks) n += 2 * t.clips.size + 2 * t.labels.size + 1
            val out = DoubleArray(n)
            var k = 0
            for (t in s.tracks) {
                for (c in t.clips) {
                    if (t.id == excludeTrackId && c.index == excludeClipIndex) continue
                    out[k++] = c.start
                    out[k++] = c.end
                }
                for (l in t.labels) {
                    out[k++] = l.t0
                    if (l.t1 != l.t0) out[k++] = l.t1
                }
                if (t.id != excludeTrackId) {
                    val e = trackEnd(t)
                    if (e > 0.0) out[k++] = e
                }
            }
            for (e in extra) if (!e.isNaN() && e >= 0.0) out[k++] = e
            return Snapper(pps, mode, out.copyOf(k))
        }

        /** End of a track: its audio end, or a label track's last label end. */
        fun trackEnd(t: TrackState): Double =
            if (t.isLabel) t.labels.maxOfOrNull { it.t1 } ?: 0.0 else max(0.0, t.end)

        /**
         * The end that stops a drag in [s]: the end of the audio of the
         * single wave track [trackId] when it has audio, else (several
         * tracks, a label track, an empty track) the project end.
         */
        fun limitFor(s: Snapshot, trackId: Long?): Double {
            if (trackId != null) {
                val t = s.track(trackId)
                if (t != null && t.isWave) {
                    val e = trackEnd(t)
                    if (e > 0.0) return e
                }
            }
            return s.projectEnd
        }

        /** [t] clamped to [0, limitEnd] when [clampToEnd] and there is audio. */
        fun clamp(t: Double, limitEnd: Double, clampToEnd: Boolean): Double {
            var v = max(0.0, if (t.isNaN()) 0.0 else t)
            if (clampToEnd && limitEnd > 0.0 && limitEnd.isFinite()) v = min(v, limitEnd)
            return v
        }
    }
}

/**
 * Feedback of one snapping drag: shows the snap guide line
 * ([EditorState.snapGuide]) and gives a haptic tick each time a magnet
 * engages (not again while it holds).
 */
internal class SnapFeedback(private val state: EditorState, private val tick: () -> Unit) {
    private var held = Double.NaN

    /** Applies [r] and returns its time. */
    fun apply(r: SnapResult): Double {
        if (r.magnet) {
            if (r.time != held) tick()
            held = r.time
            state.snapGuide = r.time
        } else {
            held = Double.NaN
            state.snapGuide = Double.NaN
        }
        return r.time
    }

    /** Ends the drag: hides the guide. */
    fun end() {
        held = Double.NaN
        state.snapGuide = Double.NaN
    }
}

/** A light haptic tick (View CLOCK_TICK) for snaps. */
@Composable
internal fun rememberSnapTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); Unit } }
}
