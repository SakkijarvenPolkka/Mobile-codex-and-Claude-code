/*
 * Files — file names made safe to show.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

/**
 * Every screen shows names through [of]. A name is only for display: operations use the
 * original `Path`, never a path rebuilt from a shown string.
 */
object DisplayName {

    private const val FSI = '\u2068'
    private const val PDI = '\u2069'
    private const val ELLIPSIS = "…"
    private const val CONTROL_PICTURES = 0x2400
    private const val DELETE_PICTURE = '\u2421'
    private const val NEWLINE_PICTURE = '\u2424'
    private const val UNKNOWN_CONTROL = '\u00B7'

    /**
     * [raw] with each C0/C1 control character (line breaks included) replaced by a visible
     * symbol from the Control Pictures block: `\n` becomes `␊`, NEL becomes `␤`, DEL becomes
     * `␡`, and the C1 controls that have no picture become `·`.
     */
    fun of(raw: String): String {
        if (raw.none { it.isISOControl() }) return raw
        val shown = StringBuilder(raw.length)
        for (c in raw) shown.append(visible(c))
        return shown.toString()
    }

    private fun visible(c: Char): Char = when (c.code) {
        in 0x00..0x1F -> (CONTROL_PICTURES + c.code).toChar()
        0x7F -> DELETE_PICTURE
        0x85 -> NEWLINE_PICTURE
        in 0x80..0x9F -> UNKNOWN_CONTROL
        else -> c
    }

    /**
     * [of] wrapped in first-strong isolate marks (U+2068 … U+2069), so a name in the middle
     * of a sentence cannot reorder the text around it.
     */
    fun inSentence(raw: String): String = "$FSI${of(raw)}$PDI"

    /**
     * [name] cut to at most [maxChars] characters with `…` in the middle. The extension (from
     * the last dot, which is not the first character) stays visible; a name whose extension
     * leaves no room for any of its start is cut in the middle as a whole.
     */
    fun middleEllipsis(name: String, maxChars: Int): String {
        if (name.length <= maxChars) return name
        if (maxChars <= 1) return ELLIPSIS.take(maxChars.coerceAtLeast(0))
        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val headLength = maxChars - ELLIPSIS.length - (name.length - dot)
            if (headLength >= 1) return name.safeHead(headLength) + ELLIPSIS + name.substring(dot)
        }
        val keep = maxChars - ELLIPSIS.length
        return name.safeHead((keep + 1) / 2) + ELLIPSIS + name.safeTail(keep / 2)
    }

    /** The first [count] chars, without half of a surrogate pair at the end. */
    private fun String.safeHead(count: Int): String {
        val end = if (count > 0 && this[count - 1].isHighSurrogate()) count - 1 else count
        return substring(0, end)
    }

    /** The last [count] chars, without half of a surrogate pair at the start. */
    private fun String.safeTail(count: Int): String {
        val start = length - count
        return substring(if (count > 0 && this[start].isLowSurrogate()) start + 1 else start)
    }
}
