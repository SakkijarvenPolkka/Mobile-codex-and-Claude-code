package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressSelectionTest {
    private val url = "https://example.com/page"
    private val all = TextRange(0, url.length)

    @Test
    fun theTapThatFocusesSelectsTheAddressAndALaterTapPlacesTheCursor() {
        val s = AddressSelection()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        assertEquals(all, v.selection)
        // The focusing tap moves the cursor when the finger lifts: the address stays selected.
        v = s.onValueChange(v, v.copy(selection = TextRange(8)))
        assertEquals(all, v.selection)
        // A second tap places the cursor.
        v = s.onValueChange(v, v.copy(selection = TextRange(8)))
        assertEquals(TextRange(8), v.selection)
    }

    @Test
    fun typingReplacesTheSelectionAndEndsTheTapRule() {
        val s = AddressSelection()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        v = s.onValueChange(v, TextFieldValue("@claude hi", TextRange(10)))
        assertEquals(TextFieldValue("@claude hi", TextRange(10)), v)
        v = s.onValueChange(v, v.copy(selection = TextRange(2)))
        assertEquals(TextRange(2), v.selection)
    }

    @Test
    fun aCommandKeepsTheCursorAfterItself() {
        val s = AddressSelection()
        s.commandFocus()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue("@all ", TextRange(5)))
        assertEquals(TextRange(5), v.selection)
        v = s.onValueChange(v, TextFieldValue("@all c", TextRange(6)))
        assertEquals("@all c", v.text)
        // The next time the field is focused by hand, it selects again.
        s.onFocusChanged(isFocused = false, gained = false, v)
        assertEquals(TextRange(0, 6), s.onFocusChanged(isFocused = true, gained = true, v).selection)
    }

    @Test
    fun losingFocusEndsTheTapRule() {
        val s = AddressSelection()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        v = s.onFocusChanged(isFocused = false, gained = false, v)
        assertEquals(TextRange(3), s.onValueChange(v, v.copy(selection = TextRange(3))).selection)
    }
}
