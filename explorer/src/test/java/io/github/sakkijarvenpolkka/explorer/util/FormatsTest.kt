// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class FormatsTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun millis(zone: ZoneId, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 10, 21, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // --- size ---

    @Test
    fun zeroBytes() {
        assertEquals("0바이트", Formats.size(0, korean = true))
        assertEquals("0 bytes", Formats.size(0, korean = false))
    }

    @Test
    fun bytesBelowOneKbHaveAThousandsSeparator() {
        assertEquals("1,023바이트", Formats.size(1023, korean = true))
        assertEquals("1,023 bytes", Formats.size(1023, korean = false))
        assertEquals("1바이트", Formats.size(1, korean = true))
        assertEquals("999 bytes", Formats.size(999, korean = false))
    }

    @Test
    fun oneKbAndUp() {
        assertEquals("1.00 KB", Formats.size(1024, korean = true))
        assertEquals("1.00 KB", Formats.size(1024, korean = false))
        assertEquals("1.50 KB", Formats.size(1536, korean = false))
        assertEquals("10.0 KB", Formats.size(10_240, korean = false))
        assertEquals("100 KB", Formats.size(102_400, korean = false))
        assertEquals("1.00 MB", Formats.size(1_048_576, korean = false))
        assertEquals("5.00 GB", Formats.size(5L shl 30, korean = false))
        assertEquals("1.00 TB", Formats.size(1L shl 40, korean = false))
    }

    @Test
    fun threeSignificantDigits() {
        assertEquals("118 MB", Formats.size(123_456_789, korean = false))
        assertEquals("118 MB", Formats.size(123_456_789, korean = true))
        assertEquals("1.13 KB", Formats.size(1152, korean = false)) // 1.125 rounds half up
        assertEquals("12.5 KB", Formats.size(12_800, korean = false))
        assertEquals("1023 KB", Formats.size(1023L * 1024, korean = false))
    }

    @Test
    fun roundingNeverShowsMoreThanThreeDigitsOrBeyondTheUnit() {
        assertEquals("10.0 KB", Formats.size(10_239, korean = false)) // 9.999 KB
        assertEquals("100 KB", Formats.size(102_349, korean = false)) // 99.95 KB
        assertEquals("1.00 MB", Formats.size(1_048_575, korean = false)) // 1023.999 KB
        assertEquals("1.00 GB", Formats.size((1L shl 30) - 1, korean = false))
    }

    @Test
    fun theLargestSizeStillFormats() {
        assertTrue(Formats.size(Long.MAX_VALUE, korean = false).endsWith(" TB"))
    }

    // --- date ---

    @Test
    fun koreanDate() {
        val text = Formats.date(millis(seoul, 15, 4), Locale.KOREAN, seoul)
        assertEquals("2026-10-21 오후 3:04", text)
    }

    @Test
    fun koreanDateWithARegionIsTheSame() {
        val text = Formats.date(millis(seoul, 15, 4), Locale.forLanguageTag("ko-KR"), seoul)
        assertEquals("2026-10-21 오후 3:04", text)
    }

    @Test
    fun koreanDateUsesAOneBasedTwelveHourClock() {
        assertEquals("2026-10-21 오전 12:05", Formats.date(millis(seoul, 0, 5), Locale.KOREAN, seoul))
        assertEquals("2026-10-21 오후 12:00", Formats.date(millis(seoul, 12, 0), Locale.KOREAN, seoul))
        assertEquals("2026-10-21 오전 9:30", Formats.date(millis(seoul, 9, 30), Locale.KOREAN, seoul))
    }

    @Test
    fun theZoneDecidesTheClock() {
        val utc = ZoneId.of("UTC")
        val at = millis(seoul, 15, 4)
        assertEquals("2026-10-21 오전 6:04", Formats.date(at, Locale.KOREAN, utc))
    }

    @Test
    fun otherLocalesUseTheShortSystemFormat() {
        val at = millis(seoul, 15, 4)
        val expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.US)
            .format(Instant.ofEpochMilli(at).atZone(seoul))
        assertEquals(expected, Formats.date(at, Locale.US, seoul))
    }

    @Test
    fun otherLocalesFollowTheirOwnShortFormat() {
        val at = millis(seoul, 15, 4)
        val expected = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.GERMANY)
            .format(Instant.ofEpochMilli(at).atZone(seoul))
        assertEquals(expected, Formats.date(at, Locale.GERMANY, seoul))
    }
}
