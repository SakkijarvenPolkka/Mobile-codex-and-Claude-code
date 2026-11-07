/*
 * Files — the rows the system file picker reads: their columns, the rows of files and folders, and what
 * each lets the picker do (pick spec §3.3).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.database.MatrixCursor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.util.baseMimeType

/** The columns of the provider's rows, the rows of its documents, and their flags. */
object DocumentRows {

    /** The columns of the provider's root, those the picker gets when it asks for none. */
    val ROOT_COLUMNS: Array<String> = arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_ICON,
        Root.COLUMN_AVAILABLE_BYTES,
    )

    /** The columns of a document, those the picker gets when it asks for none. */
    val DOCUMENT_COLUMNS: Array<String> = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
    )

    /**
     * What the picker may do with an item of [kind] and type [mime]: in a folder, make items, and delete or
     * rename the folder; write, delete or rename a file, and show the thumbnail of a picture or a video.
     * Only what is on a [writable] volume changes. A [volumeRoot] is never deleted or renamed. A folder that
     * [blocksTree] cannot be given to an app as a whole (`ACTION_OPEN_DOCUMENT_TREE`). Links and special
     * files have no flags: the picker is not shown them.
     */
    fun flagsFor(kind: EntryKind, mime: String?, writable: Boolean, volumeRoot: Boolean, blocksTree: Boolean): Int =
        when (kind) {
            EntryKind.DIR -> {
                val changes = when {
                    !writable -> 0
                    volumeRoot -> Document.FLAG_DIR_SUPPORTS_CREATE
                    else -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or
                        Document.FLAG_SUPPORTS_RENAME
                }
                changes or (if (blocksTree) Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE else 0)
            }
            EntryKind.FILE -> {
                val changes = if (writable) {
                    Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
                } else {
                    0
                }
                changes or (if (isPictureOrVideo(mime)) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
            }
            EntryKind.SYMLINK, EntryKind.OTHER -> 0
        }

    /** The type the picker is told [entry] is of: a folder's, the file's own, or `application/octet-stream`. */
    fun mimeOf(entry: FileEntry): String =
        if (entry.kind == EntryKind.DIR) Document.MIME_TYPE_DIR else entry.mime ?: UNKNOWN_MIME

    /** Adds the row of the document [id], [entry] as read, called [name] (its own, or a label), to [cursor]. */
    fun addDocument(cursor: MatrixCursor, id: String, entry: FileEntry, name: String, flags: Int) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(entry))
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_LAST_MODIFIED, entry.modified)
            // A folder's size is not known without reading all it holds.
            .add(Document.COLUMN_SIZE, if (entry.kind == EntryKind.FILE) entry.size else null)
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Adds the row of the folder [id] that is on no volume, called [name], to [cursor]. */
    fun addVirtualFolder(cursor: MatrixCursor, id: String, name: String, flags: Int) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Whether [mime] is a picture's or a video's type (parameters and case ignored). */
    private fun isPictureOrVideo(mime: String?): Boolean {
        val base = baseMimeType(mime) ?: return false
        return base.startsWith("image/") || base.startsWith("video/")
    }

    /** The type of a file whose type is not known. */
    private const val UNKNOWN_MIME = "application/octet-stream"
}
