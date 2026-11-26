package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * How the address bar's selection follows focus, as in other browsers: gaining focus selects the whole address so
 * that typing (a search, "@claude …") replaces it, except when a command such as "@all " moved focus there, where
 * the cursor stays after the command.
 *
 * A tap gains focus when the finger goes down and moves the cursor to the tapped spot when it lifts; that one move
 * keeps the whole address selected. Later moves (a second tap) place the cursor as usual.
 */
internal class AddressSelection {
    private var keepCursor = false
    private var selectAllOnTap = false

    /** A command is about to move focus to the address bar. */
    fun commandFocus() {
        keepCursor = true
    }

    /** The value to show after focus changed; [gained] is true when the field was not focused before. */
    fun onFocusChanged(isFocused: Boolean, gained: Boolean, value: TextFieldValue): TextFieldValue {
        if (!isFocused) {
            selectAllOnTap = false
            return value
        }
        if (!gained) return value
        if (keepCursor) {
            keepCursor = false
            return value
        }
        selectAllOnTap = true
        return value.copy(selection = TextRange(0, value.text.length))
    }

    /** The value to show for an edit or cursor move from the text field. */
    fun onValueChange(current: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val sameText = new.text == current.text
        if (selectAllOnTap && sameText && new.selection.collapsed) {
            selectAllOnTap = false
            return new.copy(selection = TextRange(0, new.text.length))
        }
        if (!sameText) selectAllOnTap = false
        return new
    }
}
