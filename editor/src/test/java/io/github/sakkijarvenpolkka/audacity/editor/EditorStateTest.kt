// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditorStateTest {

    private fun state(width: Float = 400f) = EditorState().apply { viewportWidthDp = width }

    @Test
    fun defaultZoomIsAudacityDefault() {
        val s = EditorState()
        assertEquals(44100.0 / 512.0, s.pps, 0.0)
        assertEquals(0.0, s.hpos, 0.0)
        assertTrue(s.followPlayhead)
    }

    @Test
    fun zoomInDoublesAboutSelection() {
        val s = state()
        s.selectionT0 = 1.0
        s.selectionT1 = 2.0
        val before = s.pps
        s.zoomIn()
        assertEquals(before * 2, s.pps, 1e-9)
        // Selection centre (1.5 s) is centred in the view.
        assertEquals(1.5, s.hpos + s.screenDuration / 2, 1e-9)
    }

    @Test
    fun zoomInWithoutVisibleSelectionKeepsCentre() {
        val s = state()
        s.setView(100.0, 10.0)
        s.selectionT0 = 0.0
        s.selectionT1 = 0.0                     // cursor off screen (left)
        val centre = s.hpos + s.screenDuration / 2
        s.zoomIn()
        assertEquals(200.0, s.pps, 1e-9)
        assertEquals(centre, s.hpos + s.screenDuration / 2, 1e-9)
    }

    @Test
    fun zoomInWhileStreamingScrollsHeadIntoView() {
        val s = state()
        s.setView(100.0, 0.0)
        s.streamingHeadTime = 3.9                // visible now, off screen after ×2 (screen = 2 s)
        s.zoomIn()
        assertEquals(200.0, s.pps, 1e-9)
        assertTrue(3.9 >= s.hpos && 3.9 < s.screenEndTime)
    }

    @Test
    fun zoomOutHalvesAboutCentre() {
        val s = state()
        s.setView(200.0, 5.0)
        val centre = s.hpos + s.screenDuration / 2
        s.zoomOut()
        assertEquals(100.0, s.pps, 1e-9)
        assertEquals(centre, s.hpos + s.screenDuration / 2, 1e-9)
    }

    @Test
    fun zoomOutClampsAtZero() {
        val s = state()
        s.setView(200.0, 0.0)
        s.zoomOut()
        assertEquals(0.0, s.hpos, 0.0)
    }

    @Test
    fun zoomNormalAndLimits() {
        val s = state()
        s.setView(1e9, 0.0)
        assertEquals(EditorState.MAX_ZOOM, s.pps, 0.0)
        s.setView(1e-9, 0.0)
        assertEquals(EditorState.MIN_ZOOM, s.pps, 0.0)
        s.zoomNormal()
        assertEquals(EditorState.DEFAULT_ZOOM, s.pps, 0.0)
    }

    @Test
    fun zoomToFitUsesUsableWidthMinus10() {
        val s = state(410f)
        s.setView(50.0, 3.0)
        s.zoomToFit(20.0)
        assertEquals(400.0 / 20.0, s.pps, 1e-9)
        assertEquals(0.0, s.hpos, 0.0)
        s.zoomToFit(0.0)                         // no-op on an empty project
        assertEquals(20.0, s.pps, 1e-9)
    }

    @Test
    fun zoomToSelection() {
        val s = state(401f)
        s.zoomToSelection(2.0, 6.0)
        assertEquals(400.0 / 4.0, s.pps, 1e-9)
        assertEquals(2.0, s.hpos, 0.0)
        val before = s.pps
        s.zoomToSelection(3.0, 3.0)              // point: unchanged
        assertEquals(before, s.pps, 0.0)
    }

    @Test
    fun zoomByKeepsFocusTimeFixed() {
        val s = state()
        s.setView(100.0, 4.0)
        val tFocus = s.xToTime(120.0)
        s.zoomBy(1.7, 120.0)
        assertEquals(170.0, s.pps, 1e-9)
        assertEquals(tFocus, s.xToTime(120.0), 1e-9)
    }

    @Test
    fun scrollToTimeCentresOnlyWhenOffScreen() {
        val s = state()
        s.setView(100.0, 0.0)                    // 4 s visible
        s.scrollToTime(2.0)
        assertEquals(0.0, s.hpos, 0.0)
        s.scrollToTime(10.0)
        assertEquals(10.0 - 2.0, s.hpos, 1e-9)
    }

    @Test
    fun followHeadPagesLikeAudacity() {
        val s = state()
        s.setView(100.0, 0.0)
        s.followHead(3.0)
        assertEquals(0.0, s.hpos, 0.0)
        s.followHead(4.2)                        // right of the view: page to the head
        assertEquals(4.2, s.hpos, 1e-9)
        s.followHead(1.0)                        // left (loop wrap): head - screen
        assertEquals(0.0, s.hpos, 1e-9)
        s.followPlayhead = false
        s.followHead(50.0)
        assertEquals(0.0, s.hpos, 1e-9)
    }

    @Test
    fun scrollByIsClampedToProjectRange() {
        val s = state()
        s.setView(100.0, 0.0)
        s.projectEnd = 10.0
        s.scrollByDp(-500.0)
        assertEquals(0.0, s.hpos, 0.0)
        s.scrollByDp(1_000_000.0)
        // last + screen/4 - screen = 10 + 1 - 4
        assertEquals(7.0, s.hpos, 1e-9)
    }

    @Test
    fun viewChangesAreCountedButAdoptionIsNot() {
        val s = state()
        val c0 = s.viewChangeCount
        s.zoomIn()
        assertTrue(s.viewChangeCount > c0)
        val c1 = s.viewChangeCount
        s.adoptView(50.0, 2.0)
        assertEquals(c1, s.viewChangeCount)
        assertEquals(50.0, s.pps, 0.0)
        assertEquals(2.0, s.hpos, 0.0)
    }

    @Test
    fun tileLevelMatchesZoomContract() {
        // API.md §7.1: draw the level just below the exact zoom, scaled by zoom/pps(level) ∈ [1, 1.09).
        val densities = floatArrayOf(1f, 1.5f, 2.625f, 3f)
        var pps = 0.001
        while (pps < 1e6) {
            for (d in densities) {
                val px = pps * d
                if (px > Zoom.ppsForLevel(Zoom.MAX_LEVEL) || px < Zoom.ppsForLevel(Zoom.MIN_LEVEL)) continue
                val level = EditorState.tileLevel(pps, d)
                assertEquals(Zoom.levelAtOrBelow(px), level)
                assertTrue(Zoom.ppsForLevel(level) <= px)
                assertTrue(level == Zoom.MAX_LEVEL || Zoom.ppsForLevel(level + 1) > px)
                val scale = EditorState.tileScale(pps, d)
                assertTrue("scale $scale at $pps/$d", scale >= 1.0 && scale < 1.0906)
            }
            pps *= 1.37
        }
    }

    @Test
    fun trackHeightsAndCollapse() {
        val s = EditorState()
        assertEquals(null, s.trackHeightDp(5))
        s.setTrackHeight(5, 10f)
        assertEquals(EditorState.MIN_TRACK_HEIGHT_DP, s.trackHeightDp(5))
        s.setTrackHeight(5, 200f)
        assertEquals(200f, s.trackHeightDp(5))
        assertFalse(s.isCollapsed(5))
        s.toggleCollapsed(5)
        assertTrue(s.isCollapsed(5))
        s.toggleCollapsed(5)
        assertFalse(s.isCollapsed(5))
        s.retainTracks(setOf(6))
        assertEquals(null, s.trackHeightDp(5))
    }

    @Test
    fun saverRoundTrip() {
        val s = state()
        s.setView(123.0, 4.5)
        s.followPlayhead = false
        s.setTrackHeight(7, 222f)
        s.toggleCollapsed(8)
        s.lastSentZoom = 99.0
        s.setSpectrogram(9, true)
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(EditorState.Saver) { scope.save(s) }!!
        val r = EditorState.Saver.restore(saved)!!
        assertEquals(123.0, r.pps, 0.0)
        assertEquals(4.5, r.hpos, 0.0)
        assertFalse(r.followPlayhead)
        assertEquals(222f, r.trackHeightDp(7))
        assertTrue(r.isCollapsed(8))
        assertEquals(99.0, r.lastSentZoom, 0.0)
        assertTrue(r.isSpectrogram(9))
        assertTrue(r.lastSentHpos.isNaN())
        assertTrue(abs(r.pps - s.pps) < 1e-12)
    }

    @Test
    fun spectrogramViewToggle() {
        val s = EditorState()
        assertFalse(s.isSpectrogram(3))
        s.setSpectrogram(3, true)
        assertTrue(s.isSpectrogram(3))
        assertEquals(setOf(3L), s.spectrogramTracks)
        s.retainTracks(setOf(4))
        assertFalse(s.isSpectrogram(3))
        s.setSpectrogram(4, true)
        s.setSpectrogram(4, false)
        assertTrue(s.spectrogramTracks.isEmpty())
    }
}
