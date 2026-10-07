// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sakkijarvenpolkka.audacity.engine.model.EngineJson
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ExportOptionRows
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sample `export.options` payloads in the format of API.md §5.6 / import-export-project.md §2.3. */
object ExportSamples {
    val MP3 = """
    { "format": { "key": "MP3 Files", "description": "MP3 Files", "extensions": ["mp3"], "maxChannels": 2, "canMetaData": true },
      "sampleRates": [8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000],
      "options": [
        { "id": 0, "title": "Bit Rate Mode", "type": "enum", "readOnly": false, "hidden": false,
          "value": {"t":"s","v":"SET"},
          "values": [{"t":"s","v":"SET"}, {"t":"s","v":"VBR"}, {"t":"s","v":"ABR"}, {"t":"s","v":"CBR"}],
          "names": ["Preset", "Variable", "Average", "Constant"] },
        { "id": 1, "title": "Quality", "type": "enum", "value": {"t":"i","v":2},
          "values": [{"t":"i","v":0}, {"t":"i","v":1}, {"t":"i","v":2}, {"t":"i","v":3}],
          "names": ["Excessive, 320 kbps", "Extreme, 220-260 kbps", "Standard, 170-210 kbps", "Medium, 145-185 kbps"] },
        { "id": 2, "title": "Quality", "type": "enum", "hidden": true, "value": {"t":"i","v":2},
          "values": [{"t":"i","v":0}, {"t":"i","v":1}, {"t":"i","v":2}], "names": ["a", "b", "c"] }
      ] }
    """.trimIndent()

    val OTHER = """
    { "format": { "key": "Ogg Vorbis Files", "description": "Ogg Vorbis Files", "extensions": ["ogg"], "maxChannels": 255 },
      "sampleRates": [],
      "options": [
        { "id": 0, "title": "Quality", "type": "range", "value": {"t":"i","v":5}, "values": [{"t":"i","v":0}, {"t":"i","v":10}] },
        { "id": 7, "title": "Hybrid Mode", "type": "bool", "value": {"t":"b","v":false} },
        { "id": 8, "title": "Create Correction(.wvc) File", "type": "bool", "readOnly": true, "value": {"t":"b","v":false} },
        { "id": 9, "title": "Comment", "type": "string", "value": {"t":"s","v":"hi"} },
        { "id": 10, "title": "Level", "type": "double", "value": {"t":"d","v":0.5} }
      ] }
    """.trimIndent()

    fun decode(json: String): ExportOptions = EngineJson.json.decodeFromString(ExportOptions.serializer(), json)
}

class ExportModelTest {
    @Test
    fun enumRowsKeepNamesSelectionAndTags() {
        val rows = ExportModel.rows(ExportSamples.decode(ExportSamples.MP3))
        assertEquals("hidden options are not rendered", 2, rows.size)
        val mode = rows[0] as ExportModel.EnumRow
        assertEquals("Bit Rate Mode", mode.title)
        assertEquals(listOf("Preset", "Variable", "Average", "Constant"), mode.names)
        assertEquals(0, mode.selected)
        assertEquals(ExportValue("s", JsonPrimitive("VBR")), ExportModel.enumValue(mode.option, 1))
        val quality = rows[1] as ExportModel.EnumRow
        assertEquals(2, quality.selected)
        assertEquals(ExportValue("i", JsonPrimitive(3)), ExportModel.enumValue(quality.option, 3))
    }

    @Test
    fun plainRowsByValueTag() {
        val rows = ExportModel.rows(ExportSamples.decode(ExportSamples.OTHER))
        val range = rows[0] as ExportModel.RangeRow
        assertEquals(0.0, range.min, 0.0)
        assertEquals(10.0, range.max, 0.0)
        assertEquals(5.0, range.value, 0.0)
        assertTrue(range.isInt)
        assertEquals(ExportValue("i", JsonPrimitive(7L)), ExportModel.numberValue(range.option, 7.2))
        val hybrid = rows[1] as ExportModel.BoolRow
        assertFalse(hybrid.value)
        assertEquals(ExportValue("b", JsonPrimitive(true)), ExportModel.boolValue(hybrid.option, true))
        assertFalse("read-only option is disabled", rows[2].enabled)
        assertEquals("hi", (rows[3] as ExportModel.TextRow).value)
        val level = rows[4] as ExportModel.NumberRow
        assertFalse(level.isInt)
        assertEquals(ExportValue("d", JsonPrimitive(0.25)), ExportModel.numberValue(level.option, 0.25))
    }

    @Test
    fun sampleRateRuleOfExportFilePanel() {
        val mp3 = ExportSamples.decode(ExportSamples.MP3)
        val rates = ExportModel.rateChoices(mp3, null)
        assertEquals(48000, rates.last())
        assertEquals(44100, ExportModel.pickRate(44100, rates))
        assertEquals(48000, ExportModel.pickRate(46000, rates))   // smallest larger rate
        assertEquals(48000, ExportModel.pickRate(96000, rates))   // else the largest
        val any = ExportSamples.decode(ExportSamples.OTHER)
        assertEquals(ExportModel.DEFAULT_RATES, ExportModel.rateChoices(any, null))
        assertEquals(listOf(22050, 44100), ExportModel.rateChoices(any, ExportDefaults(rates = listOf(44100, 22050))))
    }

    @Test
    fun channelsAndFileNames() {
        val mono = ExportFormat("X", maxChannels = 1, extensions = listOf("mp2"))
        assertEquals(listOf(1), ExportModel.channelChoices(mono, ExportDefaults()))
        assertEquals(1, ExportModel.defaultChannels(mono, ExportDefaults(defaultChannels = 2)))
        val mp3 = ExportFormat("MP3 Files", extensions = listOf("mp3"))
        assertEquals("My Song.mp3", ExportModel.fileName("My Song", mp3))
        assertEquals("a_b.mp3", ExportModel.fileName("a/b", mp3))
        assertEquals("take.mp3", ExportModel.withExtension("take.wav", mp3))
        assertEquals("audio/mpeg", ExportModel.mimeType(mp3))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportOptionRowsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersGenericOptionsAndSendsTaggedValues() {
        val opts = ExportSamples.decode(ExportSamples.MP3)
        var sent: Pair<Int, ExportValue>? = null
        compose.setContent {
            MaterialTheme { Column { ExportOptionRows(opts) { o, v -> sent = o.id to v } } }
        }
        compose.onNodeWithText("Bit Rate Mode").assertExists()
        compose.onNodeWithText("Preset").assertExists()
        compose.onNodeWithText("Standard, 170-210 kbps").assertExists()
        compose.onAllNodesWithText("Quality").assertCountEquals(1)
        compose.onNodeWithText("Preset").performClick()
        compose.onNodeWithText("Variable").performClick()
        compose.runOnIdle { assertEquals(0 to ExportValue("s", JsonPrimitive("VBR")), sent) }
    }

    @Test
    fun rendersRangeBoolAndTextOptions() {
        val opts = ExportSamples.decode(ExportSamples.OTHER)
        compose.setContent { MaterialTheme { Column { ExportOptionRows(opts) { _, _ -> } } } }
        compose.onNodeWithText("Quality").assertExists()
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithText("Hybrid Mode").assertExists()
        compose.onNodeWithText("Comment").assertExists()
    }
}
