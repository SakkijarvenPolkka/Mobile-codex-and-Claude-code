/*
 * Files — what the shell's view model works with and what it hands the shell: its
 * dependencies, the UI state, the info-bar message and the overlay.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.content.Intent
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewSetting
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.Thumbnails
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import io.github.sakkijarvenpolkka.explorer.platform.storageUsage
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * What [ExplorerViewModel] works with; [io] reads folders and resolves paths. Without
 * [openWith], files are not handed to other apps. [fileOps] renames and creates items right away;
 * [runner], the app's one operation runner, runs the other file operations: without both, files
 * are not changed. [bin] is the app's one Recycle Bin, shared with the file operations; without
 * it, there is none. [opsNotice] is what the file operations tell from outside any activity,
 * which the app keeps until it leaves the screen here ([clearOpsNotice]). [thumbnails] are the app's
 * cached thumbnails, for the tiles and large icons views; without them, those show icons only. With
 * [builtInViewers], the files the built-in viewers show open in them (the shell is given what they read
 * files with); without, every file opens in another app. [clock] is when a file is opened, for the
 * recent files. [usage] is how full the volume at a root is, (total, free) bytes, for the drives on Home:
 * null when that is not known.
 */
data class ExplorerDeps(
    val fs: FsBackend,
    val settings: SettingsStore,
    val volumes: VolumeSource,
    val lookup: VolumeLookup,
    val mime: MimeResolver,
    val io: CoroutineDispatcher,
    val openWith: OpenWith? = null,
    val fileOps: FileOps? = null,
    val bin: RecycleBin? = null,
    val runner: OperationRunner? = null,
    val opsNotice: StateFlow<OpsNotice?> = MutableStateFlow(null),
    val clearOpsNotice: () -> Unit = {},
    val thumbnails: Thumbnails? = null,
    val builtInViewers: Boolean = false,
    val clock: () -> Long = System::currentTimeMillis,
    val usage: (Path) -> Pair<Long, Long>? = ::storageUsage,
)

/** How a [UiMessage] is meant: the shell shows each as an info bar of the matching kind. */
enum class MessageSeverity { INFO, WARNING, ERROR, SUCCESS }

/**
 * A message shown in an info bar: [text], [severity], an optional [action], as its label and
 * what it does, an optional [detail] on a line of its own (such as why an operation stopped), and
 * an optional [hint] on the next (what the user could do about it). One about the active tab's
 * location goes when the tab leaves it; any other stays until it is dismissed or replaced.
 */
data class UiMessage(
    val text: UiText,
    val severity: MessageSeverity,
    val action: Pair<UiText, () -> Unit>? = null,
    val detail: UiText? = null,
    val hint: UiText? = null,
)

/** Why renaming an item to [name], as typed, failed: [error]. */
data class RenameError(val name: String, val error: UiText)

/** A question about a file operation, answered yes or no ([ExplorerViewModel.answerConfirm]). */
sealed interface ConfirmRequest {
    /** Delete [paths] for good, [count] items: always asked, it cannot be undone. */
    data class PermanentDelete(val paths: List<Path>, val count: Int) : ConfirmRequest

    /** [paths] could not go to the Recycle Bin: delete them for good instead? */
    data class BinUnavailable(val paths: List<Path>) : ConfirmRequest

    /** Rename [entry] to [newName], which changes its extension. */
    data class ExtensionChange(val entry: FileEntry, val newName: String) : ConfirmRequest

    /** Delete all [count] items of the Recycle Bin (as listed when asked) for good. */
    data class EmptyBin(val count: Int) : ConfirmRequest

    /** [count] items cannot be stored on the destination drive: skip them, running [spec] (the rest)? */
    data class SkipUnstorable(val count: Int, val spec: OperationSpec) : ConfirmRequest
}

/** What is open over the shell; Back closes it before anything else. */
sealed interface Overlay {
    /** The context menu of [entry], which is selected. */
    data class ContextMenu(val entry: FileEntry) : Overlay

    /** The [children] folders of [folder], from the chevron after its breadcrumb segment. */
    data class Subfolders(val folder: Path, val children: List<FileEntry>) : Overlay

    /** The address bar shows the real path for typing. */
    data object AddressEdit : Overlay

    data object Options : Overlay

    /** The properties of [entries]: the selection in listing order, or the current folder. */
    data class Properties(val entries: List<FileEntry>) : Overlay

    /** The menu of the tab [id]: Close, Close other tabs, Duplicate. */
    data class TabMenu(val id: TabId) : Overlay
}

/**
 * Everything the shell shows. [listing] is the active tab's; [unavailable] is set when the
 * active tab's folder is on a volume that is not mounted (the tab keeps its place);
 * [restricted] when the folder is under `Android/data` or, unless the user lets the app install
 * apps, `Android/obb`. [selected] are the selected items in listing order; [up] is where Up
 * goes, null when it is disabled. [launch] is an intent for the shell to start from the
 * activity, so that the app it opens comes back here, and to report with
 * [ExplorerViewModel.launched]. [renaming] is the item the rename dialog is open for, and
 * [renameError] why renaming it to the name typed failed; [confirm] a question to answer;
 * [progressInBackground] whether the progress of the operations shows as a chip of the status bar
 * instead of a dialog; [failedOp] an operation whose failures to list. [opsNotice] is what the file
 * operations tell from outside any activity (keep the app open), in a bar of its own above [message].
 * [renamePending] is set while the rename asked for in the dialog runs. [viewer] is the file shown full
 * screen; [previewPane] whether the preview pane is on (a wide window shows it), and [previewed] the file
 * it shows: the one item selected, else the file last opened into it. [home] is what Home shows (null
 * until it is read), [favorites] the pinned folders, in the user's order, and [unavailableTabs] every tab
 * whose folder is on a volume that is not mounted, with where on it.
 */
data class ExplorerUiState(
    val tabs: TabsModel,
    val listing: ListingState,
    val viewSetting: ViewSetting,
    val global: GlobalSettings,
    val volumes: List<Volume>,
    val clipboard: Clipboard?,
    val commands: Set<Command>,
    val backTarget: BackTarget?,
    val unavailable: DecodedLocation.Unavailable?,
    val restricted: Boolean,
    val message: UiMessage?,
    val overlay: Overlay? = null,
    val drawerOpen: Boolean = false,
    val selected: List<FileEntry> = emptyList(),
    val up: Location? = null,
    val launch: Intent? = null,
    val renaming: FileEntry? = null,
    val confirm: ConfirmRequest? = null,
    val progressInBackground: Boolean = false,
    val failedOp: FinishedOp? = null,
    val renameError: RenameError? = null,
    val opsNotice: UiMessage? = null,
    val renamePending: Boolean = false,
    val viewer: ViewerRequest? = null,
    val previewPane: Boolean = false,
    val previewed: FileEntry? = null,
    val home: HomeData? = null,
    val favorites: List<PinnedFolder> = emptyList(),
    val unavailableTabs: Map<TabId, DecodedLocation.Unavailable> = emptyMap(),
) {
    /** The active tab's location. */
    val location: Location get() = tabs.active.current.location

    /** The rows the listing shows now (none while loading or failed). */
    val rows: List<Row> get() = listing.rows()

    /** The item whose context menu is open. */
    val contextMenuFor: FileEntry? get() = (overlay as? Overlay.ContextMenu)?.entry
}

/** The rows of a loaded or streaming listing; none otherwise. */
internal fun ListingState.rows(): List<Row> = when (this) {
    is ListingState.Loaded -> rows
    is ListingState.Streaming -> rows
    ListingState.Loading, is ListingState.Error -> emptyList()
}
