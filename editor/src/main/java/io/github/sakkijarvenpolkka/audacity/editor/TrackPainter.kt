/*
 * Audacity Android port — track panel painting (Canvas / DrawScope).
 *
 * Reproduces the 3.7.9 look (notes/ui-reference.md §5.6-§5.9,
 * notes/waveform-data.md §1.6-§1.7): blank/clip backgrounds with the time
 * selection, per-column min/max bands with the RMS band inside and optional
 * clipping columns (WaveBitmapCache.cpp:106-271 row math), individual
 * samples as stems + dots or connected lines when zoomed in
 * (WaveformView.cpp:573-700), clip title bars with rounded top corners,
 * names and the overflow button (TrackArt.cpp:208-345), label tracks
 * (LabelTrackView) and the edit cursor. Logic adapted from Audacity,
 * the Audacity Team; GPL-2.0-or-later.
 *
 * Draw loops are allocation-free: columns are batched into a reusable
 * FloatArray and drawn with one Canvas.drawLines call per colour.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.collection.MutableLongObjectMap
import androidx.collection.mutableLongObjectMapOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything one paint pass needs; one instance is reused across frames. */
internal class PaintParams {
    var snapshot: Snapshot = Snapshot.EMPTY
    var layout: PanelLayout = PanelLayout(emptyList(), 0f, EditorMetrics(compact = true, large = false))
    var palette: AudacityPalette = AudacityColors.Light
    var pps: Double = EditorState.DEFAULT_ZOOM
    var hpos: Double = 0.0
    var vposDp: Float = 0f
    var showRms: Boolean = true
    var showClipping: Boolean = false
    /** Tracks drawn as spectrogram. */
    var spectroTracks: Set<Long> = emptySet()
    /** Effective selection (drag preview or snapshot). */
    var selT0: Double = 0.0
    var selT1: Double = 0.0
    /** Track-selection preview during a drag (null = snapshot flags). */
    var selTracks: Set<Long>? = null
    /** Clip being time-shifted: drawn at [ghostDt] on [ghostTargetTrackId]. */
    var ghostTrackId: Long = NO_TRACK
    var ghostClipIndex: Int = -1
    var ghostDt: Double = 0.0
    var ghostTargetTrackId: Long = NO_TRACK
    /** Title bar highlighted (pressed). */
    var pressedTrackId: Long = NO_TRACK
    var pressedClipIndex: Int = -1
    /** Live recording: clips are extended to the record head. */
    var recordHead: Double = Double.NaN
    var recordingStart: Double = Double.NaN

    fun isTrackSelected(t: TrackState): Boolean = selTracks?.contains(t.id) ?: t.selected

    companion object {
        const val NO_TRACK = Long.MIN_VALUE
    }
}

internal class TrackPainter(
    private val cache: WaveTileCache,
    private val textMeasurer: TextMeasurer,
) {
    private var lines = FloatArray(4 * 2048)
    private var nLines = 0
    private var rmsLines = FloatArray(4 * 2048)
    private var nRms = 0
    private var clipLines = FloatArray(4 * 256)
    private var nClip = 0
    private var dots = FloatArray(2 * 512)
    private var nDots = 0
    private val labelRowEnds = FloatArray(MAX_LABEL_ROWS)

    private val linePaint = android.graphics.Paint().apply {
        isAntiAlias = false
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.BUTT
    }
    private val smoothPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /** Label text boxes of the last paint, canvas px: trackId → [l,t,r,b] × labels. */
    val labelBoxes: MutableLongObjectMap<FloatArray> = mutableLongObjectMapOf()

    var clipNameStyle: TextStyle = TextStyle.Default
    var labelStyle: TextStyle = TextStyle.Default

    // Per-paint scalars (set at the start of paint()).
    private var d = 1f
    private var pxPerSec = 1.0
    private var hposPx = 0.0
    private var level = 0
    private var levelPps = 1.0
    private var colScale = 1.0
    private var width = 0f

    private fun x(t: Double): Float = (t * pxPerSec - hposPx).toFloat()

    fun paint(scope: DrawScope, p: PaintParams) = with(scope) {
        d = density
        width = size.width
        pxPerSec = p.pps * d
        hposPx = p.hpos * pxPerSec
        level = Zoom.levelAtOrBelow(pxPerSec)
        levelPps = Zoom.ppsForLevel(level)
        colScale = pxPerSec / levelPps
        val pal = p.palette
        drawRect(pal.trackBackground)
        val selPoint = p.selT1 <= p.selT0
        val geoms = p.layout.tracks
        for (gi in geoms.indices) {
            val g = geoms[gi]
            val top = (g.bodyTop - p.vposDp) * d
            val bottom = (g.bottom - p.vposDp) * d
            if (bottom < 0f || top > size.height) continue
            if (g.header > 0f) {
                // Compact header band (the header composable is drawn over it).
                val ht = (g.top - p.vposDp) * d
                drawRect(
                    if (p.isTrackSelected(g.track)) pal.trackInfoSelected else pal.trackInfo,
                    Offset(0f, ht), Size(width, g.header * d),
                )
            }
            when {
                g.track.isWave -> paintWaveTrack(this, p, g, top, bottom - top, selPoint)
                g.track.isLabel -> paintLabelTrack(this, p, g, top, bottom - top, selPoint)
                else -> drawRect(pal.blank, Offset(0f, top), Size(width, bottom - top))
            }
        }
        // Ghost of a clip being dragged to another track.
        if (p.ghostTrackId != PaintParams.NO_TRACK && p.ghostTargetTrackId != p.ghostTrackId) {
            val src = p.layout.geom(p.ghostTrackId)
            val dst = p.layout.geom(p.ghostTargetTrackId)
            val clip = src?.track?.clips?.firstOrNull { it.index == p.ghostClipIndex }
            if (src != null && dst != null && clip != null) {
                paintClip(this, p, src.track, clip, p.ghostDt, dst, selPoint = true, selected = false)
            }
        }
        // Edit cursor across the selected tracks.
        if (selPoint) {
            val cx = x(p.selT0)
            if (cx >= -1f && cx <= width + 1f) {
                for (gi in geoms.indices) {
                    val g = geoms[gi]
                    if (!p.isTrackSelected(g.track)) continue
                    val top = (g.bodyTop - p.vposDp) * d
                    val bottom = (g.bottom - p.vposDp) * d
                    if (bottom < 0f || top > size.height) continue
                    drawLine(pal.cursor, Offset(cx, top), Offset(cx, bottom), strokeWidth = max(1f, d * 0.75f))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Wave tracks
    // ------------------------------------------------------------------

    private fun paintWaveTrack(s: DrawScope, p: PaintParams, g: TrackGeom, top: Float, height: Float, selPoint: Boolean) {
        val pal = p.palette
        val selected = p.isTrackSelected(g.track)
        s.drawRect(pal.blank, Offset(0f, top), Size(width, height))
        if (selected && !selPoint) {
            val a = max(0f, x(p.selT0))
            val b = min(width, x(p.selT1))
            if (b > a) s.drawRect(pal.blankSelected, Offset(a, top), Size(b - a, height))
        }
        val clips = g.track.clips
        for (ci in clips.indices) {
            val clip = clips[ci]
            if (p.ghostTrackId == g.track.id && p.ghostClipIndex == clip.index) {
                if (p.ghostTargetTrackId == g.track.id) {
                    paintClip(s, p, g.track, clip, p.ghostDt, g, selPoint, selected)
                }
                continue
            }
            paintClip(s, p, g.track, clip, 0.0, g, selPoint, selected)
        }
        // Separator between the channels of a stereo track.
        if (g.channelCount == 2) {
            val y = (g.channelTop(1, CH_SEP) - CH_SEP - p.vposDp) * d
            s.drawRect(pal.trackBackground, Offset(0f, y), Size(width, CH_SEP * d))
        }
    }

    /** End of [clip] as drawn: extended to the record head while recording into it. */
    private fun drawnEnd(p: PaintParams, track: TrackState, clip: ClipState): Double =
        recordingEnd(track, clip, p.recordHead, p.recordingStart)

    private fun paintClip(
        s: DrawScope, p: PaintParams, track: TrackState, clip: ClipState, dt: Double,
        g: TrackGeom, selPoint: Boolean, selected: Boolean,
    ) {
        val pal = p.palette
        val cx0 = x(clip.start + dt)
        val cx1 = x(drawnEnd(p, track, clip) + dt)
        if (cx1 < 0f || cx0 > width) return
        val vx0 = max(cx0, 0f)
        val vx1 = min(cx1, width)
        val titleTop = (g.bodyTop - p.vposDp) * d
        val titleH = g.titleBar * d
        // Title bar first: its rounded rect extends below and is covered by the body.
        paintTitleBar(s, p, track, clip, cx0, cx1, titleTop, titleH, selected)
        val chH = g.channelHeight(CH_SEP) * d
        val selA = x(p.selT0)
        val selB = x(p.selT1)
        val muted = track.mute
        for (ch in 0 until g.channelCount) {
            val cy = (g.channelTop(ch, CH_SEP) - p.vposDp) * d
            s.drawRect(pal.clipBody, Offset(vx0, cy), Size(vx1 - vx0, chH))
            if (selected && !selPoint) {
                val a = max(vx0, selA)
                val b = min(vx1, selB)
                if (b > a) s.drawRect(pal.clipBodySelected, Offset(a, cy), Size(b - a, chH))
            }
            s.clipRect(vx0, cy, vx1, cy + chH) {
                when {
                    Zoom.needsSampleMode(pxPerSec, clip.rate, clip.stretchRatio) ->
                        paintSamples(this, p, track, clip, ch, dt, vx0, vx1, cy, chH, muted)
                    track.id in p.spectroTracks -> {
                        paintSpectrogram(this, p, track, ch, dt, vx0, vx1, cy, chH)
                        if (selected && !selPoint) {
                            val a = max(vx0, selA)
                            val b = min(vx1, selB)
                            if (b > a) drawRect(SPECTRO_SELECTION, Offset(a, cy), Size(b - a, chH))
                        }
                    }
                    else -> paintColumns(this, p, track, ch, dt, vx0, vx1, cy, chH, muted)
                }
            }
        }
        // Clip edges (1 px outline pen) over the channel area.
        val bodyTop = titleTop + titleH
        val bodyBottom = (g.bottom - p.vposDp) * d
        if (cx0 >= 0f) s.drawLine(pal.clipOutline, Offset(cx0, bodyTop), Offset(cx0, bodyBottom), d * 0.75f)
        if (cx1 <= width) s.drawLine(pal.clipOutline, Offset(cx1, bodyTop), Offset(cx1, bodyBottom), d * 0.75f)
    }

    private fun paintTitleBar(
        s: DrawScope, p: PaintParams, track: TrackState, clip: ClipState,
        cx0: Float, cx1: Float, top: Float, h: Float, trackSelected: Boolean,
    ) {
        if (h < 2f) return
        val pal = p.palette
        val r = 6f * d
        // Keep the rectangle bounded (huge zooms): extend at most r beyond the screen.
        val left = max(cx0, -r * 2)
        val right = min(cx1, width + r * 2)
        if (right - left < 1f) return
        val active = p.pressedTrackId == track.id && p.pressedClipIndex == clip.index ||
            p.ghostTrackId == track.id && p.ghostClipIndex == clip.index
        val clipSelected = trackSelected && !(p.selT1 <= p.selT0) &&
            kotlin.math.abs(p.selT0 - clip.start) < 1e-6 && kotlin.math.abs(p.selT1 - clip.end) < 1e-6
        val radius = CornerRadius(min(r, (right - left) / 2f), min(r, (right - left) / 2f))
        if (clipSelected) {
            s.drawRoundRect(
                pal.clipStroke, Offset(left - d, top - d), Size(right - left + 2 * d, h + r + d),
                radius, style = Stroke(d),
            )
        }
        s.drawRoundRect(if (active) pal.clipBarActive else pal.clipBar, Offset(left, top), Size(right - left, h + r), radius)
        s.drawRoundRect(pal.clipOutline, Offset(left, top), Size(right - left, h + r), radius, style = Stroke(max(1f, d * 0.75f)))
        // Overflow "⋯" button (dropped when < 50 dp would remain for the title).
        val barW = cx1 - cx0
        val overflowW = OVERFLOW_W * d
        val showOverflow = barW >= (50f + OVERFLOW_W) * d
        if (showOverflow && cx1 <= width + overflowW) {
            val cxDots = cx1 - overflowW / 2f
            val cyDots = top + h / 2f
            for (i in -1..1) {
                s.drawCircle(pal.clipName, radius = 1.2f * d, center = Offset(cxDots + i * 4f * d, cyDots))
            }
        }
        // Clip name, left aligned, inset by the radius, truncated with an ellipsis.
        val name = clip.name
        if (name.isEmpty()) return
        val textLeft = max(cx0, 0f) + r
        val textRight = (if (showOverflow) cx1 - overflowW else cx1) - r
        val maxW = min(textRight, width) - textLeft
        if (maxW < 12f * d) return
        val layout = measure(name, clipNameStyle, maxW.toInt(), (h).toInt())
        s.drawText(layout, color = pal.clipName, topLeft = Offset(textLeft, top + (h - layout.size.height) / 2f))
    }

    private fun measure(text: String, style: TextStyle, maxWidth: Int, maxHeight: Int): TextLayoutResult =
        textMeasurer.measure(
            text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
            constraints = Constraints(maxWidth = max(1, maxWidth), maxHeight = max(1, maxHeight)),
        )

    // --- columns ------------------------------------------------------

    private fun paintColumns(
        s: DrawScope, p: PaintParams, track: TrackState, ch: Int, dt: Double,
        vx0: Float, vx1: Float, top: Float, h: Float, muted: Boolean,
    ) {
        val pal = p.palette
        // Zero line (black), under the samples.
        val y0 = top + rowOf(0f, h)
        s.drawLine(pal.zeroLine, Offset(vx0, y0), Offset(vx1, y0), strokeWidth = 1f)
        nLines = 0; nRms = 0; nClip = 0
        val shift = (p.hpos - dt) * levelPps
        val cFirst = floor(shift + vx0 / colScale).toLong()
        val cLast = max(cFirst, floor(shift + vx1 / colScale).toLong())
        val tFirst = Math.floorDiv(cFirst, Zoom.TILE_COLUMNS.toLong())
        val tLast = Math.floorDiv(cLast, Zoom.TILE_COLUMNS.toLong())
        var ti = tFirst
        while (ti <= tLast) {
            val tileC0 = ti * Zoom.TILE_COLUMNS
            val a = max(cFirst, tileC0)
            val b = min(cLast, tileC0 + Zoom.TILE_COLUMNS - 1)
            val tile = cache.tile(track.id, ch, level, ti)
            val data = tile?.data
            if (data == null) {
                val xa = ((a - shift) * colScale).toFloat()
                val xb = ((b + 1 - shift) * colScale).toFloat()
                flushColumns(s, p, muted, colScale)
                paintFallback(s, p, track, ch, dt, xa, xb, top, h, muted)
            } else {
                val envTile = cache.tile(track.id, WaveTileCache.ENVELOPE, level, ti)
                val env = if (envTile != null && !envTile.trivial) envTile.data else null
                addColumns(data, env, tileC0, a, b, shift, colScale, top, h, p.showRms, p.showClipping)
            }
            ti++
        }
        flushColumns(s, p, muted, colScale)
    }

    /** Draws columns of the nearest zoom level that has data for [xa, xb). */
    private fun paintFallback(
        s: DrawScope, p: PaintParams, track: TrackState, ch: Int, dt: Double,
        xa: Float, xb: Float, top: Float, h: Float, muted: Boolean,
    ) {
        for (dl in 1..FALLBACK_LEVELS) {
            for (sign in 0..1) {
                val lf = if (sign == 0) level - dl else level + dl
                if (lf < Zoom.MIN_LEVEL || lf > Zoom.MAX_LEVEL) continue
                val pf = Zoom.ppsForLevel(lf)
                val sf = pxPerSec / pf
                val shift = (p.hpos - dt) * pf
                val ca = floor(shift + xa / sf).toLong()
                val cb = max(ca, floor(shift + xb / sf).toLong() - 1)
                val t0 = Math.floorDiv(ca, Zoom.TILE_COLUMNS.toLong())
                val t1 = Math.floorDiv(cb, Zoom.TILE_COLUMNS.toLong())
                var any = false
                var ti = t0
                while (ti <= t1) {
                    if (cache.tile(track.id, ch, lf, ti)?.data != null) { any = true; break }
                    ti++
                }
                if (!any) continue
                ti = t0
                while (ti <= t1) {
                    val tile = cache.tile(track.id, ch, lf, ti)
                    val data = tile?.data
                    if (data != null) {
                        val tileC0 = ti * Zoom.TILE_COLUMNS
                        val a = max(ca, tileC0)
                        val b = min(cb, tileC0 + Zoom.TILE_COLUMNS - 1)
                        val envTile = cache.tile(track.id, WaveTileCache.ENVELOPE, lf, ti)
                        val env = if (envTile != null && !envTile.trivial) envTile.data else null
                        addColumns(data, env, tileC0, a, b, shift, sf, top, h, p.showRms, p.showClipping)
                    }
                    ti++
                }
                flushColumns(s, p, muted, sf)
                return
            }
        }
    }

    private fun addColumns(
        data: FloatArray, env: FloatArray?, tileC0: Long, a: Long, b: Long,
        shift: Double, scale: Double, top: Float, h: Float, showRms: Boolean, showClipping: Boolean,
    ) {
        val n = Zoom.TILE_COLUMNS
        var c = a
        val half = scale * 0.5
        while (c <= b) {
            val i = (c - tileC0).toInt()
            var mn = data[i]
            var mx = data[n + i]
            var rm = data[2 * n + i]
            if (!mn.isNaN() && !mx.isNaN()) {
                if (env != null) {
                    val e = env[i]
                    if (!e.isNaN()) { mn *= e; mx *= e; rm *= e }
                }
                val x = ((c - shift) * scale + half).toFloat()
                val r0 = rowOf(mx, h)
                val r1 = max(rowOf(mn, h), r0 + 1f)
                pushLine(x, top + r0, x, top + r1)
                if (showRms && !rm.isNaN()) {
                    val q0 = rowOf(min(rm, mx), h)
                    val q1 = rowOf(max(-rm, mn), h)
                    if (q1 > q0) pushRms(x, top + q0, x, top + q1)
                }
                if (showClipping && (mn <= -MAX_AUDIO || mx >= MAX_AUDIO)) pushClip(x, top, x, top + h)
            }
            c++
        }
    }

    private fun flushColumns(s: DrawScope, p: PaintParams, muted: Boolean, scale: Double) {
        if (nLines == 0 && nRms == 0 && nClip == 0) return
        val pal = p.palette
        val stroke = (scale + 0.35).toFloat()
        s.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            linePaint.strokeWidth = stroke
            if (nLines > 0) {
                linePaint.color = (if (muted) pal.muteSample else pal.sample).toArgb()
                nc.drawLines(lines, 0, nLines * 4, linePaint)
            }
            if (nRms > 0) {
                linePaint.color = (if (muted) pal.muteRms else pal.rms).toArgb()
                nc.drawLines(rmsLines, 0, nRms * 4, linePaint)
            }
            if (nClip > 0) {
                linePaint.color = (if (muted) pal.muteClipped else pal.clipped).toArgb()
                nc.drawLines(clipLines, 0, nClip * 4, linePaint)
            }
        }
        nLines = 0; nRms = 0; nClip = 0
    }

    // --- spectrogram ---------------------------------------------------

    private fun paintSpectrogram(
        s: DrawScope, p: PaintParams, track: TrackState, ch: Int, dt: Double,
        vx0: Float, vx1: Float, top: Float, h: Float,
    ) {
        val shift = (p.hpos - dt) * levelPps
        val cFirst = floor(shift + vx0 / colScale).toLong()
        val cLast = max(cFirst, floor(shift + vx1 / colScale).toLong())
        val tFirst = Math.floorDiv(cFirst, Zoom.TILE_COLUMNS.toLong())
        val tLast = Math.floorDiv(cLast, Zoom.TILE_COLUMNS.toLong())
        val dstTop = top.roundToInt()
        val dstH = max(1, h.roundToInt())
        var ti = tFirst
        while (ti <= tLast) {
            val image = cache.tile(track.id, WaveTileCache.SPECTRO + ch, level, ti)?.image
            if (image != null) {
                val x0 = ((ti * Zoom.TILE_COLUMNS - shift) * colScale).toFloat()
                val x1 = (((ti + 1) * Zoom.TILE_COLUMNS - shift) * colScale).toFloat()
                s.drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(floor(x0).toInt(), dstTop),
                    dstSize = IntSize(max(1, ceil(x1).toInt() - floor(x0).toInt()), dstH),
                    filterQuality = FilterQuality.Low,
                )
            }
            ti++
        }
    }

    // --- individual samples --------------------------------------------

    private fun paintSamples(
        s: DrawScope, p: PaintParams, track: TrackState, clip: ClipState, ch: Int, dt: Double,
        vx0: Float, vx1: Float, top: Float, h: Float, muted: Boolean,
    ) {
        val pal = p.palette
        val yZero = top + rowOf(0f, h)
        s.drawLine(pal.zeroLine, Offset(vx0, yZero), Offset(vx1, yZero), strokeWidth = 1f)
        val window = cache.sampleWindow(track.id, ch) ?: return
        val runs = window.runs
        nLines = 0; nDots = 0; nClip = 0
        val tLeft = p.hpos + vx0 / pxPerSec - dt
        val tRight = p.hpos + vx1 / pxPerSec - dt
        for (ri in runs.indices) {
            val run = runs[ri]
            if (run.clipIndex != clip.index) continue
            val period = run.samplePeriod
            if (!(period > 0.0)) continue
            val n = run.values.size
            if (n == 0) continue
            val showPoints = period * p.pps >= 3.0
            val i0 = max(0, floor((tLeft - run.firstSampleTime) / period).toInt() - 1)
            val i1 = min(n - 1, ceil((tRight - run.firstSampleTime) / period).toInt() + 1)
            var px = Float.NaN
            var py = Float.NaN
            var i = i0
            while (i <= i1) {
                val e = if (i < run.envelope.size) run.envelope[i] else 1f
                var v = run.values[i]
                if (!e.isNaN()) v *= e
                val xx = x(run.firstSampleTime + i * period + dt)
                val yy = top + rowOf(v, h).coerceIn(0f, h)
                if (showPoints) {
                    pushLine(xx, yZero, xx, yy)
                    pushDot(xx, yy)
                } else if (!px.isNaN()) {
                    pushLine(px, py, xx, yy)
                }
                if (p.showClipping && (v <= -MAX_AUDIO || v >= MAX_AUDIO)) pushClip(xx, top, xx, top + h)
                px = xx; py = yy
                i++
            }
        }
        s.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val color = (if (muted) pal.muteSample else pal.sample).toArgb()
            if (nLines > 0) {
                smoothPaint.color = color
                smoothPaint.strokeWidth = max(1f, d * 0.75f)
                nc.drawLines(lines, 0, nLines * 4, smoothPaint)
            }
            if (nDots > 0) {
                smoothPaint.color = color
                smoothPaint.strokeWidth = 3.5f * d
                nc.drawPoints(dots, 0, nDots * 2, smoothPaint)
            }
            if (nClip > 0) {
                linePaint.color = (if (muted) pal.muteClipped else pal.clipped).toArgb()
                linePaint.strokeWidth = max(1f, d)
                nc.drawLines(clipLines, 0, nClip * 4, linePaint)
            }
        }
        nLines = 0; nDots = 0; nClip = 0
    }

    // ------------------------------------------------------------------
    // Label tracks
    // ------------------------------------------------------------------

    private fun paintLabelTrack(s: DrawScope, p: PaintParams, g: TrackGeom, top: Float, height: Float, selPoint: Boolean) {
        val pal = p.palette
        val track = g.track
        val selected = p.isTrackSelected(track)
        s.drawRect(pal.blank, Offset(0f, top), Size(width, height))
        if (selected && !selPoint) {
            val a = max(0f, x(p.selT0))
            val b = min(width, x(p.selT1))
            if (b > a) s.drawRect(pal.blankSelected, Offset(a, top), Size(b - a, height))
        }
        val labels = track.labels
        var boxes = labelBoxes[track.id]
        if (boxes == null || boxes.size < labels.size * 4) {
            boxes = FloatArray(max(8, labels.size * 4))
            labelBoxes[track.id] = boxes
        }
        java.util.Arrays.fill(boxes, Float.NaN)
        labelRowEnds.fill(Float.NEGATIVE_INFINITY)
        val barH = LABEL_BAR * d
        val barY = top + height - barH - 4f * d
        val pad = 3f * d
        val maxTextH = max(1f, barY - top - 6f * d)
        for (li in labels.indices) {
            val label = labels[li]
            val x0 = x(label.t0)
            val x1 = x(label.t1)
            if (x1 < -200f * d || x0 > width + 4f * d) continue
            val inSel = selected && label.t0 >= p.selT0 && label.t1 <= p.selT1 && !(selPoint && label.t0 != p.selT0)
            // Boundary lines and the label bar.
            s.drawLine(pal.labelSurround, Offset(x0, top), Offset(x0, top + height), max(1f, d * 0.75f))
            if (x1 > x0) {
                s.drawLine(pal.labelSurround, Offset(x1, top), Offset(x1, top + height), max(1f, d * 0.75f))
                s.drawRect(if (inSel) pal.labelBarSelected else pal.labelBarUnselected, Offset(x0, barY), Size(x1 - x0, barH))
            }
            s.drawCircle(if (inSel) pal.labelBarSelected else pal.labelBarUnselected, 3f * d, Offset(x0, barY + barH / 2f))
            if (x1 > x0) s.drawCircle(if (inSel) pal.labelBarSelected else pal.labelBarUnselected, 3f * d, Offset(x1, barY + barH / 2f))
            // Text box, placed in the first row where it does not overlap.
            val text = label.title.ifEmpty { " " }
            val layout = measure(text, labelStyle, (240f * d).toInt(), maxTextH.toInt())
            val bw = layout.size.width + 2 * pad
            val bh = layout.size.height + 2 * pad
            val bx = x0 + 2f * d
            var row = 0
            while (row < MAX_LABEL_ROWS - 1 && labelRowEnds[row] > bx) row++
            labelRowEnds[row] = bx + bw + 2f * d
            val by = top + 3f * d + row * (bh + 2f * d)
            // No room for another row: draw in the first row.
            val boxY = if (by + bh > barY) top + 3f * d else by
            s.drawRoundRect(if (inSel) pal.labelBoxEdit else pal.labelBox, Offset(bx, boxY), Size(bw, bh), CornerRadius(2f * d))
            s.drawRoundRect(pal.labelSurround, Offset(bx, boxY), Size(bw, bh), CornerRadius(2f * d), style = Stroke(max(1f, d * 0.75f)))
            s.drawText(layout, color = pal.labelText, topLeft = Offset(bx + pad, boxY + pad))
            if (li * 4 + 3 < boxes.size) {
                boxes[li * 4] = bx
                boxes[li * 4 + 1] = boxY
                boxes[li * 4 + 2] = bx + bw
                boxes[li * 4 + 3] = boxY + bh
            }
        }
    }

    // ------------------------------------------------------------------
    // Buffers
    // ------------------------------------------------------------------

    private fun pushLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        if ((nLines + 1) * 4 > lines.size) lines = lines.copyOf(lines.size * 2)
        val o = nLines * 4
        lines[o] = x0; lines[o + 1] = y0; lines[o + 2] = x1; lines[o + 3] = y1
        nLines++
    }

    private fun pushRms(x0: Float, y0: Float, x1: Float, y1: Float) {
        if ((nRms + 1) * 4 > rmsLines.size) rmsLines = rmsLines.copyOf(rmsLines.size * 2)
        val o = nRms * 4
        rmsLines[o] = x0; rmsLines[o + 1] = y0; rmsLines[o + 2] = x1; rmsLines[o + 3] = y1
        nRms++
    }

    private fun pushClip(x0: Float, y0: Float, x1: Float, y1: Float) {
        if ((nClip + 1) * 4 > clipLines.size) clipLines = clipLines.copyOf(clipLines.size * 2)
        val o = nClip * 4
        clipLines[o] = x0; clipLines[o + 1] = y0; clipLines[o + 2] = x1; clipLines[o + 3] = y1
        nClip++
    }

    private fun pushDot(x: Float, y: Float) {
        if ((nDots + 1) * 2 > dots.size) dots = dots.copyOf(dots.size * 2)
        dots[nDots * 2] = x; dots[nDots * 2 + 1] = y
        nDots++
    }

    companion object {
        /** MAX_AUDIO = 1 − 1/32768 (lib-utility/MemoryX.h). */
        const val MAX_AUDIO = 1f - 1f / 32768f
        const val CH_SEP = 1f
        const val OVERFLOW_W = 30f
        const val LABEL_BAR = 6f
        private const val FALLBACK_LEVELS = 12
        private val SPECTRO_SELECTION = Color(0x55FFFFFF)
        private const val MAX_LABEL_ROWS = 8

        /** WaveBitmapCache row math with zMin = −1, zMax = 1 (relative to the channel top). */
        fun rowOf(v: Float, h: Float): Float = floor((1f - v) / 2f * (h - 1f) + 0.5f)

        /** Darkens a colour for disabled / dimmed drawing. */
        fun dim(c: Color, alpha: Float): Color = c.copy(alpha = c.alpha * alpha)
    }
}
