// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class NamesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val plain = VolumeTraits(caseInsensitive = true, fatNames = false)
    private val fat = VolumeTraits(caseInsensitive = true, fatNames = true)

    private val ko = NameLabels(newFolder = "새 폴더", newTextDocument = "새 텍스트 문서", copySuffix = " - 복사본")
    private val en = NameLabels(newFolder = "New folder", newTextDocument = "New Text Document", copySuffix = " - Copy")

    private fun check(raw: String, traits: VolumeTraits = plain): NameCheck = NameValidator.validate(raw, traits)

    private fun bad(problem: NameProblem) = NameCheck.Bad(problem)

    private fun utf8Bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** True when every surrogate in [s] is half of a proper pair. */
    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                    i++
                }
                c.isLowSurrogate() -> return false
            }
            i++
        }
        return true
    }

    // --- NameValidator ---

    @Test
    fun rejectsEmptySlashNulDots() {
        assertEquals(bad(NameProblem.Empty), check(""))
        assertEquals(bad(NameProblem.Empty), check("   "))
        assertEquals(bad(NameProblem.Slash), check("a/b"))
        assertEquals(bad(NameProblem.Slash), check("/"))
        assertEquals(bad(NameProblem.ControlChar), check("a\u0000"))
        assertEquals(bad(NameProblem.DotOrDotDot), check("."))
        assertEquals(bad(NameProblem.DotOrDotDot), check(".."))
        assertEquals(bad(NameProblem.DotOrDotDot), check(" .. "))
        assertEquals(NameCheck.Ok("..."), check("..."))
        assertEquals(NameCheck.Ok(".hidden"), check(".hidden"))
    }

    @Test
    fun rejectsControlAndBidi() {
        assertEquals(bad(NameProblem.ControlChar), check("a\u0007"))
        // C0 (tab and line breaks included), DEL and C1 controls.
        for (code in listOf(0x01, 0x09, 0x0A, 0x0D, 0x1F, 0x7F, 0x80, 0x85, 0x9F)) {
            assertEquals("U+%04X".format(code), bad(NameProblem.ControlChar), check("a${code.toChar()}b"))
        }
        // Only plain spaces are trimmed: a leading or trailing line break is still rejected.
        assertEquals(bad(NameProblem.ControlChar), check("\na"))
        assertEquals(bad(NameProblem.ControlChar), check("a\t"))

        assertEquals(bad(NameProblem.BidiControl), check("a\u202Eb"))
        val bidi = listOf(0x200E, 0x200F, 0x061C) + (0x202A..0x202E) + (0x2066..0x2069)
        for (code in bidi) {
            assertEquals("U+%04X".format(code), bad(NameProblem.BidiControl), check("a${code.toChar()}b"))
        }
        // The neighbours of those ranges are ordinary characters.
        for (code in listOf(0x200D, 0x2010, 0x2029, 0x202F, 0x2065, 0x206A)) {
            assertEquals("U+%04X".format(code), NameCheck.Ok("a${code.toChar()}b"), check("a${code.toChar()}b"))
        }
    }

    @Test
    fun trimsSurroundingSpaces() {
        assertEquals(NameCheck.Ok("a.txt"), check("  a.txt "))
        assertEquals(NameCheck.Ok("a b"), check(" a b "))
        assertEquals(NameCheck.Ok("a.txt"), check("  a.txt ", fat))
    }

    @Test
    fun tooLongBytes() {
        val hangul85 = "가".repeat(85)
        val hangul86 = "가".repeat(86)
        assertEquals(255, utf8Bytes(hangul85))
        assertEquals(258, utf8Bytes(hangul86))
        assertEquals(NameCheck.Ok(hangul85), check(hangul85))
        assertEquals(bad(NameProblem.TooLong), check(hangul86))
        assertEquals(NameCheck.Ok(hangul85), check(hangul85, fat))
        assertEquals(bad(NameProblem.TooLong), check(hangul86, fat))

        assertEquals(NameCheck.Ok("a".repeat(255)), check("a".repeat(255)))
        assertEquals(bad(NameProblem.TooLong), check("a".repeat(256)))

        // A surrogate pair is 4 bytes: 63 of them and 3 letters fill the limit.
        val emoji63 = "😀".repeat(63) + "abc"
        assertEquals(255, utf8Bytes(emoji63))
        assertEquals(NameCheck.Ok(emoji63), check(emoji63))
        assertEquals(bad(NameProblem.TooLong), check(emoji63 + "d"))
        assertEquals(bad(NameProblem.TooLong), check("😀".repeat(64)))
    }

    @Test
    fun lengthIsMeasuredAfterTrimming() {
        val name = "a".repeat(255)
        assertEquals(NameCheck.Ok(name), check("  $name  "))
    }

    @Test
    fun fatRulesOnlyOnFatVolumes() {
        assertEquals(NameCheck.Ok("a:b"), check("a:b", plain))
        assertEquals(bad(NameProblem.FatChar(':')), check("a:b", fat))

        for (c in "\\:*?\"<>|") {
            assertEquals(NameCheck.Ok("a${c}b"), check("a${c}b", plain))
            assertEquals(bad(NameProblem.FatChar(c)), check("a${c}b", fat))
        }
        // The first offending character is the one reported.
        assertEquals(bad(NameProblem.FatChar('*')), check("a*b?", fat))

        assertEquals(NameCheck.Ok("a."), check("a.", plain))
        assertEquals(bad(NameProblem.TrailingDotOrSpace), check("a.", fat))
        // The trailing space is trimmed away; the dot that is left in front of it is not allowed.
        assertEquals(NameCheck.Ok("a"), check("a ", plain))
        assertEquals(NameCheck.Ok("a"), check("a ", fat))
        assertEquals(NameCheck.Ok("a."), check("a. ", plain))
        assertEquals(bad(NameProblem.TrailingDotOrSpace), check("a. ", fat))
        // Dots inside a name are fine on FAT.
        assertEquals(NameCheck.Ok("a.b.c"), check("a.b.c", fat))
        assertEquals(NameCheck.Ok(".hidden"), check(".hidden", fat))
    }

    @Test
    fun alwaysRulesComeFirstOnFat() {
        assertEquals(bad(NameProblem.Slash), check("a/b:c", fat))
        assertEquals(bad(NameProblem.ControlChar), check("a:\u0007", fat))
        assertEquals(bad(NameProblem.DotOrDotDot), check("..", fat))
        assertEquals(bad(NameProblem.Empty), check("", fat))
    }

    // --- splitName ---

    @Test
    fun splitNameCases() {
        assertEquals("report" to ".pdf", splitName("report.pdf"))
        assertEquals(".bashrc" to "", splitName(".bashrc"))
        assertEquals("a.tar" to ".gz", splitName("a.tar.gz"))
        assertEquals("noext" to "", splitName("noext"))
        assertEquals("a." to "", splitName("a."))
        assertEquals("..." to "", splitName("..."))
        assertEquals(".a" to ".b", splitName(".a.b"))
        assertEquals("" to "", splitName(""))
        // The extension keeps its case: it is part of a real file name.
        assertEquals("Report" to ".PDF", splitName("Report.PDF"))
        assertEquals("사진" to ".JPG", splitName("사진.JPG"))
        for (name in listOf("report.pdf", ".bashrc", "a.tar.gz", "a.", "..x", "x..", "A.B.C")) {
            val (stem, ext) = splitName(name)
            assertEquals(name, stem + ext)
        }
    }

    // --- candidateName ---

    @Test
    fun keepBothAndCopyOfNames() {
        assertEquals("새 폴더", candidateName(ko.newFolder, "", UniqueStyle.KEEP_BOTH, 1, ko))
        assertEquals("새 폴더 (2)", candidateName(ko.newFolder, "", UniqueStyle.KEEP_BOTH, 2, ko))
        assertEquals("새 폴더 (10)", candidateName(ko.newFolder, "", UniqueStyle.KEEP_BOTH, 10, ko))
        assertEquals("새 텍스트 문서.txt", candidateName(ko.newTextDocument, ".txt", UniqueStyle.KEEP_BOTH, 1, ko))
        assertEquals("새 텍스트 문서 (3).txt", candidateName(ko.newTextDocument, ".txt", UniqueStyle.KEEP_BOTH, 3, ko))

        assertEquals("사진 - 복사본.jpg", candidateName("사진", ".jpg", UniqueStyle.COPY_OF, 1, ko))
        assertEquals("사진 - 복사본 (2).jpg", candidateName("사진", ".jpg", UniqueStyle.COPY_OF, 2, ko))
        assertEquals("폴더 - 복사본", candidateName("폴더", "", UniqueStyle.COPY_OF, 1, ko))

        assertEquals("photo - Copy.jpg", candidateName("photo", ".jpg", UniqueStyle.COPY_OF, 1, en))
        assertEquals("photo - Copy (2).jpg", candidateName("photo", ".jpg", UniqueStyle.COPY_OF, 2, en))
        assertEquals("New folder (2)", candidateName(en.newFolder, "", UniqueStyle.KEEP_BOTH, 2, en))
        assertEquals("New Text Document.txt", candidateName(en.newTextDocument, ".txt", UniqueStyle.KEEP_BOTH, 1, en))
    }

    @Test
    fun candidateNumberStartsAtOne() {
        assertThrows(IllegalArgumentException::class.java) {
            candidateName("a", ".txt", UniqueStyle.KEEP_BOTH, 0, en)
        }
    }

    @Test
    fun candidateTrimmedToFitBytes() {
        // 84 Hangul syllables are 252 bytes: with the extension and the suffix they do not fit.
        val stem = "가".repeat(84)
        val name = candidateName(stem, ".txt", UniqueStyle.COPY_OF, 1, ko)
        assertTrue(utf8Bytes(name) <= 255)
        assertTrue(name.endsWith(" - 복사본.txt"))
        // Cut as late as possible: 79 syllables (237 bytes) + 16 bytes of suffix and extension.
        assertEquals("가".repeat(79) + " - 복사본.txt", name)
        assertEquals(253, utf8Bytes(name))

        // Numbered names reserve room for the number.
        val numbered = candidateName(stem, ".txt", UniqueStyle.COPY_OF, 12, ko)
        assertEquals("가".repeat(78) + " - 복사본 (12).txt", numbered)
        assertTrue(utf8Bytes(numbered) <= 255)

        // A stem that fits is left alone.
        assertEquals("가".repeat(80) + ".txt", candidateName("가".repeat(80), ".txt", UniqueStyle.KEEP_BOTH, 1, ko))
    }

    @Test
    fun candidateIsCutOnCodePointBoundaries() {
        // 70 emoji are 280 bytes. The room left for them is 247 bytes: 61 whole emoji, never 61 and a half.
        val stem = "😀".repeat(70)
        val name = candidateName(stem, ".txt", UniqueStyle.KEEP_BOTH, 2, en)
        assertEquals("😀".repeat(61) + " (2).txt", name)
        assertTrue(wellFormed(name))
        assertTrue(utf8Bytes(name) <= 255)

        // One spare byte instead of three: still no half emoji.
        val oneSpare = candidateName("😀".repeat(70), ".abcde", UniqueStyle.KEEP_BOTH, 2, en)
        assertEquals("😀".repeat(61) + " (2).abcde", oneSpare)
        assertTrue(wellFormed(oneSpare))

        // Mixed widths: whole code points are kept or dropped.
        val mixed = candidateName("a😀".repeat(100), ".md", UniqueStyle.COPY_OF, 1, en)
        assertTrue(wellFormed(mixed))
        assertTrue(utf8Bytes(mixed) <= 255)
        assertTrue(mixed.endsWith(" - Copy.md"))
        // One more code point would not have fit.
        val kept = mixed.removeSuffix(" - Copy.md")
        val next = ("a😀".repeat(100)).codePointAt(kept.length)
        assertTrue(utf8Bytes(mixed) + utf8Bytes(String(Character.toChars(next))) > 255)
    }

    @Test
    fun candidateKeepsExtensionWhenStemVanishes() {
        // The extension alone fills the limit: nothing of the stem survives, the extension does.
        val ext = "." + "e".repeat(254)
        val name = candidateName("stem", ext, UniqueStyle.KEEP_BOTH, 2, en)
        assertEquals(" (2)$ext", name)
    }

    // --- claimUniqueName ---

    @Test
    fun claimSkipsTakenNames() {
        val dir = folder.root.toPath()
        Files.createDirectory(dir.resolve("새 폴더"))
        Files.createDirectory(dir.resolve("새 폴더 (2)"))

        val (path, claimed) = claimUniqueName(dir, ko.newFolder, "", UniqueStyle.KEEP_BOTH, ko, 1) {
            Files.createDirectory(it)
        }
        assertEquals(dir.resolve("새 폴더 (3)"), path)
        assertEquals(path, claimed)
        assertTrue(Files.isDirectory(dir.resolve("새 폴더 (3)")))
    }

    @Test
    fun claimWorksForFilesWithExtensions() {
        val dir = folder.root.toPath()
        Files.createFile(dir.resolve("photo - Copy.jpg"))

        val (path, _) = claimUniqueName(dir, "photo", ".jpg", UniqueStyle.COPY_OF, en, 1) {
            Files.createFile(it)
        }
        assertEquals(dir.resolve("photo - Copy (2).jpg"), path)
        assertTrue(Files.isRegularFile(path))
    }

    @Test
    fun claimReturnsWhatTheClaimReturned() {
        val dir = folder.root.toPath()
        val (path, value) = claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 1) {
            it.fileName.toString().length
        }
        assertEquals(dir.resolve("a.txt"), path)
        assertEquals(5, value)
    }

    @Test
    fun claimStartsAtTheGivenNumber() {
        val dir = folder.root.toPath()
        val tried = mutableListOf<Path>()
        val (path, _) = claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 2) { tried.add(it) }
        assertEquals(dir.resolve("a (2).txt"), path)
        assertEquals(listOf(path), tried)
    }

    @Test
    fun claimMovesOnOnlyForFileAlreadyExists() {
        val dir = folder.root.toPath()
        val tried = mutableListOf<String>()
        val (path, _) = claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 1) {
            tried += it.fileName.toString()
            if (tried.size < 4) throw FileAlreadyExistsException(it.toString())
        }
        assertEquals(listOf("a.txt", "a (2).txt", "a (3).txt", "a (4).txt"), tried)
        assertEquals(dir.resolve("a (4).txt"), path)
    }

    @Test
    fun claimGivesUpAfter9999() {
        val dir = folder.root.toPath()
        var calls = 0
        val e = assertThrows(FsException::class.java) {
            claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 1) {
                calls++
                throw FileAlreadyExistsException(it.toString())
            }
        }
        assertEquals(FsError.AlreadyExists, e.error)
        assertEquals(9999, calls)

        calls = 0
        val late = assertThrows(FsException::class.java) {
            claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 9998) {
                calls++
                throw FileAlreadyExistsException(it.toString())
            }
        }
        assertEquals(FsError.AlreadyExists, late.error)
        assertEquals(2, calls)
    }

    @Test
    fun claimDoesNotSwallowOtherFailures() {
        val dir = folder.root.toPath()
        var calls = 0
        assertThrows(AccessDeniedException::class.java) {
            claimUniqueName(dir, "a", ".txt", UniqueStyle.KEEP_BOTH, en, 1) {
                calls++
                throw AccessDeniedException(it.toString())
            }
        }
        assertEquals(1, calls)
    }

    // --- sameNameIgnoringCase ---

    @Test
    fun sameNameIgnoringCaseNfc() {
        assertTrue(sameNameIgnoringCase("Readme.MD", "readme.md"))
        assertTrue(sameNameIgnoringCase("same", "same"))
        assertFalse(sameNameIgnoringCase("a.txt", "b.txt"))
        assertFalse(sameNameIgnoringCase("a.txt", "a.txt "))
        // NFD and NFC spellings of the same Hangul syllable (and of é) are one name.
        assertTrue(sameNameIgnoringCase("\u1100\u1161", "\uAC00"))
        assertTrue(sameNameIgnoringCase("Café", "CAFÉ"))
        assertTrue(sameNameIgnoringCase("Cafe\u0301", "CAF\u00C9"))
    }

    @Test
    fun sameNameIgnoringCaseIgnoresTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // Under Turkish rules "I" lowers to a dotless ı; ROOT rules lower it to "i".
            assertTrue(sameNameIgnoringCase("TITLE", "title"))
            assertFalse(sameNameIgnoringCase("ı", "i"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
