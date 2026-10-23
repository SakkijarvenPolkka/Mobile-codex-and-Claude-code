/*
 * Files — MIME types of file names.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.webkit.MimeTypeMap
import io.github.sakkijarvenpolkka.explorer.util.extensionOf

/**
 * The MIME type of a file name, from its extension ([extensionOf], so ignoring case; none
 * without one): the app's own table first, so these types are the same on every Android
 * version, then [MimeTypeMap].
 */
class MimeResolver {

    fun mimeOf(name: String): String? {
        val extension = extensionOf(name)
        if (extension.isEmpty()) return null
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
            "apk" to APK_MIME,
            "heic" to "image/heic",
            "avif" to "image/avif",
        )
    }
}
