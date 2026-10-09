/*
 * Audacity Android port — track panel geometry (notes/ui-reference.md §5, §9.7).
 *
 * All values are in dp (1 desktop px = 1 dp). Content coordinates: y = 0 at
 * the top of the first track; the panel shows [vpos, vpos + height).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.runtime.Immutable
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState

/** Size class dependent metrics (desktop px → dp table of ui-reference §9.7). */
@Immutable
internal class EditorMetrics(val compact: Boolean, val large: Boolean) {
    /** Track control panel column (0 = compact header rows instead). */
    val tcpWidth: Float = if (compact) 0f else if (large) 150f else 124f
    /** Vertical ruler column. */
    val vrWidth: Float = if (compact) 0f else if (large) 36f else 28f
    val waveLeft: Float get() = tcpWidth + vrWidth
    /** Compact header row above each track body. */
    val headerHeight: Float = if (compact) 32f else 0f
    /** Compact gain/pan row (shown on demand). */
    val mixerRowHeight: Float = if (compact) 40f else 0f
    /** Clip title bar ("affordance"). */
    val titleBar: Float = if (compact) 24f else 20f
    val rulerHeight: Float = if (compact) 32f else 29f
    val defaultTrackHeight: Float = if (compact) 112f else 150f
    val defaultLabelTrackHeight: Float = if (compact) 64f else 73f
    val collapsedHeight: Float = if (compact) 40f else 49f
    /** kTrackSeparatorThickness. */
    val separator: Float = 6f
    /** kChannelSeparatorThickness. */
    val channelSeparator: Float = 1f
    /** Selection edge grab zone (desktop 3 px). */
    val edgeGrab: Float = if (compact) 16f else 12f
    /** Clip border (trim) grab zone on each side of the border (desktop BoundaryThreshold 5 px). */
    val trimGrab: Float = if (compact) 20f else 14f
    /** Grab zone of the play head / cursor handle in the ruler. */
    val headGrab: Float = if (compact) 24f else 16f
    /** Clip title bar corner radius. */
    val clipRadius: Float = 6f
    /** Overflow ("⋯") button of the clip title bar. */
    val overflowWidth: Float = 30f
    /** Edge auto-scroll zone while dragging. */
    val autoScrollZone: Float = 24f
    /** Label bar (LabelBarHeight). */
    val labelBar: Float = 6f

    companion object {
        /** Width (dp) below which the phone-portrait layout is used. */
        const val COMPACT_BELOW = 600f
        const val LARGE_FROM = 840f

        fun forWidth(widthDp: Float): EditorMetrics =
            EditorMetrics(compact = widthDp < COMPACT_BELOW, large = widthDp >= LARGE_FROM)
    }
}

/** Placement of one track. */
internal class TrackGeom(
    val track: TrackState,
    val index: Int,
    /** Top in content coordinates (dp), including the compact header. */
    val top: Float,
    /** Compact header + mixer row height (0 in the wide layout). */
    val header: Float,
    /** Height of the body (clip title bar + channels, or the label area). */
    val body: Float,
    /** Clip title bar height inside the body (wave tracks only). */
    val titleBar: Float,
    val collapsed: Boolean,
) {
    val bodyTop: Float get() = top + header
    val bottom: Float get() = top + header + body
    val channelCount: Int get() = if (track.isWave) track.channels.coerceIn(1, 2) else 0

    /** Height of one channel area (dp). */
    fun channelHeight(channelSeparator: Float): Float {
        val n = channelCount
        if (n == 0) return 0f
        return ((body - titleBar - (n - 1) * channelSeparator) / n).coerceAtLeast(1f)
    }

    /** Top (content dp) of channel [ch]. */
    fun channelTop(ch: Int, channelSeparator: Float): Float =
        bodyTop + titleBar + ch * (channelHeight(channelSeparator) + channelSeparator)

    /** The channel at content y (clamped). */
    fun channelAt(y: Float, channelSeparator: Float): Int {
        val n = channelCount
        if (n <= 1) return 0
        val h = channelHeight(channelSeparator)
        return ((y - bodyTop - titleBar) / (h + channelSeparator)).toInt().coerceIn(0, n - 1)
    }
}

/** Vertical layout of all tracks. */
internal class PanelLayout(
    val tracks: List<TrackGeom>,
    val contentHeight: Float,
    val metrics: EditorMetrics,
) {
    /** The track whose area (incl. the separator below it) contains content y. */
    fun trackAt(y: Float): TrackGeom? {
        for (g in tracks) {
            if (y >= g.top && y < g.bottom + metrics.separator) return g
        }
        return null
    }

    fun geom(trackId: Long): TrackGeom? = tracks.firstOrNull { it.track.id == trackId }

    companion object {
        fun compute(tracks: List<TrackState>, state: EditorState, metrics: EditorMetrics): PanelLayout {
            val out = ArrayList<TrackGeom>(tracks.size)
            var y = 0f
            for ((i, t) in tracks.withIndex()) {
                val collapsed = state.isCollapsed(t.id)
                val header = if (metrics.compact) {
                    metrics.headerHeight + if (state.isMixerOpen(t.id) && t.isWave) metrics.mixerRowHeight else 0f
                } else 0f
                val expanded = state.trackHeightDp(t.id)
                    ?: if (t.isLabel) metrics.defaultLabelTrackHeight else metrics.defaultTrackHeight
                val body = if (collapsed) metrics.collapsedHeight else expanded
                val titleBar = if (t.isWave) minOf(metrics.titleBar, body / 3f) else 0f
                out.add(TrackGeom(t, i, y, header, body, titleBar, collapsed))
                y += header + body + metrics.separator
            }
            return PanelLayout(out, y, metrics)
        }
    }
}

/** Clip ordering helpers. */
internal fun TrackState.clipAt(t: Double): ClipState? {
    for (c in clips) if (t >= c.start && t < c.end) return c
    return null
}

/**
 * End of [clip] while recording: clips being recorded into (pending new
 * tracks have ids ≤ −2; existing tracks grow past the recording start) are
 * extended to the record head [head] between two snapshots.
 */
internal fun recordingEnd(track: TrackState, clip: ClipState, head: Double, recordingStart: Double): Double {
    if (head.isNaN() || head <= clip.end) return clip.end
    if (clip.index != track.clips.size - 1) return clip.end
    val recordingInto = track.id <= -2L || (!recordingStart.isNaN() && clip.end > recordingStart + 1e-6)
    return if (recordingInto && head - clip.end < 5.0) head else clip.end
}
