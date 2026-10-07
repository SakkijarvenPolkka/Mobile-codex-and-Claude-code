/*
 * Audacity Android port — conversions between effect parameters as the
 * engine reports them (API.md §5.5) and what the generic effect dialog shows.
 *
 * * `display == "dB"`: the dialog shows and edits 20·log10(value) (Amplify ratio).
 * * enums: value/default are choice indices (or, defensively, internal names).
 * * generators: the duration is edited as hh:mm:ss.mmm.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.effects

import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

object ParamCodec {
    /** Lower display bound for dB parameters whose linear minimum is 0. */
    const val DB_FLOOR = -100.0

    fun isDb(p: EffectParam): Boolean = p.display.equals("dB", ignoreCase = true)

    fun label(p: EffectParam): String = p.label.ifEmpty { p.key }

    fun linearToDb(v: Double): Double = if (v <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(v)
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Value as shown to the user (dB for `display == "dB"`). */
    fun toDisplay(p: EffectParam, raw: Double): Double =
        if (isDb(p)) linearToDb(raw).coerceAtLeast(DB_FLOOR) else raw

    /** Engine value from a displayed value. */
    fun fromDisplay(p: EffectParam, shown: Double): Double = if (isDb(p)) dbToLinear(shown) else shown

    /** Displayed [min, max], or null when the parameter has no range. */
    fun displayRange(p: EffectParam): ClosedFloatingPointRange<Double>? {
        val lo = p.min ?: return null
        val hi = p.max ?: return null
        if (!(hi > lo)) return null
        return toDisplay(p, lo)..toDisplay(p, hi)
    }

    /** Range for a slider: the displayed range when it is finite and not absurdly
     *  wide (e.g. Tone's frequency maximum is DBL_MAX); null = text field only. */
    fun sliderRange(p: EffectParam): ClosedFloatingPointRange<Double>? {
        val r = displayRange(p) ?: return null
        if (!r.start.isFinite() || !r.endInclusive.isFinite() || r.endInclusive - r.start > 1e6) return null
        return r
    }

    fun number(e: JsonElement?): Double? {
        if (e == null || e is JsonNull) return null
        val prim = runCatching { e.jsonPrimitive }.getOrNull() ?: return null
        prim.doubleOrNull?.let { return it }
        prim.booleanOrNull?.let { return if (it) 1.0 else 0.0 }
        return null
    }

    fun bool(e: JsonElement?): Boolean {
        val prim = runCatching { e?.jsonPrimitive }.getOrNull() ?: return false
        prim.booleanOrNull?.let { return it }
        prim.doubleOrNull?.let { return it != 0.0 }
        return prim.content.equals("true", true) || prim.content == "1"
    }

    fun string(e: JsonElement?): String {
        val prim = runCatching { e?.jsonPrimitive }.getOrNull() ?: return ""
        return prim.content
    }

    /** Choice index of an enum value (index or internal name), clamped to the choices. */
    fun enumIndex(p: EffectParam, e: JsonElement?): Int {
        val prim = runCatching { e?.jsonPrimitive }.getOrNull() ?: return 0
        val n = choices(p).size
        val idx = prim.intOrNull ?: p.choices.indexOf(prim.content).takeIf { it >= 0 } ?: 0
        return if (n == 0) 0 else idx.coerceIn(0, n - 1)
    }

    /** Labels shown for an enum: translated `choiceLabels` when complete, else internal names. */
    fun choices(p: EffectParam): List<String> =
        if (p.choiceLabels.isNotEmpty() && (p.choices.isEmpty() || p.choiceLabels.size == p.choices.size)) p.choiceLabels
        else p.choices

    /** Current value of [p] in [values] (edited) or its engine value. */
    fun current(p: EffectParam, values: Map<String, JsonElement>): JsonElement? = values[p.key] ?: p.value ?: p.defaultValue

    // ---- encoding for effects.setParams / effects.apply ---------------------

    fun encodeBool(v: Boolean): JsonElement = JsonPrimitive(v)
    fun encodeEnum(index: Int): JsonElement = JsonPrimitive(index)
    fun encodeString(v: String): JsonElement = JsonPrimitive(v)

    /** Number in engine units, clamped to the range; ints are rounded. */
    fun encodeNumber(p: EffectParam, raw: Double): JsonElement {
        var v = raw
        p.min?.let { if (v < it) v = it }
        p.max?.let { if (v > it) v = it }
        return if (p.kind == "int") JsonPrimitive(v.roundToLong()) else JsonPrimitive(v)
    }

    /** Number entered/dragged in display units (dB for dB parameters). */
    fun encodeDisplayed(p: EffectParam, shown: Double): JsonElement = encodeNumber(p, fromDisplay(p, shown))

    /** Text for a numeric value in display units. */
    fun formatDisplayed(p: EffectParam, raw: Double): String {
        val shown = toDisplay(p, raw)
        return if (p.kind == "int") shown.roundToLong().toString() else TimeCodec.number(shown, if (isDb(p)) 2 else 4)
    }

    /** Parses a displayed number; null when it is not a number or outside the range. */
    fun parseDisplayed(p: EffectParam, text: String): Double? {
        val shown = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (shown.isNaN() || shown.isInfinite()) return null
        if (p.kind == "int" && shown != Math.rint(shown)) return null
        val range = displayRange(p)
        if (range != null && (shown < range.start - 1e-9 || shown > range.endInclusive + 1e-9)) return null
        return fromDisplay(p, shown)
    }

    /** Parameter map for effects.setParams/apply from the dialog's edits (all parameters). */
    fun paramsFor(desc: EffectDescription, edits: Map<String, JsonElement>): Map<String, JsonElement> =
        desc.params.mapNotNull { p ->
            val v = edits[p.key] ?: p.value ?: return@mapNotNull null
            p.key to normalize(p, v)
        }.toMap()

    /** Coerces a value to the JSON type its kind requires. */
    fun normalize(p: EffectParam, v: JsonElement): JsonElement = when (p.kind) {
        "bool" -> JsonPrimitive(bool(v))
        "int" -> JsonPrimitive((number(v) ?: 0.0).roundToLong())
        "double" -> JsonPrimitive(number(v) ?: 0.0)
        "enum" -> JsonPrimitive(enumIndex(p, v))
        else -> JsonPrimitive(string(v))
    }

    // ---- generator duration ---------------------------------------------------

    fun formatDuration(seconds: Double): String = TimeCodec.format(seconds)

    /** Duration from `hh:mm:ss.mmm`, `mm:ss` or seconds; null when invalid or not > 0. */
    fun parseDuration(text: String): Double? = TimeCodec.parse(text)?.takeIf { it > 0.0 }

    /** Long value of an int parameter. */
    fun long(e: JsonElement?): Long = runCatching { e?.jsonPrimitive?.longOrNull }.getOrNull() ?: (number(e) ?: 0.0).roundToLong()
}
