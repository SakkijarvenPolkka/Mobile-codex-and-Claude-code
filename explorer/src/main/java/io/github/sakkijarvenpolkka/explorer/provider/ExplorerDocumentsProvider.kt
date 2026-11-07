/*
 * Files — the app in the system file picker (pick spec §3): one root, with the favorites and the drives
 * in it; their files and folders by the app's own rules (what the app keeps to itself or Android withholds,
 * the picker is not shown); thumbnails of pictures and videos; the files the app opened last; and a search.
 * The picker calls on binder threads: the blocking work is done there (pick spec §3.6).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.SearchEvent
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.search
import io.github.sakkijarvenpolkka.explorer.platform.ThumbKind
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.platform.thumbKindFor
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max

/**
 * The app's documents provider. A document that is not there, or that the picker may not be shown, is a
 * [FileNotFoundException] with the reason (pick spec §4). It only reads: what would write is refused.
 */
class ExplorerDocumentsProvider : DocumentsProvider() {

    /** What the provider works with, found on its first call: the system creates it before the app. */
    private val deps: ProviderDeps by lazy { ProviderDeps.of(requireContext()) }

    override fun onCreate(): Boolean = true

    /** The root, while the app has all files access: without it, the app shows no files, nor does the picker. */
    override fun queryRoots(projection: Array<String>?): Cursor {
        val context = requireContext()
        val cursor = MatrixCursor(projection ?: DocumentRows.ROOT_COLUMNS)
        cursor.setNotificationUri(
            context.contentResolver, DocumentsContract.buildRootsUri(DocumentsNotifier.authority(context)),
        )
        if (!deps.granted()) return cursor
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            .add(Root.COLUMN_FLAGS, ROOT_FLAGS)
            .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_AVAILABLE_BYTES, Mounted().primaryFreeBytes())
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        if (documentId == ROOT_DOC_ID) {
            return MatrixCursor(projection ?: DocumentRows.DOCUMENT_COLUMNS).also(::addTop)
        }
        val mounted = Mounted()
        return mounted.rowsOf(listOf(Found(documentId, mounted.entryOf(documentId))), projection)
    }

    /** The items of a folder, in no order (the picker sorts them); the top folder's are the favorites and drives. */
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val context = requireContext()
        val mounted = Mounted()
        // Read before the cursor is made: a folder that is not there leaves none open.
        val items = if (parentDocumentId == ROOT_DOC_ID) {
            mounted.favoritesAndDrives()
        } else {
            mounted.childrenOf(parentDocumentId)
        }
        val cursor = mounted.rowsOf(items, projection)
        cursor.setNotificationUri(
            context.contentResolver,
            DocumentsContract.buildChildDocumentsUri(DocumentsNotifier.authority(context), parentDocumentId),
        )
        return cursor
    }

    override fun getDocumentType(documentId: String): String =
        if (documentId == ROOT_DOC_ID) Document.MIME_TYPE_DIR else DocumentRows.mimeOf(Mounted().entryOf(documentId))

    /**
     * Whether [documentId] is in the folder [parentDocumentId], or is it: both are documents on the same volume
     * and the one really is under the other, wherever the links on their ways lead. The top folder holds none:
     * no app is given it.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOC_ID) return false
        if (parentDocumentId.substringBefore(':') != documentId.substringBefore(':')) return false
        val ids = Mounted().ids
        return try {
            deps.fs.realPath(ids.resolve(documentId)).startsWith(deps.fs.realPath(ids.resolve(parentDocumentId)))
        } catch (e: IOException) {
            // Either is no document (a FileNotFoundException), or no longer there.
            false
        }
    }

    /** A file, opened to be read. Writing through the picker is refused. */
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("not supported: $mode, $documentId")
        val file = Mounted().entryOf(documentId)
        if (file.kind != EntryKind.FILE) throw notFound("not a file", documentId)
        return ParcelFileDescriptor.open(file.path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * The thumbnail of a picture or a video, as big as the larger side of [sizeHint], made by the app's port and
     * written as a JPEG into a pipe the picker reads (no temporary file). None of anything else: the picker
     * shows its icon then.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val entry = Mounted().entryOf(documentId)
        val kind = thumbKindFor(entry)?.takeIf { it == ThumbKind.IMAGE || it == ThumbKind.VIDEO }
            ?: throw notFound("no thumbnail", documentId)
        val bitmap = try {
            deps.thumbnails.load(entry.path, kind, max(sizeHint.x, sizeHint.y), signal ?: CancellationSignal())
        } catch (e: IOException) {
            throw notFound("no thumbnail", documentId, e)
        } ?: throw notFound("no thumbnail", documentId)
        val (source, sink) = ParcelFileDescriptor.createReliablePipe()
        deps.io.asExecutor().execute { writeJpeg(bitmap, sink) }
        return AssetFileDescriptor(source, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /** The files the app opened last that are there now, newest first. */
    override fun queryRecentDocuments(rootId: String, projection: Array<String>?): Cursor {
        requireRoot(rootId)
        val mounted = Mounted()
        val files = deps.settings.recents.value.mapNotNull { recent ->
            val id = recent.uuid + ":" + recent.rel
            mounted.entryOrNull(id)?.takeIf { it.kind == EntryKind.FILE }?.let { Found(id, it) }
        }
        return mounted.rowsOf(files, projection)
    }

    /**
     * The items whose names match [query] as the app's search matches them, on every mounted volume: at most
     * [SEARCH_LIMIT], and what is found in [SEARCH_TIME_MS] if the search takes longer.
     */
    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        requireRoot(rootId)
        val mounted = Mounted()
        val found = LinkedHashMap<String, FileEntry>()
        // A blank query would find everything.
        if (query.isNotBlank()) {
            runBlocking(deps.io) {
                withTimeoutOrNull(SEARCH_TIME_MS) {
                    for (volume in mounted.roots) {
                        if (found.size == SEARCH_LIMIT) break
                        mounted.searchUnder(volume.root, query, found)
                    }
                }
            }
        }
        return mounted.rowsOf(found.map { (id, entry) -> Found(id, entry) }, projection)
    }

    /** Adds the row of the top folder, which holds the favorites and the drives, to [cursor]. */
    private fun addTop(cursor: MatrixCursor) {
        val flags = DocumentRows.flagsFor(
            EntryKind.DIR, mime = null, writable = false, volumeRoot = false, blocksTree = true,
        )
        DocumentRows.addVirtualFolder(cursor, ROOT_DOC_ID, requireContext().getString(R.string.app_name), flags)
    }

    private fun requireRoot(rootId: String) {
        if (rootId != ROOT_ID) throw FileNotFoundException("no such root: $rootId")
    }

    /** Writes [bitmap] into the pipe [sink] as a JPEG and closes it: with an error, if none could be made. */
    private fun writeJpeg(bitmap: Bitmap, sink: ParcelFileDescriptor) {
        val jpeg = ByteArrayOutputStream()
        val made = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
        bitmap.recycle()
        try {
            if (made) {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use(jpeg::writeTo)
            } else {
                sink.closeWithError("no JPEG")
            }
        } catch (e: IOException) {
            // The picker stopped reading: no one waits for the rest.
        }
    }

    /** A document a call found: its [id], [entry] as read, and the [label] it is shown by instead of its name. */
    private data class Found(val id: String, val entry: FileEntry, val label: String? = null)

    /**
     * The volumes as a call finds them when it starts, for all it does: the documents on those mounted
     * ([DocumentIds]), and the rows of what the picker is shown of them.
     */
    private inner class Mounted {
        val volumes: List<Volume> = deps.volumes()

        /** The mounted volumes; an id names its volume up to its first colon, so one whose key has a colon has none. */
        val roots: List<VolumeRoot> = volumes
            .mapNotNull { v -> v.root?.let { VolumeRoot(v.uuid, it, v.traits, v.isRemovable) } }
            .filter { ':' !in it.uuid }

        val ids = DocumentIds(deps.fs) { roots }

        /** The file or folder [id] names; [FileNotFoundException] if it names anything else, or nothing there. */
        fun entryOf(id: String): FileEntry {
            val path = ids.resolve(id)
            val entry = try {
                Listing.entry(deps.fs, path, deps.mime::mimeOf)
            } catch (e: IOException) {
                throw notFound("not readable", id, e)
            }
            // A link that stays on its volume resolves, but the picker is shown no links.
            if (entry.kind != EntryKind.FILE && entry.kind != EntryKind.DIR) {
                throw notFound("not a file or folder", id)
            }
            return entry
        }

        fun entryOrNull(id: String): FileEntry? = try {
            entryOf(id)
        } catch (e: FileNotFoundException) {
            null
        }

        /**
         * The rows of [found] (documents of this call): each called by its label, else by its own name, a volume's
         * root by its drive's.
         */
        fun rowsOf(found: List<Found>, projection: Array<String>?): MatrixCursor {
            val cursor = MatrixCursor(projection ?: DocumentRows.DOCUMENT_COLUMNS)
            for ((id, entry, label) in found) {
                val volume = volumes.first { it.uuid == id.substringBefore(':') }
                val rel = id.substringAfter(':')
                val volumeRoot = rel.isEmpty()
                // As Android itself since 11: no app is given a whole volume, nor the primary volume's downloads
                // (in any case: the volume ignores it).
                val blocksTree = volumeRoot ||
                    (volume.isPrimary && rel.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true))
                val writable = volume.state == VolumeState.MOUNTED
                val flags = DocumentRows.flagsFor(entry.kind, entry.mime, writable, volumeRoot, blocksTree)
                val name = label ?: if (volumeRoot) driveName(volume) else entry.name
                DocumentRows.addDocument(cursor, id, entry, name, flags)
            }
            return cursor
        }

        /**
         * The favorites that are folders on a mounted volume, in the user's order and by their labels (one of a
         * volume's root is its drive), then the drives.
         */
        fun favoritesAndDrives(): List<Found> {
            val favorites = deps.settings.favorites.value.mapNotNull { favorite ->
                if (favorite.rel.isEmpty()) return@mapNotNull null
                val id = favorite.uuid + ":" + favorite.rel
                entryOrNull(id)?.takeIf { it.kind == EntryKind.DIR }?.let { Found(id, it, favorite.label) }
            }
            val drives = volumes.mapNotNull { volume ->
                val id = volume.uuid + ":"
                entryOrNull(id)?.let { Found(id, it) }
            }
            return favorites + drives
        }

        /**
         * What the folder [id] holds: hidden items too (the picker hides them as its own setting says), but no
         * link (it may lead off the volume), no special file, and nothing [DocumentIds] refuses.
         */
        fun childrenOf(id: String): List<Found> {
            val folder = entryOf(id)
            if (folder.kind != EntryKind.DIR) throw notFound("not a folder", id)
            val children = try {
                Listing.list(deps.fs, folder.path, ListOptions(showHidden = true), deps.mime::mimeOf)
            } catch (e: IOException) {
                throw notFound("not listed", id, e)
            }
            return children.mapNotNull { child ->
                val shown = child.kind == EntryKind.FILE || child.kind == EntryKind.DIR
                if (shown) ids.idOf(child.path)?.let { Found(it, child) } else null
            }
        }

        /**
         * Adds what [search] finds of [query] under the volume [root] to [found] (by id, in the order found), and
         * stops once it holds [SEARCH_LIMIT]: no hidden item nor what is in one, nothing of the app's own nor
         * withheld (`Android/obb` too), and no link.
         */
        suspend fun searchUnder(root: Path, query: String, found: MutableMap<String, FileEntry>) {
            val excluded = { path: Path ->
                val name = path.fileName?.toString().orEmpty()
                HiddenNames.isHidden(name) || HiddenNames.isInternal(name) ||
                    isRestrictedAppDataPath(root, path, obbAllowed = false)
            }
            search(deps.fs, root, query, includeSubfolders = true, deps.mime::mimeOf, excluded)
                .transformWhile<SearchEvent, Unit> { event ->
                    if (event is SearchEvent.Batch) {
                        for (hit in event.hits) {
                            if (found.size == SEARCH_LIMIT) break
                            if (hit.kind == EntryKind.SYMLINK) continue
                            ids.idOf(hit.path)?.let { found[it] = hit }
                        }
                    }
                    found.size < SEARCH_LIMIT
                }
                .collect()
        }

        /** The bytes free for apps on the primary volume, or null when it is not mounted or cannot tell. */
        fun primaryFreeBytes(): Long? {
            val root = volumes.firstOrNull { it.isPrimary }?.root ?: return null
            return try {
                deps.fs.availableBytes(root)
            } catch (e: IOException) {
                null
            }
        }

        private fun driveName(volume: Volume): String = if (volume.isPrimary) deps.internalLabel else volume.label
    }

    companion object {
        /** The id of the provider's one root. */
        const val ROOT_ID = "explorer"

        /** The root takes new items, has recent files and a search, tells whose child a document is, and is local. */
        private const val ROOT_FLAGS = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_RECENTS or
            Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY

        /** The most items a search returns. */
        private const val SEARCH_LIMIT = 200

        /** How long a search may take, in milliseconds: what it found by then is returned. */
        private const val SEARCH_TIME_MS = 5_000L

        private const val JPEG_QUALITY = 85

        /** The picker's failure for [id]: [why], and what the file system said ([cause]), if anything. */
        private fun notFound(why: String, id: String, cause: IOException? = null) =
            FileNotFoundException("$why: $id").apply { if (cause != null) initCause(cause) }
    }
}
