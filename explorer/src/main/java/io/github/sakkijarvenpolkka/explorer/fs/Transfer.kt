/*
 * Files — one run of a copy, a move or a restore as [FileOps.run] plans it: each item placed in walk
 * order, with its conflicts, and what a move leaves in its source (spec §4.5, §4.6).
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The shortest time between two progress reports (the last one excepted). */
private val PROGRESS_INTERVAL = 100.milliseconds

/** No MIME types: the walks of copies and moves do not need them. */
internal val NO_MIME: (String) -> String? = { null }

/** Whether [a] and [b] are the same file; not when either cannot be read (a dangling link, ...). */
internal fun FsBackend.sameFile(a: Path, b: Path): Boolean = try {
    isSameFile(a, b)
} catch (e: IOException) {
    false
}

/**
 * One run of a copy or a move through its [plan], writing through [writer] on [fs]: what [FileOps.run]
 * does once its checks have passed. A restore runs as a move, out of the Recycle Bin, and puts back a
 * link or special file that is an item there by renaming it only ([placeByRename]). [unstorable] are
 * the items not to write, [removable] says whether the target is on removable media; [labels], [time],
 * [resolver], [onProgress] and [onCommitted] are those of [FileOps] and of the run.
 */
internal class Transfer(
    private val fs: FsBackend,
    private val writer: WriteProtocol,
    private val labels: NameLabels,
    time: TimeSource,
    private val spec: OperationSpec,
    private val plan: Plan,
    unstorable: List<ItemFailure>,
    private val removable: Boolean,
    private val resolver: ConflictResolver,
    onProgress: (OpProgress) -> Unit,
    private val onCommitted: (Int) -> Unit,
) {
    private val restore = spec.kind == OpKind.RESTORE
    private val move = spec.kind == OpKind.MOVE || restore
    private val unstorable = unstorable.associate { it.path to it.error }
    private val progress = Progress(time, onProgress, plan.totalItems, plan.totalBytes)

    /** The answers given "to all", by kind. */
    private val answers = HashMap<ConflictKind, ConflictAction>()
    private val created = ArrayList<Path>()
    private val failed = ArrayList<ItemFailure>()

    /** The folders the current item is in, innermost last; the first is the source's [Source.into]. */
    private val frames = ArrayDeque<Frame>()
    private var leftInSource = 0

    /** Whether the current source's [Source.into] is to be made, if missing, before its item goes in. */
    private var createInto = false

    suspend fun run(): OpResult {
        progress.report(force = true)
        for ((index, source) in plan.sources.withIndex()) {
            frames.clear()
            frames.addLast(Frame(depth = -1, source = null, target = source.into, mode = Mode.EXISTING))
            createInto = source.createInto
            for (step in source.steps) {
                currentCoroutineContext().ensureActive()
                while (frames.last().depth >= step.depth) close(frames.removeLast())
                val fatal = take(step, frames.last())
                if (fatal != null) return OpResult.Failed(fatal, progress.doneItems, plan.totalItems)
            }
            while (frames.size > 1) close(frames.removeLast())
            onCommitted(index + 1)
        }
        progress.report(force = true)
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
        progress.currentName = entry.name
        val startBytes = progress.doneBytes
        var frame: Frame? = null
        val placed = if (into == null) null else try {
            if (step.depth == 0 && createInto) createFolders(into)
            val refused = unstorable[entry.path.toString()]
            when {
                refused != null -> fail(entry.path, refused, parent)
                entry.kind == EntryKind.FILE -> placeFile(entry, into, parent)
                entry.kind == EntryKind.DIR -> placeDir(entry, into, parent, step.depth).also { frame = it }.placed
                restore && step.depth == 0 -> placeByRename(entry, into, parent)
                else -> fail(entry.path, FsError.Unsupported, parent)
            }
        } catch (e: IOException) {
            // Only what the file system reports fails the item: anything else, such as a bug in the
            // resolver or in onProgress, ends the run with it.
            val error = e.toFsError()
            if (error.isFatal) return error
            fail(entry.path, error, parent)
        }
        if (entry.kind == EntryKind.DIR) frames.addLast(frame ?: passed(step.depth))
        if (step.depth == 0 && placed != null) created.add(placed)
        progress.doneItems++
        progress.doneBytes = startBytes + entry.size
        progress.report()
        return null
    }

    /** Creates [dir] if it is missing, and each missing folder above it, from the top down. */
    private fun createFolders(dir: Path) {
        val missing = generateSequence(dir) { it.parent }.takeWhile { !fs.exists(it) }.toList()
        for (folder in missing.asReversed()) {
            try {
                fs.createDirectory(folder)
            } catch (e: FileAlreadyExistsException) {
                // Made in the meantime.
            }
        }
    }

    /** Puts the file [entry] into [dir]; returns where it now is, or null if it is not there. */
    private suspend fun placeFile(entry: FileEntry, dir: Path, parent: Frame): Path? {
        val dst = dir.resolve(entry.name)
        val renameFirst = move && parent.mode == Mode.EXISTING
        if (!fs.exists(dst)) return putFile(entry, dst, replace = false, renameFirst, parent)
        if (fs.sameFile(entry.path, dst)) {
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
        val startBytes = progress.doneBytes
        var read = 0L
        fs.openRead(entry.path).use { input ->
            val sync = move || replace || removable
            writer.write(dst, spec.id, sync, replace, entry.modified, input, entry.size) { bytes ->
                read += bytes
                // The size planned, not what a growing file turned out to have.
                progress.doneBytes = startBytes + minOf(read, entry.size)
                progress.report()
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
            stays(parent)
        }
    }

    /**
     * Puts the link or special file [entry], an item a restore takes out of the bin, into [dir] by renaming
     * it, never by copying: one that would have to cross volumes fails as [FsError.Unsupported]. A name
     * taken there is a conflict, as for a file; it is not checked for the same file first, which would
     * follow the link.
     */
    private suspend fun placeByRename(entry: FileEntry, dir: Path, parent: Frame): Path? {
        val dst = dir.resolve(entry.name)
        if (!fs.exists(dst)) return renameOnly(entry, dst, parent)
        val existing = Listing.entry(fs, dst, NO_MIME)
        val kind = if (existing.kind == EntryKind.DIR) ConflictKind.FILE_DIR else ConflictKind.FILE_FILE
        return when (decide(kind, entry, existing)) {
            // A rename replaces the file that has the name.
            ConflictAction.REPLACE -> renameOnly(entry, dst, parent)
            ConflictAction.KEEP_BOTH -> {
                val (stem, ext) = splitName(entry.name)
                claimUniqueName(dir, stem, ext, UniqueStyle.KEEP_BOTH, labels, 2) { candidate ->
                    if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
                    renameOnly(entry, candidate, parent)
                }.second
            }
            else -> stays(parent)
        }
    }

    /** Renames [entry] to [dst]; returns [dst], or null with [entry] failed if that would cross volumes. */
    private fun renameOnly(entry: FileEntry, dst: Path, parent: Frame): Path? =
        if (renamed(entry.path, dst)) dst else fail(entry.path, FsError.Unsupported, parent)

    /** Puts the folder [entry] into [dir]; returns the [Frame] its content goes through. */
    private suspend fun placeDir(entry: FileEntry, dir: Path, parent: Frame, depth: Int): Frame {
        val dst = dir.resolve(entry.name)
        val renameFirst = move && parent.mode == Mode.EXISTING
        if (!fs.exists(dst)) return putDir(entry, dst, renameFirst, depth)
        if (fs.sameFile(entry.path, dst)) {
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
     * folder of a move is deleted, unless something in it stays. One that cannot be deleted, with all it
     * held moved, is listed as copied with its source kept.
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
        if (!frame.incomplete) {
            val error = deleteFolder(source) ?: return
            failed += ItemFailure(source.toString(), error, copiedButSourceKept = true)
        }
        stays(frames.last())
    }

    /** Deletes the emptied source [folder]; returns why it could not be, or null once it is gone. */
    private fun deleteFolder(folder: Path): FsError? = try {
        fs.delete(folder)
        null
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IOException) {
        e.toFsError()
    }

    /** The answer to a conflict of [kind]: one given to all before, or [resolver]'s. */
    private suspend fun decide(kind: ConflictKind, source: FileEntry, existing: FileEntry): ConflictAction {
        val action = answers[kind] ?: resolver.resolve(Conflict(kind, source, existing)).let { answer ->
            if (answer.applyToAll) answers[kind] = answer.action
            answer.action
        }
        return if (action in allowedActions(kind)) action else ConflictAction.SKIP
    }

    /**
     * Lists [path] as failed with [error], with the folder it was going into ([parent]'s target); whatever
     * it was, it is not in the target.
     */
    private fun fail(path: Path, error: FsError, parent: Frame): Path? {
        failed += ItemFailure(path.toString(), error, into = parent.target?.toString())
        return stays(parent)
    }

    /**
     * Notes that an item of [parent]'s stays where it is, so that a move keeps [parent]'s source folder,
     * and counts it in [OpResult.Done.leftInSource] if it is in one.
     */
    private fun stays(parent: Frame): Path? {
        if (move && parent.source != null) leftInSource++
        parent.incomplete = true
        return null
    }

    private fun passed(depth: Int, placed: Path? = null) = Frame(depth, null, null, Mode.PASSED, placed = placed)
}

/**
 * How far a run is, out of [totalItems] and [totalBytes], and the name it is working on; [report] passes
 * it to [onProgress] at most once each 100 ms by [time], unless it is forced (the first and last reports).
 */
internal class Progress(
    private val time: TimeSource,
    private val onProgress: (OpProgress) -> Unit,
    val totalItems: Int,
    val totalBytes: Long,
) {
    var doneItems = 0
    var doneBytes = 0L
    var currentName: String? = null
    private var reported: TimeMark? = null

    /** Reports progress, unless the last report was less than 100 ms ago and this one is not [force]d. */
    fun report(force: Boolean = false) {
        val last = reported
        if (!force && last != null && last.elapsedNow() < PROGRESS_INTERVAL) return
        reported = time.markNow()
        onProgress(OpProgress(totalBytes, doneBytes, totalItems, doneItems, currentName))
    }
}

/** A copy, a move or a restore as walked before it starts: its [sources] and their totals. */
internal class Plan(val sources: List<Source>, val totalItems: Int, val totalBytes: Long)

/**
 * A top-level source: it and everything under it, in walk order, the folder it goes [into], and whether
 * it is [copied] (vs renamed). With [createInto], that folder, and any missing above it, is made first
 * if it is not there (a restore to where an item was deleted from).
 */
internal class Source(val steps: List<Step>, val copied: Boolean, val into: Path, val createInto: Boolean = false)

/** One step of a [Source]: an item, or something that could not be read; [depth] 0 is the source itself. */
internal sealed interface Step {
    val path: Path
    val depth: Int
}

internal class Item(val entry: FileEntry, override val depth: Int) : Step {
    override val path: Path get() = entry.path
}

internal class Unreadable(override val path: Path, val error: FsError, override val depth: Int) : Step

/** What happens to the content of a folder in a [Frame]. */
internal enum class Mode {
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
internal class Frame(
    val depth: Int,
    val source: Path?,
    val target: Path?,
    val mode: Mode,
    val modified: Long = 0,
    val placed: Path? = null,
) {
    var incomplete = false
}
