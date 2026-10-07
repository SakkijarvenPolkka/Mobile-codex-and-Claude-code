/*
 * Audacity Android port — time and number formatting for dialogs.
 *
 * Formats follow the 3.7.9 NumericConverter formats "hh:mm:ss" and
 * "hh:mm:ss + milliseconds" (lib-numeric-formats), shown in the compact
 * editable form `hh:mm:ss.mmm`.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.util

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

object TimeCodec {

    /** `hh:mm:ss.mmm` (milliseconds rounded); negative / NaN → `--:--:--.---`. */
    fun format(seconds: Double, withMillis: Boolean = true): String {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0.0) return if (withMillis) "--:--:--.---" else "--:--:--"
        val totalMs = if (withMillis) (seconds * 1000.0).roundToLong() else floor(seconds + 0.5).toLong() * 1000L
        val ms = totalMs % 1000L
        val s = (totalMs / 1000L) % 60L
        val m = (totalMs / 60_000L) % 60L
        val h = totalMs / 3_600_000L
        return if (withMillis) String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms)
        else String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    }

    /**
     * Parses `[[hh:]mm:]ss[.fff]`, plain seconds (`90.5`) or the Audacity
     * display form `00 h 01 m 30.500 s`. Returns null when invalid or < 0.
     */
    fun parse(text: String): Double? {
        var t = text.trim()
        if (t.isEmpty()) return null
        if (t.contains('h') || t.contains('m') || t.contains('s')) {
            t = t.replace(Regex("\\s*h\\s*"), ":").replace(Regex("\\s*m\\s*"), ":").replace(Regex("\\s*s\\s*$"), "")
                .replace(" ", "")
        }
        t = t.replace(',', '.')
        val parts = t.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() }) return null
        var total = 0.0
        for ((i, p) in parts.withIndex()) {
            val last = i == parts.lastIndex
            val v = if (last) p.toDoubleOrNull() else p.toLongOrNull()?.toDouble()
            if (v == null || v < 0.0 || v.isNaN() || v.isInfinite()) return null
            if (!last && i > 0 && v >= 60.0) return null
            if (last && parts.size > 1 && v >= 60.0) return null
            total = total * 60.0 + v
        }
        return total
    }

    /** "1.5 MB" style size. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }

    /** Number for display with up to [decimals] decimals, C locale, trailing zeros trimmed. */
    fun number(v: Double, decimals: Int = 3): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
        val s = String.format(Locale.ROOT, "%.${decimals}f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }
}
