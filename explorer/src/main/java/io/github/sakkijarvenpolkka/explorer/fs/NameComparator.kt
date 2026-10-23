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
     * Compares two [key]s char by char, except that where both have a digit the whole digit
     * runs are compared by value: leading zeros dropped, then the longer run is larger, then
     * digit by digit. A digit against another char compares as chars, so `a.txt` < `a1.txt`.
     */
    internal fun compareKeys(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val startA = zerosEnd(a, i, endA)
                val startB = zerosEnd(b, j, endB)
                val byLength = (endA - startA).compareTo(endB - startB)
                if (byLength != 0) return byLength
                for (k in 0 until endA - startA) {
                    val byDigit = a[startA + k].compareTo(b[startB + k])
                    if (byDigit != 0) return byDigit
                }
                i = endA
                j = endB
            } else {
                val byChar = a[i].compareTo(b[j])
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun digitsEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    private fun zerosEnd(s: String, from: Int, end: Int): Int {
        var start = from
        while (start < end && s[start] == '0') start++
        return start
    }
}
