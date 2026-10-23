/*
 * Files — running the file operations one at a time, in the order they come (spec §5): the queue, the
 * conflicts handed to the UI, cancelling, the journal, and what finished or was interrupted.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ops

import io.github.sakkijarvenpolkka.explorer.fs.Conflict
import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.ConflictResolver
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpProgress
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.fs.allowedActions
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** How many finished operations [RunnerState.finished] keeps. */
private const val MAX_FINISHED = 20

/** The kinds whose sources, once done, are no longer where they were. */
private val GONE_ONCE_DONE = setOf(OpKind.MOVE, OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.RESTORE)

/**
 * A conflict the running operation [opId] waits on: [conflict], and the answers its kind offers
 * ([allowed]). The UI shows it from the runner's state, so it outlives the activity, and [answer]s it;
 * the first answer counts, and one given after the operation is cancelled does nothing.
 */
class PendingConflict(val opId: String, val conflict: Conflict, val allowed: List<ConflictAction>) {
    private val reply = CompletableDeferred<Resolution>()

    fun answer(r: Resolution) {
        reply.complete(r)
    }

    internal suspend fun await(): Resolution = reply.await()

    /** Once its operation no longer waits: a later answer does nothing. */
    internal fun cancel() {
        reply.cancel()
    }
}

/** The operation running now, [spec], and how far it is ([progress]; null before its first report). */
data class RunningOp(val spec: OperationSpec, val progress: OpProgress?)

/** An operation that ended, [spec], and how ([result]). */
data class FinishedOp(val spec: OperationSpec, val result: OpResult)

/**
 * What the runner is doing: the operations waiting their turn ([queue], first to run first), the one
 * [running], the conflict it waits on ([pending]), the last 20 that ended ([finished], the latest last)
 * until they are dismissed, and those a crash interrupted ([interrupted]) until they are run again or
 * dismissed.
 */
data class RunnerState(
    val queue: List<OperationSpec> = emptyList(),
    val running: RunningOp? = null,
    val pending: PendingConflict? = null,
    val finished: List<FinishedOp> = emptyList(),
    val interrupted: List<InterruptedOp> = emptyList(),
)

/**
 * What the runner tells: that it has work once it had none ([onBusy], from the call that brought the
 * work), that it has none left ([onIdle]), and each operation that ended ([onFinished]), always in that
 * order. They are called while the runner holds its lock, so they must be quick and must not wait for
 * another thread that uses the runner.
 */
interface RunnerHost {
    fun onBusy()

    fun onIdle()

    fun onFinished(op: FinishedOp)
}

/**
 * Runs the file operations of the whole app with [ops], one at a time in the order they are submitted,
 * on [dispatcher] within [scope] (spec §5). Each is journaled ([journal]): begun when it starts, noted
 * as each top-level source is done, ended when it ends; before it starts, what crashes left behind is
 * swept. A conflict waits in [state] as a [PendingConflict] until the UI answers it.
 *
 * Nothing an operation does stops the runner: an exception [ops] throws fails that operation (with
 * [FsError.Unknown], unless the file system reported it), and the next one runs. [host] hears when there
 * is work and when it ends.
 */
class OperationRunner(
    private val scope: CoroutineScope,
    private val ops: FileOps,
    private val journal: OperationJournal,
    private val dispatcher: CoroutineDispatcher,
    @Volatile var host: RunnerHost?,
) {
    private val mutableState = MutableStateFlow(RunnerState())
    val state: StateFlow<RunnerState> = mutableState.asStateFlow()

    /** The operations submitted, in order; the queue in [state] says which are still to run. */
    private val incoming = Channel<OperationSpec>(Channel.UNLIMITED)

    /** Guards [busy] and [current], and keeps what the host hears in order. */
    private val lock = Any()

    /** Whether the host was last told there is work. */
    private var busy = false

    /** The operation running now, with how it is to stop, if it is to. */
    private var current: Current? = null

    private class Current(val spec: OperationSpec, val job: Job) {
        /** Set once it is to stop: the error it then fails with, or null to cancel it. */
        var stop: Stop? = null
    }

    private class Stop(val error: FsError?)

    init {
        scope.launch(dispatcher) {
            for (spec in incoming) run(spec)
        }
    }

    /** Puts [spec] at the end of the queue. */
    fun submit(spec: OperationSpec) {
        synchronized(lock) {
            mutableState.update { it.copy(queue = it.queue + spec) }
            if (!busy) {
                busy = true
                host?.onBusy()
            }
        }
        incoming.trySend(spec)
    }

    /**
     * Cancels the operation [opId]: one still queued ends at once, the running one once it has cleaned up
     * (the file being written is deleted, and a conflict it waits on goes). Either is [OpResult.Cancelled].
     */
    fun cancel(opId: String) = stop(Stop(null)) { it.id == opId }

    /**
     * Stops every operation with a source or a target under [root] (a volume going away): each fails with
     * [error], the queued ones after 0 of 0 items, the running one after the items it has done.
     */
    fun cancelTouching(root: Path, error: FsError = FsError.VolumeRemoved) = stop(Stop(error)) { spec ->
        pathsOf(spec).any { it.startsWith(root) }
    }

    /**
     * Runs [interrupted] again, under a new id, on what it had not done: the top-level sources it had done
     * are left out, and so, for a kind whose sources are no longer where they were once done (a move, a
     * delete, a restore), are those known to be gone ([FileOps.isGone]: a move or a delete that got to them
     * before it could note it). Any other source is run again, so that one out of reach (its volume away,
     * its folder unreadable) fails as such. A compression runs whole again: its archive is written at
     * once. Its journal is discarded once the new operation is submitted. If nothing is left to run, none
     * is: it stays in [RunnerState.interrupted], with its journal, until it is dismissed.
     */
    fun retry(interrupted: InterruptedOp) {
        forgetInterrupted(interrupted.spec.id)
        scope.launch(dispatcher) {
            val again = remainderOf(interrupted)
            if (again == null) {
                mutableState.update { state ->
                    state.copy(interrupted = (state.interrupted + interrupted).sortedBy { it.spec.id })
                }
                return@launch
            }
            journal.discard(interrupted.spec.id)
            submit(again)
        }
    }

    /**
     * Runs again, under new ids, what [op] could not do ([OpResult.Done.failed]), except what was copied
     * but could not be deleted from its source ([ItemFailure.copiedButSourceKept]), which would be copied
     * twice:
     * - a copy or a move takes each item to the folder it was going into ([ItemFailure.into], under the
     *   name that folder got there), one operation for each folder;
     * - a delete takes the items that failed, and only them: not what was put in their folders since
     *   (the folders kept because of them stay);
     * - a restore, emptying the bin, and the ZIP kinds run again on the top-level sources the failures
     *   are in: a restore goes by bin item, emptying by bin, and an archive is read or written whole.
     */
    fun retryFailed(op: FinishedOp) {
        val failed = (op.result as? OpResult.Done)?.failed.orEmpty().filterNot { it.copiedButSourceKept }
        if (failed.isEmpty()) return
        val spec = op.spec
        val again = when (spec.kind) {
            OpKind.COPY, OpKind.MOVE -> {
                val target = requireNotNull(spec.target) { "${spec.kind} needs a target" }
                failed.groupBy({ it.into ?: target }, { it.path })
                    .map { (folder, paths) -> spec.copy(id = newId(), sources = paths, target = folder) }
            }
            OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT ->
                listOf(spec.copy(id = newId(), sources = failed.map { it.path }))
            OpKind.RESTORE, OpKind.EMPTY_BIN, OpKind.COMPRESS, OpKind.EXTRACT -> {
                val paths = failed.map { Paths.get(it.path) }
                val indices = spec.sources.indices.filter { i ->
                    val top = Paths.get(spec.sources[i])
                    paths.any { it.startsWith(top) }
                }
                listOf(spec.narrowedTo(indices))
            }
        }
        again.filter { it.sources.isNotEmpty() }.forEach(::submit)
    }

    /** Forgets the finished operation [opId]. */
    fun dismiss(opId: String) {
        mutableState.update { state -> state.copy(finished = state.finished.filterNot { it.spec.id == opId }) }
    }

    /** Forgets the interrupted operation [opId], and its journal. */
    fun dismissInterrupted(opId: String) {
        forgetInterrupted(opId)
        scope.launch(dispatcher) { journal.discard(opId) }
    }

    /** The paths the running operation works on: its sources and its target. */
    fun busyPaths(): Set<Path> = state.value.running?.spec?.let { pathsOf(it).toSet() } ?: emptySet()

    /**
     * Sweeps the journal ([OperationJournal.sweep]: what a crash left behind is cleaned up) and puts the
     * operations it interrupted in [RunnerState.interrupted]. The app does this when it starts.
     */
    fun loadInterrupted() {
        scope.launch(dispatcher) {
            val found = journal.sweep()
            mutableState.update { it.copy(interrupted = found) }
        }
    }

    /** Runs [spec], unless it was cancelled while queued, and records how it ended. */
    private suspend fun run(spec: OperationSpec) {
        var result: OpResult? = null
        val ran = coroutineScope {
            val job = launch(start = CoroutineStart.LAZY) { result = execute(spec) }
            if (!claim(spec, job)) {
                job.cancel()
                return@coroutineScope false
            }
            journal.sweep()
            journal.begin(spec)
            // One cancelled before it starts does not run at all.
            job.start()
            true
        }
        if (!ran) return
        journal.end(spec.id)
        val stop = synchronized(lock) { current?.stop }
        val progress = state.value.running?.progress
        val stopped = stop?.error?.let { OpResult.Failed(it, progress.done, progress.total) } ?: OpResult.Cancelled
        finish(FinishedOp(spec, result ?: stopped))
    }

    /** Takes [spec] out of the queue as the one running, in [job]; false if it is no longer queued. */
    private fun claim(spec: OperationSpec, job: Job): Boolean = synchronized(lock) {
        if (spec !in state.value.queue) return false
        mutableState.update { it.copy(queue = it.queue - spec, running = RunningOp(spec, null)) }
        current = Current(spec, job)
        true
    }

    /** [spec] run to its end; a failure that is not the file system's, or a cancellation, ends it. */
    private suspend fun execute(spec: OperationSpec): OpResult = try {
        ops.run(
            spec,
            resolverFor(spec),
            onProgress = { progress -> report(spec, progress) },
            onCommitted = { count -> journal.committed(spec.id, count) },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A bug rather than the file system: this operation fails, and the runner goes on.
        val error = if (e is IOException) e.toFsError() else FsError.Unknown(e.message)
        val progress = state.value.running?.progress
        OpResult.Failed(error, progress.done, progress.total)
    }

    /** Asks the UI, through [state], about the conflicts of [spec]. */
    private fun resolverFor(spec: OperationSpec) = ConflictResolver { conflict ->
        val pending = PendingConflict(spec.id, conflict, allowedActions(conflict.kind))
        mutableState.update { it.copy(pending = pending) }
        try {
            pending.await()
        } finally {
            pending.cancel()
            mutableState.update { if (it.pending === pending) it.copy(pending = null) else it }
        }
    }

    private fun report(spec: OperationSpec, progress: OpProgress) {
        mutableState.update { if (it.running?.spec == spec) it.copy(running = RunningOp(spec, progress)) else it }
    }

    /** Stops the running operation and drops the queued ones that are [which], as [why] says. */
    private fun stop(why: Stop, which: (OperationSpec) -> Boolean) {
        synchronized(lock) {
            val running = current
            if (running != null && running.stop == null && which(running.spec)) {
                running.stop = why
                running.job.cancel()
            }
            val dropped = state.value.queue.filter(which)
            if (dropped.isEmpty()) return
            mutableState.update { state -> state.copy(queue = state.queue.filterNot { it in dropped }) }
            val result = why.error?.let { OpResult.Failed(it, 0, 0) } ?: OpResult.Cancelled
            for (spec in dropped) record(FinishedOp(spec, result))
            idleIfDone()
        }
    }

    /** Records [op] as the one that was running, now ended. */
    private fun finish(op: FinishedOp) {
        synchronized(lock) {
            current = null
            mutableState.update { it.copy(running = null, pending = null) }
            record(op)
            idleIfDone()
        }
    }

    /** Adds [op] to the finished ones and tells the host. Called with [lock] held. */
    private fun record(op: FinishedOp) {
        mutableState.update { it.copy(finished = (it.finished + op).takeLast(MAX_FINISHED)) }
        host?.onFinished(op)
    }

    /** Tells the host there is no work left, if there is none. Called with [lock] held. */
    private fun idleIfDone() {
        val now = state.value
        if (busy && now.queue.isEmpty() && now.running == null) {
            busy = false
            host?.onIdle()
        }
    }

    private fun forgetInterrupted(opId: String) {
        mutableState.update { state -> state.copy(interrupted = state.interrupted.filterNot { it.spec.id == opId }) }
    }

    /** What [op] had not done, under a new id (see [retry]); null if nothing is left. */
    private fun remainderOf(op: InterruptedOp): OperationSpec? {
        val spec = op.spec
        if (spec.kind == OpKind.COMPRESS) return spec.copy(id = newId())
        val left = spec.sources.indices.drop(op.committed.coerceAtLeast(0))
        val gone = { index: Int -> spec.kind in GONE_ONCE_DONE && ops.isGone(Paths.get(spec.sources[index])) }
        return spec.narrowedTo(left.filterNot(gone)).takeIf { it.sources.isNotEmpty() }
    }

    /** [this] under a new id, on its sources at [indices] only (and, for a restore, their bin items). */
    private fun OperationSpec.narrowedTo(indices: List<Int>): OperationSpec {
        val ids = options.binItemIds
        val items = if (ids.isEmpty()) options else options.copy(binItemIds = indices.mapNotNull { ids.getOrNull(it) })
        return copy(id = newId(), sources = indices.map { sources[it] }, options = items)
    }

    private fun pathsOf(spec: OperationSpec): List<Path> =
        (spec.sources + listOfNotNull(spec.target)).map { Paths.get(it) }

    private fun newId(): String = UUID.randomUUID().toString()

    private val OpProgress?.done: Int get() = this?.doneItems ?: 0

    private val OpProgress?.total: Int get() = this?.totalItems ?: 0
}
