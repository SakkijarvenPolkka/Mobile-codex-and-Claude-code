package io.github.sakkijarvenpolkka.trio.ui

import android.os.SystemClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The address bar's selection rule, as in desktop browsers: the tap that focuses the field selects the
 * whole address, so typing (a search, "@claude …") replaces it instead of landing inside it. A later
 * tap places the cursor as usual.
 *
 * The text field gains focus when the finger goes down and moves the cursor to the tap position when it
 * lifts, which would undo a selection made on focus alone. The rule therefore stays armed after focusing
 * and turns the first cursor move of that tap back into select-all. It disarms when the text changes,
 * when code sets the text (a command such as "@all "), when focus is lost, and shortly after the tap
 * (a field focused without a tap, by keyboard or TalkBack, would otherwise swallow the user's first
 * cursor move).
 */
internal class AddressSelection(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private var keepCursor = false
    private var armedAt = NOT_ARMED

    /**
     * Code replaced the text. [willFocus]: the field is not focused yet and code is focusing it, so the
     * focus that follows keeps the cursor where the code put it.
     */
    fun commandApplied(willFocus: Boolean) {
        armedAt = NOT_ARMED
        keepCursor = willFocus
    }

    fun onFocusChanged(isFocused: Boolean, gained: Boolean, value: TextFieldValue): TextFieldValue {
        if (!isFocused) {
            armedAt = NOT_ARMED
            keepCursor = false
            return value
        }
        if (!gained) return value
        if (keepCursor) {
            keepCursor = false
            return value
        }
        // Nothing to select; the tap's cursor move would not even reach onValueChange (it changes nothing).
        if (value.text.isEmpty()) return value
        armedAt = clock()
        return value.copy(selection = TextRange(0, value.text.length))
    }

    fun onValueChange(current: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val sameText = new.text == current.text
        val armed = armedAt != NOT_ARMED && clock() - armedAt <= TAP_MS
        if (armed && sameText && new.selection.collapsed) {
            armedAt = NOT_ARMED
            return new.copy(selection = TextRange(0, new.text.length))
        }
        if (!sameText || !armed) armedAt = NOT_ARMED
        return new
    }

    companion object {
        /** Longer than a tap; a longer press turns into a long-press (word selection) instead. */
        const val TAP_MS = 700L
        private const val NOT_ARMED = Long.MIN_VALUE
    }
}
