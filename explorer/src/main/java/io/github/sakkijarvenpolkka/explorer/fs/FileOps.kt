/*
 * Files — copying and moving with their conflicts, renaming, creating new items (spec §4.5), and deleting,
 * to the Recycle Bin or for good, restoring from it and emptying it (§4.6).
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

/** What a copy leaves free on the target's volume, besides what it writes. */
private const val SPACE_MARGIN = 64L shl 20

/** The prefix of the name a file has between the two steps of a case-only rename. */
private const val RENAME_TEMP_PREFIX = ".~ren-"

/**
 * What [FileOps.precheck] finds before anything is written: the items the target cannot store
 * ([unstorable]: names its FAT naming rules refuse ([VolumeTraits.fatNames]), and files larger than its
 * file system holds, when that is known ([VolumeTraits.maxFileSize])), how many bytes will be copied
 * ([copyBytes]: all a copy writes, but of a move only what goes to another volume, and never the
 * unstorable items), and how many the target's volume has left ([availableBytes]; 0 when it cannot tell).
 */
data class Precheck(val unstorable: List<ItemFailure>, val copyBytes: Long, val availableBytes: Long)

/**
 * Copies and moves (spec §4.5), writing through the [WriteProtocol] with [journal], renames and creates
 * items, and deletes them, to the Recycle Bin [bin] or for good (§4.6). [volumes] says which volume a
 * path is on, [labels] name new items and copies, [time] paces the progress reports, [clock] is the
 * wall clock, and [ids] makes the temporary names of renames. Nothing here switches threads: the caller
 * runs it where blocking calls belong.
 */
class FileOps(
    private val fs: FsBackend,
    private val volumes: VolumeLookup,
    private val labels: NameLabels,
    private val journal: TempJournal,
    private val time: TimeSource = TimeSource.Monotonic,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String,
    private val bin: RecycleBin? = null,
) {
    private val writer = WriteProtocol(fs, journal)

    /**
     * Runs [spec], as its kind says:
     * - [COPY][OpKind.COPY] and [MOVE][OpKind.MOVE] put its sources into its target folder (see below);
     * - [DELETE_TO_BIN][OpKind.DELETE_TO_BIN] moves each source into the Recycle Bin of its volume
     *   ([RecycleBin.moveToBin]), without asking. One that cannot go there stays where it is, listed in
     *   [OpResult.Done.failed] as [FsError.BinUnavailable] (or [FsError.NotFound]): it is never deleted
     *   instead, that is for the caller to ask. [OpResult.Done.created] holds where each one is in the bin;
     * - [DELETE_PERMANENT][OpKind.DELETE_PERMANENT] deletes each source and everything under it for good,
     *   from one [walk] (no name left out), one item at a time and each before the folder it is in. A link
     *   is deleted, never what it points to. What cannot be read or deleted is listed in
     *   [OpResult.Done.failed], and the folders it is in are kept, untried;
     * - [RESTORE][OpKind.RESTORE] moves the bin items [OpOptions.binItemIds] names back where they came
     *   from ([RecycleBin.restoreTarget]), making that folder again if it is gone, as a move does (a name
     *   taken there is a conflict for [resolver]). The items are looked for in the bins of the volumes the
     *   sources are on: the sources are the items' [BinItem.payload]s, one for each id in the same order
     *   (else this throws [IllegalArgumentException]). An item no longer in the bin fails as
     *   [FsError.NotFound], under its source; one that is not wholly moved out (skipped, or partly merged)
     *   stays in the bin, with its record;
     * - [EMPTY_BIN][OpKind.EMPTY_BIN] deletes every item in the Recycle Bins of the volumes its sources
     *   are on (the bin folders, [RecycleBin.binDir]) for good ([RecycleBin.purge]);
     * - the ZIP kinds are [FsError.Unsupported], after 0 of 0 items.
     *
     * The kinds that use the Recycle Bin are [FsError.BinUnavailable], after 0 of 0 items, without [bin].
     * A failure that ends everything ([isFatal]: no space, I/O error, read-only, volume gone) stops at once
     * with [OpResult.Failed] after the items done so far. Cancelling throws [CancellationException]; anything
     * else [resolver], [onProgress] or [onCommitted] throws ends the run with it. [onProgress] gets the
     * totals first, then at most one report each 100 ms, then a final one; [onCommitted] gets the number
     * of top-level sources done so far (of a restore: of its ids, in their order), after each.
     *
     * A copy or a move writes nothing, and is [OpResult.Failed] after 0 of 0 items, if the target is not
     * a folder, or if it is one of the source folders or inside one (by file key, up from the target's
     * real path, so an alias through a link is caught: [FsError.CrossesIntoSelf]): both are checked on the
     * sources themselves, before anything under them is walked. Then the sources are walked once ([walk]:
     * links not followed, the app's internal names left out), which gives the totals; if the volume can
     * tell it has no room for [Precheck.copyBytes] and 64 MiB more, the result is [FsError.NoSpace] after
     * 0 of them, with nothing written.
     *
     * Then each item, in walk order:
     * - links, devices, pipes and sockets fail as [FsError.Unsupported], and [Precheck.unstorable] items
     *   with their reason;
     * - an item pasted onto itself (the target already holds the very same file) is copied under a
     *   ` - Copy` name without asking, or, moved, left as it is: the same file is never "replaced";
     * - a name the target already has is a [Conflict] for [resolver]; an answer its kind does not offer
     *   ([allowedActions]) is a skip, and one given "to all" answers the later conflicts of that kind.
     *   Merging a folder never deletes anything in the target;
     * - a move tries a rename first ([FsBackend.atomicMove]) for every item that goes to a new name or
     *   replaces a file, and copies what is on another volume, deleting each source file once its copy
     *   has its name and each source folder (last, and only if empty) once its content is done;
     * - files are written through [WriteProtocol]: never a cut file under its final name, the modified
     *   time kept, flushed to the device when moving, replacing or writing to removable media. A folder
     *   gets its time when its content is done. A file larger than the target's file system holds, when
     *   that was not known before, fails as [FsError.TooLargeForVolume], with its part deleted.
     *
     * A fatal failure leaves the sources where they are, and the file being written is deleted when the
     * run stops or is cancelled. Any other failure the file system reports is listed in
     * [OpResult.Done.failed] and the rest goes on; a move whose copy is in place but whose source (a file,
     * or a folder whose content all went) could not be deleted is marked [ItemFailure.copiedButSourceKept].
     * A source folder a move had to leave behind, because something in it was skipped or failed, stays,
     * and the items left in such folders are counted in [OpResult.Done.leftInSource].
     * [OpResult.Done.created] holds where each top-level source now is (a folder merged into included).
     */
    suspend fun run(
        spec: OperationSpec,
        resolver: ConflictResolver,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit = {},
    ): OpResult = when (spec.kind) {
        OpKind.COPY, OpKind.MOVE -> transfer(spec, resolver, onProgress, onCommitted)
        OpKind.DELETE_PERMANENT -> deletePermanently(spec, onProgress, onCommitted)
        OpKind.DELETE_TO_BIN -> withBin { deleteToBin(it, spec, onProgress, onCommitted) }
        OpKind.RESTORE -> withBin { restore(it, spec, resolver, onProgress, onCommitted) }
        OpKind.EMPTY_BIN -> withBin { emptyBin(it, spec, onProgress, onCommitted) }
        OpKind.COMPRESS, OpKind.EXTRACT -> OpResult.Failed(FsError.Unsupported, 0, 0)
    }

    /** What [block] makes of the Recycle Bin, or [FsError.BinUnavailable] after 0 of 0 items without one. */
    private inline fun withBin(block: (RecycleBin) -> OpResult): OpResult =
        bin?.let(block) ?: OpResult.Failed(FsError.BinUnavailable, 0, 0)

    /**
     * What [run] would find before writing anything for a copy or a move: what the target cannot store,
     * how much will be copied and how much room there is (see [Precheck]).
     */
    suspend fun precheck(spec: OperationSpec): Precheck {
        val target = targetOf(spec)
        return precheckOf(target, plan(spec, target, topsOf(spec)))
    }

    /**
     * Renames [path] to [newName] in its folder, right away, and returns the new path. The name is checked
     * ([NameValidator], with the rules of [path]'s volume) and trimmed. A name that differs only in case
     * or normalization, for the same file, goes through a temporary name in the same folder (so that a
     * case-insensitive volume does not take it for the name it already has), and is undone if the second
     * step fails; any other name that is taken is [FsError.AlreadyExists]. Fails with [FsException].
     */
    suspend fun rename(path: Path, newName: String): Path = asFsFailure {
        currentCoroutineContext().ensureActive()
        val traits = volumes.volumeOf(path)?.traits ?: VolumeTraits()
        val name = when (val check = NameValidator.validate(newName, traits)) {
            is NameCheck.Ok -> check.name
            is NameCheck.Bad -> throw FsException(FsError.NameInvalid(check.problem))
        }
        val oldName = requireNotNull(path.fileName) { "$path has no name" }.toString()
        val target = path.resolveSibling(name)
        when {
            name == oldName -> Unit
            !fs.exists(target) -> fs.atomicMove(path, target)
            sameNameIgnoringCase(oldName, name) && fs.sameFile(path, target) -> renameInTwoSteps(path, target)
            else -> throw FsException(FsError.AlreadyExists)
        }
        target
    }

    /** Creates a folder named [NameLabels.newFolder] in [parent] (numbered if taken) and returns it. */
    suspend fun createFolder(parent: Path): Path = asFsFailure {
        currentCoroutineContext().ensureActive()
        claimUniqueName(parent, labels.newFolder, "", UniqueStyle.KEEP_BOTH, labels, 1) {
            fs.createDirectory(it)
        }.first
    }

    /** Creates an empty `.txt` file named [NameLabels.newTextDocument] in [parent] and returns it. */
    suspend fun createTextFile(parent: Path): Path = asFsFailure {
        currentCoroutineContext().ensureActive()
        claimUniqueName(parent, labels.newTextDocument, ".txt", UniqueStyle.KEEP_BOTH, labels, 1) {
            fs.createNew(it).close()
        }.first
    }

    /** A copy or a move, as [run] describes it. */
    private suspend fun transfer(
        spec: OperationSpec,
        resolver: ConflictResolver,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val target = targetOf(spec)
        val tops = topsOf(spec)
        refusal(target, tops)?.let { return OpResult.Failed(it, 0, 0) }
        val plan = plan(spec, target, tops)
        val check = precheckOf(target, plan)
        val known = check.availableBytes > 0
        if (known && check.copyBytes > 0 && check.copyBytes > check.availableBytes - SPACE_MARGIN) {
            return OpResult.Failed(FsError.NoSpace, 0, plan.totalItems)
        }
        val removable = volumes.volumeOf(target)?.removable == true
        val transfer = Transfer(
            fs, writer, labels, time, spec, plan, check.unstorable, removable, resolver, onProgress, onCommitted,
        )
        return transfer.run()
    }

    /** Each source into the Recycle Bin of its volume, as [run] describes it. */
    private suspend fun deleteToBin(
        bin: RecycleBin,
        spec: OperationSpec,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit,
    ): OpResult {
        val created = ArrayList<Path>()
        val failed = ArrayList<ItemFailure>()
        val progress = Progress(time, onProgress, spec.sources.size, totalBytes = 0)
        progress.report(force = true)
        for ((index, raw) in spec.sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            val path = Paths.get(raw)
            progress.currentName = path.fileName?.toString()
            try {
                created.add(bin.moveToBin(path).payload)
            } catch (e: FsException) {
                failed += ItemFailure(raw, e.error)
            }
            progress.doneItems++
            progress.report()
            onCommitted(index + 1)
        }
        progress.report(force = true)
        return OpResult.Done(created, failed)
    }

    /** Each source and everything under it deleted for good, as [run] describes it. */
    private suspend fun deletePermanently(
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

    /** The bin items [OpOptions.binItemIds] names moved back where they came from, as [run] describes it. */
    private suspend fun restore(
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
            val steps = stepsFrom(topOf(item.payload))
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

    /** Every item in the Recycle Bins of the sources' volumes deleted for good, as [run] describes it. */
    private suspend fun emptyBin(
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

    /** `a.txt` → `.~ren-<id>` → `A.txt`; if the second step fails, the first is undone. */
    private fun renameInTwoSteps(from: Path, to: Path) {
        val temp = from.resolveSibling(RENAME_TEMP_PREFIX + ids())
        fs.atomicMove(from, temp)
        try {
            fs.atomicMove(temp, to)
        } catch (e: Exception) {
            try {
                fs.atomicMove(temp, from)
            } catch (undo: IOException) {
                e.addSuppressed(undo)
            }
            throw e
        }
    }

    /** [block], with an IOException it throws as an [FsException] of its [toFsError]. */
    private inline fun <T> asFsFailure(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: IOException) {
        throw FsException(e.toFsError(), e)
    }

    /** The target folder of [spec], a copy or a move. */
    private fun targetOf(spec: OperationSpec): Path {
        require(spec.kind == OpKind.COPY || spec.kind == OpKind.MOVE) { "${spec.kind} is not a copy or a move" }
        return Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
    }

    /** Each of [spec]'s sources itself (see [topOf]). */
    private fun topsOf(spec: OperationSpec): List<Step> = spec.sources.map { topOf(Paths.get(it)) }

    /** The source [path] itself (links not followed), not what is under it: its [Item], or [Unreadable]. */
    private fun topOf(path: Path): Step = try {
        Item(Listing.entry(fs, path, NO_MIME), depth = 0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Unreadable(path, e.toFsError(), depth = 0)
    }

    /**
     * Why [target] cannot take the sources [tops]: it is not a folder (or not there), or it is one of the
     * source folders or inside one ([FsError.CrossesIntoSelf]); null if it can.
     */
    private fun refusal(target: Path, tops: List<Step>): FsError? {
        val realTarget = try {
            fs.realPath(target).also { real ->
                if (!fs.attributes(real).isDirectory) throw FsException(FsError.NotFound)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.toFsError()
        }
        return FsError.CrossesIntoSelf.takeIf { crossesIntoSelf(tops, realTarget) }
    }

    /** Walks the sources [tops] of [spec] into [target]: each one with everything under it, and the totals. */
    private suspend fun plan(spec: OperationSpec, target: Path, tops: List<Step>): Plan {
        val targetVolume = volumes.volumeOf(target)
        var items = 0
        var bytes = 0L
        val sources = tops.map { top ->
            val steps = stepsFrom(top)
            for (step in steps) {
                if (step !is Item) continue
                items++
                bytes += step.entry.size
            }
            Source(steps, copied = spec.kind == OpKind.COPY || onOtherVolume(top.path, targetVolume), into = target)
        }
        return Plan(sources, items, bytes)
    }

    /** The source [top] and everything under it, in walk order, with what could not be read where it was. */
    private suspend fun stepsFrom(top: Step): List<Step> {
        if (top !is Item || top.entry.kind != EntryKind.DIR) return listOf(top)
        val path = top.path
        val steps = arrayListOf<Step>(top)
        walk(fs, path, onSkipped = { skipped, error ->
            if (!HiddenNames.isInternal(skipped.fileName.toString())) {
                // A folder that cannot be listed is reported right after itself, as if it were its content.
                val last = steps.last()
                val listed = last is Item && last.entry.path == skipped
                val depth = if (listed) last.depth + 1 else path.relativize(skipped).nameCount
                steps += Unreadable(skipped, error, depth)
            }
        }) { item ->
            steps += Item(item.entry, item.depth)
            true
        }
        return steps
    }

    private fun isFatProblem(problem: NameProblem): Boolean =
        problem is NameProblem.FatChar || problem == NameProblem.TrailingDotOrSpace

    /** Whether [path] is known to be on another volume than the target's, so that moving it copies it. */
    private fun onOtherVolume(path: Path, targetVolume: VolumeRoot?): Boolean {
        val volume = volumes.volumeOf(path)
        return volume != null && targetVolume != null && volume.root != targetVolume.root
    }

    private fun precheckOf(target: Path, plan: Plan): Precheck {
        val traits = volumes.volumeOf(target)?.traits
        val unstorable = ArrayList<ItemFailure>()
        var copyBytes = 0L
        for (source in plan.sources) {
            if (!source.copied) continue
            // Below an unstorable folder, nothing is written either.
            var below = Int.MAX_VALUE
            for (step in source.steps) {
                if (step !is Item || step.depth > below) continue
                below = Int.MAX_VALUE
                val problem = traits?.let { unstorableOn(it, step.entry) }
                if (problem == null) {
                    copyBytes += step.entry.size
                } else {
                    unstorable += ItemFailure(step.entry.path.toString(), problem)
                    below = step.depth
                }
            }
        }
        val available = try {
            fs.availableBytes(target)
        } catch (e: IOException) {
            0L
        }
        return Precheck(unstorable, copyBytes, available)
    }

    /**
     * Why a volume with [traits] cannot store [entry]: larger than its file system holds, if that is known,
     * or, with FAT naming rules, a name FAT refuses.
     */
    private fun unstorableOn(traits: VolumeTraits, entry: FileEntry): FsError? {
        val maxSize = traits.maxFileSize
        if (maxSize != null && entry.kind == EntryKind.FILE && entry.size > maxSize) return FsError.TooLargeForVolume
        if (!traits.fatNames) return null
        val problem = when (val check = NameValidator.validate(entry.name, traits)) {
            // Only what FAT refuses: the other rules are for typed names, and this one is already on disk.
            is NameCheck.Bad -> check.problem.takeIf(::isFatProblem)
            // The validator trims what a person types; a name on disk that ends in a space really does.
            is NameCheck.Ok -> NameProblem.TrailingDotOrSpace.takeIf { entry.name.endsWith(' ') }
        }
        return problem?.let { FsError.NameInvalid(it) }
    }

    /** Whether the target ([realTarget]) or a folder above it is one of the source folders among [tops]. */
    private fun crossesIntoSelf(tops: List<Step>, realTarget: Path): Boolean {
        val folders = tops.mapNotNull { top -> (top as? Item)?.entry?.takeIf { it.kind == EntryKind.DIR } }
        if (folders.isEmpty()) return false
        val chain = generateSequence(realTarget) { it.parent }.toList()
        val keys = chain.map(::fileKeyOf)
        return folders.any { folder ->
            val key = folder.fileKey
            if (key != null) key in keys else chain.any { fs.sameFile(it, folder.path) }
        }
    }

    private fun fileKeyOf(path: Path): Any? = try {
        fs.attributes(path).fileKey()
    } catch (e: IOException) {
        null
    }
}
