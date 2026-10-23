/*
 * Files — the hardware keyboard shortcuts: which key does what, in one table.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** A key pressed with exactly these modifiers (Ctrl+Shift+C is not Ctrl+C). */
data class Shortcut(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

/** What a shortcut does; [BACK] is the system Back (it closes what is open first). */
enum class ShortcutAction {
    COPY, CUT, PASTE, SELECT_ALL, DELETE, DELETE_PERMANENT, RENAME, REFRESH, FOCUS_ADDRESS,
    NEW_TAB, CLOSE_TAB, NEXT_TAB, BACK, FORWARD, UP, OPEN, PREVIEW_PANE,
}

private val SHORTCUTS: Map<Shortcut, ShortcutAction> = mapOf(
    Shortcut(Key.C, ctrl = true) to ShortcutAction.COPY,
    Shortcut(Key.X, ctrl = true) to ShortcutAction.CUT,
    Shortcut(Key.V, ctrl = true) to ShortcutAction.PASTE,
    Shortcut(Key.A, ctrl = true) to ShortcutAction.SELECT_ALL,
    Shortcut(Key.Delete) to ShortcutAction.DELETE,
    Shortcut(Key.Delete, shift = true) to ShortcutAction.DELETE_PERMANENT,
    Shortcut(Key.F2) to ShortcutAction.RENAME,
    Shortcut(Key.F5) to ShortcutAction.REFRESH,
    Shortcut(Key.R, ctrl = true) to ShortcutAction.REFRESH,
    Shortcut(Key.L, ctrl = true) to ShortcutAction.FOCUS_ADDRESS,
    Shortcut(Key.T, ctrl = true) to ShortcutAction.NEW_TAB,
    Shortcut(Key.W, ctrl = true) to ShortcutAction.CLOSE_TAB,
    Shortcut(Key.Tab, ctrl = true) to ShortcutAction.NEXT_TAB,
    Shortcut(Key.DirectionLeft, alt = true) to ShortcutAction.BACK,
    Shortcut(Key.Backspace) to ShortcutAction.BACK,
    Shortcut(Key.DirectionRight, alt = true) to ShortcutAction.FORWARD,
    Shortcut(Key.DirectionUp, alt = true) to ShortcutAction.UP,
    Shortcut(Key.Enter) to ShortcutAction.OPEN,
    Shortcut(Key.NumPadEnter) to ShortcutAction.OPEN,
    Shortcut(Key.P, alt = true) to ShortcutAction.PREVIEW_PANE,
)

/** The keys a focused text field keeps for editing (and Enter for submitting it). */
private val TEXT_EDITING: Set<Shortcut> = setOf(
    Shortcut(Key.Backspace),
    Shortcut(Key.Delete),
    Shortcut(Key.Delete, shift = true),
    Shortcut(Key.A, ctrl = true),
    Shortcut(Key.C, ctrl = true),
    Shortcut(Key.V, ctrl = true),
    Shortcut(Key.X, ctrl = true),
    Shortcut(Key.Enter),
    Shortcut(Key.NumPadEnter),
)

/**
 * The action of [event], or null when it has none: only the first key-down counts (a held key
 * does not repeat a delete), and nothing with the Meta key, which the system keeps. While
 * [textFieldFocused], the editing keys go to the text field.
 */
fun shortcutFor(event: KeyEvent, textFieldFocused: Boolean): ShortcutAction? {
    if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0 || event.isMetaPressed) return null
    val shortcut = Shortcut(event.key, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)
    if (textFieldFocused && shortcut in TEXT_EDITING) return null
    return SHORTCUTS[shortcut]
}
