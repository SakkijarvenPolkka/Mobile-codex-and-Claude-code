/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.MeterSample
import io.github.sakkijarvenpolkka.audacity.engine.model.TransportSample
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeAndZoomTest {

    @Test
    fun ppsForLevelIsPowerOfTwoOverEight() {
        assertEquals(1.0, Zoom.ppsForLevel(0), 0.0)
        assertEquals(2.0, Zoom.ppsForLevel(8), 0.0)
        assertEquals(0.5, Zoom.ppsForLevel(-8), 0.0)
        assertEquals(Math.pow(2.0, 51 / 8.0), Zoom.ppsForLevel(51), 0.0)
        assertEquals(1e6, Zoom.ppsForLevel(160), 1e5)
    }

    @Test
    fun levelAtOrBelowRoundTripsEveryLevel() {
        for (level in Zoom.MIN_LEVEL..Zoom.MAX_LEVEL) {
            assertEquals(level, Zoom.levelAtOrBelow(Zoom.ppsForLevel(level)))
            assertEquals(level, Zoom.levelAtOrBelow(Zoom.ppsForLevel(level) * 1.04))
        }
        assertEquals(Zoom.MIN_LEVEL, Zoom.levelAtOrBelow(0.0))
        assertEquals(Zoom.MIN_LEVEL, Zoom.levelAtOrBelow(1e-12))
        assertEquals(Zoom.MAX_LEVEL, Zoom.levelAtOrBelow(1e12))
    }

    @Test
    fun defaultZoomDrawsLevel51Scaled() {
        val zoom = 86.1328125 // 44100 / 512, ZoomInfo default
        val level = Zoom.levelAtOrBelow(zoom)
        assertEquals(51, level)
        assertTrue(Zoom.ppsForLevel(level) <= zoom && zoom < Zoom.ppsForLevel(level + 1))
        val scale = Zoom.drawScale(zoom)
        assertTrue(scale >= 1.0 && scale < 1.09)
    }

    @Test
    fun columnHelpers() {
        assertEquals(0L, Zoom.tileStart(255))
        assertEquals(256L, Zoom.tileStart(256))
        assertEquals(-256L, Zoom.tileStart(-1))
        assertEquals(200L, Zoom.columnAt(100.0, 8))
        assertEquals(100.0, Zoom.timeOfColumn(200, 8), 0.0)
        assertEquals(-1L, Zoom.columnAt(-0.1, 0))
        assertFalse(Zoom.needsSampleMode(22050.0, 44100.0))
        assertTrue(Zoom.needsSampleMode(22050.1, 44100.0))
        assertTrue(Zoom.needsSampleMode(12000.0, 44100.0, stretchRatio = 2.0))
    }

    @Test
    fun displayStatusPartialBit() {
        val v = 12345L or DisplayStatus.PARTIAL_BIT
        assertTrue(DisplayStatus.isPartial(v))
        assertEquals(12345L, DisplayStatus.version(v))
        assertFalse(DisplayStatus.isPartial(12345L))
        assertFalse(DisplayStatus.isPartial(DisplayStatus.SAMPLE_MODE))
    }

    private fun transportArray(state: Int, display: Double, sampledAt: Double, looping: Boolean = false) = doubleArrayOf(
        state.toDouble(), display + 0.02, display, sampledAt, 1.0, 3.0, if (looping) 1.0 else 0.0, 48000.0,
        0.02, 0.01, 1.0, 1.0, 0.5, 17.0, 0.0, 0.0,
    )

    @Test
    fun decodesTransportLayout() {
        val s = TransportSample.decode(transportArray(TransportSample.STATE_RECORDING, 2.0, 1e9))
        assertTrue(s.isRecording)
        assertTrue(s.isActive)
        assertEquals(2.02, s.streamTime, 1e-12)
        assertEquals(2.0, s.displayTime, 0.0)
        assertEquals(1_000_000_000L, s.sampledAtNanos)
        assertEquals(1.0, s.loopT0, 0.0)
        assertEquals(3.0, s.loopT1, 0.0)
        assertEquals(48000.0, s.deviceRate, 0.0)
        assertEquals(0.02, s.outputLatency, 0.0)
        assertEquals(0.01, s.inputLatency, 0.0)
        assertTrue(s.capturing)
        assertEquals(0.5, s.recordingStart, 0.0)
        assertEquals(17L, s.generation)
        assertFalse(TransportSample.IDLE.isActive)
    }

    @Test
    fun headTimeExtrapolatesAndClamps() {
        val playing = TransportSample.decode(transportArray(TransportSample.STATE_PLAYING, 2.0, 1e9))
        assertEquals(2.5, playing.headTime(1_500_000_000L), 1e-9)
        assertEquals(2.2, playing.headTime(1_500_000_000L, playEnd = 2.2), 1e-9)
        assertEquals(2.0, playing.headTime(500_000_000L), 1e-9) // clock before sample: no rewind
        val looping = TransportSample.decode(transportArray(TransportSample.STATE_PLAYING, 2.9, 1e9, looping = true))
        assertEquals(3.0, looping.headTime(2_000_000_000L), 1e-9)
        val paused = TransportSample.decode(transportArray(TransportSample.STATE_PAUSED_PLAY, 2.0, 1e9))
        assertEquals(2.0, paused.headTime(9_000_000_000L), 0.0)
        val recording = TransportSample.decode(transportArray(TransportSample.STATE_RECORDING, 2.0, 1e9))
        assertEquals(5.0, recording.headTime(4_000_000_000L, playEnd = 2.2), 1e-9)
        assertTrue(TransportSample.IDLE.headTime(0).isNaN())
    }

    @Test
    fun decodesMeterLayout() {
        val m = MeterSample.decode(floatArrayOf(0.9f, 0.8f, 0.3f, 0.2f, 1f, 0f, 0.5f, 0.4f, 0.1f, 0.05f, 0f, 1f, 2f, 1f))
        assertEquals(0.9f, m.playPeak[0], 0f)
        assertEquals(0.8f, m.playPeak[1], 0f)
        assertEquals(0.2f, m.playRms[1], 0f)
        assertTrue(m.playClip[0])
        assertFalse(m.playClip[1])
        assertEquals(0.4f, m.recPeak[1], 0f)
        assertEquals(0.05f, m.recRms[1], 0f)
        assertTrue(m.recClip[1])
        assertEquals(2, m.playChannels)
        assertEquals(1, m.recChannels)
    }
}
