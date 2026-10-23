/*
 * Files — which files the text viewer shows, how their head is read and how it is decoded:
 * the byte order mark, NUL bytes, strict UTF-8 and then CP949 (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import io.github.sakkijarvenpolkka.explorer.util.extensionOf
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.UnsupportedCharsetException
import java.util.Locale

/** The most of a file the text viewer reads and shows: 1 MiB. */
const val TEXT_PREVIEW_LIMIT = 1 shl 20

/** The extensions (lower case) of the files the text viewer shows whatever their MIME type. */
val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "log", "csv", "tsv", "md", "json", "xml", "html", "ini", "cfg", "conf", "yaml", "yml", "smi", "srt",
    "vtt", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh", "gradle", "properties",
)

/** Whether the text viewer shows a file named [name] of type [mime]: a `text/` type, or an extension in [TEXT_EXTENSIONS]. */
fun isTextCandidate(name: String, mime: String?): Boolean {
    val type = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    return type?.startsWith("text/") == true || extensionOf(name) in TEXT_EXTENSIONS
}

/** The encodings the text viewer reads; [CP949] is [koreanCharset]. */
enum class TextEncoding { UTF8, UTF16LE, UTF16BE, CP949 }

/** What [TextSniffer.sniff] made of the head of a file. */
sealed interface SniffResult {
    /** The bytes decoded with [encoding]; [truncated] when they are only the head of the file. */
    data class Text(val text: String, val encoding: TextEncoding, val truncated: Boolean) : SniffResult

    /** No byte order mark and a NUL byte in the first 8 KiB: not text. */
    data object Binary : SniffResult
}

/** Reads the head of a file and decodes it as text. */
object TextSniffer {

    /** How much of the head is searched for NUL bytes. */
    private const val NUL_SCAN = 8 * 1024

    /** The first buffer of [readHead]; it doubles up to the limit. */
    private const val READ_CHUNK = 64 * 1024

    private const val REPLACEMENT = '\uFFFD'

    /**
     * [bytes], the head of a file ([truncated] when the file goes on), as text: with [forced]
     * when it is given, else by the byte order mark (EF BB BF UTF-8, FF FE UTF-16LE, FE FF
     * UTF-16BE), else [SniffResult.Binary] for a NUL in the first 8 KiB, else UTF-8 when every
     * byte is valid UTF-8, else CP949. A byte order mark is never part of the text. A cut
     * head is not invalid for ending inside a character: UTF-16 is cut to an even length, and
     * a character the cut leaves unfinished becomes one U+FFFD.
     */
    fun sniff(bytes: ByteArray, truncated: Boolean, forced: TextEncoding? = null): SniffResult {
        val bom = bomOf(bytes)
        val encoding = forced ?: bom?.first
        if (encoding != null) return text(bytes, start = if (bom?.first == encoding) bom.second else 0, encoding, truncated)
        if (hasNul(bytes)) return SniffResult.Binary
        val utf8 = decode(bytes, 0, TextEncoding.UTF8, truncated, strict = true)
        return if (utf8 != null) SniffResult.Text(utf8, TextEncoding.UTF8, truncated) else text(bytes, 0, TextEncoding.CP949, truncated)
    }

    /**
     * The first [limit] bytes of [input] (all of it when it is shorter), read in a loop of
     * plain reads, and whether more follows them. Reads at most one byte past [limit].
     */
    fun readHead(input: InputStream, limit: Int = TEXT_PREVIEW_LIMIT): Pair<ByteArray, Boolean> {
        var buffer = ByteArray(minOf(limit, READ_CHUNK))
        var filled = 0
        while (true) {
            if (filled == buffer.size) {
                if (filled == limit) return buffer to (input.read() >= 0)
                buffer = buffer.copyOf(minOf(limit.toLong(), buffer.size * 2L).toInt())
            }
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) return buffer.copyOf(filled) to false
            filled += read
        }
    }

    /** Whether the first [NUL_SCAN] bytes hold a NUL. */
    private fun hasNul(bytes: ByteArray): Boolean {
        for (i in 0 until minOf(bytes.size, NUL_SCAN)) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** The encoding [bytes] start with a byte order mark of, and the mark's length. */
    private fun bomOf(bytes: ByteArray): Pair<TextEncoding, Int>? {
        fun startsWith(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> TextEncoding.UTF8 to 3
            startsWith(0xFF, 0xFE) -> TextEncoding.UTF16LE to 2
            startsWith(0xFE, 0xFF) -> TextEncoding.UTF16BE to 2
            else -> null
        }
    }

    /**
     * [bytes] from [start] in [encoding]. When [strict], null if they are not valid in it;
     * otherwise what is not valid becomes U+FFFD. A [truncated] head is decoded as input that
     * goes on (see [sniff]).
     */
    private fun decode(bytes: ByteArray, start: Int, encoding: TextEncoding, truncated: Boolean, strict: Boolean): String? {
        val utf16 = encoding == TextEncoding.UTF16LE || encoding == TextEncoding.UTF16BE
        val end = if (truncated && utf16 && (bytes.size - start) % 2 != 0) bytes.size - 1 else bytes.size
        val onError = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = charsetOf(encoding).newDecoder().onMalformedInput(onError).onUnmappableCharacter(onError)
        val input = ByteBuffer.wrap(bytes, start, end - start)
        // None of these encodings makes more characters than bytes; one more for the U+FFFD of a cut character.
        var output = CharBuffer.allocate(end - start + 1)
        while (true) {
            val result = decoder.decode(input, output, !truncated)
            if (result.isError) return null
            if (!result.isOverflow) break
            output = output.grown()
        }
        if (truncated) {
            if (input.hasRemaining()) {
                if (!output.hasRemaining()) output = output.grown()
                output.put(REPLACEMENT)
            }
        } else {
            while (decoder.flush(output).isOverflow) output = output.grown()
        }
        // array() and position() rather than flip(): CharBuffer.flip() returning CharBuffer is newer than API 30.
        return String(output.array(), 0, output.position())
    }

    /** [bytes] from [start] decoded with [encoding], what is not valid in it shown as U+FFFD. */
    private fun text(bytes: ByteArray, start: Int, encoding: TextEncoding, truncated: Boolean): SniffResult.Text =
        SniffResult.Text(checkNotNull(decode(bytes, start, encoding, truncated, strict = false)), encoding, truncated)

    private fun CharBuffer.grown(): CharBuffer = CharBuffer.allocate(capacity() * 2 + 16).put(array(), 0, position())

    private fun charsetOf(encoding: TextEncoding): Charset = when (encoding) {
        TextEncoding.UTF8 -> Charsets.UTF_8
        TextEncoding.UTF16LE -> Charsets.UTF_16LE
        TextEncoding.UTF16BE -> Charsets.UTF_16BE
        TextEncoding.CP949 -> koreanCharset()
    }
}

/**
 * Code page 949 (Unified Hangul Code, a superset of EUC-KR) under the first of the names
 * JVMs and Android know it by that this runtime supports: `x-windows-949`, `MS949`,
 * `windows-949`, and failing those plain `EUC-KR`.
 */
fun koreanCharset(): Charset = KOREAN

private val KOREAN: Charset by lazy {
    val name = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR").firstOrNull(Charset::isSupported)
        ?: throw UnsupportedCharsetException("EUC-KR")
    Charset.forName(name)
}

/**
 * [text] as the rows the text viewer lists: one per line (a line ends at CR LF, LF or CR), and
 * a line longer than [maxLine] characters split into rows of at most [maxLine], the rows after
 * the first marked as its continuation. A row never ends inside a surrogate pair.
 */
fun splitDisplayLines(text: String, maxLine: Int = 2000): List<DisplayLine> {
    require(maxLine > 0) { "maxLine $maxLine" }
    val rows = ArrayList<DisplayLine>()
    text.lineSequence().forEachIndexed { index, line ->
        val number = index + 1
        var start = 0
        do {
            var end = minOf(start + maxLine, line.length)
            if (end < line.length && end - 1 > start && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
            rows += DisplayLine(number, line.substring(start, end), continuation = start > 0)
            start = end
        } while (start < line.length)
    }
    return rows
}

/** A row of the text viewer: [text] of line [number] (from 1), or a [continuation] of that line's previous row. */
data class DisplayLine(val number: Int, val text: String, val continuation: Boolean)
