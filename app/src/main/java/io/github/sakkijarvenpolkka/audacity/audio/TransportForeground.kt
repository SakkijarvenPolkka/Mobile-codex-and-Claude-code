/*
 * Audacity Android port — runs the [TransportService] while this project
 * plays or records (transport events, API.md §4.5).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.audio

import android.content.Context
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object TransportForeground {
    /** What the service should do for a transport state. */
    enum class Want { RECORDING, PLAYING, KEEP, STOP }

    fun want(state: String): Want = when (state) {
        "recording" -> Want.RECORDING
        "playing" -> Want.PLAYING
        // Paused playback/recording keeps the service (and its type)
        "paused" -> Want.KEEP
        // stopped, monitoring (input meter only, not kept in the background)
        else -> Want.STOP
    }

    /** Follows [engine]'s transport state for the life of [scope]. */
    fun follow(context: Context, engine: AudacityEngine, scope: CoroutineScope): Job {
        val app = context.applicationContext
        return scope.launch(Dispatchers.Main.immediate) {
            engine.transportState.map { it.state }.distinctUntilChanged().collect { state ->
                when (want(state)) {
                    Want.RECORDING -> TransportService.start(app, recording = true)
                    Want.PLAYING -> TransportService.start(app, recording = false)
                    Want.KEEP -> Unit
                    Want.STOP -> TransportService.stop(app)
                }
            }
        }
    }
}
