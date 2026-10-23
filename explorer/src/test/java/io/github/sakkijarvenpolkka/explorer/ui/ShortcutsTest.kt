// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.KeyEvent as AndroidKeyEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutsTest {

    private val ctrl = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON
    private val shift = AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON
    private val alt = AndroidKeyEvent.META_ALT_ON or AndroidKeyEvent.META_ALT_LEFT_ON

    private fun event(code: Int, meta: Int, action: Int = AndroidKeyEvent.ACTION_DOWN, repeat: Int = 0) =
        KeyEvent(AndroidKeyEvent(0L, 0L, action, code, repeat, meta))

    private fun press(code: Int, meta: Int = 0, textField: Boolean = false): ShortcutAction? =
        shortcutFor(event(code, meta), textFieldFocused = textField)

    @Test
    fun keysMapToActions() {
        assertEquals(ShortcutAction.COPY, press(AndroidKeyEvent.KEYCODE_C, ctrl))
        assertEquals(ShortcutAction.CUT, press(AndroidKeyEvent.KEYCODE_X, ctrl))
        assertEquals(ShortcutAction.PASTE, press(AndroidKeyEvent.KEYCODE_V, ctrl))
        assertEquals(ShortcutAction.SELECT_ALL, press(AndroidKeyEvent.KEYCODE_A, ctrl))
        assertEquals(ShortcutAction.DELETE, press(AndroidKeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(ShortcutAction.DELETE_PERMANENT, press(AndroidKeyEvent.KEYCODE_FORWARD_DEL, shift))
        assertEquals(ShortcutAction.RENAME, press(AndroidKeyEvent.KEYCODE_F2))
        assertEquals(ShortcutAction.REFRESH, press(AndroidKeyEvent.KEYCODE_F5))
        assertEquals(ShortcutAction.REFRESH, press(AndroidKeyEvent.KEYCODE_R, ctrl))
        assertEquals(ShortcutAction.FOCUS_ADDRESS, press(AndroidKeyEvent.KEYCODE_L, ctrl))
        assertEquals(ShortcutAction.NEW_TAB, press(AndroidKeyEvent.KEYCODE_T, ctrl))
        assertEquals(ShortcutAction.CLOSE_TAB, press(AndroidKeyEvent.KEYCODE_W, ctrl))
        assertEquals(ShortcutAction.NEXT_TAB, press(AndroidKeyEvent.KEYCODE_TAB, ctrl))
        assertEquals(ShortcutAction.BACK, press(AndroidKeyEvent.KEYCODE_DPAD_LEFT, alt))
        assertEquals(ShortcutAction.BACK, press(AndroidKeyEvent.KEYCODE_DEL))
        assertEquals(ShortcutAction.FORWARD, press(AndroidKeyEvent.KEYCODE_DPAD_RIGHT, alt))
        assertEquals(ShortcutAction.UP, press(AndroidKeyEvent.KEYCODE_DPAD_UP, alt))
        assertEquals(ShortcutAction.OPEN, press(AndroidKeyEvent.KEYCODE_ENTER))
        assertEquals(ShortcutAction.PREVIEW_PANE, press(AndroidKeyEvent.KEYCODE_P, alt))
    }

    @Test
    fun textFieldKeepsItsEditingKeys() {
        val editing = listOf(
            AndroidKeyEvent.KEYCODE_DEL to 0,
            AndroidKeyEvent.KEYCODE_A to ctrl,
            AndroidKeyEvent.KEYCODE_C to ctrl,
            AndroidKeyEvent.KEYCODE_V to ctrl,
            AndroidKeyEvent.KEYCODE_X to ctrl,
            AndroidKeyEvent.KEYCODE_FORWARD_DEL to 0,
            AndroidKeyEvent.KEYCODE_FORWARD_DEL to shift,
            AndroidKeyEvent.KEYCODE_ENTER to 0,
        )
        for ((code, meta) in editing) assertNull("key $code", press(code, meta, textField = true))
        assertEquals(ShortcutAction.BACK, press(AndroidKeyEvent.KEYCODE_DPAD_LEFT, alt, textField = true))
        assertEquals(ShortcutAction.REFRESH, press(AndroidKeyEvent.KEYCODE_F5, textField = true))
    }

    @Test
    fun onlyAFirstKeyDownWithExactlyTheModifiersCounts() {
        assertNull(shortcutFor(event(AndroidKeyEvent.KEYCODE_F5, 0, AndroidKeyEvent.ACTION_UP), false))
        assertNull(shortcutFor(event(AndroidKeyEvent.KEYCODE_FORWARD_DEL, 0, repeat = 1), false))
        assertNull(press(AndroidKeyEvent.KEYCODE_C))
        assertNull(press(AndroidKeyEvent.KEYCODE_C, ctrl or shift))
        assertNull(press(AndroidKeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(press(AndroidKeyEvent.KEYCODE_F2, AndroidKeyEvent.META_META_ON))
    }
}
