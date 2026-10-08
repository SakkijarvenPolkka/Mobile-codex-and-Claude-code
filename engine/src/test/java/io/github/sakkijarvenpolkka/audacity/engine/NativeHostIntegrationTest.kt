/*
 * Host end-to-end test: Kotlin → JNI (libaudacity-jni) → bridge → Audacity.
 *
 * Loads the host build of libaudacity-jni.so into the unit-test JVM and
 * drives the REAL NativeAudacityEngine. Skipped unless the library path is
 * given (engine/build.gradle.kts passes -Paudacity.hostJniLibrary as the
 * system property NativeBridge.LIBRARY_PATH_PROPERTY):
 *
 *   flock /tmp/claude-0/locks/build-host.lock ninja -C native/build-host audacity-jni
 *   ./gradlew --no-daemon -Paudacity.buildNative=false \
 *       -Paudacity.hostJniLibrary=$PWD/native/build-host/lib/libaudacity-jni.so \
 *       :engine:testDebugUnitTest --tests '*NativeHostIntegrationTest*'
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.StartConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class NativeHostIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val libraryPath: String? =
        System.getProperty(NativeBridge.LIBRARY_PATH_PROPERTY)?.takeIf { it.isNotBlank() }

    @Test(timeout = 600_000)
    fun realEngineEndToEnd() {
        assumeTrue(
            "host libaudacity-jni.so not given (-Paudacity.hostJniLibrary=<path>)",
            libraryPath != null && File(libraryPath).isFile,
        )
        assertTrue("libaudacity-jni failed to load: ${NativeBridge.loadError}", NativeBridge.isLoaded)

        val files = tmp.newFolder("files")
        val config = StartConfig(
            filesDir = files.path,
            noBackupDir = tmp.newFolder("no_backup").path,
            cacheDir = tmp.newFolder("cache").path,
            // Not extracted on the host: the nyquistRuntime self-check fails,
            // which the engine tolerates (API.md §4.1)
            nyquistDir = File(files, "audacity/nyquist").path,
            pluginsDir = File(files, "audacity/plug-ins").path,
            locale = "en_US",
            deviceModel = "jvm-host-test",
            audioOutputSampleRate = 48000,
            audioFramesPerBuffer = 192,
            recordPermission = false,
        )
        val engine = NativeAudacityEngine(NativeBridgeApi) { config }
        try {
            runBlocking { drive(engine) }
        } finally {
            // Join the engine thread before the JVM exits
            NativeBridge.stop()
        }
        // After stop every command answers NOT_READY
        val envelope = NativeBridge.invoke("project.info".encodeToByteArray(), "{}".encodeToByteArray()).decodeToString()
        assertTrue(envelope, envelope.contains("\"NOT_READY\""))
    }

    private suspend fun drive(engine: NativeAudacityEngine) {
        // ----- start ------------------------------------------------------
        engine.start()
        val status = withTimeout(120_000) {
            engine.status.first { it is EngineStatus.Ready || it is EngineStatus.Failed }
        }
        assertTrue("engine did not start: $status", status is EngineStatus.Ready)
        assertEquals("3.7.9", engine.readyInfo.value?.audacityVersion)
        val info = engine.invokeCommand("app.info").jsonObject
        assertEquals("3.7.9", info["audacityVersion"]?.jsonPrimitive?.content)

        // Every snapshot the flow publishes, in order
        val generations = Collections.synchronizedList(mutableListOf<Long>())
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collector.launch { engine.snapshot.collect { s: Snapshot -> generations += s.generation } }
        try {
            commands(engine, generations)
        } finally {
            collector.cancel()
        }
    }

    private suspend fun commands(engine: NativeAudacityEngine, generations: List<Long>) {
        // ----- project + test track -----------------------------------------
        engine.newProject()
        val s0 = engine.snapshot.value
        assertTrue(s0.project.open)
        assertTrue(s0.tracks.isEmpty())

        val made = engine.invokeCommand("debug.makeTestTrack", buildJsonObject {
            put("seconds", 2.0)
            put("frequency", 440.0)
            put("channels", 2)
            put("amplitude", 0.5)
        }).jsonObject
        val id = made["id"]!!.jsonPrimitive.long
        // The command's snapshot is applied when the call returns
        val s1 = engine.snapshot.value
        assertTrue("generation ${s1.generation} > ${s0.generation}", s1.generation > s0.generation)
        val track = s1.tracks.single { it.id == id }
        assertEquals("wave", track.kind)
        assertEquals(2, track.channels)
        assertEquals(2.0, track.end - track.start, 1e-6)
        assertTrue(s1.history.canUndo)
        val toneName = track.name

        // ----- UTF-8 through invoke and the snapshot event --------------------
        val utf8Name = "테스트 🎵 café"   // 테스트 🎵 café
        engine.renameTrack(id, utf8Name)
        assertEquals(utf8Name, engine.snapshot.value.tracks.single { it.id == id }.name)

        // ----- undo / redo (track ids are stable) ---------------------------------
        engine.undo()
        assertEquals(toneName, engine.snapshot.value.tracks.single { it.id == id }.name)
        engine.undo()
        val undone = engine.snapshot.value
        assertTrue(undone.tracks.none { it.id == id })
        assertTrue(undone.history.canRedo)
        engine.redo()
        engine.redo()
        val redone = engine.snapshot.value
        assertEquals(utf8Name, redone.tracks.single { it.id == id }.name)
        assertFalse(redone.history.canRedo)

        // The flow published increasing generations along the way
        withTimeout(5_000) { while (generations.lastOrNull() != redone.generation) delay(10) }
        val seen = synchronized(generations) { generations.toList() }
        assertTrue("snapshots seen: $seen", seen.size >= 2 && seen.last() == redone.generation)
        assertTrue("generations never decrease: $seen", seen.zipWithNext().all { (a, b) -> b >= a })

        // ----- display data (display module; -3 = busy/not available) -----------
        // Level 80 = 1024 px/s: column mode for a 44.1/48 kHz clip
        var tile: WaveTile? = null
        for (attempt in 0 until 20) {
            tile = engine.waveColumns(id, 0, 80, 0L, 256)
            if (tile != null) break
            val raw = engine.waveColumnsInto(id, 0, 80, 0L, 256, FloatArray(3 * 256))
            assertEquals("waveColumns status", DisplayStatus.NOT_READY, raw)
            delay(100)
        }
        if (tile != null) {
            assertEquals(256, tile.count)
            assertEquals(redone.tracks.single { it.id == id }.waveVersion, tile.waveVersion)
            val min0 = tile.data[0]
            val max0 = tile.data[256]
            assertTrue("column 0 min $min0 max $max0", min0 <= max0 && max0 > 0.1f && max0 <= 0.51f && min0 >= -0.51f)
            val envelope = engine.envelopeColumns(id, 80, 0L, 256)
            assertNotNull("envelopeColumns", envelope)
            assertEquals(1f, envelope!![0], 1e-6f)
            val runs = engine.waveSamples(id, 0, 0.0, 0.01)
            assertNotNull(runs)
            assertEquals(1, runs!!.size)
            assertTrue(runs[0].values.isNotEmpty())
            val spectrum = engine.spectrogramColumns(id, 0, 80, 0L, 16, 64)
            assertNotNull("spectrogramColumns", spectrum)
        } else {
            println("NativeHostIntegrationTest: waveColumns kept answering NOT_READY (display module missing?)")
        }
        assertEquals(DisplayStatus.NO_TRACK, engine.waveColumnsInto(id + 1000, 0, 80, 0L, 256, FloatArray(3 * 256)))

        // ----- a blocking question answered with replyDialog ----------------------
        coroutineScope {
            val answer = async(Dispatchers.Default) {
                engine.invokeCommand("debug.ask", buildJsonObject {
                    put("message", "Proceed?")
                    put("title", "JNI test")
                }).jsonObject
            }
            val dialog = withTimeout(10_000) {
                engine.pendingDialogs.first { list -> list.any { it.message == "Proceed?" } }
            }.single { it.message == "Proceed?" }
            assertTrue(dialog.blocking)
            engine.replyDialog(dialog.id, 0)
            assertEquals("yes", withTimeout(10_000) { answer.await() }["result"]!!.jsonPrimitive.content)
            assertTrue(engine.pendingDialogs.value.none { it.id == dialog.id })
        }

        // ----- multi-choice answered with replyDialogChoices -------------------
        coroutineScope {
            val answer = async(Dispatchers.Default) {
                engine.invokeCommand("debug.ask", buildJsonObject {
                    put("multiChoice", true)
                    put("message", "Which streams?")
                    put("choices", buildJsonArray { listOf("a", "b", "c").forEach { add(JsonPrimitive(it)) } })
                    put("defaultChecked", buildJsonArray { listOf(true, false, true).forEach { add(JsonPrimitive(it)) } })
                }).jsonObject
            }
            val dialog = withTimeout(10_000) {
                engine.pendingDialogs.first { list -> list.any { it.message == "Which streams?" } }
            }.single { it.message == "Which streams?" }
            assertEquals("multiChoice", dialog.kind)
            assertEquals(listOf("a", "b", "c"), dialog.choices)
            // Through the seam (the facade method is the kotlin-sync agent's)
            NativeBridgeApi.replyDialogChoices(dialog.id, intArrayOf(2, 0, 2))
            val chosen = withTimeout(10_000) { answer.await() }["choices"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(listOf(0, 2), chosen)
        }

        // ----- progress cancel ------------------------------------------------------
        coroutineScope {
            val started = System.nanoTime()
            val run = async(Dispatchers.Default) {
                runCatching { engine.invokeCommand("debug.progress", buildJsonObject { put("seconds", 30.0) }) }
            }
            val progress = withTimeout(10_000) { engine.progress.first { it.isNotEmpty() } }.values.first()
            assertTrue(progress.cancellable)
            engine.cancelProgress(progress.id, false)
            val result = withTimeout(15_000) { run.await() }
            val error = result.exceptionOrNull()
            assertTrue("expected CANCELLED, got $result", error is EngineException && error.code == ErrorCodes.CANCELLED)
            assertTrue((System.nanoTime() - started) / 1e9 < 15.0)
            withTimeout(5_000) { engine.progress.first { it.isEmpty() } }
        }
        // ...and stop (keeps the partial result: {stopped:true})
        coroutineScope {
            val run = async(Dispatchers.Default) {
                engine.invokeCommand("debug.progress", buildJsonObject { put("seconds", 30.0) }).jsonObject
            }
            val progress = withTimeout(10_000) { engine.progress.first { it.isNotEmpty() } }.values.first()
            engine.cancelProgress(progress.id, true)
            assertEquals(true, withTimeout(15_000) { run.await() }["stopped"]!!.jsonPrimitive.content.toBoolean())
        }

        // ----- lock-free reads --------------------------------------------------------
        val transport = engine.readTransport()
        assertEquals(TransportSample.STATE_STOPPED, transport.state)
        engine.readMeters()   // null without the audio module; must not throw

        // ----- errors come back as EngineException -------------------------------
        try {
            engine.invokeCommand("no.such.command", JsonObject(emptyMap()))
            fail("expected UNKNOWN_COMMAND")
        } catch (e: EngineException) {
            assertEquals(ErrorCodes.UNKNOWN_COMMAND, e.code)
        }
        // Still alive after all of that
        engine.refreshSnapshot()
        assertTrue(engine.snapshot.value.tracks.any { it.id == id })
    }
}
