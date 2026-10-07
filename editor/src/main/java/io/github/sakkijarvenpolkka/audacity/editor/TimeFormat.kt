/*
 * Audacity Android port — time formatting for the toolbars and the timeline ruler.
 *
 * Mirrors the 3.7.9 NumericConverter formats "hh:mm:ss" (`0100 h 060 m 060 s`)
 * and "hh:mm:ss + milliseconds" (`0100 h 060 m 060>01000 s`), rounding to the
 * nearest unit of the last field like ParsedNumericConverterFormatter, and
 * the minutes/seconds tick spacing of the time ruler (lib-screen-geometry
 * TimeFormat / Ruler). Audacity, the Audacity Team; GPL-2.0-or-later.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/** Fields of a formatted time. */
internal class TimeFields(val valid: Boolean, val hours: Long, val minutes: Int, val seconds: Int, val millis: Int)

internal object TimeFormat {

    /** Splits [t] seconds into fields, rounding at milliseconds ([withMillis])
     *  or at whole seconds. Negative / NaN → invalid ("--"). */
    fun fields(t: Double, withMillis: Boolean): TimeFields {
        if (t.isNaN() || t < 0.0 || t.isInfinite()) return TimeFields(false, 0, 0, 0, 0)
        val total = if (withMillis) floor(t * 1000.0 + 0.5).toLong() else floor(t + 0.5).toLong() * 1000L
        val ms = (total % 1000L).toInt()
        val secsTotal = total / 1000L
        val s = (secsTotal % 60L).toInt()
        val m = ((secsTotal / 60L) % 60L).toInt()
        val h = secsTotal / 3600L
        return TimeFields(true, h, m, s, ms)
    }

    private fun two(v: Long): String = if (v < 10) "0$v" else v.toString()
    private fun two(v: Int): String = two(v.toLong())
    private fun three(v: Int): String = when {
        v < 10 -> "00$v"
        v < 100 -> "0$v"
        else -> v.toString()
    }

    /** "hh:mm:ss" format: `00 h 01 m 23 s` (Time toolbar). */
    fun hhmmss(t: Double): String {
        val f = fields(t, withMillis = false)
        if (!f.valid) return "-- h -- m -- s"
        return "${two(f.hours)} h ${two(f.minutes)} m ${two(f.seconds)} s"
    }

    /** "hh:mm:ss + milliseconds" format: `00 h 00 m 01.250 s` (Selection toolbar). */
    fun hhmmssMillis(t: Double): String {
        val f = fields(t, withMillis = true)
        if (!f.valid) return "-- h -- m --.--- s"
        return "${two(f.hours)} h ${two(f.minutes)} m ${two(f.seconds)}.${three(f.millis)} s"
    }

    /** Compact editable form `hh:mm:ss.mmm`. */
    fun editable(t: Double): String {
        val f = fields(t, withMillis = true)
        if (!f.valid) return "00:00:00.000"
        return "${two(f.hours)}:${two(f.minutes)}:${two(f.seconds)}.${three(f.millis)}"
    }

    /**
     * Parses a user-entered time: `hh:mm:ss.mmm`, `mm:ss(.mmm)`, `ss(.mmm)`,
     * or the display form `00 h 01 m 02.500 s`. Returns null when invalid.
     */
    fun parse(text: String): Double? {
        var s = text.trim().lowercase()
        if (s.isEmpty()) return null
        // Display form: replace unit letters by separators.
        if (s.contains('h') || s.contains('m') || s.endsWith("s")) {
            s = s.replace("h", ":").replace("m", ":").replace("s", "").replace(" ", "")
        }
        s = s.replace(',', '.')
        val parts = s.split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty()) return null
            val v = p.toDoubleOrNull() ?: return null
            if (v < 0.0 || v.isNaN() || v.isInfinite()) return null
            val isLast = i == parts.size - 1
            if (!isLast && v != floor(v)) return null
            total = total * 60.0 + v
        }
        return total
    }

    /** Label of a ruler tick at [t] given the major tick spacing [step]. */
    fun rulerLabel(t: Double, step: Double): String {
        val neg = t < 0
        val a = abs(t)
        val decimals = when {
            step >= 1.0 -> 0
            step >= 0.1 -> 1
            step >= 0.01 -> 2
            step >= 0.001 -> 3
            step >= 0.0001 -> 4
            else -> 5
        }
        val scale = 10.0.pow(decimals)
        val totalUnits = (a * scale).roundToLong()
        val unitsPerSecond = scale.toLong()
        val wholeSeconds = totalUnits / unitsPerSecond
        val frac = totalUnits % unitsPerSecond
        val h = wholeSeconds / 3600
        val m = (wholeSeconds / 60) % 60
        val sec = wholeSeconds % 60
        val sb = StringBuilder()
        if (neg) sb.append('-')
        when {
            h > 0 -> sb.append(h).append(':').append(two(m)).append(':').append(two(sec))
            m > 0 || step >= 60.0 -> sb.append(m).append(':').append(two(sec))
            else -> sb.append(sec)
        }
        if (decimals > 0) {
            sb.append('.')
            val f = frac.toString()
            repeat(decimals - f.length) { sb.append('0') }
            sb.append(f)
        }
        return sb.toString()
    }
}

/**
 * Tick spacing of the timeline ruler: picks the smallest "nice" major step
 * whose labels are at least [minLabelSpacingDp] apart, with matching minor
 * subdivisions (seconds / minutes / hours like Audacity's time ruler).
 */
internal object RulerTicks {
    /** (major, minor) pairs in seconds, ascending. */
    private val STEPS = doubleArrayOf(
        0.0001, 0.00002, 0.0002, 0.00005, 0.0005, 0.0001,
        0.001, 0.0002, 0.002, 0.0005, 0.005, 0.001,
        0.01, 0.002, 0.02, 0.005, 0.05, 0.01,
        0.1, 0.02, 0.2, 0.05, 0.5, 0.1,
        1.0, 0.2, 2.0, 0.5, 5.0, 1.0,
        10.0, 2.0, 15.0, 5.0, 30.0, 5.0, 60.0, 15.0, 120.0, 30.0, 300.0, 60.0,
        600.0, 120.0, 900.0, 300.0, 1800.0, 300.0, 3600.0, 900.0, 7200.0, 1800.0,
        18000.0, 3600.0, 36000.0, 7200.0, 86400.0, 21600.0, 432000.0, 86400.0,
    )

    /** Returns the (major, minor) step in seconds for [pps] dp per second. */
    fun steps(pps: Double, minLabelSpacingDp: Double): Pair<Double, Double> {
        var i = 0
        while (i < STEPS.size) {
            if (STEPS[i] * pps >= minLabelSpacingDp) return STEPS[i] to STEPS[i + 1]
            i += 2
        }
        return STEPS[STEPS.size - 2] to STEPS[STEPS.size - 1]
    }

    /** Major step only (allocation-free). */
    fun majorStep(pps: Double, minLabelSpacingDp: Double): Double {
        var i = 0
        while (i < STEPS.size) {
            if (STEPS[i] * pps >= minLabelSpacingDp) return STEPS[i]
            i += 2
        }
        return STEPS[STEPS.size - 2]
    }

    /** Minor step for [major] (allocation-free). */
    fun minorStep(major: Double): Double {
        var i = 0
        while (i < STEPS.size) {
            if (STEPS[i] == major) return STEPS[i + 1]
            i += 2
        }
        return major / 5.0
    }
}
