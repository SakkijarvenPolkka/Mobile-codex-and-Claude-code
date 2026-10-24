/*
 * Files — what a file system error tells the user.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.util.UiText

/**
 * The text of [e], the one mapping for listing errors, messages and the failures of the file
 * operations: each error with a text of its own gets it, any other "Something went wrong" (never a
 * wrong reason). A name the file system refuses, or one the target volume's rules refuse before
 * anything is written, is "not allowed on the destination drive"; what is wrong with a name being
 * typed is told by the rename dialog.
 */
fun errorText(e: FsError): UiText = UiText.res(
    when (e) {
        FsError.AccessDenied -> R.string.error_access_denied
        FsError.NotFound -> R.string.error_not_found
        FsError.Restricted -> R.string.restricted_banner
        FsError.NoSpace -> R.string.err_no_space
        FsError.ReadOnly -> R.string.err_read_only
        FsError.IoError -> R.string.err_io
        FsError.VolumeRemoved -> R.string.err_volume_removed
        FsError.CrossesIntoSelf -> R.string.err_into_self
        FsError.TooLargeForVolume -> R.string.err_too_large_fat
        is FsError.NameInvalid -> R.string.err_name_volume
        FsError.Unsupported -> R.string.err_unsupported_item
        FsError.Busy -> R.string.err_busy
        FsError.AlreadyExists -> R.string.err_already_exists
        FsError.UnsafeArchive -> R.string.zip_unsafe
        FsError.Corrupt, FsError.Encrypted -> R.string.zip_corrupt
        FsError.UnsupportedEncoding -> R.string.zip_encoding
        else -> R.string.error_generic
    },
)
