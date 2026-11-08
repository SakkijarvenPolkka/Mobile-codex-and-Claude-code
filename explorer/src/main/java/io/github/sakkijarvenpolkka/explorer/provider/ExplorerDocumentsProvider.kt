/*
 * Files — the app in the system file picker (pick spec §3): one root, with the favorites and the drives
 * in it; their files and folders by the app's own rules (what the app keeps to itself or Android withholds,
 * the picker is not shown); thumbnails of pictures and videos; the files the app opened last; and a search.
 * Other apps save, make, rename and delete through it with the app's own safeguards (pick spec §3.5).
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
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.NameCheck
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NameValidator
import io.github.sakkijarvenpolkka.explorer.fs.UniqueStyle
import io.github.sakkijarvenpolkka.explorer.fs.claimUniqueName
import io.github.sakkijarvenpolkka.explorer.fs.deleteTree
import io.github.sakkijarvenpolkka.explorer.fs.doomedTree
import io.github.sakkijarvenpolkka.explorer.fs.splitName
import io.github.sakkijarvenpolkka.explorer.platform.pictureOrVideoKind
import io.github.sakkijarvenpolkka.explorer.util.baseMimeType
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max

/**
 * The app's documents provider. A document that is not there, or that the picker may not be shown, is a
 * [FileNotFoundException] with the reason (pick spec §4), and so is a change that is refused or fails.
 */
class ExplorerDocumentsProvider : DocumentsProvider() {

    /** What the provider works with, found on its first call: the system creates it before the app. */
    private val deps: ProviderDeps by lazy { ProviderDeps.of(requireContext()) }

    /**
     * Where the provider hears that a file it opened for writing was closed (pick spec §3.5): a thread of its own,
     * started at the first write. Tests wait on its looper.
     */
    internal val closes: Handler by lazy { Handler(HandlerThread("explorer-documents").apply { start() }.looper) }

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
            .add(Root.COLUMN_AVAILABLE_BYTES, Mounted(deps).primaryFreeBytes())
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        if (documentId == ROOT_DOC_ID) {
            return MatrixCursor(projection ?: DocumentRows.DOCUMENT_COLUMNS).also(::addTop)
        }
        val mounted = Mounted(deps)
        return mounted.rowsOf(listOf(Found(documentId, mounted.entryOf(documentId))), projection)
    }

    /** The items of a folder, in no order (the picker sorts them); the top folder's are the favorites and drives. */
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val context = requireContext()
        val mounted = Mounted(deps)
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

    override fun getDocumentType(documentId: String): String = if (documentId == ROOT_DOC_ID) {
        Document.MIME_TYPE_DIR
    } else {
        DocumentRows.mimeOf(Mounted(deps).entryOf(documentId))
    }

    /**
     * Whether [documentId] is in the folder [parentDocumentId], or is it: both are documents on the same volume
     * and the one really is under the other, wherever the links on their ways lead. The top folder holds none:
     * no app is given it.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOC_ID) return false
        if (parentDocumentId.substringBefore(':') != documentId.substringBefore(':')) return false
        val ids = Mounted(deps).ids
        return try {
            deps.fs.realPath(ids.resolve(documentId)).startsWith(deps.fs.realPath(ids.resolve(parentDocumentId)))
        } catch (e: IOException) {
            // Either is no document (a FileNotFoundException), or no longer there.
            false
        }
    }

    /**
     * A file, opened as [mode] says:
     * - `r`, to be read;
     * - `w` or `wt`, to be written anew: into a temporary file next to it, which takes its place once the other app
     *   closes it without an error ([SafeSave]). Until then, and if it is closed with one, the file is as it was;
     * - `wa`, to be appended to, or `rw` and `rwt` (from empty), to be read and written anywhere: in the file itself,
     *   as no temporary file can stand for what stays of it.
     *
     * Nothing is written on a volume that takes no changes. Once a file written is closed, the picker reads its
     * folder again and the media store the file ([changed]).
     */
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val mounted = Mounted(deps)
        if (mode == "r") {
            val file = mounted.fileOf(documentId)
            return ParcelFileDescriptor.open(file.path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val inPlace = IN_PLACE_MODES[mode]
        if (inPlace == null && mode !in SAVE_MODES) throw FileNotFoundException("not supported: $mode, $documentId")
        val file = mounted.changeable(documentId)
        if (file.kind != EntryKind.FILE) throw notFound("not a file", documentId)
        return try {
            if (inPlace == null) openToSave(file.path) else openInPlace(file.path, inPlace)
        } catch (e: IOException) {
            throw notFound("not opened to write", documentId, e)
        }
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
        val entry = Mounted(deps).entryOf(documentId)
        // The files whose rows have the flag (DocumentRows.flagsFor).
        val kind = (if (entry.kind == EntryKind.FILE) pictureOrVideoKind(entry.mime) else null)
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
        val mounted = Mounted(deps)
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
        val mounted = Mounted(deps)
        val found = LinkedHashMap<String, FileEntry>()
        // A blank query would find everything.
        if (query.isNotBlank()) {
            runBlocking(deps.io) {
                withTimeoutOrNull(SEARCH_TIME_MS) {
                    for (volume in mounted.roots) {
                        if (found.size == SEARCH_LIMIT) break
                        mounted.searchUnder(volume.root, query, SEARCH_LIMIT, found)
                    }
                }
            }
        }
        return mounted.rowsOf(found.map { (id, entry) -> Found(id, entry) }, projection)
    }

    /**
     * A new file, or a folder ([Document.MIME_TYPE_DIR]), in the folder [parentDocumentId] on a volume that takes
     * changes; returns its id. It is called [displayName] if that volume's naming rules take it ([NameValidator]:
     * none of the app's own names either). A file whose name has no extension gets the one [mimeType] stands for,
     * if any (`application/octet-stream` stands for none). A name taken is numbered, `name (2).ext`: the new item
     * claims its name, so it never replaces anything.
     */
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val mounted = Mounted(deps)
        val parent = mounted.changeable(parentDocumentId)
        if (parent.kind != EntryKind.DIR) throw notFound("not a folder", parentDocumentId)
        val name = when (val check = NameValidator.validate(displayName, mounted.volumeOf(parentDocumentId).traits)) {
            is NameCheck.Ok -> check.name
            is NameCheck.Bad -> throw notFound("not a name here (${check.problem})", parentDocumentId)
        }
        val folder = mimeType == Document.MIME_TYPE_DIR
        // A folder's name is numbered as a whole, a file's before its extension.
        val (stem, ext) = if (folder) {
            name to ""
        } else {
            splitName(name).let { (stem, ext) -> stem to ext.ifEmpty { extensionFor(mimeType, name) } }
        }
        val made = try {
            claimUniqueName(parent.path, stem, ext, UniqueStyle.KEEP_BOTH, NO_LABELS, startAt = 1) { path ->
                if (folder) deps.fs.createDirectory(path) else deps.fs.createNew(path).close()
            }.first
        } catch (e: IOException) {
            throw notFound("not made", parentDocumentId, e)
        }
        val id = mounted.ids.idOf(made)
        if (id == null) {
            // In a folder Android withholds after all (its real path says so): not left there.
            deleteMade(made)
            throw notFound("withheld", parentDocumentId)
        }
        changed(mounted, made, scan = !folder)
        return id
    }

    /**
     * Renames the file or folder [documentId] (no drive) to [displayName] as the app renames ([FileOps.rename]: the
     * naming rules of its volume, and a name that only changes case in two steps), and returns its new id; null
     * when it keeps its id (the name it has).
     *
     * The old id is left for the system to revoke: once this returns, it grants the app that renamed the document
     * what that app held of the old id on the new one, and only then revokes the old id from every app. Revoked
     * here, before that, an app that holds the document alone (what `ACTION_OPEN_DOCUMENT` grants) would get back a
     * new id it may not open.
     */
    override fun renameDocument(documentId: String, displayName: String): String? {
        val mounted = Mounted(deps)
        val item = mounted.changeableItem(documentId)
        val renamed = try {
            runBlocking(deps.io) { deps.fileOps.rename(item.path, displayName) }
        } catch (e: FsException) {
            throw notFound("not renamed", documentId, e)
        }
        val id = mounted.ids.idOf(renamed)
        if (id == null) {
            // A name Android withholds after all (its real path says so): back as it was.
            renameBack(renamed, item.name)
            throw notFound("withheld", documentId)
        }
        if (id == documentId) return null
        changed(mounted, renamed, scan = false)
        return id
    }

    /**
     * Deletes the file or folder [documentId] (no drive) into the Recycle Bin of its volume, where the app can
     * restore it from. On a volume where no bin can be had, it is deleted for good, with all it holds: no one can
     * be asked, so what was asked for is done (pick spec §3.5). It is no longer granted to any app.
     */
    override fun deleteDocument(documentId: String) {
        val mounted = Mounted(deps)
        val path = mounted.changeableItem(documentId).path
        val failure = runBlocking(deps.io) { binOrDelete(path) }
        // Even a deletion that failed partway changed the folder.
        changed(mounted, path, scan = false, revoked = documentId.takeIf { failure == null })
        if (failure != null) throw notFound("not deleted", documentId, failure)
    }

    /**
     * [target] opened to be written anew: into the temporary file of a [SafeSave], which takes the file's place once
     * it is closed without an error ([saved]).
     */
    private fun openToSave(target: Path): ParcelFileDescriptor {
        val pending = SafeSave(deps.fs, deps.journal).begin(target, deps.ids())
        return try {
            ParcelFileDescriptor.open(pending.temp.toFile(), SAVE_MODE, closes) { error -> saved(pending, error) }
        } catch (e: IOException) {
            drop(pending)
            throw e
        }
    }

    /**
     * What follows the close of a file being saved ([openToSave]), on [closes]: what was written takes the file's
     * place, unless it was closed with an [error] (the other app gave up, or died) or that fails. Then it is dropped,
     * and the file is as it was. The other app was told already: a failure is logged.
     */
    private fun saved(pending: SafeSave.Pending, error: IOException?) {
        if (error != null) {
            drop(pending)
            return
        }
        try {
            pending.commit()
        } catch (e: IOException) {
            Log.w(TAG, "Could not save ${pending.target}; it is as it was", e)
            drop(pending)
            return
        }
        changed(Mounted(deps), pending.target, scan = true)
    }

    /** Drops [pending]: a temporary file that cannot be deleted is logged, and left to the journal's sweep. */
    private fun drop(pending: SafeSave.Pending) {
        try {
            pending.abort()
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete ${pending.temp}; the journal's sweep will", e)
        }
    }

    /** [target] itself, opened in [mode]: once it is closed, however the writing went, it may have changed. */
    private fun openInPlace(target: Path, mode: Int): ParcelFileDescriptor =
        ParcelFileDescriptor.open(target.toFile(), mode, closes) { changed(Mounted(deps), target, scan = true) }

    /** Renames [path] back to [name], what it was called before; a failure is logged. */
    private fun renameBack(path: Path, name: String) {
        try {
            runBlocking(deps.io) { deps.fileOps.rename(path, name) }
        } catch (e: FsException) {
            Log.w(TAG, "Could not rename $path back to $name", e)
        }
    }

    /** Deletes [made], an item just made that the picker could not be shown; a failure is logged. */
    private fun deleteMade(made: Path) {
        try {
            deps.fs.delete(made)
        } catch (e: IOException) {
            Log.w(TAG, "Could not delete $made", e)
        }
    }

    /**
     * Moves [path] into the Recycle Bin of its volume or, where none can be had, deletes it for good, all it holds
     * too; returns why not all of it went, or null.
     */
    private suspend fun binOrDelete(path: Path): IOException? {
        try {
            deps.bin.moveToBin(path)
            return null
        } catch (e: FsException) {
            if (e.error != FsError.BinUnavailable) return e
        }
        return try {
            val tree = doomedTree(deps.fs, path)
            var failed = tree.unreadable.firstOrNull()?.second
            val fatal = deleteTree(deps.fs, tree, onFailed = { _, error -> if (failed == null) failed = error })
            (fatal ?: failed)?.let(::FsException)
        } catch (e: IOException) {
            e
        }
    }

    /**
     * What follows a change the picker made at [path] (pick spec §3.5): the picker is told that the folder it is in
     * holds other items now; if [scan], the media store scans [path], where its volume is served through FUSE; and
     * no app keeps its grant of [revoked], a document deleted. (The system revokes a renamed document's old id
     * itself: [renameDocument].)
     */
    private fun changed(mounted: Mounted, path: Path, scan: Boolean, revoked: String? = null) {
        DocumentsNotifier.parentsOf(requireContext(), mounted.ids, listOf(path))
        if (scan && mounted.isFuseBacked(path)) deps.mediaScan.scan(listOf(path))
        if (revoked != null) revokeDocumentPermission(revoked)
    }

    /**
     * The extension, with its dot, that a file called [name] (which has none) gets for its type [mime]: what
     * [MimeTypeMap] gives the type, unless it gives none, the type is `application/octet-stream`, or the name would
     * be one of the app's own then.
     */
    private fun extensionFor(mime: String, name: String): String {
        val type = baseMimeType(mime)?.takeIf { it != DocumentRows.UNKNOWN_MIME } ?: return ""
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: return ""
        return ".$ext".takeUnless { HiddenNames.isInternal(name + it) }.orEmpty()
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

        private const val TAG = "ExplorerDocuments"

        /** The modes a file is written anew in ([SafeSave]). */
        private val SAVE_MODES = setOf("w", "wt")

        /** How the temporary file of a save is opened for the other app: to be written, from empty. */
        private const val SAVE_MODE = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE

        /** The modes a file is written in itself, and how it is opened for each. */
        private val IN_PLACE_MODES = mapOf(
            "wa" to (ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_APPEND),
            "rw" to ParcelFileDescriptor.MODE_READ_WRITE,
            "rwt" to (ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE),
        )

        /** The labels [claimUniqueName] takes: [UniqueStyle.KEEP_BOTH] names use none. */
        private val NO_LABELS = NameLabels(newFolder = "", newTextDocument = "", copySuffix = "")
    }
}
