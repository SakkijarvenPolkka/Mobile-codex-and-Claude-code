// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.audio.AudioDeviceMonitor
import io.github.sakkijarvenpolkka.audacity.audio.TransportForeground
import io.github.sakkijarvenpolkka.audacity.audio.TransportService
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** The recording/playback foreground service and the device-list injection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundAudioTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engines = mutableListOf<FakeAudacityEngine>()

    private fun engine() = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false)).also { engines += it }

    @After
    fun tearDown() {
        scope.cancel()
        engines.forEach { it.dispose() }
    }

    private fun startCommand(intent: Intent): TransportService =
        Robolectric.buildService(TransportService::class.java, intent).create().startCommand(0, 1).get()

    private fun update(recording: Boolean) = Intent(app, TransportService::class.java)
        .setAction(TransportService.ACTION_UPDATE).putExtra(TransportService.EXTRA_RECORDING, recording)

    @Test
    fun transportStatesMapToServiceStates() {
        assertEquals(TransportForeground.Want.RECORDING, TransportForeground.want("recording"))
        assertEquals(TransportForeground.Want.PLAYING, TransportForeground.want("playing"))
        assertEquals(TransportForeground.Want.KEEP, TransportForeground.want("paused"))
        assertEquals(TransportForeground.Want.STOP, TransportForeground.want("stopped"))
        assertEquals(TransportForeground.Want.STOP, TransportForeground.want("monitoring"))
    }

    @Test
    fun recordingRunsAsMicrophoneAndPlaybackService() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startCommand(update(recording = true))
        val shadow = shadowOf(service)
        assertEquals(TransportService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            service.foregroundServiceType)
        val n = shadow.lastForegroundNotification
        assertNotNull(n)
        assertEquals("Recording…", shadowOf(n).contentText)
        assertEquals("Stop", n.actions.single().title)
    }

    @Test
    fun withoutTheMicrophonePermissionItIsAPlaybackService() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = startCommand(update(recording = true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
    }

    @Test
    fun aRefusedForegroundStartStopsTheServiceInsteadOfCrashing() {
        val controller = Robolectric.buildService(TransportService::class.java, update(recording = false)).create()
        shadowOf(controller.get()).setThrowInStartForeground(SecurityException("not allowed"))
        controller.startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun stopCommandRemovesTheNotification() {
        val controller = Robolectric.buildService(TransportService::class.java, update(recording = false)).create().startCommand(0, 1)
        controller.withIntent(Intent(app, TransportService::class.java).setAction(TransportService.ACTION_STOP_SERVICE)).startCommand(0, 2)
        val shadow = shadowOf(controller.get())
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.isStoppedBySelf)
    }

    @Test
    fun theServiceFollowsTheTransport() {
        val e = engine()
        TransportForeground.follow(app, e, scope)
        val shadowApp = shadowOf(app)
        runBlocking { e.play() }
        val started = waitForStartedService()
        assertEquals(TransportService.ACTION_UPDATE, started.action)
        assertEquals(false, started.getBooleanExtra(TransportService.EXTRA_RECORDING, true))
        assertTrue(TransportService.active)
        runBlocking { e.stop() }
        assertEquals(TransportService.ACTION_STOP_SERVICE, waitForStartedService().action)
        assertTrue(!TransportService.active)
        assertTrue(shadowApp.nextStartedService == null)
    }

    private fun waitForStartedService(): Intent = runBlocking {
        withTimeout(5_000) {
            var intent: Intent? = null
            while (intent == null) {
                shadowOf(Looper.getMainLooper()).idle()
                intent = shadowOf(app).nextStartedService
                if (intent == null) delay(20)
            }
            intent
        }
    }

    @Test
    fun androidDevicesAreDescribedForTheEngine() {
        val d = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_HEADSET).build()
        val spec = AudioDeviceMonitor.specOf(d)!!
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, spec.type)
        assertEquals(d.id, spec.id)
        assertEquals(d.productName.toString(), spec.name)
    }

    @Test
    fun theDeviceListIsInjectedAndFollowsChanges() {
        var devices = listOf(
            AudioDeviceSpec(3, "C-Media", AudioDeviceInfo.TYPE_USB_HEADSET, isSource = false, isSink = true, listOf(2), listOf(48000)),
            AudioDeviceSpec(4, "C-Media", AudioDeviceInfo.TYPE_USB_HEADSET, isSource = true, isSink = false, listOf(1), listOf(48000)),
        )
        val e = engine()
        val monitor = AudioDeviceMonitor(app, e, scope) { devices }
        monitor.start()
        awaitOutputs(e) { names -> "USB headset: C-Media" in names }
        assertEquals(listOf("Default Input", "USB headset: C-Media (id 4)"), runBlocking { e.audioDevices().inputs.map { it.name } })
        // Unplugged: the callback re-sends the list
        devices = emptyList()
        val am = app.getSystemService(AudioManager::class.java)
        shadowOf(am).setOutputDevices(emptyList())
        monitor.stop()
        monitor.start()
        awaitOutputs(e) { names -> names == listOf("Default Output") }
        monitor.stop()
    }

    private fun awaitOutputs(e: FakeAudacityEngine, done: (List<String>) -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!done(e.audioDevices().outputs.map { it.name })) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(50)
            }
        }
    }
}
