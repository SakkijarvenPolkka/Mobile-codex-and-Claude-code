/*
 * Audacity Android port — deterministic procedural audio for the fake engine
 * (demo project, simulated microphone, stand-in for imported files).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object FakeDemo {
    private fun midiToHz(m: Double) = 440.0 * 2.0.pow((m - 69) / 12.0)

    /** A plucked-synth melody (0.5 s per note) with harmonics and vibrato. */
    fun melody(rate: Double, seconds: Double): FloatArray {
        val notes = intArrayOf(60, 64, 67, 72, 71, 67, 64, 62, 60, 65, 69, 72, 70, 67, 64, 60)
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val noteLen = 0.5
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / rate
            val k = floor(t / noteLen).toInt()
            val local = t - k * noteLen
            val f = midiToHz(notes[k % notes.size].toDouble()) * (1.0 + 0.004 * Osc.sin01(5.5 * t))
            phase += f / rate
            val env = when {
                local < 0.01 -> local / 0.01
                local < 0.12 -> 1.0 - 0.4 * (local - 0.01) / 0.11
                local < noteLen - 0.06 -> 0.6 * exp(-(local - 0.12) * 1.2)
                else -> 0.6 * exp(-(noteLen - 0.18) * 1.2) * max(0.0, (noteLen - local) / 0.06)
            }
            val v = 0.62 * Osc.sin01(phase) + 0.2 * Osc.sin01(2 * phase) + 0.08 * Osc.sin01(3 * phase)
            out[i] = (0.75 * env * v).toFloat()
        }
        return out
    }

    /** Speech-like signal: a buzzy voiced source with a pitch contour, gated
     *  into syllables with short pauses, plus some fricative noise. */
    fun voice(rate: Double, seconds: Double, seed: Long): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        var lp = 0.0
        for (i in 0 until n) {
            val t = i / rate
            val syl = floor(t / 0.22).toLong()
            val local = t - syl * 0.22
            val voiced = Osc.hash(seed, syl) > -0.55
            val sylEnv = if (local < 0.18) Osc.sin01(local / 0.36).toDouble() else 0.0
            val phraseGap = if ((t % 2.2) > 1.9) 0.0 else 1.0
            val f0 = 130.0 + 25.0 * Osc.sin01(0.7 * t + 0.1 * Osc.hash(seed, syl / 3)) + 10.0 * Osc.hash(seed + 1, syl)
            phase += f0 / rate
            val p = phase - floor(phase)
            val buzz = (2.0 * p - 1.0) * 0.5 + 0.35 * Osc.sin01(2.0 * phase)
            lp += 0.18 * (buzz - lp) // soften the sawtooth
            val noise = Osc.hash(seed + 7, i.toLong()) * 0.25
            val v = if (voiced) lp * 1.4 else noise
            out[i] = (0.55 * sylEnv * phraseGap * v).toFloat()
        }
        return out
    }

    /** A stereo drum loop (100 BPM) with a soft pad, as two channels. */
    fun drums(rate: Double, seconds: Double): Array<FloatArray> {
        val n = (rate * seconds).toInt()
        val l = FloatArray(n)
        val r = FloatArray(n)
        val beat = 0.6
        val pad = doubleArrayOf(57.0, 60.0, 64.0, 69.0).map { midiToHz(it) }
        for (i in 0 until n) {
            val t = i / rate
            val b = floor(t / beat).toInt()
            val tb = t - b * beat
            val eighth = t % (beat / 2)
            var kick = 0.0
            if (b % 2 == 0 && tb < 0.35) {
                val f = 50.0 + 70.0 * exp(-tb * 30)
                kick = Osc.sin01(f * tb) * exp(-tb * 9) * 0.9
            }
            var snare = 0.0
            if (b % 2 == 1 && tb < 0.25) snare = (Osc.hash(11, i.toLong()) * 0.6 + Osc.sin01(190 * tb) * 0.3) * exp(-tb * 18)
            val hat = if (eighth < 0.05) Osc.hash(23, i.toLong()) * exp(-eighth * 90) * 0.22 else 0.0
            val swell = 0.5 - 0.5 * Osc.sin01(t / 6.0 + 0.25)
            var padL = 0.0
            var padR = 0.0
            for ((k, f) in pad.withIndex()) {
                padL += Osc.sin01(f * t * (1.0 + 0.0015 * k))
                padR += Osc.sin01(f * t * (1.0 - 0.0015 * k) + 0.25)
            }
            val p = 0.06 * swell
            l[i] = (0.62 * kick + 0.5 * snare + 0.7 * hat + p * padL).toFloat().coerceIn(-0.98f, 0.98f)
            r[i] = (0.62 * kick + 0.42 * snare + 0.45 * hat + p * padR).toFloat().coerceIn(-0.98f, 0.98f)
        }
        return arrayOf(l, r)
    }

    /** Simulated microphone input: frame [n] of channel [ch] (deterministic,
     *  so a recording is reproducible whatever the polling rate). */
    fun microphone(n: Long, rate: Double, ch: Int): Float {
        val t = n / rate
        val syl = floor(t / 0.25).toLong()
        val local = t - syl * 0.25
        val talk = if (Osc.hash(99, syl) > -0.3 && (t % 3.0) < 2.4) Osc.sin01(local / 0.5).toDouble() else 0.0
        val f0 = 170.0 + 30.0 * Osc.sin01(0.5 * t)
        val v = 0.5 * Osc.sin01(f0 * t) + 0.25 * Osc.sin01(2 * f0 * t) + 0.12 * Osc.sin01(3 * f0 * t)
        val noise = Osc.hash(101 + ch.toLong(), n) * 0.01
        return (0.6 * talk * v * (if (ch == 0) 1.0 else 0.85) + noise).toFloat()
    }

    /** Stand-in audio for a file the fake cannot decode (deterministic from
     *  [seed], e.g. the file name's hash). */
    fun procedural(seed: Long, rate: Double, seconds: Double, channels: Int): Array<FloatArray> {
        val base = melody(rate, seconds)
        val shift = 1 + ((seed ushr 3) and 7).toInt()
        return Array(channels) { ch ->
            val out = FloatArray(base.size)
            for (i in out.indices) {
                val j = (i * shift / 4) % base.size
                out[i] = (base[j] * 0.8f + Osc.hash(seed + ch, i.toLong()) * 0.02f) * (if (ch == 0) 1f else 0.9f)
            }
            out
        }
    }

    /** Tone generator of Audacity's Tone/Chirp (waveform index as in the
     *  `Waveform` enum: Sine, Square, Sawtooth, Square (no alias), Triangle). */
    fun tone(rate: Double, frames: Int, f0: Double, f1: Double, a0: Double, a1: Double, waveform: Int, logInterp: Boolean): FloatArray {
        val out = FloatArray(frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val x = if (frames > 1) i.toDouble() / (frames - 1) else 0.0
            val f = if (logInterp && f0 > 0 && f1 > 0) f0 * (f1 / f0).pow(x) else f0 + (f1 - f0) * x
            val a = if (logInterp && a0 > 0 && a1 > 0) a0 * (a1 / a0).pow(x) else a0 + (a1 - a0) * x
            val p = phase - floor(phase)
            val v = when (waveform) {
                1, 3 -> if (p < 0.5) 1.0 else -1.0
                2 -> 2.0 * p - 1.0
                4 -> if (p < 0.5) 4.0 * p - 1.0 else 3.0 - 4.0 * p
                else -> Osc.sin01(phase).toDouble()
            }
            out[i] = (a * v).toFloat()
            phase += min(f, rate / 2) / rate
        }
        return out
    }

    /** DTMF frequencies (row, column) of a key; null for pauses. */
    fun dtmf(c: Char): Pair<Double, Double>? {
        val rows = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
        val cols = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)
        val keys = arrayOf("123A", "456B", "789C", "*0#D")
        if (c in 'a'..'z') {
            // Lower-case letters map like a phone keypad (DtmfBase)
            val pad = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")
            for ((digit, letters) in pad) if (c in letters) return dtmf(digit)
            return null
        }
        for (r in 0..3) {
            val col = keys[r].indexOf(c)
            if (col >= 0) return rows[r] to cols[col]
        }
        return null
    }

    const val TWO_PI = 2 * PI
}
