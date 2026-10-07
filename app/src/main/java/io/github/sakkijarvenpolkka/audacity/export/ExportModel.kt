/*
 * Audacity Android port — export dialog model.
 *
 * Renders the generic option snapshot of `export.options` (API.md §5.6,
 * import-export-project.md §2.3) as rows, and ports the sample-rate rule of
 * ExportFilePanel::UpdateSampleRateList (src/export/ExportFilePanel.cpp,
 * Audacity 3.7.9, the Audacity Team, GPL-2.0-or-later).
 *
 * Values keep the tag they were received with: option editors reject type
 * mismatches (FLAC bit depth and MP3 mode are strings).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.export

import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOption
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

object ExportModel {

    sealed interface Row {
        val option: ExportOption
        val title: String
        val enabled: Boolean
    }

    data class EnumRow(override val option: ExportOption, override val title: String, override val enabled: Boolean,
                       val names: List<String>, val selected: Int) : Row

    data class RangeRow(override val option: ExportOption, override val title: String, override val enabled: Boolean,
                        val min: Double, val max: Double, val value: Double, val isInt: Boolean) : Row

    data class BoolRow(override val option: ExportOption, override val title: String, override val enabled: Boolean,
                       val value: Boolean) : Row

    data class NumberRow(override val option: ExportOption, override val title: String, override val enabled: Boolean,
                         val value: Double, val isInt: Boolean) : Row

    data class TextRow(override val option: ExportOption, override val title: String, override val enabled: Boolean,
                       val value: String) : Row

    /** Visible rows (hidden options stay in the engine's parameters but are not shown). */
    fun rows(options: ExportOptions): List<Row> = options.options.filter { !it.hidden }.map { row(it) }

    fun row(o: ExportOption): Row {
        val enabled = !o.readOnly
        val title = o.title.ifEmpty { "#${o.id}" }
        return when (o.type) {
            "enum" -> {
                val names = if (o.names.size == o.values.size) o.names else o.values.map { display(it) }
                val sel = o.values.indexOfFirst { same(it, o.value) }.coerceAtLeast(0)
                EnumRow(o, title, enabled, names, sel)
            }
            "range" -> {
                val lo = o.values.getOrNull(0)?.let { num(it) } ?: 0.0
                val hi = o.values.getOrNull(1)?.let { num(it) } ?: lo
                val v = o.value?.let { num(it) } ?: lo
                RangeRow(o, title, enabled, lo, hi, v.coerceIn(lo, maxOf(lo, hi)), o.value?.t != "d")
            }
            else -> when (o.value?.t ?: tagForType(o.type)) {
                "b" -> BoolRow(o, title, enabled, o.value?.let { bool(it) } ?: false)
                "i" -> NumberRow(o, title, enabled, o.value?.let { num(it) } ?: 0.0, true)
                "d" -> NumberRow(o, title, enabled, o.value?.let { num(it) } ?: 0.0, false)
                else -> TextRow(o, title, enabled, o.value?.let { display(it) } ?: "")
            }
        }
    }

    private fun tagForType(type: String) = when (type) {
        "bool" -> "b"
        "int" -> "i"
        "double" -> "d"
        else -> "s"
    }

    fun num(v: ExportValue): Double {
        val p = runCatching { v.v.jsonPrimitive }.getOrNull() ?: return 0.0
        return p.doubleOrNull ?: p.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: 0.0
    }

    fun bool(v: ExportValue): Boolean {
        val p = runCatching { v.v.jsonPrimitive }.getOrNull() ?: return false
        return p.booleanOrNull ?: p.doubleOrNull?.let { it != 0.0 } ?: false
    }

    fun display(v: ExportValue): String = runCatching { v.v.jsonPrimitive.content }.getOrDefault("")

    /** Same tagged value (numbers compared numerically). */
    fun same(a: ExportValue, b: ExportValue?): Boolean {
        if (b == null || a.t != b.t) return false
        return when (a.t) {
            "i", "d" -> num(a) == num(b)
            "b" -> bool(a) == bool(b)
            else -> display(a) == display(b)
        }
    }

    // ---- values sent back with export.setOption -------------------------------

    fun enumValue(o: ExportOption, index: Int): ExportValue = o.values[index.coerceIn(0, o.values.lastIndex)]

    fun numberValue(o: ExportOption, v: Double): ExportValue {
        val tag = o.value?.t ?: (o.values.firstOrNull()?.t ?: "d")
        return if (tag == "i") ExportValue("i", JsonPrimitive(v.roundToLong())) else ExportValue("d", JsonPrimitive(v))
    }

    fun boolValue(o: ExportOption, v: Boolean): ExportValue = ExportValue(o.value?.t ?: "b", JsonPrimitive(v))

    fun textValue(o: ExportOption, v: String): ExportValue = ExportValue(o.value?.t ?: "s", JsonPrimitive(v))

    // ---- range, channels, rate (ExportFilePanel.cpp) ---------------------------

    /** DefaultRates of ExportFilePanel.cpp:34-48. */
    val DEFAULT_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

    /** Rates offered for a format: its list, else the engine's default list, else [DEFAULT_RATES]. */
    fun rateChoices(options: ExportOptions?, defaults: ExportDefaults?): List<Int> {
        val list = options?.sampleRates?.takeIf { it.isNotEmpty() }
            ?: defaults?.rates?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_RATES
        return list.distinct().sorted()
    }

    /** The wanted rate if offered, else the smallest larger one, else the largest. */
    fun pickRate(wanted: Int, rates: List<Int>): Int {
        if (rates.isEmpty()) return wanted
        if (wanted in rates) return wanted
        return rates.firstOrNull { it >= wanted } ?: rates.last()
    }

    /** Channels offered: mono, and stereo when the format allows it. */
    fun channelChoices(format: ExportFormat?, defaults: ExportDefaults?): List<Int> {
        val max = minOf(format?.maxChannels ?: 2, defaults?.maxChannels ?: 2)
        return if (max >= 2) listOf(1, 2) else listOf(1)
    }

    fun defaultChannels(format: ExportFormat?, defaults: ExportDefaults?): Int =
        (defaults?.defaultChannels ?: 2).coerceIn(1, channelChoices(format, defaults).last())

    fun extension(format: ExportFormat?): String = format?.extensions?.firstOrNull()?.lowercase() ?: "wav"

    /** "<name>.<ext>" with the project name sanitised for a document name. */
    fun fileName(baseName: String, format: ExportFormat?): String {
        val base = SafFiles.sanitizeBaseName(baseName.ifBlank { "Untitled" })
        return "$base.${extension(format)}"
    }

    /** Replaces the extension of [name] with the one of [format]. */
    fun withExtension(name: String, format: ExportFormat?): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "Untitled" }
        return "$base.${extension(format)}"
    }

    fun mimeType(format: ExportFormat?): String = SafFiles.mimeForExtension(extension(format))
}
