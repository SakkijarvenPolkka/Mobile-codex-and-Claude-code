/*
 * Host contract test of the typed facade: NativeAudacityEngine → JNI →
 * bridge for the commands of the revised API.md (project rename/compact,
 * select.set with tracks, sort/align/mute-all, label references and files,
 * device injection, settings, multi-choice replies) and the packaged
 * gettext catalog layout (filesDir/audacity/locale/<lang>/LC_MESSAGES).
 *
 * Skipped unless both properties are given; run it alone (the engine
 * derives its directories once per process, so it cannot share a JVM with
 * NativeHostIntegrationTest):
 *
 *   ./gradlew --no-daemon -Paudacity.buildNative=false \
 *       -Paudacity.hostJniLibrary=$PWD/native/build-host/lib/libaudacity-jni.so \
 *       -Paudacity.hostContractTest=true \
 *       :engine:testDebugUnitTest --tests '*NativeContractHostTest*'
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.StartConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class NativeContractHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val libraryPath: String? = System.getProperty(NativeBridge.LIBRARY_PATH_PROPERTY)?.takeIf { it.isNotBlank() }

    @Test(timeout = 600_000)
    fun revisedContractThroughTheTypedFacade() {
        assumeTrue("needs -Paudacity.hostJniLibrary and -Paudacity.hostContractTest=true",
            libraryPath != null && File(libraryPath).isFile && System.getProperty(PROPERTY) == "true")
        assertTrue("libaudacity-jni failed to load: ${NativeBridge.loadError}", NativeBridge.isLoaded)

        val files = tmp.newFolder("files")
        // The layout AssetInstaller extracts (API.md §2.1)
        val catalog = File(System.getProperty("user.dir"), "../native/audacity/locale/ko/LC_MESSAGES/audacity.mo")
        assertTrue("missing $catalog", catalog.isFile)
        catalog.copyTo(File(files, "audacity/locale/ko/LC_MESSAGES/audacity.mo"))
        val config = StartConfig(
            filesDir = files.path, noBackupDir = tmp.newFolder("no_backup").path, cacheDir = tmp.newFolder("cache").path,
            nyquistDir = File(files, "audacity/nyquist").path, pluginsDir = File(files, "audacity/plug-ins").path,
            locale = "en_US", deviceModel = "jvm-contract-test", audioOutputSampleRate = 48000, audioFramesPerBuffer = 192,
            recordPermission = false,
        )
        val engine = NativeAudacityEngine(NativeBridgeApi) { config }
        try {
            runBlocking {
                engine.start()
                val status = withTimeout(120_000) { engine.status.first { it is EngineStatus.Ready || it is EngineStatus.Failed } }
                assertTrue("engine did not start: $status", status is EngineStatus.Ready)
                drive(engine, files, catalog.isFile)
            }
        } finally {
            NativeBridge.stop()
        }
    }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: EngineException) {
            assertEquals(e.message, code, e.code)
        }
    }

    private suspend fun drive(engine: NativeAudacityEngine, files: File, haveCatalog: Boolean) {
        // ----- app.info languages / settings of §5.1 ----------------------------
        val info = engine.appInfo()
        assertEquals("en", info.language)
        if (haveCatalog) assertTrue("languages ${info.languages}", "ko" in info.languages)
        val settings = engine.getSettings()
        assertEquals("system", settings.language)
        assertEquals(false, settings.syncLock)
        assertTrue(settings.dropoutDetection != null && settings.preferNewTrackRecord != null &&
            settings.pasteAsNewClips != null && settings.moveSelectionWithTracks != null)
        if (haveCatalog) {
            engine.setSettings(Settings(language = "ko"))
            assertEquals("ko", engine.appInfo().language)
            engine.setSettings(Settings(language = "system"))
            assertEquals("en", engine.appInfo().language)
        }
        expectError(ErrorCodes.INVALID_ARGS) { engine.setSettings(Settings(language = "xx", syncLock = true)) }
        assertEquals(false, engine.getSettings().syncLock)

        // ----- two test tones -------------------------------------------------------
        engine.newProject()
        suspend fun tone(seconds: Double): Long =
            engine.invokeCommand("debug.makeTestTrack", buildJsonObject { put("seconds", seconds) }).jsonObject["id"]!!.jsonPrimitive.long
        val t1 = tone(1.0)
        val t2 = tone(2.0)
        engine.renameTrack(t1, "b")
        engine.renameTrack(t2, "A")
        engine.sortTracks("name")
        assertEquals(listOf(t2, t1), engine.snapshot.value.tracks.map { it.id })
        assertEquals("Sort by Name", engine.snapshot.value.history.undo)

        // ----- select.set with a track part: one command, no generation bump ---------
        val gen = engine.snapshot.value.generation
        engine.select(0.4, 0.2, trackIds = listOf(t1), focus = t1)
        var s = engine.snapshot.value
        assertEquals(gen, s.generation)
        assertEquals(0.2, s.selection.t0, 1e-9)
        assertEquals(listOf(t1), s.selectedTracks.map { it.id })
        assertTrue(s.track(t1)!!.focused)
        expectError(ErrorCodes.NOT_FOUND) { engine.select(0.0, 1.0, trackIds = listOf(987654L)) }
        engine.selectCommand("select.zeroCrossing")
        s = engine.snapshot.value
        assertTrue("zero crossing moved ${s.selection}", abs(s.selection.t0 - 0.2) < 0.011 && abs(s.selection.t1 - 0.4) < 0.011)

        // ----- align end to end, mute all (undo state updated in place) --------------
        engine.selectAll()
        engine.alignTracks("endToEnd")
        s = engine.snapshot.value
        assertEquals(s.tracks[0].end, s.tracks[1].start, 1e-6)
        assertEquals("Align End to End", s.history.undo)
        val states = engine.history().states.size
        engine.muteAllTracks(true)
        assertTrue(engine.snapshot.value.tracks.all { it.mute })
        engine.muteAllTracks(false)
        assertFalse(engine.snapshot.value.tracks.any { it.mute })
        assertEquals(states, engine.history().states.size)

        // ----- slider drag: no generation bump until the final value -----------------
        val g0 = engine.snapshot.value.generation
        engine.setTrackGain(t1, 0.5, final = false)
        assertEquals(g0, engine.snapshot.value.generation)
        engine.setTrackGain(t1, 0.5, final = true)
        assertEquals("Volume", engine.snapshot.value.history.undo)

        // ----- labels: references with generations, files ---------------------------
        engine.select(0.5, 0.6)
        val (labelTrack, index) = engine.addLabel("one")
        engine.select(0.1, 0.2)
        engine.addLabel("zero")
        val labelGen = engine.snapshot.value.generation
        assertEquals(listOf("zero", "one"), engine.snapshot.value.track(labelTrack)!!.labels.map { it.title })
        // "one" is at index 1 now; moving it to 0.05 sorts it first
        assertEquals(0, engine.editLabel(labelTrack, index + 1, t0 = 0.05, t1 = 0.06, generation = labelGen))
        expectError(ErrorCodes.STALE) { engine.removeLabel(labelTrack, 0, generation = labelGen) }
        val srt = File(tmp.newFolder("labels"), "chapters.srt")
        assertEquals(2, engine.exportLabels(srt.path, "subrip"))
        assertTrue(srt.readText().contains("-->"))
        val imported = engine.importLabels(srt.path)
        assertEquals("chapters", engine.snapshot.value.track(imported)!!.name)
        assertEquals(2, engine.snapshot.value.track(imported)!!.labels.size)
        engine.removeLabel(imported, 0, generation = engine.snapshot.value.generation)
        assertEquals(1, engine.snapshot.value.track(imported)!!.labels.size)

        // ----- multi-choice answered through the facade --------------------------------
        coroutineScope {
            val answer = async(Dispatchers.Default) {
                engine.invokeCommand("debug.ask", buildJsonObject {
                    put("multiChoice", true)
                    put("message", "Contract streams?")
                    put("choices", buildJsonArray { listOf("x", "y").forEach { add(JsonPrimitive(it)) } })
                }).jsonObject
            }
            val dialog = withTimeout(10_000) { engine.pendingDialogs.first { l -> l.any { it.message == "Contract streams?" } } }
                .single { it.message == "Contract streams?" }
            assertTrue(dialog.isMultiChoice)
            assertTrue("${dialog.defaultChecked}", dialog.defaultChecked.isEmpty() || dialog.defaultChecked == listOf(false, false))
            engine.replyDialogChoices(dialog.id, listOf(1))
            assertEquals(listOf(1), withTimeout(10_000) { answer.await() }["choices"]!!.jsonArray.map { it.jsonPrimitive.int })
        }

        // ----- project files: save, rename, compact ----------------------------------
        val info0 = engine.compactInfo()
        assertTrue("$info0", info0.totalBytes >= info0.usedBytes && info0.usedBytes >= 0)
        assertTrue(engine.compactProject() >= 0)
        val h = engine.history()
        assertEquals("Compacted project file", h.states[h.current].description)
        val projects = File(files, "Projects").apply { mkdirs() }
        val saved = engine.saveProjectAs(File(projects, "Contract A.aup3").path)
        engine.newProject()
        val renamed = engine.renameProject(saved, "Contract B")
        assertTrue(renamed, renamed.endsWith("/Contract B.aup3"))
        assertTrue(engine.listProjects().any { it.path == renamed })
        expectError(ErrorCodes.INVALID_ARGS) { engine.renameProject(renamed, "bad/name") }
        expectError(ErrorCodes.NOT_FOUND) { engine.renameProject(saved, "Contract C") }

        // ----- device-list injection, latency fields ----------------------------------
        val applied = engine.setAudioDevices(listOf(
            AudioDeviceSpec(31, "Contract DAC", 22, isSource = false, isSink = true, channelCounts = listOf(2), sampleRates = listOf(48000)),
            AudioDeviceSpec(32, "Contract DAC", 22, isSource = true, isSink = false, channelCounts = listOf(1), sampleRates = listOf(48000)),
            AudioDeviceSpec(1, "earpiece", 1, isSource = false, isSink = true),
        ))
        assertTrue(applied)
        val devices = engine.audioDevices()
        assertTrue("outputs ${devices.outputs.map { it.name }}", devices.outputs.any { it.name == "USB headset: Contract DAC" })
        assertTrue("inputs ${devices.inputs.map { it.name }}", devices.inputs.any { it.name.startsWith("USB headset: Contract DAC") })
        assertTrue(devices.outputs.none { it.name.contains("earpiece") })
        assertFalse(devices.pending)
        val latency = engine.latency()
        assertEquals(engine.getSettings().latencyCorrectionMs ?: 0.0, latency.userTrimMs, 1e-9)

        // ----- spectrum: cepstrum (effects module) ----------------------------------------
        tone(1.0)
        engine.selectAll()
        engine.select(0.0, 0.5)
        try {
            val cep = engine.plotSpectrum("cepstrum", "hann", 1024)
            assertEquals("cepstrum", cep.algorithm)
            assertEquals(1024, cep.size)
            assertEquals(512, cep.values.size)
            assertTrue(cep.binSeconds != null)
        } catch (e: EngineException) {
            if (e.code != ErrorCodes.UNKNOWN_COMMAND) throw e
            println("NativeContractHostTest: analyze.spectrum not registered (effects module missing)")
        }
        assertTrue(engine.snapshot.value.has(CommandFlags.NSL))
    }

    companion object {
        const val PROPERTY = "audacity.hostContractTest"
    }
}
