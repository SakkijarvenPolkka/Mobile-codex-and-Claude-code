/*
 * Files — copying and moving with their conflicts, renaming, and creating new items (spec §4.5).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What a copy leaves free on the target's volume, besides what it writes. */
private const val SPACE_MARGIN = 64L shl 20

/** The largest file FAT32 holds: 4 GiB − 1 bytes. */
private const val FAT32_MAX_FILE = 4_294_967_295L

/** The shortest time between two progress reports (the last one excepted). */
private val PROGRESS_INTERVAL = 100.milliseconds

/** The prefix of the name a file has between the two steps of a case-only rename. */
private const val RENAME_TEMP_PREFIX = ".~ren-"

private val NO_MIME: (String) -> String? = { null }

/**
 * What [FileOps.precheck] finds before anything is written: the items the target cannot store
 * ([unstorable]: on removable media, names FAT refuses and files over 4 GiB − 1 bytes), how many bytes
 * will be copied ([copyBytes]: all a copy writes, but of a move only what goes to another volume, and
 * never the unstorable items), and how many the target's volume has left ([availableBytes]; 0 when it
 * cannot tell).
 */
data class Precheck(val unstorable: List<ItemFailure>, val copyBytes: Long, val availableBytes: Long)

/**
 * Copies and moves (spec §4.5), writing through the [WriteProtocol] with [journal], and renames and
 * creates items. [volumes] says which volume a path is on, [labels] name new items and copies, [time]
 * paces the progress reports, [clock] is the wall clock, and [ids] makes the temporary names of renames.
 * Nothing here switches threads: the caller runs it where blocking calls belong.
 */
class FileOps(
    private val fs: FsBackend,
    private val volumes: VolumeLookup,
    private val labels: NameLabels,
    private val journal: TempJournal,
    private val time: TimeSource = TimeSource.Monotonic,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String,
) {
    private val writer = WriteProtocol(fs, journal)

    /**
     * Copies or moves [spec]'s sources into its target folder; other kinds are [FsError.Unsupported].
     *
     * First the sources are walked once ([walk]: links not followed, the app's internal names left out),
     * which gives the totals. Nothing is written, and the result is [OpResult.Failed] after 0 items, if
     * the target is not a folder, if it is one of the source folders or inside one (by file key, up from
     * the target's real path, so an alias through a link is caught: [FsError.CrossesIntoSelf]), or if the
     * volume can tell it has no room for [Precheck.copyBytes] and 64 MiB more ([FsError.NoSpace]).
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
     *   gets its time when its content is done.
     *
     * A failure that ends everything ([isFatal]: no space, I/O error, read-only, volume gone) stops at
     * once with [OpResult.Failed] after the items done so far, leaving the sources where they are. Any
     * other failure is listed in [OpResult.Done.failed] and the rest goes on; a move whose copy is in place
     * but whose source could not be deleted is marked [ItemFailure.copiedButSourceKept]. A source folder a
     * move had to leave behind, because something in it was skipped or failed, is counted in
     * [OpResult.Done.leftInSource]. [OpResult.Done.created] holds where each top-level source now is (a
     * folder merged into included). Cancelling throws [CancellationException], with the file being
     * written deleted.
     *
     * [onProgress] gets the totals first, then at most one report each 100 ms, then a final one;
     * [onCommitted] gets the number of top-level sources done so far, after each.
     */
    suspend fun run(
        spec: OperationSpec,
        resolver: ConflictResolver,
        onProgress: (OpProgress) -> Unit,
        onCommitted: (Int) -> Unit = {},
    ): OpResult {
        if (spec.kind != OpKind.COPY && spec.kind != OpKind.MOVE) return OpResult.Failed(FsError.Unsupported, 0, 0)
        val plan = plan(spec)
        val total = plan.totalItems
        val realTarget = try {
            fs.realPath(plan.target).also { real ->
                if (!fs.attributes(real).isDirectory) throw FsException(FsError.NotFound)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OpResult.Failed(e.toFsError(), 0, total)
        }
        if (crossesIntoSelf(plan, realTarget)) return OpResult.Failed(FsError.CrossesIntoSelf, 0, total)
        val check = precheckOf(plan)
        val known = check.availableBytes > 0
        if (known && check.copyBytes > 0 && check.copyBytes > check.availableBytes - SPACE_MARGIN) {
            return OpResult.Failed(FsError.NoSpace, 0, total)
        }
        return Transfer(spec, plan, check.unstorable, resolver, onProgress, onCommitted).run()
    }

    /**
     * What [run] would find before writing anything for a copy or a move: what the target cannot store,
     * how much will be copied and how much room there is (see [Precheck]).
     */
    suspend fun precheck(spec: OperationSpec): Precheck = precheckOf(plan(spec))

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
            sameNameIgnoringCase(oldName, name) && sameFile(path, target) -> renameInTwoSteps(path, target)
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

    /** Walks [spec]'s sources: each one with everything under it, and the totals. */
    private suspend fun plan(spec: OperationSpec): Plan {
        require(spec.kind == OpKind.COPY || spec.kind == OpKind.MOVE) { "${spec.kind} is not a copy or a move" }
        val target = Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
        val targetVolume = volumes.volumeOf(target)
        var items = 0
        var bytes = 0L
        val sources = spec.sources.map { raw ->
            val path = Paths.get(raw)
            val steps = stepsOf(path)
            for (step in steps) {
                if (step !is Item) continue
                items++
                bytes += step.entry.size
            }
            Source(steps, copied = spec.kind == OpKind.COPY || onOtherVolume(path, targetVolume))
        }
        return Plan(target, sources, items, bytes)
    }

    /** [path] and everything under it, in walk order, with what could not be read where it was. */
    private suspend fun stepsOf(path: Path): List<Step> {
        val steps = ArrayList<Step>()
        val top = try {
            Listing.entry(fs, path, NO_MIME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(Unreadable(path, e.toFsError(), depth = 0))
        }
        steps += Item(top, depth = 0)
        if (top.kind != EntryKind.DIR) return steps
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

    private fun precheckOf(plan: Plan): Precheck {
        val removable = volumes.volumeOf(plan.target)?.takeIf { it.removable }
        val unstorable = ArrayList<ItemFailure>()
        var copyBytes = 0L
        for (source in plan.sources) {
            if (!source.copied) continue
            // Below an unstorable folder, nothing is written either.
            var below = Int.MAX_VALUE
            for (step in source.steps) {
                if (step !is Item || step.depth > below) continue
                below = Int.MAX_VALUE
                val problem = removable?.let { unstorableOn(it.traits, step.entry) }
                if (problem == null) {
                    copyBytes += step.entry.size
                } else {
                    unstorable += ItemFailure(step.entry.path.toString(), problem)
                    below = step.depth
                }
            }
        }
        val available = try {
            fs.availableBytes(plan.target)
        } catch (e: IOException) {
            0L
        }
        return Precheck(unstorable, copyBytes, available)
    }

    /** Why removable media with [traits] cannot store [entry]: a name FAT refuses, or over 4 GiB − 1. */
    private fun unstorableOn(traits: VolumeTraits, entry: FileEntry): FsError? {
        if (entry.kind == EntryKind.FILE && entry.size > FAT32_MAX_FILE) return FsError.TooLargeForVolume
        val problem = when (val check = NameValidator.validate(entry.name, traits)) {
            // Only what FAT refuses: the other rules are for typed names, and this one is already on disk.
            is NameCheck.Bad -> check.problem.takeIf(::isFatProblem)
            // The validator trims what a person types; a name on disk that ends in a space really does.
            is NameCheck.Ok -> NameProblem.TrailingDotOrSpace.takeIf { traits.fatNames && entry.name.endsWith(' ') }
        }
        return problem?.let { FsError.NameInvalid(it) }
    }

    /** Whether the target ([realTarget]) or a folder above it is one of [plan]'s source folders. */
    private fun crossesIntoSelf(plan: Plan, realTarget: Path): Boolean {
        val folders = plan.sources.mapNotNull { source ->
            (source.steps.first() as? Item)?.entry?.takeIf { it.kind == EntryKind.DIR }
        }
        if (folders.isEmpty()) return false
        val chain = generateSequence(realTarget) { it.parent }.toList()
        val keys = chain.map(::fileKeyOf)
        return folders.any { folder ->
            val key = folder.fileKey
            if (key != null) key in keys else chain.any { sameFile(it, folder.path) }
        }
    }

    private fun fileKeyOf(path: Path): Any? = try {
        fs.attributes(path).fileKey()
    } catch (e: IOException) {
        null
    }

    /** Whether [a] and [b] are the same file; not when either cannot be read (a dangling link, ...). */
    private fun sameFile(a: Path, b: Path): Boolean = try {
        fs.isSameFile(a, b)
    } catch (e: IOException) {
        false
    }

    /** One run of a copy or a move through its [plan]. */
    private inner class Transfer(
        private val spec: OperationSpec,
        private val plan: Plan,
        unstorable: List<ItemFailure>,
        private val resolver: ConflictResolver,
        private val onProgress: (OpProgress) -> Unit,
        private val onCommitted: (Int) -> Unit,
    ) {
        private val move = spec.kind == OpKind.MOVE
        private val removable = volumes.volumeOf(plan.target)?.removable == true
        private val unstorable = unstorable.associate { it.path to it.error }

        /** The answers given "to all", by kind. */
        private val answers = HashMap<ConflictKind, ConflictAction>()
        private val created = ArrayList<Path>()
        private val failed = ArrayList<ItemFailure>()

        /** The folders the current item is in, innermost last; the first is the target itself. */
        private val frames = ArrayDeque<Frame>()
        private var leftInSource = 0
        private var doneItems = 0
        private var doneBytes = 0L
        private var currentName: String? = null
        private var reported: TimeMark? = null

        suspend fun run(): OpResult {
            report(force = true)
            for ((index, source) in plan.sources.withIndex()) {
                frames.clear()
                frames.addLast(Frame(depth = -1, source = null, target = plan.target, mode = Mode.EXISTING))
                for (step in source.steps) {
                    currentCoroutineContext().ensureActive()
                    while (frames.last().depth >= step.depth) close(frames.removeLast())
                    val fatal = take(step, frames.last())
                    if (fatal != null) return OpResult.Failed(fatal, doneItems, plan.totalItems)
                }
                while (frames.size > 1) close(frames.removeLast())
                onCommitted(index + 1)
            }
            report(force = true)
            return OpResult.Done(created.toList(), failed.toList(), leftInSource)
        }

        /** Copies or moves [step] into [parent]; returns the error that ends the run, if it is one. */
        private suspend fun take(step: Step, parent: Frame): FsError? {
            val into = parent.target
            if (step is Unreadable) {
                // Under a folder passed over, it does not matter that it could not be read.
                if (into != null) fail(step.path, step.error, parent)
                return null
            }
            val entry = (step as Item).entry
            currentName = entry.name
            val startBytes = doneBytes
            var frame: Frame? = null
            val placed = if (into == null) null else try {
                val refused = unstorable[entry.path.toString()]
                when {
                    refused != null -> fail(entry.path, refused, parent)
                    entry.kind == EntryKind.FILE -> placeFile(entry, into, parent)
                    entry.kind == EntryKind.DIR -> placeDir(entry, into, parent, step.depth).also { frame = it }.placed
                    else -> fail(entry.path, FsError.Unsupported, parent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toFsError()
                if (error.isFatal) return error
                fail(entry.path, error, parent)
            }
            if (entry.kind == EntryKind.DIR) frames.addLast(frame ?: passed(step.depth))
            if (step.depth == 0 && placed != null) created.add(placed)
            doneItems++
            doneBytes = startBytes + entry.size
            report()
            return null
        }

        /** Puts the file [entry] into [dir]; returns where it now is, or null if it is not there. */
        private suspend fun placeFile(entry: FileEntry, dir: Path, parent: Frame): Path? {
            val dst = dir.resolve(entry.name)
            val renameFirst = move && parent.mode == Mode.EXISTING
            if (!fs.exists(dst)) return putFile(entry, dst, replace = false, renameFirst, parent)
            if (sameFile(entry.path, dst)) {
                if (move) return stays(parent)
                return claimFile(entry, dir, UniqueStyle.COPY_OF, 1, renameFirst = false, parent)
            }
            val existing = Listing.entry(fs, dst, NO_MIME)
            val kind = if (existing.kind == EntryKind.DIR) ConflictKind.FILE_DIR else ConflictKind.FILE_FILE
            return when (decide(kind, entry, existing)) {
                ConflictAction.REPLACE -> putFile(entry, dst, replace = true, renameFirst, parent)
                ConflictAction.KEEP_BOTH -> claimFile(entry, dir, UniqueStyle.KEEP_BOTH, 2, renameFirst, parent)
                else -> stays(parent)
            }
        }

        /** Puts the file [entry] at the free name [style] gives it in [dir], from number [startAt]. */
        private suspend fun claimFile(
            entry: FileEntry,
            dir: Path,
            style: UniqueStyle,
            startAt: Int,
            renameFirst: Boolean,
            parent: Frame,
        ): Path {
            val (stem, ext) = splitName(entry.name)
            return claimUniqueName(dir, stem, ext, style, labels, startAt) { candidate ->
                if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
                putFile(entry, candidate, replace = false, renameFirst, parent)
            }.second
        }

        /**
         * Puts the file [entry] at [dst], replacing a file there if [replace]: renamed if [renameFirst] and
         * the volume allows, else written, and then, for a move, its source deleted.
         */
        private suspend fun putFile(
            entry: FileEntry,
            dst: Path,
            replace: Boolean,
            renameFirst: Boolean,
            parent: Frame,
        ): Path {
            if (renameFirst && renamed(entry.path, dst)) return dst
            val startBytes = doneBytes
            var read = 0L
            fs.openRead(entry.path).use { input ->
                val sync = move || replace || removable
                writer.write(dst, spec.id, sync, replace, entry.modified, input, entry.size) { bytes ->
                    read += bytes
                    // The size planned, not what a growing file turned out to have.
                    doneBytes = startBytes + minOf(read, entry.size)
                    report()
                }
            }
            if (move) deleteSource(entry.path, parent)
            return dst
        }

        /** Deletes a moved file's source, now that its copy has its name; a failure is listed as such. */
        private fun deleteSource(source: Path, parent: Frame) {
            try {
                fs.delete(source)
            } catch (e: NoSuchFileException) {
                // Already gone.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += ItemFailure(source.toString(), e.toFsError(), copiedButSourceKept = true)
                parent.incomplete = true
            }
        }

        /** Puts the folder [entry] into [dir]; returns the [Frame] its content goes through. */
        private suspend fun placeDir(entry: FileEntry, dir: Path, parent: Frame, depth: Int): Frame {
            val dst = dir.resolve(entry.name)
            val renameFirst = move && parent.mode == Mode.EXISTING
            if (!fs.exists(dst)) return putDir(entry, dst, renameFirst, depth)
            if (sameFile(entry.path, dst)) {
                if (move) return passed(depth).also { stays(parent) }
                return claimDir(entry, dir, UniqueStyle.COPY_OF, 1, renameFirst = false, depth)
            }
            val existing = Listing.entry(fs, dst, NO_MIME)
            val kind = if (existing.kind == EntryKind.DIR) ConflictKind.DIR_DIR else ConflictKind.DIR_FILE
            return when (decide(kind, entry, existing)) {
                ConflictAction.MERGE -> Frame(depth, entry.path, dst, Mode.EXISTING, placed = dst)
                ConflictAction.KEEP_BOTH -> claimDir(entry, dir, UniqueStyle.KEEP_BOTH, 2, renameFirst, depth)
                else -> passed(depth).also { stays(parent) }
            }
        }

        private fun claimDir(
            entry: FileEntry,
            dir: Path,
            style: UniqueStyle,
            startAt: Int,
            renameFirst: Boolean,
            depth: Int,
        ): Frame = claimUniqueName(dir, entry.name, "", style, labels, startAt) { candidate ->
            if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
            putDir(entry, candidate, renameFirst, depth)
        }.second

        /** Renames the folder [entry] to [dst] if [renameFirst] and the volume allows, else creates [dst]. */
        private fun putDir(entry: FileEntry, dst: Path, renameFirst: Boolean, depth: Int): Frame {
            // Moved with all it holds: its content is passed over.
            if (renameFirst && renamed(entry.path, dst)) return passed(depth, placed = dst)
            fs.createDirectory(dst)
            return Frame(depth, entry.path, dst, Mode.CREATED, entry.modified, placed = dst)
        }

        /** Renames [from] to [to] in one step; false if that would cross volumes. */
        private fun renamed(from: Path, to: Path): Boolean = try {
            fs.atomicMove(from, to)
            true
        } catch (e: AtomicMoveNotSupportedException) {
            false
        }

        /**
         * Done with the folder [frame] stands for: a folder this run created gets its time, and the source
         * folder of a move is deleted, unless something in it stays (or it cannot be).
         */
        private fun close(frame: Frame) {
            val target = frame.target
            if (frame.mode == Mode.CREATED && target != null) {
                try {
                    fs.setModified(target, frame.modified)
                } catch (e: IOException) {
                    // The folder is still copied; only its time is lost.
                }
            }
            val source = frame.source ?: return
            if (!move) return
            if (frame.incomplete || !deleted(source)) {
                leftInSource++
                stays(frames.last())
            }
        }

        private fun deleted(folder: Path): Boolean = try {
            fs.delete(folder)
            true
        } catch (e: NoSuchFileException) {
            true
        } catch (e: IOException) {
            false
        }

        /** The answer to a conflict of [kind]: one given to all before, or [resolver]'s. */
        private suspend fun decide(kind: ConflictKind, source: FileEntry, existing: FileEntry): ConflictAction {
            val action = answers[kind] ?: resolver.resolve(Conflict(kind, source, existing)).let { answer ->
                if (answer.applyToAll) answers[kind] = answer.action
                answer.action
            }
            return if (action in allowedActions(kind)) action else ConflictAction.SKIP
        }

        /** Lists [path] as failed with [error]; whatever it was, it is not in the target. */
        private fun fail(path: Path, error: FsError, parent: Frame): Path? {
            failed += ItemFailure(path.toString(), error)
            return stays(parent)
        }

        /** Notes that an item of [parent]'s stays where it is, so that a move keeps [parent]'s source. */
        private fun stays(parent: Frame): Path? {
            parent.incomplete = true
            return null
        }

        private fun passed(depth: Int, placed: Path? = null) = Frame(depth, null, null, Mode.PASSED, placed = placed)

        /** Reports progress, unless the last report was less than 100 ms ago and this one is not [force]d. */
        private fun report(force: Boolean = false) {
            val last = reported
            if (!force && last != null && last.elapsedNow() < PROGRESS_INTERVAL) return
            reported = time.markNow()
            onProgress(OpProgress(plan.totalBytes, doneBytes, plan.totalItems, doneItems, currentName))
        }
    }
}

/** A copy or a move as walked before it starts: its [target], its [sources] and their totals. */
private class Plan(val target: Path, val sources: List<Source>, val totalItems: Int, val totalBytes: Long)

/** A top-level source: it and everything under it, in walk order, and whether it is [copied] (vs renamed). */
private class Source(val steps: List<Step>, val copied: Boolean)

/** One step of a [Source]: an item, or something that could not be read; [depth] 0 is the source itself. */
private sealed interface Step {
    val depth: Int
}

private class Item(val entry: FileEntry, override val depth: Int) : Step

private class Unreadable(val path: Path, val error: FsError, override val depth: Int) : Step

/** What happens to the content of a folder in a [Frame]. */
private enum class Mode {
    /** It goes into a folder that was there (the target, or one merged into); a move renames it if it can. */
    EXISTING,

    /** It goes into a folder this run created, by copying. */
    CREATED,

    /** It stays where it is: the folder was skipped, failed, or moved whole. */
    PASSED,
}

/**
 * A folder the walk is in: from [source] (null for the target and passed folders) to [target] (null
 * when its content is passed over), at [depth], with the [modified] time a created folder gets and where
 * it was [placed]. [incomplete] notes that something in it stays in the source.
 */
private class Frame(
    val depth: Int,
    val source: Path?,
    val target: Path?,
    val mode: Mode,
    val modified: Long = 0,
    val placed: Path? = null,
) {
    var incomplete = false
}
