/*
 * Audacity Android port — conversions between effect parameters as the
 * engine reports them (API.md §5.5) and what the generic effect dialog shows.
 *
 * * `display == "dB"`: the dialog shows and edits 20·log10(value) (Amplify ratio).
 * * `display == "ratio"`: a percent change (Change Tempo/Pitch/Speed, Sliding
 *   Stretch) shown and edited as the multiplier r = 1 + percent/100
 *   (1.25 ⇔ +25 %, 0.65 ⇔ −35 %); sent back as exactly (r − 1)·100.
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

object ParamCodec {
    /** Lower display bound for dB parameters whose linear minimum is 0. */
    const val DB_FLOOR = -100.0

    fun isDb(p: EffectParam): Boolean = p.display.equals("dB", ignoreCase = true)

    /** A percent change shown as a multiplier (`display == "ratio"`). */
    fun isRatio(p: EffectParam): Boolean = p.isRatio

    fun label(p: EffectParam): String = p.label.ifEmpty { p.key }

    fun linearToDb(v: Double): Double = if (v <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(v)
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Value as shown to the user (dB for `display == "dB"`, the multiplier for `"ratio"`). */
    fun toDisplay(p: EffectParam, raw: Double): Double = when {
        isDb(p) -> linearToDb(raw).coerceAtLeast(DB_FLOOR)
        isRatio(p) -> percentToRatio(raw)
        else -> raw
    }

    /** Engine value from a displayed value. */
    fun fromDisplay(p: EffectParam, shown: Double): Double = when {
        isDb(p) -> dbToLinear(shown)
        isRatio(p) -> ratioToPercent(shown)
        else -> shown
    }

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
        if (isRatio(p)) return formatRatio(percentToRatio(raw))
        val shown = toDisplay(p, raw)
        return if (p.kind == "int") shown.roundToLong().toString() else TimeCodec.number(shown, if (isDb(p)) 2 else 4)
    }

    /** Parses a displayed number; null when it is not a number or outside the range. */
    fun parseDisplayed(p: EffectParam, text: String): Double? {
        if (isRatio(p)) {
            val r = parseRatio(text) ?: return null
            return if (ratioInRange(p, r)) ratioToPercent(r) else null
        }
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

    // ---- ratio display (tempo, pitch, speed) ----------------------------------

    /** Multipliers offered as one-tap chips (those inside the parameter's range). */
    val RATIO_PRESETS: List<Double> = listOf(0.5, 0.65, 0.75, 0.8, 0.9, 1.1, 1.2, 1.25, 1.35, 1.5, 2.0)

    /** Step of the − / + buttons. */
    const val RATIO_STEP: Double = 0.05

    /** The slider covers at most ×0.25 … ×4 (log scale); the text field takes the whole range. */
    const val RATIO_SLIDER_MIN: Double = 0.25
    const val RATIO_SLIDER_MAX: Double = 4.0

    /** Decimals kept for a multiplier (1.3333). */
    private const val RATIO_DECIMALS = 4

    /** Percent change → multiplier, exact in decimal (35 → 1.35, −35 → 0.65). */
    fun percentToRatio(percent: Double): Double {
        if (!percent.isFinite()) return EffectParam.percentToRatio(percent)
        return BigDecimal.valueOf(percent).movePointLeft(2).add(BigDecimal.ONE).toDouble()
    }

    /** Multiplier → percent change: exactly (r − 1)·100 in decimal (1.25 → 25, 0.65 → −35, 1.35 → 35). */
    fun ratioToPercent(ratio: Double): Double {
        if (!ratio.isFinite()) return EffectParam.ratioToPercent(ratio)
        return BigDecimal.valueOf(ratio).subtract(BigDecimal.ONE).movePointRight(2).toDouble()
    }

    /** Pitch shift in semitones of a multiplier (12·log2 r; 1.25 → 3.86). */
    fun semitones(ratio: Double): Double = EffectParam.ratioToSemitones(ratio)

    /** The multiplier rounded to [decimals] decimals (half up). */
    fun roundRatio(ratio: Double, decimals: Int = RATIO_DECIMALS): Double =
        if (!ratio.isFinite()) ratio else BigDecimal.valueOf(ratio).setScale(decimals, RoundingMode.HALF_UP).toDouble()

    /** Multipliers allowed by the parameter's percent range (no range: anything above 0). */
    fun ratioRange(p: EffectParam): ClosedFloatingPointRange<Double> {
        val lo = p.min?.let { percentToRatio(it) }?.takeIf { it > 0.0 } ?: 1e-4
        val hi = p.max?.let { percentToRatio(it) }?.takeIf { it > lo } ?: 100.0
        return lo..hi
    }

    fun ratioInRange(p: EffectParam, ratio: Double): Boolean {
        val r = ratioRange(p)
        return ratio >= r.start - 1e-9 && ratio <= r.endInclusive + 1e-9
    }

    /** Range of the log-scale slider: the parameter's range within ×0.25 … ×4; null when empty. */
    fun ratioSliderRange(p: EffectParam): ClosedFloatingPointRange<Double>? {
        val r = ratioRange(p)
        val lo = maxOf(r.start, RATIO_SLIDER_MIN)
        val hi = minOf(r.endInclusive, RATIO_SLIDER_MAX)
        return if (hi > lo) lo..hi else null
    }

    /** Slider position 0…1 of [ratio] on a log scale over [range]. */
    fun ratioToSlider(ratio: Double, range: ClosedFloatingPointRange<Double>): Float {
        if (!(ratio > 0.0)) return 0f
        val pos = ln(ratio / range.start) / ln(range.endInclusive / range.start)
        return pos.coerceIn(0.0, 1.0).toFloat()
    }

    /** Multiplier at slider position [pos] (log scale), rounded to 0.01 and kept in [range]. */
    fun sliderToRatio(pos: Float, range: ClosedFloatingPointRange<Double>): Double {
        val r = range.start * exp(pos.toDouble().coerceIn(0.0, 1.0) * ln(range.endInclusive / range.start))
        return roundRatio(r, 2).coerceIn(range.start, range.endInclusive)
    }

    /** [ratio] moved by [delta] (the − / + buttons), rounded to 0.01 and kept in the parameter's range. */
    fun stepRatio(p: EffectParam, ratio: Double, delta: Double): Double {
        val range = ratioRange(p)
        return roundRatio(ratio + delta, 2).coerceIn(range.start, range.endInclusive)
    }

    /** The preset chips that fit the parameter's range. */
    fun ratioPresets(p: EffectParam): List<Double> = RATIO_PRESETS.filter { ratioInRange(p, it) }

    /**
     * Parses a multiplier as typed: `1.25`, `1,25` (decimal comma), `×1.25`,
     * `x1.25`, `*1.25`, `1.25x`; a percent change with a `%` sign (`+25%`,
     * `-35 %`) is converted. Null when it is not a number above 0 (no range
     * check: see [ratioInRange]).
     */
    fun parseRatio(text: String): Double? {
        var t = text.trim().replace('\u00A0', ' ').replace(" ", "")
        if (t.isEmpty()) return null
        val percent = t.endsWith('%')
        if (percent) t = t.dropLast(1)
        if (!percent) {
            t = t.trimStart('×', 'x', 'X', '*', '✕').trimEnd('×', 'x', 'X', '✕')
        }
        t = t.replace('−', '-').replace(',', '.')
        if (t.startsWith('+')) t = t.drop(1)
        if (t.isEmpty() || t.count { it == '.' } > 1 || !t.all { it.isDigit() || it == '.' || it == '-' }) return null
        val v = t.toDoubleOrNull() ?: return null
        if (!v.isFinite()) return null
        val ratio = if (percent) percentToRatio(v) else v
        return if (ratio > 0.0) roundRatio(ratio) else null
    }

    /** Engine value (percent) of [ratio], clamped to the parameter's min/max. */
    fun encodeRatio(p: EffectParam, ratio: Double): JsonElement = encodeNumber(p, ratioToPercent(roundRatio(ratio)))

    /** "1.25", "0.65", "2.0", "1.3333": at least one decimal, at most four. */
    fun formatRatio(ratio: Double): String {
        if (!ratio.isFinite()) return TimeCodec.number(ratio)
        val s = TimeCodec.number(roundRatio(ratio), RATIO_DECIMALS)
        return if (s.contains('.')) s else "$s.0"
    }

    /** Signed semitones with two decimals: "+3.86", "-7.46", "0.00". */
    fun formatSemitones(semitones: Double): String {
        if (!semitones.isFinite()) return "–"
        val v = if (abs(semitones) < 0.005) 0.0 else semitones
        val s = String.format(Locale.ROOT, "%.2f", v)
        return if (v > 0) "+$s" else s
    }

    /** Signed percent change of a multiplier: "+25", "-35", "0" (up to two decimals). */
    fun formatPercentChange(ratio: Double): String {
        val pc = ratioToPercent(roundRatio(ratio))
        val s = TimeCodec.number(pc, 2)
        return if (pc > 0) "+$s" else s
    }

    // ---- generator duration ---------------------------------------------------

    fun formatDuration(seconds: Double): String = TimeCodec.format(seconds)

    /** Duration from `hh:mm:ss.mmm`, `mm:ss` or seconds; null when invalid or not > 0. */
    fun parseDuration(text: String): Double? = TimeCodec.parse(text)?.takeIf { it > 0.0 }

    /** Long value of an int parameter. */
    fun long(e: JsonElement?): Long = runCatching { e?.jsonPrimitive?.longOrNull }.getOrNull() ?: (number(e) ?: 0.0).roundToLong()
}
