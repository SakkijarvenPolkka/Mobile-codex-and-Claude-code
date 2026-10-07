/*
 * Audacity Android port — timeline ruler (AdornedRulerPanel, notes/ui-reference.md §5.10).
 *
 * Minutes/seconds ticks and labels, the time selection blended at 0.4, the
 * play region (loop) band with edge markers, the edit cursor and the
 * play/record pointer. Tap = Quick-Play from there (stop when playing),
 * drag = create a loop region (or adjust an edge), double tap inside the loop
 * = toggle looping, long press = Timeline Options menu.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private enum class RulerMode { TAP, LONG_PRESS, DRAG }

@Composable
internal fun TimelineRuler(
    engine: AudacityEngine,
    state: EditorState,
    snapshot: Snapshot,
    head: HeadState,
    callbacks: EditorCallbacks,
    metrics: EditorMetrics,
    modifier: Modifier = Modifier,
) {
    val pal = LocalAudacityColors.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<TimeRange?>(null) }
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val labelStyle = remember(pal) { TextStyle(color = pal.text, fontSize = 10.sp) }
    val tri = remember { Path() }
    // Laid-out tick labels of the current step, keyed by tick index (no
    // string allocation on frames that show the same ticks).
    val labelCache = remember(measurer) { LabelCache() }
    val description = stringResource(R.string.aued_timeline)

    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCallbacks.onMessage(e.message ?: e.toString())
            }
        }
    }

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(engine, state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val t0 = max(0.0, state.xToTime((down.position.x / density).toDouble()))
                    var mode = RulerMode.LONG_PRESS
                    var dragChange: PointerInputChange? = null
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed) {
                                ch?.consume()
                                mode = RulerMode.TAP
                                break
                            }
                            if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                mode = RulerMode.DRAG
                                dragChange = ch
                                break
                            }
                        }
                    }
                    val pr = currentSnapshot.playRegion
                    when (mode) {
                        RulerMode.TAP -> {
                            val inLoop = pr.t1 > pr.t0 && t0 >= pr.t0 && t0 <= pr.t1
                            var doubled = false
                            if (inLoop) {
                                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                    awaitFirstDown()
                                }
                                if (second != null) {
                                    doubled = true
                                    second.consume()
                                    // Wait for its release.
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        ev.changes.forEach { it.consume() }
                                        if (ev.changes.none { it.pressed }) break
                                    }
                                    launch { engine.togglePlayRegion() }
                                }
                            }
                            if (!doubled) {
                                if (head.isActive) launch { engine.stop() } else launch { engine.play(t0 = t0) }
                            }
                        }
                        RulerMode.LONG_PRESS -> {
                            currentCallbacks.onContextMenu(ContextTarget.Timeline(t0))
                            while (true) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { it.consume() }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                        RulerMode.DRAG -> {
                            // Adjust an existing edge when the drag starts near it.
                            val grab = metrics.edgeGrab / state.pps
                            var anchor = t0
                            if (pr.t1 > pr.t0) {
                                if (abs(t0 - pr.t0) <= grab) anchor = pr.t1
                                else if (abs(t0 - pr.t1) <= grab) anchor = pr.t0
                            }
                            fun update(x: Float) {
                                val t = max(0.0, state.xToTime((x / density).toDouble()))
                                preview = TimeRange(min(anchor, t), max(anchor, t))
                            }
                            dragChange?.let { update(it.position.x); it.consume() }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    ch.consume()
                                    break
                                }
                                update(ch.position.x)
                                ch.consume()
                            }
                            val r = preview
                            if (r != null && r.t1 > r.t0) {
                                launch {
                                    try {
                                        engine.setPlayRegion(r.t0, r.t1, true)
                                    } finally {
                                        preview = null
                                    }
                                }
                            } else {
                                preview = null
                            }
                        }
                    }
                }
            },
    ) {
        val d = density
        val w = size.width
        val h = size.height
        val pxPerSec = state.pps * d
        val hposPx = state.hpos * pxPerSec
        fun x(t: Double): Float = (t * pxPerSec - hposPx).toFloat()
        drawRect(pal.rulerBackground)

        val sel = snapshot.selection
        val pr = preview?.let { io.github.sakkijarvenpolkka.audacity.engine.model.PlayRegionState(true, it.t0, it.t1) }
            ?: snapshot.playRegion
        // Play region band.
        if (pr.t1 > pr.t0) {
            val a = max(0f, x(pr.t0))
            val b = min(w, x(pr.t1))
            if (b > a) drawRect(if (pr.active) pal.loopOn else pal.loopOff, Offset(a, 0f), Size(b - a, h))
        }
        // Selection band (blended 0.4), darker where it overlaps an active loop.
        if (sel.t1 > sel.t0) {
            val a = max(0f, x(sel.t0))
            val b = min(w, x(sel.t1))
            if (b > a) {
                drawRect(pal.rulerSelected, Offset(a, 0f), Size(b - a, h))
                if (pr.active && pr.t1 > pr.t0) {
                    val oa = max(a, x(pr.t0))
                    val ob = min(b, x(pr.t1))
                    if (ob > oa) drawRect(pal.loopOnSelected, Offset(oa, 0f), Size(ob - oa, h))
                }
            }
        }
        // Loop edges: vertical line + 7 dp right triangle at the bottom.
        if (pr.t1 > pr.t0) {
            for (edge in 0..1) {
                val ex = x(if (edge == 0) pr.t0 else pr.t1)
                if (ex < -8f * d || ex > w + 8f * d) continue
                drawLine(pal.text, Offset(ex, 0f), Offset(ex, h), d)
                tri.reset()
                val s = 7f * d
                if (edge == 0) {
                    tri.moveTo(ex, h); tri.lineTo(ex + s, h); tri.lineTo(ex, h - s)
                } else {
                    tri.moveTo(ex, h); tri.lineTo(ex - s, h); tri.lineTo(ex, h - s)
                }
                tri.close()
                drawPath(tri, pal.text)
            }
        }
        // Ticks and labels.
        val major = RulerTicks.majorStep(state.pps, 64.0)
        val minor = RulerTicks.minorStep(major)
        val tStart = floor(state.hpos / minor) * minor
        val tEnd = state.hpos + w / pxPerSec
        val n = ((tEnd - tStart) / minor).toLong() + 2
        val majorLen = 7f * d
        val minorLen = 3.5f * d
        var i = 0L
        while (i < n) {
            val t = tStart + i * minor
            i++
            if (t < 0.0) continue
            val tx = x(t)
            if (tx < -1f || tx > w + 1f) continue
            val ratio = t / major
            val isMajor = abs(ratio - ratio.roundToLong()) < 1e-6
            if (isMajor) {
                drawLine(pal.text, Offset(tx, h - majorLen), Offset(tx, h), d)
                val layout = labelCache.get(t, major, labelStyle) { measurer.measure(TimeFormat.rulerLabel(t, major), labelStyle) }
                drawText(layout, topLeft = Offset(tx + 3f * d, (h - majorLen - layout.size.height) / 2f + d))
            } else {
                drawLine(pal.text, Offset(tx, h - minorLen), Offset(tx, h), d * 0.75f)
            }
        }
        // Bottom edge.
        drawLine(Color.Black, Offset(0f, h - d / 2f), Offset(w, h - d / 2f), d)
        // Edit cursor.
        if (sel.t1 <= sel.t0) {
            val cx = x(sel.t0)
            if (cx in 0f..w) drawLine(pal.cursor, Offset(cx, 0f), Offset(cx, h), d)
        }
        // Play / record pointer.
        val ht = head.time
        if (!ht.isNaN()) {
            val hx = x(ht)
            if (hx >= -10f * d && hx <= w + 10f * d) {
                val s = 6.5f * d
                tri.reset()
                tri.moveTo(hx - s, h - 2 * s)
                tri.lineTo(hx + s, h - 2 * s)
                tri.lineTo(hx, h)
                tri.close()
                drawPath(tri, if (head.isRecording) pal.recordPointer else pal.playPointer)
            }
        }
    }
}

/** Tick label layouts for one major step and style. */
private class LabelCache {
    private var step = Double.NaN
    private var style: TextStyle? = null
    private val map = androidx.collection.MutableLongObjectMap<androidx.compose.ui.text.TextLayoutResult>()

    inline fun get(
        t: Double, major: Double, textStyle: TextStyle,
        make: () -> androidx.compose.ui.text.TextLayoutResult,
    ): androidx.compose.ui.text.TextLayoutResult {
        if (major != step || textStyle != style || map.size > 256) {
            map.clear()
            step = major
            style = textStyle
        }
        val key = (t / major).roundToLong()
        return map[key] ?: make().also { map[key] = it }
    }
}
