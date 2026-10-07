/*
 * Audacity Android port — EQ curve editor for Filter Curve EQ / Graphic EQ:
 * draggable (Hz, dB) points on a logarithmic (or linear) frequency axis.
 * Replaces the wx EqualizationPanel draw mode of Audacity 3.7.9
 * (src/effects/EqualizationPanel.cpp, the Audacity Team, GPL-2.0-or-later).
 *
 * Tap = add a point, drag = move the nearest point, long-press = delete it.
 * At most 200 points (effects.md §6.2), ascending frequency.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Frequency/dB ↔ pixel mapping of the curve editor (pure; unit-tested). */
class EqAxes(val width: Float, val height: Float, val linear: Boolean, val dbMin: Double, val dbMax: Double) {
    private val lo = if (linear) 0.0 else log10(F_MIN)
    private val hi = if (linear) F_MAX else log10(F_MAX)

    fun x(f: Double): Float {
        val v = if (linear) f else log10(f.coerceAtLeast(F_MIN))
        return ((v - lo) / (hi - lo) * width).toFloat()
    }

    fun f(x: Float): Double {
        val v = lo + (x / width).coerceIn(0f, 1f) * (hi - lo)
        return (if (linear) v else 10.0.pow(v)).coerceIn(F_MIN, F_MAX)
    }

    fun y(db: Double): Float = ((dbMax - db) / (dbMax - dbMin) * height).toFloat()
    fun db(y: Float): Double = (dbMax - (y / height).coerceIn(0f, 1f) * (dbMax - dbMin))

    companion object {
        const val F_MIN = 20.0
        const val F_MAX = 20000.0
        const val MAX_POINTS = 200
    }
}

/** Inserts a point keeping ascending frequency (replaces a point at the same frequency). */
fun EqCurve.withPoint(p: EqPoint): EqCurve {
    if (points.size >= EqAxes.MAX_POINTS) return this
    val list = points.filter { abs(it.f - p.f) > 1e-6 }.toMutableList()
    val i = list.indexOfFirst { it.f > p.f }.let { if (it < 0) list.size else it }
    list.add(i, p)
    return copy(points = list)
}

/** Moves point [index] to ([f], [db]) between its neighbours. */
fun EqCurve.movePoint(index: Int, f: Double, db: Double): EqCurve {
    if (index !in points.indices) return this
    val lo = points.getOrNull(index - 1)?.f?.times(1.001) ?: EqAxes.F_MIN
    val hi = points.getOrNull(index + 1)?.f?.div(1.001) ?: EqAxes.F_MAX
    val nf = if (hi > lo) f.coerceIn(lo, hi) else points[index].f
    return copy(points = points.toMutableList().also { it[index] = EqPoint(nf, db) })
}

@Composable
fun EqCurveEditor(
    curve: EqCurve,
    dbRange: ClosedFloatingPointRange<Double>,
    onChange: (EqCurve) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(curve)
    val emit by rememberUpdatedState(onChange)
    val range by rememberUpdatedState(dbRange)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val colors = MaterialTheme.colorScheme
    val grab = with(LocalDensity.current) { 28f * density }
    var dragIndex by remember { mutableIntStateOf(-1) }

    fun axes(w: Float, h: Float) = EqAxes(w, h, current.linearFreq, range.start, range.endInclusive)
    fun nearest(a: EqAxes, pos: Offset): Int {
        var best = -1
        var bestD = grab
        current.points.forEachIndexed { i, p ->
            val d = hypot(a.x(p.f) - pos.x, a.y(p.dB) - pos.y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val a = axes(size.width.toFloat(), size.height.toFloat())
                        if (nearest(a, pos) < 0) emit(current.withPoint(EqPoint(round1(a.f(pos.x)), round1(a.db(pos.y)))))
                    },
                    onLongPress = { pos ->
                        val a = axes(size.width.toFloat(), size.height.toFloat())
                        val i = nearest(a, pos)
                        if (i >= 0) emit(current.copy(points = current.points.filterIndexed { j, _ -> j != i }))
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val a = axes(size.width.toFloat(), size.height.toFloat())
                        dragIndex = nearest(a, pos)
                        if (dragIndex < 0 && current.points.size < EqAxes.MAX_POINTS) {
                            val p = EqPoint(round1(a.f(pos.x)), round1(a.db(pos.y)))
                            val c = current.withPoint(p)
                            emit(c)
                            dragIndex = c.points.indexOf(p)
                        }
                    },
                    onDragEnd = { dragIndex = -1 },
                    onDragCancel = { dragIndex = -1 },
                    onDrag = { ev, _ ->
                        val i = dragIndex
                        if (i >= 0) {
                            val a = axes(size.width.toFloat(), size.height.toFloat())
                            val pos = ev.position
                            emit(current.movePoint(i, round1(a.f(pos.x)), round1(a.db(pos.y))))
                        }
                    },
                )
            },
    ) {
        val a = EqAxes(size.width, size.height, curve.linearFreq, dbRange.start, dbRange.endInclusive)
        drawRect(colors.surfaceVariant)
        val gridColor = colors.outlineVariant
        val textStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 9.sp)
        val freqs = if (curve.linearFreq) listOf(2000.0, 4000.0, 6000.0, 8000.0, 10000.0, 12000.0, 14000.0, 16000.0, 18000.0)
        else listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
        for (f in freqs) {
            val x = a.x(f)
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            val label = if (f >= 1000) "${(f / 1000).let { if (it == it.roundToInt().toDouble()) it.roundToInt().toString() else it.toString() }}k" else f.roundToInt().toString()
            val tl = measurer.measure(label, textStyle)
            drawText(tl, topLeft = Offset(x + 2f, size.height - tl.size.height - 1f))
        }
        val step = if (dbRange.endInclusive - dbRange.start > 60) 20 else if (dbRange.endInclusive - dbRange.start > 24) 10 else 6
        var db = (dbRange.start / step).roundToInt() * step.toDouble()
        while (db <= dbRange.endInclusive) {
            val y = a.y(db)
            drawLine(if (db == 0.0) colors.outline else gridColor, Offset(0f, y), Offset(size.width, y), if (db == 0.0) 2f else 1f)
            val tl = measurer.measure("${db.roundToInt()} dB", textStyle)
            drawText(tl, topLeft = Offset(2f, (y - tl.size.height).coerceAtLeast(0f)))
            db += step
        }
        // Curve: flat before the first and after the last point.
        val pts = curve.points
        val path = Path()
        if (pts.isEmpty()) {
            path.moveTo(0f, a.y(0.0)); path.lineTo(size.width, a.y(0.0))
        } else {
            path.moveTo(0f, a.y(pts.first().dB))
            pts.forEach { p -> path.lineTo(a.x(p.f), a.y(p.dB)) }
            path.lineTo(size.width, a.y(pts.last().dB))
        }
        drawPath(path, colors.primary, style = Stroke(width = 3f))
        pts.forEachIndexed { i, p ->
            val c = Offset(a.x(p.f), a.y(p.dB))
            drawCircle(if (i == dragIndex) colors.tertiary else colors.primary, radius = 7f * density / 2f + 4f, center = c)
            drawCircle(colors.surface, radius = 3f * density / 2f + 1f, center = c)
        }
    }
}

private fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0
