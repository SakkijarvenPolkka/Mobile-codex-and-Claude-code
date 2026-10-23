/*
 * Files — MIME types of file names.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * The MIME type of a file name, from its extension (after the last dot, ignoring case; a name
 * that starts with its only dot has none): the app's own table first, so these types are the
 * same on every Android version, then [MimeTypeMap].
 */
class MimeResolver {

    fun mimeOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val extension = name.substring(dot + 1).lowercase(Locale.ROOT)
        return SUPPLEMENT[extension] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private companion object {
        /** Types the platform table lacks on older versions or gets wrong (`smi` is SAMI subtitles, not SMIL). */
        val SUPPLEMENT = mapOf(
            "smi" to "application/x-sami",
            "srt" to "application/x-subrip",
            "vtt" to "text/vtt",
            "md" to "text/markdown",
            "kt" to "text/x-kotlin",
            "apk" to "application/vnd.android.package-archive",
            "heic" to "image/heic",
            "avif" to "image/avif",
        )
    }
}
