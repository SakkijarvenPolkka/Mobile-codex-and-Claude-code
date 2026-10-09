/*
 * Audacity Android port — edit helpers shared by the mobile edit bar, the
 * razor tool and the track panel's accessibility actions.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.SplitResult
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal object EditActions {
    /** Longest extrapolation of a transport sample to "now" (else the sampled time is used). */
    private const val MAX_EXTRAPOLATION_NANOS = 250_000_000L
    private const val STOP_WAIT_MS = 2_000L
    private const val BUSY_RETRY_MS = 150L

    /**
     * The play/record head as displayed now (the transport sample
     * extrapolated to `System.nanoTime()`, API.md §6.4), or NaN when the
     * transport is not active.
     */
    fun headTime(engine: AudacityEngine): Double {
        val tr = engine.readTransport()
        if (!tr.isActive) return Double.NaN
        val now = System.nanoTime()
        val elapsed = now - tr.sampledAtNanos
        return if (elapsed in 0..MAX_EXTRAPOLATION_NANOS) tr.headTime(now) else tr.displayTime
    }

    /** True while this project records (also paused). */
    fun isRecording(engine: AudacityEngine): Boolean {
        val s = engine.readTransport().state
        return s == TransportSample.STATE_RECORDING || s == TransportSample.STATE_PAUSED_RECORD
    }

    /** True while this project plays (also paused). */
    fun isPlaying(engine: AudacityEngine): Boolean {
        val s = engine.readTransport().state
        return s == TransportSample.STATE_PLAYING || s == TransportSample.STATE_PAUSED_PLAY
    }

    /**
     * Stops playback so that an edit can run (edits are not allowed while
     * audio is busy) and waits until the transport reports it stopped.
     * Recording is never stopped: throws AUDIO_BUSY with [notWhileRecording].
     */
    suspend fun stopPlaybackForEdit(engine: AudacityEngine, notWhileRecording: String) {
        if (isRecording(engine)) throw EngineException(ErrorCodes.AUDIO_BUSY, notWhileRecording)
        if (!isPlaying(engine)) return
        engine.stop()
        withTimeoutOrNull(STOP_WAIT_MS) {
            engine.transportState.first { it.state != "playing" && it.state != "paused" && it.state != "recording" }
        }
    }

    /**
     * `edit.splitAt` at [t] after stopping playback; one retry when the
     * stream was still stopping (AUDIO_BUSY).
     */
    suspend fun splitAt(engine: AudacityEngine, t: Double, trackIds: List<Long>?, notWhileRecording: String): SplitResult {
        stopPlaybackForEdit(engine, notWhileRecording)
        return try {
            engine.splitAt(t, trackIds)
        } catch (e: EngineException) {
            if (e.code != ErrorCodes.AUDIO_BUSY || isRecording(engine)) throw e
            delay(BUSY_RETRY_MS)
            engine.splitAt(t, trackIds)
        }
    }
}
