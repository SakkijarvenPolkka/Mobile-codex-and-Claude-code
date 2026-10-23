/*
 * Files — the rules for file names: which are allowed, how a name splits into stem and
 * extension, and how a free name is found next to existing ones.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import io.github.sakkijarvenpolkka.explorer.util.extensionStart
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

/** The longest name a volume stores, in UTF-8 bytes. */
internal const val MAX_NAME_BYTES = 255

/** The last number [claimUniqueName] tries (`name (9999)`). */
private const val MAX_UNIQUE_NUMBER = 9999

/** The verdict of [NameValidator.validate]. */
sealed interface NameCheck {
    /** The name is acceptable; [name] is the input with its surrounding spaces trimmed. */
    data class Ok(val name: String) : NameCheck

    data class Bad(val problem: NameProblem) : NameCheck
}

/** Checks the names a person types (rename, new folder, new file) against what a volume accepts. */
object NameValidator {

    /** The characters FAT volumes reject, besides `/` and the control characters. */
    private const val FAT_CHARS = "\\:*?\"<>|"

    /**
     * Checks [raw] for a volume with [traits]. Spaces around the name are trimmed first (only
     * U+0020: a tab or line break there is a control character and is refused). Every volume
     * refuses an empty name, `/`, `.` and `..`, C0 and C1 control characters (NUL included), the
     * bidirectional controls that reorder text, and more than 255 UTF-8 bytes. FAT volumes
     * ([VolumeTraits.fatNames]) also refuse `\ : * ? " < > |` and a trailing dot.
     */
    fun validate(raw: String, traits: VolumeTraits): NameCheck {
        val name = raw.trim(' ')
        val problem = problemWith(name, traits)
        return if (problem == null) NameCheck.Ok(name) else NameCheck.Bad(problem)
    }

    private fun problemWith(name: String, traits: VolumeTraits): NameProblem? {
        if (name.isEmpty()) return NameProblem.Empty
        if (name == "." || name == "..") return NameProblem.DotOrDotDot
        if ('/' in name) return NameProblem.Slash
        if (name.any(::isControl)) return NameProblem.ControlChar
        if (name.any(::isBidiControl)) return NameProblem.BidiControl
        // FAT's own limit, 255 UTF-16 units, is never reached first: a char is as many bytes as units, or more.
        if (utf8Length(name) > MAX_NAME_BYTES) return NameProblem.TooLong
        if (traits.fatNames) {
            name.firstOrNull { it in FAT_CHARS }?.let { return NameProblem.FatChar(it) }
            // A trailing space was trimmed above, so only a dot can end the name here.
            if (name.last() == '.') return NameProblem.TrailingDotOrSpace
        }
        return null
    }

    /** C0 (U+0000–U+001F), DEL and C1 (U+0080–U+009F) controls. */
    private fun isControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

    /** The set `DisplayName` neutralizes: direction marks, embeddings, overrides and isolates. */
    private fun isBidiControl(c: Char): Boolean = when (c.code) {
        0x200E, 0x200F, 0x061C -> true
        in 0x202A..0x202E, in 0x2066..0x2069 -> true
        else -> false
    }
}

/**
 * Splits [name] into its stem and its extension with the dot (`report.pdf` → `report`, `.pdf`;
 * `a.tar.gz` → `a.tar`, `.gz`). A name with no extension (see [extensionStart]: `.bashrc`, `a.`)
 * has an empty one. The extension keeps its case, and `stem + extension` is always [name].
 */
fun splitName(name: String): Pair<String, String> {
    val dot = extensionStart(name)
    return if (dot < 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

/**
 * The localized parts of generated names: the stems of a new folder and a new text document, and
 * the suffix that marks a copy (with its leading separator, ` - Copy`).
 */
data class NameLabels(val newFolder: String, val newTextDocument: String, val copySuffix: String)

/** How [candidateName] makes a name that does not clash. */
enum class UniqueStyle {
    /** `name.ext`, `name (2).ext`, `name (3).ext` ... */
    KEEP_BOTH,

    /** `name - Copy.ext`, `name - Copy (2).ext` ... */
    COPY_OF,
}

/**
 * The [n]th ([n] ≥ 1) name to try for [stem] + [ext] in [style]. If the result would be longer
 * than 255 UTF-8 bytes, [stem] is cut, on a code point boundary, until it fits; the number, the
 * copy suffix and [ext] always stay. (An [ext] too long to leave room even for an empty stem
 * cannot be fixed here and the name stays too long.)
 */
fun candidateName(stem: String, ext: String, style: UniqueStyle, n: Int, labels: NameLabels): String {
    require(n >= 1) { "n must be at least 1, was $n" }
    val tail = buildString {
        if (style == UniqueStyle.COPY_OF) append(labels.copySuffix)
        if (n >= 2) append(" (").append(n).append(')')
        append(ext)
    }
    return stem.takeUtf8(MAX_NAME_BYTES - utf8Length(tail)) + tail
}

/**
 * Finds a free name for [stem] + [ext] in [dir] by [claim]ing it: [claim] creates the file or
 * folder at the candidate path (`CREATE_NEW`, `createDirectory`) and throws
 * [FileAlreadyExistsException] if the name is taken, in which case the next number is tried.
 * Claiming the name is what makes it unique, so two writers cannot pick the same one. Numbers
 * run from [startAt] (1 is the plain name) to 9999, after which this throws
 * `FsException(FsError.AlreadyExists)`. Any other failure of [claim] passes through.
 *
 * @return the claimed path and what [claim] returned for it
 */
fun <T> claimUniqueName(
    dir: Path,
    stem: String,
    ext: String,
    style: UniqueStyle,
    labels: NameLabels,
    startAt: Int,
    claim: (Path) -> T,
): Pair<Path, T> {
    for (n in startAt..MAX_UNIQUE_NUMBER) {
        val path = dir.resolve(candidateName(stem, ext, style, n, labels))
        try {
            return path to claim(path)
        } catch (e: FileAlreadyExistsException) {
            // Taken: try the next number.
        }
    }
    throw FsException(FsError.AlreadyExists)
}

/**
 * Whether [a] and [b] are the same name on a case-insensitive volume: equal after NFC
 * normalization and lower-casing in the root locale (the same fold [NameComparator] sorts by).
 */
fun sameNameIgnoringCase(a: String, b: String): Boolean = NameComparator.key(a) == NameComparator.key(b)

/** The UTF-8 length of [text] in bytes. A lone surrogate counts as 3, the size of its replacement character. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        bytes += utf8Length(codePoint)
        i += Character.charCount(codePoint)
    }
    return bytes
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/** The longest start of this string that is at most [maxBytes] in UTF-8, cut between code points. */
internal fun String.takeUtf8(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val codePoint = codePointAt(end)
        bytes += utf8Length(codePoint)
        if (bytes > maxBytes) break
        end += Character.charCount(codePoint)
    }
    return substring(0, end)
}
