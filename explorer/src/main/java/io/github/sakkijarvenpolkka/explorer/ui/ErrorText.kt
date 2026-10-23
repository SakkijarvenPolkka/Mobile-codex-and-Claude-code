/*
 * Files — what a file system error tells the user.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.util.UiText

/**
 * The text of [e], the one mapping for listing errors and messages: each error with a text of
 * its own gets it, any other "Something went wrong" (never a wrong reason).
 */
fun errorText(e: FsError): UiText = UiText.res(
    when (e) {
        FsError.AccessDenied -> R.string.error_access_denied
        FsError.NotFound -> R.string.error_not_found
        FsError.Restricted -> R.string.restricted_banner
        FsError.VolumeRemoved -> R.string.volume_unavailable
        else -> R.string.error_generic
    },
)
