/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineEventHubTest {
    private fun EngineEventHub.send(type: String, json: String) = dispatch(type, json.encodeToByteArray())

    @Test
    fun readyAndFailedUpdateStatus() = runTest {
        val hub = EngineEventHub()
        val logs = mutableListOf<LogEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { hub.logs.toList(logs) }
        hub.send("engine.ready", """{"audacityVersion":"3.7.9","recoverable":2,
            "selfChecks":[{"name":"effects","ok":true},{"name":"nyquistRuntime","ok":false,"message":"no nyquist.lsp"}]}""")
        assertEquals(EngineStatus.Ready(2), hub.status.value)
        assertEquals("3.7.9", hub.readyInfo.value?.audacityVersion)
        assertTrue(logs.any { it.level == "warning" && it.message.contains("nyquistRuntime") })
        hub.send("engine.failed", """{"message":"no temp dir"}""")
        assertEquals(EngineStatus.Failed("no temp dir"), hub.status.value)
        job.cancel()
    }

    @Test
    fun progressUpdatesAreMergedIntoBegin() {
        val hub = EngineEventHub()
        hub.send("progress", """{"id":5,"phase":"begin","title":"Applying Amplify...","message":"","fraction":0.0,"cancellable":true,"stoppable":true}""")
        hub.send("progress", """{"id":5,"phase":"update","fraction":0.37,"message":"pass 1"}""")
        val p = hub.progress.value[5]!!
        assertEquals("Applying Amplify...", p.title)
        assertEquals(0.37, p.fraction, 0.0)
        assertEquals("pass 1", p.message)
        assertTrue(p.stoppable)
        hub.send("progress", """{"id":5,"phase":"update","fraction":-1}""")
        assertEquals("pass 1", hub.progress.value[5]!!.message)
        assertEquals(-1.0, hub.progress.value[5]!!.fraction, 0.0)
        hub.send("progress", """{"id":5,"phase":"end"}""")
        assertTrue(hub.progress.value.isEmpty())
    }

    @Test
    fun dialogsStayPendingUntilResolved() = runTest {
        val hub = EngineEventHub()
        hub.send("dialog", """{"id":9,"kind":"message","style":"question","title":"Audacity","message":"Save?",
            "buttons":["Yes","No"],"defaultButton":0,"blocking":true,"helpPage":""}""")
        hub.send("dialog", """{"id":10,"kind":"choice","style":"info","title":"Streams","message":"Pick",
            "buttons":["OK","Cancel"],"choices":["A","B"],"blocking":true}""")
        assertEquals(listOf(9, 10), hub.pendingDialogs.value.map { it.id })
        assertEquals(listOf("A", "B"), hub.pendingDialogs.value[1].choices)
        assertTrue(hub.resolveDialog(9)!!.blocking)
        assertNull(hub.resolveDialog(9))
        assertEquals(listOf(10), hub.pendingDialogs.value.map { it.id })
    }

    @Test
    fun transportAndSnapshotEvents() {
        val hub = EngineEventHub()
        hub.send("transport", """{"state":"playing","reason":"user","dropouts":0}""")
        assertEquals("playing", hub.transportState.value.state)
        hub.send("snapshot", """{"generation":3,"project":{"open":true,"name":"A"},"tracks":[],"flags":1}""")
        assertEquals(3L, hub.snapshot.value.generation)
        assertEquals("A", hub.snapshot.value.project.name)
    }

    @Test
    fun malformedAndUnknownEventsAreLoggedNotThrown() = runTest {
        val hub = EngineEventHub()
        val logs = mutableListOf<LogEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { hub.logs.toList(logs) }
        hub.send("snapshot", """{"generation":"not a number"""")
        hub.send("whatever", "{}")
        hub.send("log", """{"level":"info","message":"hello"}""")
        assertEquals(0L, hub.snapshot.value.generation)
        assertEquals(listOf("error", "debug", "info"), logs.map { it.level })
        job.cancel()
    }
}
