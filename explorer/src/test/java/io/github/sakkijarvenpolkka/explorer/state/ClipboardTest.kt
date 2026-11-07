// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Paths

class ClipboardTest {

    private val a = Paths.get("/storage/emulated/0/a")
    private val b = Paths.get("/storage/emulated/0/b")
    private val c = Paths.get("/storage/emulated/0/c")

    @Test
    fun cutPasteRemovesOnlyMoved() {
        val holder = ClipboardHolder()
        holder.set(Clipboard(listOf(a, b, c), ClipMode.CUT))

        holder.afterPaste(setOf(a, c))
        assertEquals(Clipboard(listOf(b), ClipMode.CUT), holder.state.value)

        holder.afterPaste(setOf(b))
        assertNull(holder.state.value)

        val copied = Clipboard(listOf(a, b), ClipMode.COPY)
        holder.set(copied)
        holder.afterPaste(setOf(a, b))
        assertEquals(copied, holder.state.value)
    }

    @Test
    fun goneItemsLeaveEitherClipboard() {
        val holder = ClipboardHolder()
        // A copy too: what is no longer there would be pasted never again.
        holder.set(Clipboard(listOf(a, b), ClipMode.COPY))
        holder.dropGone(setOf(a))
        assertEquals(Clipboard(listOf(b), ClipMode.COPY), holder.state.value)
        holder.dropGone(setOf(b))
        assertNull(holder.state.value)

        holder.set(Clipboard(listOf(a, c), ClipMode.CUT))
        holder.dropGone(setOf(c))
        assertEquals(Clipboard(listOf(a), ClipMode.CUT), holder.state.value)
        holder.dropGone(emptySet())
        assertEquals(Clipboard(listOf(a), ClipMode.CUT), holder.state.value)
    }

    @Test
    fun startsEmptyAndClears() {
        val holder = ClipboardHolder()
        assertNull(holder.state.value)

        holder.set(Clipboard(listOf(a), ClipMode.COPY))
        holder.set(null)
        assertNull(holder.state.value)

        holder.set(Clipboard(emptyList(), ClipMode.CUT))
        assertNull(holder.state.value)
        holder.afterPaste(setOf(a))
        assertNull(holder.state.value)
    }
}
