/*
 * Files — what the system file picker asks of a search (pick spec §3.4): the name, and what its combined Recent view
 * and its chips leave out, as it puts them in the query's arguments.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.ClipDescription
import android.os.Bundle
import android.provider.DocumentsContract
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.util.baseMimeType

/**
 * A search as the picker asks for it: the [name] to match, and what it leaves out of what matches ([keeps]). The
 * arguments it applies are [honored], for the picker to know.
 */
internal class SearchArgs private constructor(
    val name: String,
    private val excludeMedia: Boolean,
    private val mimeTypes: List<String>?,
    private val sizeOver: Long?,
    private val modifiedAfter: Long?,
    val honored: List<String>,
) {
    /**
     * Whether [entry], a match of [name], is found by this search:
     * - with media left out ([DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA], which the picker's combined Recent view
     *   asks for), no file whose type is a picture, a video, music or a document, on a volume the media store indexes
     *   ([indexed]): the store's own roots list those there; on another (a USB drive mounted as it is), it stays;
     * - with types ([DocumentsContract.QUERY_ARG_MIME_TYPES], a chip), only a file of one of them (no folder);
     * - with a size ([DocumentsContract.QUERY_ARG_FILE_SIZE_OVER]), only a file larger than it;
     * - with a time ([DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER], in milliseconds), only what was modified after.
     */
    fun keeps(entry: FileEntry, indexed: Boolean): Boolean {
        val file = entry.kind == EntryKind.FILE
        val type = DocumentRows.mimeOf(entry)
        return when {
            excludeMedia && indexed && file && isMedia(type) -> false
            mimeTypes != null && (!file || mimeTypes.none { ClipDescription.compareMimeTypes(type, it) }) -> false
            sizeOver != null && (!file || entry.size <= sizeOver) -> false
            modifiedAfter != null && entry.modified <= modifiedAfter -> false
            else -> true
        }
    }

    companion object {
        /**
         * The search [args] ask for; the name is empty when they name none. A list of types that is empty asks for
         * none.
         */
        fun of(args: Bundle): SearchArgs {
            val keys = args.keySet()
            val types = args.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES)
                ?.mapNotNull(::baseMimeType)?.takeIf { it.isNotEmpty() }
            return SearchArgs(
                name = args.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME).orEmpty(),
                excludeMedia = args.getBoolean(DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA),
                mimeTypes = types,
                sizeOver = longOf(args, DocumentsContract.QUERY_ARG_FILE_SIZE_OVER),
                modifiedAfter = longOf(args, DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER),
                // As the platform's own providers tell it: each argument given (DocumentsContract keeps the list).
                honored = HONORED.filter { it in keys },
            )
        }

        private fun longOf(args: Bundle, key: String): Long? = if (args.containsKey(key)) args.getLong(key) else null

        /**
         * Whether [type] is that of a picture, a video, music or a document, as the media store's
         * `MediaFile.isImageMimeType`, `isVideoMimeType`, `isAudioMimeType` and `isDocumentMimeType` decide (Android
         * 15), for the types the app's own resolver gives.
         */
        private fun isMedia(type: String): Boolean {
            val lower = type.lowercase()
            return lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/") ||
                lower.startsWith("text/") || lower in DOCUMENT_TYPES
        }

        /** The arguments a search applies, in the platform's order. */
        private val HONORED = listOf(
            DocumentsContract.QUERY_ARG_EXCLUDE_MEDIA,
            DocumentsContract.QUERY_ARG_DISPLAY_NAME,
            DocumentsContract.QUERY_ARG_FILE_SIZE_OVER,
            DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER,
            DocumentsContract.QUERY_ARG_MIME_TYPES,
        )

        /**
         * The types besides `text/` the media store counts as documents (`MediaFile.isDocumentMimeType`, Android 15).
         */
        private val DOCUMENT_TYPES = setOf(
            "application/epub+zip",
            "application/msword",
            "application/pdf",
            "application/rtf",
            "application/vnd.ms-excel",
            "application/vnd.ms-excel.addin.macroenabled.12",
            "application/vnd.ms-excel.sheet.binary.macroenabled.12",
            "application/vnd.ms-excel.sheet.macroenabled.12",
            "application/vnd.ms-excel.template.macroenabled.12",
            "application/vnd.ms-powerpoint",
            "application/vnd.ms-powerpoint.addin.macroenabled.12",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "application/vnd.ms-powerpoint.slideshow.macroenabled.12",
            "application/vnd.ms-powerpoint.template.macroenabled.12",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/vnd.ms-word.template.macroenabled.12",
            "application/vnd.oasis.opendocument.chart",
            "application/vnd.oasis.opendocument.database",
            "application/vnd.oasis.opendocument.formula",
            "application/vnd.oasis.opendocument.graphics",
            "application/vnd.oasis.opendocument.graphics-template",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.presentation-template",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.spreadsheet-template",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.text-master",
            "application/vnd.oasis.opendocument.text-template",
            "application/vnd.oasis.opendocument.text-web",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.stardivision.calc",
            "application/vnd.stardivision.chart",
            "application/vnd.stardivision.draw",
            "application/vnd.stardivision.impress",
            "application/vnd.stardivision.impress-packed",
            "application/vnd.stardivision.mail",
            "application/vnd.stardivision.math",
            "application/vnd.stardivision.writer",
            "application/vnd.stardivision.writer-global",
            "application/vnd.sun.xml.calc",
            "application/vnd.sun.xml.calc.template",
            "application/vnd.sun.xml.draw",
            "application/vnd.sun.xml.draw.template",
            "application/vnd.sun.xml.impress",
            "application/vnd.sun.xml.impress.template",
            "application/vnd.sun.xml.math",
            "application/vnd.sun.xml.writer",
            "application/vnd.sun.xml.writer.global",
            "application/vnd.sun.xml.writer.template",
            "application/x-mspublisher",
        )
    }
}
