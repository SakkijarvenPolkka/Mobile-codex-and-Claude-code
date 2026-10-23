// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackTargetTest {

    private fun target(
        overlay: Boolean = false,
        selection: Boolean = false,
        drawer: Boolean = false,
        history: Boolean = false,
    ): BackTarget? = backTarget(
        overlayOpen = overlay,
        selectionActive = selection,
        drawerOpen = drawer,
        canGoBack = history,
    )

    @Test
    fun overlayComesFirst() {
        assertEquals(BackTarget.OVERLAY, target(overlay = true, selection = true, drawer = true, history = true))
        assertEquals(BackTarget.OVERLAY, target(overlay = true))
    }

    @Test
    fun selectionComesBeforeTheDrawerAndHistory() {
        assertEquals(BackTarget.SELECTION, target(selection = true, drawer = true, history = true))
        assertEquals(BackTarget.SELECTION, target(selection = true))
    }

    @Test
    fun drawerComesBeforeHistory() {
        assertEquals(BackTarget.DRAWER, target(drawer = true, history = true))
        assertEquals(BackTarget.DRAWER, target(drawer = true))
    }

    @Test
    fun historyIsLast() {
        assertEquals(BackTarget.HISTORY, target(history = true))
    }

    @Test
    fun nothingToHandleIsNull() {
        assertNull(target())
    }
}
