// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineStatus
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/** The process-wide engine companions of [AudacityApp.attachEngineServices]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineServicesTest {
    /** A fake engine whose start outcome the test decides. */
    private class ScriptedEngine(val inner: FakeAudacityEngine) : AudacityEngine by inner {
        val statusFlow = MutableStateFlow<EngineStatus>(EngineStatus.Starting)
        override val status: StateFlow<EngineStatus> get() = statusFlow
        val deviceLists: MutableList<List<AudioDeviceSpec>> = Collections.synchronizedList(mutableListOf())

        override suspend fun setAudioDevices(devices: List<AudioDeviceSpec>): Boolean {
            deviceLists += devices
            return inner.setAudioDevices(devices)
        }
    }

    private val inner = FakeAudacityEngine(FakeConfig(autoTick = false))

    @After
    fun tearDown() = inner.dispose()

    private fun pump(millis: Long) = runBlocking {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
    }

    @Test
    fun theDeviceListIsSentWhenTheEngineBecomesReadyAfterAFailedFirstOutcome() {
        val app = ApplicationProvider.getApplicationContext<AudacityApp>()
        val engine = ScriptedEngine(inner)
        app.attachEngineServices(engine)
        // A start interrupted by a closed activity used to end as Failed; the
        // device monitor then never ran for the process lifetime
        engine.statusFlow.value = EngineStatus.Failed("interrupted")
        pump(600)
        assertEquals(0, engine.deviceLists.size)
        engine.statusFlow.value = EngineStatus.Ready(0)
        runBlocking {
            withTimeout(5_000) {
                while (engine.deviceLists.isEmpty()) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(20)
                }
            }
        }
        assertTrue(engine.deviceLists.isNotEmpty())
    }
}
