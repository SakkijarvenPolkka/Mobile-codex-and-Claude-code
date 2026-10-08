// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.audio.AudioDeviceMonitor
import io.github.sakkijarvenpolkka.audacity.audio.AudioInterruptions
import io.github.sakkijarvenpolkka.audacity.audio.AudioInterruptions.Cause
import io.github.sakkijarvenpolkka.audacity.audio.AudioInterruptions.Recording
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Audio focus, headphones unplugged, silenced microphone, background monitoring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioInterruptionsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val am: AudioManager = app.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engines = mutableListOf<FakeAudacityEngine>()

    private fun engine() = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false)).also { engines += it }

    @After
    fun tearDown() {
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        engines.forEach { it.dispose() }
    }

    private fun awaitState(e: AudacityEngine, state: String) = runBlocking {
        withTimeout(5_000) {
            while (e.transportState.value.state != state) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
        }
        // let the follower see it
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun follow(e: AudacityEngine) {
        AudioInterruptions(app, e, scope).start()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun decisions() {
        assertTrue(AudioInterruptions.shouldPause(Cause.FOCUS, "playing"))
        assertTrue(AudioInterruptions.shouldPause(Cause.FOCUS, "recording"))
        assertFalse(AudioInterruptions.shouldPause(Cause.FOCUS, "paused"))
        assertFalse(AudioInterruptions.shouldPause(Cause.NOISY, "monitoring"))
        assertTrue(AudioInterruptions.shouldPause(Cause.NOISY, "playing"))
        assertTrue(AudioInterruptions.shouldPause(Cause.SILENCED, "recording"))
        assertFalse(AudioInterruptions.shouldPause(Cause.SILENCED, "playing"))
        assertTrue(AudioInterruptions.pausesFor(AudioManager.AUDIOFOCUS_LOSS))
        assertTrue(AudioInterruptions.pausesFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertFalse(AudioInterruptions.pausesFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(AudioInterruptions.pausesFor(AudioManager.AUDIOFOCUS_GAIN))
        val vr = MediaRecorder.AudioSource.VOICE_RECOGNITION
        // a call silences every recording
        assertTrue(AudioInterruptions.isOwnInputSilenced(listOf(Recording(vr, true))))
        assertTrue(AudioInterruptions.isOwnInputSilenced(listOf(Recording(vr, true), Recording(MediaRecorder.AudioSource.CAMCORDER, true))))
        // we silence a background recorder: ours (not silenced) is among them
        assertFalse(AudioInterruptions.isOwnInputSilenced(listOf(Recording(vr, true), Recording(vr, false))))
        // a silenced recording with a source the engine never uses is someone else's
        assertFalse(AudioInterruptions.isOwnInputSilenced(listOf(Recording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true))))
        assertFalse(AudioInterruptions.isOwnInputSilenced(emptyList()))
    }

    @Test
    fun playbackHoldsTheAudioFocusAndPausesWhenItIsLost() {
        val e = engine()
        follow(e)
        runBlocking { e.play() }
        awaitState(e, "playing")
        val request = shadowOf(am).lastAudioFocusRequest
        assertNotNull(request)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.audioFocusRequest.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioFocusRequest.audioAttributes.usage)
        // a call rings
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        awaitState(e, "paused")
        // paused: the focus is given back, nothing resumes by itself
        assertEquals(request.audioFocusRequest, shadowOf(am).lastAbandonedAudioFocusRequest)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("paused", e.transportState.value.state)
        // resumed by the user: focus again
        runBlocking { e.pause() }
        awaitState(e, "playing")
        assertTrue(shadowOf(am).lastAudioFocusRequest.audioFocusRequest !== request.audioFocusRequest)
        // ducking is done by the system
        shadowOf(am).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("playing", e.transportState.value.state)
        runBlocking { e.stop() }
        awaitState(e, "stopped")
    }

    @Test
    fun refusedFocusPauses() {
        val e = engine()
        follow(e)
        shadowOf(am).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        runBlocking { e.play() }
        awaitState(e, "paused")
    }

    @Test
    fun unpluggedHeadphonesPausePlayback() {
        val e = engine()
        follow(e)
        runBlocking { e.play() }
        awaitState(e, "playing")
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        awaitState(e, "paused")
        // not registered while paused/stopped: another broadcast changes nothing
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("paused", e.transportState.value.state)
    }

    private fun recordingConfig(source: Int, silenced: Boolean): AudioRecordingConfiguration {
        val c = shadowOf(am).createActiveRecordingConfiguration(1, source, "io.github.sakkijarvenpolkka.audacity")
        ReflectionHelpers.setField(c, "mClientSilenced", silenced)
        return c
    }

    @Test
    fun aSilencedMicrophonePausesTheRecording() {
        val e = engine()
        runBlocking { e.setRecordPermission(true) }
        follow(e)
        runBlocking { e.record(newTrack = true) }
        awaitState(e, "recording")
        // our recording runs
        shadowOf(am).setActiveRecordingConfigurations(listOf(recordingConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, false)), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("recording", e.transportState.value.state)
        // a call: Android silences it
        shadowOf(am).setActiveRecordingConfigurations(listOf(recordingConfig(MediaRecorder.AudioSource.VOICE_RECOGNITION, true)), true)
        awaitState(e, "paused")
        runBlocking { e.stop() }
        awaitState(e, "stopped")
    }

    @Test
    fun monitoringStopsInTheBackground() {
        val e = engine()
        runBlocking { e.setRecordPermission(true) }
        follow(e)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        runBlocking { e.monitor(true) }
        awaitState(e, "monitoring")
        activity.pause().stop()
        awaitState(e, "stopped")
        activity.destroy()
    }

    @Test
    fun theUnprocessedMicrophoneSupportIsReported() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val fake = engine()
        val e = object : AudacityEngine by fake {
            override suspend fun invokeCommand(command: String, args: JsonObject): JsonElement {
                synchronized(calls) { calls += command to args }
                return JsonObject(emptyMap())
            }
        }
        val monitor = AudioDeviceMonitor(app, e, scope, unprocessedSupported = true) { emptyList() }
        monitor.start()
        runBlocking {
            withTimeout(5_000) {
                while (synchronized(calls) { calls.isEmpty() }) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(20)
                }
            }
        }
        monitor.stop()
        val (command, args) = synchronized(calls) { calls.first() }
        assertEquals("audio.setInputOptions", command)
        assertEquals(true, (args["unprocessedSupported"] as JsonPrimitive).boolean)
    }
}
