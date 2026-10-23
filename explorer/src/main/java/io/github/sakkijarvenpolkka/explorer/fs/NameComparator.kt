/*
 * Files — the order of names in a folder.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.text.Normalizer
import java.util.Locale

/**
 * Orders names like Windows' `StrCmpLogicalW`: NFC-normalized, ignoring case, with runs of
 * digits compared by value (`2.txt` < `10.txt`). Names that are still equal (`file1` and
 * `file01`, NFD and NFC spellings) are ordered by their original text, so the order is total.
 */
object NameComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int = compare(key(a), a, key(b), b)

    /** The folded form of [name] that is compared; precompute it to sort many names. */
    internal fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** [compare] with the [key]s of [a] and [b] already computed. */
    internal fun compare(keyA: String, a: String, keyB: String, b: String): Int {
        val byKey = compareKeys(keyA, keyB)
        return if (byKey != 0) byKey else a.compareTo(b)
    }

    /**
     * Compares two [key]s chunk by chunk, a chunk being a run of digits or a run of other
     * chars. Two digit runs compare by value: leading zeros dropped, then the longer run is
     * larger, then digit by digit. Two other runs compare as strings. A digit run sorts before
     * other text, and a name that runs out of chunks first sorts first, so `a1.txt` < `a.txt`.
     */
    internal fun compareKeys(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val digitsA = a[i].isAsciiDigit()
            val digitsB = b[j].isAsciiDigit()
            if (digitsA != digitsB) return if (digitsA) -1 else 1
            val endA = chunkEnd(a, i, digitsA)
            val endB = chunkEnd(b, j, digitsB)
            val byChunk = if (digitsA) compareNumbers(a, i, endA, b, j, endB) else compareText(a, i, endA, b, j, endB)
            if (byChunk != 0) return byChunk
            i = endA
            j = endB
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun compareNumbers(a: String, fromA: Int, endA: Int, b: String, fromB: Int, endB: Int): Int {
        val startA = zerosEnd(a, fromA, endA)
        val startB = zerosEnd(b, fromB, endB)
        val byLength = (endA - startA).compareTo(endB - startB)
        if (byLength != 0) return byLength
        return compareText(a, startA, endA, b, startB, endB)
    }

    /** `a.substring(fromA, endA).compareTo(b.substring(fromB, endB))` without the copies. */
    private fun compareText(a: String, fromA: Int, endA: Int, b: String, fromB: Int, endB: Int): Int {
        val length = minOf(endA - fromA, endB - fromB)
        for (k in 0 until length) {
            val byChar = a[fromA + k].compareTo(b[fromB + k])
            if (byChar != 0) return byChar
        }
        return (endA - fromA).compareTo(endB - fromB)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    /** The end of the chunk at [from]: a digit run if [digits], else a run of other chars. */
    private fun chunkEnd(s: String, from: Int, digits: Boolean): Int {
        var end = from
        while (end < s.length && s[end].isAsciiDigit() == digits) end++
        return end
    }

    private fun zerosEnd(s: String, from: Int, end: Int): Int {
        var start = from
        while (start < end && s[start] == '0') start++
        return start
    }
}
