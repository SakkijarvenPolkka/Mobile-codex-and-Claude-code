/*
 * Audacity Android port — what Android's audio policy expects from a
 * recorder/player besides the foreground service (notes/audio-io.md §4.11,
 * §4.13):
 *
 *  - audio focus (AUDIOFOCUS_GAIN, USAGE_MEDIA) while playing or recording:
 *    other players stop instead of sounding through the speaker into the
 *    microphone; losing it (a call rings, another player starts) pauses;
 *  - ACTION_AUDIO_BECOMING_NOISY (headphones unplugged) pauses playback and
 *    recording before the sound moves to the speaker;
 *  - a recording whose microphone input Android silences (a call, an
 *    assistant: AudioRecordingConfiguration.isClientSilenced, API 29) is
 *    paused instead of filling the take with silence;
 *  - input monitoring (the meter) stops when the app goes to the background:
 *    it has no foreground service and would keep a low-latency input stream
 *    waking the CPU every few milliseconds, silenced.
 *
 * Pauses go through transport.pause {paused: true, cause}, so the engine's
 * transport event carries reason "device" and a message the UI shows; a
 * paused transport is never resumed automatically.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.audio

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AudioInterruptions(
    context: Context,
    private val engine: AudacityEngine,
    private val scope: CoroutineScope,
) {
    /** Why the transport is paused; the `cause` of transport.pause. */
    enum class Cause(val wire: String) { FOCUS("focus"), NOISY("noisy"), SILENCED("silenced") }

    /** One AudioRecordingConfiguration, as far as [isOwnInputSilenced] needs it. */
    data class Recording(val source: Int, val silenced: Boolean)

    private val app = context.applicationContext
    private val audioManager: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var state = "stopped"
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var recordingCallbackRegistered = false
    private var startedActivities = 0
    private var job: Job? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (pausesFor(change)) pause(Cause.FOCUS)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause(Cause.NOISY)
        }
    }

    private val recordingCallback: AudioManager.AudioRecordingCallback? =
        if (Build.VERSION.SDK_INT >= 29) silencingCallback() else null

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities > 0) startedActivities--
            // A configuration change restarts the activity at once: not the background
            if (startedActivities == 0 && !activity.isChangingConfigurations) onBackground()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Follows the transport for the life of [scope] (main thread; idempotent). */
    fun start(): Job {
        job?.let { return it }
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        return scope.launch(Dispatchers.Main.immediate) {
            try {
                engine.transportState.map { it.state }.distinctUntilChanged().collect { onState(it) }
            } finally {
                onState("stopped")
                (app as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
            }
        }.also { job = it }
    }

    @VisibleForTesting
    internal fun onState(newState: String) {
        state = newState
        val active = newState == "playing" || newState == "recording"
        if (active) requestFocus() else abandonFocus()
        setNoisyReceiver(active)
        setRecordingCallback(newState == "recording")
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val am = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        val result = try {
            am.requestAudioFocus(request)
        } catch (e: RuntimeException) {
            Log.w(TAG, "requestAudioFocus: $e")
            return
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        } else {
            // A call is active: do not play over it (nor record silence)
            Log.i(TAG, "audio focus refused ($result)")
            pause(Cause.FOCUS)
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        runCatching { audioManager?.abandonAudioFocusRequest(request) }
    }

    private fun setNoisyReceiver(on: Boolean) {
        if (on == noisyRegistered) return
        noisyRegistered = on
        if (on) {
            ContextCompat.registerReceiver(
                app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            runCatching { app.unregisterReceiver(noisyReceiver) }
        }
    }

    private fun setRecordingCallback(on: Boolean) {
        val cb = recordingCallback ?: return
        val am = audioManager ?: return
        if (on == recordingCallbackRegistered) return
        recordingCallbackRegistered = on
        if (on) {
            am.registerAudioRecordingCallback(cb, handler)
            // The callback reports changes only: check the current state once
            onRecordingConfigs(am.activeRecordingConfigurations)
        } else {
            am.unregisterAudioRecordingCallback(cb)
        }
    }

    @RequiresApi(29)
    private fun silencingCallback() = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            onRecordingConfigs(configs.orEmpty())
        }
    }

    private fun onRecordingConfigs(configs: List<AudioRecordingConfiguration>) {
        if (Build.VERSION.SDK_INT < 29 || state != "recording") return
        val recordings = configs.map { Recording(it.clientAudioSource, it.isClientSilenced) }
        if (isOwnInputSilenced(recordings)) pause(Cause.SILENCED)
    }

    private fun onBackground() {
        if (state != "monitoring") return
        scope.launch {
            try {
                engine.monitor(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cannot stop monitoring: $e")
            }
        }
    }

    private fun pause(cause: Cause) {
        if (!shouldPause(cause, state)) return
        scope.launch {
            // The state may have changed meanwhile (the follower runs on the main thread too)
            if (!shouldPause(cause, engine.transportState.value.state)) return@launch
            try {
                engine.invokeCommand(
                    "transport.pause",
                    buildJsonObject {
                        put("paused", true)
                        put("cause", cause.wire)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                if (e.code == "UNKNOWN_COMMAND") {
                    // An engine without the raw command (the fake engine): its toggle, the
                    // state was checked above
                    runCatching { engine.pause() }
                } else {
                    Log.w(TAG, "cannot pause ($cause): $e")
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot pause ($cause): $e")
            }
        }
    }

    companion object {
        private const val TAG = "AudacityInterruptions"

        /** MediaRecorder.AudioSource values of the input presets the engine may use
         *  (audio.setInputOptions: generic, camcorder, voice recognition, unprocessed,
         *  voice performance). */
        private val OWN_SOURCES = setOf(1, 5, 6, 9, 10)

        /** Whether an interruption pauses the transport in [state]. */
        fun shouldPause(cause: Cause, state: String): Boolean = when (cause) {
            Cause.FOCUS, Cause.NOISY -> state == "playing" || state == "recording"
            Cause.SILENCED -> state == "recording"
        }

        /** A transient or permanent loss pauses; for "can duck" the system lowers our volume. */
        fun pausesFor(focusChange: Int): Boolean =
            focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT

        /**
         * True when the app's own capture is silenced. An ordinary app receives every
         * app's configurations anonymized (no uid or package), so "own" is approximated
         * by the audio sources the engine records with: all such recordings silenced.
         * A non-silenced one may be ours, e.g. while we silence a background recorder:
         * then nothing is paused (a missed detection rather than a wrong pause).
         */
        fun isOwnInputSilenced(recordings: List<Recording>): Boolean {
            val candidates = recordings.filter { it.source in OWN_SOURCES }
            return candidates.isNotEmpty() && candidates.all { it.silenced }
        }
    }
}
