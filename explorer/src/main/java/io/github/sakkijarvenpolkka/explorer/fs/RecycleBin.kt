/*
 * Files — the Recycle Bin of each volume (spec §4.6).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** The suffix of the record of a bin item, `<id>.json`, next to its folder `<id>/`. */
private const val META_SUFFIX = ".json"

private val META_JSON = Json { ignoreUnknownKeys = true }

/**
 * The record of an item in the Recycle Bin, `<id>.json` (format version [v]): where it was deleted from
 * ([originalPath], its folder's real path and its name), when ([deletedAt], epoch milliseconds), its
 * [size] in bytes (for a folder, that of the files in it) and whether it [isDir].
 */
@Serializable
data class BinMeta(
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val v: Int = 1,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
    val isDir: Boolean,
)

/**
 * An item in the Recycle Bin of the volume at [volumeRoot]: what was deleted, [payload] (`<id>/<name>`
 * in the bin, under its own [name]), its record [meta] (null when it is missing or cannot be read, so
 * that where it came from is not known) and the [payload]'s [modified] time.
 */
data class BinItem(
    val id: String,
    val volumeRoot: Path,
    val payload: Path,
    val name: String,
    val meta: BinMeta?,
    val modified: Long,
)

/**
 * The Recycle Bin: a folder `.Recycle.Bin` at the root of each volume ([binDir]), where an item deleted
 * from the volume goes, renamed, as `<id>/<name>`, with its record `<id>.json` ([BinMeta]). [volumes]
 * says which volume a path is on, [clock] gives the time of a deletion, [ids] the id of each item.
 *
 * Whenever the process dies, an item is either where it was or whole in the bin: the record is written
 * before the item goes in, and [list] tidies what a crash leaves. One instance serves the whole app, so
 * that [list] knows which items are still going in.
 */
class RecycleBin(
    private val fs: FsBackend,
    private val volumes: VolumeLookup,
    private val clock: () -> Long,
    private val ids: () -> String,
) {
    private val writer = WriteProtocol(fs, NoJournal)

    /** The ids of the items [moveToBin] is putting in the bin, which [list] leaves alone. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** The Recycle Bin folder of the volume at [volumeRoot]. */
    fun binDir(volumeRoot: Path): Path = volumeRoot.resolve(HiddenNames.RECYCLE_BIN)

    /**
     * Moves [path] (a link itself, not what it points to) into the Recycle Bin of its volume and returns
     * it as an item there. In order: the bin and the item's folder `<id>/` are made, the record is written
     * (to a temporary file, flushed, then renamed: [WriteProtocol]), and the item is renamed to
     * `<id>/<name>`. The record holds the real path of [path]'s folder with its name, the time from
     * [clock], and the size ([binnedSize]: a folder's is that of the files in it, walked first, unless the
     * caller has walked it already and gives its [size]).
     *
     * Fails with [FsException]: [FsError.NotFound] if [path] is not there, else [FsError.BinUnavailable],
     * with nothing changed: when [path] is on no known volume, is a volume's root, the bin itself or in
     * it, or when the bin cannot be used (a read-only volume, a denied rename, ...). What was made for the
     * item is deleted again then, and when the move is cancelled.
     */
    suspend fun moveToBin(path: Path, size: Long? = null): BinItem {
        val name = path.fileName?.toString() ?: unavailable()
        val parent = path.parent ?: unavailable()
        val original = try {
            fs.realPath(parent).resolve(name)
        } catch (e: IOException) {
            throw notBinned(e)
        }
        val volume = volumes.volumeOf(original) ?: unavailable()
        if (original == volume.root || inBin(volume.root, original)) unavailable()
        val entry = try {
            Listing.entry(fs, path, NO_MIME)
        } catch (e: IOException) {
            throw notBinned(e)
        }
        val meta = BinMeta(
            originalPath = original.toString(),
            deletedAt = clock(),
            size = size ?: binnedSize(fs, entry),
            isDir = entry.kind == EntryKind.DIR,
        )
        val bin = binDir(volume.root)
        val id = ids()
        val folder = bin.resolve(id)
        val record = bin.resolve(id + META_SUFFIX)
        val payload = folder.resolve(name)
        inFlight += id
        try {
            var madeFolder = false
            var wroteRecord = false
            try {
                if (!fs.exists(bin)) makeBin(bin)
                fs.createDirectory(folder)
                madeFolder = true
                val bytes = META_JSON.encodeToString(BinMeta.serializer(), meta).toByteArray()
                val source = ByteArrayInputStream(bytes)
                val size = bytes.size.toLong()
                writer.write(record, id, sync = true, replaceExisting = false, mtime = null, source, size) {}
                wroteRecord = true
                fs.atomicMove(path, payload)
            } catch (e: Exception) {
                // The folder first: if the item did go in after all, it is not empty, and its record stays.
                if (madeFolder && deleteQuietly(folder) && wroteRecord) deleteQuietly(record)
                if (e is IOException) throw FsException(FsError.BinUnavailable, e)
                throw e
            }
        } finally {
            inFlight -= id
        }
        return BinItem(id, volume.root, payload, name, meta, entry.modified)
    }

    /**
     * The items in the Recycle Bins of the volumes at [volumeRoots], in no particular order, after
     * tidying what a crash or a partial restore leaves behind there: a record whose item is gone is
     * deleted with the item's empty folder (once the folder, looked for again, is not there), and so are
     * an empty folder without a record and a leftover temporary record. An item whose record is missing,
     * cannot be read or is of an unknown version is listed without one ([BinItem.meta] null), and so is
     * a file or link something else put in the bin itself, as an item of its own. Items [moveToBin] is
     * still putting in are left alone. A bin that cannot be read lists nothing.
     */
    fun list(volumeRoots: List<Path>): List<BinItem> = volumeRoots.flatMap(::itemsIn)

    /**
     * Where [item] was deleted from, or null when that is not known: no record, or a recorded path that
     * is not absolute and normal (`.` or `..` in it), is not on [item]'s volume (or is its root), is in
     * its Recycle Bin or in a folder Android withholds ([isRestrictedAppDataPath]), or does not end in
     * [item]'s name.
     */
    fun originalLocation(item: BinItem): Path? {
        val recorded = item.meta?.originalPath ?: return null
        val path = try {
            Paths.get(recorded)
        } catch (e: InvalidPathException) {
            return null
        }
        val valid = path.isAbsolute &&
            path == path.normalize() &&
            path != item.volumeRoot &&
            volumes.volumeOf(path)?.root == item.volumeRoot &&
            !inBin(item.volumeRoot, path) &&
            !isRestrictedAppDataPath(item.volumeRoot, path) &&
            path.fileName?.toString() == item.name
        return path.takeIf { valid }
    }

    /** Where [item] is restored to: where it came from, if that is known, else the root of its volume. */
    fun restoreTarget(item: BinItem): Path = originalLocation(item) ?: item.volumeRoot.resolve(item.name)

    /**
     * Forgets [item] once it has been restored, out of the bin: deletes its record, then its folder.
     * While its payload is still in the bin (it was skipped, or only part of it could be moved), nothing
     * is deleted. What cannot be deleted is left for [list] to tidy.
     */
    fun finishRestore(item: BinItem) {
        val stays = try {
            fs.exists(item.payload)
        } catch (e: IOException) {
            true
        }
        if (stays) return
        val bin = binDir(item.volumeRoot)
        deleteQuietly(bin.resolve(item.id + META_SUFFIX))
        deleteQuietly(bin.resolve(item.id))
    }

    /**
     * Deletes [item] for good: its content first (its folder, each item before the folder it is in, as
     * [deleteTree] does), its record last, so that what is left stays listed with it. Fails with
     * [FsException] for the first item that could not be read or deleted, after trying all the others,
     * or at once for a failure that ends everything ([isFatal]). A record that cannot be deleted is left
     * for [list] to tidy.
     */
    suspend fun purge(item: BinItem) {
        val bin = binDir(item.volumeRoot)
        val tree = try {
            doomedTree(fs, bin.resolve(item.id))
        } catch (e: NoSuchFileException) {
            null
        } catch (e: IOException) {
            throw FsException(e.toFsError(), e)
        }
        if (tree != null) {
            var first = tree.unreadable.firstOrNull()?.second
            val fatal = deleteTree(fs, tree, onFailed = { _, error -> if (first == null) first = error })
            (fatal ?: first)?.let { throw FsException(it) }
        }
        deleteQuietly(bin.resolve(item.id + META_SUFFIX))
    }

    /** The size of the items in the Recycle Bins of the volumes at [volumeRoots] ([list]), in bytes. */
    fun totalSize(volumeRoots: List<Path>): Long = list(volumeRoots).sumOf(::sizeOf)

    /** [item]'s size: as recorded, or, without a record, its payload's own (0 for a folder). */
    internal fun sizeOf(item: BinItem): Long = item.meta?.size ?: try {
        fs.attributes(item.payload).let { if (it.isRegularFile) it.size() else 0L }
    } catch (e: IOException) {
        0L
    }

    /** The items in the bin of the volume at [volumeRoot], tidied (see [list]). */
    private fun itemsIn(volumeRoot: Path): List<BinItem> {
        val bin = binDir(volumeRoot)
        val children = try {
            fs.list(bin)
        } catch (e: IOException) {
            return emptyList()
        }
        // An item seen here that is not in flight below is wholly in or wholly out: moveToBin marks it
        // before making anything for it, and unmarks it once done.
        val folders = HashSet<String>()
        val records = HashSet<String>()
        for (child in children) {
            val name = child.fileName.toString()
            when {
                name.endsWith(HiddenNames.PART_SUFFIX) -> if (!isRecordBeingWritten(name)) deleteQuietly(child)
                name.endsWith(META_SUFFIX) -> records += name.removeSuffix(META_SUFFIX)
                else -> folders += name
            }
        }
        val items = ArrayList<BinItem>()
        for (id in folders + records) {
            if (id in inFlight) continue
            val folder = bin.resolve(id)
            val record = bin.resolve(id + META_SUFFIX)
            // Looked for again, not taken from the listing: reading a folder is not atomic, so it may have
            // missed an item's folder that was made before its record was written.
            val attributes = try {
                fs.attributes(folder)
            } catch (e: NoSuchFileException) {
                null
            } catch (e: IOException) {
                continue
            }
            if (attributes != null && !attributes.isDirectory) {
                // Not put here by this app, so where it came from is not known.
                items += BinItem(id, volumeRoot, folder, id, meta = null, attributes.lastModifiedTime().toMillis())
                continue
            }
            val content = if (attributes == null) {
                emptyList()
            } else {
                try {
                    fs.list(folder)
                } catch (e: IOException) {
                    continue // Not readable: not ours to tidy.
                }
            }
            // Only one thing is ever put in an item's folder.
            val payload = content.minByOrNull { it.fileName.toString() }
            if (payload == null) {
                if (attributes != null) deleteQuietly(folder)
                if (id in records) deleteQuietly(record)
                continue
            }
            val modified = try {
                fs.attributes(payload).lastModifiedTime().toMillis()
            } catch (e: IOException) {
                continue // Gone since the folder was read.
            }
            val meta = if (id in records) readMeta(record) else null
            items += BinItem(id, volumeRoot, payload, payload.fileName.toString(), meta, modified)
        }
        return items
    }

    /** Whether the temporary file [name] is the record of an item [moveToBin] is writing now. */
    private fun isRecordBeingWritten(name: String): Boolean =
        inFlight.any { id -> tempNameFor(id + META_SUFFIX, id) == name }

    /** The record at [path], or null if it cannot be read, is not one, or is of a version this app does not know. */
    private fun readMeta(path: Path): BinMeta? = try {
        val text = fs.openRead(path).use { it.readBytes() }.decodeToString()
        META_JSON.decodeFromString(BinMeta.serializer(), text).takeIf { it.v == 1 }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null // Not JSON, or not a record (SerializationException).
    }

    private fun makeBin(bin: Path) {
        try {
            fs.createDirectory(bin)
        } catch (e: FileAlreadyExistsException) {
            // Made in the meantime.
        }
    }

    /** Whether [path] is the Recycle Bin of the volume at [volumeRoot] or in it, ignoring case. */
    private fun inBin(volumeRoot: Path, path: Path): Boolean {
        if (!path.startsWith(volumeRoot) || path == volumeRoot) return false
        return volumeRoot.relativize(path).getName(0).toString().equals(HiddenNames.RECYCLE_BIN, ignoreCase = true)
    }

    /** Deletes [path] (a file or an empty folder); whether it is gone. */
    private fun deleteQuietly(path: Path): Boolean = try {
        fs.delete(path)
        true
    } catch (e: NoSuchFileException) {
        true
    } catch (e: IOException) {
        false
    }

    private fun unavailable(): Nothing = throw FsException(FsError.BinUnavailable)

    /** [e] as [moveToBin] reports it: an item that is not there is [FsError.NotFound], else the bin is unusable. */
    private fun notBinned(e: IOException): FsException =
        FsException(if (e.toFsError() == FsError.NotFound) FsError.NotFound else FsError.BinUnavailable, e)
}

/**
 * The size [entry] is recorded with in the Recycle Bin: a file's own, that of the files in a folder (links
 * not followed, the app's internal names left out), else 0.
 */
internal suspend fun binnedSize(fs: FsBackend, entry: FileEntry): Long = when (entry.kind) {
    EntryKind.FILE -> entry.size
    EntryKind.DIR -> {
        var total = 0L
        walk(fs, entry.path) { item ->
            total += item.entry.size
            true
        }
        total
    }
    EntryKind.SYMLINK, EntryKind.OTHER -> 0L
}
