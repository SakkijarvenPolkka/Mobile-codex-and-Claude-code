// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import android.provider.DocumentsContract.Document
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The flags of the system picker's rows (pick spec §3.3). */
class DocumentRowsTest {

    private fun flags(
        kind: EntryKind,
        mime: String? = null,
        writable: Boolean = true,
        volumeRoot: Boolean = false,
        blocksTree: Boolean = false,
    ) = DocumentRows.flagsFor(kind, mime, writable, volumeRoot, blocksTree)

    @Test
    fun folderTakesNewItemsAndCanBeDeletedAndRenamed() {
        assertEquals(CREATE or DELETE or RENAME, flags(EntryKind.DIR))
    }

    @Test
    fun fileCanBeWrittenDeletedAndRenamed() {
        assertEquals(WRITE or DELETE or RENAME, flags(EntryKind.FILE, "text/plain"))
        // Of a type no one knows, too.
        assertEquals(WRITE or DELETE or RENAME, flags(EntryKind.FILE, null))
    }

    @Test
    fun picturesAndVideosHaveThumbnails() {
        for (mime in listOf("image/jpeg", "image/heic", "video/mp4", "IMAGE/PNG; charset=binary")) {
            assertEquals(mime, WRITE or DELETE or RENAME or THUMBNAIL, flags(EntryKind.FILE, mime))
        }
        // Songs, documents and folders have none.
        for (mime in listOf("audio/mpeg", "application/pdf", "text/plain", "imagex/y")) {
            assertEquals(mime, WRITE or DELETE or RENAME, flags(EntryKind.FILE, mime))
        }
        assertEquals(CREATE or DELETE or RENAME, flags(EntryKind.DIR, "image/jpeg"))
    }

    @Test
    fun volumeRootOnlyTakesNewItemsAndBlocksTree() {
        assertEquals(CREATE or BLOCKS_TREE, flags(EntryKind.DIR, volumeRoot = true, blocksTree = true))
    }

    @Test
    fun blockedFolderKeepsItsOtherFlags() {
        // The primary volume's Download: what any folder can do, but no folder access for another app.
        assertEquals(CREATE or DELETE or RENAME or BLOCKS_TREE, flags(EntryKind.DIR, blocksTree = true))
    }

    @Test
    fun readOnlyVolumeHasNoWriteFlags() {
        assertEquals(0, flags(EntryKind.DIR, writable = false))
        assertEquals(0, flags(EntryKind.FILE, "text/plain", writable = false))
        assertEquals(THUMBNAIL, flags(EntryKind.FILE, "image/jpeg", writable = false))
        assertEquals(BLOCKS_TREE, flags(EntryKind.DIR, writable = false, volumeRoot = true, blocksTree = true))
    }

    @Test
    fun virtualRootOnlyBlocksTree() {
        // The folder of the favorites and the drives: nothing is made, deleted or renamed there.
        assertEquals(BLOCKS_TREE, flags(EntryKind.DIR, writable = false, blocksTree = true))
    }

    @Test
    fun linksAndSpecialFilesHaveNone() {
        for (kind in listOf(EntryKind.SYMLINK, EntryKind.OTHER)) {
            assertEquals(kind.name, 0, flags(kind, "image/jpeg", blocksTree = true))
        }
    }

    private companion object {
        const val CREATE = Document.FLAG_DIR_SUPPORTS_CREATE
        const val DELETE = Document.FLAG_SUPPORTS_DELETE
        const val RENAME = Document.FLAG_SUPPORTS_RENAME
        const val WRITE = Document.FLAG_SUPPORTS_WRITE
        const val THUMBNAIL = Document.FLAG_SUPPORTS_THUMBNAIL
        const val BLOCKS_TREE = Document.FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE
    }
}
