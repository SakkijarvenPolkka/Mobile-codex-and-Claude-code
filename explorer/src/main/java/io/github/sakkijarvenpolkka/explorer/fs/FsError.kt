/*
 * Files — the errors of the file system layer and how NIO and errno failures
 * map onto them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/** Why a file system call failed. Turned into text only in `ui/`. */
sealed interface FsError {
    data object AccessDenied : FsError
    data object NotFound : FsError
    data object AlreadyExists : FsError

    /** ENOSPC or EDQUOT. */
    data object NoSpace : FsError
    data class NameInvalid(val problem: NameProblem) : FsError

    /** EFBIG: the file does not fit the volume's format (4 GiB on FAT32). */
    data object TooLargeForVolume : FsError

    /** A folder copied or moved into itself or one of its subfolders. */
    data object CrossesIntoSelf : FsError

    /** EROFS. */
    data object ReadOnly : FsError

    /** EIO: the medium failed. */
    data object IoError : FsError
    data object VolumeRemoved : FsError

    /** A path Android withholds from file managers (`Android/data`, `Android/obb`). */
    data object Restricted : FsError
    data object Corrupt : FsError
    data object Encrypted : FsError
    data object Unsupported : FsError

    /** An archive with an entry that escapes the extraction folder. */
    data object UnsafeArchive : FsError

    /** No character set decodes the archive's entry names. */
    data object UnsupportedEncoding : FsError

    /** The volume's Recycle Bin cannot be used. */
    data object BinUnavailable : FsError
    data object Busy : FsError
    data class Unknown(val message: String?) : FsError
}

/** What is wrong with a file name. */
sealed interface NameProblem {
    data object Empty : NameProblem
    data object Slash : NameProblem
    data object DotOrDotDot : NameProblem
    data object ControlChar : NameProblem
    data object BidiControl : NameProblem

    /** More than 255 bytes (UTF-8) or, on FAT volumes, 255 UTF-16 units. */
    data object TooLong : NameProblem

    /** A character FAT volumes reject (`\ : * ? " < > |`). */
    data class FatChar(val char: Char) : NameProblem
    data object TrailingDotOrSpace : NameProblem

    /** The volume refused the name (EINVAL or ENAMETOOLONG). */
    data object NotAllowedByVolume : NameProblem
}

/** Errors that stop a whole operation rather than one item of it. */
val FsError.isFatal: Boolean
    get() = when (this) {
        FsError.NoSpace, FsError.IoError, FsError.ReadOnly, FsError.VolumeRemoved -> true
        else -> false
    }

/** An [IOException] that carries an [FsError] across code that only speaks exceptions. */
class FsException(val error: FsError, cause: Throwable? = null) : IOException(error.toString(), cause)

/**
 * The [FsError] for a failure: by exception type, then by the [FileSystemException.reason]
 * or the message, where an errno name (Android: `open failed: ENOENT (...)`) wins over a
 * strerror phrase. Anything else, EXDEV included, is [FsError.Unknown].
 */
fun Throwable.toFsError(): FsError = when (this) {
    is FsException -> error
    is AccessDeniedException -> FsError.AccessDenied
    is NoSuchFileException -> FsError.NotFound
    is FileAlreadyExistsException -> FsError.AlreadyExists
    // A FileSystemException's message also holds the paths; only its reason is read.
    else -> errorIn(if (this is FileSystemException) reason else message) ?: FsError.Unknown(message)
}

private val NOT_ALLOWED_BY_VOLUME = FsError.NameInvalid(NameProblem.NotAllowedByVolume)

private val ERRNO_NAME = Regex("""\bE[A-Z0-9]{2,}\b""")

private val ERRNO_ERRORS = mapOf(
    "ENOSPC" to FsError.NoSpace,
    "EDQUOT" to FsError.NoSpace,
    "EROFS" to FsError.ReadOnly,
    "EFBIG" to FsError.TooLargeForVolume,
    "EINVAL" to NOT_ALLOWED_BY_VOLUME,
    "ENAMETOOLONG" to NOT_ALLOWED_BY_VOLUME,
    "EIO" to FsError.IoError,
    "EACCES" to FsError.AccessDenied,
    "ENOENT" to FsError.NotFound,
)

/** strerror texts of glibc and bionic, matched ignoring case. */
private val STRERROR_ERRORS = listOf(
    "No space left on device" to FsError.NoSpace,
    "quota exceeded" to FsError.NoSpace,
    "Read-only file system" to FsError.ReadOnly,
    "File too large" to FsError.TooLargeForVolume,
    "Invalid argument" to NOT_ALLOWED_BY_VOLUME,
    "File name too long" to NOT_ALLOWED_BY_VOLUME,
    "Input/output error" to FsError.IoError,
    "I/O error" to FsError.IoError,
    "Permission denied" to FsError.AccessDenied,
    "No such file or directory" to FsError.NotFound,
)

/**
 * The error [text] names, or null. Paths come before the errno in Android's messages, so
 * the last errno-like word decides; one we do not map (EXDEV, EPERM, ...) leaves only the
 * strerror phrases to look at.
 */
private fun errorIn(text: String?): FsError? {
    if (text == null) return null
    val errno = ERRNO_NAME.findAll(text).lastOrNull()?.value
    if (errno != null) ERRNO_ERRORS[errno]?.let { return it }
    return STRERROR_ERRORS.firstOrNull { (phrase, _) -> text.contains(phrase, ignoreCase = true) }?.second
}
