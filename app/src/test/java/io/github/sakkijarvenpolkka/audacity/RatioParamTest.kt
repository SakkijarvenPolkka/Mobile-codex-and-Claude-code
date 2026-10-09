// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import io.github.sakkijarvenpolkka.audacity.effects.ParamCodec
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tempo / pitch / speed as multipliers (`display:"ratio"`, user request 4): ×1.25 ⇔ +25 %. */
class RatioParamTest {
    /** Change Tempo as the engine describes it (ChangeTempoBase.h: Percentage −95 … 3000). */
    private val tempo: EffectDescription = EngineJson.json.decodeFromString(
        EffectDescription.serializer(),
        """
        { "id": "Effect_Audacity_Audacity_Change Tempo_Built-in Effect: Change Tempo", "name": "Change Tempo", "type": "process",
          "params": [
            { "key": "Percentage", "label": "Percent change", "kind": "double", "min": -95.0, "max": 3000.0,
              "default": 0.0, "value": 0.0, "unit": "%", "display": "ratio" },
            { "key": "SBSMS", "label": "Use high quality stretching (slow)", "kind": "bool", "default": false, "value": false }
          ] }
        """.trimIndent(),
    )
    private val percent: EffectParam = tempo.params.first()

    private fun param(min: Double, max: Double, semitones: Boolean = false) =
        EffectParam("Percentage", "Percent change", "double", min, max, display = EffectParam.DISPLAY_RATIO, semitones = semitones)

    private fun sent(v: kotlinx.serialization.json.JsonElement): Double = v.jsonPrimitive.double

    @Test
    fun ratioToPercentIsExact() {
        assertTrue(ParamCodec.isRatio(percent))
        assertEquals(25.0, ParamCodec.ratioToPercent(1.25), 0.0)
        assertEquals(-35.0, ParamCodec.ratioToPercent(0.65), 0.0)
        assertEquals(35.0, ParamCodec.ratioToPercent(1.35), 0.0)
        assertEquals(100.0, ParamCodec.ratioToPercent(2.0), 0.0)
        assertEquals(-50.0, ParamCodec.ratioToPercent(0.5), 0.0)
        assertEquals(0.0, ParamCodec.ratioToPercent(1.0), 0.0)
        // Plain floating point would give 35.00000000000001 here
        assertEquals(35.0, sent(ParamCodec.encodeRatio(percent, 1.35)), 0.0)
        assertEquals(-35.0, sent(ParamCodec.encodeRatio(percent, 0.65)), 0.0)
        assertEquals(25.0, sent(ParamCodec.encodeRatio(percent, 1.25)), 0.0)
    }

    @Test
    fun percentToRatio() {
        assertEquals(1.25, ParamCodec.percentToRatio(25.0), 0.0)
        assertEquals(0.65, ParamCodec.percentToRatio(-35.0), 0.0)
        assertEquals(1.35, ParamCodec.percentToRatio(35.0), 0.0)
        assertEquals("1.25", ParamCodec.formatDisplayed(percent, 25.0))
        assertEquals("0.65", ParamCodec.formatDisplayed(percent, -35.0))
        assertEquals("1.0", ParamCodec.formatDisplayed(percent, 0.0))
        assertEquals("2.0", ParamCodec.formatRatio(2.0))
        assertEquals("1.3333", ParamCodec.formatRatio(4.0 / 3.0))
        assertEquals(1.25, ParamCodec.toDisplay(percent, 25.0), 0.0)
        assertEquals(25.0, ParamCodec.fromDisplay(percent, 1.25), 0.0)
    }

    @Test
    fun parsingAcceptsCommaMultiplySignAndPercent() {
        assertEquals(1.25, ParamCodec.parseRatio("1,25")!!, 0.0)
        assertEquals(1.25, ParamCodec.parseRatio("×1.25")!!, 0.0)
        assertEquals(1.25, ParamCodec.parseRatio("× 1,25")!!, 0.0)
        assertEquals(1.35, ParamCodec.parseRatio("x1.35")!!, 0.0)
        assertEquals(1.35, ParamCodec.parseRatio("1.35x")!!, 0.0)
        assertEquals(0.65, ParamCodec.parseRatio(" 0.65 ")!!, 0.0)
        assertEquals(0.65, ParamCodec.parseRatio(".65")!!, 0.0)
        assertEquals(2.0, ParamCodec.parseRatio("2")!!, 0.0)
        // A percent change typed with its sign is converted
        assertEquals(1.25, ParamCodec.parseRatio("+25%")!!, 0.0)
        assertEquals(0.65, ParamCodec.parseRatio("-35 %")!!, 0.0)
        for (bad in listOf("", "abc", "0", "-1.2", "1.2.3", "×", "1e5", "1,2,5", "-100%")) {
            assertNull("'$bad'", ParamCodec.parseRatio(bad))
        }
        // The generic parser of the dialog gives the engine value (percent)
        assertEquals(25.0, ParamCodec.parseDisplayed(percent, "1,25")!!, 0.0)
        assertEquals(-35.0, ParamCodec.parseDisplayed(percent, "×0.65")!!, 0.0)
        assertNull("above ×31", ParamCodec.parseDisplayed(percent, "50"))
        assertNull("below ×0.05", ParamCodec.parseDisplayed(percent, "0.01"))
    }

    @Test
    fun clampingToMinAndMax() {
        val range = ParamCodec.ratioRange(percent)
        assertEquals(0.05, range.start, 1e-12)
        assertEquals(31.0, range.endInclusive, 1e-12)
        assertTrue(ParamCodec.ratioInRange(percent, 31.0))
        assertFalse(ParamCodec.ratioInRange(percent, 31.5))
        assertEquals(3000.0, sent(ParamCodec.encodeRatio(percent, 100.0)), 0.0)
        assertEquals(-95.0, sent(ParamCodec.encodeRatio(percent, 0.01)), 0.0)
        // ± 0.05 steps stay in the range and on the 0.01 grid
        assertEquals(1.3, ParamCodec.stepRatio(percent, 1.25, ParamCodec.RATIO_STEP), 0.0)
        assertEquals(1.2, ParamCodec.stepRatio(percent, 1.25, -ParamCodec.RATIO_STEP), 0.0)
        assertEquals(0.05, ParamCodec.stepRatio(percent, 0.07, -ParamCodec.RATIO_STEP), 0.0)
        assertEquals(31.0, ParamCodec.stepRatio(percent, 30.99, ParamCodec.RATIO_STEP), 0.0)
        val narrow = param(-50.0, 100.0)
        assertEquals(2.0, ParamCodec.stepRatio(narrow, 2.0, ParamCodec.RATIO_STEP), 0.0)
        assertEquals(0.5, ParamCodec.stepRatio(narrow, 0.5, -ParamCodec.RATIO_STEP), 0.0)
    }

    @Test
    fun logSliderCoversQuarterToFourTimes() {
        val r = ParamCodec.ratioSliderRange(percent)!!
        assertEquals(0.25, r.start, 0.0)
        assertEquals(4.0, r.endInclusive, 0.0)
        assertEquals(0.5f, ParamCodec.ratioToSlider(1.0, r), 1e-6f)
        assertEquals(0.25, ParamCodec.sliderToRatio(0f, r), 0.0)
        assertEquals(4.0, ParamCodec.sliderToRatio(1f, r), 0.0)
        assertEquals(1.0, ParamCodec.sliderToRatio(0.5f, r), 0.0)
        // Every 0.01 multiplier survives a slider round trip
        for (x in listOf(0.65, 1.25, 1.35, 2.0)) {
            assertEquals(x, ParamCodec.sliderToRatio(ParamCodec.ratioToSlider(x, r), r), 0.0)
        }
        // Outside the slider (the text field allows ×0.05 … ×31): the thumb stays at the end
        assertEquals(1f, ParamCodec.ratioToSlider(10.0, r), 0f)
        // Sliding Stretch's pitch (−50 … +100 %): ×0.5 … ×2
        val narrow = ParamCodec.ratioSliderRange(param(-50.0, 100.0))!!
        assertEquals(0.5, narrow.start, 0.0)
        assertEquals(2.0, narrow.endInclusive, 0.0)
        assertNull("no room for a slider", ParamCodec.ratioSliderRange(param(500.0, 600.0)))
    }

    @Test
    fun presetsSemitonesAndPercentHints() {
        assertEquals(ParamCodec.RATIO_PRESETS, ParamCodec.ratioPresets(percent))
        assertEquals(listOf(0.5, 0.65, 0.75, 0.8, 0.9, 1.1, 1.2, 1.25, 1.35, 1.5), ParamCodec.ratioPresets(param(-60.0, 50.0)))
        assertEquals("+3.86", ParamCodec.formatSemitones(ParamCodec.semitones(1.25)))
        assertEquals("-12.00", ParamCodec.formatSemitones(ParamCodec.semitones(0.5)))
        assertEquals("+12.00", ParamCodec.formatSemitones(ParamCodec.semitones(2.0)))
        assertEquals("0.00", ParamCodec.formatSemitones(ParamCodec.semitones(1.0)))
        assertEquals("-7.46", ParamCodec.formatSemitones(ParamCodec.semitones(0.65)))
        assertEquals("+25", ParamCodec.formatPercentChange(1.25))
        assertEquals("-35", ParamCodec.formatPercentChange(0.65))
        assertEquals("0", ParamCodec.formatPercentChange(1.0))
    }

    @Test
    fun paramsSentToTheEngineArePercent() {
        val edits = mapOf("Percentage" to ParamCodec.encodeRatio(percent, 1.35))
        val params = ParamCodec.paramsFor(tempo, edits)
        assertEquals(JsonPrimitive(35.0), params["Percentage"])
        assertEquals(JsonPrimitive(false), params["SBSMS"])
        assertNotNull(params["SBSMS"])
    }
}
