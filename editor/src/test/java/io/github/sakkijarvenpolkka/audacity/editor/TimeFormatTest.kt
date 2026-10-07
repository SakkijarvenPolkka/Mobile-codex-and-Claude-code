// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {
    @Test
    fun hhmmssRoundsToNearestSecond() {
        assertEquals("00 h 00 m 00 s", TimeFormat.hhmmss(0.0))
        assertEquals("00 h 01 m 23 s", TimeFormat.hhmmss(83.4))
        assertEquals("00 h 01 m 24 s", TimeFormat.hhmmss(83.5))
        assertEquals("02 h 00 m 00 s", TimeFormat.hhmmss(7199.6))
        assertEquals("-- h -- m -- s", TimeFormat.hhmmss(Double.NaN))
        assertEquals("-- h -- m -- s", TimeFormat.hhmmss(-1.0))
    }

    @Test
    fun hhmmssMillis() {
        assertEquals("00 h 00 m 01.250 s", TimeFormat.hhmmssMillis(1.25))
        assertEquals("00 h 00 m 02.000 s", TimeFormat.hhmmssMillis(1.9996))
        assertEquals("01 h 01 m 01.001 s", TimeFormat.hhmmssMillis(3661.001))
        assertEquals("00:01:23.500", TimeFormat.editable(83.5))
    }

    @Test
    fun parse() {
        assertEquals(83.5, TimeFormat.parse("00:01:23.500")!!, 1e-9)
        assertEquals(83.5, TimeFormat.parse("1:23.5")!!, 1e-9)
        assertEquals(12.25, TimeFormat.parse("12.25")!!, 1e-9)
        assertEquals(12.25, TimeFormat.parse("12,25")!!, 1e-9)
        assertEquals(3723.004, TimeFormat.parse("01 h 02 m 03.004 s")!!, 1e-9)
        assertNull(TimeFormat.parse(""))
        assertNull(TimeFormat.parse("abc"))
        assertNull(TimeFormat.parse("1.5:00"))
        assertNull(TimeFormat.parse("1:2:3:4"))
    }

    @Test
    fun rulerLabels() {
        assertEquals("0", TimeFormat.rulerLabel(0.0, 1.0))
        assertEquals("15", TimeFormat.rulerLabel(15.0, 5.0))
        assertEquals("1:00", TimeFormat.rulerLabel(60.0, 15.0))
        assertEquals("1:30", TimeFormat.rulerLabel(90.0, 30.0))
        assertEquals("0.5", TimeFormat.rulerLabel(0.5, 0.5))
        assertEquals("2.25", TimeFormat.rulerLabel(2.25, 0.05))
        assertEquals("1:00:00", TimeFormat.rulerLabel(3600.0, 600.0))
    }

    @Test
    fun rulerStepsKeepLabelsApart() {
        var pps = 0.01
        while (pps < 1e6) {
            val major = RulerTicks.majorStep(pps, 64.0)
            val minor = RulerTicks.minorStep(major)
            assertTrue(major * pps >= 64.0 || major == 432000.0)
            assertTrue(minor < major)
            pps *= 1.5
        }
    }
}
