/*
 * Audacity Android port — play/record head for drawing (API.md §6.4,
 * notes/audio-io.md §2.10).
 *
 * While the transport is active, readTransport() is sampled on every frame
 * (withFrameNanos, the Choreographer clock = System.nanoTime) and the
 * latency-compensated display time is extrapolated to the frame time. Small
 * backward steps caused by a fresh engine sample are held (the head never
 * moves backwards except on a loop wrap or a seek).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** Observable head position, updated once per frame while audio streams. */
@Stable
internal class HeadState {
    /** Extrapolated head time; NaN when the transport is stopped. */
    var time by mutableDoubleStateOf(Double.NaN)
        internal set

    /** TransportSample.STATE_* of the last sample. */
    var transportState by mutableIntStateOf(TransportSample.STATE_STOPPED)
        internal set

    /** Recording start time of the current recording (NaN otherwise). */
    var recordingStart: Double = Double.NaN
        internal set

    val isRecording: Boolean
        get() = transportState == TransportSample.STATE_RECORDING ||
            transportState == TransportSample.STATE_PAUSED_RECORD

    val isPlaying: Boolean
        get() = transportState == TransportSample.STATE_PLAYING ||
            transportState == TransportSample.STATE_PAUSED_PLAY

    val isStreaming: Boolean
        get() = transportState == TransportSample.STATE_PLAYING ||
            transportState == TransportSample.STATE_RECORDING

    val isActive: Boolean
        get() = transportState in TransportSample.STATE_PLAYING..TransportSample.STATE_PAUSED_RECORD

    private var lastSampledAt = 0L
    private var lastTime = Double.NaN

    /** Applies a fresh engine sample at frame time [nowNanos]. */
    fun update(s: TransportSample, nowNanos: Long, playEnd: Double) {
        transportState = s.state
        recordingStart = if (s.isRecording || s.state == TransportSample.STATE_PAUSED_RECORD) s.recordingStart else Double.NaN
        if (!s.isActive) {
            time = Double.NaN
            lastTime = Double.NaN
            return
        }
        var t = s.headTime(nowNanos, playEnd)
        if (!t.isNaN() && !lastTime.isNaN() && s.sampledAtNanos != lastSampledAt) {
            // A fresh sample slightly behind our extrapolation: hold.
            if (t < lastTime && lastTime - t < HOLD_SECONDS) t = lastTime
        } else if (!t.isNaN() && !lastTime.isNaN() && t < lastTime && lastTime - t < HOLD_SECONDS) {
            t = lastTime
        }
        lastSampledAt = s.sampledAtNanos
        lastTime = t
        time = t
    }

    fun reset() {
        transportState = TransportSample.STATE_STOPPED
        time = Double.NaN
        lastTime = Double.NaN
        recordingStart = Double.NaN
    }

    companion object {
        private const val HOLD_SECONDS = 0.08
    }
}

/**
 * Remembers a [HeadState] driven by [engine]. [playEnd] bounds the
 * extrapolation while playing; [onFrame] runs after each update while the
 * transport is active (follow-play-head scrolling).
 */
@Composable
internal fun rememberHeadState(
    engine: AudacityEngine,
    playEnd: () -> Double = { Double.POSITIVE_INFINITY },
    onFrame: (HeadState) -> Unit = {},
): HeadState {
    val head = remember(engine) { HeadState() }
    val currentPlayEnd by rememberUpdatedState(playEnd)
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(engine, head) {
        engine.transportState.collectLatest { ev ->
            val active = ev.state == "playing" || ev.state == "recording" || ev.state == "paused"
            if (!active) {
                // One last read so a "stopping" state settles, then idle.
                val s = runCatching { engine.readTransport() }.getOrNull()
                if (s != null && s.isActive) {
                    head.update(s, System.nanoTime(), currentPlayEnd())
                } else {
                    head.reset()
                }
                currentOnFrame(head)
                return@collectLatest
            }
            while (isActive) {
                withFrameNanos { now ->
                    val s = runCatching { engine.readTransport() }.getOrNull()
                    if (s != null) head.update(s, now, currentPlayEnd())
                    currentOnFrame(head)
                }
                if (!head.isActive && head.transportState != TransportSample.STATE_STOPPING) {
                    // The engine stopped before its transport event arrived.
                    head.reset()
                }
            }
        }
    }
    return head
}
