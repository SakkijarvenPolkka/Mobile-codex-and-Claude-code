/*
 * Files — which character set the entry names of a ZIP archive are in: UTF-8, or else the Korean code
 * page Windows writes them in (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException
import java.util.zip.ZipException

/** The character that stands for bytes a decoder could not read. */
private const val REPLACEMENT = '�'

/**
 * The character set to read an archive's entry names with: the first of UTF-8 and [koreanCharset] (when
 * this runtime has it) that [open] reads the names with, without throwing [IllegalArgumentException] or
 * [ZipException] (how `ZipFile` reports names its character set does not decode) and without a
 * replacement character among them ([hasReplacementChar]). [open] opens the archive with the given
 * character set and returns its entry names; an entry flagged as UTF-8 (EFS) is read as UTF-8 whatever
 * the set. If none reads them, this throws `FsException(UnsupportedEncoding)`, caused by the last
 * failure, if one was thrown; anything else [open] throws passes through.
 */
fun chooseZipCharset(open: (Charset) -> List<String>): Charset {
    var failure: Exception? = null
    for (charset in zipCharsets()) {
        val names = try {
            open(charset)
        } catch (e: IllegalArgumentException) {
            failure = e
            continue
        } catch (e: ZipException) {
            failure = e
            continue
        }
        if (!hasReplacementChar(names)) return charset
    }
    throw FsException(FsError.UnsupportedEncoding, failure)
}

/** Whether any of [names] has U+FFFD, which a decoder puts for bytes it could not read. */
fun hasReplacementChar(names: List<String>): Boolean = names.any { REPLACEMENT in it }

/**
 * The character sets [chooseZipCharset] tries, in order: UTF-8, then [koreanCharset] when this runtime has
 * it. These are what an extraction can be told to read the names with.
 */
fun zipCharsets(): List<Charset> {
    val korean = try {
        koreanCharset()
    } catch (e: UnsupportedCharsetException) {
        null
    }
    return listOfNotNull(Charsets.UTF_8, korean)
}
