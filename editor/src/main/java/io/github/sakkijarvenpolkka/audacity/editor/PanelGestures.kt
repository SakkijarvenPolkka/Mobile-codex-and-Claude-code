/*
 * Audacity Android port — touch gestures of the track panel
 * (notes/ui-reference.md §9.6):
 *
 *   tap               → cursor (+ select that track); TCP: select track
 *   double tap        → select clip / rename clip (title bar) / edit label
 *   long press        → context menu (clip, track, label, empty area)
 *   1-finger drag     → time selection (edge adjusting), clip title bar: time
 *                       shift with ghost preview; TCP/header: scroll
 *   2 fingers         → pan (horizontal + vertical) with fling, pinch =
 *                       horizontal zoom about the focus, vertical pinch =
 *                       track height
 *   mouse wheel       → scroll; Ctrl+wheel → zoom (ChromeOS / DeX)
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

private enum class GestureMode { TAP, LONG_PRESS, DRAG, TRANSFORM }

/** Runs the track panel gesture loop until cancelled. */
internal suspend fun PointerInputScope.panelGestures(c: PanelController) = coroutineScope {
    var lastTapUptime = 0L
    var lastTapPos = Offset.Zero
    var fling: Job? = null
    val doubleTapSlop = 32f * density

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        fling?.cancel()
        c.state.gestureActive = true
        try {
            val hit = c.hitTest(down.position)
            val isSecondTap = down.uptimeMillis - lastTapUptime < viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPos).getDistance() < doubleTapSlop
            if (hit.kind == HitKind.CLIP_BAR) c.pressedClip = hit.trackId to (hit.clip?.index ?: -1)

            var mode = GestureMode.LONG_PRESS
            var upChange: PointerInputChange? = null
            var dragStart: PointerInputChange? = null
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.count { it.pressed }
                    if (pressed >= 2) {
                        mode = GestureMode.TRANSFORM
                        break
                    }
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) {
                        mode = GestureMode.TAP
                        upChange = ch
                        break
                    }
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        mode = GestureMode.DRAG
                        dragStart = ch
                        break
                    }
                }
            }
            when (mode) {
                GestureMode.TAP -> {
                    upChange?.consume()
                    c.pressedClip = null
                    if (isSecondTap) {
                        lastTapUptime = 0L
                        c.doubleTap(hit)
                    } else {
                        lastTapUptime = upChange?.uptimeMillis ?: down.uptimeMillis
                        lastTapPos = down.position
                        c.tap(hit)
                    }
                }
                GestureMode.LONG_PRESS -> {
                    c.pressedClip = null
                    c.longPress(hit)
                    // Swallow the rest of this gesture.
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
                GestureMode.DRAG -> {
                    lastTapUptime = 0L
                    val start = dragStart!!
                    when {
                        hit.kind == HitKind.CLIP_BAR || hit.kind == HitKind.CLIP_MENU ->
                            clipDrag(c, hit, start, this@coroutineScope)
                        hit.isWaveArea -> selectionDrag(c, hit, start, this@coroutineScope)
                        else -> fling = scrollDrag(c, hit, start, this@coroutineScope)
                    }
                }
                GestureMode.TRANSFORM -> {
                    lastTapUptime = 0L
                    c.pressedClip = null
                    fling = transform(c, this@coroutineScope)
                }
            }
        } finally {
            c.state.gestureActive = false
        }
    }
}

/** Mouse wheel / trackpad scroll events. */
internal suspend fun PointerInputScope.panelWheel(c: PanelController) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Main)
            if (ev.type != PointerEventType.Scroll) continue
            val ch = ev.changes.firstOrNull() ?: continue
            val delta = ch.scrollDelta
            if (ev.keyboardModifiers.isCtrlPressed) {
                val factor = if (delta.y < 0f) 2.0 else 0.5
                c.zoom(factor, ch.position.x)
            } else if (ev.keyboardModifiers.isShiftPressed) {
                c.pan(-delta.y * 48f * density, 0f)
            } else {
                c.pan(-delta.x * 48f * density, -delta.y * 48f * density)
            }
            ch.consume()
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.selectionDrag(
    c: PanelController, hit: Hit, start: PointerInputChange, scope: CoroutineScope,
) {
    c.selectionDragStart(hit)
    c.selectionDragMove(c.hitTest(start.position))
    start.consume()
    var last = start.position
    val autoScroll = scope.launch { autoScrollLoop(c) { last } }
    try {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == start.id } ?: break
            if (!ch.pressed) {
                ch.consume()
                break
            }
            if (ch.positionChange() != Offset.Zero) {
                last = ch.position
                c.selectionDragMove(c.hitTest(ch.position))
            }
            ch.consume()
        }
    } finally {
        autoScroll.cancel()
        c.selectionDragEnd()
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.clipDrag(
    c: PanelController, hit: Hit, start: PointerInputChange, scope: CoroutineScope,
) {
    c.clipDragStart(hit)
    c.clipDragMove(c.hitTest(start.position))
    start.consume()
    var last = start.position
    val autoScroll = scope.launch { autoScrollLoop(c, clip = true) { last } }
    var completed = false
    try {
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == start.id } ?: break
            if (!ch.pressed) {
                ch.consume()
                completed = true
                break
            }
            if (ch.positionChange() != Offset.Zero) {
                last = ch.position
                c.clipDragMove(c.hitTest(ch.position))
            }
            ch.consume()
        }
    } finally {
        autoScroll.cancel()
        if (completed) c.clipDragEnd() else c.cancelDrags()
    }
}

/** Scrolls while the finger rests near the left/right edge of the wave area. */
private suspend fun autoScrollLoop(c: PanelController, clip: Boolean = false, position: () -> Offset) {
    var lastNanos = 0L
    while (true) {
        val now = withFrameNanos { it }
        val dtSec = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1e9
        lastNanos = now
        val m = c.layout.metrics
        val xd = position().x / c.density - m.waveLeft
        val width = c.state.usableWidthDp.toFloat()
        val zone = m.autoScrollZone
        val speedDp = when {
            xd < zone -> -(zone - xd) / zone
            xd > width - zone -> (xd - (width - zone)) / zone
            else -> 0f
        }.coerceIn(-1.5f, 1.5f) * width * 1.2f
        if (speedDp != 0f && dtSec > 0.0) {
            c.state.scrollByDp(speedDp * dtSec)
            val hit = c.hitTest(position())
            if (clip) c.clipDragMove(hit) else c.selectionDragMove(hit)
        }
    }
}

/** One-finger scroll (TCP column, headers, below the tracks); returns the fling job. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.scrollDrag(
    c: PanelController, hit: Hit, start: PointerInputChange, scope: CoroutineScope,
): Job? {
    val horizontal = hit.kind != HitKind.TCP && hit.kind != HitKind.VR
    val tracker = VelocityTracker()
    tracker.addPosition(start.uptimeMillis, start.position)
    c.pan(start.position.x - start.previousPosition.x, start.position.y - start.previousPosition.y, horizontal, true)
    start.consume()
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == start.id } ?: break
        if (!ch.pressed) {
            ch.consume()
            break
        }
        val delta = ch.positionChange()
        tracker.addPosition(ch.uptimeMillis, ch.position)
        c.pan(delta.x, delta.y, horizontal, true)
        ch.consume()
    }
    val v = tracker.calculateVelocity()
    return startFling(c, scope, if (horizontal) v.x else 0f, v.y)
}

/** Two-finger pan / pinch until all fingers are up; returns the fling job. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.transform(
    c: PanelController, scope: CoroutineScope,
): Job? {
    val tracker = VelocityTracker()
    var verticalPinch: Boolean? = null
    var lastUptime = 0L
    var centroid = Offset.Unspecified
    while (true) {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.filter { it.pressed && it.previousPressed }
        if (ev.changes.none { it.pressed }) {
            ev.changes.forEach { it.consume() }
            break
        }
        if (pressed.isEmpty()) {
            ev.changes.forEach { it.consume() }
            continue
        }
        // Centroids before and after this event.
        var cx = 0f; var cy = 0f; var px = 0f; var py = 0f
        for (p in pressed) {
            cx += p.position.x; cy += p.position.y
            px += p.previousPosition.x; py += p.previousPosition.y
        }
        val n = pressed.size
        cx /= n; cy /= n; px /= n; py /= n
        c.pan(cx - px, cy - py)
        if (n >= 2) {
            val a = pressed[0]
            val b = pressed[1]
            val dxNow = abs(a.position.x - b.position.x)
            val dyNow = abs(a.position.y - b.position.y)
            val dxPrev = abs(a.previousPosition.x - b.previousPosition.x)
            val dyPrev = abs(a.previousPosition.y - b.previousPosition.y)
            if (verticalPinch == null && hypot(dxNow, dyNow) > 0f) {
                verticalPinch = dyNow > 2.5f * dxNow
            }
            if (verticalPinch == true) {
                if (dyPrev > 24f * c.density && dyNow > 0f) c.resizeTrack(dyNow / dyPrev, cy)
            } else if (dxPrev > 16f * c.density && dxNow > 0f) {
                c.zoom((dxNow / dxPrev).toDouble(), cx)
            }
        }
        lastUptime = pressed[0].uptimeMillis
        centroid = Offset(cx, cy)
        tracker.addPosition(lastUptime, centroid)
        ev.changes.forEach { it.consume() }
    }
    if (centroid == Offset.Unspecified) return null
    val v = tracker.calculateVelocity()
    return startFling(c, scope, v.x, v.y)
}

private fun startFling(c: PanelController, scope: CoroutineScope, vx: Float, vy: Float): Job? {
    val min = 120f * c.density
    val fx = if (abs(vx) > min) vx else 0f
    val fy = if (abs(vy) > min) vy else 0f
    if (fx == 0f && fy == 0f) return null
    return scope.launch {
        val decay = exponentialDecay<Float>(frictionMultiplier = 1.4f)
        val jobX = if (fx != 0f) launch {
            var last = 0f
            AnimationState(0f, fx).animateDecay(decay) {
                c.pan(value - last, 0f, horizontal = true, vertical = false)
                last = value
            }
        } else null
        if (fy != 0f) {
            var last = 0f
            AnimationState(0f, fy).animateDecay(decay) {
                c.pan(0f, value - last, horizontal = false, vertical = true)
                last = value
            }
        }
        jobX?.join()
    }
}
