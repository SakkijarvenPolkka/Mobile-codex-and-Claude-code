// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset
import java.nio.file.Path
import java.text.Normalizer
import java.util.zip.ZipException

class ZipCharsetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir: Path by lazy { folder.newFolder("dir").toPath() }

    private val journal = MemoryJournal()

    private val zip = ZipOps(NioFsBackend, WriteProtocol(NioFsBackend, journal), KO_LABELS)

    private fun extract(archive: Path, charset: Charset? = null): ExtractResult =
        runBlocking { zip.extract(archive, dir, charset, OP_ID) {} }

    @Test
    fun cp949WithoutEfsDetected() {
        val archive = putZip(dir, "x.zip", zipOf("가.txt" to "x", "각.txt" to "y", charset = Charset.forName("MS949")))

        assertEquals(koreanCharset(), zip.detectCharset(archive))
        val result = extract(archive)

        assertEquals(mapOf("가.txt" to "x", "각.txt" to "y"), tree(result.folder))
    }

    @Test
    fun utf8WithEfs() {
        val bytes = zipOf("한글.txt" to "x", "a.txt" to "y")
        assertTrue(centralHeaders(bytes).indices.all { bytes.flags(it) and FLAG_UTF8 != 0 })
        val archive = putZip(dir, "x.zip", bytes)

        assertEquals(Charsets.UTF_8, zip.detectCharset(archive))
        assertEquals(mapOf("a.txt" to "y", "한글.txt" to "x"), tree(extract(archive).folder))
    }

    @Test
    fun nfdUtf8WithoutEfsNormalizedToNfc() {
        // As the Finder of macOS writes names: decomposed Hangul, in UTF-8 but not flagged so.
        val nfd = Normalizer.normalize("한글.txt", Normalizer.Form.NFD)
        val bytes = zipOf(nfd to "x").apply { clearFlags(0, FLAG_UTF8) }
        val archive = putZip(dir, "x.zip", bytes)

        assertEquals(Charsets.UTF_8, zip.detectCharset(archive))
        val names = tree(extract(archive).folder).keys

        assertEquals(setOf("한글.txt"), names)
        assertFalse(names.single() == nfd)
    }

    @Test
    fun throwingOpenFallsBack() {
        val tried = ArrayList<Charset>()

        val chosen = chooseZipCharset { charset ->
            tried += charset
            if (charset == Charsets.UTF_8) throw IllegalArgumentException("MALFORMED")
            listOf("가.txt")
        }

        assertEquals(koreanCharset(), chosen)
        assertEquals(listOf(Charsets.UTF_8, koreanCharset()), tried)
        // A ZipException (the JDK's "bad entry name") falls back as well.
        assertEquals(
            koreanCharset(),
            chooseZipCharset { if (it == Charsets.UTF_8) throw ZipException("invalid CEN header") else listOf("a") },
        )
    }

    @Test
    fun replacementCharFallsBack() {
        val chosen = chooseZipCharset { charset ->
            if (charset == Charsets.UTF_8) listOf("a.txt", "��.txt") else listOf("a.txt", "가.txt")
        }

        assertEquals(koreanCharset(), chosen)
        assertTrue(hasReplacementChar(listOf("a", "b�")))
        assertFalse(hasReplacementChar(listOf("a", "가")))
        // UTF-8 is taken when it decodes every name.
        assertEquals(Charsets.UTF_8, chooseZipCharset { listOf("가.txt") })
    }

    @Test
    fun allFailThrowsUnsupportedEncoding() {
        val thrown = assertThrows(FsException::class.java) {
            chooseZipCharset { throw IllegalArgumentException("MALFORMED") }
        }
        assertEquals(FsError.UnsupportedEncoding, thrown.error)

        val replaced = assertThrows(FsException::class.java) { chooseZipCharset { listOf("�") } }
        assertEquals(FsError.UnsupportedEncoding, replaced.error)
    }

    @Test
    fun forcedCharsetThatDoesNotDecodeIsUnsupportedEncoding() {
        val archive = putZip(dir, "x.zip", zipOf("가.txt" to "x", charset = Charset.forName("MS949")))

        val thrown = assertThrows(FsException::class.java) { extract(archive, Charsets.UTF_8) }

        assertEquals(FsError.UnsupportedEncoding, thrown.error)
        assertEquals(setOf("x.zip"), tree(dir).keys)
    }
}
