/*
 * Files — the file operations as the shell asks for them and shows them: cut, copy and paste, deleting
 * (to the Recycle Bin or for good), renaming, new items, restoring and emptying the Recycle Bin, the
 * questions they ask, and what the runner's state comes to on screen.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.BinItem
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.NameComparator
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpOptions
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.splitName
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.InterruptedOp
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.ops.RunnerState
import io.github.sakkijarvenpolkka.explorer.ui.errorText
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** The kinds whose sources are no longer where they were once done: their folders change. */
private val TAKES_SOURCES = setOf(OpKind.MOVE, OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.RESTORE)

/** The kinds that change what the Recycle Bin holds (deleting for good: a bin item, from the bin). */
private val CHANGES_BIN = setOf(OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.RESTORE, OpKind.EMPTY_BIN)

/**
 * The file operations of the view model [shell]. Renaming and new items run at once with
 * [ExplorerDeps.fileOps]; the others go to the app's runner ([ExplorerDeps.runner]), whose state this
 * follows ([follow]): without them, nothing changes. Main thread only, in [scope].
 */
internal class OperationsUi(
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val clipboard: ClipboardHolder,
    private val shell: ShellHost,
) {
    /**
     * What the shell shows of the operations: the item the rename dialog is open for ([renaming]), the
     * question to answer ([confirm]), whether the progress shows as a chip ([inBackground]), and the
     * operations whose failures to list, the first one first ([failures]).
     */
    data class State(
        val renaming: FileEntry? = null,
        val confirm: ConfirmRequest? = null,
        val inBackground: Boolean = false,
        val failures: List<FinishedOp> = emptyList(),
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** The runner's state; without a runner, one where nothing ever runs. */
    val runner: StateFlow<RunnerState> = deps.runner?.state ?: MutableStateFlow(RunnerState())

    /** The sources of each move of the clipboard, by its id: those it moved leave the clipboard. */
    private val cuts = HashMap<String, List<Path>>()

    /** The ids of the finished operations already taken from the runner. */
    private val handled = HashSet<String>()

    /** The interrupted operation whose bar is shown, with that bar. */
    private var interruptedBar: Pair<InterruptedOp, UiMessage>? = null

    /** The Recycle Bin items last listed ([binListing]), by their payload; written off the main thread. */
    @Volatile
    private var listed: Map<Path, BinItem> = emptyMap()

    /** What emptying the Recycle Bin asked about ([emptyBin]): the folders of those items in the bin. */
    private var emptying: List<Path> = emptyList()

    /**
     * Follows the runner, the app's notice ([ExplorerDeps.opsNotice]) and the shell's [messages] while
     * the view model lives: each operation that ends is taken from the runner ([onFinished]), and the
     * progress shows as a dialog again once nothing runs; the notice shows until its message leaves the
     * screen, dismissed or replaced, when the app forgets it (so that the next one shows); an operation a
     * crash interrupted is offered again while no other message is shown ([offerInterrupted]).
     */
    suspend fun follow(messages: Flow<UiMessage?>) = coroutineScope {
        launch { runner.collect(::onRunner) }
        launch {
            deps.opsNotice.collect { notice ->
                when (notice) {
                    OpsNotice.ForegroundRefused -> shell.showUnbound(KEEP_APP_OPEN)
                    null -> Unit
                }
            }
        }
        launch {
            var shown: UiMessage? = null
            messages.collect { message ->
                if (shown == KEEP_APP_OPEN && message != KEEP_APP_OPEN) deps.clearOpsNotice()
                shown = message
            }
        }
        launch {
            val interrupted = runner.map { it.interrupted }.distinctUntilChanged()
            combine(interrupted, messages) { _, message -> message }.collect(::offerInterrupted)
        }
    }

    /** [message] was closed: the bar of an interrupted operation forgets it, if it is still listed. */
    fun onDismissed(message: UiMessage?) {
        val (op, bar) = interruptedBar ?: return
        if (message !== bar) return
        interruptedBar = null
        if (runner.value.interrupted.any { it.spec.id == op.spec.id }) deps.runner?.dismissInterrupted(op.spec.id)
    }

    /** Cuts [selected] (in listing order): pasting moves them. */
    fun cut(selected: List<FileEntry>) = clipboard.set(Clipboard(selected.map { it.path }, ClipMode.CUT))

    /** Copies [selected] (in listing order): pasting copies them. */
    fun copy(selected: List<FileEntry>) = clipboard.set(Clipboard(selected.map { it.path }, ClipMode.COPY))

    /**
     * Pastes the clipboard into the active tab's folder: the items still there, in clipboard order, are
     * copied or moved; those gone are left out (and out of a cut clipboard). Onto removable media, what it
     * cannot store ([FileOps.precheck]) is asked about first ([ConfirmRequest.SkipUnstorable]).
     */
    fun paste() {
        val clip = clipboard.state.value ?: return
        val fileOps = deps.fileOps ?: return
        val target = (shell.here() as? Location.Folder)?.path ?: return
        scope.launch {
            val there = existing(clip.paths)
            if (clip.mode == ClipMode.CUT) clipboard.afterPaste(clip.paths.filterNot { it in there }.toSet())
            if (there.isEmpty()) return@launch
            val kind = if (clip.mode == ClipMode.CUT) OpKind.MOVE else OpKind.COPY
            val spec = OperationSpec(newId(), kind, there.map { it.toString() }, target.toString())
            val removable = deps.lookup.volumeOf(target)?.removable == true
            val unstorable = if (removable) unstorableIn(fileOps, spec) else emptyList()
            if (unstorable.isEmpty()) return@launch submit(spec)
            val skipped = unstorable.map { it.path }.toSet()
            // What is inside the folders that go fails by itself, and is listed.
            val rest = spec.copy(sources = spec.sources.filterNot { it in skipped })
            ask(ConfirmRequest.SkipUnstorable(unstorable.size, rest))
        }
    }

    /**
     * Deletes [selected]: to the Recycle Bin without asking, or, [permanent]ly or in the Recycle Bin (where
     * that is what deleting is), once the user confirms ([ConfirmRequest.PermanentDelete]).
     */
    fun delete(permanent: Boolean, selected: List<FileEntry>) {
        val inBin = shell.here() == Location.RecycleBin
        val paths = if (inBin) binItemsOf(selected).mapNotNull(::binPathOf) else selected.map { it.path }
        when {
            paths.isEmpty() -> Unit
            permanent || inBin -> ask(ConfirmRequest.PermanentDelete(paths, paths.size))
            else -> submit(OperationSpec(newId(), OpKind.DELETE_TO_BIN, paths.map { it.toString() }))
        }
    }

    /** Deletes [paths] for good, the user having confirmed it. */
    fun confirmPermanent(paths: List<Path>) {
        mutableState.update { it.copy(confirm = null) }
        if (paths.isNotEmpty()) submit(OperationSpec(newId(), OpKind.DELETE_PERMANENT, paths.map { it.toString() }))
    }

    /** Opens the rename dialog for [entry], unless the operation running uses it ([isBusy]). */
    fun startRename(entry: FileEntry) {
        if (isBusy(entry.path)) return shell.showError(shell.here(), errorText(FsError.Busy))
        mutableState.update { it.copy(renaming = entry) }
    }

    /**
     * Renames [entry] to [newName] at once ([FileOps.rename]) and selects it under its new name; the name it
     * has only closes the dialog. Changing a file's extension is asked about first
     * ([ConfirmRequest.ExtensionChange]) unless [extensionConfirmed]. An item the operation running uses is
     * not renamed, nor one the rename fails for: a message tells why.
     */
    fun commitRename(entry: FileEntry, newName: String, extensionConfirmed: Boolean = false) {
        val fileOps = deps.fileOps ?: return
        val at = shell.here()
        val name = newName.trim(' ')
        if (name == entry.name) return cancelRename()
        if (!extensionConfirmed && entry.kind == EntryKind.FILE && extensionChanges(entry.name, name)) {
            return ask(ConfirmRequest.ExtensionChange(entry, newName))
        }
        mutableState.update { it.copy(renaming = null, confirm = null) }
        if (isBusy(entry.path)) return shell.showError(at, errorText(FsError.Busy))
        scope.launch {
            val renamed = try {
                withContext(deps.io) { fileOps.rename(entry.path, newName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shell.showError(at, errorText(e.toFsError()))
                return@launch
            }
            shell.refreshShowing { it == at }
            shell.selectAt(at, listOf(renamed))
        }
    }

    /** Closes the rename dialog, and the question about its extension. */
    fun cancelRename() = mutableState.update {
        it.copy(renaming = null, confirm = it.confirm.takeUnless { c -> c is ConfirmRequest.ExtensionChange })
    }

    /** Makes a new folder in the active tab's folder ([FileOps.createFolder]), to be named at once. */
    fun newFolder() = create { fileOps, folder -> fileOps.createFolder(folder) }

    /** Makes a new text document in the active tab's folder ([FileOps.createTextFile]), to be named at once. */
    fun newTextFile() = create { fileOps, folder -> fileOps.createTextFile(folder) }

    /** The Recycle Bin items listed for [entries] (rows of the bin's listing). */
    fun binItemsOf(entries: List<FileEntry>): List<BinItem> = entries.mapNotNull { listed[it.path] }

    /** Moves [items] back where they were deleted from (or to their volume's root, when that is not known). */
    fun restore(items: List<BinItem>) {
        if (items.isEmpty()) return
        val ids = OpOptions(binItemIds = items.map { it.id })
        submit(OperationSpec(newId(), OpKind.RESTORE, items.map { it.payload.toString() }, options = ids))
    }

    /**
     * Asks whether to delete everything in the Recycle Bin, as last listed, for good. Yes deletes those
     * items only: what reaches the bin in the meantime (from an operation queued or running) was not asked
     * about, and stays.
     */
    fun emptyBin() {
        emptying = listed.values.mapNotNull(::binPathOf)
        if (emptying.isNotEmpty()) ask(ConfirmRequest.EmptyBin(emptying.size))
    }

    /** The answer to [State.confirm]: yes does what it asked about; no leaves things as they are. */
    fun answerConfirm(yes: Boolean) {
        val request = state.value.confirm ?: return
        mutableState.update { it.copy(confirm = null) }
        if (!yes) return
        when (request) {
            is ConfirmRequest.PermanentDelete -> confirmPermanent(request.paths)
            is ConfirmRequest.BinUnavailable -> confirmPermanent(request.paths)
            is ConfirmRequest.ExtensionChange -> commitRename(request.entry, request.newName, extensionConfirmed = true)
            is ConfirmRequest.EmptyBin -> confirmPermanent(emptying)
            is ConfirmRequest.SkipUnstorable -> if (request.spec.sources.isNotEmpty()) submit(request.spec)
        }
    }

    /** Cancels the operation [opId], queued or running. */
    fun cancel(opId: String) {
        deps.runner?.cancel(opId)
    }

    /** Shows the progress as a chip of the status bar ([background]) or as its dialog. */
    fun setInBackground(background: Boolean) = mutableState.update { it.copy(inBackground = background) }

    /** Runs again what [op] could not do ([io.github.sakkijarvenpolkka.explorer.ops.OperationRunner.retryFailed]). */
    fun retryFailed(op: FinishedOp) {
        dismissFailures(op)
        deps.runner?.retryFailed(op)
    }

    /** Stops listing the failures of [op]. */
    fun dismissFailures(op: FinishedOp) = mutableState.update { state ->
        state.copy(failures = state.failures.filterNot { it.spec.id == op.spec.id })
    }

    /**
     * The items in the Recycle Bins of the mounted volumes as rows sorted by name, read off the main
     * thread ([RecycleBin.list]): each with where it was deleted from (null when that is not known), when,
     * and the size it was recorded with. No rows without a Recycle Bin.
     */
    fun binListing(): Flow<ListingState> = flow {
        val bin = deps.bin
        val items = bin?.list(deps.volumes.volumes.value.mapNotNull { it.root }).orEmpty()
        listed = items.associateBy { it.payload }
        val rows = if (bin == null) emptyList() else items.mapNotNull { rowOf(bin, it) }
        emit(ListingState.Loaded(rows.sortedWith(compareBy(NameComparator) { it.entry.name })))
    }.flowOn(deps.io)

    private fun rowOf(bin: RecycleBin, item: BinItem): Row? {
        val entry = try {
            Listing.entry(deps.fs, item.payload, deps.mime::mimeOf)
        } catch (e: IOException) {
            return null // Gone since the bin was listed.
        }
        val info = RowExtra.BinInfo(bin.originalLocation(item), item.meta?.deletedAt ?: item.modified, item.id)
        return Row(entry.copy(size = item.meta?.size ?: entry.size), info)
    }

    /** What deleting [item] for good deletes: its folder in the bin (or itself, a stray file there). */
    private fun binPathOf(item: BinItem): Path? = deps.bin?.binDir(item.volumeRoot)?.resolve(item.id)

    /** Makes an item in the active tab's folder with [make], selects it and opens the rename dialog for it. */
    private fun create(make: suspend (FileOps, Path) -> Path) {
        val fileOps = deps.fileOps ?: return
        val at = shell.here()
        val folder = (at as? Location.Folder)?.path ?: return
        scope.launch {
            val made = try {
                withContext(deps.io) { Listing.entry(deps.fs, make(fileOps, folder), deps.mime::mimeOf) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                shell.showError(at, errorText(e.toFsError()))
                return@launch
            }
            shell.refreshShowing { it == at }
            shell.selectAt(at, listOf(made.path))
            if (shell.here() == at) mutableState.update { it.copy(renaming = made) }
        }
    }

    /** Puts [spec] in the runner's queue; a move of the clipboard is remembered, for [onFinished]. */
    private fun submit(spec: OperationSpec) {
        val runner = deps.runner ?: return
        if (spec.kind == OpKind.MOVE) cuts[spec.id] = spec.sources.map { Paths.get(it) }
        runner.submit(spec)
    }

    private fun ask(request: ConfirmRequest) = mutableState.update { it.copy(confirm = request) }

    /** The progress shows as a dialog again once nothing runs; each operation that ended is taken. */
    private suspend fun onRunner(now: RunnerState) {
        val idle = now.running == null && now.queue.isEmpty()
        if (idle && state.value.inBackground) setInBackground(false)
        handled.retainAll(now.finished.map { it.spec.id }.toSet())
        for (op in now.finished) if (handled.add(op.spec.id)) onFinished(op)
    }

    /**
     * [op] ended: the runner forgets it, the tabs showing a folder it changed are read again, what a copy
     * or a move made is the new selection where the active tab shows it, the items a move of the
     * clipboard moved leave it, and how it ended is told ([tell]).
     */
    private suspend fun onFinished(op: FinishedOp) {
        deps.runner?.dismiss(op.spec.id)
        val spec = op.spec
        val result = op.result
        val folders = HashSet<Path>()
        // add(), not +=: a Path is an Iterable<Path> of its names.
        spec.target?.let { folders.add(Paths.get(it)) }
        if (spec.kind in TAKES_SOURCES) spec.sources.mapNotNullTo(folders) { Paths.get(it).parent }
        (result as? OpResult.Done)?.created?.mapNotNullTo(folders) { it.parent }
        val binChanged = spec.kind in CHANGES_BIN
        shell.refreshShowing { it is Location.Folder && it.path in folders || binChanged && it == Location.RecycleBin }
        val target = spec.target?.let { Paths.get(it) }
        if (result is OpResult.Done && target != null && (spec.kind == OpKind.COPY || spec.kind == OpKind.MOVE)) {
            val made = result.created.filter { it.parent == target }
            if (made.isNotEmpty()) shell.selectAt(Location.Folder(target), made)
        }
        cuts.remove(spec.id)?.let { sources ->
            val left = existing(sources)
            clipboard.afterPaste(sources.filterNot { it in left }.toSet())
        }
        tell(op)
    }

    /**
     * How [op] ended, as messages about no place: one that did all it was asked with how many items (unless
     * a message that matters more is shown: [showSuccess]), a refused one (nothing done) by its error only,
     * one that stopped with how far it got and why, a move that left items in its sources with how many; a
     * cancelled one, not at all. Its failures are listed ([State.failures]), but those of a delete to the
     * Recycle Bin: the items that could not go there are asked about ([ConfirmRequest.BinUnavailable]),
     * unless another question is open (they stay where they are), and those already gone need no word.
     */
    private fun tell(op: FinishedOp) {
        val kind = op.spec.kind
        when (val result = op.result) {
            OpResult.Cancelled -> Unit
            is OpResult.Failed -> when {
                kind == OpKind.DELETE_TO_BIN && result.error == FsError.BinUnavailable ->
                    askIfFree(ConfirmRequest.BinUnavailable(op.spec.sources.map { Paths.get(it) }))
                result.done == 0 && result.total == 0 ->
                    shell.showUnbound(UiMessage(errorText(result.error), InfoSeverity.ERROR))
                else -> {
                    val stopped = UiText.res(R.string.op_fatal, result.done, result.total, UiText.res(nameOf(kind)))
                    shell.showUnbound(UiMessage(stopped, InfoSeverity.ERROR, detail = errorText(result.error)))
                }
            }
            is OpResult.Done -> {
                if (result.failed.isEmpty() && result.leftInSource == 0) {
                    showSuccess(UiMessage(UiText.res(R.string.op_done, op.spec.sources.size), InfoSeverity.SUCCESS))
                }
                if (result.leftInSource > 0) {
                    val left = UiText.res(R.string.left_in_source, result.leftInSource)
                    shell.showUnbound(UiMessage(left, InfoSeverity.WARNING))
                }
                if (kind == OpKind.DELETE_TO_BIN) {
                    val refused = result.failed.filter { it.error == FsError.BinUnavailable }.map { Paths.get(it.path) }
                    if (refused.isNotEmpty()) askIfFree(ConfirmRequest.BinUnavailable(refused))
                } else if (result.failed.isNotEmpty()) {
                    mutableState.update { it.copy(failures = it.failures + op) }
                }
            }
        }
    }

    /**
     * Shows [success] about no place, but only over no message or another success: an error or a warning
     * (an operation that stopped, items left in a source, the notice to keep the app open, an interrupted
     * operation) matters more, and may be all that tells of what it is about.
     */
    private fun showSuccess(success: UiMessage) {
        val shown = shell.shown()
        if (shown == null || shown.severity == InfoSeverity.SUCCESS) shell.showUnbound(success)
    }

    private fun askIfFree(request: ConfirmRequest) = mutableState.update {
        if (it.confirm == null) it.copy(confirm = request) else it
    }

    /**
     * Shows the first operation a crash interrupted while no other message is shown. Its bar retries it or,
     * closed, forgets it ([onDismissed]); once it is no longer listed (retried), its bar goes, and the next
     * one shows.
     */
    private fun offerInterrupted(message: UiMessage?) {
        // As the runner has them now: what this was called with may be older than the message.
        val interrupted = runner.value.interrupted
        val shown = interruptedBar
        if (shown != null && message === shown.second) {
            if (interrupted.none { it.spec.id == shown.first.spec.id }) {
                // Not forgotten with it: it is running again.
                interruptedBar = null
                shell.dismissMessage()
            }
            return
        }
        if (message != null) return
        val op = interrupted.firstOrNull() ?: return
        val retry = UiText.res(R.string.retry) to { deps.runner?.retry(op) ?: Unit }
        val bar = UiMessage(UiText.res(R.string.op_interrupted, op.committed, op.spec.sources.size), InfoSeverity.WARNING, retry)
        interruptedBar = op to bar
        shell.showUnbound(bar)
    }

    /**
     * Whether the operation running uses [path]: [path] is one of its sources or inside one, or holds one
     * of them or its target.
     */
    private fun isBusy(path: Path): Boolean {
        val spec = runner.value.running?.spec ?: return false
        val target = spec.target?.let { Paths.get(it) }
        return spec.sources.map { Paths.get(it) }.any { it.startsWith(path) || path.startsWith(it) } ||
            target?.startsWith(path) == true
    }

    /** The items of [paths] that are there, looked for off the main thread; none when that fails. */
    private suspend fun existing(paths: List<Path>): List<Path> = try {
        withContext(deps.io) { paths.filter(deps.fs::exists) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** What the target of [spec] cannot store ([FileOps.precheck]); nothing when that cannot be told. */
    private suspend fun unstorableIn(fileOps: FileOps, spec: OperationSpec): List<ItemFailure> = try {
        withContext(deps.io) { fileOps.precheck(spec).unstorable }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList() // The run finds out why, and says so.
    }

    private fun extensionChanges(old: String, new: String): Boolean =
        !splitName(old).second.equals(splitName(new).second, ignoreCase = true)

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        /** The message of [OpsNotice.ForegroundRefused]. */
        val KEEP_APP_OPEN = UiMessage(UiText.res(R.string.fgs_refused), InfoSeverity.WARNING)

        /** What an operation of [kind] is called in "Stopped …". */
        fun nameOf(kind: OpKind): Int = when (kind) {
            OpKind.COPY -> R.string.op_name_copy
            OpKind.MOVE -> R.string.op_name_move
            OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT -> R.string.op_name_delete
            OpKind.RESTORE -> R.string.op_name_restore
            OpKind.EMPTY_BIN -> R.string.op_name_empty_bin
            OpKind.COMPRESS -> R.string.op_name_compress
            OpKind.EXTRACT -> R.string.op_name_extract
        }
    }
}
