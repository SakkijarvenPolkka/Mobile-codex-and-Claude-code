/*
 * The fake engine's side of the mobile editing commands: edit.splitAt
 * (split button / razor tool), clips.trim (clip border drags), labels.add at
 * an explicit position and the "ratio" display of tempo/pitch/speed
 * parameters. It must behave like the bridge (native/tests/bridge/edit,
 * native/tests/bridge/effects).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeMobileEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val engines = mutableListOf<FakeAudacityEngine>()

    private fun engine() =
        FakeAudacityEngine(FakeConfig(demoProject = false, longOperationMillis = 0, autoTick = false,
            filesDir = tmp.root.absolutePath + "/files", cacheDir = tmp.root.absolutePath + "/cache"))
            .also { engines += it }

    @After
    fun tearDown() = engines.forEach { it.dispose() }

    private val FakeAudacityEngine.s: Snapshot get() = snapshot.value
    private fun Snapshot.tr(id: Long): TrackState = tracks.first { it.id == id }

    private suspend fun FakeAudacityEngine.tone(seconds: Double, channels: Int = 1): Long =
        invokeCommand("debug.makeTestTrack", buildJsonObject {
            put("seconds", seconds)
            put("channels", channels)
        }).jsonObject["id"]!!.jsonPrimitive.long

    private suspend fun FakeAudacityEngine.currentDescription(): String =
        history().let { h -> h.states.first { it.index == h.current }.description }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: EngineException) {
            assertEquals(e.message, code, e.code)
        }
    }

    private fun assertClip(s: Snapshot, track: Long, index: Int, start: Double, end: Double) {
        val c = s.tr(track).clips[index]
        assertEquals("start of clip $index: $c", start, c.start, 1e-6)
        assertEquals("end of clip $index: $c", end, c.end, 1e-6)
    }

    @Test
    fun splitAtSelectedExplicitAndUnselectedTracks() = runTest {
        val e = engine()
        val a = e.tone(3.0)
        val b = e.tone(3.0)
        e.selectTracks(listOf(a), "set")
        e.select(0.25, 0.75)
        val h0 = e.history().states.size

        // Default: the selected wave tracks; the cursor moves to t
        var r = e.splitAt(1.5)
        assertEquals(1, r.splits)
        assertEquals(listOf(a), r.trackIds)
        assertEquals(2, e.s.tr(a).clips.size)
        assertEquals(1, e.s.tr(b).clips.size)
        assertClip(e.s, a, 0, 0.0, 1.5)
        assertClip(e.s, a, 1, 1.5, 3.0)
        // Each part keeps the other one hidden (WaveTrack::SplitAt)
        assertEquals(1.5, e.s.tr(a).clips[0].trimRight, 1e-6)
        assertEquals(1.5, e.s.tr(a).clips[1].trimLeft, 1e-6)
        assertEquals(1.5, e.s.selection.t0, 1e-6)
        assertEquals(1.5, e.s.selection.t1, 1e-6)
        assertEquals(h0 + 1, e.history().states.size)
        assertEquals("Split", e.s.history.undo)
        e.undo()
        assertEquals(1, e.s.tr(a).clips.size)
        e.redo()
        assertEquals(2, e.s.tr(a).clips.size)

        // At a boundary, in a gap or outside the audio: nothing changes
        e.select(0.5, 0.5)
        val gen = e.s.generation
        assertEquals(0, e.splitAt(1.5 + 1e-7).splits)
        assertEquals(0, e.splitAt(10.0).splits)
        assertEquals(0, e.splitAt(-1.0).splits)
        assertEquals(h0 + 1, e.history().states.size)
        assertEquals(0.5, e.s.selection.t0, 1e-9)
        assertEquals(gen, e.s.generation)

        // Explicit tracks: one entry, request order, duplicates dropped
        r = e.splitAt(2.0, listOf(b, a, b))
        assertEquals(listOf(b, a), r.trackIds)
        assertEquals(3, e.s.tr(a).clips.size)
        assertEquals(2, e.s.tr(b).clips.size)
        assertEquals(h0 + 2, e.history().states.size)
        assertTrue(e.s.tr(a).selected && !e.s.tr(b).selected)

        // No track selected: the tracks with a clip at t
        val c = e.tone(1.0)
        e.selectNone()
        r = e.splitAt(2.5)
        assertEquals(listOf(a, b), r.trackIds)
        assertEquals(1, e.s.tr(c).clips.size)
        assertFalse(e.s.tr(a).selected)

        // Stereo
        val st = e.tone(2.0, channels = 2)
        assertEquals(1, e.splitAt(1.0, listOf(st)).splits)
        assertClip(e.s, st, 1, 1.0, 2.0)

        expectError(ErrorCodes.INVALID_ARGS) { e.splitAt(1.0, emptyList()) }
        expectError(ErrorCodes.INVALID_ARGS) { e.splitAt(Double.NaN) }
        expectError(ErrorCodes.NOT_FOUND) { e.splitAt(1.0, listOf(987654L)) }
        val lt = e.addTrack("label")
        expectError(ErrorCodes.NOT_FOUND) { e.splitAt(1.0, listOf(lt)) }
    }

    @Test
    fun trimClipLiveDragIsOneEntryAndUndoable() = runTest {
        val e = engine()
        val a = e.tone(3.0)
        val before = e.s
        val g0 = before.generation
        val h0 = e.history().states.size
        val rate = before.tr(a).clips[0].rate

        // final = false: model change only
        var r = e.trimClip(a, 0, g0, trimLeft = 0.5, final = false)
        assertEquals(0.5, r.trimLeft, 1e-9)
        assertEquals(0.5, r.start, 1e-9)
        assertEquals(g0, e.s.generation)
        assertEquals(h0, e.history().states.size)
        assertClip(e.s, a, 0, 0.5, 3.0)
        assertEquals(0.5, e.s.tr(a).clips[0].trimLeft, 1e-9)
        assertNotEquals(before.tr(a).waveVersion, e.s.tr(a).waveVersion)
        e.trimClip(a, 0, g0, trimLeft = 0.75, final = false)

        // final = true: one entry for the whole drag, measured from its start
        r = e.trimClip(a, 0, g0, trimLeft = 1.0, final = true)
        assertEquals(1.0, r.trimLeft, 1e-9)
        assertTrue(e.s.generation > g0)
        assertEquals(h0 + 1, e.history().states.size)
        assertEquals("Trim by 1.00s", e.s.history.undo)
        assertEquals("Adjust left trim by 1.00 seconds", e.currentDescription())
        expectError(ErrorCodes.STALE) { e.trimClip(a, 0, g0, trimLeft = 0.0) }
        e.undo()
        assertClip(e.s, a, 0, 0.0, 3.0)
        assertEquals(0.0, e.s.tr(a).clips[0].trimLeft, 1e-9)
        e.redo()
        assertClip(e.s, a, 0, 1.0, 3.0)

        // Right border, final defaults to true
        e.trimClip(a, 0, e.s.generation, trimRight = 0.5)
        assertEquals("Adjust right trim by 0.50 seconds", e.currentDescription())
        assertClip(e.s, a, 0, 1.0, 2.5)

        // Clamped to the audio; one sample stays
        r = e.trimClip(a, 0, e.s.generation, trimLeft = -5.0, trimRight = -1.0)
        assertEquals(0.0, r.trimLeft, 1e-9)
        assertEquals(0.0, r.trimRight, 1e-9)
        r = e.trimClip(a, 0, e.s.generation, trimLeft = 10.0)
        assertEquals(3.0 - 1 / rate, r.start, 1e-9)
        assertEquals(3.0, r.end, 1e-9)
        e.trimClip(a, 0, e.s.generation, trimLeft = 0.0)
        assertClip(e.s, a, 0, 0.0, 3.0)

        // A split hides audio that trimming brings back, up to the neighbour
        // (off the sample grid: robust against rounding of the clip start)
        val w0 = 1.2 + 0.3 / rate
        val w1 = 1.21 + 0.3 / rate
        val original = e.waveSamples(a, 0, w0, w1)!!.single().values
        e.splitAt(1.5, listOf(a))
        var h = e.history().states.size
        r = e.trimClip(a, 1, e.s.generation, trimLeft = 0.0)
        assertEquals(1.5, r.start, 1e-6)
        assertEquals(h, e.history().states.size)
        e.trimClip(a, 0, e.s.generation, trimRight = 2.0)
        r = e.trimClip(a, 1, e.s.generation, trimLeft = 0.0)
        assertEquals(1.0, r.start, 1e-6)
        assertClip(e.s, a, 0, 0.0, 1.0)
        assertClip(e.s, a, 1, 1.0, 3.0)
        assertEquals(h + 2, e.history().states.size)
        val recovered = e.waveSamples(a, 0, w0, w1)!!.single().values
        assertTrue(original.contentEquals(recovered))

        // A drag back to its start: no entry
        h = e.history().states.size
        e.trimClip(a, 1, e.s.generation, trimLeft = 1.4, final = false)
        assertClip(e.s, a, 1, 1.4, 3.0)
        e.trimClip(a, 1, e.s.generation, trimLeft = 1.0, final = true)
        assertClip(e.s, a, 1, 1.0, 3.0)
        assertEquals(h, e.history().states.size)

        // An unfinished live drag of another clip is cancelled by the next one
        val g = e.s.generation
        e.trimClip(a, 0, g, trimRight = 2.5, final = false)
        assertClip(e.s, a, 0, 0.0, 0.5)
        e.trimClip(a, 1, g, trimRight = 0.5, final = true)
        assertClip(e.s, a, 0, 0.0, 1.0)
        assertClip(e.s, a, 1, 1.0, 2.5)
        assertEquals(h + 1, e.history().states.size)

        expectError(ErrorCodes.INVALID_ARGS) { e.trimClip(a, 0, e.s.generation) }
        expectError(ErrorCodes.INVALID_ARGS) { e.trimClip(a, 0, e.s.generation, trimLeft = Double.NaN) }
        expectError(ErrorCodes.NOT_FOUND) { e.trimClip(a, 9, e.s.generation, trimLeft = 0.0) }
    }

    @Test
    fun addLabelAtAnExplicitPositionKeepsTheSelection() = runTest {
        val e = engine()
        e.tone(3.0)
        e.select(0.1, 0.2)
        val (lt, index) = e.addLabel("Here", 2.25)
        assertEquals(0.1, e.s.selection.t0, 1e-9)
        assertEquals(0.2, e.s.selection.t1, 1e-9)
        val label = e.s.tr(lt).labels[index]
        assertEquals("Here", label.title)
        assertEquals(2.25, label.t0, 1e-9)
        assertEquals(2.25, label.t1, 1e-9)
        assertEquals("Label", e.s.history.undo)
        val (lt2, i2) = e.addLabel("Range", 0.5, 0.8)
        assertEquals(lt, lt2)
        assertEquals(0.5, e.s.tr(lt).labels[i2].t0, 1e-9)
        assertEquals(0.8, e.s.tr(lt).labels[i2].t1, 1e-9)
        // The old overload still uses the selection
        val (_, i3) = e.addLabel("Sel")
        assertEquals(0.1, e.s.tr(lt).labels[i3].t0, 1e-9)
        expectError(ErrorCodes.INVALID_ARGS) { e.addLabel("x", 1.0, 0.5) }
        expectError(ErrorCodes.INVALID_ARGS) { e.addLabel("x", -1.0) }
        expectError(ErrorCodes.INVALID_ARGS) { e.addLabel("x", Double.NaN) }
    }

    @Test
    fun tempoPitchAndSpeedPercentsAreShownAsRatios() = runTest {
        val e = engine()
        val ids = e.effects().effects.associate { it.name to it.id }
        suspend fun check(effect: String, key: String, min: Double, max: Double, semitones: Boolean) {
            val p = e.describeEffect(ids.getValue(effect)).params.first { it.key == key }
            assertEquals("$effect $key", EffectParam.DISPLAY_RATIO, p.display)
            assertTrue(p.isRatio)
            assertEquals(min, p.min!!, 1e-9)
            assertEquals(max, p.max!!, 1e-9)
            assertEquals("$effect $key", semitones, p.semitones)
        }
        check("Change Tempo", "Percentage", -95.0, 3000.0, false)
        check("Change Pitch", "Percentage", -99.0, 3000.0, true)
        check("Change Speed and Pitch", "Percentage", -99.0, 4900.0, false)
        check("Sliding Stretch", "RatePercentChangeStart", -90.0, 500.0, false)
        check("Sliding Stretch", "PitchPercentChangeEnd", -50.0, 100.0, true)
        assertFalse(e.describeEffect(ids.getValue("Sliding Stretch")).params.first { it.key == "PitchHalfStepsStart" }.isRatio)

        // 1.25 is sent as +25 %, 0.65 as -35 %
        val tempo = ids.getValue("Change Tempo")
        val d = e.setEffectParams(tempo, mapOf("Percentage" to JsonPrimitive(EffectParam.ratioToPercent(1.25))))
        assertEquals(25.0, d.params.first { it.key == "Percentage" }.value!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(1.25, EffectParam.percentToRatio(25.0), 1e-12)
        assertEquals(-35.0, EffectParam.ratioToPercent(0.65), 1e-9)
        assertEquals(12.0, EffectParam.ratioToSemitones(2.0), 1e-9)
        assertEquals(2.0, EffectParam.semitonesToRatio(12.0), 1e-9)
        assertTrue(EffectParam.ratioToSemitones(0.0).isNaN())
    }
}
