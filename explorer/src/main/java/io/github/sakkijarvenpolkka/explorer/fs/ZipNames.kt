/*
 * Files — the entry names of a ZIP archive as extracting it may use them: no way out of the extraction
 * folder, no control characters, each part a name a volume stores (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.text.Normalizer

/** Checks the entry names of ZIP archives before anything is extracted. */
object ZipNames {

    /** A drive at the start of a name: `C:`, `c:x`. */
    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * [raw], the name of an entry, as the path it is extracted to under the extraction folder, or null if
     * it is unsafe. The name is NFC-normalized (macOS writes Hangul decomposed) and `\` is a separator as
     * much as `/`. It is unsafe if it starts with `/` or a drive (`C:`), has a `..` part or a part longer
     * than 255 UTF-8 bytes, or holds a C0 or C1 control character (U+0000–U+001F, U+0080–U+009F: NUL
     * included). Any other character is kept, as names already on a volume may have it. Empty and `.`
     * parts are dropped; the parts left are joined with `/`, and a trailing `/` (a folder) is kept. A name
     * with no part left is "": the extraction folder itself.
     */
    fun sanitize(raw: String): String? {
        val name = Normalizer.normalize(raw, Normalizer.Form.NFC).replace('\\', '/')
        if (name.any { it.code <= 0x1F || it.code in 0x80..0x9F }) return null
        if (name.startsWith('/') || DRIVE.containsMatchIn(name)) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." || utf8Length(it) > MAX_NAME_BYTES }) return null
        val path = parts.joinToString("/")
        return if (path.isNotEmpty() && name.endsWith('/')) "$path/" else path
    }
}
