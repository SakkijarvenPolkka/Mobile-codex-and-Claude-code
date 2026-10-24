// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult
import kotlin.text.Charsets.UTF_16BE
import kotlin.text.Charsets.UTF_16LE
import kotlin.text.Charsets.UTF_8

class TextSnifferTest {

    /**
     * UTF-8 decoded the way Android's ICU decoders do it: a character left unfinished at the end of the
     * input is taken into the decoder (all the input is consumed), and only flush() tells of it, as
     * malformed. Unlike the JDK's, which leaves those bytes in the input.
     */
    private class BufferingUtf8 : Charset("x-test-buffering-utf-8", null) {
        override fun contains(cs: Charset): Boolean = false

        override fun newEncoder(): CharsetEncoder = throw UnsupportedOperationException()

        override fun newDecoder(): CharsetDecoder = object : CharsetDecoder(this, 1f, 1f) {
            private var held = 0

            override fun decodeLoop(input: ByteBuffer, output: CharBuffer): CoderResult {
                val bytes = ByteArray(input.remaining()).also { input.get(it) }
                val cut = unfinishedTail(bytes)
                held += cut
                output.put(String(bytes, 0, bytes.size - cut, UTF_8))
                return CoderResult.UNDERFLOW
            }

            override fun implFlush(output: CharBuffer): CoderResult =
                if (held > 0) CoderResult.malformedForLength(held) else CoderResult.UNDERFLOW

            override fun implReset() {
                held = 0
            }
        }

        /** How many bytes at the end of [bytes] begin a character they do not finish. */
        private fun unfinishedTail(bytes: ByteArray): Int {
            for (back in 1..minOf(3, bytes.size)) {
                val b = bytes[bytes.size - back].toInt() and 0xFF
                if (b and 0xC0 == 0x80) continue
                val length = when {
                    b >= 0xF0 -> 4
                    b >= 0xE0 -> 3
                    b >= 0xC0 -> 2
                    else -> 1
                }
                return if (back < length) back else 0
            }
            return 0
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun sniff(bytes: ByteArray, truncated: Boolean = false, forced: TextEncoding? = null): SniffResult =
        TextSniffer.sniff(bytes, truncated, forced)

    private fun text(result: SniffResult): SniffResult.Text =
        result as? SniffResult.Text ?: throw AssertionError("expected text, got $result")

    @Test
    fun bomDetection() {
        val sample = "한글 abc"
        assertEquals(
            SniffResult.Text(sample, TextEncoding.UTF8, truncated = false),
            sniff(bytes(0xEF, 0xBB, 0xBF) + sample.toByteArray(UTF_8)),
        )
        // UTF-16 text is full of NUL bytes; the byte order mark is looked at first.
        assertEquals(
            SniffResult.Text(sample, TextEncoding.UTF16LE, truncated = false),
            sniff(bytes(0xFF, 0xFE) + sample.toByteArray(UTF_16LE)),
        )
        assertEquals(
            SniffResult.Text(sample, TextEncoding.UTF16BE, truncated = false),
            sniff(bytes(0xFE, 0xFF) + sample.toByteArray(UTF_16BE)),
        )
        assertEquals(SniffResult.Text("", TextEncoding.UTF16LE, truncated = false), sniff(bytes(0xFF, 0xFE)))
    }

    @Test
    fun nulMeansBinary() {
        assertEquals(SniffResult.Binary, sniff("abc".toByteArray() + bytes(0) + "def".toByteArray()))
        assertEquals(SniffResult.Binary, sniff(ByteArray(8 * 1024) { if (it == 8 * 1024 - 1) 0 else 'a'.code.toByte() }))
        assertEquals(SniffResult.Binary, sniff("abc".toByteArray(UTF_16LE)))
        // Only the first 8 KiB are looked at.
        val lateNul = ByteArray(8 * 1024 + 1) { if (it == 8 * 1024) 0 else 'a'.code.toByte() }
        assertEquals(TextEncoding.UTF8, text(sniff(lateNul)).encoding)
    }

    @Test
    fun strictUtf8() {
        val sample = "한글, ASCII, ✓ and \uD83D\uDE00\r\nsecond line"
        assertEquals(SniffResult.Text(sample, TextEncoding.UTF8, truncated = false), sniff(sample.toByteArray(UTF_8)))
        assertEquals(SniffResult.Text("", TextEncoding.UTF8, truncated = false), sniff(ByteArray(0)))
        // A lead byte without its continuation, an overlong form, and (in a whole file) an unfinished last character.
        listOf(
            bytes(0x61, 0xC3, 0x28, 0x62),
            bytes(0x61, 0xC0, 0xAF, 0x62),
            "가".toByteArray(UTF_8).copyOf(2),
        ).forEach { invalid ->
            assertEquals(TextEncoding.CP949, text(sniff(invalid)).encoding)
        }
    }

    @Test
    fun cp949Fallback() {
        assertEquals(
            SniffResult.Text("한글", TextEncoding.CP949, truncated = false),
            sniff("한글".toByteArray(koreanCharset())),
        )
        val lines = "첫째 줄\n둘째 줄 (EUC-KR에 없는 글자: 똠방각하)"
        assertEquals(SniffResult.Text(lines, TextEncoding.CP949, truncated = false), sniff(lines.toByteArray(koreanCharset())))
    }

    @Test
    fun koreanCharsetIsCodePage949() {
        assertTrue(koreanCharset().name(), koreanCharset().name() in listOf("x-windows-949", "MS949", "windows-949", "EUC-KR"))
        assertArrayEquals(bytes(0xC7, 0xD1, 0xB1, 0xDB), "한글".toByteArray(koreanCharset()))
    }

    @Test
    fun truncatedUtf8TailNotMalformed() {
        // The 1 MiB head of "가" (3 bytes) repeated, after 0, 1 and 2 ASCII bytes: it ends 1 byte,
        // 0 bytes and 2 bytes into a character.
        listOf("" to "\uFFFD", "a" to "", "ab" to "\uFFFD").forEach { (prefix, tail) ->
            val whole = (prefix + "가".repeat(TEXT_PREVIEW_LIMIT / 3 + 1)).toByteArray(UTF_8)
            val result = text(sniff(whole.copyOf(TEXT_PREVIEW_LIMIT), truncated = true))
            val complete = (TEXT_PREVIEW_LIMIT - prefix.length) / 3
            assertEquals(TextEncoding.UTF8, result.encoding)
            assertTrue(result.truncated)
            assertEquals("prefix '$prefix'", prefix + "가".repeat(complete) + tail, result.text)
        }
    }

    @Test
    fun truncatedUtf16TrimmedEven() {
        val le = bytes(0xFF, 0xFE) + "가나다".toByteArray(UTF_16LE)
        assertEquals(SniffResult.Text("가나", TextEncoding.UTF16LE, truncated = true), sniff(le.copyOf(le.size - 1), truncated = true))
        val be = bytes(0xFE, 0xFF) + "가나다".toByteArray(UTF_16BE)
        assertEquals(SniffResult.Text("가나", TextEncoding.UTF16BE, truncated = true), sniff(be.copyOf(be.size - 1), truncated = true))
        // Forced UTF-16 without a byte order mark is trimmed too.
        val bare = "ab".toByteArray(UTF_16LE)
        assertEquals(
            SniffResult.Text("a", TextEncoding.UTF16LE, truncated = true),
            sniff(bare.copyOf(bare.size - 1), truncated = true, forced = TextEncoding.UTF16LE),
        )
    }

    @Test
    fun forcedEncodingWins() {
        // Valid UTF-8 whose bytes (C3 A9 C3 A8) are also two Hangul syllables in CP949.
        val utf8 = "éè".toByteArray(UTF_8)
        assertEquals(TextEncoding.UTF8, text(sniff(utf8)).encoding)
        val asCp949 = text(sniff(utf8, forced = TextEncoding.CP949))
        assertEquals(SniffResult.Text(String(utf8, koreanCharset()), TextEncoding.CP949, truncated = false), asCp949)
        assertEquals(2, asCp949.text.length)
        assertFalse('\uFFFD' in asCp949.text)
        val cp949 = "abc 한글".toByteArray(koreanCharset())
        val asUtf8 = text(sniff(cp949, forced = TextEncoding.UTF8))
        assertEquals(TextEncoding.UTF8, asUtf8.encoding)
        assertTrue(asUtf8.text, asUtf8.text.startsWith("abc ") && '\uFFFD' in asUtf8.text)
        // NUL bytes do not stop a forced encoding: UTF-16 without a byte order mark.
        assertEquals(
            SniffResult.Text("abc", TextEncoding.UTF16LE, truncated = false),
            sniff("abc".toByteArray(UTF_16LE), forced = TextEncoding.UTF16LE),
        )
        assertEquals(
            SniffResult.Text("abc", TextEncoding.UTF16BE, truncated = false),
            sniff("abc".toByteArray(UTF_16BE), forced = TextEncoding.UTF16BE),
        )
        // The byte order mark of the forced encoding is not part of the text.
        assertEquals(
            SniffResult.Text("abc", TextEncoding.UTF8, truncated = false),
            sniff(bytes(0xEF, 0xBB, 0xBF) + "abc".toByteArray(), forced = TextEncoding.UTF8),
        )
        assertEquals(
            SniffResult.Text("abc", TextEncoding.UTF16LE, truncated = false),
            sniff(bytes(0xFF, 0xFE) + "abc".toByteArray(UTF_16LE), forced = TextEncoding.UTF16LE),
        )
    }

    @Test
    fun readHeadStopsAtLimit() {
        val content = ByteArray(2 * TEXT_PREVIEW_LIMIT) { (it % 251).toByte() }
        val input = ByteArrayInputStream(content)
        val (head, truncated) = TextSniffer.readHead(input)
        assertEquals(TEXT_PREVIEW_LIMIT, head.size)
        assertTrue(truncated)
        assertArrayEquals(content.copyOf(TEXT_PREVIEW_LIMIT), head)
        assertTrue("read past the limit", input.available() >= TEXT_PREVIEW_LIMIT - 1)

        val (exact, exactTruncated) = TextSniffer.readHead(ByteArrayInputStream(ByteArray(TEXT_PREVIEW_LIMIT)))
        assertEquals(TEXT_PREVIEW_LIMIT, exact.size)
        assertFalse(exactTruncated)

        // A stream that hands out a few bytes per read.
        val short = ByteArray(10_000) { (it % 7).toByte() }
        val trickle = object : InputStream() {
            private val inner = ByteArrayInputStream(short)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 7))
        }
        val (all, allTruncated) = TextSniffer.readHead(trickle)
        assertArrayEquals(short, all)
        assertFalse(allTruncated)

        val (small, smallTruncated) = TextSniffer.readHead(ByteArrayInputStream(short), limit = 100)
        assertArrayEquals(short.copyOf(100), small)
        assertTrue(smallTruncated)
    }

    @Test
    fun splitLongLines() {
        assertEquals(
            listOf(
                DisplayLine(1, "x".repeat(2000), continuation = false),
                DisplayLine(1, "x".repeat(2000), continuation = true),
                DisplayLine(1, "x".repeat(500), continuation = true),
            ),
            splitDisplayLines("x".repeat(4500)),
        )
        assertEquals(
            listOf(
                DisplayLine(1, "a", continuation = false),
                DisplayLine(2, "", continuation = false),
                DisplayLine(3, "b", continuation = false),
                DisplayLine(4, "c", continuation = false),
                DisplayLine(5, "", continuation = false),
            ),
            splitDisplayLines("a\r\n\nb\rc\n"),
        )
        assertEquals(listOf(DisplayLine(1, "", continuation = false)), splitDisplayLines(""))
        // A row never ends between the two halves of a surrogate pair.
        val emoji = "\uD83D\uDE00"
        assertEquals(
            listOf(
                DisplayLine(1, "abc", continuation = false),
                DisplayLine(1, emoji + "d", continuation = true),
            ),
            splitDisplayLines("abc${emoji}d", maxLine = 4),
        )
    }

    @Test
    fun isTextCandidateByExtAndMime() {
        assertEquals(
            setOf(
                "txt", "log", "csv", "tsv", "md", "json", "xml", "html", "ini", "cfg", "conf", "yaml", "yml", "smi",
                "srt", "vtt", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh", "gradle", "properties",
            ),
            TEXT_EXTENSIONS,
        )
        assertTrue(isTextCandidate("notes.TXT", null))
        assertTrue(isTextCandidate("build.gradle", null))
        assertTrue(isTextCandidate("data.json", "application/json"))
        assertTrue(isTextCandidate("README", "text/plain"))
        assertTrue(isTextCandidate("page.bin", "TEXT/HTML; charset=utf-8"))
        assertFalse(isTextCandidate("Makefile", null))
        assertFalse(isTextCandidate("photo.jpg", "image/jpeg"))
        assertFalse(isTextCandidate("report.pdf", "application/pdf"))
        assertFalse(isTextCandidate(".txt", null))
    }

    @Test
    fun unfinishedLastCharacterReportedByFlushIsMalformed() {
        // A whole file whose last character is cut short, read by a decoder that tells so only when flushed.
        val bytes = "a".toByteArray() + "가".toByteArray(UTF_8).copyOf(2)
        val buffering = BufferingUtf8()
        assertNull(TextSniffer.decode(bytes, 0, TextEncoding.UTF8, truncated = false, strict = true, charset = buffering))
        assertEquals(
            "a\uFFFD",
            TextSniffer.decode(bytes, 0, TextEncoding.UTF8, truncated = false, strict = false, charset = buffering),
        )
    }

    @Test
    fun truncatedUtf8TailCutWithoutTheDecodersHelp() {
        // The head of a longer file, cut 1 and 2 bytes into "가" and 1 into "😀", read by a decoder that
        // would keep the cut bytes to itself: the cut character is one U+FFFD all the same, and no error.
        val buffering = BufferingUtf8()
        val han = "가".toByteArray(UTF_8)
        val emoji = "\uD83D\uDE00".toByteArray(UTF_8)
        val cuts = listOf(han.copyOf(1), han.copyOf(2), emoji.copyOf(3))
        for (head in cuts.map { "ab".toByteArray() + it }) {
            assertEquals(
                "ab\uFFFD",
                TextSniffer.decode(head, 0, TextEncoding.UTF8, truncated = true, strict = true, charset = buffering),
            )
        }
        // A head that ends on a whole character has nothing cut.
        val whole = "ab".toByteArray() + han
        assertEquals("ab가", TextSniffer.decode(whole, 0, TextEncoding.UTF8, truncated = true, strict = true, charset = buffering))
        // Stray continuation bytes at the end are invalid, not cut.
        assertNull(TextSniffer.decode(bytes(0x61, 0x80, 0x80), 0, TextEncoding.UTF8, truncated = true, strict = true))
    }

    /**
     * What strict UTF-8 makes of a truncated head "ab" + [tail], read by the decoder that keeps unfinished
     * bytes to itself (so nothing but the cut can make it valid).
     */
    private fun truncatedTail(vararg tail: Int): String? {
        val head = "ab".toByteArray() + bytes(*tail)
        val buffering = BufferingUtf8()
        return TextSniffer.decode(head, 0, TextEncoding.UTF8, truncated = true, strict = true, charset = buffering)
    }

    @Test
    fun unfinishedE0NeedsA0ToBF() {
        // E0 80 could only begin an overlong form: not a character cut short, but bytes that are not UTF-8.
        assertEquals("ab\uFFFD", truncatedTail(0xE0, 0xA0))
        assertEquals("ab\uFFFD", truncatedTail(0xE0, 0xBF))
        assertNull(truncatedTail(0xE0, 0x80))
        assertNull(truncatedTail(0xE0, 0x9F))
        // Other three-byte leads take any continuation byte.
        assertEquals("ab\uFFFD", truncatedTail(0xE1, 0x80))
        assertEquals("ab\uFFFD", truncatedTail(0xEF, 0xBF))
    }

    @Test
    fun unfinishedEDNeeds80To9F() {
        // ED A0 to ED BF would begin a surrogate.
        assertEquals("ab\uFFFD", truncatedTail(0xED, 0x80))
        assertEquals("ab\uFFFD", truncatedTail(0xED, 0x9F))
        assertNull(truncatedTail(0xED, 0xA0))
        assertNull(truncatedTail(0xED, 0xBF))
    }

    @Test
    fun unfinishedF0Needs90ToBF() {
        // F0 80 to F0 8F could only begin an overlong form.
        assertEquals("ab\uFFFD", truncatedTail(0xF0, 0x90))
        assertEquals("ab\uFFFD", truncatedTail(0xF0, 0xBF, 0x80))
        assertNull(truncatedTail(0xF0, 0x80))
        assertNull(truncatedTail(0xF0, 0x8F, 0x80))
        // Other four-byte leads take any continuation byte.
        assertEquals("ab\uFFFD", truncatedTail(0xF1, 0x80))
        assertEquals("ab\uFFFD", truncatedTail(0xF3, 0xBF, 0xBF))
    }

    @Test
    fun unfinishedF4Needs80To8F() {
        // F4 90 and above would be past U+10FFFF.
        assertEquals("ab\uFFFD", truncatedTail(0xF4, 0x80))
        assertEquals("ab\uFFFD", truncatedTail(0xF4, 0x8F, 0xBF))
        assertNull(truncatedTail(0xF4, 0x90))
        assertNull(truncatedTail(0xF4, 0xBF, 0x80))
    }

    @Test
    fun readHeadRefusesNegativeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            TextSniffer.readHead(ByteArrayInputStream(ByteArray(1)), limit = -1)
        }
        val (empty, truncated) = TextSniffer.readHead(ByteArrayInputStream(ByteArray(1)), limit = 0)
        assertEquals(0, empty.size)
        assertTrue(truncated)
    }
}
