/*
 * Commands added by the revised contract (API.md §3.3) in the fake engine.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class FakeContractCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var nowNs = 1_000_000_000L
    private val engines = mutableListOf<FakeAudacityEngine>()

    private fun engine(demo: Boolean = true) =
        FakeAudacityEngine(FakeConfig(demoProject = demo, clock = { nowNs }, autoTick = false,
            filesDir = tmp.root.absolutePath + "/files", cacheDir = tmp.root.absolutePath + "/cache"))
            .also { engines += it }

    @After
    fun tearDown() = engines.forEach { it.dispose() }

    private val FakeAudacityEngine.s: Snapshot get() = snapshot.value
    private fun Snapshot.named(name: String) = tracks.first { it.name == name }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: EngineException) {
            assertEquals(e.message, code, e.code)
        }
    }

    @Test
    fun projectRenameCompactAndCompactInfo() = runTest {
        val e = engine()
        val projects = e.listProjects()
        val demo = projects.first { it.name == "Demo song" }
        val newPath = e.renameProject(demo.path, "  Renamed.aup3 ")
        assertTrue(newPath.endsWith("/Renamed.aup3"))
        assertEquals(setOf("Renamed", "Interview"), e.listProjects().map { it.name }.toSet())
        assertEquals(newPath, e.renameProject(newPath, "Renamed"))   // same name: no-op
        expectError(ErrorCodes.FAILED) { e.renameProject(newPath, "Interview") }
        for (bad in listOf("", "a/b", ".hidden", "backup~", "x\u0001")) expectError(ErrorCodes.INVALID_ARGS) { e.renameProject(newPath, bad) }
        expectError(ErrorCodes.NOT_FOUND) { e.renameProject(demo.path, "Other") }

        // The open project cannot be renamed
        e.openProject(newPath)
        expectError(ErrorCodes.FAILED) { e.renameProject(newPath, "Again") }

        // Edits leave undo states; compaction keeps the current (and the saved) one
        e.selectAll()
        e.edit("edit.silence")
        e.edit("edit.copy")
        e.edit("edit.cut")
        assertFalse(e.s.clipboard.empty)
        assertEquals(3, e.history().states.size)
        val info = e.compactInfo()
        assertTrue(info.totalBytes >= info.usedBytes)
        assertTrue(info.fileBytes >= info.totalBytes)
        val dirty = e.s.project.dirty
        val gen = e.s.generation
        val freed = e.compactProject()
        assertEquals(info.totalBytes - info.usedBytes, freed)
        val h = e.history()
        assertEquals(2, h.states.size)                 // saved state (opened) + current
        assertEquals("Compacted project file", h.states[h.current].description)
        assertTrue(e.s.clipboard.empty)
        assertEquals(dirty, e.s.project.dirty)
        assertTrue(e.s.generation > gen)
        assertEquals(e.compactInfo().totalBytes, e.compactInfo().usedBytes)
    }

    @Test
    fun selectSetWithTracksAndFocusIsOneCommand() = runTest {
        val e = engine()
        val a2 = e.s.named("Audio 2").id
        val gen = e.s.generation
        e.select(3.0, 1.0, trackIds = listOf(a2), focus = a2)
        assertEquals(1.0, e.s.selection.t0, 0.0)
        assertEquals(3.0, e.s.selection.t1, 0.0)
        assertEquals(listOf(a2), e.s.selectedTracks.map { it.id })
        assertTrue(e.s.tracks.first { it.id == a2 }.focused)
        assertEquals(gen, e.s.generation)               // S: no generation bump
        // Unknown ids change nothing
        expectError(ErrorCodes.NOT_FOUND) { e.select(5.0, 6.0, trackIds = listOf(999L)) }
        assertEquals(1.0, e.s.selection.t0, 0.0)
        e.select(4.0, 4.0)                              // no track part: tracks unchanged
        assertEquals(listOf(a2), e.s.selectedTracks.map { it.id })
    }

    @Test
    fun selectionCommandsOfTheRevisedContract() = runTest {
        val e = engine()
        val a1 = e.s.named("Audio 1").id
        // Audio 1: clips [0, 8) and [10, 14)
        e.select(2.0, 2.0, trackIds = listOf(a1))
        e.selectCommand("select.nextClip")
        assertEquals(10.0, e.s.selection.t0, 1e-9)
        assertEquals(14.0, e.s.selection.t1, 1e-9)
        e.selectCommand("select.prevClip")
        assertEquals(0.0, e.s.selection.t0, 1e-9)
        assertEquals(8.0, e.s.selection.t1, 1e-9)
        e.select(3.0, 5.0)
        e.selectCommand("select.toProjectStart")
        assertEquals(0.0, e.s.selection.t0, 0.0)
        assertEquals(5.0, e.s.selection.t1, 0.0)
        e.selectCommand("select.toProjectEnd")
        assertEquals(e.s.projectEnd, e.s.selection.t1, 1e-9)

        // Zero crossing: moves the edges by at most ±5 ms
        e.select(1.2345, 3.4567)
        e.selectCommand("select.zeroCrossing")
        assertTrue(abs(e.s.selection.t0 - 1.2345) <= 0.0051)
        assertTrue(abs(e.s.selection.t1 - 3.4567) <= 0.0051)
        // EditableTracksSelected
        e.selectNone()
        expectError(ErrorCodes.NO_SELECTION) { e.selectCommand("select.zeroCrossing") }
        expectError(ErrorCodes.NO_SELECTION) { e.selectCommand("select.cursorToTrackStart") }
        e.setSettings(Settings(selectAllOnNone = true))
        e.selectCommand("select.cursorToTrackEnd")
        assertTrue(e.s.selectedTracks.isNotEmpty())
    }

    @Test
    fun muteSoloAreUndoStateUpdatesAndMuteAll() = runTest {
        val e = engine()
        val a1 = e.s.named("Audio 1").id
        val a2 = e.s.named("Audio 2").id
        val labels = e.s.named("Label 1").id
        e.renameTrack(labels, "Markers")
        val states = e.history().states.size
        // Simple solo: muting one of two tracks lights the other's solo indicator
        e.setTrackMute(a1, true)
        assertTrue(e.s.track(a2)!!.solo)
        val gen = e.s.generation
        e.setTrackMute(a1, true)                        // unchanged: nothing happens
        assertEquals(gen, e.s.generation)
        e.setTrackMute(a1, false)
        assertFalse(e.s.track(a2)!!.solo)
        e.setTrackSolo(a1, false)                       // unchanged
        assertEquals(gen + 1, e.s.generation)
        expectError(ErrorCodes.NOT_FOUND) { e.setTrackMute(labels, true) }
        expectError(ErrorCodes.NOT_FOUND) { e.setTrackSolo(labels, true) }

        e.setTrackSolo(a2, true)
        assertTrue(e.s.track(a1)!!.mute)
        e.muteAllTracks(false)
        assertFalse(e.s.tracks.any { it.mute || it.solo })
        e.muteAllTracks(true)
        assertTrue(e.s.tracks.filter { it.isWave }.all { it.mute })
        assertEquals(states, e.history().states.size)   // U: no history entries
        // The current undo state was updated in place: undo leaves it, redo restores the mutes
        e.undo()
        assertFalse(e.s.tracks.any { it.mute })
        assertEquals("Label 1", e.s.track(labels)!!.name)
        e.redo()
        assertTrue(e.s.tracks.filter { it.isWave }.all { it.mute })
    }

    @Test
    fun gainDragHasNoGenerationBumpAndFinalConsolidates() = runTest {
        val e = engine()
        val a1 = e.s.named("Audio 1").id
        val states = e.history().states.size
        val gen = e.s.generation
        e.setTrackGain(a1, 0.5, final = false)
        e.setTrackPan(a1, -0.5, final = false)
        assertEquals(gen, e.s.generation)               // clip references stay valid
        assertEquals(0.5, e.s.track(a1)!!.gain, 0.0)
        assertEquals(states, e.history().states.size)
        e.setTrackGain(a1, 0.6, final = true)
        e.setTrackGain(a1, 0.7, final = true)
        assertEquals(states + 1, e.history().states.size)
        assertEquals("Volume", e.s.history.undo)
        expectError(ErrorCodes.INVALID_ARGS) { e.setTrackGain(a1, 63.2, final = true) }
        expectError(ErrorCodes.NOT_FOUND) { e.setTrackGain(e.s.named("Label 1").id, 1.0, final = true) }
    }

    @Test
    fun sortAndAlignTracks() = runTest {
        val e = engine()
        e.renameTrack(e.s.named("Audio 1").id, "beta")
        e.renameTrack(e.s.named("Audio 2").id, "Alpha")
        e.sortTracks("name")
        assertEquals(listOf("Alpha", "beta", "Label 1"), e.s.tracks.map { it.name })
        assertEquals("Sort by Name", e.s.history.undo)
        expectError(ErrorCodes.INVALID_ARGS) { e.sortTracks("size") }

        // Align end to end: the selected audio tracks one after the other
        val alpha = e.s.named("Alpha").id
        val beta = e.s.named("beta").id
        e.selectTracks(listOf(alpha, beta))
        e.select(1.0, 2.0)
        e.alignTracks("endToEnd")
        val a = e.s.track(alpha)!!
        val b = e.s.track(beta)!!
        assertEquals(0.0, a.start, 1e-9)
        assertEquals(a.end, b.start, 1e-9)
        assertEquals("Align End to End", e.s.history.undo)
        assertEquals(1.0, e.s.selection.t0, 0.0)         // never moves the selection

        // Fixed-distance align moves the group and, if asked, the selection
        e.alignTracks("startToSelEnd", moveSelection = true)
        assertEquals(2.0, e.s.track(alpha)!!.start, 1e-9)
        assertEquals(3.0, e.s.selection.t0, 1e-9)
        assertEquals(4.0, e.s.selection.t1, 1e-9)
        assertEquals("Align/Move Start", e.s.history.undo)
        e.setSettings(Settings(moveSelectionWithTracks = false))
        e.alignTracks("startToZero")
        assertEquals(0.0, e.s.track(alpha)!!.start, 1e-9)
        assertEquals(3.0, e.s.selection.t0, 1e-9)

        e.selectTracks(listOf(e.s.named("Label 1").id))
        expectError(ErrorCodes.NO_SELECTION) { e.alignTracks("together") }
    }

    @Test
    fun labelReferencesWithGenerationAndSorting() = runTest {
        val e = engine()
        val lt = e.s.named("Label 1").id                 // Intro 0-2, Break 8, Voice 10-14
        val gen = e.s.generation
        val index = e.editLabel(lt, 0, t0 = 9.0, t1 = 9.5, generation = gen)
        assertEquals(1, index)
        assertEquals(listOf("Break", "Intro", "Voice"), e.s.track(lt)!!.labels.map { it.title })
        assertEquals("Label Edit", e.s.history.undo)
        expectError(ErrorCodes.STALE) { e.editLabel(lt, 0, title = "x", generation = gen) }
        expectError(ErrorCodes.STALE) { e.removeLabel(lt, 0, generation = gen) }
        val states = e.history().states.size
        assertEquals(2, e.editLabel(lt, 2, title = "Voice"))   // nothing changes: no entry
        assertEquals(states, e.history().states.size)
        e.removeLabel(lt, 0, generation = e.s.generation)
        assertEquals(listOf("Intro", "Voice"), e.s.track(lt)!!.labels.map { it.title })
        expectError(ErrorCodes.NOT_FOUND) { e.removeLabel(e.s.named("Audio 1").id, 0) }
    }

    @Test
    fun labelImportAndExport() = runTest {
        val e = engine()
        val dir = tmp.newFolder("labels")
        val txt = File(dir, "out.txt")
        assertEquals(3, e.exportLabels(txt.path, "text"))
        assertEquals("0\t2\tIntro\n8\t8\tBreak\n10\t14\tVoice\n", txt.readText())
        val srt = File(dir, "out.srt")
        e.exportLabels(srt.path, "subrip")
        assertTrue(srt.readText().startsWith("1\n00:00:00,000 --> 00:00:02,000\nIntro\n\n2\n"))
        val vtt = File(dir, "out.vtt")
        e.exportLabels(vtt.path, "webvtt")
        assertTrue(vtt.readText().contains("00:00:10.000 --> 00:00:14.000"))
        val chapters = File(dir, "out.json")
        e.exportLabels(chapters.path, "podcastChapters")
        assertTrue(chapters.readText().contains("{\"startTime\": 10, \"title\": \"Voice\"}"))
        expectError(ErrorCodes.INVALID_ARGS) { e.exportLabels(txt.path, "csv") }

        // Import: a new label track named after the file, the only selected track
        val id = e.importLabels(srt.path)
        val t = e.s.track(id)!!
        assertEquals("out", t.name)
        assertEquals(listOf("Intro", "Break", "Voice"), t.labels.map { it.title })
        assertEquals(listOf(id), e.s.selectedTracks.map { it.id })
        assertEquals("Import Labels", e.s.history.undo)
        val messy = File(dir, "messy.txt").apply { writeText("1.5\t2.5\tok\nnot a label\n\\\t100\t200\n3\tnoend\n") }
        val id2 = e.importLabels(messy.path)
        assertEquals(listOf("ok", "noend"), e.s.track(id2)!!.labels.map { it.title })
        assertTrue(e.pendingDialogs.value.any { !it.blocking })     // unreadable lines reported
        expectError(ErrorCodes.UNSUPPORTED) { e.importLabels(vtt.path) }
        expectError(ErrorCodes.NOT_FOUND) { e.importLabels(File(dir, "missing.txt").path) }

        val empty = engine(demo = false)
        expectError(ErrorCodes.FAILED) { empty.exportLabels(File(dir, "none.txt").path, "text") }
    }

    @Test
    fun multiChoiceDialogsAndStreamSelection() = runTest {
        val e = engine()
        val ask = async {
            e.invokeCommand("debug.ask", EngineProtocol.args("multiChoice" to true, "choices" to listOf("a", "b", "c"),
                "defaultChecked" to listOf(true, false, true)))
        }
        runCurrent()
        val d = e.pendingDialogs.value.single()
        assertEquals(DialogEvent.KIND_MULTI_CHOICE, d.kind)
        assertEquals(listOf(0, 2), d.defaultIndices)
        e.replyDialogChoices(d.id, listOf(2, 1, 2, 7))
        val r = ask.await() as JsonObject
        assertEquals(listOf(1, 2), (r["choices"] as JsonArray).map { it.jsonPrimitive.content.toInt() })
        assertTrue(e.pendingDialogs.value.isEmpty())

        // A button reply accepts the defaults, -1 cancels
        val ask2 = async { e.invokeCommand("debug.ask", EngineProtocol.args("multiChoice" to true, "choices" to listOf("x", "y"),
            "defaultChecked" to listOf(false, true))) }
        runCurrent()
        e.replyDialog(e.pendingDialogs.value.single().id, 0)
        assertEquals("[1]", (ask2.await() as JsonObject)["choices"].toString())
        val ask3 = async { e.invokeCommand("debug.ask", EngineProtocol.args("multiChoice" to true, "choices" to listOf("x"))) }
        runCurrent()
        e.replyDialog(e.pendingDialogs.value.single().id, -1)
        assertEquals("cancel", (ask3.await() as JsonObject)["result"]!!.jsonPrimitive.content)

        // Import of a multi-stream container asks which streams to import
        val mkv = File(tmp.newFolder("import"), "movie.mkv").apply { writeBytes(ByteArray(48_000)) }
        val before = e.s.tracks.size
        val imp = async { e.importFiles(listOf(mkv.path)) }
        runCurrent()
        val streams = e.pendingDialogs.value.single()
        assertEquals("Select stream(s) to import", streams.title)
        assertEquals(listOf(true, true), streams.defaultChecked)
        e.replyDialogChoices(streams.id, listOf(1))
        assertEquals(1, imp.await().trackIds.size)
        assertEquals(before + 1, e.s.tracks.size)
        val imp2 = async { runCatching { e.importFiles(listOf(mkv.path)) } }
        runCurrent()
        e.replyDialogChoices(e.pendingDialogs.value.single().id, emptyList())
        assertEquals(ErrorCodes.CANCELLED, (imp2.await().exceptionOrNull() as EngineException).code)
        assertEquals(before + 1, e.s.tracks.size)
    }

    @Test
    fun settingsOfTheRevisedContract() = runTest {
        val e = engine()
        val s = e.getSettings()
        assertEquals(false, s.syncLock)
        assertEquals("system", s.language)
        assertEquals(0.0, s.latencyCorrectionMs!!, 0.0)
        assertTrue(e.s.has(CommandFlags.NSL))
        e.setSettings(Settings(syncLock = true, language = "ko", preferNewTrackRecord = true, dropoutDetection = false,
            pasteAsNewClips = true, moveSelectionWithTracks = true))
        assertTrue(e.s.has(CommandFlags.SL))
        assertFalse(e.s.has(CommandFlags.NSL))
        assertEquals("ko", e.appInfo().language)
        assertEquals(listOf("en", "ko"), e.appInfo().languages)
        val after = e.getSettings()
        assertEquals(true, after.preferNewTrackRecord)
        assertEquals(false, after.dropoutDetection)
        // Validation: nothing is written when one key is invalid
        expectError(ErrorCodes.INVALID_ARGS) { e.setSettings(Settings(language = "xx", syncLock = false)) }
        assertEquals(true, e.getSettings().syncLock)
    }

    @Test
    fun deviceInjectionLatencySpectrumAndContrast() = runTest {
        val e = engine()
        assertTrue(e.setAudioDevices(listOf(
            AudioDeviceSpec(7, "Pixel USB-C", 22, isSource = true, isSink = true, channelCounts = listOf(1, 2), sampleRates = listOf(48000)),
            AudioDeviceSpec(1, "earpiece", 1, isSource = false, isSink = true),
            AudioDeviceSpec(3, "", 15, isSource = true, isSink = false),
        )))
        val d = e.audioDevices()
        assertEquals(listOf("Default Output", "USB headset: Pixel USB-C"), d.outputs.map { it.name })
        assertEquals(listOf("Default Input", "Microphone", "USB headset: Pixel USB-C"), d.inputs.map { it.name })
        assertFalse(d.pending)
        // While playing the list waits for the stream to stop
        e.play()
        assertFalse(e.setAudioDevices(emptyList()))
        assertTrue(e.audioDevices().pending)
        e.stop()
        assertFalse(e.audioDevices().pending)
        assertEquals(listOf("Default Output"), e.audioDevices().outputs.map { it.name })

        e.setSettings(Settings(latencyCorrectionMs = 12.0))
        val lat = e.latency()
        assertEquals(12.0, lat.userTrimMs, 0.0)
        assertEquals(-lat.duplexOffsetMs + 12.0, lat.correctionMs, 1e-9)
        assertFalse(lat.measured)

        // Spectrum: cepstrum and sizes up to 131072; too short a selection fails
        val a1 = e.s.named("Audio 1").id
        e.select(0.0, 4.0, trackIds = listOf(a1))
        val cep = e.plotSpectrum("cepstrum", "hann", 1024)
        assertEquals("cepstrum", cep.algorithm)
        assertEquals(1024, cep.size)
        assertEquals(512, cep.values.size)
        assertTrue(cep.binSeconds!! > 0)
        e.select(0.0, 2.0)
        expectError(ErrorCodes.FAILED) { e.plotSpectrum("spectrum", "hann", 131072) }
        expectError(ErrorCodes.INVALID_ARGS) { e.plotSpectrum("spectrum", "hann", 262144) }

        // Contrast: one selected track; digital silence is -1000 dB
        val c = e.contrast(0.0, 2.0, 8.5, 9.5)
        assertTrue(c.backgroundSilent)
        assertEquals(-1000.0, c.backgroundDb, 0.0)
        assertTrue(c.passes)
        assertEquals("WCAG2 Pass", c.verdict)
        e.selectAll()
        expectError(ErrorCodes.NO_SELECTION) { e.contrast(0.0, 1.0, 2.0, 3.0) }
    }

    @Test
    fun effectsTakeACurveWithApplyAndPreview() = runTest {
        val e = engine()
        val eq = e.effects().effects.first { it.name == "Filter Curve EQ" }.id
        e.select(0.0, 1.0, trackIds = listOf(e.s.named("Audio 1").id))
        val curve = EqCurve(listOf(EqPoint(100.0, -6.0), EqPoint(1000.0, 3.0)))
        assertTrue(e.applyEffect(eq, curve = curve).applied)
        assertEquals(curve, e.describeEffect(eq).curve)
        e.previewEffect(eq, curve = EqCurve(listOf(EqPoint(200.0, 1.0))))
        assertEquals(1, e.describeEffect(eq).curve!!.points.size)
        e.stopPreview()
        val amplify = e.effects().effects.first { it.name == "Amplify" }.id
        expectError(ErrorCodes.INVALID_ARGS) { e.applyEffect(amplify, curve = curve) }
    }
}
