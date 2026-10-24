/*
 * Files — file sizes and modification dates as the app shows them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Sizes use 1024-based units with the Windows spellings (`bytes`/`KB`/`MB`/`GB`/`TB`), not
 * `Formatter.formatFileSize`, which counts in thousands. Dates are Korean `yyyy-MM-dd a h:mm`
 * or the system's short format.
 */
object Formats {

    private val UNITS = arrayOf("KB", "MB", "GB", "TB")
    private val KIB = BigDecimal(1024)
    private val TEN = BigDecimal.TEN
    private val HUNDRED = BigDecimal(100)

    private val KOREAN_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd a h:mm", Locale.KOREAN)

    /**
     * Below 1 KB the exact count with a thousands separator (`1,023 bytes`, Korean
     * `1,023바이트`); from there a number of three significant digits and the unit (`1.50 KB`,
     * `10.0 KB`, `118 MB`). The Korean bytes label follows the number without a space. A negative
     * size (a difference that came out below zero) is shown as 0.
     */
    fun size(bytes: Long, korean: Boolean): String {
        val amount = bytes.coerceAtLeast(0)
        if (amount < 1024) {
            val count = String.format(Locale.US, "%,d", amount)
            return if (korean) "${count}바이트" else "$count bytes"
        }
        var unit = 0
        var value = BigDecimal(amount).divide(KIB)
        while (unit < UNITS.lastIndex && value >= KIB) {
            value = value.divide(KIB)
            unit++
        }
        var shown = threeDigits(value)
        if (shown >= KIB && unit < UNITS.lastIndex) {
            // 1023.9 KB rounds to 1024 KB, which is 1.00 MB
            unit++
            shown = threeDigits(value.divide(KIB))
        }
        return "${shown.toPlainString()} ${UNITS[unit]}"
    }

    private fun threeDigits(value: BigDecimal): BigDecimal {
        val scale = when {
            value >= HUNDRED -> 0
            value >= TEN -> 1
            else -> 2
        }
        val rounded = value.setScale(scale, RoundingMode.HALF_UP)
        // Rounding up to the next power of ten (9.999 -> 10.00, 99.95 -> 100.0) drops a digit.
        return when {
            scale == 2 && rounded >= TEN -> rounded.setScale(1)
            scale == 1 && rounded >= HUNDRED -> rounded.setScale(0)
            else -> rounded
        }
    }

    /**
     * [millis] (epoch milliseconds) in [zone]: `yyyy-MM-dd a h:mm` for Korean (`2026-10-21
     * 오후 3:04`), the system's short date and time format for any other [locale].
     */
    fun date(millis: Long, locale: Locale, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone)
        val formatter = if (locale.language == "ko") KOREAN_DATE
        else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
        return formatter.format(time)
    }
}
