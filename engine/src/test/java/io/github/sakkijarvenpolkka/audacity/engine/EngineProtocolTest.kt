/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EngineProtocolTest {

    /** The §4.2 example of API.md plus a field the app does not know yet. */
    private val snapshotJson = """
        {
          "generation": 42,
          "project": { "open": true, "name": "Untitled", "path": null, "temporary": true, "dirty": true,
                       "rate": 44100, "defaultFormat": "float" },
          "tracks": [
            { "id": 3, "kind": "wave", "name": "Audio 1", "selected": true, "focused": true,
              "channels": 2, "rate": 44100, "format": "float",
              "gain": 1.0, "pan": 0.0, "mute": false, "solo": false,
              "start": 0.0, "end": 12.5, "waveVersion": 9182736455,
              "clips": [ { "index": 0, "name": "Audio 1 #1", "start": 0.0, "end": 12.5,
                           "trimLeft": 0.0, "trimRight": 0.0, "stretchRatio": 1.0, "rate": 44100 } ],
              "labels": [] },
            { "id": 7, "kind": "label", "name": "Label 1", "selected": false, "focused": false,
              "labels": [ { "index": 0, "t0": 1.0, "t1": 2.5, "title": "Intro" } ] }
          ],
          "selection": { "t0": 1.0, "t1": 4.0 },
          "playRegion": { "active": false, "t0": 0.0, "t1": 0.0 },
          "history": { "canUndo": true, "canRedo": false, "undo": "Cut", "redo": "" },
          "view": { "zoom": 86.1328125, "hpos": 0.0 },
          "audio": { "busy": false },
          "clipboard": { "empty": false, "duration": 3.0 },
          "lastEffect": { "id": "Effect_Audacity_Audacity_Amplify_Built-in Effect: Amplify", "name": "Amplify" },
          "lastGenerator": null, "lastAnalyzer": null, "lastTool": null,
          "flags": 59203501053,
          "futureField": { "nested": [1, 2, 3] }
        }
    """.trimIndent()

    @Test
    fun decodesSuccessEnvelope() {
        val env = EngineProtocol.decodeEnvelope(
            """{"ok":true,"result":{"open":true,"name":"Song","path":"/x/Song.aup3","temporary":false,"dirty":false,
               "rate":48000,"defaultFormat":"int24","durationSec":12.5,"tracks":3},"generation":42}""".encodeToByteArray(),
        )
        assertTrue(env.ok)
        assertEquals(42L, env.generation)
        val info = EngineProtocol.decodeResult<ProjectInfo>("project.info", EngineProtocol.unwrap(env))
        assertEquals("Song", info.name)
        assertEquals(48000.0, info.rate, 0.0)
        assertEquals(3, info.tracks)
    }

    @Test
    fun errorEnvelopeThrowsWithCodeAndMessage() {
        val env = EngineProtocol.decodeEnvelope(
            """{"ok":false,"error":{"code":"AUDIO_BUSY","message":"stop playback first"},"generation":7}""".encodeToByteArray(),
        )
        try {
            EngineProtocol.unwrap(env)
            fail("expected an exception")
        } catch (e: EngineException) {
            assertEquals(ErrorCodes.AUDIO_BUSY, e.code)
            assertEquals("stop playback first", e.message)
        }
    }

    @Test
    fun malformedEnvelopeBecomesInternalError() {
        val env = EngineProtocol.decodeEnvelope("not json".encodeToByteArray())
        assertFalse(env.ok)
        assertEquals(ErrorCodes.INTERNAL, env.error?.code)
        val missingOk = EngineProtocol.decodeEnvelope("""{"result":{}}""".encodeToByteArray())
        assertEquals(ErrorCodes.INTERNAL, missingOk.error?.code)
    }

    @Test
    fun missingResultIsEmptyObject() {
        val r = EngineProtocol.unwrap(EngineProtocol.decodeEnvelope("""{"ok":true,"generation":1}""".encodeToByteArray()))
        assertEquals(JsonObject(emptyMap()), r)
    }

    @Test
    fun resultSchemaMismatchIsInternal() {
        try {
            EngineProtocol.decodeResult<ProjectInfo>("project.info", JsonPrimitive(3))
            fail("expected an exception")
        } catch (e: EngineException) {
            assertEquals(ErrorCodes.INTERNAL, e.code)
        }
    }

    @Test
    fun decodesApiSnapshotExample() {
        val s = EngineJson.json.decodeFromString(Snapshot.serializer(), snapshotJson)
        assertEquals(42L, s.generation)
        assertTrue(s.project.open)
        assertNull(s.project.path)
        assertEquals(2, s.tracks.size)
        val wave = s.tracks[0]
        assertTrue(wave.isWave)
        assertEquals(2, wave.channels)
        assertEquals(9182736455L, wave.waveVersion)
        assertEquals("Audio 1 #1", wave.clips.single().name)
        val label = s.track(7)!!
        assertTrue(label.isLabel)
        assertEquals("Intro", label.labels.single().title)
        assertEquals(1.0, s.selection.t0, 0.0)
        assertTrue(s.hasTimeSelection)
        assertEquals("Cut", s.history.undo)
        assertEquals("Amplify", s.lastEffect?.name)
        assertNull(s.lastGenerator)
        assertEquals(12.5, s.projectEnd, 0.0)
        assertEquals(listOf(wave), s.selectedTracks)
        // 59203501053 = PROJECT_OPEN|CLIPBOARD|FOC|NO_TIMETRACK|PLAYABLE|TFOCUS|LAST_EFF|HW|CC|CS|ST|NSL|WE|ZO|ZI|UA|LE|CNB|AS|ES|TE|WS|TS|NB
        assertTrue(s.has(CommandFlags.NB or CommandFlags.TS or CommandFlags.WS))
        assertTrue(s.has(CommandFlags.PROJECT_OPEN or CommandFlags.CLIPBOARD))
        assertFalse(s.has(CommandFlags.RA))
        assertFalse(s.enabled(CommandFlags.RECORD_PERMISSION))
        assertTrue(s.enabled(CommandFlags.CC or CommandFlags.UA))
    }

    @Test
    fun decodesEffectDescriptionOfApiExample() {
        val json = """
            { "id": "x", "name": "Amplify", "type": "process",
              "params": [
                { "key": "Ratio", "label": "Amplification", "kind": "double",
                  "min": 0.003162, "max": 316.227766, "default": 0.9, "scale": 1.0, "value": 1.25, "unit": "", "display": "dB" },
                { "key": "AllowClipping", "label": "Allow clipping", "kind": "bool", "default": false, "value": false },
                { "key": "Type", "label": "Type", "kind": "enum", "choices": ["Sine","Square"], "choiceLabels": ["Sine","Square"], "default": 0, "value": 1 },
                { "key": "Text", "label": "Text", "kind": "string", "default": "", "value": "" }
              ],
              "presets": { "factory": ["A"], "user": [] },
              "supportsDuration": false, "duration": 30.0, "special": null, "nyquist": null, "help": "Amplify" }
        """.trimIndent()
        val d = EngineJson.json.decodeFromString(EffectDescription.serializer(), json)
        assertEquals(4, d.params.size)
        assertEquals(1.25, d.params[0].value!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.9, d.params[0].defaultValue!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("dB", d.params[0].display)
        assertEquals(listOf("A"), d.presets.factory)
    }

    @Test
    fun decodesExportOptionsWithTaggedValues() {
        val json = """
            { "format": { "key": "MP3 Files", "description": "MP3 Files", "extensions": ["mp3"], "maxChannels": 2, "canMetaData": false },
              "sampleRates": [8000, 44100],
              "options": [ { "id": 0, "title": "Bit Rate Mode", "type": "enum", "readOnly": false, "hidden": false,
                             "value": {"t":"s","v":"SET"}, "values": [{"t":"s","v":"SET"},{"t":"s","v":"CBR"}],
                             "names": ["Preset","Constant"] } ] }
        """.trimIndent()
        val o = EngineJson.json.decodeFromString(ExportOptions.serializer(), json)
        assertEquals("s", o.options[0].value!!.t)
        assertEquals("SET", o.options[0].value!!.v.jsonPrimitive.content)
        assertEquals(listOf(8000, 44100), o.sampleRates)
    }

    @Test
    fun argsBuilderOmitsNullsAndEncodesCollections() {
        val a = EngineProtocol.args("t0" to 1.5, "t1" to null, "ids" to listOf(1L, 2L), "m" to mapOf("x" to true), "s" to "é🎵")
        assertEquals(setOf("t0", "ids", "m", "s"), a.keys)
        assertEquals("""{"t0":1.5,"ids":[1,2],"m":{"x":true},"s":"é🎵"}""", a.toString())
        // Standard UTF-8 (not JNI's modified UTF-8) for supplementary characters
        val bytes = EngineProtocol.encodeArgs(a)
        assertEquals(a.toString(), bytes.decodeToString())
        assertTrue(bytes.size > a.toString().length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun argsRejectNonFiniteNumbers() {
        EngineProtocol.args("t" to Double.NaN)
    }

    @Test
    fun partialSettingsEncodeOnlySetFields() {
        val text = EngineJson.json.encodeToString(Settings.serializer(), Settings(recordChannels = 2, soloMode = "Multi"))
        assertEquals("""{"recordChannels":2,"soloMode":"Multi"}""", text)
        val back = EngineJson.json.parseToJsonElement(text).jsonObject
        assertEquals(2, back.size)
    }

    @Test
    fun waveSamplesRoundTrip() {
        val runs = listOf(
            SampleRun(0, 1.25, 1 / 44100.0, floatArrayOf(0.1f, -0.2f, 0.3f), floatArrayOf(1f, 1f, 0.5f)),
            SampleRun(3, 10.0, 1 / 48000.0, floatArrayOf(), floatArrayOf()),
        )
        val decoded = EngineProtocol.decodeWaveSamples(EngineProtocol.encodeWaveSamples(runs))!!
        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].clipIndex)
        assertEquals(1.25, decoded[0].firstSampleTime, 0.0)
        assertEquals(1 / 44100.0, decoded[0].samplePeriod, 0.0)
        assertArrayEquals(floatArrayOf(0.1f, -0.2f, 0.3f), decoded[0].values, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 0.5f), decoded[0].envelope, 0f)
        assertEquals(3, decoded[1].clipIndex)
        assertEquals(0, decoded[1].values.size)
    }

    @Test
    fun waveSamplesLittleEndianLayout() {
        val bytes = java.nio.ByteBuffer.allocate(4 + 4 + 8 + 8 + 4 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(2).putDouble(0.5).putDouble(0.25).putInt(1).putFloat(-1f).putFloat(0.75f).array()
        val r = EngineProtocol.decodeWaveSamples(bytes)!!.single()
        assertEquals(2, r.clipIndex)
        assertEquals(-1f, r.values[0], 0f)
        assertEquals(0.75f, r.envelope[0], 0f)
    }

    @Test
    fun truncatedWaveSamplesAreRejected() {
        val full = EngineProtocol.encodeWaveSamples(listOf(SampleRun(0, 0.0, 1.0, FloatArray(10), FloatArray(10))))
        assertNull(EngineProtocol.decodeWaveSamples(full.copyOf(full.size - 4)))
        assertNull(EngineProtocol.decodeWaveSamples(ByteArray(2)))
        assertNull(EngineProtocol.decodeWaveSamples(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(-1).array()))
    }
}
