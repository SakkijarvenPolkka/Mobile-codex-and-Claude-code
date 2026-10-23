/*
 * Files — deleting a folder tree for good, one item at a time and each before the folder it is in.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * A folder tree to delete for good, as one [walk] found it: [entries], its root first and then in walk
 * order, and what could not be read in it ([unreadable]: a folder that could not be listed, an item
 * whose attributes could not be read), with why.
 */
internal class DoomedTree(val entries: List<FileEntry>, val unreadable: List<Pair<Path, FsError>>)

/**
 * [root] and everything under it, links not followed and no name left out (a folder being deleted goes
 * whole). Throws what reading [root] itself throws.
 */
internal suspend fun doomedTree(fs: FsBackend, root: Path): DoomedTree {
    val top = Listing.entry(fs, root, NO_MIME)
    val entries = arrayListOf(top)
    val unreadable = ArrayList<Pair<Path, FsError>>()
    if (top.kind == EntryKind.DIR) {
        walk(fs, root, include = { true }, onSkipped = { path, error -> unreadable += path to error }) { item ->
            entries += item.entry
            true
        }
    }
    return DoomedTree(entries, unreadable)
}

/**
 * Deletes [tree] one item at a time ([FsBackend.delete], nothing recursive), each before the folder it is
 * in: its entries last first. A link is deleted, never what it points to. A folder is kept, untried,
 * when something in it is unreadable or could not be deleted, since it could only fail as not empty.
 * An item already gone counts as deleted. [onDone] gets each entry once it is dealt with (deleted,
 * failed or kept), [onFailed] each that could not be deleted, with why. A failure that ends everything
 * ([isFatal]) stops the deletion at once and is returned; null when it went to the end.
 */
internal suspend fun deleteTree(
    fs: FsBackend,
    tree: DoomedTree,
    onFailed: (Path, FsError) -> Unit,
    onDone: (FileEntry) -> Unit = {},
): FsError? {
    val kept = HashSet<Path>()
    // Marks [path] and every folder above it as kept; a folder kept already has its own above it kept.
    fun keep(path: Path) {
        var folder: Path? = path
        while (folder != null && kept.add(folder)) folder = folder.parent
    }
    for ((path, _) in tree.unreadable) keep(path)
    for (entry in tree.entries.asReversed()) {
        currentCoroutineContext().ensureActive()
        if (entry.path !in kept) {
            try {
                fs.delete(entry.path)
            } catch (e: NoSuchFileException) {
                // Already gone.
            } catch (e: IOException) {
                val error = e.toFsError()
                if (error.isFatal) return error
                onFailed(entry.path, error)
                keep(entry.path)
            }
        }
        onDone(entry)
    }
    return null
}
