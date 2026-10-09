/*
 * Audacity Android port — effect catalogue of the fake engine.
 *
 * Mirrors the Audacity 3.7.9 built-in effects and the bundled Nyquist
 * plug-ins: names, ids (PluginManager::GetID format), types, menu grouping
 * (resources/EffectsMenuDefaults.xml), parameter keys/ranges/defaults
 * (the EffectParameter tables of lib-builtin-effects and the $control lines
 * of the bundled plug-ins/<name>.ny files) and factory presets (ReverbBase.cpp, DistortionBase.cpp,
 * lib-dynamic-range-processor/DynamicRangeProcessorUtils.h; Audacity team,
 * GPL-2.0-or-later). The DSP is a simplified stand-in, good enough to see
 * the waveform change.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectMenus
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.EqPoint
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.MenuSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

internal enum class PKind(val wire: String) { BOOL("bool"), INT("int"), DOUBLE("double"), ENUM("enum"), STRING("string") }

/** One parameter (automation key, schema, default). */
internal class PDef(
    val key: String,
    val label: String,
    val kind: PKind,
    val default: JsonPrimitive,
    val min: Double? = null,
    val max: Double? = null,
    val scale: Double? = null,
    val choices: List<String> = emptyList(),
    val choiceLabels: List<String> = emptyList(),
    val unit: String = "",
    val display: String = "",
    /** Nyquist control type ("float", "int-text", "time", "choice", ...). */
    val nyquistType: String? = null,
    /** display "ratio" of a pitch (API.md §5.5 `semitones`). */
    val semitones: Boolean = false,
) {
    fun toParam(value: JsonPrimitive) = EffectParam(
        key = key, label = label, kind = kind.wire, min = min, max = max, scale = scale,
        defaultValue = default, value = value, unit = unit, display = display,
        choices = choices, choiceLabels = choiceLabels.ifEmpty { choices }, semitones = semitones,
    )

    /** Validates [input] (API.md §5.5 accepted JSON types) and returns the
     *  normalized stored value. @throws EngineException INVALID_ARGS */
    fun validate(input: JsonElement): JsonPrimitive {
        fun bad(why: String): Nothing = throw EngineException(ErrorCodes.INVALID_ARGS, "parameter '$key': $why")
        val p = input as? JsonPrimitive ?: bad("expected a ${kind.wire}")
        return when (kind) {
            PKind.BOOL -> if (!p.isString && p.booleanOrNull != null) JsonPrimitive(p.booleanOrNull!!) else bad("expected a JSON boolean")
            PKind.INT -> {
                val d = if (p.isString) null else p.doubleOrNull
                if (d == null || d != floor(d) || d.isInfinite()) bad("expected an integer")
                checkRange(d, ::bad)
                JsonPrimitive(d.toLong())
            }
            PKind.DOUBLE -> {
                val d = if (p.isString) null else p.doubleOrNull
                if (d == null || !d.isFinite()) bad("expected a number")
                checkRange(d, ::bad)
                JsonPrimitive(d)
            }
            PKind.ENUM -> {
                if (p.isString) {
                    val i = choices.indexOf(p.content)
                    if (i < 0) bad("unknown choice '${p.content}' (choices: ${choices.joinToString()})")
                    JsonPrimitive(i)
                } else {
                    val i = p.longOrNull ?: bad("expected a choice index or name")
                    if (i < 0 || i >= choices.size) bad("choice index $i out of range 0..${choices.size - 1}")
                    JsonPrimitive(i.toInt())
                }
            }
            PKind.STRING -> if (p.isString) p else bad("expected a string")
        }
    }

    private fun checkRange(d: Double, bad: (String) -> Nothing) {
        if (min != null && d < min) bad("$d is below the minimum $min")
        if (max != null && d > max) bad("$d is above the maximum $max")
    }

    fun nyquistControl(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["key"] = JsonPrimitive(key)
        m["label"] = JsonPrimitive(label)
        m["kind"] = JsonPrimitive(kind.wire)
        m["type"] = JsonPrimitive(nyquistType ?: kind.wire)
        min?.let { m["min"] = JsonPrimitive(it) }
        max?.let { m["max"] = JsonPrimitive(it) }
        m["default"] = default
        if (choices.isNotEmpty()) {
            m["choices"] = JsonArray(choices.map { JsonPrimitive(it) })
            m["choiceLabels"] = JsonArray(choiceLabels.ifEmpty { choices }.map { JsonPrimitive(it) })
        }
        return JsonObject(m)
    }
}

/** Typed read access to validated parameter values. */
internal class P(private val values: Map<String, JsonPrimitive>) {
    fun d(key: String): Double = values[key]?.doubleOrNull ?: 0.0
    fun i(key: String): Int = values[key]?.doubleOrNull?.toInt() ?: 0
    fun b(key: String): Boolean = values[key]?.booleanOrNull ?: false
    fun s(key: String): String = values[key]?.content ?: ""
}

/** Extra context for processors that need more than the segment. */
internal class FxContext(
    /** Mono mix of the Auto Duck control track over the same range (or null). */
    val control: FloatArray? = null,
    /** RMS level of the captured noise profile (Noise Reduction), or null. */
    val noiseProfileRms: Double? = null,
    /** Current curve of Filter Curve EQ / Graphic EQ. */
    val curve: EqCurve? = null,
)

internal class AnalysisResult(val labels: List<FLabel>, val trackName: String, val message: String? = null)

internal sealed interface FxImpl {
    /** Changes nothing (the history still gets an entry). */
    data object Identity : FxImpl
    class Process(val fn: (data: Array<FloatArray>, rate: Double, p: P, ctx: FxContext) -> Array<FloatArray>) : FxImpl
    /** frames = requested duration (Nyquist generators decide their own length). */
    class Generate(val fn: (frames: Int, channels: Int, rate: Double, p: P) -> Array<FloatArray>) : FxImpl
    /** mono = mix of the selected tracks over the selection starting at t0. */
    class Analyze(val fn: (mono: FloatArray, rate: Double, t0: Double, p: P) -> AnalysisResult) : FxImpl
    /** Returns (applied, message); throws EngineException on failure. */
    class Tool(val fn: (P) -> Pair<Boolean, String?>) : FxImpl
}

internal class FxDef(
    /** Symbol internal name (part of the id). */
    val internalName: String,
    /** Menu name. */
    val name: String,
    val type: String,
    val family: String = "Audacity",
    val vendor: String = "Audacity",
    val path: String = "Built-in Effect: $internalName",
    val description: String = "",
    val interactive: Boolean = true,
    val realtime: Boolean = false,
    val special: String? = null,
    val params: List<PDef> = emptyList(),
    val factoryPresets: List<Pair<String, Map<String, Any>>> = emptyList(),
    /** Built-in generators take a duration; Nyquist ones decide their length. */
    val supportsDuration: Boolean = type == "generate" && family == "Audacity",
    val impl: FxImpl = FxImpl.Identity,
) {
    val id: String = "Effect_${family}_${vendor}_${internalName}_$path"
    val isNyquist: Boolean get() = family == "Nyquist"
    /** Process-like behaviour (needs a time selection on wave tracks). */
    val needsSelection: Boolean get() = impl is FxImpl.Process || impl is FxImpl.Analyze ||
        (impl is FxImpl.Identity && type == "process")

    fun info() = EffectInfo(
        id = id, name = name, type = type, family = family, vendor = vendor, description = description,
        interactive = interactive, realtime = realtime, isDefault = true, special = special,
    )

    fun defaults(): Map<String, JsonPrimitive> = params.associate { it.key to it.default }
}

// ---------------------------------------------------------------------------
// Parameter builders
// ---------------------------------------------------------------------------

private fun pBool(key: String, label: String, def: Boolean) = PDef(key, label, PKind.BOOL, JsonPrimitive(def))

private fun pInt(key: String, label: String, def: Int, min: Number?, max: Number?, unit: String = "", ny: String? = null) =
    PDef(key, label, PKind.INT, JsonPrimitive(def), min?.toDouble(), max?.toDouble(), unit = unit, nyquistType = ny)

private fun pDouble(
    key: String, label: String, def: Double, min: Double?, max: Double?, unit: String = "", display: String = "",
    scale: Double? = null, ny: String? = null, semitones: Boolean = false,
) = PDef(key, label, PKind.DOUBLE, JsonPrimitive(def), min, max, scale, unit = unit, display = display, nyquistType = ny,
    semitones = semitones)

/** A percent change shown as a multiplier (API.md §5.5 display "ratio"). */
private fun pRatio(key: String, label: String, min: Double, max: Double, unit: String = "%", semitones: Boolean = false) =
    pDouble(key, label, 0.0, min, max, unit, display = "ratio", semitones = semitones)

private fun pEnum(key: String, label: String, def: Int, choices: List<String>, labels: List<String> = choices, ny: String? = null) =
    PDef(key, label, PKind.ENUM, JsonPrimitive(def), choices = choices, choiceLabels = labels, nyquistType = ny)

private fun pString(key: String, label: String, def: String, ny: String? = null) =
    PDef(key, label, PKind.STRING, JsonPrimitive(def), nyquistType = ny)

private const val FLT_MAX = 3.4028234663852886E38
private const val DBL_MAX = Double.MAX_VALUE
private const val INT_MAX = 2147483647

// ---------------------------------------------------------------------------
// DSP stand-ins
// ---------------------------------------------------------------------------

private inline fun eachChannel(data: Array<FloatArray>, f: (Int, FloatArray) -> Unit): Array<FloatArray> {
    data.forEachIndexed(f)
    return data
}

private fun gainRamp(data: Array<FloatArray>, shape: (Double) -> Double) = eachChannel(data) { _, a ->
    val n = a.size
    for (i in 0 until n) a[i] = (a[i] * shape(if (n > 1) i.toDouble() / (n - 1) else 1.0)).toFloat()
}

private fun scale(data: Array<FloatArray>, g: Double) = eachChannel(data) { _, a -> for (i in a.indices) a[i] = (a[i] * g).toFloat() }

private fun biquads(data: Array<FloatArray>, make: () -> List<Dsp.Biquad>) = eachChannel(data) { _, a -> for (b in make()) b.process(a) }

/** Envelope follower in dB (attack/release in seconds). */
private fun envelopeDb(a: FloatArray, rate: Double, attack: Double, release: Double): DoubleArray {
    val ca = exp(-1.0 / (max(attack, 1e-4) * rate))
    val cr = exp(-1.0 / (max(release, 1e-4) * rate))
    var env = 0.0
    return DoubleArray(a.size) { i ->
        val x = abs(a[i].toDouble())
        env = if (x > env) ca * env + (1 - ca) * x else cr * env + (1 - cr) * x
        Dsp.linToDb(max(env, 1e-9))
    }
}

private fun compress(data: Array<FloatArray>, rate: Double, thresholdDb: Double, ratio: Double, kneeDb: Double,
                     attack: Double, release: Double, makeupDb: Double) = eachChannel(data) { _, a ->
    val env = envelopeDb(a, rate, attack, release)
    for (i in a.indices) {
        val over = env[i] - thresholdDb
        val reduction = when {
            kneeDb > 0 && abs(over) <= kneeDb / 2 -> (1 / ratio - 1) * (over + kneeDb / 2).pow(2) / (2 * kneeDb)
            over > 0 -> over * (1 / ratio - 1)
            else -> 0.0
        }
        a[i] = (a[i] * Dsp.dbToLin(reduction + makeupDb)).toFloat()
    }
}

private fun gate(data: Array<FloatArray>, rate: Double, thresholdDb: Double, reductionDb: Double, attack: Double, release: Double) =
    eachChannel(data) { _, a ->
        val env = envelopeDb(a, rate, attack, release)
        val g = Dsp.dbToLin(reductionDb)
        var smooth = 1.0
        val c = exp(-1.0 / (0.005 * rate))
        for (i in a.indices) {
            val target = if (env[i] < thresholdDb) g else 1.0
            smooth = c * smooth + (1 - c) * target
            a[i] = (a[i] * smooth).toFloat()
        }
    }

private fun lfo(i: Int, rate: Double, freq: Double, phaseDeg: Double) =
    0.5 + 0.5 * Osc.sin01(freq * i / rate + phaseDeg / 360.0)

private fun stretchAll(data: Array<FloatArray>, newLength: Int) = Array(data.size) { Dsp.stretch(data[it], newLength) }

/** Gain of an EQ curve at [f] (interpolated on a log or linear frequency axis). */
private fun curveDb(curve: EqCurve, f: Double): Double {
    val pts = curve.points
    if (pts.isEmpty()) return 0.0
    if (f <= pts.first().f) return pts.first().dB
    if (f >= pts.last().f) return pts.last().dB
    val k = pts.indexOfFirst { it.f >= f }
    val a = pts[k - 1]
    val b = pts[k]
    val x = if (curve.linearFreq) (f - a.f) / (b.f - a.f) else (kotlin.math.ln(f / a.f) / kotlin.math.ln(b.f / a.f))
    return a.dB + (b.dB - a.dB) * x
}

/** Approximates the curve with peaking filters at the octave-band centres. */
private fun equalize(data: Array<FloatArray>, rate: Double, curve: EqCurve?) = biquads(data) {
    val c = curve ?: return@biquads emptyList()
    listOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        .filter { it < rate / 2 }
        .map { f -> f to curveDb(c, f) }
        .filter { abs(it.second) > 0.01 }
        .map { (f, db) -> Dsp.Biquad.peaking(f, 1.4, db, rate) }
}

private fun truncateSilence(data: Array<FloatArray>, rate: Double, p: P): Array<FloatArray> {
    val thr = Dsp.dbToLin(p.d("Threshold")).toFloat()
    val minRun = (p.d("Minimum") * rate).toInt().coerceAtLeast(1)
    val n = data[0].size
    val keep = BooleanArray(n) { true }
    var i = 0
    while (i < n) {
        if (data.all { abs(it[i]) < thr }) {
            var j = i
            while (j < n && data.all { abs(it[j]) < thr }) j++
            val run = j - i
            if (run > minRun) {
                val allowed = if (p.i("Action") == 0) (p.d("Truncate") * rate).toInt()
                else (minRun + (run - minRun) * (1 - p.d("Compress") / 100)).toInt()
                for (k in i + allowed.coerceIn(0, run) until j) keep[k] = false
            }
            i = j
        } else i++
    }
    val count = keep.count { it }
    return Array(data.size) { ch ->
        val out = FloatArray(count)
        var w = 0
        for (k in 0 until n) if (keep[k]) out[w++] = data[ch][k]
        out
    }
}

private fun reverb(data: Array<FloatArray>, rate: Double, p: P) = eachChannel(data) { ch, a ->
    val room = 0.3 + 0.7 * p.d("RoomSize") / 100
    val fb = 0.55 + 0.4 * p.d("Reverberance") / 100
    val damp = p.d("HfDamping") / 100 * 0.6
    val pre = (p.d("Delay") / 1000 * rate).toInt()
    val wet = Dsp.dbToLin(p.d("WetGain"))
    val dry = if (p.b("WetOnly")) 0.0 else Dsp.dbToLin(p.d("DryGain"))
    val delays = doubleArrayOf(0.0297, 0.0371, 0.0411, 0.0437).map { ((it * room + ch * 0.0011) * rate).toInt().coerceAtLeast(1) }
    val bufs = delays.map { FloatArray(it) }
    val idx = IntArray(4)
    val lp = DoubleArray(4)
    val src = a.copyOf()
    for (i in a.indices) {
        val x = if (i - pre >= 0) src[i - pre].toDouble() else 0.0
        var acc = 0.0
        for (k in 0 until 4) {
            val y = bufs[k][idx[k]].toDouble()
            lp[k] = y * (1 - damp) + lp[k] * damp
            bufs[k][idx[k]] = (x + lp[k] * fb).toFloat()
            idx[k] = (idx[k] + 1) % delays[k]
            acc += y
        }
        a[i] = (dry * src[i] + wet * 0.25 * acc).toFloat()
    }
}

private fun phaser(data: Array<FloatArray>, rate: Double, p: P) = eachChannel(data) { _, a ->
    val stages = (p.i("Stages") / 2 * 2).coerceAtLeast(2)
    val mix = p.i("DryWet") / 255.0
    val depth = p.i("Depth") / 255.0
    val fb = p.i("Feedback") / 100.0
    val g = Dsp.dbToLin(p.d("Gain"))
    val z = DoubleArray(stages)
    var last = 0.0
    for (i in a.indices) {
        val m = lfo(i, rate, p.d("Freq"), p.d("Phase"))
        val coef = 0.2 + 0.7 * depth * m
        var x = a[i] + fb * last
        for (s in 0 until stages) {
            val y = -coef * x + z[s]
            z[s] = x + coef * y
            x = y
        }
        last = x
        a[i] = ((a[i] * (1 - mix) + x * mix) * g).toFloat()
    }
}

private fun wahwah(data: Array<FloatArray>, rate: Double, p: P) = eachChannel(data) { _, a ->
    val depth = p.i("Depth") / 100.0
    val offset = p.i("Offset") / 100.0
    val q = p.d("Resonance")
    val g = Dsp.dbToLin(p.d("Gain"))
    var low = 0.0
    var band = 0.0
    for (i in a.indices) {
        val m = lfo(i, rate, p.d("Freq"), p.d("Phase"))
        val fc = 300.0 + 2700.0 * (offset + depth * m).coerceIn(0.0, 1.0)
        val f = 2 * kotlin.math.sin(PI * fc / rate)
        val high = a[i] - low - band / q
        band += f * high
        low += f * band
        a[i] = (band * g * 2).toFloat()
    }
}

private fun distortion(data: Array<FloatArray>, p: P) = eachChannel(data) { _, a ->
    val type = p.i("Type")
    val thr = Dsp.dbToLin(p.d("Threshold dB"))
    val drive = 1 + p.d("Parameter 1") / 10
    val makeup = p.d("Parameter 2") / 100
    for (i in a.indices) {
        val x = a[i].toDouble()
        val y = when (type) {
            0, 10 -> x.coerceIn(-thr, thr) * (1 + makeup * (1 / thr - 1))
            9 -> if (p.d("Parameter 1") >= 100) abs(x) else max(0.0, x)
            else -> tanh(drive * x) / tanh(drive)
        }
        a[i] = y.toFloat()
    }
    if (p.b("DC Block")) {
        val mean = a.average()
        for (i in a.indices) a[i] = (a[i] - mean).toFloat()
    }
}

private fun karplusStrong(rate: Double, frames: Int, midi: Int, gradual: Boolean): FloatArray {
    val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
    val period = (rate / f).toInt().coerceAtLeast(2)
    val buf = FloatArray(period) { Osc.hash(31, it.toLong()) * 0.8f }
    val out = FloatArray(frames)
    var k = 0
    for (i in 0 until frames) {
        val next = (k + 1) % period
        val v = buf[k]
        buf[k] = 0.996f * 0.5f * (buf[k] + buf[next])
        out[i] = v * (if (gradual) (1f - i.toFloat() / frames) else 1f)
        k = next
    }
    return out
}

private fun rhythm(rate: Double, p: P): FloatArray {
    val tempo = p.d("TEMPO")
    val beatsPerBar = p.i("TIMESIG").coerceAtLeast(1)
    val bars = p.i("BARS")
    val offset = p.d("OFFSET")
    val beat = 60.0 / tempo
    val length = if (bars > 0) bars * beatsPerBar * beat else p.d("CLICK-TRACK-DUR")
    val frames = ((offset + length) * rate).toInt().coerceAtLeast(1)
    val out = FloatArray(frames)
    val nBeats = (length / beat).toInt()
    for (b in 0 until nBeats) {
        val swing = if (b % 2 == 1) p.d("SWING") * beat / 3 else 0.0
        val start = ((offset + b * beat + swing) * rate).toInt()
        val midi = if (b % beatsPerBar == 0) p.i("HIGH") else p.i("LOW")
        val f = 440.0 * 2.0.pow((midi - 69) / 12.0)
        for (i in 0 until (0.03 * rate).toInt()) {
            val j = start + i
            if (j >= frames) break
            out[j] += (0.8 * Osc.sin01(f * i / rate) * exp(-i / rate * 120)).toFloat()
        }
    }
    return out
}

private fun noise(frames: Int, type: Int, amplitude: Double, seed: Long): FloatArray {
    val out = FloatArray(frames)
    var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var brown = 0.0
    for (i in 0 until frames) {
        val w = Osc.hash(seed, i.toLong()).toDouble()
        out[i] = (amplitude * when (type) {
            1 -> { // Paul Kellet's economy pink filter
                b0 = 0.99765 * b0 + w * 0.0990460
                b1 = 0.96300 * b1 + w * 0.2965164
                b2 = 0.57000 * b2 + w * 1.0526913
                (b0 + b1 + b2 + w * 0.1848) * 0.2
            }
            2 -> { brown = (brown + 0.02 * w) / 1.002; brown * 3.5 }
            else -> w
        }).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

private fun dtmf(rate: Double, frames: Int, p: P): FloatArray {
    val seq = p.s("Sequence")
    val out = FloatArray(frames)
    if (seq.isEmpty()) return out
    val duty = p.d("Duty Cycle") / 100
    val n = seq.length
    val slot = frames / (n * duty + (n - 1) * (1 - duty)).coerceAtLeast(1e-9)
    val tone = (slot * duty).toInt()
    val gap = (slot * (1 - duty)).toInt()
    val amp = p.d("Amplitude")
    var pos = 0
    for ((k, c) in seq.withIndex()) {
        val f = FakeDemo.dtmf(c)
        for (i in 0 until tone) {
            if (pos + i >= frames) break
            if (f != null) out[pos + i] = (amp * 0.5 * (Osc.sin01(f.first * i / rate) + Osc.sin01(f.second * i / rate))).toFloat()
        }
        pos += tone + if (k < n - 1) gap else 0
    }
    return out
}

private fun validDtmf(s: String) = s.all { it in "0123456789*#ABCD" || it in 'a'..'z' }

private fun labelRuns(mono: FloatArray, rate: Double, t0: Double, above: (Int) -> Boolean, minLen: Int, minGap: Int): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var i = 0
    while (i < mono.size) {
        if (above(i)) {
            var j = i
            var quiet = 0
            while (j < mono.size && quiet < minGap) { if (above(j)) quiet = 0 else quiet++; j++ }
            val end = j - quiet
            if (end - i >= minLen) out += (t0 + i / rate) to (t0 + end / rate)
            i = j
        } else i++
    }
    return out
}

private fun smoothAbs(mono: FloatArray, rate: Double, window: Double): FloatArray {
    val c = exp(-1.0 / (window * rate))
    var e = 0.0
    return FloatArray(mono.size) { i -> e = max(abs(mono[i].toDouble()), c * e); e.toFloat() }
}

// ---------------------------------------------------------------------------
// The catalogue
// ---------------------------------------------------------------------------

internal object FxCatalog {
    /** resources/EffectsMenuDefaults.xml of 3.7.9, in order. */
    val MENU_GROUPS: List<Pair<String, List<String>>> = listOf(
        "Volume and Compression" to listOf("Amplify", "Compressor", "Limiter", "Normalize", "Loudness Normalization", "Auto Duck"),
        "Fading" to listOf("Fade In", "Fade Out", "Studio Fade Out", "Adjustable Fade", "Crossfade Clips", "Crossfade Tracks"),
        "Pitch and Tempo" to listOf("Change Pitch", "Change Speed and Pitch", "Change Tempo", "Paulstretch", "Sliding Stretch"),
        "EQ and Filters" to listOf("Bass and Treble", "Graphic EQ", "Filter Curve EQ", "High-Pass Filter", "Low-Pass Filter", "Shelf Filter", "Notch Filter"),
        "Noise Removal and Repair" to listOf("Click Removal", "Noise Reduction", "Noise Gate", "Repair", "Clip Fix"),
        "Delay and Reverb" to listOf("Echo", "Reverb", "Delay"),
        "Distortion and Modulation" to listOf("Tremolo", "Distortion", "Wahwah", "Phaser", "Vocoder"),
        "Special" to listOf("Repeat", "Reverse", "Invert", "Truncate Silence", "Vocal Reduction and Isolation", "Vocal Remover"),
        "Spectral Tools" to listOf("Spectral Delete", "Spectral Edit Multi Tool", "Spectral Edit Parametric EQ", "Spectral Edit Shelves"),
        "Legacy" to listOf("Legacy Compressor", "Legacy Limiter", "Classic Filters"),
    )

    /** Factory presets of Filter Curve EQ (EqualizationBase.cpp FactoryPresets,
     *  Audacity 3.7.9); the second value marks the ones Graphic EQ offers. */
    val EQ_CURVES: List<Triple<String, Boolean, List<EqPoint>>> = listOf(
        Triple("100Hz Rumble", false, listOf(EqPoint(20.0, -80.0), EqPoint(49.237316986327, -33.107692718506), EqPoint(54.196034330446, -29.553844451904), EqPoint(88.033573501041, -6.923076629639), EqPoint(95.871851182279, -4.523078918457), EqPoint(108.957037410504, -1.938461303711), EqPoint(123.828171198057, -0.73846244812), EqPoint(149.228077614658, -0.092308044434))),
        Triple("AM Radio", false, listOf(EqPoint(20.0, -63.67), EqPoint(31.0, -33.219), EqPoint(50.0, -3.01), EqPoint(63.0, -0.106), EqPoint(100.0, 0.0), EqPoint(2500.0, 0.0), EqPoint(4000.0, -0.614), EqPoint(5000.0, -8.059), EqPoint(8000.0, -39.981), EqPoint(20000.0, -103.651), EqPoint(48000.0, -164.485))),
        Triple("Bass Boost", true, listOf(EqPoint(100.0, 9.0), EqPoint(500.0, 0.0))),
        Triple("Bass Cut", true, listOf(EqPoint(150.0, -50.0), EqPoint(300.0, 0.0))),
        Triple("Low rolloff for speech", false, listOf(EqPoint(50.0, -120.0), EqPoint(60.0, -50.0), EqPoint(65.0, -24.0), EqPoint(70.0, -12.0), EqPoint(80.0, -4.0), EqPoint(90.0, -1.0), EqPoint(100.0, 0.0))),
        Triple("RIAA", true, listOf(EqPoint(20.0, 19.274), EqPoint(25.0, 18.954), EqPoint(31.0, 18.516), EqPoint(40.0, 17.792), EqPoint(50.0, 16.946), EqPoint(63.0, 15.852), EqPoint(80.0, 14.506), EqPoint(100.0, 13.088), EqPoint(125.0, 11.563), EqPoint(160.0, 9.809), EqPoint(200.0, 8.219), EqPoint(250.0, 6.677), EqPoint(315.0, 5.179), EqPoint(400.0, 3.784), EqPoint(500.0, 2.648), EqPoint(630.0, 1.642), EqPoint(800.0, 0.751), EqPoint(1000.0, 0.0), EqPoint(1250.0, -0.744), EqPoint(1600.0, -1.643), EqPoint(2000.0, -2.589), EqPoint(2500.0, -3.7), EqPoint(3150.0, -5.038), EqPoint(4000.0, -6.605), EqPoint(5000.0, -8.21), EqPoint(6300.0, -9.98), EqPoint(8000.0, -11.894), EqPoint(10000.0, -13.734), EqPoint(12500.0, -15.609), EqPoint(16000.0, -17.708), EqPoint(20000.0, -19.62), EqPoint(25000.0, -21.542), EqPoint(48000.0, -27.187))),
        Triple("Telephone", false, listOf(EqPoint(20.0, -94.087), EqPoint(200.0, -14.254), EqPoint(250.0, -7.243), EqPoint(315.0, -2.245), EqPoint(400.0, -0.414), EqPoint(500.0, 0.0), EqPoint(2500.0, 0.0), EqPoint(3150.0, -0.874), EqPoint(4000.0, -3.992), EqPoint(5000.0, -9.993), EqPoint(48000.0, -88.117))),
        Triple("Treble Boost", true, listOf(EqPoint(4000.0, 0.0), EqPoint(5000.0, 9.0))),
        Triple("Treble Cut", true, listOf(EqPoint(6000.0, 0.0), EqPoint(10000.0, -110.0))),
        Triple("Walkie-talkie", false, listOf(EqPoint(100.0, -120.0), EqPoint(101.0, 0.0), EqPoint(2000.0, 0.0), EqPoint(2001.0, -120.0))),
    )

    val DEFAULT_EQ_CURVE = EqCurve(listOf(EqPoint(20.0, 0.0), EqPoint(20000.0, 0.0)), linearFreq = false)

    private fun parsePreset(s: String): Map<String, Any> =
        Regex("""(\w+)="([^"]*)"""").findAll(s).associate { m ->
            val (k, v) = m.destructured
            k to (if (k.startsWith("show")) v == "1" else v.toDouble())
        }

    private val COMPRESSOR_PRESETS = listOf(
        "Modern" to "attackMs=\"0.2\" compressionRatio=\"4\" kneeWidthDb=\"18\" lookaheadMs=\"1\" makeupGainDb=\"0\" releaseMs=\"210\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-14\"",
        "Glue Compressor" to "attackMs=\"20\" compressionRatio=\"1.2\" kneeWidthDb=\"12\" lookaheadMs=\"1\" makeupGainDb=\"2.5\" releaseMs=\"1000\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-22\"",
        "Gentle" to "attackMs=\"1\" compressionRatio=\"1.5\" kneeWidthDb=\"6\" lookaheadMs=\"1\" makeupGainDb=\"0\" releaseMs=\"100\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-18\"",
        "Beat Booster" to "attackMs=\"14\" compressionRatio=\"4\" kneeWidthDb=\"1\" lookaheadMs=\"1\" makeupGainDb=\"3\" releaseMs=\"9\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-18\"",
        "Deep Dive Master" to "attackMs=\"52.2\" compressionRatio=\"1.2\" kneeWidthDb=\"1\" lookaheadMs=\"33.2\" makeupGainDb=\"1.6\" releaseMs=\"12.2\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"1\" thresholdDb=\"-23.5\"",
        "Beefy Master" to "attackMs=\"49.6\" compressionRatio=\"1.2\" kneeWidthDb=\"4.9\" lookaheadMs=\"100.4\" makeupGainDb=\"2.5\" releaseMs=\"17.9\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-16.8\"",
        "Make It Right Master" to "attackMs=\"1\" compressionRatio=\"1.4\" kneeWidthDb=\"1\" lookaheadMs=\"10\" makeupGainDb=\"1.6\" releaseMs=\"1\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-6.5\"",
        "Brick Wall Master" to "attackMs=\"0\" compressionRatio=\"100\" kneeWidthDb=\"2\" lookaheadMs=\"1\" makeupGainDb=\"3\" releaseMs=\"2\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-10\"",
        "Lead Vocals" to "attackMs=\"1\" compressionRatio=\"5.2\" kneeWidthDb=\"5.5\" lookaheadMs=\"1\" makeupGainDb=\"0\" releaseMs=\"60\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-14\"",
        "Fat Vocals" to "attackMs=\"86.9\" compressionRatio=\"1.7\" kneeWidthDb=\"5\" lookaheadMs=\"1\" makeupGainDb=\"2.5\" releaseMs=\"15.2\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-32\"",
        "Power Vocals" to "attackMs=\"2.8\" compressionRatio=\"1.5\" kneeWidthDb=\"19.6\" lookaheadMs=\"46.2\" makeupGainDb=\"3\" releaseMs=\"356.3\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-16.8\"",
        "Vocal Control" to "attackMs=\"0\" compressionRatio=\"3\" kneeWidthDb=\"23.5\" lookaheadMs=\"1\" makeupGainDb=\"4.5\" releaseMs=\"196\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-15\"",
        "Vocal Touch-Up" to "attackMs=\"2\" compressionRatio=\"1.5\" kneeWidthDb=\"30\" lookaheadMs=\"0\" makeupGainDb=\"3.6\" releaseMs=\"450\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"1\" thresholdDb=\"-22\"",
        "Voice Memos Balancer" to "attackMs=\"6.5\" compressionRatio=\"10.1\" kneeWidthDb=\"5.8\" lookaheadMs=\"1\" makeupGainDb=\"4.5\" releaseMs=\"3.6\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-22.3\"",
        "Podcast/Radio" to "attackMs=\"15\" compressionRatio=\"3\" kneeWidthDb=\"24\" lookaheadMs=\"1\" makeupGainDb=\"1\" releaseMs=\"40\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-15\"",
        "Piano" to "attackMs=\"0.2\" compressionRatio=\"2\" kneeWidthDb=\"18\" lookaheadMs=\"1\" makeupGainDb=\"1\" releaseMs=\"150\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-16\"",
        "Acoustic Guitar" to "attackMs=\"15\" compressionRatio=\"2.5\" kneeWidthDb=\"8\" lookaheadMs=\"1\" makeupGainDb=\"1.5\" releaseMs=\"225\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-15\"",
        "Bass Guitar" to "attackMs=\"1\" compressionRatio=\"3\" kneeWidthDb=\"2\" lookaheadMs=\"40\" makeupGainDb=\"0\" releaseMs=\"50\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-13\"",
        "Strings" to "attackMs=\"30\" compressionRatio=\"1.8\" kneeWidthDb=\"14.3\" lookaheadMs=\"1\" makeupGainDb=\"2.5\" releaseMs=\"400\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-15\"",
        "Kick Drums" to "attackMs=\"30\" compressionRatio=\"4\" kneeWidthDb=\"0.5\" lookaheadMs=\"1\" makeupGainDb=\"2\" releaseMs=\"120\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-14\"",
        "Drums Control" to "attackMs=\"2\" compressionRatio=\"2\" kneeWidthDb=\"29\" lookaheadMs=\"1\" makeupGainDb=\"1\" releaseMs=\"40\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-12\"",
        "Climax Impulser SFX" to "attackMs=\"172\" compressionRatio=\"23.4\" kneeWidthDb=\"27.4\" lookaheadMs=\"0\" makeupGainDb=\"0\" releaseMs=\"813.4\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-55.1\"",
        "Engine Breathing SFX" to "attackMs=\"190.2\" compressionRatio=\"4.7\" kneeWidthDb=\"3.5\" lookaheadMs=\"2.3\" makeupGainDb=\"0\" releaseMs=\"0.2\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-37.7\"",
        "Great Impact SFX" to "attackMs=\"172\" compressionRatio=\"24.6\" kneeWidthDb=\"5\" lookaheadMs=\"0.6\" makeupGainDb=\"8.3\" releaseMs=\"562.6\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-49.3\"",
        "Great Body SFX" to "attackMs=\"74.6\" compressionRatio=\"2.4\" kneeWidthDb=\"0.3\" lookaheadMs=\"29.3\" makeupGainDb=\"8.6\" releaseMs=\"204.8\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-32.8\"",
        "Great Tail SFX" to "attackMs=\"1.4\" compressionRatio=\"2.4\" kneeWidthDb=\"0.3\" lookaheadMs=\"0\" makeupGainDb=\"23.9\" releaseMs=\"199.6\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-55.4\"",
        "Smack Explosion SFX" to "attackMs=\"155.5\" compressionRatio=\"5.9\" kneeWidthDb=\"24.4\" lookaheadMs=\"1.3\" makeupGainDb=\"7.1\" releaseMs=\"1.7\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-32.5\"",
    ).map { (n, s) -> n to parsePreset(s) }

    private val LIMITER_PRESETS = listOf(
        "Master Limiter" to "kneeWidthDb=\"0.1\" lookaheadMs=\"0.1\" makeupTargetDb=\"-0.1\" releaseMs=\"0.1\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-0.1\"",
        "SFX Limiter" to "kneeWidthDb=\"1\" lookaheadMs=\"1\" makeupTargetDb=\"-1\" releaseMs=\"1\" showActual=\"1\" showInput=\"0\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-3\"",
        "VO Limiter" to "kneeWidthDb=\"0\" lookaheadMs=\"0.1\" makeupTargetDb=\"-1\" releaseMs=\"10.1\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-4\"",
        "Modern" to "kneeWidthDb=\"0\" lookaheadMs=\"1\" makeupTargetDb=\"-1.5\" releaseMs=\"5\" showActual=\"0\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-1.5\"",
        "Modern Punch" to "kneeWidthDb=\"0\" lookaheadMs=\"1\" makeupTargetDb=\"-1.5\" releaseMs=\"5\" showActual=\"0\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-1.5\"",
        "Modern Punch 2" to "kneeWidthDb=\"1\" lookaheadMs=\"1\" makeupTargetDb=\"-1.5\" releaseMs=\"2\" showActual=\"1\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-1\"",
        "Play it Loud" to "kneeWidthDb=\"0\" lookaheadMs=\"1\" makeupTargetDb=\"-2.2\" releaseMs=\"5\" showActual=\"0\" showInput=\"1\" showOutput=\"1\" showTarget=\"0\" thresholdDb=\"-8.5\"",
    ).map { (n, s) -> n to parsePreset(s) }

    private val REVERB_KEYS = listOf("RoomSize", "Delay", "Reverberance", "HfDamping", "ToneLow", "ToneHigh", "WetGain", "DryGain", "StereoWidth")
    private fun rv(name: String, vararg v: Int): Pair<String, Map<String, Any>> =
        name to (REVERB_KEYS.zip(v.map { it.toDouble() }).toMap<String, Any>() + ("WetOnly" to false))
    private val REVERB_PRESETS = listOf(
        rv("Acoustic", 50, 10, 75, 100, 21, 100, -14, 0, 80), rv("Ambience", 100, 55, 100, 50, 53, 38, 0, -10, 100),
        rv("Artificial", 81, 99, 23, 62, 16, 19, -4, 0, 100), rv("Clean", 50, 10, 75, 100, 55, 100, -18, 0, 75),
        rv("Modern", 50, 10, 75, 100, 55, 100, -15, 0, 75), rv("Vocal I", 70, 20, 40, 99, 100, 50, -12, 0, 70),
        rv("Vocal II", 50, 0, 50, 99, 50, 100, -1, -1, 70), rv("Dance Vocal", 90, 2, 60, 77, 30, 51, -10, 0, 100),
        rv("Modern Vocal", 66, 27, 77, 8, 0, 51, -10, 0, 68), rv("Voice Tail", 66, 27, 100, 8, 0, 51, -6, 0, 68),
        rv("Bathroom", 16, 8, 80, 0, 0, 100, -6, 0, 100), rv("Small Room Bright", 30, 10, 50, 50, 50, 100, -1, -1, 100),
        rv("Small Room Dark", 30, 10, 50, 50, 100, 0, -1, -1, 100), rv("Medium Room", 75, 10, 40, 50, 100, 70, -1, -1, 70),
        rv("Large Room", 85, 10, 40, 50, 100, 80, 0, -6, 90), rv("Church Hall", 90, 32, 60, 50, 100, 50, 0, -12, 100),
        rv("Cathedral", 90, 16, 90, 50, 100, 0, 0, -20, 100), rv("Big Cave", 100, 55, 100, 50, 53, 38, 5, -3, 100),
    )

    private fun dist(name: String, type: Int, dc: Boolean, thr: Double, floor: Double, p1: Double, p2: Double, rep: Int) =
        name to mapOf<String, Any>("Type" to type, "DC Block" to dc, "Threshold dB" to thr, "Noise Floor" to floor,
            "Parameter 1" to p1, "Parameter 2" to p2, "Repeats" to rep)
    private val DISTORTION_PRESETS = listOf(
        dist("Hard clip -12dB, 80% make-up gain", 0, false, -12.0, -70.0, 0.0, 80.0, 0),
        dist("Soft clip -12dB, 80% make-up gain", 1, false, -12.0, -70.0, 50.0, 80.0, 0),
        dist("Fuzz Box", 1, false, -30.0, -70.0, 80.0, 80.0, 0),
        dist("Walkie-talkie", 1, false, -50.0, -70.0, 60.0, 80.0, 0),
        dist("Blues drive sustain", 2, false, -6.0, -70.0, 30.0, 80.0, 0),
        dist("Light Crunch Overdrive", 3, false, -6.0, -70.0, 20.0, 80.0, 0),
        dist("Heavy Overdrive", 4, false, -6.0, -70.0, 90.0, 80.0, 0),
        dist("3rd Harmonic (Perfect Fifth)", 5, false, -6.0, -70.0, 100.0, 60.0, 0),
        dist("Valve Overdrive", 6, true, -6.0, -70.0, 30.0, 40.0, 0),
        dist("2nd Harmonic (Octave)", 6, true, -6.0, -70.0, 50.0, 0.0, 0),
        dist("Gated Expansion Distortion", 7, false, -6.0, -70.0, 30.0, 80.0, 0),
        dist("Leveller, Light, -70dB noise floor", 8, false, -6.0, -70.0, 0.0, 50.0, 1),
        dist("Leveller, Moderate, -70dB noise floor", 8, false, -6.0, -70.0, 0.0, 50.0, 2),
        dist("Leveller, Heavy, -70dB noise floor", 8, false, -6.0, -70.0, 0.0, 50.0, 3),
        dist("Leveller, Heavier, -70dB noise floor", 8, false, -6.0, -70.0, 0.0, 50.0, 4),
        dist("Leveller, Heaviest, -70dB noise floor", 8, false, -6.0, -70.0, 0.0, 50.0, 5),
        dist("Half-wave Rectifier", 9, false, -6.0, -70.0, 50.0, 50.0, 0),
        dist("Full-wave Rectifier", 9, false, -6.0, -70.0, 100.0, 50.0, 0),
        dist("Full-wave Rectifier (DC blocked)", 9, true, -6.0, -70.0, 100.0, 50.0, 0),
        dist("Percussion Limiter", 10, false, -12.0, -70.0, 100.0, 30.0, 0),
    )

    private val WAVEFORMS = listOf("Sine", "Square", "Sawtooth", "Square, no alias", "Triangle")
    private val INTERPOLATIONS = listOf("Linear", "Logarithmic")
    private val EQ_PARAMS = listOf(
        pInt("FilterLength", "Length of filter", 8191, 21, 8191),
        pBool("InterpolateLin", "Linear frequency scale", false),
        pEnum("InterpolationMethod", "Interpolation", 0, listOf("B-spline", "Cosine", "Cubic")),
    )
    private fun showKeys(input: Boolean, output: Boolean, actual: Boolean, target: Boolean) = listOf(
        pBool("showInput", "Show input", input), pBool("showOutput", "Show output", output),
        pBool("showActual", "Show actual compression", actual), pBool("showTarget", "Show target compression", target),
    )

    /** Builds the catalogue; Nyquist paths live under [pluginsDir]. */
    fun build(pluginsDir: String): List<FxDef> {
        fun ny(
            file: String, name: String, type: String, author: String, description: String,
            params: List<PDef> = emptyList(), interactive: Boolean = params.isNotEmpty(), impl: FxImpl = FxImpl.Identity,
        ) = FxDef(name, name, type, family = "Nyquist", vendor = author, path = "$pluginsDir/$file",
            description = description, interactive = interactive, params = params, impl = impl)

        val list = mutableListOf<FxDef>()

        // ---- Built-in process effects --------------------------------------
        list += FxDef("Amplify", "Amplify", "process", description = "Increases or decreases the volume of the audio you have selected",
            params = listOf(
                pDouble("Ratio", "Amplification", 0.9, 0.003162, 316.227766, display = "dB"),
                pBool("AllowClipping", "Allow clipping", false),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                val peak = d.maxOf { Dsp.peak(it) }
                if (!p.b("AllowClipping") && peak * p.d("Ratio") > 1.0 + 1e-6) {
                    throw EngineException(ErrorCodes.INVALID_ARGS,
                        "Amplification would clip the audio (new peak ${String.format(java.util.Locale.ROOT, "%.2f", Dsp.linToDb(peak * p.d("Ratio")))} dB); allow clipping or lower the amplification")
                }
                scale(d, p.d("Ratio"))
            })
        list += FxDef("Auto Duck", "Auto Duck", "process", special = "autoDuck",
            description = "Reduces (ducks) the volume of one or more tracks whenever the volume of a specified \"control\" track reaches a particular level",
            params = listOf(
                pDouble("DuckAmountDb", "Duck amount", -12.0, -24.0, 0.0, "dB"),
                pDouble("InnerFadeDownLen", "Inner fade down length", 0.0, 0.0, 3.0, "s"),
                pDouble("InnerFadeUpLen", "Inner fade up length", 0.0, 0.0, 3.0, "s"),
                pDouble("OuterFadeDownLen", "Outer fade down length", 0.5, 0.0, 3.0, "s"),
                pDouble("OuterFadeUpLen", "Outer fade up length", 0.5, 0.0, 3.0, "s"),
                pDouble("ThresholdDb", "Threshold", -30.0, -100.0, 0.0, "dB"),
                pDouble("MaximumPause", "Maximum pause", 1.0, 0.0, DBL_MAX, "s"),
            ),
            impl = FxImpl.Process { d, rate, p, ctx ->
                val control = ctx.control ?: return@Process d
                val env = smoothAbs(control, rate, 0.05)
                val thr = Dsp.dbToLin(p.d("ThresholdDb"))
                val duck = Dsp.dbToLin(p.d("DuckAmountDb"))
                val c = exp(-1.0 / (max(p.d("OuterFadeDownLen"), 0.01) * rate))
                var g = 1.0
                eachChannel(d) { ch, a ->
                    if (ch == 0) g = 1.0
                    for (i in a.indices) {
                        val target = if (i < env.size && env[i] > thr) duck else 1.0
                        g = c * g + (1 - c) * target
                        a[i] = (a[i] * g).toFloat()
                    }
                }
            })
        list += FxDef("Bass and Treble", "Bass and Treble", "process", realtime = true,
            description = "Simple tone control effect",
            params = listOf(
                pDouble("Bass", "Bass", 0.0, -30.0, 30.0, "dB"), pDouble("Treble", "Treble", 0.0, -30.0, 30.0, "dB"),
                pDouble("Gain", "Volume", 0.0, -30.0, 30.0, "dB"), pBool("Link Sliders", "Link Volume control to Tone controls", false),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                biquads(d) { listOf(Dsp.Biquad.lowShelf(250.0, p.d("Bass"), rate), Dsp.Biquad.highShelf(4000.0, p.d("Treble"), rate)) }
                scale(d, Dsp.dbToLin(p.d("Gain")))
            })
        list += FxDef("Change Pitch", "Change Pitch", "process", description = "Changes the pitch of a track without changing its tempo",
            params = listOf(pRatio("Percentage", "Percent change", -99.0, 3000.0, semitones = true), pBool("SBSMS", "Use high quality stretching (slow)", false)))
        list += FxDef("Change Speed and Pitch", "Change Speed and Pitch", "process",
            description = "Changes the speed of a track, also changing its pitch",
            params = listOf(pRatio("Percentage", "Speed multiplier (percent change)", -99.0, 4900.0)),
            impl = FxImpl.Process { d, _, p, _ -> stretchAll(d, (d[0].size / (1 + p.d("Percentage") / 100)).roundToInt()) })
        list += FxDef("Change Tempo", "Change Tempo", "process", description = "Changes the tempo of a selection without changing its pitch",
            params = listOf(pRatio("Percentage", "Percent change", -95.0, 3000.0), pBool("SBSMS", "Use high quality stretching (slow)", false)),
            impl = FxImpl.Process { d, _, p, _ -> stretchAll(d, (d[0].size / (1 + p.d("Percentage") / 100)).roundToInt()) })
        list += FxDef("Classic Filters", "Classic Filters", "process",
            description = "Performs IIR filtering that emulates analog filters",
            params = listOf(
                pEnum("FilterType", "Filter type", 0, listOf("Butterworth", "Chebyshev Type I", "Chebyshev Type II")),
                pEnum("FilterSubtype", "Subtype", 0, listOf("Lowpass", "Highpass")),
                pInt("Order", "Order", 1, 1, 10),
                pDouble("Cutoff", "Cutoff", 1000.0, 1.0, FLT_MAX, "Hz"),
                pDouble("PassbandRipple", "Passband ripple", 1.0, 0.0, 100.0, "dB"),
                pDouble("StopbandRipple", "Minimum stopband attenuation", 30.0, 0.0, 100.0, "dB"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                val sections = (p.i("Order") + 1) / 2
                biquads(d) {
                    List(sections) {
                        if (p.i("FilterSubtype") == 0) Dsp.Biquad.lowPass(p.d("Cutoff"), 0.707, rate)
                        else Dsp.Biquad.highPass(p.d("Cutoff"), 0.707, rate)
                    }
                }
            })
        list += FxDef("Click Removal", "Click Removal", "process",
            description = "Click Removal is designed to remove clicks on audio tracks",
            params = listOf(pInt("Threshold", "Threshold (lower is more sensitive)", 200, 0, 900), pInt("Width", "Max spike width (higher is more sensitive)", 20, 0, 40)))
        list += FxDef("Compressor", "Compressor", "process", realtime = true,
            description = "Reduces \"dynamic range\", or differences between loud and quiet parts.",
            params = listOf(
                pDouble("thresholdDb", "Threshold", -10.0, -60.0, 0.0, "dB"),
                pDouble("makeupGainDb", "Make-up gain", 0.0, -30.0, 30.0, "dB"),
                pDouble("kneeWidthDb", "Knee width", 5.0, 0.0, 30.0, "dB"),
                pDouble("compressionRatio", "Ratio", 10.0, 1.0, 100.0),
                pDouble("lookaheadMs", "Lookahead", 1.0, 0.0, 1000.0, "ms"),
                pDouble("attackMs", "Attack", 30.0, 0.0, 200.0, "ms"),
                pDouble("releaseMs", "Release", 150.0, 0.0, 1000.0, "ms"),
            ) + showKeys(false, true, true, false),
            factoryPresets = COMPRESSOR_PRESETS,
            impl = FxImpl.Process { d, rate, p, _ ->
                compress(d, rate, p.d("thresholdDb"), p.d("compressionRatio"), p.d("kneeWidthDb"),
                    p.d("attackMs") / 1000, p.d("releaseMs") / 1000, p.d("makeupGainDb"))
            })
        list += FxDef("Distortion", "Distortion", "process", realtime = true,
            description = "Waveshaping distortion effect",
            params = listOf(
                pEnum("Type", "Distortion type", 0, listOf("Hard Clipping", "Soft Clipping", "Soft Overdrive", "Medium Overdrive",
                    "Hard Overdrive", "Cubic Curve (odd harmonics)", "Even Harmonics", "Expand and Compress", "Leveller",
                    "Rectifier Distortion", "Hard Limiter 1413")),
                pBool("DC Block", "DC blocking filter", false),
                pDouble("Threshold dB", "Clipping level", -6.0, -100.0, 0.0, "dB", scale = 1000.0),
                pDouble("Noise Floor", "Noise floor", -70.0, -80.0, -20.0, "dB"),
                pDouble("Parameter 1", "Drive", 50.0, 0.0, 100.0),
                pDouble("Parameter 2", "Make-up gain", 50.0, 0.0, 100.0),
                pInt("Repeats", "Repeat processing", 1, 0, 5),
            ),
            factoryPresets = DISTORTION_PRESETS,
            impl = FxImpl.Process { d, _, p, _ -> distortion(d, p) })
        list += FxDef("Echo", "Echo", "process", description = "Repeats the selected audio again and again",
            params = listOf(pDouble("Delay", "Delay time", 1.0, 0.001, FLT_MAX, "s"), pDouble("Decay", "Decay factor", 0.5, 0.0, FLT_MAX)),
            impl = FxImpl.Process { d, rate, p, _ ->
                val delay = (p.d("Delay") * rate).toInt().coerceAtLeast(1)
                val decay = p.d("Decay")
                eachChannel(d) { _, a -> for (i in delay until a.size) a[i] = (a[i] + decay * a[i - delay]).toFloat() }
            })
        list += FxDef("Fade In", "Fade In", "process", interactive = false,
            description = "Applies a linear fade-in to the selected audio", impl = FxImpl.Process { d, _, _, _ -> gainRamp(d) { it } })
        list += FxDef("Fade Out", "Fade Out", "process", interactive = false,
            description = "Applies a linear fade-out to the selected audio", impl = FxImpl.Process { d, _, _, _ -> gainRamp(d) { 1 - it } })
        list += FxDef("Filter Curve", "Filter Curve EQ", "process", special = "equalization",
            description = "Adjusts the volume levels of particular frequencies",
            params = EQ_PARAMS,
            factoryPresets = EQ_CURVES.map { it.first to mapOf<String, Any>() },
            impl = FxImpl.Process { d, rate, _, ctx -> equalize(d, rate, ctx.curve) })
        list += FxDef("Graphic EQ", "Graphic EQ", "process", special = "graphicEq",
            description = "Adjusts the volume levels of particular frequencies",
            params = EQ_PARAMS,
            factoryPresets = EQ_CURVES.filter { it.second }.map { it.first to mapOf<String, Any>() },
            impl = FxImpl.Process { d, rate, _, ctx -> equalize(d, rate, ctx.curve) })
        list += FxDef("Invert", "Invert", "process", interactive = false,
            description = "Flips the audio samples upside-down, reversing their polarity",
            impl = FxImpl.Process { d, _, _, _ -> scale(d, -1.0) })
        list += FxDef("Legacy Compressor", "Legacy Compressor", "process",
            description = "Compresses the dynamic range of audio",
            params = listOf(
                pDouble("Threshold", "Threshold", -12.0, -60.0, -1.0, "dB"),
                pDouble("NoiseFloor", "Noise Floor", -40.0, -80.0, -20.0, "dB"),
                pDouble("Ratio", "Ratio", 2.0, 1.1, 10.0),
                pDouble("AttackTime", "Attack Time", 0.2, 0.1, 5.0, "s"),
                pDouble("ReleaseTime", "Release Time", 1.0, 1.0, 30.0, "s"),
                pBool("Normalize", "Make-up gain for 0 dB after compressing", true),
                pBool("UsePeak", "Compress based on Peaks", false),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                compress(d, rate, p.d("Threshold"), p.d("Ratio"), 0.0, p.d("AttackTime"), p.d("ReleaseTime"), 0.0)
                if (p.b("Normalize")) {
                    val peak = d.maxOf { Dsp.peak(it) }
                    if (peak > 0) scale(d, 1.0 / peak)
                }
                d
            })
        list += FxDef("Limiter", "Limiter", "process", realtime = true,
            description = "Augments loudness while minimizing distortion.",
            params = listOf(
                pDouble("thresholdDb", "Threshold", -5.0, -30.0, 0.0, "dB"),
                pDouble("makeupTargetDb", "Make-up target", -1.0, -30.0, 0.0, "dB"),
                pDouble("kneeWidthDb", "Knee width", 2.0, 0.0, 10.0, "dB"),
                pDouble("lookaheadMs", "Lookahead", 1.0, 0.0, 50.0, "ms"),
                pDouble("releaseMs", "Release", 20.0, 0.0, 1000.0, "ms"),
            ) + showKeys(true, true, true, false),
            factoryPresets = LIMITER_PRESETS,
            impl = FxImpl.Process { d, rate, p, _ ->
                compress(d, rate, p.d("thresholdDb"), 100.0, p.d("kneeWidthDb"), 0.0005, p.d("releaseMs") / 1000,
                    p.d("makeupTargetDb") - p.d("thresholdDb"))
                val ceiling = Dsp.dbToLin(p.d("makeupTargetDb")).toFloat()
                eachChannel(d) { _, a -> for (i in a.indices) a[i] = a[i].coerceIn(-ceiling, ceiling) }
            })
        list += FxDef("Loudness Normalization", "Loudness Normalization", "process",
            description = "Sets the loudness of one or more tracks",
            params = listOf(
                pBool("StereoIndependent", "Normalize stereo channels independently", false),
                pDouble("LUFSLevel", "Perceived loudness", -23.0, -145.0, 0.0, "LUFS"),
                pDouble("RMSLevel", "RMS", -20.0, -145.0, 0.0, "dB"),
                pBool("DualMono", "Treat mono as dual-mono (recommended)", true),
                pInt("NormalizeTo", "Normalize to", 0, 0, 1),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                val target = Dsp.dbToLin(if (p.i("NormalizeTo") == 0) p.d("LUFSLevel") + 0.691 else p.d("RMSLevel"))
                if (p.b("StereoIndependent")) eachChannel(d) { _, a -> val r = Dsp.rms(a); if (r > 0) for (i in a.indices) a[i] = (a[i] * target / r).toFloat() }
                else {
                    val r = sqrt(d.sumOf { Dsp.rms(it).pow(2) } / d.size)
                    if (r > 0) scale(d, target / r) else d
                }
            })
        list += FxDef("Noise Reduction", "Noise Reduction", "process", special = "noiseReduction",
            description = "Removes background noise such as fans, tape noise, or hums",
            params = listOf(
                pDouble("Gain", "Noise reduction", 6.0, 0.0, 48.0, "dB"),
                pDouble("Sensitivity", "Sensitivity", 6.0, 0.01, 24.0),
                pInt("FreqSmoothing", "Frequency smoothing", 6, 0, 12, "bands"),
                pEnum("ReductionChoice", "Noise", 0, listOf("Reduce", "Residue")),
            ),
            impl = FxImpl.Process { d, rate, p, ctx ->
                val profile = ctx.noiseProfileRms
                    ?: throw EngineException(ErrorCodes.FAILED, "Select a few seconds of just noise and use Get Noise Profile first.")
                val thr = Dsp.linToDb(profile * Dsp.dbToLin(p.d("Sensitivity")))
                if (p.i("ReductionChoice") == 1) {
                    val original = Array(d.size) { d[it].copyOf() }
                    gate(d, rate, thr, -p.d("Gain"), 0.005, 0.05)
                    eachChannel(d) { ch, a -> for (i in a.indices) a[i] = original[ch][i] - a[i] }
                } else gate(d, rate, thr, -p.d("Gain"), 0.005, 0.05)
            })
        list += FxDef("Normalize", "Normalize", "process",
            description = "Sets the peak amplitude of one or more tracks",
            params = listOf(
                pDouble("PeakLevel", "Normalize peak amplitude to", -1.0, -145.0, 0.0, "dB"),
                pBool("RemoveDcOffset", "Remove DC offset (center on 0.0 vertically)", true),
                pBool("ApplyVolume", "Normalize peak amplitude", true),
                pBool("StereoIndependent", "Normalize stereo channels independently", false),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                if (p.b("RemoveDcOffset")) eachChannel(d) { _, a -> val m = a.average(); for (i in a.indices) a[i] = (a[i] - m).toFloat() }
                if (p.b("ApplyVolume")) {
                    val target = Dsp.dbToLin(p.d("PeakLevel"))
                    if (p.b("StereoIndependent")) eachChannel(d) { _, a -> val pk = Dsp.peak(a); if (pk > 0) for (i in a.indices) a[i] = (a[i] * target / pk).toFloat() }
                    else { val pk = d.maxOf { Dsp.peak(it) }; if (pk > 0) scale(d, target / pk) }
                }
                d
            })
        list += FxDef("Paulstretch", "Paulstretch", "process", description = "Paulstretch is only for an extreme time-stretch or \"stasis\" effect",
            params = listOf(pDouble("Stretch Factor", "Stretch Factor", 10.0, 1.0, FLT_MAX), pDouble("Time Resolution", "Time Resolution", 0.25, 0.00099, FLT_MAX, "s")),
            impl = FxImpl.Process { d, _, p, _ ->
                val n = (d[0].size * p.d("Stretch Factor")).toLong().coerceAtMost(48_000L * 600).toInt()
                stretchAll(d, n)
            })
        list += FxDef("Phaser", "Phaser", "process", realtime = true,
            description = "Combines phase-shifted signals with the original signal",
            params = listOf(
                pInt("Stages", "Stages", 2, 2, 24), pInt("DryWet", "Dry/Wet", 128, 0, 255),
                pDouble("Freq", "LFO Frequency", 0.4, 0.001, 4.0, "Hz"), pDouble("Phase", "LFO Start Phase", 0.0, 0.0, 360.0, "deg"),
                pInt("Depth", "Depth", 100, 0, 255), pInt("Feedback", "Feedback", 0, -100, 100, "%"),
                pDouble("Gain", "Output gain", -6.0, -30.0, 30.0, "dB"),
            ),
            impl = FxImpl.Process { d, rate, p, _ -> phaser(d, rate, p) })
        list += FxDef("Repair", "Repair", "process", interactive = false,
            description = "Sets the peak amplitude of a one or more tracks",
            impl = FxImpl.Process { d, _, _, _ ->
                if (d[0].size > 128) throw EngineException(ErrorCodes.FAILED,
                    "The Repair effect is intended to be used on very short sections of damaged audio (up to 128 samples).\n\nZoom in and select a tiny fraction of a second to repair.")
                eachChannel(d) { _, a -> val s = a.first(); val e = a.last(); for (i in a.indices) a[i] = s + (e - s) * i / max(1, a.size - 1) }
            })
        list += FxDef("Repeat", "Repeat", "process", description = "Repeats the selection the specified number of times",
            params = listOf(pInt("Count", "Repeats", 1, 1, INT_MAX)),
            impl = FxImpl.Process { d, _, p, _ ->
                val times = p.i("Count") + 1
                if (d[0].size.toLong() * times > 48_000L * 3600) throw EngineException(ErrorCodes.INVALID_ARGS, "the result would be too long")
                Array(d.size) { ch -> FloatArray(d[ch].size * times) { d[ch][it % d[ch].size] } }
            })
        list += FxDef("Reverb", "Reverb", "process", realtime = true, description = "Adds ambience or a \"hall effect\"",
            params = listOf(
                pDouble("RoomSize", "Room Size", 75.0, 0.0, 100.0, "%"), pDouble("Delay", "Pre-delay", 10.0, 0.0, 200.0, "ms"),
                pDouble("Reverberance", "Reverberance", 50.0, 0.0, 100.0, "%"), pDouble("HfDamping", "Damping", 50.0, 0.0, 100.0, "%"),
                pDouble("ToneLow", "Tone Low", 100.0, 0.0, 100.0, "%"), pDouble("ToneHigh", "Tone High", 100.0, 0.0, 100.0, "%"),
                pDouble("WetGain", "Wet Gain", -1.0, -20.0, 10.0, "dB"), pDouble("DryGain", "Dry Gain", -1.0, -20.0, 10.0, "dB"),
                pDouble("StereoWidth", "Stereo Width", 100.0, 0.0, 100.0, "%"), pBool("WetOnly", "Wet Only", false),
            ),
            factoryPresets = REVERB_PRESETS,
            impl = FxImpl.Process { d, rate, p, _ -> reverb(d, rate, p) })
        list += FxDef("Reverse", "Reverse", "process", interactive = false,
            description = "Reverses the selected audio", impl = FxImpl.Process { d, _, _, _ -> eachChannel(d) { _, a -> a.reverse() } })
        list += FxDef("Sliding Stretch", "Sliding Stretch", "process",
            description = "Allows continuous changes to the tempo and/or pitch",
            params = listOf(
                pRatio("RatePercentChangeStart", "Initial Tempo Change", -90.0, 500.0),
                pRatio("RatePercentChangeEnd", "Final Tempo Change", -90.0, 500.0),
                pDouble("PitchHalfStepsStart", "Initial Pitch Shift (semitones)", 0.0, -12.0, 12.0),
                pDouble("PitchHalfStepsEnd", "Final Pitch Shift (semitones)", 0.0, -12.0, 12.0),
                pRatio("PitchPercentChangeStart", "Initial Pitch Shift (percent)", -50.0, 100.0, semitones = true),
                pRatio("PitchPercentChangeEnd", "Final Pitch Shift (percent)", -50.0, 100.0, semitones = true),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                val avg = 1 + (p.d("RatePercentChangeStart") + p.d("RatePercentChangeEnd")) / 200
                stretchAll(d, (d[0].size / avg).roundToInt())
            })
        list += FxDef("Truncate Silence", "Truncate Silence", "process",
            description = "Automatically reduces the length of passages where the volume is below a specified level",
            params = listOf(
                pDouble("Threshold", "Threshold", -20.0, -80.0, -20.0, "dB"),
                pEnum("Action", "Action", 0, listOf("Truncate Detected Silence", "Compress Excess Silence")),
                pDouble("Minimum", "Duration", 0.5, 0.001, 10000.0, "s"),
                pDouble("Truncate", "Truncate to", 0.5, 0.0, 10000.0, "s"),
                pDouble("Compress", "Compress to", 50.0, 0.0, 99.9, "%"),
                pBool("Independent", "Truncate tracks independently", false),
                pBool("TruncateStart", "Truncate silence at the start", true),
                pBool("TruncateMiddle", "Truncate silence in the middle", true),
                pBool("TruncateEnd", "Truncate silence at the end", true),
            ),
            impl = FxImpl.Process { d, rate, p, _ -> truncateSilence(d, rate, p) })
        list += FxDef("Wahwah", "Wahwah", "process", realtime = true,
            description = "Rapid tone quality variations, like that guitar sound so popular in the 1970's",
            params = listOf(
                pDouble("Freq", "LFO Frequency", 1.5, 0.1, 4.0, "Hz"), pDouble("Phase", "LFO Start Phase", 0.0, 0.0, 360.0, "deg"),
                pInt("Depth", "Depth", 70, 0, 100, "%"), pDouble("Resonance", "Resonance", 2.5, 0.1, 10.0),
                pInt("Offset", "Wah Frequency Offset", 30, 0, 100, "%"), pDouble("Gain", "Output gain", -6.0, -30.0, 30.0, "dB"),
            ),
            impl = FxImpl.Process { d, rate, p, _ -> wahwah(d, rate, p) })

        // ---- Built-in generators ------------------------------------------
        list += FxDef("Chirp", "Chirp", "generate", description = "Generates an ascending or descending tone of one of four types",
            params = listOf(
                pDouble("StartFreq", "Frequency Start", 440.0, 1.0, DBL_MAX, "Hz"), pDouble("EndFreq", "Frequency End", 1320.0, 1.0, DBL_MAX, "Hz"),
                pDouble("StartAmp", "Amplitude Start", 0.8, 0.0, 1.0), pDouble("EndAmp", "Amplitude End", 0.1, 0.0, 1.0),
                pEnum("Waveform", "Waveform", 0, WAVEFORMS), pEnum("Interpolation", "Interpolation", 0, INTERPOLATIONS),
            ),
            impl = FxImpl.Generate { frames, ch, rate, p ->
                val a = FakeDemo.tone(rate, frames, p.d("StartFreq"), p.d("EndFreq"), p.d("StartAmp"), p.d("EndAmp"), p.i("Waveform"), p.i("Interpolation") == 1)
                Array(ch) { a.copyOf() }
            })
        list += FxDef("DTMF Tones", "DTMF Tones", "generate", description = "Generates dual-tone multi-frequency (DTMF) tones like those produced by the keypad on telephones",
            params = listOf(
                pString("Sequence", "DTMF sequence", "audacity"),
                pDouble("Duty Cycle", "Tone/silence ratio", 55.0, 0.0, 100.0, "%", scale = 10.0),
                pDouble("Amplitude", "Amplitude", 0.8, 0.001, 1.0),
            ),
            impl = FxImpl.Generate { frames, ch, rate, p -> val a = dtmf(rate, frames, p); Array(ch) { a.copyOf() } })
        list += FxDef("Noise", "Noise", "generate", description = "Generates one of three different types of noise",
            params = listOf(pEnum("Type", "Noise type", 0, listOf("White", "Pink", "Brownian")), pDouble("Amplitude", "Amplitude", 0.8, 0.0, 1.0)),
            impl = FxImpl.Generate { frames, ch, _, p -> Array(ch) { noise(frames, p.i("Type"), p.d("Amplitude"), 77L + it) } })
        list += FxDef("Silence", "Silence", "generate", description = "Creates audio of zero amplitude",
            impl = FxImpl.Generate { frames, ch, _, _ -> Array(ch) { FloatArray(frames) } })
        list += FxDef("Tone", "Tone", "generate", description = "Generates a constant frequency tone of one of four types",
            params = listOf(
                pDouble("Frequency", "Frequency", 440.0, 1.0, DBL_MAX, "Hz"), pDouble("Amplitude", "Amplitude", 0.8, 0.0, 1.0),
                pEnum("Waveform", "Waveform", 0, WAVEFORMS), pEnum("Interpolation", "Interpolation", 0, INTERPOLATIONS),
            ),
            impl = FxImpl.Generate { frames, ch, rate, p ->
                val a = FakeDemo.tone(rate, frames, p.d("Frequency"), p.d("Frequency"), p.d("Amplitude"), p.d("Amplitude"), p.i("Waveform"), false)
                Array(ch) { a.copyOf() }
            })

        // ---- Built-in analyzer --------------------------------------------
        list += FxDef("Find Clipping", "Find Clipping", "analyze", description = "Creates labels where clipping is detected",
            params = listOf(pInt("Duty Cycle Start", "Start threshold (samples)", 3, 1, INT_MAX), pInt("Duty Cycle End", "Stop threshold (samples)", 3, 1, INT_MAX)),
            impl = FxImpl.Analyze { mono, rate, t0, p ->
                val runs = labelRuns(mono, rate, t0, { abs(mono[it]) >= 0.99999f }, p.i("Duty Cycle Start"), p.i("Duty Cycle End"))
                AnalysisResult(runs.map { FLabel(it.first, it.second, "Clipped") }, "Clipping")
            })

        // ---- Nyquist plug-ins (plug-ins/*.ny of 3.7.9) ---------------------
        list += ny("adjustable-fade.ny", "Adjustable Fade", "process", "Steve Daulton", "Applies a fade with adjustable shape",
            listOf(
                pEnum("TYPE", "Fade Type", 0, listOf("Up", "Down", "SCurveUp", "SCurveDown"), listOf("Fade Up", "Fade Down", "S-Curve Up", "S-Curve Down"), "choice"),
                pDouble("CURVE", "Mid-fade Adjust (%)", 0.0, -100.0, 100.0, ny = "real"),
                pEnum("UNITS", "Start/End as", 0, listOf("Percent", "dB"), listOf("% of Original", "dB Gain"), "choice"),
                pDouble("GAIN0", "Start (or end)", 0.0, null, null, ny = "float-text"),
                pDouble("GAIN1", "End (or start)", 100.0, null, null, ny = "float-text"),
                pEnum("PRESET", "Handy Presets (override controls)", 0,
                    listOf("None", "LinearIn", "LinearOut", "ExponentialIn", "ExponentialOut", "LogarithmicIn", "LogarithmicOut",
                        "RoundedIn", "RoundedOut", "CosineIn", "CosineOut", "SCurveIn", "SCurveOut"),
                    listOf("None Selected", "Linear In", "Linear Out", "Exponential In", "Exponential Out", "Logarithmic In",
                        "Logarithmic Out", "Rounded In", "Rounded Out", "Cosine In", "Cosine Out", "S-Curve In", "S-Curve Out"), "choice"),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                val toLin = { v: Double -> if (p.i("UNITS") == 1) Dsp.dbToLin(v) else v / 100 }
                val g0 = toLin(p.d("GAIN0")); val g1 = toLin(p.d("GAIN1"))
                val down = p.i("TYPE") % 2 == 1
                val s = p.i("TYPE") >= 2
                gainRamp(d) { x0 ->
                    val x = if (s) x0 * x0 * (3 - 2 * x0) else x0
                    if (down) g1 + (g0 - g1) * x else g0 + (g1 - g0) * x
                }
            })
        list += ny("beat.ny", "Beat Finder", "analyze", "Audacity", "Places labels at beats",
            listOf(pInt("THRESVAL", "Threshold Percentage", 65, 5, 100, ny = "int")),
            impl = FxImpl.Analyze { mono, rate, t0, p ->
                val env = smoothAbs(mono, rate, 0.1)
                val thr = (env.maxOrNull() ?: 0f) * p.i("THRESVAL") / 100f
                val labels = ArrayList<FLabel>()
                var above = false
                for (i in env.indices) {
                    if (!above && env[i] > thr) labels += FLabel(t0 + i / rate, t0 + i / rate, "B")
                    above = env[i] > thr
                }
                AnalysisResult(labels, "Beat Finder")
            })
        list += ny("clipfix.ny", "Clip Fix", "process", "Benjamin Schwartz and Steve Daulton", "Reconstructs clipped peaks",
            listOf(
                pDouble("THRESHOLD", "Threshold of Clipping (%)", 95.0, 0.0, 100.0, ny = "float"),
                pDouble("GAIN", "Reduce amplitude to allow for restored peaks (dB)", -9.0, -30.0, 0.0, ny = "float"),
            ),
            impl = FxImpl.Process { d, _, p, _ -> scale(d, Dsp.dbToLin(p.d("GAIN"))) })
        list += ny("crossfadeclips.ny", "Crossfade Clips", "process", "Steve Daulton", "Crossfades two adjacent clips", interactive = false)
        list += ny("crossfadetracks.ny", "Crossfade Tracks", "process", "Steve Daulton", "Crossfades two tracks",
            listOf(
                pEnum("TYPE", "Fade TYPE", 0, listOf("ConstantGain", "ConstantPower1", "ConstantPower2", "CustomCurve"),
                    listOf("Constant Gain", "Constant Power 1", "Constant Power 2", "Custom Curve"), "choice"),
                pDouble("CURVE", "Custom curve", 0.0, 0.0, 1.0, ny = "real"),
                pEnum("DIRECTION", "Fade direction", 0, listOf("Automatic", "OutIn", "InOut"),
                    listOf("Automatic", "Alternating Out / In", "Alternating In / Out"), "choice"),
            ))
        list += ny("delay.ny", "Delay", "process", "Steve Daulton", "A configurable delay effect",
            listOf(
                pEnum("DELAY-TYPE", "Delay type", 0, listOf("Regular", "BouncingBall", "ReverseBouncingBall"),
                    listOf("Regular", "Bouncing Ball", "Reverse Bouncing Ball"), "choice"),
                pDouble("DGAIN", "Delay level per echo (dB)", -6.0, -30.0, 1.0, ny = "real"),
                pDouble("DELAY", "Delay time (seconds)", 0.3, 0.0, 5.0, ny = "real"),
                pEnum("PITCH-TYPE", "Pitch change effect", 0, listOf("PitchTempo", "LQPitchShift", "HQPitchShift"),
                    listOf("Pitch/Tempo", "Low-quality Pitch Shift", "High-quality Pitch Shift"), "choice"),
                pDouble("SHIFT", "Pitch change per echo (semitones)", 0.0, -2.0, 2.0, ny = "real"),
                pInt("NUMBER", "Number of echoes", 5, 1, 30, ny = "int"),
                pEnum("CONSTRAIN", "Allow duration to change", 0, listOf("Yes", "No"), ny = "choice"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                val src = Array(d.size) { d[it].copyOf() }
                val step = (p.d("DELAY") * rate).toInt()
                if (step > 0) for (k in 1..p.i("NUMBER")) {
                    val g = Dsp.dbToLin(p.d("DGAIN") * k).toFloat()
                    eachChannel(d) { ch, a -> for (i in k * step until a.size) a[i] += src[ch][i - k * step] * g }
                }
                d
            })
        list += ny("highpass.ny", "High-Pass Filter", "process", "Dominic Mazzoni", "Passes frequencies above its cutoff frequency",
            listOf(
                pDouble("FREQUENCY", "Frequency (Hz)", 1000.0, 0.0, null, "Hz", ny = "float-text"),
                pEnum("ROLLOFF", "Roll-off (dB per octave)", 0, listOf("dB6", "dB12", "dB24", "dB36", "dB48"),
                    listOf("6 dB", "12 dB", "24 dB", "36 dB", "48 dB"), "choice"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                biquads(d) { List(max(1, p.i("ROLLOFF"))) { Dsp.Biquad.highPass(p.d("FREQUENCY"), if (p.i("ROLLOFF") == 0) 0.5 else 0.707, rate) } }
            })
        list += ny("label-sounds.ny", "Label Sounds", "analyze", "Steve Daulton", "Labels areas of sound",
            listOf(
                pDouble("THRESHOLD", "Threshold level (dB)", -30.0, -100.0, 0.0, "dB", ny = "float"),
                pEnum("MEASUREMENT", "Threshold measurement", 0, listOf("peak", "avg", "rms"), listOf("Peak level", "Average level", "RMS level"), "choice"),
                pDouble("SIL-DUR", "Minimum silence duration", 1.0, 0.01, 3600.0, "s", ny = "time"),
                pDouble("SND-DUR", "Minimum label interval", 1.0, 0.01, 7200.0, "s", ny = "time"),
                pEnum("TYPE", "Label type", 2, listOf("before", "after", "around", "between"),
                    listOf("Point before sound", "Point after sound", "Region around sounds", "Region between sounds"), "choice"),
                pDouble("PRE-OFFSET", "Maximum leading silence", 0.0, 0.0, null, "s", ny = "time"),
                pDouble("POST-OFFSET", "Maximum trailing silence", 0.0, 0.0, null, "s", ny = "time"),
                pString("TEXT", "Label text", "Sound ##1", "string"),
            ),
            impl = FxImpl.Analyze { mono, rate, t0, p ->
                val env = smoothAbs(mono, rate, 0.05)
                val thr = Dsp.dbToLin(p.d("THRESHOLD")).toFloat()
                val runs = labelRuns(mono, rate, t0, { env[it] > thr }, (0.01 * rate).toInt(), (p.d("SIL-DUR") * rate).toInt())
                val labels = runs.mapIndexed { k, (a, b) ->
                    val text = p.s("TEXT").replace("##1", (k + 1).toString().padStart(2, '0')).replace("#1", "${k + 1}")
                    when (p.i("TYPE")) {
                        0 -> FLabel(a, a, text)
                        1 -> FLabel(b, b, text)
                        else -> FLabel(a, b, text)
                    }
                }
                AnalysisResult(labels, "Label Sounds", if (labels.isEmpty()) "No sounds found.\nTry lowering 'Threshold' or reduce 'Minimum sound duration'." else null)
            })
        list += ny("legacy-limiter.ny", "Legacy Limiter", "process", "Steve Daulton", "Limits the dynamic range",
            listOf(
                pEnum("TYPE", "Type", 0, listOf("SoftLimit", "HardLimit", "SoftClip", "HardClip"), listOf("Soft Limit", "Hard Limit", "Soft Clip", "Hard Clip"), "choice"),
                pDouble("GAIN-L", "Input Gain (dB) mono/Left", 0.0, 0.0, 10.0, "dB", ny = "real"),
                pDouble("GAIN-R", "Input Gain (dB) Right channel", 0.0, 0.0, 10.0, "dB", ny = "real"),
                pDouble("THRESH", "Limit to (dB)", -3.0, -10.0, 0.0, "dB", ny = "real"),
                pDouble("HOLD", "Hold (ms)", 10.0, 1.0, 50.0, "ms", ny = "real"),
                pEnum("MAKEUP", "Apply Make-up Gain", 0, listOf("No", "Yes"), ny = "choice"),
            ),
            impl = FxImpl.Process { d, _, p, _ ->
                val limit = Dsp.dbToLin(p.d("THRESH"))
                eachChannel(d) { ch, a ->
                    val g = Dsp.dbToLin(if (ch == 0) p.d("GAIN-L") else p.d("GAIN-R"))
                    for (i in a.indices) {
                        val x = a[i] * g
                        val y = if (p.i("TYPE") % 2 == 1) x.coerceIn(-limit, limit) else limit * tanh(x / limit)
                        a[i] = (if (p.i("MAKEUP") == 1) y / limit else y).toFloat()
                    }
                }
            })
        list += ny("lowpass.ny", "Low-Pass Filter", "process", "Dominic Mazzoni", "Passes frequencies below its cutoff frequency",
            listOf(
                pDouble("FREQUENCY", "Frequency (Hz)", 1000.0, 0.0, null, "Hz", ny = "float-text"),
                pEnum("ROLLOFF", "Roll-off (dB per octave)", 0, listOf("dB6", "dB12", "dB24", "dB36", "dB48"),
                    listOf("6 dB", "12 dB", "24 dB", "36 dB", "48 dB"), "choice"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                biquads(d) { List(max(1, p.i("ROLLOFF"))) { Dsp.Biquad.lowPass(p.d("FREQUENCY"), if (p.i("ROLLOFF") == 0) 0.5 else 0.707, rate) } }
            })
        list += ny("noisegate.ny", "Noise Gate", "process", "Steve Daulton", "Reduces the level of quiet passages",
            listOf(
                pEnum("MODE", "Select Function", 0, listOf("Gate", "Analyze"), listOf("Gate", "Analyze Noise Level"), "choice"),
                pEnum("STEREO-LINK", "Stereo Linking", 0, listOf("LinkStereo", "DoNotLink"), listOf("Link Stereo Tracks", "Don't Link Stereo"), "choice"),
                pDouble("THRESHOLD", "Gate threshold (dB)", -40.0, -96.0, -6.0, "dB", ny = "float"),
                pDouble("GATE-FREQ", "Gate frequencies above (kHz)", 0.0, 0.0, 10.0, "kHz", ny = "float"),
                pDouble("LEVEL-REDUCTION", "Level reduction (dB)", -24.0, -100.0, 0.0, "dB", ny = "float"),
                pDouble("ATTACK", "Attack (ms)", 10.0, 1.0, 1000.0, "ms", ny = "float"),
                pDouble("HOLD", "Hold (ms)", 50.0, 0.0, 2000.0, "ms", ny = "float"),
                pDouble("DECAY", "Decay (ms)", 100.0, 10.0, 4000.0, "ms", ny = "float"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                gate(d, rate, p.d("THRESHOLD"), p.d("LEVEL-REDUCTION"), p.d("ATTACK") / 1000, p.d("DECAY") / 1000)
            })
        list += ny("notch.ny", "Notch Filter", "process", "Steve Daulton and Bill Wharrie", "Removes a narrow band of frequencies",
            listOf(
                pDouble("FREQUENCY", "Frequency (Hz)", 60.0, 0.0, null, "Hz", ny = "float-text"),
                pDouble("Q", "Q (higher value reduces width)", 1.0, 0.1, 1000.0, ny = "float-text"),
            ),
            impl = FxImpl.Process { d, rate, p, _ -> biquads(d) { listOf(Dsp.Biquad.notch(p.d("FREQUENCY"), p.d("Q"), rate)) } })
        list += ny("nyquist-plug-in-installer.ny", "Nyquist Plugin Installer", "tool", "Steve Daulton", "Installs Nyquist plug-ins",
            listOf(
                pString("FILES", "Select file(s) to install", "", "file"),
                pEnum("OVERWRITE", "Allow overwriting", 0, listOf("Disallow", "Allow"), ny = "choice"),
            ),
            impl = FxImpl.Tool { p ->
                if (p.s("FILES").isBlank()) false to "Error.\nNo file selected." else
                    throw EngineException(ErrorCodes.UNSUPPORTED, "Installing plug-ins is not supported by the fake engine")
            })
        list += ny("pluck.ny", "Pluck", "generate", "David R.Sky", "A synthesized pluck tone with abrupt or gradual fade-out",
            listOf(
                pInt("PITCH", "Pluck MIDI pitch", 60, 1, 120, ny = "int"),
                pEnum("FADE", "Fade-out type", 0, listOf("Abrupt", "Gradual"), ny = "choice"),
                pDouble("DUR", "Duration (60s max)", 1.0, 0.0, 60.0, "s", ny = "time"),
            ),
            impl = FxImpl.Generate { _, ch, rate, p ->
                val a = karplusStrong(rate, (p.d("DUR") * rate).toInt().coerceAtLeast(1), p.i("PITCH"), p.i("FADE") == 1)
                Array(ch) { a.copyOf() }
            })
        list += ny("rhythmtrack.ny", "Rhythm Track", "generate", "Dominic Mazzoni, David R. Sky and Steve Daulton", "Generates a click track",
            listOf(
                pDouble("TEMPO", "Tempo (bpm)", 120.0, 30.0, 300.0, "bpm", ny = "real"),
                pInt("TIMESIG", "Beats per bar", 4, 1, 20, ny = "int"),
                pDouble("SWING", "Swing amount", 0.0, -1.0, 1.0, ny = "float"),
                pInt("BARS", "Number of bars", 16, 0, 1000, ny = "int"),
                pDouble("CLICK-TRACK-DUR", "Rhythm track duration", 0.0, 0.0, null, "s", ny = "time"),
                pDouble("OFFSET", "Start time offset", 0.0, 0.0, null, "s", ny = "time"),
                pEnum("CLICK-TYPE", "Beat sound", 0,
                    listOf("Metronome", "Ping (short)", "Ping (long)", "Cowbell", "ResonantNoise", "NoiseClick", "Drip (short)", "Drip (long)"),
                    listOf("Metronome Tick", "Ping (short)", "Ping (long)", "Cowbell", "Resonant Noise", "Noise Click", "Drip (short)", "Drip (long)"), "choice"),
                pInt("HIGH", "MIDI pitch of strong beat", 84, 18, 116, ny = "int"),
                pInt("LOW", "MIDI pitch of weak beat", 80, 18, 116, ny = "int"),
            ),
            impl = FxImpl.Generate { _, ch, rate, p -> val a = rhythm(rate, p); Array(ch) { a.copyOf() } })
        list += ny("rissetdrum.ny", "Risset Drum", "generate", "Steven Jones", "Produces a realistic drum sound",
            listOf(
                pDouble("FREQ", "Frequency (Hz)", 100.0, 50.0, 2000.0, "Hz", ny = "real"),
                pDouble("DECAY", "Decay (seconds)", 2.0, 0.1, 60.0, "s", ny = "real"),
                pDouble("CF", "Center frequency of noise (Hz)", 500.0, 100.0, 5000.0, "Hz", ny = "real"),
                pDouble("BW", "Width of noise band (Hz)", 400.0, 10.0, 1000.0, "Hz", ny = "real"),
                pDouble("NOISE", "Amount of noise in mix (percent)", 25.0, 0.0, 100.0, "%", ny = "real"),
                pDouble("GAIN", "Amplitude (0 - 1)", 0.8, 0.0, 1.0, ny = "real"),
            ),
            impl = FxImpl.Generate { _, ch, rate, p ->
                val frames = (p.d("DECAY") * rate).toInt().coerceAtLeast(1)
                val nz = noise(frames, 0, 1.0, 5)
                Dsp.Biquad.peaking(p.d("CF"), p.d("CF") / p.d("BW"), 12.0, rate).process(nz)
                val mixN = p.d("NOISE") / 100
                val a = FloatArray(frames) { i ->
                    val env = exp(-i / rate * 5 / p.d("DECAY"))
                    (p.d("GAIN") * env * ((1 - mixN) * Osc.sin01(p.d("FREQ") * i / rate) + mixN * nz[i] * 0.3)).toFloat()
                }
                Array(ch) { a.copyOf() }
            })
        list += ny("sample-data-export.ny", "Sample Data Export", "tool", "Steve Daulton", "Exports sample values to a text file",
            listOf(
                pInt("NUMBER", "Limit output to first", 100, 1, 1000000, "samples", ny = "int-text"),
                pEnum("UNITS", "Measurement scale", 0, listOf("dB", "Linear"), ny = "choice"),
                pString("FILENAME", "Export data to", "*default*/sample-data.txt", "file"),
                pEnum("FILEFORMAT", "Index (text files only)", 0, listOf("None", "Count", "Time"), listOf("None", "Sample Count", "Time Indexed"), "choice"),
                pEnum("HEADER", "Include header information", 0, listOf("None", "Minimal", "Standard", "All"), ny = "choice"),
                pString("OPTEXT", "Optional header text", "", "string"),
                pEnum("CHANNEL-LAYOUT", "Channel layout for stereo", 0, listOf("SameLine", "Alternate", "LFirst"),
                    listOf("L-R on Same Line", "Alternate Lines", "L Channel First"), "choice"),
                pEnum("MESSAGES", "Show messages", 0, listOf("Yes", "Errors", "None"), listOf("Yes", "Errors Only", "None"), "choice"),
            ),
            impl = FxImpl.Tool { p -> true to "${p.i("NUMBER")} samples written to ${p.s("FILENAME").replace("*default*", "~")}" })
        list += ny("sample-data-import.ny", "Sample Data Import", "tool", "Steve Daulton", "Imports sample values from a text file",
            listOf(
                pString("FILENAME", "Select file", "*default*/sample-data.txt", "file"),
                pEnum("BAD-DATA", "Invalid data handling", 0, listOf("ThrowError", "ReadAsZero"), listOf("Throw Error", "Read as Zero"), "choice"),
            ),
            impl = FxImpl.Tool { p -> throw EngineException(ErrorCodes.NOT_FOUND, "File not found: ${p.s("FILENAME")}") })
        list += ny("equalabel.ny", "Regular Interval Labels", "tool", "Steve Daulton", "Places labels at regular intervals",
            listOf(
                pEnum("MODE", "Create labels based on", 0, listOf("Both", "Number", "Interval"), listOf("Number and Interval", "Number of Labels", "Label Interval"), "choice"),
                pInt("TOTALNUM", "Number of labels", 10, 1, 1000, ny = "int-text"),
                pDouble("INTERVAL", "Label interval (seconds)", 10.0, 0.001, 3600.0, "s", ny = "float-text"),
                pDouble("REGION", "Length of label region (seconds)", 0.0, 0.0, 3600.0, "s", ny = "float-text"),
                pEnum("ADJUST", "Adjust label interval to fit length", 0, listOf("No", "Yes"), ny = "choice"),
                pString("LABELTEXT", "Label text", "Label", "string"),
                pEnum("ZEROS", "Minimum number of digits in label", 2,
                    listOf("TextOnly", "OneBefore", "TwoBefore", "ThreeBefore", "OneAfter", "TwoAfter", "ThreeAfter"),
                    listOf("None - Text Only", "1 (Before Label)", "2 (Before Label)", "3 (Before Label)", "1 (After Label)", "2 (After Label)", "3 (After Label)"), "choice"),
                pInt("FIRSTNUM", "Begin numbering from", 1, 0, null, ny = "int-text"),
                pEnum("VERBOSE", "Message on completion", 0, listOf("Details", "Warnings", "None"), listOf("Details", "Warnings only", "None"), "choice"),
            ),
            impl = FxImpl.Analyze { mono, rate, t0, p ->
                val length = mono.size / rate
                val interval = when (p.i("MODE")) {
                    1 -> length / p.i("TOTALNUM").coerceAtLeast(1)
                    else -> p.d("INTERVAL")
                }
                val count = when (p.i("MODE")) {
                    0, 1 -> p.i("TOTALNUM")
                    else -> floor(length / interval).toInt() + 1
                }.coerceAtMost(1000)
                val zeros = p.i("ZEROS")
                val digits = if (zeros == 0) 0 else (zeros - 1) % 3 + 1
                val labels = List(count) { k ->
                    val num = (p.i("FIRSTNUM") + k).toString().padStart(digits, '0')
                    val text = when {
                        zeros == 0 -> p.s("LABELTEXT")
                        zeros <= 3 -> num + p.s("LABELTEXT")
                        else -> p.s("LABELTEXT") + num
                    }
                    val t = t0 + k * interval
                    FLabel(t, t + p.d("REGION"), text)
                }
                AnalysisResult(labels, "Label", if (p.i("VERBOSE") == 0) "$count labels created at intervals of ${String.format(java.util.Locale.ROOT, "%.3f", interval)} seconds." else null)
            })
        list += ny("rms.ny", "Measure RMS", "analyze", "Steve Daulton", "Measures the RMS level of the selection", interactive = false,
            impl = FxImpl.Analyze { mono, _, _, _ ->
                val db = Dsp.linToDb(max(Dsp.rms(mono), 1e-10))
                AnalysisResult(emptyList(), "Measure RMS", "Mono: ${String.format(java.util.Locale.ROOT, "%.2f", db)} dB")
            })
        list += ny("ShelfFilter.ny", "Shelf Filter", "process", "Steve Daulton", "Boosts or cuts frequencies below or above a frequency",
            listOf(
                pEnum("TYPE", "Filter type", 0, listOf("Low", "High"), listOf("Low-shelf", "High-shelf"), "choice"),
                pInt("HZ", "Frequency (Hz)", 1000, 10, 10000, "Hz", ny = "int"),
                pInt("GAIN", "Amount (dB)", -6, -72, 72, "dB", ny = "int"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                biquads(d) {
                    listOf(if (p.i("TYPE") == 0) Dsp.Biquad.lowShelf(p.d("HZ"), p.d("GAIN"), rate) else Dsp.Biquad.highShelf(p.d("HZ"), p.d("GAIN"), rate))
                }
            })
        list += ny("spectral-delete.ny", "Spectral Delete", "process", "Steve Daulton", "Deletes the selected frequency range", interactive = false)
        list += ny("SpectralEditMulti.ny", "Spectral Edit Multi Tool", "process", "Paul Licameli", "Spectral editing", interactive = false)
        list += ny("SpectralEditParametricEQ.ny", "Spectral Edit Parametric EQ", "process", "Paul Licameli", "Spectral editing",
            listOf(pDouble("CONTROL-GAIN", "Gain (dB)", 0.0, -24.0, 24.0, "dB", ny = "real")),
            impl = FxImpl.Process { d, rate, p, _ -> biquads(d) { listOf(Dsp.Biquad.peaking(1000.0, 1.0, p.d("CONTROL-GAIN"), rate)) } })
        list += ny("SpectralEditShelves.ny", "Spectral Edit Shelves", "process", "Paul Licameli", "Spectral editing",
            listOf(pDouble("CONTROL-GAIN", "Gain (dB)", 0.0, -24.0, 24.0, "dB", ny = "real")),
            impl = FxImpl.Process { d, rate, p, _ -> biquads(d) { listOf(Dsp.Biquad.highShelf(1000.0, p.d("CONTROL-GAIN"), rate)) } })
        list += ny("StudioFadeOut.ny", "Studio Fade Out", "process", "Steve Daulton", "Applies a more musical fade out", interactive = false,
            impl = FxImpl.Process { d, _, _, _ -> gainRamp(d) { x -> 0.5 + 0.5 * kotlin.math.cos(PI * x) } })
        list += ny("tremolo.ny", "Tremolo", "process", "Steve Daulton", "Modulates the volume",
            listOf(
                pEnum("WAVE", "Waveform type", 0, listOf("Sine", "Triangle", "Sawtooth", "InverseSawtooth", "Square"),
                    listOf("Sine", "Triangle", "Sawtooth", "Inverse Sawtooth", "Square"), "choice"),
                pInt("PHASE", "Starting phase (degrees)", 0, -180, 180, "deg", ny = "int"),
                pInt("WET", "Wet level (percent)", 40, 1, 100, "%", ny = "int"),
                pDouble("LFO", "Frequency (Hz)", 4.0, 0.001, 1000.0, "Hz", ny = "float-text"),
            ),
            impl = FxImpl.Process { d, rate, p, _ ->
                val wet = p.i("WET") / 100.0
                eachChannel(d) { _, a -> for (i in a.indices) a[i] = (a[i] * (1 - wet * lfo(i, rate, p.d("LFO"), p.i("PHASE").toDouble()))).toFloat() }
            })
        list += ny("vocoder.ny", "Vocoder", "process", "Edgar-RFT and Steve Daulton", "Synthesizes audio (usually a voice) in the left channel with the right",
            listOf(
                pDouble("DST", "Distance: (1 to 120, default = 20)", 20.0, 1.0, 120.0, ny = "float"),
                pEnum("MST", "Output choice", 0, listOf("BothChannels", "RightOnly"), listOf("Both Channels", "Right Only"), "choice"),
                pInt("BANDS", "Number of vocoder bands", 40, 10, 240, ny = "int"),
                pDouble("TRACK-VL", "Amplitude of carrier wave (percent)", 100.0, 0.0, 100.0, "%", ny = "float"),
                pDouble("NOISE-VL", "Amplitude of white noise (percent)", 0.0, 0.0, 100.0, "%", ny = "float"),
                pDouble("RADAR-VL", "Amplitude of Radar Needles (percent)", 0.0, 0.0, 100.0, "%", ny = "float"),
                pDouble("RADAR-F", "Frequency of Radar Needles (Hz)", 30.0, 1.0, 100.0, "Hz", ny = "float"),
            ))
        list += FxDef("Nyquist Prompt", "Nyquist Prompt", "tool", family = "Nyquist", vendor = "Audacity", path = "Nyquist Prompt",
            description = "Runs Nyquist code",
            params = listOf(pString("Command", "Command", ""), pString("Parameters", "Parameters", "")),
            impl = FxImpl.Tool { p ->
                if (p.s("Command").isBlank()) false to "Nyquist returned the value: nil"
                else throw EngineException(ErrorCodes.UNSUPPORTED, "Nyquist code cannot run in the fake engine")
            })
        return list
    }

    /** Menu sections of API.md §5.4 ("default" grouping of 3.7.9). */
    fun menus(effects: List<FxDef>): EffectMenus {
        val byName = effects.associateBy { it.name }
        val effectSections = ArrayList<MenuSection>()
        val grouped = HashSet<String>()
        for ((title, names) in MENU_GROUPS) {
            val ids = names.mapNotNull { byName[it] }.filter { it.type == "process" }.sortedBy { it.name.lowercase() }
            if (ids.isNotEmpty()) {
                effectSections += MenuSection(title, ids.map { it.id })
                grouped += ids.map { it.id }
            }
        }
        effects.filter { it.type == "process" && it.id !in grouped }.groupBy { it.vendor }.toSortedMap()
            .forEach { (vendor, list) -> effectSections += MenuSection(vendor, list.sortedBy { it.name.lowercase() }.map { it.id }) }
        fun flat(type: String) = listOf(MenuSection(null, effects.filter { it.type == type }.sortedBy { it.name.lowercase() }.map { it.id }))
            .filter { it.ids.isNotEmpty() }
        return EffectMenus(generate = flat("generate"), effect = effectSections, analyze = flat("analyze"), tools = flat("tool"))
    }

    /** Converts preset values (Number/Boolean/String) to stored primitives. */
    fun presetValues(def: FxDef, values: Map<String, Any>): Map<String, JsonPrimitive> {
        val out = HashMap<String, JsonPrimitive>()
        for (p in def.params) {
            val v = values[p.key] ?: continue
            out[p.key] = when (v) {
                is Boolean -> JsonPrimitive(v)
                is Int -> if (p.kind == PKind.DOUBLE) JsonPrimitive(v.toDouble()) else JsonPrimitive(v)
                is Number -> if (p.kind == PKind.INT || p.kind == PKind.ENUM) JsonPrimitive(v.toLong()) else JsonPrimitive(v.toDouble())
                else -> JsonPrimitive(v.toString())
            }
        }
        return out
    }

}
