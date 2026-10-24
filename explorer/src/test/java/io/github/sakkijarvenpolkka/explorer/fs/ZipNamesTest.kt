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
    fun namesTheAppRefusesAnywhereAreUnsafe() {
        // DEL, a C1 control, a line separator and a bidirectional override, as NameValidator refuses them.
        for (raw in listOf("a\u007F", "a\u0085b", "a\u2028b", "invoice\u202Etxt.exe")) {
            assertNull(raw, ZipNames.sanitize(raw))
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
