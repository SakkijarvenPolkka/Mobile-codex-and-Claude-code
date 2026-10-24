// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.Normalizer

class ZipNamesTest {

    @Test
    fun plainNameStaysAsItIs() {
        assertEquals("a/b.txt", ZipNames.sanitize("a/b.txt"))
        assertEquals("a/", ZipNames.sanitize("a/"))
        assertEquals("한글.txt", ZipNames.sanitize("한글.txt"))
    }

    @Test
    fun backslashIsASeparator() {
        assertEquals("a/b.txt", ZipNames.sanitize("a\\b.txt"))
    }

    @Test
    fun namesThatLeaveTheFolderOrHoldControlsAreUnsafe() {
        val unsafe = listOf(
            "/etc/x", "C:/x", "c:x", "../x", "a/../../x", "a\u0000b", "a\u0007", "\\etc\\x", "..\\x", "a/..",
        )
        for (raw in unsafe) assertNull(raw, ZipNames.sanitize(raw))
    }

    @Test
    fun c0AndC1ControlsAreUnsafe() {
        for (raw in listOf("a\u0001", "a\u001Fb", "a\u0080", "a\u0085b", "d/a\u009F.txt")) {
            assertNull(raw, ZipNames.sanitize(raw))
        }
    }

    @Test
    fun otherCharactersAreKept() {
        // Spec §6.2 refuses NUL and the C0 and C1 controls only: DEL, line separators and bidirectional
        // controls are names a volume stores, shown as the list shows any such name.
        for (raw in listOf("a\u007F", "a\u2028b", "a\u2029b", "invoice\u202Etxt.exe", "a\u200Eb", "d/\u200F.txt")) {
            assertEquals(raw, raw, ZipNames.sanitize(raw))
        }
    }

    @Test
    fun dotAndEmptyPartsAreDropped() {
        assertEquals("a/b/", ZipNames.sanitize("./a//b/"))
        assertEquals("a/b.txt", ZipNames.sanitize("a/./b.txt"))
        // Nothing but the extraction folder itself.
        assertEquals("", ZipNames.sanitize("./"))
    }

    @Test
    fun partLongerThan255BytesIsUnsafe() {
        assertEquals("a".repeat(255), ZipNames.sanitize("a".repeat(255)))
        assertNull(ZipNames.sanitize("a".repeat(256)))
        assertNull(ZipNames.sanitize("d/" + "가".repeat(86)))
        assertEquals("d/" + "가".repeat(85), ZipNames.sanitize("d/" + "가".repeat(85)))
    }

    @Test
    fun namesAreNfc() {
        val nfd = Normalizer.normalize("한글/파일.txt", Normalizer.Form.NFD)

        assertEquals("한글/파일.txt", ZipNames.sanitize(nfd))
    }
}
