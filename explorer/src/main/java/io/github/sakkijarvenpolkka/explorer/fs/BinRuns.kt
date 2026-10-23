/*
 * Files — the file operations that delete, to the Recycle Bin or for good, restore from it and empty it
 * (spec §4.6), as [FileOps] runs them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.TimeSource

/**
 * The runs of [FileOps] for [OpKind.DELETE_TO_BIN], [OpKind.DELETE_PERMANENT], [OpKind.RESTORE] and
 * [OpKind.EMPTY_BIN], as [FileOps.run] describes them, with its [fs], [volumes], [labels], [time] and
 * [writer]. [itemSteps] walks a bin item ([BinItem.payload]): it and everything under it, in walk order,
 * the app's internal names included.
 */
internal class BinRuns(
    private val fs: FsBackend,
    private val volumes: VolumeLookup,
    private val labels: NameLabels,
    private val time: TimeSource,
    private val writer: WriteProtocol,
    private val itemSteps: suspend (Path) -> List<Step>,
) {
    /** Each source into the Recycle Bin of its volume, as [FileOps.run] describes it. */
    suspend fun deleteToBin(
        bin: RecycleBin,
        spec: OperationSpec,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val created = ArrayList<Path>()
        val failed = ArrayList<ItemFailure>()
        // Walked once, for the progress, and the records reuse the sizes.
        val sizes = spec.sources.map { sizeInBin(Paths.get(it)) }
        val progress = Progress(time, onProgress, spec.sources.size, sizes.sumOf { it ?: 0L })
        progress.report(force = true)
        for ((index, raw) in spec.sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val path = Paths.get(raw)
            progress.currentName = path.fileName?.toString()
            try {
                created.add(bin.moveToBin(path, sizes[index]).payload)
            } catch (e: FsException) {
                failed += ItemFailure(raw, e.error)
            }
            progress.doneItems++
            progress.doneBytes += sizes[index] ?: 0L
            progress.report()
            onCommitted(index + 1)
        }
        progress.report(force = true)
        return OpResult.Done(created, failed)
    }

    /** The size [path] is recorded with in the Recycle Bin ([binnedSize]); null when it cannot be read. */
    private suspend fun sizeInBin(path: Path): Long? = try {
        binnedSize(fs, Listing.entry(fs, path, NO_MIME))
    } catch (e: IOException) {
        null
    }

    /** Each source and everything under it deleted for good, as [FileOps.run] describes it. */
    suspend fun deletePermanently(
        spec: OperationSpec,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val failed = ArrayList<ItemFailure>()
        val trees = spec.sources.map { raw ->
            try {
                doomedTree(fs, Paths.get(raw)).also { tree ->
                    tree.unreadable.mapTo(failed) { (path, error) -> ItemFailure(path.toString(), error) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += ItemFailure(raw, e.toFsError())
                null
            }
        }
        val entries = trees.flatMap { it?.entries.orEmpty() }
        val progress = Progress(time, onProgress, entries.size, entries.sumOf { it.size })
        progress.report(force = true)
        for ((index, tree) in trees.withIndex()) {
            if (tree != null) {
                val onFailed = { path: Path, error: FsError -> failed += ItemFailure(path.toString(), error) }
                val fatal = deleteTree(fs, tree, onFailed) {
                    progress.currentName = it.name
                    progress.doneItems++
                    progress.doneBytes += it.size
                    progress.report()
                }
                if (fatal != null) return OpResult.Failed(fatal, progress.doneItems, progress.totalItems)
            }
            onCommitted(index + 1)
        }
        progress.report(force = true)
        return OpResult.Done(emptyList(), failed)
    }

    /** The bin items [OpOptions.binItemIds] names moved back where they came from, as [FileOps.run] describes it. */
    suspend fun restore(
        bin: RecycleBin,
        spec: OperationSpec,
        resolver: ConflictResolver,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val ids = spec.options.binItemIds
        require(ids.size == spec.sources.size) { "a restore needs the bin item id of each source" }
        val roots = spec.sources.mapNotNull { volumes.volumeOf(Paths.get(it))?.root }.distinct()
        val inBin = bin.list(roots).associateBy { it.id }
        val gone = ArrayList<ItemFailure>()
        val items = ArrayList<BinItem>()
        // The index in ids of each of items.
        val positions = ArrayList<Int>()
        for ((index, id) in ids.withIndex()) {
            val item = inBin[id]
            if (item == null) {
                gone += ItemFailure(spec.sources[index], FsError.NotFound)
            } else {
                items += item
                positions += index
            }
        }
        var totalItems = 0
        var totalBytes = 0L
        val sources = items.map { item ->
            val steps = itemSteps(item.payload)
            for (step in steps) {
                if (step !is Item) continue
                totalItems++
                totalBytes += step.entry.size
            }
            Source(steps, copied = false, into = bin.restoreTarget(item).parent, createInto = true)
        }
        // Each item done is forgotten by the bin, unless some of it stays there; the ids before the next
        // item to restore are all done, those not in the bin included.
        val committed = { done: Int ->
            bin.finishRestore(items[done - 1])
            onCommitted(if (done < items.size) positions[done] else ids.size)
        }
        // A restore is a move within the item's volume, which flushes what it copies anyway.
        val plan = Plan(sources, totalItems, totalBytes)
        val transfer = Transfer(
            fs, writer, labels, time, spec, plan, emptyList(), removable = false, resolver, onProgress, committed,
        )
        val result = transfer.run()
        if (items.isEmpty() && ids.isNotEmpty()) onCommitted(ids.size)
        return if (result is OpResult.Done) result.copy(failed = gone + result.failed) else result
    }

    /** Every item in the Recycle Bins of the sources' volumes deleted for good, as [FileOps.run] describes it. */
    suspend fun emptyBin(
        bin: RecycleBin,
        spec: OperationSpec,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val failed = ArrayList<ItemFailure>()
        val emptied = HashSet<Path>()
        // The items of each source's bin, with their sizes; a bin two sources are on is emptied once.
        val bins = spec.sources.map { raw ->
            val root = volumes.volumeOf(Paths.get(raw))?.root
            when {
                root == null -> {
                    failed += ItemFailure(raw, FsError.BinUnavailable)
                    emptyList()
                }
                !emptied.add(root) -> emptyList()
                else -> bin.list(listOf(root)).map { it to bin.sizeOf(it) }
            }
        }
        val totalBytes = bins.sumOf { items -> items.sumOf { (_, size) -> size } }
        val progress = Progress(time, onProgress, bins.sumOf { it.size }, totalBytes)
        progress.report(force = true)
        for ((index, items) in bins.withIndex()) {
            for ((item, size) in items) {
                currentCoroutineContext().ensureActive()
                progress.currentName = item.name
                try {
                    bin.purge(item)
                } catch (e: FsException) {
                    if (e.error.isFatal) return OpResult.Failed(e.error, progress.doneItems, progress.totalItems)
                    failed += ItemFailure(item.payload.toString(), e.error)
                }
                progress.doneItems++
                progress.doneBytes += size
                progress.report()
            }
            onCommitted(index + 1)
        }
        progress.report(force = true)
        return OpResult.Done(emptyList(), failed)
    }
}
