package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressSelectionTest {
    private val url = "https://example.com/page"
    private val all = TextRange(0, url.length)
    private var now = 1_000L
    private fun rule() = AddressSelection { now }

    @Test
    fun theTapThatFocusesSelectsTheAddressAndALaterTapPlacesTheCursor() {
        val s = rule()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        assertEquals(all, v.selection)
        // The focusing tap moves the cursor when the finger lifts: the address stays selected.
        now += 120
        v = s.onValueChange(v, v.copy(selection = TextRange(8)))
        assertEquals(all, v.selection)
        // A second tap places the cursor.
        now += 400
        v = s.onValueChange(v, v.copy(selection = TextRange(8)))
        assertEquals(TextRange(8), v.selection)
    }

    @Test
    fun typingReplacesTheSelectionAndEndsTheTapRule() {
        val s = rule()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        v = s.onValueChange(v, TextFieldValue("@claude hi", TextRange(10)))
        assertEquals(TextFieldValue("@claude hi", TextRange(10)), v)
        v = s.onValueChange(v, v.copy(selection = TextRange(2)))
        assertEquals(TextRange(2), v.selection)
    }

    @Test
    fun aCommandKeepsTheCursorAfterItself() {
        val s = rule()
        s.commandApplied(willFocus = true)
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue("@all ", TextRange(5)))
        assertEquals(TextRange(5), v.selection)
        v = s.onValueChange(v, TextFieldValue("@all c", TextRange(6)))
        assertEquals("@all c", v.text)
        // The next time the field is focused by hand, it selects again.
        s.onFocusChanged(isFocused = false, gained = false, v)
        assertEquals(TextRange(0, 6), s.onFocusChanged(isFocused = true, gained = true, v).selection)
    }

    /** Menu › Ask all while the field already has focus (the focusing tap's cursor move never reached the rule). */
    @Test
    fun aCommandWhileTheFieldIsFocusedEndsTheTapRule() {
        val s = rule()
        s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        var v = TextFieldValue("@all ", TextRange(5))
        s.commandApplied(willFocus = false)
        v = s.onValueChange(v, v.copy(selection = TextRange(2)))
        assertEquals("moving the caret inside the command does not select it", TextRange(2), v.selection)
        v = s.onValueChange(v, TextFieldValue("@all q", TextRange(6)))
        assertEquals("@all q", v.text)
    }

    /** An empty field has nothing to select, and the tap's cursor move (to 0) never reaches the rule. */
    @Test
    fun anEmptyFieldDoesNotArmTheRule() {
        val s = rule()
        s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(""))
        val v = TextFieldValue("@all ", TextRange(5))
        assertEquals(TextRange(1), s.onValueChange(v, v.copy(selection = TextRange(1))).selection)
    }

    /** Focus from a keyboard or TalkBack has no tap: the user's first cursor move is not taken over later on. */
    @Test
    fun theRuleEndsShortlyAfterFocus() {
        val s = rule()
        val v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        now += AddressSelection.TAP_MS + 1
        assertEquals(TextRange(8), s.onValueChange(v, v.copy(selection = TextRange(8))).selection)
    }

    @Test
    fun losingFocusEndsTheTapRule() {
        val s = rule()
        var v = s.onFocusChanged(isFocused = true, gained = true, TextFieldValue(url))
        v = s.onFocusChanged(isFocused = false, gained = false, v)
        assertEquals(TextRange(3), s.onValueChange(v, v.copy(selection = TextRange(3))).selection)
    }
}
