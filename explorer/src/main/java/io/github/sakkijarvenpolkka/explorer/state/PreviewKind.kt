/*
 * Files — which files the built-in viewers show, and what a viewer opened full screen is asked to show
 * (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.isTextCandidate
import io.github.sakkijarvenpolkka.explorer.util.baseMimeType

/** Which built-in viewer shows a file ([previewKindFor]). */
sealed interface PreviewKind {
    data object Image : PreviewKind
    data object Text : PreviewKind
    data object Audio : PreviewKind
    data object Video : PreviewKind
    data object Pdf : PreviewKind
}

/** The pictures [ImageViewer] shows: those `ImageDecoder` decodes (Android's type table calls BMP `image/x-ms-bmp`). */
private val IMAGE_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp", "image/x-ms-bmp",
    "image/avif",
)

/**
 * The viewer that shows [entry], a file, by its MIME type (parameters and case ignored), in this order:
 * the pictures `ImageDecoder` decodes, any video, any audio, PDF, then text ([isTextCandidate], by type
 * or extension), so that a `.ts` video (`video/mp2t`) is not read as TypeScript. Null for a folder, a
 * link, and any other file, which another app opens.
 */
fun previewKindFor(entry: FileEntry): PreviewKind? {
    if (entry.kind != EntryKind.FILE) return null
    val mime = baseMimeType(entry.mime)
    return when {
        mime in IMAGE_TYPES -> PreviewKind.Image
        mime?.startsWith("video/") == true -> PreviewKind.Video
        mime?.startsWith("audio/") == true -> PreviewKind.Audio
        mime == "application/pdf" -> PreviewKind.Pdf
        isTextCandidate(entry.name, entry.mime) -> PreviewKind.Text
        else -> null
    }
}

/** [files] when [entry] is among them, else [entry] alone: what a viewer opened on [entry] swipes through. */
internal fun siblingsOrSelf(entry: FileEntry, files: List<FileEntry>): List<FileEntry> =
    files.takeIf { list -> list.any { it.path == entry.path } } ?: listOf(entry)

/** [entry] to show full screen, with the files to swipe to from it ([siblings], [entry] among them). */
data class ViewerRequest(val entry: FileEntry, val siblings: List<FileEntry>)
