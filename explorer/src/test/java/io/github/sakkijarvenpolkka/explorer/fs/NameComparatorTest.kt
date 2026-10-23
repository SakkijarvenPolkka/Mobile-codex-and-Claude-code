// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sign

class NameComparatorTest {

    @Test
    fun numbersCompareByValue() {
        assertEquals(
            listOf("1.txt", "2.txt", "10.txt"),
            listOf("10.txt", "2.txt", "1.txt").sortedWith(NameComparator),
        )
    }

    @Test
    fun ignoresCase() {
        assertTrue(NameComparator.compare("a", "B") < 0)
        assertTrue(NameComparator.compare("B", "a") > 0)
    }

    @Test
    fun nfdAndNfcAreOrderedByTheOriginalText() {
        val nfd = "가"
        val nfc = "가"
        assertOppositeNonZero(nfd, nfc)
        // Normalized before comparing: the NFD syllable sorts with its NFC twin, not by its jamo.
        assertTrue(NameComparator.compare("${nfd}b", "${nfc}a") > 0)
    }

    @Test
    fun leadingZerosAreOrderedByTheOriginalText() {
        assertOppositeNonZero("file1", "file01")
    }

    @Test
    fun comparesEveryNumberRun() {
        assertTrue(NameComparator.compare("x2y9", "x2y10") < 0)
    }

    @Test
    fun punctuationSortsBeforeDigits() {
        assertTrue(NameComparator.compare("a.txt", "a1.txt") < 0)
    }

    private fun assertOppositeNonZero(a: String, b: String) {
        val ab = NameComparator.compare(a, b)
        val ba = NameComparator.compare(b, a)
        assertNotEquals(0, ab)
        assertNotEquals(0, ba)
        assertEquals(-ab.sign, ba.sign)
    }
}
