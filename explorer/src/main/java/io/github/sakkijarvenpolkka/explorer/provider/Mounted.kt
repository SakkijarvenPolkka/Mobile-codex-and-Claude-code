/*
 * Files — the volumes as one call of the system file picker finds them, the documents on them, and the rows
 * of what the picker is shown of them (pick spec §3.2, §3.3).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.database.MatrixCursor
import android.os.Environment
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.SearchEvent
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.search
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path

/** A document a call found: its [id], [entry] as read, and the [label] it is shown by instead of its name. */
internal data class Found(val id: String, val entry: FileEntry, val label: String? = null)

/**
 * The mounted volumes of [volumes], as [DocumentIds] takes them. An id names its volume up to its first colon, so one
 * whose key has a colon has none.
 */
internal fun mountedRoots(volumes: List<Volume>): List<VolumeRoot> = volumes
    .mapNotNull { v -> v.root?.let { VolumeRoot(v.uuid, it, v.traits, v.isRemovable) } }
    .filter { ':' !in it.uuid }

/**
 * The volumes as a call finds them when it starts, for all it does: the documents on those mounted
 * ([DocumentIds]), and the rows of what the picker is shown of them. [deps] is what the provider works with.
 */
internal class Mounted(private val deps: ProviderDeps) {
    val volumes: List<Volume> = deps.volumes()

    /** The mounted volumes ([mountedRoots]). */
    val roots: List<VolumeRoot> = mountedRoots(volumes)

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

    /** The file [id] names: [FileNotFoundException] as [entryOf], and for a folder. */
    fun fileOf(id: String): FileEntry {
        val entry = entryOf(id)
        if (entry.kind != EntryKind.FILE) throw notFound("not a file", id)
        return entry
    }

    /**
     * The file or folder [id] names, to be changed: [FileNotFoundException] as [entryOf], and while its volume takes
     * no changes (mounted read-only).
     */
    fun changeable(id: String): FileEntry {
        val entry = entryOf(id)
        if (!takesChanges(volumeOf(id))) throw notFound("read-only", id)
        return entry
    }

    /** The file or folder [id] names, to be renamed or deleted: as [changeable], but no drive (pick spec §3.3). */
    fun changeableItem(id: String): FileEntry {
        val entry = changeable(id)
        if (id.substringAfter(':').isEmpty()) throw notFound("a drive", id)
        return entry
    }

    /** The volume of [id], a document of this call. */
    fun volumeOf(id: String): Volume = volumes.first { it.uuid == id.substringBefore(':') }

    /** Whether [path] is on a volume served through FUSE: the media store indexes no other. */
    fun isFuseBacked(path: Path): Boolean {
        val uuid = ids.volumeOf(path)?.uuid ?: return false
        return volumes.first { it.uuid == uuid }.fuseBacked
    }

    /**
     * The rows of [found] (documents of this call): each called by its label, else by its own name, a volume's
     * root by its drive's.
     */
    fun rowsOf(found: List<Found>, projection: Array<String>?): MatrixCursor {
        val cursor = MatrixCursor(projection ?: DocumentRows.DOCUMENT_COLUMNS)
        for ((id, entry, label) in found) {
            val volume = volumeOf(id)
            val rel = id.substringAfter(':')
            val volumeRoot = rel.isEmpty()
            // As Android itself since 11: no app is given a whole volume, nor the primary volume's downloads
            // (in any case: the volume ignores it).
            val blocksTree = volumeRoot ||
                (volume.isPrimary && rel.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true))
            val flags = DocumentRows.flagsFor(entry.kind, entry.mime, takesChanges(volume), volumeRoot, blocksTree)
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
     * stops once it holds [limit]: no hidden item nor what is in one, nothing of the app's own nor withheld
     * (`Android/obb` too), and no link.
     */
    suspend fun searchUnder(root: Path, query: String, limit: Int, found: MutableMap<String, FileEntry>) {
        val excluded = { path: Path ->
            val name = path.fileName?.toString().orEmpty()
            HiddenNames.isHidden(name) || HiddenNames.isInternal(name) ||
                isRestrictedAppDataPath(root, path, obbAllowed = false)
        }
        search(deps.fs, root, query, includeSubfolders = true, deps.mime::mimeOf, excluded)
            .transformWhile<SearchEvent, Unit> { event ->
                if (event is SearchEvent.Batch) {
                    for (hit in event.hits) {
                        if (found.size == limit) break
                        if (hit.kind == EntryKind.SYMLINK) continue
                        ids.idOf(hit.path)?.let { found[it] = hit }
                    }
                }
                found.size < limit
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

    private fun driveName(volume: Volume): String = if (volume.isPrimary) deps.internalLabel() else volume.label

    /** Whether [volume] takes changes now: not while it is mounted read-only. */
    private fun takesChanges(volume: Volume): Boolean = volume.state == VolumeState.MOUNTED
}
