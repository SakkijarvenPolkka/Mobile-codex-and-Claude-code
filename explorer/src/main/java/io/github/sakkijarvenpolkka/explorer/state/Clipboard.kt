/*
 * Files — the app's own clipboard for cut, copy and paste.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path

/** Whether pasting copies the items or moves them. */
enum class ClipMode { COPY, CUT }

/** Items cut or copied, as canonical [paths] taken when they were picked up. */
data class Clipboard(val paths: List<Path>, val mode: ClipMode)

/** Holds the clipboard. It is never an empty [Clipboard]: that is stored as null. */
class ClipboardHolder {
    private val current = MutableStateFlow<Clipboard?>(null)

    /** The clipboard, or null when nothing is cut or copied. */
    val state: StateFlow<Clipboard?> = current.asStateFlow()

    /** Replaces the clipboard; null or an empty clipboard clears it. */
    fun set(c: Clipboard?) {
        current.value = c?.takeIf { it.paths.isNotEmpty() }
    }

    /**
     * After a paste: a cut drops the items in [moved], the ones actually moved, and keeps the
     * rest for another try; the clipboard clears once all are gone. A copy is kept as it is.
     */
    fun afterPaste(moved: Set<Path>) {
        current.update { clip ->
            if (clip == null || clip.mode != ClipMode.CUT) return@update clip
            val left = clip.paths.filterNot { it in moved }
            if (left.isEmpty()) null else clip.copy(paths = left)
        }
    }
}
