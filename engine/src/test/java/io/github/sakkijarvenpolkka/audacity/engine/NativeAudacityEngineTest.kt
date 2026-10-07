/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.StartConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class NativeAudacityEngineTest {

    private class FakeBridge(override val isLoaded: Boolean = true) : BridgeApi {
        var config: String? = null
        var listener: EngineListener? = null
        val calls: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
        val replies = mutableListOf<Pair<Int, Int>>()
        var respond: (String, String) -> String = { _, _ -> """{"ok":true,"result":{},"generation":1}""" }
        var transport: DoubleArray? = null
        var waveResult = 0L
        var displayThread: String? = null
        var missingJni = false

        override fun start(config: ByteArray, listener: EngineListener): Boolean {
            this.config = config.decodeToString()
            this.listener = listener
            return true
        }

        override fun invoke(command: ByteArray, args: ByteArray): ByteArray {
            if (missingJni) throw UnsatisfiedLinkError("no Java_..._invoke")
            val c = command.decodeToString()
            val a = args.decodeToString()
            calls += Triple(c, a, Thread.currentThread().name)
            return respond(c, a).encodeToByteArray()
        }

        override fun replyDialog(dialogId: Int, button: Int) { replies += dialogId to button }
        override fun cancelProgress(progressId: Int, stop: Boolean) {}
        override fun readTransport(out: DoubleArray): Boolean {
            if (missingJni) throw UnsatisfiedLinkError("no Java_..._readTransport")
            val t = transport ?: return false
            t.copyInto(out)
            return true
        }
        override fun readMeters(out: FloatArray): Boolean = false
        override fun waveColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
            displayThread = Thread.currentThread().name
            if (missingJni) throw UnsatisfiedLinkError("no Java_..._waveColumns")
            for (i in 0 until 3 * count) out[i] = i.toFloat()
            return waveResult
        }
        override fun envelopeColumns(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
            out.fill(1f)
            return 5
        }
        override fun waveSamples(trackId: Long, channel: Int, t0: Double, t1: Double): ByteArray =
            EngineProtocol.encodeWaveSamples(listOf(SampleRun(1, t0, 1 / 44100.0, floatArrayOf(0.5f), floatArrayOf(1f))))
        override fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int, out: ByteArray): Long =
            DisplayStatus.UNSUPPORTED
    }

    private val config = StartConfig("/files", "/no_backup", "/cache", "/files/audacity/nyquist", "/files/audacity/plug-ins", "ko_KR",
        "Pixel", 48000, 192, true)

    private fun engine(bridge: FakeBridge) = NativeAudacityEngine(bridge) { config }

    @Test
    fun unavailableWithoutLibrary() = runBlocking {
        val e = engine(FakeBridge(isLoaded = false))
        assertEquals(EngineStatus.Unavailable, e.status.value)
        e.start()
        assertEquals(EngineStatus.Unavailable, e.status.value)
        try {
            e.undo()
            fail("expected NOT_READY")
        } catch (ex: EngineException) {
            assertEquals(ErrorCodes.NOT_READY, ex.code)
        }
        assertEquals(TransportSample.IDLE, e.readTransport())
        assertNull(e.readMeters())
        assertNull(e.waveColumns(1, 0, 0, 0, 256))
    }

    @Test
    fun startPassesConfigAndReadyEventSetsStatus() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        assertEquals(EngineStatus.Starting, e.status.value)
        e.start()
        e.start() // idempotent
        val cfg = EngineJson.json.parseToJsonElement(bridge.config!!).jsonObject
        assertEquals("/files", (cfg["filesDir"] as JsonPrimitive).content)
        assertEquals("/files/audacity/nyquist", (cfg["nyquistDir"] as JsonPrimitive).content)
        assertEquals("ko_KR", (cfg["locale"] as JsonPrimitive).content)
        assertEquals("48000", (cfg["audioOutputSampleRate"] as JsonPrimitive).content)
        bridge.listener!!.onEvent("engine.ready", """{"audacityVersion":"3.7.9","selfChecks":[],"recoverable":0}""".encodeToByteArray())
        val status = withTimeout(5000) { e.status.first { it !is EngineStatus.Starting } }
        assertEquals(EngineStatus.Ready(0), status)
        assertEquals(EngineStatus.Ready(0), e.awaitStarted())
    }

    @Test
    fun commandsAreSerializedOnTheInvokeThreadWithExactArguments() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        e.select(1.0, 2.5)
        e.setView(hpos = 3.0)
        e.selectTracks(listOf(3L, 7L), "add")
        e.setTrackGain(3, 0.5, final = false)
        e.applyEffect("Effect_X", mapOf("Ratio" to JsonPrimitive(1.5)), null)
        e.contrast(0.0, 1.0, 2.0, 3.0)
        assertEquals(
            listOf(
                "select.set" to """{"t0":1.0,"t1":2.5}""",
                "view.set" to """{"hpos":3.0}""",
                "select.tracks" to """{"ids":[3,7],"mode":"add"}""",
                "tracks.setGain" to """{"id":3,"gain":0.5,"final":false}""",
                "effects.apply" to """{"id":"Effect_X","params":{"Ratio":1.5}}""",
                "analyze.contrast" to """{"foreground":{"t0":0.0,"t1":1.0},"background":{"t0":2.0,"t1":3.0}}""",
            ),
            bridge.calls.map { it.first to it.second },
        )
        // (coroutine debug mode appends " @coroutine#n" to thread names)
        assertTrue(bridge.calls.all { it.third.startsWith("audacity-invoke") })
    }

    @Test
    fun snapshotEmittedBeforeResponseIsVisibleWhenTheCallReturns() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        e.start()
        bridge.respond = { cmd, _ ->
            if (cmd == "edit.cut") {
                bridge.listener!!.onEvent("snapshot", """{"generation":43,"project":{"open":true,"name":"x"}}""".encodeToByteArray())
                bridge.listener!!.onEvent("progress", """{"id":1,"phase":"begin","title":"t"}""".encodeToByteArray())
            }
            """{"ok":true,"result":{},"generation":43}"""
        }
        e.edit("edit.cut")
        assertEquals(43L, e.snapshot.value.generation)
        assertTrue(e.progress.value.containsKey(1))
    }

    @Test
    fun errorEnvelopesAndTypedResults() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        bridge.respond = { cmd, _ ->
            when (cmd) {
                "tracks.add" -> """{"ok":true,"result":{"id":12},"generation":2}"""
                "labels.add" -> """{"ok":true,"result":{"trackId":7,"index":3},"generation":3}"""
                "project.save" -> """{"ok":false,"error":{"code":"NEEDS_PATH","message":"never saved"},"generation":3}"""
                "export.formats" -> """{"ok":true,"result":{"formats":[{"key":"WAV (Microsoft)","extensions":["wav"],"maxChannels":255}]},"generation":3}"""
                else -> """{"ok":true,"result":{},"generation":3}"""
            }
        }
        assertEquals(12L, e.addTrack("mono"))
        assertEquals(7L to 3, e.addLabel("x"))
        assertEquals("wav", e.exportFormats().single().extensions.single())
        try {
            e.saveProject()
            fail("expected NEEDS_PATH")
        } catch (ex: EngineException) {
            assertEquals(ErrorCodes.NEEDS_PATH, ex.code)
            assertEquals("never saved", ex.message)
        }
    }

    @Test
    fun dialogsRepliesGoToNativeOnlyForBlockingOnes() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        e.start()
        bridge.listener!!.onEvent("dialog", """{"id":1,"message":"info","blocking":false}""".encodeToByteArray())
        bridge.listener!!.onEvent("dialog", """{"id":2,"message":"ask","buttons":["Yes","No"],"blocking":true}""".encodeToByteArray())
        withTimeout(5000) { e.pendingDialogs.first { it.size == 2 } }
        e.replyDialog(1, 0)
        e.replyDialog(2, 1)
        assertEquals(listOf(2 to 1), bridge.replies)
        assertTrue(e.pendingDialogs.value.isEmpty())
    }

    @Test
    fun realtimeAndDisplayCalls() = runBlocking {
        val bridge = FakeBridge()
        val e = engine(bridge)
        assertEquals(TransportSample.IDLE, e.readTransport())
        bridge.transport = DoubleArray(16).also { it[0] = 1.0; it[2] = 4.5 }
        assertTrue(e.readTransport().isPlaying)
        assertEquals(4.5, e.readTransport().displayTime, 0.0)

        bridge.waveResult = 77L or DisplayStatus.PARTIAL_BIT
        val tile = e.waveColumns(3, 0, 51, 256, 4)!!
        assertTrue(bridge.displayThread!!.startsWith("audacity-display"))
        assertEquals(77L, tile.waveVersion)
        assertTrue(tile.partial)
        assertEquals(12, tile.data.size)
        assertEquals(11f, tile.data[11], 0f)
        bridge.waveResult = DisplayStatus.SAMPLE_MODE
        assertNull(e.waveColumns(3, 0, 51, 256, 4))
        val out = FloatArray(12)
        assertEquals(DisplayStatus.SAMPLE_MODE, e.waveColumnsInto(3, 0, 51, 256, 4, out))
        assertEquals(1f, e.envelopeColumns(3, 51, 0, 4)!![3], 0f)
        val runs = e.waveSamples(3, 0, 2.0, 2.1)!!
        assertEquals(1, runs.single().clipIndex)
        assertEquals(2.0, runs.single().firstSampleTime, 0.0)
        assertNull(e.spectrogramColumns(3, 0, 51, 0, 4, 8))
        assertFalse(e.isFake)
    }

    @Test
    fun missingJniEntryPointsDoNotCrash() = runBlocking {
        val bridge = FakeBridge().apply { missingJni = true; transport = DoubleArray(16) }
        val e = engine(bridge)
        try {
            e.undo()
            fail("expected INTERNAL")
        } catch (ex: EngineException) {
            assertEquals(ErrorCodes.INTERNAL, ex.code)
        }
        assertEquals(TransportSample.IDLE, e.readTransport())
        assertNull(e.waveColumns(1, 0, 0, 0, 4))
        assertEquals(DisplayStatus.NOT_READY, e.waveColumnsInto(1, 0, 0, 0, 4, FloatArray(12)))
    }
}
