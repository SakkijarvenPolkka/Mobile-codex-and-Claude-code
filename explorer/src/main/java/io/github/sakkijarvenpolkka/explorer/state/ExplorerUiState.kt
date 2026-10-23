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
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * What [ExplorerViewModel] works with; [io] reads folders and resolves paths. Without
 * [openWith], files are not handed to other apps. [bin] is the app's one Recycle Bin, shared with
 * the file operations; without it, there is none. [runner] is the app's one operation runner,
 * [opsNotice] what the file operations tell from outside any activity, which the app keeps until
 * the user dismisses it here ([clearOpsNotice]).
 */
data class ExplorerDeps(
    val fs: FsBackend,
    val settings: SettingsStore,
    val volumes: VolumeSource,
    val lookup: VolumeLookup,
    val mime: MimeResolver,
    val io: CoroutineDispatcher,
    val openWith: OpenWith? = null,
    val bin: RecycleBin? = null,
    val runner: OperationRunner? = null,
    val opsNotice: StateFlow<OpsNotice?> = MutableStateFlow(null),
    val clearOpsNotice: () -> Unit = {},
)

/**
 * A message shown in an info bar: [text], [severity] and an optional [action], as its label and
 * what it does. One about the active tab's location goes when the tab leaves it; any other stays
 * until it is dismissed.
 */
data class UiMessage(val text: UiText, val severity: InfoSeverity, val action: Pair<UiText, () -> Unit>? = null)

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
}

/**
 * Everything the shell shows. [listing] is the active tab's; [unavailable] is set when the
 * active tab's folder is on a volume that is not mounted (the tab keeps its place);
 * [restricted] when the folder is under `Android/data` or, unless the user lets the app install
 * apps, `Android/obb`. [selected] are the selected items in listing order; [up] is where Up
 * goes, null when it is disabled. [launch] is an intent for the shell to start from the
 * activity, so that the app it opens comes back here, and to report with
 * [ExplorerViewModel.launched].
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
