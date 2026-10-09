// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.LabelState
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnappingTest {
    /** 100 dp per second: the 16 dp tolerance is 0.16 s. */
    private val pps = 100.0

    @Test
    fun clampsToTheEndAndSnapsExactlyWithinTheTolerance() {
        val s = Snapper(pps, SnapMode.OFF, DoubleArray(0))
        s.snap(12.5, 12.0, clampToEnd = true).let {
            assertEquals(12.0, it.time, 0.0)
            assertEquals(SnapKind.END, it.kind)
            assertTrue(it.magnet)
        }
        s.snap(11.9, 12.0, clampToEnd = true).let {        // 10 dp before the end
            assertEquals(12.0, it.time, 0.0)
            assertEquals(SnapKind.END, it.kind)
        }
        s.snap(11.8, 12.0, clampToEnd = true).let {        // 20 dp: free
            assertEquals(11.8, it.time, 0.0)
            assertEquals(SnapKind.NONE, it.kind)
        }
        // Without "stop at the end" and without snapping the end is no magnet.
        assertEquals(12.5, s.snap(12.5, 12.0, clampToEnd = false).time, 0.0)
        assertEquals(11.9, s.snap(11.9, 12.0, clampToEnd = false).time, 0.0)
        // No audio (end 0): no upper clamp.
        assertEquals(30.0, s.snap(30.0, 0.0, clampToEnd = true).time, 0.0)
    }

    @Test
    fun zeroIsAlwaysAMagnetAndNegativeTimesClamp() {
        val s = Snapper(pps, SnapMode.OFF, DoubleArray(0))
        assertEquals(SnapKind.ZERO, s.snap(-3.0, 12.0, true).kind)
        assertEquals(0.0, s.snap(-3.0, 12.0, true).time, 0.0)
        assertEquals(0.0, s.snap(0.1, 12.0, true).time, 0.0)
        assertEquals(0.2, s.snap(0.2, 12.0, true).time, 0.0)
        assertEquals(0.0, s.snap(Double.NaN, 12.0, true).time, 0.0)
    }

    @Test
    fun pointsSnapOnlyWhenSnappingIsOnAndTheNearestWins() {
        val points = doubleArrayOf(5.1, 5.0, 9.0)
        val off = Snapper(pps, SnapMode.OFF, points)
        assertEquals(5.07, off.snap(5.07, 12.0, true).time, 0.0)
        val on = Snapper(pps, SnapMode.EDGES, points)
        on.snap(5.07, 12.0, true).let {
            assertEquals(5.1, it.time, 0.0)
            assertEquals(SnapKind.POINT, it.kind)
        }
        assertEquals(5.0, on.snap(5.04, 12.0, true).time, 0.0)
        assertEquals(9.0, on.snap(8.85, 12.0, true).time, 0.0)
        assertEquals(8.8, on.snap(8.8, 12.0, true).time, 0.0)
        // The end is a magnet with snapping on even without "stop at the end".
        assertEquals(12.0, on.snap(12.1, 12.0, clampToEnd = false).time, 0.0)
        // A point past the end is never used while stopping at the end.
        val past = Snapper(pps, SnapMode.EDGES, doubleArrayOf(12.05))
        assertEquals(12.0, past.snap(11.95, 12.0, clampToEnd = true).time, 0.0)
    }

    @Test
    fun gridAppliesWhenNoPointIsNear() {
        val s = Snapper(pps, SnapMode.EDGES_AND_GRID, doubleArrayOf(5.0))
        // 100 dp/s: major ticks every 1 s, minor every 0.2 s.
        assertEquals(0.2, Snapper.gridStep(pps), 0.0)
        s.snap(3.33, 12.0, true).let {
            assertEquals(3.4, it.time, 1e-9)
            assertEquals(SnapKind.GRID, it.kind)
            assertFalse(it.magnet)
        }
        assertEquals(5.0, s.snap(5.12, 12.0, true).time, 0.0)   // the point first
        assertEquals(12.0, s.snap(11.95, 12.0, true).time, 0.0)
    }

    private val snapshot = Snapshot(
        tracks = listOf(
            TrackState(
                1, "wave", "A", start = 0.0, end = 14.0,
                clips = listOf(ClipState(0, start = 0.0, end = 8.0), ClipState(1, start = 10.0, end = 14.0)),
            ),
            TrackState(2, "wave", "B", start = 0.0, end = 12.0, clips = listOf(ClipState(0, start = 0.5, end = 12.0))),
            TrackState(3, "wave", "Empty"),
            TrackState(4, "label", "L", labels = listOf(LabelState(0, 3.0, 3.0), LabelState(1, 15.0, 16.0))),
        ),
    )

    @Test
    fun snapPointsComeFromClipsLabelsTrackEndsAndExtras() {
        val s = Snapper.forSnapshot(snapshot, pps, SnapMode.EDGES, doubleArrayOf(6.5, Double.NaN))
        for (p in doubleArrayOf(8.0, 10.0, 0.5, 12.0, 3.0, 15.0, 16.0, 6.5)) {
            assertEquals("point $p", p, s.snap(p + 0.05, 0.0, clampToEnd = false).time, 0.0)
        }
        // The dragged clip's own edges are left out.
        val excl = Snapper.forSnapshot(snapshot, pps, SnapMode.EDGES, excludeTrackId = 1, excludeClipIndex = 0)
        assertEquals(8.05, excl.snap(8.05, 0.0, clampToEnd = false).time, 0.0)
        assertEquals(10.0, excl.snap(10.05, 0.0, clampToEnd = false).time, 0.0)
    }

    @Test
    fun theLimitIsTheDraggedTracksEndElseTheProjectEnd() {
        assertEquals(16.0, snapshot.projectEnd, 0.0)
        assertEquals(12.0, Snapper.limitFor(snapshot, 2L), 0.0)
        assertEquals(14.0, Snapper.limitFor(snapshot, 1L), 0.0)
        assertEquals(16.0, Snapper.limitFor(snapshot, 4L), 0.0)   // label track: the project end
        assertEquals(16.0, Snapper.limitFor(snapshot, 3L), 0.0)   // no audio: the project end
        assertEquals(16.0, Snapper.limitFor(snapshot, null), 0.0)
        assertEquals(16.0, Snapper.limitFor(snapshot, 99L), 0.0)
        assertEquals(12.0, Snapper.clamp(13.0, 12.0, true), 0.0)
        assertEquals(13.0, Snapper.clamp(13.0, 12.0, false), 0.0)
        assertEquals(0.0, Snapper.clamp(-1.0, 12.0, false), 0.0)
    }

    @Test
    fun feedbackTicksOnceWhenAMagnetEngagesAndShowsTheGuide() {
        val state = EditorState()
        var ticks = 0
        val f = SnapFeedback(state) { ticks++ }
        assertEquals(12.0, f.apply(SnapResult(12.0, SnapKind.END)), 0.0)
        assertEquals(1, ticks)
        assertEquals(12.0, state.snapGuide, 0.0)
        f.apply(SnapResult(12.0, SnapKind.END))                 // still held: no second tick
        assertEquals(1, ticks)
        f.apply(SnapResult(11.0, SnapKind.NONE))
        assertTrue(state.snapGuide.isNaN())
        f.apply(SnapResult(12.0, SnapKind.END))
        f.apply(SnapResult(8.0, SnapKind.POINT))
        assertEquals(3, ticks)
        f.apply(SnapResult(3.4, SnapKind.GRID))                 // the grid neither ticks nor shows a guide
        assertEquals(3, ticks)
        assertTrue(state.snapGuide.isNaN())
        f.apply(SnapResult(8.0, SnapKind.POINT))
        f.end()
        assertTrue(state.snapGuide.isNaN())
        assertEquals(4, ticks)
    }

    @Test
    fun editorStateKeepsTheMobileSettingsAcrossSaveAndRestore() {
        val s = EditorState()
        assertTrue(s.stopAtTrackEnd)
        assertEquals(SnapMode.EDGES, s.snapping)
        assertEquals(EditTool.SELECT, s.tool)
        s.stopAtTrackEnd = false
        s.snapping = SnapMode.EDGES_AND_GRID
        s.tool = EditTool.SPLIT
        val saved = with(EditorState.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(s) }!!
        val r = EditorState.Saver.restore(saved)!!
        assertFalse(r.stopAtTrackEnd)
        assertEquals(SnapMode.EDGES_AND_GRID, r.snapping)
        assertEquals(EditTool.SPLIT, r.tool)
    }
}
