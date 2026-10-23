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
import io.github.sakkijarvenpolkka.explorer.fs.NameCheck
import io.github.sakkijarvenpolkka.explorer.fs.NameComparator
import io.github.sakkijarvenpolkka.explorer.fs.NameValidator
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpOptions
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
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
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.seconds

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
     * What the shell shows of the operations: the item the rename dialog is open for ([renaming]) and why
     * the name typed there could not be given to it ([renameError]), the question to answer ([confirm]),
     * whether the progress shows as a chip ([inBackground]), the operations whose failures to list, the
     * first one first ([failures]), and the app's notice of them ([notice]).
     */
    data class State(
        val renaming: FileEntry? = null,
        val renameError: RenameError? = null,
        val confirm: ConfirmRequest? = null,
        val inBackground: Boolean = false,
        val failures: List<FinishedOp> = emptyList(),
        val notice: UiMessage? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** The runner's state; without a runner, one where nothing ever runs. */
    val runner: StateFlow<RunnerState> = deps.runner?.state ?: MutableStateFlow(RunnerState())

    /** The ids of the finished operations already taken from the runner. */
    private val handled = HashSet<String>()

    /** Whether the runner had work when last seen ([onRunner]). */
    private var hadWork = false

    /** The questions to ask once the one open ([State.confirm]) is answered, the first first. */
    private val waiting = ArrayDeque<ConfirmRequest>()

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
     * progress shows as a dialog again once nothing runs ([onRunner]); the notice shows in its own slot
     * ([State.notice]) while the app has it, whatever other messages come and go, until it is dismissed
     * ([dismissNotice]) or the work it is about ends; an operation a crash interrupted is offered again
     * while no other message is shown ([offerInterrupted]).
     */
    suspend fun follow(messages: Flow<UiMessage?>) = coroutineScope {
        launch { runner.collect(::onRunner) }
        launch {
            deps.opsNotice.collect { notice ->
                val shown = when (notice) {
                    OpsNotice.ForegroundRefused -> KEEP_APP_OPEN
                    null -> null
                }
                mutableState.update { it.copy(notice = shown) }
            }
        }
        launch {
            val interrupted = runner.map { it.interrupted }.distinctUntilChanged()
            combine(interrupted, messages) { _, message -> message }.collect(::offerInterrupted)
        }
    }

    /**
     * Closes the app's notice ([State.notice]), which the app then forgets, so that the next one shows; also
     * when the slot has not taken it up yet.
     */
    fun dismissNotice() {
        mutableState.update { it.copy(notice = null) }
        deps.clearOpsNotice()
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
     * copied or moved; those gone are left out (and out of a cut clipboard). When that cannot be told, a
     * message says why and the clipboard stays as it is. Onto removable media, the items it cannot store
     * ([FileOps.precheck]) are asked about first ([ConfirmRequest.SkipUnstorable]).
     */
    fun paste() {
        val clip = clipboard.state.value ?: return
        val fileOps = deps.fileOps ?: return
        val at = shell.here()
        val target = (at as? Location.Folder)?.path ?: return
        scope.launch {
            val there = try {
                present(clip.paths)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch shell.showError(at, errorText(e.toFsError()))
            }
            if (clip.mode == ClipMode.CUT) clipboard.afterPaste(clip.paths.filterNot { it in there }.toSet())
            if (there.isEmpty()) return@launch
            val kind = if (clip.mode == ClipMode.CUT) OpKind.MOVE else OpKind.COPY
            val spec = OperationSpec(newId(), kind, there.map { it.toString() }, target.toString())
            val removable = deps.lookup.volumeOf(target)?.removable == true
            val unstorable = if (removable) unstorableIn(fileOps, spec).map { it.path }.toSet() else emptySet()
            // Only what is pasted can be left out: what is inside the folders that go fails by itself, and is listed.
            val skipped = spec.sources.filter { it in unstorable }.toSet()
            if (skipped.isEmpty()) return@launch submit(spec)
            val rest = spec.copy(sources = spec.sources.filterNot { it in skipped })
            ask(ConfirmRequest.SkipUnstorable(skipped.size, rest))
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

    /** Deletes [paths] for good, the user having confirmed it: the question open is closed. */
    fun confirmPermanent(paths: List<Path>) {
        closeQuestion()
        deletePermanently(paths)
    }

    /** Opens the rename dialog for [entry], unless the operation running uses it ([isBusy]). */
    fun startRename(entry: FileEntry) {
        if (isBusy(entry.path)) return shell.showError(shell.here(), errorText(FsError.Busy))
        mutableState.update { it.copy(renaming = entry, renameError = null) }
    }

    /**
     * Renames [entry] to [newName] at once ([FileOps.rename]), closes the dialog and selects it under its
     * new name. The name it has, as typed or once trimmed ([NameValidator]), only closes the dialog.
     * Changing a file's extension is asked about first ([ConfirmRequest.ExtensionChange]) unless
     * [extensionConfirmed]. An item the operation running uses is not renamed, nor one the rename fails
     * for: the dialog stays open, and says why ([State.renameError]; a message, when it is not open).
     */
    fun commitRename(entry: FileEntry, newName: String, extensionConfirmed: Boolean = false) {
        val fileOps = deps.fileOps ?: return
        val at = shell.here()
        val name = checkedName(entry, newName)
        if (newName == entry.name || name == entry.name) return cancelRename()
        if (!extensionConfirmed && entry.kind == EntryKind.FILE && extensionChanges(entry.name, name)) {
            return ask(ConfirmRequest.ExtensionChange(entry, newName))
        }
        if (isBusy(entry.path)) return renameFailed(entry, newName, at, FsError.Busy)
        mutableState.update { it.copy(renameError = null) }
        scope.launch {
            val renamed = try {
                withContext(deps.io) { fileOps.rename(entry.path, newName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch renameFailed(entry, newName, at, e.toFsError())
            }
            // Its dialog closes, unless it was closed meanwhile (and opened for another item).
            mutableState.update {
                if (it.renaming?.path == entry.path) it.copy(renaming = null, renameError = null) else it
            }
            shell.refreshShowing { it == at }
            shell.selectAt(at, listOf(renamed))
        }
    }

    /** Closes the rename dialog, and the question about its extension (asked or to ask). */
    fun cancelRename() {
        waiting.removeAll { it is ConfirmRequest.ExtensionChange }
        mutableState.update { it.copy(renaming = null, renameError = null) }
        if (state.value.confirm is ConfirmRequest.ExtensionChange) closeQuestion()
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

    /**
     * The answer to [State.confirm]: yes does what it asked about; no leaves things as they are. The next
     * question waiting is asked then.
     */
    fun answerConfirm(yes: Boolean) {
        val request = state.value.confirm ?: return
        closeQuestion()
        if (!yes) return
        when (request) {
            is ConfirmRequest.PermanentDelete -> deletePermanently(request.paths)
            is ConfirmRequest.BinUnavailable -> deletePermanently(request.paths)
            is ConfirmRequest.ExtensionChange -> commitRename(request.entry, request.newName, extensionConfirmed = true)
            is ConfirmRequest.EmptyBin -> deletePermanently(emptying)
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
            if (shell.here() == at) mutableState.update { it.copy(renaming = made, renameError = null) }
        }
    }

    /** Puts [spec] in the runner's queue. */
    private fun submit(spec: OperationSpec) {
        deps.runner?.submit(spec)
    }

    /** Deletes [paths] for good (the user was asked). */
    private fun deletePermanently(paths: List<Path>) {
        if (paths.isNotEmpty()) submit(OperationSpec(newId(), OpKind.DELETE_PERMANENT, paths.map { it.toString() }))
    }

    /** Asks [request] now, or, while another question is open, once that one is answered: none is dropped. */
    private fun ask(request: ConfirmRequest) {
        if (state.value.confirm != null) return waiting.addLast(request)
        mutableState.update { it.copy(confirm = request) }
    }

    /** Closes the question open; the next one waiting is asked. */
    private fun closeQuestion() {
        val next = waiting.removeFirstOrNull()
        mutableState.update { it.copy(confirm = next) }
    }

    /**
     * The progress shows as a dialog again once nothing runs, and the app's notice goes once the runner
     * goes from having work to having none (no work goes on without the foreground service any more, and
     * new work starts it again); each operation that ended is taken.
     */
    private suspend fun onRunner(now: RunnerState) {
        val idle = now.running == null && now.queue.isEmpty()
        // One that ended since it was last seen: there was work, even if the runner was not seen busy.
        val worked = hadWork || now.finished.any { it.spec.id !in handled }
        hadWork = !idle
        if (idle && state.value.inBackground) setInBackground(false)
        if (idle && worked) dismissNotice()
        handled.retainAll(now.finished.map { it.spec.id }.toSet())
        for (op in now.finished) if (handled.add(op.spec.id)) onFinished(op)
    }

    /**
     * [op] ended: the runner forgets it, the tabs showing a folder it changed are read again, what a copy
     * or a move made is the new selection where the active tab shows it, the items a move moved leave a
     * cut clipboard, and how it ended is told ([tell]).
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
        if (spec.kind == OpKind.MOVE) afterMove(spec.sources.map { Paths.get(it) })
        tell(op)
    }

    /** The [sources] of a move that are gone leave a cut clipboard; when that cannot be told, it stays as it is. */
    private suspend fun afterMove(sources: List<Path>) {
        val left = try {
            present(sources)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        clipboard.afterPaste(sources.filterNot { it in left }.toSet())
    }

    /**
     * Tells how [op] ended ([tellingOf]): its message about no place, a success only as [showSuccess] lets
     * it; its question once no other is open ([ask]); its failures listed ([State.failures]).
     */
    private suspend fun tell(op: FinishedOp) {
        val telling = tellingOf(op, freeableFor(op))
        telling.message?.let { if (it.severity == InfoSeverity.SUCCESS) showSuccess(it) else shell.showUnbound(it) }
        telling.question?.let(::ask)
        telling.listed?.let { listed -> mutableState.update { it.copy(failures = it.failures + listed) } }
    }

    /**
     * What emptying the Recycle Bin of [op]'s target volume would free, read off the main thread, when [op]
     * stopped for want of space; else 0.
     */
    private suspend fun freeableFor(op: FinishedOp): Long {
        if ((op.result as? OpResult.Failed)?.error != FsError.NoSpace) return 0
        val bin = deps.bin ?: return 0
        val root = op.spec.target?.let { deps.lookup.volumeOf(Paths.get(it))?.root } ?: return 0
        return try {
            withContext(deps.io) { bin.totalSize(listOf(root)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Shows [success] about no place, but only over no message or another success: an error or a warning
     * (an operation that stopped, items left in a source, an interrupted operation) matters more, and may
     * be all that tells of what it is about. It is short: it goes by itself after [SUCCESS_SHOWN_FOR] (on
     * the main thread's clock), unless another message took its place.
     */
    private fun showSuccess(success: UiMessage) {
        val shown = shell.shown()
        if (shown != null && shown.severity != InfoSeverity.SUCCESS) return
        shell.showUnbound(success)
        scope.launch {
            delay(SUCCESS_SHOWN_FOR)
            if (shell.shown() === success) shell.dismissMessage()
        }
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

    /** The items of [paths] that are there, looked for off the main thread; throws when that cannot be told. */
    private suspend fun present(paths: List<Path>): List<Path> = withContext(deps.io) { paths.filter(deps.fs::exists) }

    /** The name [typed] for [entry] comes to, as the rename checks it ([NameValidator]): as typed when invalid. */
    private fun checkedName(entry: FileEntry, typed: String): String {
        val traits = deps.lookup.volumeOf(entry.path)?.traits ?: VolumeTraits()
        return (NameValidator.validate(typed, traits) as? NameCheck.Ok)?.name ?: typed
    }

    /** The rename of [entry] to [typed] failed with [error]: said in its dialog, if open, else about [at]. */
    private fun renameFailed(entry: FileEntry, typed: String, at: Location, error: FsError) {
        val text = errorText(error)
        if (state.value.renaming?.path != entry.path) return shell.showError(at, text)
        mutableState.update { it.copy(renameError = RenameError(typed, text)) }
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

        /** How long the bar of an operation that did all it was asked shows. */
        val SUCCESS_SHOWN_FOR = 4.seconds
    }
}
