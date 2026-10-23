// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameTest {

    /** U+200E, U+200F, U+061C, U+202A–U+202E, U+2066–U+2069 and U+2028/U+2029, ascending. */
    private val directionAndSeparatorCodes: IntArray = intArrayOf(
        0x061C, 0x200E, 0x200F, 0x2028, 0x2029,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2066, 0x2067, 0x2068, 0x2069,
    )

    // --- of ---

    @Test
    fun plainNamesAreUnchanged() {
        assertEquals("report 2026.pdf", DisplayName.of("report 2026.pdf"))
        assertEquals("보고서 (최종).txt", DisplayName.of("보고서 (최종).txt"))
        assertEquals("😀.png", DisplayName.of("😀.png"))
        assertEquals("", DisplayName.of(""))
    }

    @Test
    fun c0ControlsBecomeTheirSymbols() {
        assertEquals("a␊b", DisplayName.of("a\nb"))
        assertEquals("x␇", DisplayName.of("x\u0007"))
        assertEquals("␀", DisplayName.of("\u0000"))
        assertEquals("a␉b", DisplayName.of("a\tb"))
        assertEquals("␟", DisplayName.of("\u001F"))
    }

    @Test
    fun everyC0ControlMapsToItsOwnSymbol() {
        for (code in 0x00..0x1F) {
            assertEquals(
                "U+%04X".format(code),
                (0x2400 + code).toChar().toString(),
                DisplayName.of(code.toChar().toString()),
            )
        }
    }

    @Test
    fun deleteBecomesItsSymbol() {
        assertEquals("a␡", DisplayName.of("a\u007F"))
    }

    @Test
    fun nextLineBecomesTheNewlineSymbol() {
        assertEquals("␤", DisplayName.of("\u0085"))
    }

    @Test
    fun otherC1ControlsBecomeAMiddleDot() {
        for (code in 0x80..0x9F) {
            val expected = if (code == 0x85) "␤" else "·"
            assertEquals("U+%04X".format(code), expected, DisplayName.of(code.toChar().toString()))
        }
    }

    @Test
    fun noControlCharacterSurvives() {
        val raw = (0x00..0x9F).joinToString("") { it.toChar().toString() }
        val shown = DisplayName.of(raw)
        assertEquals(raw.length, shown.length)
        assertFalse(shown.any { it.isISOControl() })
    }

    @Test
    fun charactersAroundTheControlRangesAreKept() {
        assertEquals(" ~", DisplayName.of(" ~"))
        assertEquals("\u00A0\u00A1", DisplayName.of("\u00A0\u00A1"))
    }

    @Test
    fun ofIsIdempotent() {
        val once = DisplayName.of("a\nb\u0085c\u0090d\u007F")
        assertEquals(once, DisplayName.of(once))
    }

    @Test
    fun bidiControlsAndLineSeparatorsBecomeAMiddleDot() {
        for (code in directionAndSeparatorCodes) {
            assertEquals("U+%04X".format(code), "·", DisplayName.of(code.toChar().toString()))
        }
    }

    @Test
    fun ofCoversExactlyTheDocumentedInvisibleSet() {
        val replaced = (0x00..0xFFFF).filter { code ->
            val shown = DisplayName.of(code.toChar().toString())
            val isControl = code.toChar().isISOControl()
            shown != code.toChar().toString() && !isControl
        }
        assertEquals(directionAndSeparatorCodes.toList(), replaced)
    }

    @Test
    fun aReorderingNameCannotHideItsExtension() {
        val shown = DisplayName.of("photo\u202Egnp.exe")
        assertEquals("photo·gnp.exe", shown)
        assertFalse(shown.any { it in '\u202A'..'\u202E' })
    }

    @Test
    fun lineAndParagraphSeparatorsDoNotBreakARow() {
        assertEquals("a·b·c", DisplayName.of("a\u2028b\u2029c"))
    }

    @Test
    fun ofIsIdempotentForDirectionControls() {
        val once = DisplayName.of("a\u200Eb\u061Cc\u2066d\u2069e\u2028f\n")
        assertEquals(once, DisplayName.of(once))
    }

    @Test
    fun otherInvisibleCharactersAreKept() {
        assertEquals("a\u200Bb\u200Dc\uFEFFd\u2060e", DisplayName.of("a\u200Bb\u200Dc\uFEFFd\u2060e"))
    }

    // --- inSentence ---

    @Test
    fun inSentenceIsolatesTheName() {
        assertEquals("\u2068abc\u2069", DisplayName.inSentence("abc"))
    }

    @Test
    fun inSentenceAlsoMakesControlsVisible() {
        assertEquals("\u2068a␊b\u2069", DisplayName.inSentence("a\nb"))
    }

    @Test
    fun aNameCannotCloseTheIsolateOrReorderTheSentence() {
        val shown = DisplayName.inSentence("x\u2069\u202Eabc")
        assertEquals("\u2068x··abc\u2069", shown)
        assertEquals(1, shown.count { it == '\u2069' })
        assertEquals(1, shown.count { it == '\u2068' })
        assertFalse(shown.contains('\u202E'))
    }

    @Test
    fun inSentenceHasNoDirectionControlsOtherThanItsOwnPair() {
        val every = directionAndSeparatorCodes.joinToString("") { it.toChar().toString() }
        val shown = DisplayName.inSentence("a${every}b")
        val inner = shown.substring(1, shown.length - 1)
        assertTrue(inner, inner.none { it.code in directionAndSeparatorCodes })
        assertTrue(shown.startsWith("\u2068"))
        assertTrue(shown.endsWith("\u2069"))
    }

    // --- middleEllipsis ---

    @Test
    fun middleEllipsisKeepsTheExtension() {
        val shown = DisplayName.middleEllipsis("verylongname.pdf", 10)
        assertTrue(shown, shown.endsWith(".pdf"))
        assertTrue(shown, shown.contains("…"))
        assertEquals(10, shown.length)
        assertEquals("veryl….pdf", shown)
    }

    @Test
    fun middleEllipsisLeavesShortNamesAlone() {
        assertEquals("a.txt", DisplayName.middleEllipsis("a.txt", 10))
        assertEquals("exactly10c", DisplayName.middleEllipsis("exactly10c", 10))
        assertEquals("", DisplayName.middleEllipsis("", 10))
    }

    @Test
    fun middleEllipsisCutsTheMiddleOfNamesWithoutAnExtension() {
        val shown = DisplayName.middleEllipsis("abcdefghijklmnop", 9)
        assertEquals("abcd…mnop", shown)
    }

    @Test
    fun middleEllipsisUsesOnlyTheLastExtension() {
        val shown = DisplayName.middleEllipsis("archive.backup.tar.gz", 12)
        assertEquals(12, shown.length)
        assertTrue(shown, shown.endsWith(".gz"))
        assertTrue(shown, shown.contains("…"))
    }

    @Test
    fun middleEllipsisTreatsALeadingDotAsPartOfTheName() {
        val shown = DisplayName.middleEllipsis(".averyveryverylonghiddenname", 10)
        assertEquals(10, shown.length)
        assertEquals(".aver…name", shown)
    }

    @Test
    fun middleEllipsisFallsBackWhenTheExtensionDoesNotFit() {
        val shown = DisplayName.middleEllipsis("a.averyveryverylongextension", 10)
        assertEquals(10, shown.length)
        assertEquals("a.ave…sion", shown)
    }

    @Test
    fun middleEllipsisNeverSplitsASurrogatePair() {
        val name = "😀😀😀😀😀😀😀😀.png"
        for (max in 4..name.length) {
            val shown = DisplayName.middleEllipsis(name, max)
            assertTrue("max=$max shown=$shown", shown.length <= max)
            assertTrue("max=$max shown=$shown", isWellFormed(shown))
        }
    }

    @Test
    fun middleEllipsisNeverExceedsTheLimit() {
        val names = listOf(
            "verylongname.pdf", "no-extension-at-all-here", ".hidden-and-quite-long",
            "한국어로 된 아주 긴 파일 이름입니다.txt", "a.b", "trailingdot.", "x".repeat(300) + ".zip",
        )
        for (name in names) {
            for (max in 1..40) {
                val shown = DisplayName.middleEllipsis(name, max)
                assertTrue("name=$name max=$max shown=$shown", shown.length <= max)
                if (name.length > max) assertTrue("name=$name max=$max shown=$shown", shown.contains("…"))
            }
        }
    }

    @Test
    fun middleEllipsisWithATinyLimitIsJustTheEllipsis() {
        assertEquals("…", DisplayName.middleEllipsis("verylongname.pdf", 1))
        assertEquals("", DisplayName.middleEllipsis("verylongname.pdf", 0))
    }

    private fun isWellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i++
            }
        }
        return true
    }
}
