package io.github.sakkijarvenpolkka.trio.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating panel and bubble in windows of every size: split screen, landscape, the keyboard, rotation. */
class PanelGeometryTest {
    // 260dp × 300dp at 3x.
    private val minW = 780f
    private val minH = 900f

    private fun assertInside(b: PanelBox, maxW: Float, maxH: Float) {
        assertTrue("$b fits $maxW", b.w <= maxW + 0.01f)
        assertTrue("$b fits $maxH", b.h <= maxH + 0.01f)
        assertTrue("$b top on screen", b.y >= 0f && b.y + b.h <= maxH + 0.01f)
        assertTrue("$b header reachable", b.x + b.w * 0.6f >= -0.01f && b.x + b.w * 0.4f <= maxW + 0.01f)
    }

    /** Smaller than the panel's minimum size (a 1/3 split screen, landscape with the keyboard up): no empty range. */
    @Test
    fun windowsSmallerThanTheMinimumSizeNeverThrow() {
        val wanted = listOf(PanelBox(30f, 600f, 1000f, 1200f), PanelBox(-900f, 5000f, 10f, 10f), PanelBox(0f, 0f, 0f, 0f))
        for (maxW in listOf(0f, 100f, 700f, 1080f, 2400f)) for (maxH in listOf(0f, 50f, 600f, 899f, 2000f)) for (b in wanted) {
            assertInside(b.fit(maxW, maxH, minW, minH), maxW, maxH)
            assertInside(b.moved(50f, -2000f, maxW, maxH, minW, minH), maxW, maxH)
            assertInside(b.moved(-5000f, 5000f, maxW, maxH, minW, minH), maxW, maxH)
            assertInside(b.resized(-5000f, 5000f, maxW, maxH, minW, minH), maxW, maxH)
            assertInside(b.resized(5000f, -5000f, maxW, maxH, minW, minH), maxW, maxH)
        }
    }

    @Test
    fun afterRotatingThePanelIsOnScreenAndRotatingBackRestoresIt() {
        val portrait = PanelBox(32f, 680f, 1015f, 1240f)
        val landscape = portrait.fit(2200f, 980f, minW, minH)
        assertInside(landscape, 2200f, 980f)
        assertEquals(980f, landscape.h)
        assertEquals(portrait, portrait.fit(1080f, 2000f, minW, minH))
    }

    @Test
    fun resizingKeepsTheTopAndTheMinimumSize() {
        val b = PanelBox(30f, 600f, 1000f, 1000f)
        val taller = b.resized(0f, 300f, 1080f, 2000f, minW, minH)
        assertEquals(600f, taller.y)
        assertEquals(1300f, taller.h)
        val tiny = b.resized(-900f, -900f, 1080f, 2000f, minW, minH)
        assertEquals(minW, tiny.w)
        assertEquals(minH, tiny.h)
        // Not past the bottom: the bottom edge (and the grip) stays on screen.
        assertEquals(1400f, b.resized(0f, 3000f, 1080f, 2000f, minW, minH).h)
    }

    /** Opened while the keyboard is up, a panel the user never sized is full size once the keyboard goes down. */
    @Test
    fun anUnchosenPanelFollowsTheSpace() {
        val withKeyboard = panelFor(null, null, null, null, 1080f, 900f).fit(1080f, 900f, minW, minH)
        val after = panelFor(null, null, null, null, 1080f, 2000f).fit(1080f, 2000f, minW, minH)
        assertEquals(900f, withKeyboard.h)
        assertEquals(1240f, after.h)
        // Moved while the keyboard was up: the place sticks, the size still follows the space.
        val moved = withKeyboard.moved(0f, -100f, 1080f, 900f, minW, minH)
        assertEquals(1240f, panelFor(moved.x, moved.y, null, null, 1080f, 2000f).fit(1080f, 2000f, minW, minH).h)
    }

    @Test
    fun theBubbleStaysOnScreen() {
        assertEquals(0f to 0f, fitBubble(500f, 900f, -10f, -10f))
        assertEquals(100f to 200f, fitBubble(500f, 900f, 100f, 200f))
        assertEquals(300f to 900f, fitBubble(300f, 900f, 1000f, 2000f))
    }
}
