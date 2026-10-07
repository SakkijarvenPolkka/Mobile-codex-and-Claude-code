/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.fake.Wav
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAudacityEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var nowNs = 1_000_000_000L
    private val engines = mutableListOf<FakeAudacityEngine>()

    private fun engine(demo: Boolean = true, longOps: Long = 0, permission: Boolean = true) =
        FakeAudacityEngine(FakeConfig(demoProject = demo, longOperationMillis = longOps, clock = { nowNs }, autoTick = false,
            recordPermission = permission, filesDir = tmp.root.absolutePath + "/files", cacheDir = tmp.root.absolutePath + "/cache"))
            .also { engines += it }

    @After
    fun tearDown() = engines.forEach { it.dispose() }

    private val FakeAudacityEngine.s: Snapshot get() = snapshot.value
    private fun Snapshot.flag(f: Long) = (flags and f) == f
    private fun Snapshot.trackNamed(name: String) = tracks.first { it.name == name }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: EngineException) {
            assertEquals(e.message, code, e.code)
        }
    }

    private suspend fun FakeAudacityEngine.effectId(name: String) = effects().effects.first { it.name == name }.id

    @Test
    fun startsReadyWithDemoProjectAndFlags() = runTest {
        val e = engine()
        assertTrue(e.isFake)
        assertEquals(EngineStatus.Ready(0), e.status.value)
        val s = e.s
        assertTrue(s.project.open)
        assertEquals(listOf("Audio 1", "Audio 2", "Label 1"), s.tracks.map { it.name })
        val a1 = s.trackNamed("Audio 1")
        assertEquals(2, a1.clips.size)
        assertEquals(8.0, a1.clips[0].end, 1e-9)
        assertEquals(10.0, a1.clips[1].start, 1e-9)
        assertEquals(2, s.trackNamed("Audio 2").channels)
        for (f in listOf(CommandFlags.PROJECT_OPEN, CommandFlags.NB, CommandFlags.TS, CommandFlags.WS, CommandFlags.TE, CommandFlags.ES,
            CommandFlags.AS, CommandFlags.CNB, CommandFlags.LE, CommandFlags.ZI, CommandFlags.ZO, CommandFlags.WE, CommandFlags.NSL,
            CommandFlags.CS, CommandFlags.CC, CommandFlags.HW, CommandFlags.TFOCUS, CommandFlags.PLAYABLE, CommandFlags.NO_TIMETRACK,
            CommandFlags.FOC, CommandFlags.RECORD_PERMISSION)) assertTrue("flag $f", s.flag(f))
        for (f in listOf(CommandFlags.BUSY, CommandFlags.UA, CommandFlags.RA, CommandFlags.ST, CommandFlags.PAUSED, CommandFlags.CLIPBOARD,
            CommandFlags.JC, CommandFlags.LAST_EFF, CommandFlags.SL)) assertFalse("flag $f", s.flag(f))
        assertFalse(s.project.dirty)
        val empty = engine(demo = false)
        assertEquals("Untitled", empty.s.project.name)
        assertTrue(empty.s.tracks.isEmpty())
        assertFalse(empty.s.flag(CommandFlags.TE))
    }

    @Test
    fun cutUndoRedoAndClipboard() = runTest {
        val e = engine()
        val gen0 = e.s.generation
        val id = e.s.trackNamed("Audio 1").id
        e.edit("edit.cut")
        val afterCut = e.s
        assertTrue(afterCut.generation > gen0)
        assertEquals(6.0, afterCut.track(id)!!.clips[0].end, 1e-4)
        assertEquals(8.0, afterCut.track(id)!!.clips[1].start, 1e-4)
        assertTrue(afterCut.flag(CommandFlags.CLIPBOARD))
        assertEquals(2.0, afterCut.clipboard.duration, 1e-9)
        assertEquals(2.0, afterCut.selection.t0, 0.0)
        assertTrue(afterCut.selection.isPoint)
        assertTrue(afterCut.history.canUndo)
        assertEquals("Cut", afterCut.history.undo)
        assertTrue(afterCut.project.dirty)
        assertEquals(1, e.clipboardInfo().trackCount)

        e.undo()
        assertEquals(8.0, e.s.track(id)!!.clips[0].end, 1e-4)
        assertEquals(TimeRangeOf(2.0, 4.0), e.s.selection.t0 to e.s.selection.t1)
        assertTrue(e.s.flag(CommandFlags.RA))
        assertFalse(e.s.flag(CommandFlags.UA))
        assertFalse(e.s.project.dirty)
        e.redo()
        assertEquals(6.0, e.s.track(id)!!.clips[0].end, 1e-4)
        val h = e.history()
        assertEquals(1, h.current)
        assertEquals(listOf("Created new project", "Cut"), h.states.map { it.shortDescription })

        // Paste back at the cursor: one clip of 8 s again
        e.edit("edit.paste")
        assertEquals(8.0, e.s.track(id)!!.clips[0].end, 1e-4)
        assertEquals(2, e.s.track(id)!!.clips.size)
        assertEquals(2.0, e.s.selection.t0, 0.0)
        assertEquals(4.0, e.s.selection.t1, 1e-9)
        assertEquals("Paste", e.s.history.undo)
    }

    private fun TimeRangeOf(a: Double, b: Double) = a to b

    @Test
    fun selectionCommandsDoNotBumpGeneration() = runTest {
        val e = engine()
        val gen = e.s.generation
        val a1 = e.s.trackNamed("Audio 1").id
        val a2 = e.s.trackNamed("Audio 2").id
        e.selectTrackHeader(a2, shift = false, ctrl = false)
        assertEquals(listOf(a2), e.s.selectedTracks.map { it.id })
        assertEquals(0.0, e.s.selection.t0, 1e-9)
        assertEquals(12.0, e.s.selection.t1, 1e-6)
        assertTrue(e.s.flag(CommandFlags.ST))
        e.selectTrackHeader(a1, shift = false, ctrl = true)
        assertEquals(setOf(a1, a2), e.s.selectedTracks.map { it.id }.toSet())
        e.selectTrackHeader(a1, shift = false, ctrl = true)
        assertEquals(listOf(a2), e.s.selectedTracks.map { it.id })
        e.selectNone()
        assertTrue(e.s.selectedTracks.isEmpty())
        assertTrue(e.s.selection.isPoint)
        assertFalse(e.s.flag(CommandFlags.AS))
        e.selectAll()
        assertEquals(3, e.s.selectedTracks.size)
        assertEquals(14.0, e.s.selection.t1, 1e-6)
        e.select(5.0, 1.0)
        assertEquals(1.0, e.s.selection.t0, 0.0)
        e.selectCommand("select.cursorToTrackEnd")
        assertEquals(14.0, e.s.selection.t0, 1e-6)
        e.select(9.0, 9.0)
        e.selectCommand("select.prevClipBoundary")
        assertEquals(8.0, e.s.selection.t0, 1e-6)
        e.selectCommand("select.nextClipBoundary")
        assertEquals(10.0, e.s.selection.t0, 1e-6)
        e.setPlayRegion(1.0, 3.0, true)
        e.setView(zoom = 200.0, hpos = 1.0)
        assertEquals(200.0, e.s.view.zoom, 0.0)
        assertEquals(gen, e.s.generation)
        expectError(ErrorCodes.UNKNOWN_COMMAND) { e.selectCommand("select.bogus") }
        expectError(ErrorCodes.NOT_FOUND) { e.selectTracks(listOf(999L)) }
        expectError(ErrorCodes.INVALID_ARGS) { e.selectTracks(listOf(a1), "flip") }
    }

    @Test
    fun joinFlagAndClipReferences() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        e.selectTracks(listOf(a1))
        e.select(7.0, 11.0)
        assertTrue(e.s.flag(CommandFlags.JC))
        val staleGen = e.s.generation
        e.edit("edit.join")
        assertEquals(1, e.s.track(a1)!!.clips.size)
        assertEquals(14.0, e.s.track(a1)!!.end, 1e-6)
        expectError(ErrorCodes.STALE) { e.moveClip(a1, 0, staleGen, 1.0) }
        e.moveClip(a1, 0, e.s.generation, 1.5)
        assertEquals(1.5, e.s.track(a1)!!.clips[0].start, 0.0)
        expectError(ErrorCodes.NOT_FOUND) { e.renameClip(a1, 5, e.s.generation, "x") }
        e.renameClip(a1, 0, e.s.generation, "Joined")
        assertEquals("Joined", e.s.track(a1)!!.clips[0].name)
    }

    @Test
    fun labelsAndLabelsSelectedFlag() = runTest {
        val e = engine()
        val labelTrack = e.s.trackNamed("Label 1").id
        e.selectTracks(listOf(labelTrack))
        e.select(0.0, 3.0)
        assertTrue(e.s.flag(CommandFlags.LS))
        e.select(5.0, 6.0)
        val (tid, index) = e.addLabel("Mid")
        assertEquals(labelTrack, tid)
        assertEquals(1, index)
        assertEquals(listOf("Intro", "Mid", "Break", "Voice"), e.s.track(tid)!!.labels.map { it.title })
        e.editLabel(tid, 1, t0 = 9.0, t1 = 9.5)
        assertEquals(listOf("Intro", "Break", "Mid", "Voice"), e.s.track(tid)!!.labels.map { it.title })
        expectError(ErrorCodes.INVALID_ARGS) { e.editLabel(tid, 0, t0 = 5.0, t1 = 4.0) }
        e.removeLabel(tid, 0)
        assertEquals(3, e.s.track(tid)!!.labels.size)
        expectError(ErrorCodes.NOT_FOUND) { e.removeLabel(tid, 10) }
    }

    @Test
    fun mixerControlsSoloAndUndoSemantics() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        val a2 = e.s.trackNamed("Audio 2").id
        val states = e.history().states.size
        val gen = e.s.generation
        e.setTrackMute(a1, true)
        assertTrue(e.s.track(a1)!!.mute)
        assertTrue(e.s.generation > gen)
        assertEquals(states, e.history().states.size)
        e.setTrackSolo(a2, true) // Simple mode: solo a2, mute the others, unmute a2
        assertTrue(e.s.track(a2)!!.solo)
        assertTrue(e.s.track(a1)!!.mute)
        assertFalse(e.s.track(a2)!!.mute)
        e.setTrackGain(a1, 0.5, final = false)
        e.setTrackGain(a1, 0.4, final = true)
        e.setTrackGain(a1, 0.3, final = true)
        assertEquals(states + 1, e.history().states.size) // consolidated
        assertEquals(0.3, e.s.track(a1)!!.gain, 0.0)
        expectError(ErrorCodes.INVALID_ARGS) { e.setTrackPan(a1, 2.0, true) }
        e.setSettings(Settings(soloMode = "Multi"))
        e.setTrackSolo(a1, true)
        assertTrue(e.s.track(a1)!!.solo && e.s.track(a2)!!.solo)
        expectError(ErrorCodes.INVALID_ARGS) { e.setSettings(Settings(soloMode = "None")) }
    }

    @Test
    fun effectParameterValidation() = runTest {
        val e = engine()
        val amplify = e.effectId("Amplify")
        assertEquals("Effect_Audacity_Audacity_Amplify_Built-in Effect: Amplify", amplify)
        val d = e.describeEffect(amplify)
        val ratio = d.params.first { it.key == "Ratio" }
        assertEquals("double", ratio.kind)
        assertEquals("dB", ratio.display)
        assertEquals(0.9, ratio.defaultValue!!.jsonPrimitive.content.toDouble(), 0.0)

        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(amplify, mapOf("Ratio" to JsonPrimitive(1000.0), "AllowClipping" to JsonPrimitive(true))) }
        // Nothing changed after a failed validation
        assertEquals(false, e.describeEffect(amplify).params.first { it.key == "AllowClipping" }.value!!.jsonPrimitive.content.toBoolean())
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(amplify, mapOf("Nope" to JsonPrimitive(1))) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(amplify, mapOf("AllowClipping" to JsonPrimitive(1))) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(amplify, mapOf("Ratio" to JsonPrimitive("2"))) }
        expectError(ErrorCodes.NOT_FOUND) { e.describeEffect("nope") }

        val tone = e.effectId("Tone")
        val byName = e.setEffectParams(tone, mapOf<String, JsonElement>("Waveform" to JsonPrimitive("Square")), duration = 2.0)
        assertEquals("1", byName.params.first { it.key == "Waveform" }.value!!.jsonPrimitive.content)
        assertTrue(byName.supportsDuration)
        assertEquals(2.0, byName.duration, 0.0)
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(tone, mapOf("Waveform" to JsonPrimitive(9))) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(tone, emptyMap(), duration = -1.0) }

        val repeat = e.effectId("Repeat")
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(repeat, mapOf("Count" to JsonPrimitive(1.5))) }
        assertEquals("3", e.setEffectParams(repeat, mapOf("Count" to JsonPrimitive(3))).params[0].value!!.jsonPrimitive.content)
        val dtmf = e.effectId("DTMF Tones")
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(dtmf, mapOf("Sequence" to JsonPrimitive("12!"))) }

        // Presets
        val reverb = e.effectId("Reverb")
        val r = e.loadEffectPreset(reverb, "factory", name = "Cathedral")
        assertEquals(90.0, r.params.first { it.key == "RoomSize" }.value!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(18, r.presets.factory.size)
        e.saveEffectPreset(reverb, "Mine")
        e.loadEffectPreset(reverb, "defaults")
        assertEquals(75.0, e.describeEffect(reverb).params.first { it.key == "RoomSize" }.value!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(90.0, e.loadEffectPreset(reverb, "user", "Mine").params.first { it.key == "RoomSize" }.value!!.jsonPrimitive.content.toDouble(), 0.0)
        e.deleteEffectPreset(reverb, "Mine")
        expectError(ErrorCodes.NOT_FOUND) { e.loadEffectPreset(reverb, "user", "Mine") }
        assertEquals(27, e.describeEffect(e.effectId("Compressor")).presets.factory.size)
    }

    @Test
    fun equalizationCurves() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        val before = e.clipSamples(a1, 0)!!
        val eq = e.effectId("Filter Curve EQ")
        val d = e.describeEffect(eq)
        assertEquals("equalization", d.special)
        assertEquals(10, d.curves.size)
        assertNotNull(d.curve)
        val treble = e.loadEffectPreset(eq, "factory", name = "Treble Cut")
        assertEquals(-110.0, treble.curve!!.points.last().dB, 0.0)
        val bad = io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve(listOf(
            io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint(1000.0, 0.0),
            io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint(100.0, 3.0)))
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(eq, emptyMap(), curve = bad) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setEffectParams(e.effectId("Amplify"), emptyMap(), curve = treble.curve) }
        assertEquals(5, e.describeEffect(e.effectId("Graphic EQ")).curves.size)
        assertTrue(e.applyEffect(eq).applied)
        val i = (3.0 * 44100).toInt()
        assertTrue((i until i + 2000).any { abs(e.clipSamples(a1, 0)!![it] - before[it]) > 1e-4f })
    }

    @Test
    fun effectListAndMenus() = runTest {
        val e = engine()
        val list = e.effects()
        val ids = list.effects.map { it.id }.toSet()
        assertEquals(ids.size, list.effects.size)
        val menus = list.menus
        assertEquals("Volume and Compression", menus.effect.first().title)
        assertEquals(listOf("Amplify", "Auto Duck", "Compressor", "Limiter", "Loudness Normalization", "Normalize"),
            menus.effect.first().ids.map { id -> list.effects.first { it.id == id }.name })
        for (section in menus.effect + menus.generate + menus.analyze + menus.tools) for (id in section.ids) assertTrue(id in ids)
        val generators = menus.generate.flatMap { it.ids }.map { id -> list.effects.first { it.id == id }.name }
        assertTrue(generators.containsAll(listOf("Chirp", "DTMF Tones", "Noise", "Silence", "Tone", "Pluck", "Rhythm Track", "Risset Drum")))
        val tools = menus.tools.flatMap { it.ids }.map { id -> list.effects.first { it.id == id }.name }
        assertTrue(tools.containsAll(listOf("Nyquist Prompt", "Regular Interval Labels", "Sample Data Export")))
        val highPass = list.effects.first { it.name == "High-Pass Filter" }
        assertEquals("Nyquist", highPass.family)
        assertTrue(highPass.id.startsWith("Effect_Nyquist_Dominic Mazzoni_High-Pass Filter_"))
        assertNotNull(e.describeEffect(highPass.id).nyquist)
        assertEquals("equalization", list.effects.first { it.name == "Filter Curve EQ" }.special)
    }

    @Test
    fun applyAmplifyChangesAudioAndUndoRestoresIt() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        val before = e.clipSamples(a1, 0)!!
        val amplify = e.effectId("Amplify")
        val result = e.applyEffect(amplify, mapOf("Ratio" to JsonPrimitive(0.5), "AllowClipping" to JsonPrimitive(false)))
        assertTrue(result.applied)
        val after = e.clipSamples(a1, 0)!!
        val i = (3.0 * 44100).toInt()
        assertEquals(before[i] * 0.5f, after[i], 1e-6f)
        val outside = (6.0 * 44100).toInt()
        assertEquals(before[outside], after[outside], 0f)
        assertEquals("Amplify", e.s.history.undo)
        assertEquals("Amplify", e.s.lastEffect?.name)
        assertTrue(e.s.flag(CommandFlags.LAST_EFF))
        assertNotEquals(e.snapshot.value.track(a1)!!.waveVersion, 0L)
        e.undo()
        assertEquals(before[i], e.clipSamples(a1, 0)!![i], 0f)
        val repeated = e.repeatLastEffect()
        assertTrue(repeated.applied)
        assertEquals(before[i] * 0.5f, e.clipSamples(a1, 0)!![i], 1e-6f)
        // Clipping is refused unless allowed
        expectError(ErrorCodes.INVALID_ARGS) { e.applyEffect(amplify, mapOf("Ratio" to JsonPrimitive(100.0))) }
        e.selectNone()
        expectError(ErrorCodes.NO_SELECTION) { e.applyEffect(amplify) }
    }

    @Test
    fun lengthChangingEffectsMoveSelectionAndLaterClips() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        e.selectTracks(listOf(a1))
        e.select(1.0, 2.0)
        e.applyEffect(e.effectId("Repeat"), mapOf("Count" to JsonPrimitive(2)))
        assertEquals(1.0, e.s.selection.t0, 0.0)
        assertEquals(4.0, e.s.selection.t1, 1e-4)
        assertEquals(10.0, e.s.track(a1)!!.clips[0].end, 1e-4)
        assertEquals(12.0, e.s.track(a1)!!.clips[1].start, 1e-4)
    }

    @Test
    fun generatorsAnalyzersAndNoiseReduction() = runTest {
        val e = engine()
        val before = e.s.tracks.size
        e.selectNone()
        e.select(1.0, 1.0)
        e.applyEffect(e.effectId("Tone"), mapOf("Frequency" to JsonPrimitive(1000.0)), duration = 2.0)
        val tone = e.s.tracks.last()
        assertEquals(before + 1, e.s.tracks.size)
        assertEquals("Audio 3", tone.name)
        assertEquals(1.0, tone.start, 1e-9)
        assertEquals(3.0, tone.end, 1e-4)
        assertEquals(3.0, e.s.selection.t1, 1e-4)
        assertTrue(e.s.flag(CommandFlags.LAST_GEN))

        // Point selection inside a clip of a selected track inserts and pushes later audio
        val a1 = e.s.trackNamed("Audio 1").id
        e.selectTracks(listOf(a1))
        e.select(4.0, 4.0)
        e.applyEffect(e.effectId("Silence"), duration = 1.0)
        assertEquals(9.0, e.s.track(a1)!!.clips[0].end, 1e-4)

        e.selectTracks(listOf(a1))
        e.select(0.0, 9.0)
        val found = e.applyEffect(e.effectId("Label Sounds"))
        assertTrue(found.applied)
        assertTrue(e.s.flag(CommandFlags.LAST_ANA))
        assertEquals("Label Sounds", e.s.tracks.last().name)
        assertTrue(e.s.tracks.last().labels.isNotEmpty())

        val nr = e.effectId("Noise Reduction")
        expectError(ErrorCodes.FAILED) { e.applyEffect(nr) }
        e.selectTracks(listOf(a1))
        e.select(12.0, 13.0)
        e.captureNoiseProfile()
        assertTrue(e.describeEffect(nr).profileCaptured)
        assertTrue(e.applyEffect(nr).applied)
    }

    @Test
    fun exportOptionsDependOnEachOther() = runTest {
        val e = engine()
        val keys = e.exportFormats().map { it.key }
        assertEquals(listOf("WAV (Microsoft)", "Other uncompressed files", "MP3 Files", "Ogg Vorbis Files", "Opus Files",
            "FLAC Files", "WavPack Files", "MP2 Files", "M4A (AAC) Files"), keys)
        val mp3 = e.exportOptions("MP3 Files")
        assertEquals(listOf(false, false, true, true, true), mp3.options.map { it.hidden })
        assertEquals("SET", mp3.options[0].value!!.v.jsonPrimitive.content)
        val cbr = e.setExportOption("MP3 Files", 0, ExportValue("s", JsonPrimitive("CBR")))
        assertEquals(listOf(false, true, true, true, false), cbr.options.map { it.hidden })
        val c320 = e.setExportOption("MP3 Files", 4, ExportValue("i", JsonPrimitive(320)))
        assertEquals(listOf(32000, 44100, 48000), c320.sampleRates)
        expectError(ErrorCodes.INVALID_ARGS) { e.setExportOption("MP3 Files", 0, ExportValue("i", JsonPrimitive(1))) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setExportOption("MP3 Files", 0, ExportValue("s", JsonPrimitive("XYZ"))) }
        expectError(ErrorCodes.INVALID_ARGS) { e.setExportOption("Ogg Vorbis Files", 0, ExportValue("i", JsonPrimitive(11))) }
        expectError(ErrorCodes.NOT_FOUND) { e.exportOptions("Nope") }
        val other = e.setExportOption("Other uncompressed files", 0, ExportValue("i", JsonPrimitive(0x030000)))
        assertEquals(listOf("au"), other.format.extensions)
        assertFalse(other.options.first { it.id == 0x030000 }.hidden)
        assertTrue(other.options.first { it.id == 0x020000 }.hidden)
        val wv = e.exportOptions("WavPack Files")
        assertTrue(wv.options.first { it.id == 4 }.readOnly)
        assertFalse(e.setExportOption("WavPack Files", 2, ExportValue("b", JsonPrimitive(true))).options.first { it.id == 4 }.readOnly)
        assertEquals(listOf(8000, 12000, 16000, 24000, 48000), e.exportOptions("Opus Files").sampleRates)

        val defaults = e.exportDefaults("WAV (Microsoft)")
        assertTrue(defaults.hasSelection)
        assertEquals(2, defaults.defaultChannels)
        assertEquals(44100, defaults.defaultRate)
        assertEquals(48000, e.exportDefaults("Opus Files").defaultRate)
    }

    @Test
    fun exportWritesAStagingFile() = runTest {
        val e = engine()
        val out = tmp.root.resolve("cache/export/1/song.wav")
        val path = e.export(out.absolutePath, "WAV (Microsoft)", "selection", 2, 22050)
        assertEquals(out.absolutePath, path)
        val wav = Wav.read(out)!!
        assertEquals(22050, wav.rate)
        assertEquals(2, wav.channels.size)
        assertEquals(2 * 22050, wav.channels[0].size)
        assertTrue(wav.channels[0].any { abs(it) > 0.01f })
        expectError(ErrorCodes.INVALID_ARGS) { e.export(out.absolutePath, "WAV (Microsoft)", "project", 2, 44100) }
        expectError(ErrorCodes.INVALID_ARGS) { e.export(tmp.root.resolve("x.opus").absolutePath, "Opus Files", "project", 2, 44100) }
        expectError(ErrorCodes.INVALID_ARGS) { e.export(tmp.root.resolve("y.mp3").absolutePath, "MP3 Files", "project", 3, 44100) }
        // Import the exported file back
        val imported = e.importFiles(listOf(out.absolutePath))
        val track = e.s.track(imported.trackIds.single())!!
        assertEquals("song", track.name)
        assertEquals(22050.0, track.rate, 0.0)
        assertEquals(2.0, track.end, 1e-3)
        assertEquals("Import", e.s.history.undo)
        expectError(ErrorCodes.NOT_FOUND) { e.importFiles(listOf(tmp.root.resolve("missing.wav").absolutePath)) }
    }

    @Test
    fun transportFollowsTheClock() = runTest {
        val e = engine()
        e.play()
        var t = e.readTransport()
        assertEquals(TransportSample.STATE_PLAYING, t.state)
        assertEquals(2.0, t.displayTime, 1e-9)
        assertEquals("playing", e.transportState.value.state)
        assertTrue(e.s.flag(CommandFlags.BUSY))
        assertFalse(e.s.flag(CommandFlags.NB))
        expectError(ErrorCodes.AUDIO_BUSY) { e.edit("edit.cut") }
        nowNs += 1_000_000_000L
        t = e.readTransport()
        assertEquals(3.0, t.displayTime, 1e-9)
        val m = e.readMeters()!!
        assertTrue(m.playPeak[0] > 0f)
        assertEquals(2, m.playChannels)
        e.pause()
        assertEquals(TransportSample.STATE_PAUSED_PLAY, e.readTransport().state)
        assertTrue(e.s.flag(CommandFlags.PAUSED))
        nowNs += 5_000_000_000L
        assertEquals(3.0, e.readTransport().displayTime, 1e-9)
        e.pause()
        nowNs += 2_000_000_000L
        t = e.readTransport()
        assertEquals(TransportSample.STATE_STOPPED, t.state)
        assertEquals("stopped", e.transportState.value.state)
        assertEquals("end", e.transportState.value.reason)
        assertTrue(e.s.flag(CommandFlags.NB))

        e.play(loop = true)
        assertTrue(e.s.playRegion.active)
        nowNs += 2_500_000_000L
        t = e.readTransport()
        assertTrue(t.looping)
        assertEquals(2.5, t.displayTime, 1e-9)
        e.stop()
        assertEquals("user", e.transportState.value.reason)
    }

    @Test
    fun recordingAppendsAudio() = runTest {
        val e = engine()
        val tracksBefore = e.s.tracks.size
        e.select(1.0, 1.0)
        e.record(newTrack = true)
        assertEquals(TransportSample.STATE_RECORDING, e.readTransport().state)
        assertFalse(e.s.flag(CommandFlags.CNB))
        nowNs += 1_500_000_000L
        e.readTransport()
        val pending = e.s.track(FakeAudacityEngine.PENDING_TRACK_ID)!!
        assertEquals(2.5, pending.end, 1e-3)
        assertTrue(e.readMeters()!!.recPeak[0] > 0f)
        val level = 40
        val first = Zoom.tileStart(Zoom.columnAt(2.4, level))
        val raw = FloatArray(3 * 256)
        val status = e.waveColumnsInto(FakeAudacityEngine.PENDING_TRACK_ID, 0, level, first, 256, raw)
        assertTrue(DisplayStatus.isPartial(status))
        e.stop()
        val s = e.s
        assertEquals(tracksBefore + 1, s.tracks.size)
        val rec = s.tracks.last()
        assertTrue(rec.id >= 0)
        assertEquals(1.0, rec.start, 1e-9)
        assertEquals(1.5, rec.end - rec.start, 1e-3)
        assertEquals("Record", s.history.undo)
        assertNull(s.track(FakeAudacityEngine.PENDING_TRACK_ID))

        // Without permission recording is refused
        val denied = engine(permission = false)
        expectError(ErrorCodes.UNSUPPORTED) { denied.record(newTrack = false) }
        assertFalse(denied.s.flag(CommandFlags.RECORD_PERMISSION))
        denied.setRecordPermission(true)
        assertTrue(denied.s.flag(CommandFlags.RECORD_PERMISSION))
    }

    @Test
    fun waveColumnsMatchTheSamples() = runTest {
        val e = engine()
        val a1 = e.s.trackNamed("Audio 1").id
        val level = 40 // 32 px/s
        val pps = Zoom.ppsForLevel(level)
        val tile = e.waveColumns(a1, 0, level, 0, 256)!!
        assertEquals(e.s.track(a1)!!.waveVersion, tile.waveVersion)
        assertFalse(tile.partial)
        val samples = e.clipSamples(a1, 0)!!
        for (c in listOf(0, 10, 100, 255)) {
            val t0 = c / pps
            val t1 = (c + 1) / pps
            val inGap = t0 >= 8.0 && t1 <= 10.0
            val past = t0 >= 14.0
            if (inGap || past) {
                assertTrue("column $c", tile.data[c].isNaN() && tile.data[256 + c].isNaN() && tile.data[512 + c].isNaN())
                continue
            }
            if (t1 > 8.0) continue
            val i0 = (t0 * 44100).toInt()
            val i1 = (t1 * 44100).toInt()
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (i in i0 until i1) { mn = minOf(mn, samples[i]); mx = maxOf(mx, samples[i]) }
            assertEquals("min $c", mn, tile.data[c], 1e-6f)
            assertEquals("max $c", mx, tile.data[256 + c], 1e-6f)
            assertTrue(tile.data[512 + c] <= maxOf(abs(mn), abs(mx)) + 1e-6f)
        }
        val env = e.envelopeColumns(a1, level, 0, 256)!!
        assertEquals(1f, env[10], 0f)
        val env2 = e.envelopeColumns(a1, level, 256, 256)!! // 8 s .. 16 s
        assertTrue(env2[(9.0 * pps).toInt() - 256].isNaN())
        assertEquals(1f, env2[(11.0 * pps).toInt() - 256], 0f)
        // Beyond 0.5 * rate pixels per second: sample mode
        val deep = 124 // 2^15.5 > 22050
        assertNull(e.waveColumns(a1, 0, deep, Zoom.columnAt(1.0, deep), 256))
        assertEquals(DisplayStatus.SAMPLE_MODE, e.waveColumnsInto(a1, 0, deep, Zoom.columnAt(1.0, deep), 256, FloatArray(768)))
        val runs = e.waveSamples(a1, 0, 1.0, 1.001)!!
        assertEquals(0, runs.single().clipIndex)
        assertEquals(samples[44100], runs.single().values[0], 0f)
        assertEquals(DisplayStatus.NO_TRACK, e.waveColumnsInto(999, 0, level, 0, 256, FloatArray(768)))
        val spec = e.spectrogramColumns(a1, 0, level, 0, 64, 32)!!
        assertEquals(64 * 32, spec.size)
        assertTrue(spec.any { it != 0.toByte() })
    }

    @Test
    fun projectsSaveOpenAndRecover() = runTest {
        val e = engine()
        expectError(ErrorCodes.NEEDS_PATH) { e.saveProject() }
        val path = tmp.root.absolutePath + "/files/Projects/Mine.aup3"
        assertEquals(path, e.saveProjectAs(path))
        assertEquals("Mine", e.s.project.name)
        assertFalse(e.s.project.temporary)
        assertFalse(e.s.project.dirty)
        assertTrue(e.listProjects().any { it.path == path })
        e.edit("edit.delete")
        assertTrue(e.s.project.dirty)
        assertEquals(path, e.saveProject())
        assertFalse(e.s.project.dirty)
        val genBefore = e.s.generation
        val other = e.listProjects().first { it.name == "Interview" }
        e.openProject(other.path)
        assertEquals("Interview", e.s.project.name)
        assertTrue(e.s.generation > genBefore)
        expectError(ErrorCodes.FAILED) { e.openProject(other.path) }
        expectError(ErrorCodes.NOT_FOUND) { e.openProject(tmp.root.absolutePath + "/nope.aup3") }
        expectError(ErrorCodes.INVALID_ARGS) { e.openProject(tmp.root.absolutePath + "/song.mp3") }
        expectError(ErrorCodes.FAILED) { e.deleteProject(other.path) }
        e.closeProject()
        assertFalse(e.s.project.open)
        assertTrue(e.s.flag(CommandFlags.NB))
        assertFalse(e.s.flag(CommandFlags.PROJECT_OPEN))
        expectError(ErrorCodes.NO_PROJECT) { e.undo() }
        e.deleteProject(other.path)
        assertTrue(e.listProjects().none { it.path == other.path })
        e.newProject()
        assertEquals("Untitled", e.s.project.name)

        val recovering = FakeAudacityEngine(FakeConfig(clock = { nowNs }, autoTick = false,
            recoverable = listOf(io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry("/s/a.aup3unsaved", "Lost"))))
            .also { engines += it }
        assertEquals(EngineStatus.Ready(1), recovering.status.value)
        assertFalse(recovering.s.project.open)
        recovering.recoverProject("/s/a.aup3unsaved")
        assertTrue(recovering.s.project.dirty)
        assertTrue(recovering.recoverableProjects().isEmpty())
    }

    @Test
    fun longOperationsReportProgressAndCanBeCancelled() = runTest {
        val e = engine(longOps = 1000)
        val a1 = e.s.trackNamed("Audio 1").id
        val before = e.clipSamples(a1, 0)!!
        val amplify = e.effectId("Amplify")
        val job = async { runCatching { e.applyEffect(amplify, mapOf("Ratio" to JsonPrimitive(0.5))) } }
        runCurrent()
        advanceTimeBy(250)
        val progress = e.progress.value.values.single()
        assertEquals("Applying Amplify...", progress.title)
        assertTrue(progress.fraction > 0.0)
        e.cancelProgress(progress.id)
        advanceTimeBy(1000)
        val result = job.await()
        assertEquals(ErrorCodes.CANCELLED, (result.exceptionOrNull() as EngineException).code)
        assertTrue(e.progress.value.isEmpty())
        assertEquals(before[44100 * 3], e.clipSamples(a1, 0)!![44100 * 3], 0f)
        // Without cancel the same call completes after the simulated time
        assertTrue(e.applyEffect(amplify, mapOf("Ratio" to JsonPrimitive(0.5))).applied)
    }

    @Test
    fun trackCommands() = runTest {
        val e = engine()
        val id = e.addTrack("stereo")
        assertEquals("Audio 3", e.s.track(id)!!.name)
        assertEquals(listOf(id), e.s.selectedTracks.map { it.id })
        val a2 = e.s.trackNamed("Audio 2").id
        e.trackChannelCommand("tracks.splitStereo", a2)
        val halves = e.s.tracks.filter { it.name == "Audio 2" }
        assertEquals(2, halves.size)
        assertEquals(listOf(-1.0, 1.0), halves.map { it.pan })
        e.trackChannelCommand("tracks.makeStereo", halves[0].id)
        assertEquals(2, e.s.track(halves[0].id)!!.channels)
        expectError(ErrorCodes.INVALID_ARGS) { e.trackChannelCommand("tracks.swapChannels", e.s.trackNamed("Audio 1").id) }
        e.moveTrack(id, "top")
        assertEquals(id, e.s.tracks.first().id)
        e.renameTrack(id, "Drums")
        assertEquals("Drums", e.s.tracks.first().name)
        e.removeTracks(listOf(id))
        assertNull(e.s.track(id))
        e.selectTracks(listOf(e.s.trackNamed("Audio 1").id, a2.let { halves[0].id }))
        e.mixAndRender(toNewTrack = true)
        assertEquals("Mix", e.s.tracks.last().name)
        assertEquals(2, e.s.tracks.last().channels)
        e.selectTracks(listOf(e.s.tracks.last().id))
        e.resample(22050)
        assertEquals(22050.0, e.s.tracks.last().rate, 0.0)
        e.alignTracks("startToCursor")
        assertEquals(e.s.selection.t0, e.s.tracks.last().start, 1e-9)
        e.setTrackFormat(e.s.tracks.last().id, "int16")
        assertEquals("int16", e.s.tracks.last().format)
    }

    @Test
    fun rawCommandsAndDialogs() = runTest {
        val e = engine()
        val r = e.invokeCommand("debug.makeTestTrack", io.github.sakkijarvenpolkka.audacity.engine.EngineProtocol.args("seconds" to 2.0))
        val id = (r as kotlinx.serialization.json.JsonObject)["id"]!!.jsonPrimitive.content.toLong()
        assertEquals("Test Tone 1", e.s.track(id)!!.name)
        val ask = async { e.invokeCommand("debug.ask", io.github.sakkijarvenpolkka.audacity.engine.EngineProtocol.args("message" to "Sure?")) }
        runCurrent()
        val dialog = e.pendingDialogs.value.single()
        assertTrue(dialog.blocking)
        e.replyDialog(dialog.id, 1)
        assertEquals("no", (ask.await() as kotlinx.serialization.json.JsonObject)["result"]!!.jsonPrimitive.content)
        assertTrue(e.pendingDialogs.value.isEmpty())
        expectError(ErrorCodes.UNKNOWN_COMMAND) { e.invokeCommand("bogus.command") }
    }
}
