/*
 * Files — file names made safe to show.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

/**
 * Whether [codePoint] reorders the text around it: the direction marks U+200E, U+200F and U+061C, and the
 * embedding, override and isolate controls U+202A–U+202E and U+2066–U+2069.
 */
fun isBidiControl(codePoint: Int): Boolean = when (codePoint) {
    0x200E, 0x200F, 0x061C -> true
    in 0x202A..0x202E, in 0x2066..0x2069 -> true
    else -> false
}

/** Whether [codePoint] ends a line without being a C0/C1 control: U+2028 and U+2029. */
fun isLineSeparator(codePoint: Int): Boolean = codePoint == 0x2028 || codePoint == 0x2029

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
    private const val PLACEHOLDER = '\u00B7'

    /**
     * [raw] with each C0/C1 control character (line breaks included) replaced by a visible
     * symbol from the Control Pictures block: `\n` becomes `␊`, NEL becomes `␤`, DEL becomes
     * `␡`, and the C1 controls that have no picture become `·`. The characters that reorder
     * text or end a line without being C0/C1 controls (the direction marks U+200E, U+200F and
     * U+061C, the embedding, override and isolate controls U+202A–U+202E and U+2066–U+2069,
     * and the line and paragraph separators U+2028 and U+2029) become `·` as well.
     */
    fun of(raw: String): String {
        if (raw.none { visible(it) != it }) return raw
        val shown = StringBuilder(raw.length)
        for (c in raw) shown.append(visible(c))
        return shown.toString()
    }

    private fun visible(c: Char): Char = when {
        c.code <= 0x1F -> (CONTROL_PICTURES + c.code).toChar()
        c.code == 0x7F -> DELETE_PICTURE
        c.code == 0x85 -> NEWLINE_PICTURE
        c.code in 0x80..0x9F -> PLACEHOLDER
        isBidiControl(c.code) || isLineSeparator(c.code) -> PLACEHOLDER
        else -> c
    }

    /**
     * [of] wrapped in first-strong isolate marks (U+2068 … U+2069). The name itself holds no
     * direction control, so it cannot close the isolate early or reorder the text around it.
     */
    fun inSentence(raw: String): String = "$FSI${of(raw)}$PDI"

    /**
     * [name] cut to at most [maxChars] characters with `…` in the middle. The extension with its
     * dot (see [extensionStart]) stays visible; a name without one, or whose extension leaves no
     * room for any of its start, is cut in the middle as a whole.
     */
    fun middleEllipsis(name: String, maxChars: Int): String {
        if (name.length <= maxChars) return name
        if (maxChars <= 1) return ELLIPSIS.take(maxChars.coerceAtLeast(0))
        val dot = extensionStart(name)
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
