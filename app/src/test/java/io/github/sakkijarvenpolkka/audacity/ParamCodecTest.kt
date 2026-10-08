// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import io.github.sakkijarvenpolkka.audacity.effects.ParamCodec
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint
import io.github.sakkijarvenpolkka.audacity.files.LabelFormat
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.EqAxes
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.SpectrumPlot
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.movePoint
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.withPoint
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.log10

class ParamCodecTest {
    /** The Amplify example of API.md §5.5. */
    private val desc: EffectDescription = EngineJson.json.decodeFromString(
        EffectDescription.serializer(),
        """
        { "id": "amp", "name": "Amplify", "type": "process",
          "params": [
            { "key": "Ratio", "label": "Amplification", "kind": "double",
              "min": 0.003162, "max": 316.227766, "default": 0.9, "scale": 1.0, "value": 1.25, "unit": "", "display": "dB" },
            { "key": "AllowClipping", "label": "Allow clipping", "kind": "bool", "default": false, "value": false },
            { "key": "Type", "label": "Type", "kind": "enum", "choices": ["Sine","Square","Sawtooth"],
              "choiceLabels": ["Sine","Square","Sawtooth"], "default": 0, "value": 1 },
            { "key": "Bands", "kind": "int", "min": 0, "max": 12, "default": 6, "value": 6 },
            { "key": "Text", "label": "Text", "kind": "string", "default": "", "value": "" }
          ],
          "presets": { "factory": [], "user": ["Mine"] }, "supportsDuration": true, "duration": 30.0 }
        """.trimIndent(),
    )
    private fun p(key: String): EffectParam = desc.params.first { it.key == key }

    @Test
    fun dbDisplay() {
        val ratio = p("Ratio")
        assertEquals(20 * log10(1.25), ParamCodec.toDisplay(ratio, 1.25), 1e-9)
        val range = ParamCodec.displayRange(ratio)!!
        assertEquals(-50.0, range.start, 1e-3)
        assertEquals(50.0, range.endInclusive, 1e-3)
        assertEquals(1.9952623, (ParamCodec.encodeDisplayed(ratio, 6.0) as JsonPrimitive).content.toDouble(), 1e-6)
        assertEquals(0.5011872, ParamCodec.parseDisplayed(ratio, "-6")!!, 1e-6)
        assertNull("outside the range", ParamCodec.parseDisplayed(ratio, "60"))
        assertNull(ParamCodec.parseDisplayed(ratio, "abc"))
        assertEquals("1.94", ParamCodec.formatDisplayed(ratio, 1.25))
        assertEquals(ParamCodec.DB_FLOOR, ParamCodec.toDisplay(ratio, 0.0), 0.0)
        assertEquals("Amplification", ParamCodec.label(ratio))
        assertEquals("Bands", ParamCodec.label(p("Bands")))
    }

    @Test
    fun enumIndices() {
        val type = p("Type")
        assertEquals(1, ParamCodec.enumIndex(type, type.value))
        assertEquals(2, ParamCodec.enumIndex(type, JsonPrimitive("Sawtooth")))
        assertEquals(2, ParamCodec.enumIndex(type, JsonPrimitive(7)))
        assertEquals(JsonPrimitive(0), ParamCodec.encodeEnum(0))
        assertEquals(listOf("Sine", "Square", "Sawtooth"), ParamCodec.choices(type))
    }

    @Test
    fun intsAreClampedAndRounded() {
        val bands = p("Bands")
        assertEquals(JsonPrimitive(12L), ParamCodec.encodeNumber(bands, 12.7))
        assertEquals(JsonPrimitive(3L), ParamCodec.encodeNumber(bands, 3.4))
        assertEquals(JsonPrimitive(0L), ParamCodec.encodeNumber(bands, -2.0))
        assertNull("ints reject fractions", ParamCodec.parseDisplayed(bands, "2.5"))
    }

    @Test
    fun paramsForNormalisesTypes() {
        val edits = mapOf("AllowClipping" to JsonPrimitive(1), "Type" to JsonPrimitive("Square"), "Bands" to JsonPrimitive(4.0))
        val params = ParamCodec.paramsFor(desc, edits)
        assertEquals(JsonPrimitive(true), params["AllowClipping"])
        assertEquals(JsonPrimitive(1), params["Type"])
        assertEquals(JsonPrimitive(4L), params["Bands"])
        assertEquals(JsonPrimitive(1.25), params["Ratio"])
        assertEquals(JsonPrimitive(""), params["Text"])
    }

    @Test
    fun generatorDuration() {
        assertEquals(30.0, ParamCodec.parseDuration("00:00:30.000")!!, 0.0)
        assertEquals(90.0, ParamCodec.parseDuration("1:30")!!, 0.0)
        assertEquals(90.5, ParamCodec.parseDuration("90.5")!!, 0.0)
        assertEquals(3723.25, ParamCodec.parseDuration("01:02:03.250")!!, 1e-9)
        assertEquals(61.5, ParamCodec.parseDuration("00 h 01 m 01.500 s")!!, 1e-9)
        assertNull(ParamCodec.parseDuration("0"))
        assertNull(ParamCodec.parseDuration("1:75"))
        assertNull(ParamCodec.parseDuration("x"))
        assertEquals("00:01:30.500", ParamCodec.formatDuration(90.5))
        assertEquals("00:00:30.000", ParamCodec.formatDuration(desc.duration))
    }

    @Test
    fun timeCodec() {
        assertEquals("01:00:00.000", TimeCodec.format(3600.0))
        assertEquals("00:00:01", TimeCodec.format(1.4, withMillis = false))
        assertEquals("--:--:--.---", TimeCodec.format(Double.NaN))
        assertEquals("1.5 MB", TimeCodec.formatBytes(1572864))
        assertEquals("0.25", TimeCodec.number(0.25))
    }

    @Test
    fun eqCurveEditing() {
        var c = EqCurve()
        c = c.withPoint(EqPoint(1000.0, 3.0)).withPoint(EqPoint(100.0, -6.0)).withPoint(EqPoint(10000.0, 0.0))
        assertEquals(listOf(100.0, 1000.0, 10000.0), c.points.map { it.f })
        c = c.movePoint(1, 50.0, 4.0)   // cannot pass its left neighbour
        assertEquals(100.0 * 1.001, c.points[1].f, 1e-9)
        assertEquals(4.0, c.points[1].dB, 0.0)
        val a = EqAxes(1000f, 200f, linear = false, dbMin = -30.0, dbMax = 30.0)
        assertEquals(0f, a.x(20.0), 1e-3f)
        assertEquals(1000f, a.x(20000.0), 1e-3f)
        assertEquals(1000.0, a.f(a.x(1000.0)), 1e-2)
        assertEquals(100f, a.y(0.0), 1e-3f)
        assertEquals(30.0, a.db(0f), 1e-9)
    }

    @Test
    fun spectrumHelpers() {
        assertEquals("A4", SpectrumPlot.pitchName(440.0))
        assertEquals("C4", SpectrumPlot.pitchName(261.63))
        assertEquals(3, SpectrumPlot.peakNear(listOf(0f, 1f, 2f, 5f, 1f), 1, 2))
        assertEquals(listOf(128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072), SpectrumPlot.SIZES)
        assertEquals("cepstrum", SpectrumPlot.ALGORITHMS.last())
        assertEquals(SpectrumPlot.ALGORITHMS.size, SpectrumPlot.ALGORITHM_LABELS.size)
    }

    @Test
    fun labelFormatsMatchTheEngineKeys() {
        // labels.export formats of API.md §3.3 and the 3.7.9 file types
        assertEquals(listOf("text", "subrip", "webvtt", "podcastChapters"), LabelFormat.entries.map { it.key })
        assertEquals(listOf("labels.txt", "labels.srt", "labels.vtt", "labels.json"), LabelFormat.entries.map { it.fileName })
        assertEquals(LabelFormat.SUBRIP, LabelFormat.ofKey("subrip"))
        assertEquals(LabelFormat.TEXT, LabelFormat.ofKey("bogus"))
    }
}
