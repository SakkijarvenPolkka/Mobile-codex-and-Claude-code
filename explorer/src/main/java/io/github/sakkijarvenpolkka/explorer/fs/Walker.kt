/*
 * Files — the one walk over a folder tree that counting, copying, deleting and searching share.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path

/**
 * One item [walk] reached: its [entry], its path [relative] to the walked root (`b/c.txt`) and its
 * [depth], which is 1 for a child of the root.
 */
data class WalkItem(val entry: FileEntry, val relative: Path, val depth: Int)

/**
 * Visits everything under [root] in pre-order, the children of each folder in name order
 * ([NameComparator]): an item, then everything under it, then its next sibling. Nothing is followed
 * through a link: a link is visited as one [EntryKind.SYMLINK] item, and a [root] that is not a folder
 * itself (a link included) has nothing under it.
 *
 * An item [include] refuses (by default, the app's internal names) is neither visited nor descended
 * into. A folder is descended into when [visit] returns true for it. A folder that cannot be listed, or
 * an item whose attributes cannot be read, is reported to [onSkipped] (any exception of the backend,
 * as [toFsError]) and the walk goes on. The walk checks for cancellation before every item; a failure of
 * the caller's own [mime], [include] or [visit] ends it.
 *
 * The walk is iterative (a stack of folders, no recursion) and reads each folder once, through [fs].
 */
suspend fun walk(
    fs: FsBackend,
    root: Path,
    mime: (String) -> String? = { null },
    include: (FileEntry) -> Boolean = { !HiddenNames.isInternal(it.name) },
    onSkipped: (Path, FsError) -> Unit = { _, _ -> },
    visit: suspend (WalkItem) -> Boolean,
) {
    val isFolder = orSkip(root, onSkipped) { fs.attributes(root).isDirectory } ?: return
    if (!isFolder) return
    val stack = ArrayDeque<Level>()
    val top = orSkip(root, onSkipped) { childrenOf(fs, root) } ?: return
    stack.addLast(Level(top, root.fileSystem.getPath(""), depth = 0))
    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val level = stack.last()
        if (level.next == level.children.size) {
            stack.removeLast()
            continue
        }
        val child = level.children[level.next++]
        val attributes = orSkip(child, onSkipped) { fs.attributes(child) } ?: continue
        val entry = Listing.entry(child, attributes, mime)
        if (!include(entry)) continue
        val item = WalkItem(entry, level.relative.resolve(entry.name), level.depth + 1)
        if (!visit(item) || entry.kind != EntryKind.DIR) continue
        val children = orSkip(child, onSkipped) { childrenOf(fs, child) } ?: continue
        stack.addLast(Level(children, item.relative, item.depth))
    }
}

/** A folder on the walk's stack: its sorted [children], the index of the [next] one, and where it is. */
private class Level(val children: List<Path>, val relative: Path, val depth: Int) {
    var next = 0
}

/** The children of [dir] in name order; each name's sort key is computed once. */
private fun childrenOf(fs: FsBackend, dir: Path): List<Path> =
    fs.list(dir)
        .map { Named(it, it.fileName.toString()) }
        .sortedWith { a, b -> NameComparator.compare(a.key, a.name, b.key, b.name) }
        .map { it.path }

private class Named(val path: Path, val name: String) {
    val key = NameComparator.key(name)
}

/**
 * [block]'s result, or null after its failure is reported to [onSkipped] for [path]. Any exception
 * counts, not only an IOException: an unexpected one from a backend skips one item rather than
 * ending the walk, and the app with it.
 */
private inline fun <T : Any> orSkip(path: Path, onSkipped: (Path, FsError) -> Unit, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onSkipped(path, e.toFsError())
    null
}
