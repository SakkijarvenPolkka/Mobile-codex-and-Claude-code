/*
 * Files — the shell's view model: the tabs, the active tab's listing, and the settings and
 * volumes they depend on, combined into one UI state.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewSetting
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.BinItem
import io.github.sakkijarvenpolkka.explorer.fs.ContentCount
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.fs.countContents
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.RunnerState
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.platform.Thumbnails
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * The single entry point of the shell. It keeps no Compose state: the tabs go through
 * [TabsReducer], each tab's listing through its own [ListingLoader] (created here, so the
 * reading stops with the view model), the settings through [SettingsStore]. The session is
 * saved when a tab moves or tabs change, not when the selection does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerViewModel(app: Application, private val deps: ExplorerDeps) : AndroidViewModel(app) {

    private val settings = deps.settings
    private val codec = VolumeLocationCodec { deps.volumes.volumes.value }
    private val loader = ListingLoader(viewModelScope, deps.fs, deps.io, deps.mime::mimeOf)
    private val clipboard = ClipboardHolder()
    private val host = Host()
    private val handOver = HandOver(app, deps, viewModelScope, host)
    private val ops = OperationsUi(deps, viewModelScope, clipboard, host)
    private val home = HomeLoader(deps, codec, viewModelScope)
    internal val searches = Searches(deps, viewModelScope, host) { shell.value.obbAllowed }
    private val zips = ZipUi(deps, viewModelScope, host) { shell.value.obbAllowed }

    /** The built-in viewers: the file shown full screen or in the preview pane, and what their buttons do. */
    internal val viewing = Viewers(deps, viewModelScope, host, handOver, ::listedRows)

    /** The app's thumbnails, for the list to draw ([ExplorerDeps.thumbnails]). */
    val thumbnails: Thumbnails? = deps.thumbnails

    /** What the app's file operations are doing: the one running, its conflict, the queue. */
    val runner: StateFlow<RunnerState> = ops.runner

    private val tabs: MutableStateFlow<TabsModel>

    /** The tabs on volumes that are not mounted; main thread only, as the maps below. */
    private val volumeTracking: VolumeTracking
    private val shell = MutableStateFlow(ShellState(obbAllowed = installsAllowed()))

    /** What each tab's list was last read for. */
    private val loadedFor = HashMap<TabId, LoadKey>()

    /**
     * Where each tab's list was last scrolled to at the location it shows: for the history entry
     * it leaves, and for a list built again there (the activity recreated).
     */
    private val scrollSpots = HashMap<TabId, ScrollSpot>()

    init {
        val restored = settings.loadSession(codec)
        tabs = MutableStateFlow(restored?.model ?: TabsReducer.initial(Location.Home))
        volumeTracking = VolumeTracking(deps.volumes, codec, restored?.unavailable.orEmpty())
    }

    private val activeListing: Flow<Pair<TabId, ListingState>> = tabs
        .map { it.activeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> loader.state(id).map { id to it } }

    val ui: StateFlow<ExplorerUiState> = combine(
        combine(tabs, activeListing, volumeTracking.unavailable, shell) { t, l, u, s -> Core(t, l, u, s) },
        combine(settings.global, deps.volumes.volumes, clipboard.state, pins(deps, codec)) { g, v, c, p ->
            Env(g, v, c, p)
        },
        ops.state,
        viewing.state,
        home.state,
        ::build,
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        build(
            Core(
                tabs.value, tabs.value.activeId to ListingState.Loading, volumeTracking.unavailable.value, shell.value,
            ),
            Env(settings.global.value, deps.volumes.volumes.value, clipboard.state.value, pinsOf(deps, codec)),
            ops.state.value,
            viewing.state.value,
            home.state.value,
        ),
    )

    init {
        viewModelScope.launch { deps.volumes.volumes.collect(volumeTracking::rememberRoots) }
        viewModelScope.launch { deps.volumes.events.collect(::onVolumeEvent) }
        viewModelScope.launch { ops.follow(shell.map { it.message }.distinctUntilChanged()) }
        viewModelScope.launch { activeListing.collect { (id, state) -> onListing(id, state) } }
        viewModelScope.launch { home.follow { here() == Location.Home } }
        viewModelScope.launch {
            val viewRevision = shell.map { it.viewRevision }.distinctUntilChanged()
            val unavailable = volumeTracking.unavailable
            combine(tabs, unavailable, settings.global, viewRevision) { _, _, _, _ -> }.collect { ensureLoaded() }
        }
    }

    /** Goes to [l] in the active tab, remembering where the list was scrolled for Back. */
    fun navigate(l: Location) = reduce(TabAction.Navigate(l, leavingEntry()))

    fun back() = reduce(TabAction.Back)

    fun forward() = reduce(TabAction.Forward)

    /** Goes to the parent folder, from a volume root Home; nothing from Home or a folder on no mounted volume. */
    fun up() {
        val tab = tabs.value.active
        val roots = deps.volumes.volumes.value.mapNotNull { it.root }
        upFrom(tab.current.location, tab.id in volumeTracking.unavailable.value, roots, deps.lookup)?.let(::navigate)
    }

    /** Reads the active tab's folder again, keeping its rows on screen until the new ones arrive. */
    fun refresh() = ensureLoaded(refresh = true)

    /**
     * Opens [entry]: a folder here (a link is followed when it leads to a folder in storage), a ZIP archive in
     * the extract dialog where its folder takes the new one ([ZipUi.extracts]), a file in its built-in viewer or
     * else another app ([Viewers.open]). An APK needs the user to let this app install apps first, which a
     * message offers to do.
     */
    fun open(entry: FileEntry) {
        when (entry.kind) {
            EntryKind.DIR -> navigate(Location.Folder(entry.path))
            EntryKind.SYMLINK -> goTo(entry.path.toString(), base = null, showFile = false)
            EntryKind.FILE -> if (zips.extracts(entry)) requestExtract(entry) else viewing.open(entry, listedRows())
            // A pipe, socket or device: no app reads it as a file.
            EntryKind.OTHER -> host.showError(here(), UiText.res(R.string.open_unsupported))
        }
    }

    /** A tap: toggles the item in selection mode and in the Recycle Bin, opens it otherwise. */
    fun onTap(entry: FileEntry) {
        val toggles = tabs.value.active.selection.active || here() == Location.RecycleBin
        if (toggles) reduce(TabAction.Toggle(entry.path)) else open(entry)
    }

    /**
     * A long press: selects the item (entering selection mode), adds it to the selection in
     * selection mode, or opens its context menu when it is already selected (the Recycle Bin has none).
     */
    fun onLongPress(entry: FileEntry) {
        val selection = tabs.value.active.selection
        when {
            entry.path in selection.items && here() == Location.RecycleBin -> Unit
            entry.path in selection.items -> shell.update { it.copy(overlay = Overlay.ContextMenu(entry)) }
            selection.active -> reduce(TabAction.Toggle(entry.path))
            else -> reduce(TabAction.Select(entry.path))
        }
    }

    /** The item's "⋯": its context menu, selecting it first when it is not selected. */
    fun openContextMenu(entry: FileEntry) {
        if (entry.path !in tabs.value.active.selection.items) reduce(TabAction.Select(entry.path))
        shell.update { it.copy(overlay = Overlay.ContextMenu(entry)) }
    }

    fun dispatch(a: TabAction) = reduce(a)

    // The tab strip and its menu (spec §2.1); a new tab opens at Home.

    fun newTab() = reduce(TabAction.NewTab(Location.Home))

    fun activate(id: TabId) = reduce(TabAction.Activate(id))

    /** Activates the next tab, the first after the last (Ctrl+Tab). */
    fun nextTab() = activate(tabs.value.afterActive())

    fun closeTab(id: TabId) = reduce(TabAction.Close(id))

    fun closeOthers(id: TabId) = reduce(TabAction.CloseOthers(id))

    fun duplicate(id: TabId) = reduce(TabAction.Duplicate(id))

    fun openTabMenu(id: TabId) = shell.update { it.copy(overlay = Overlay.TabMenu(id)) }

    // The favorites (spec §3.5), in the user's order: HomeState.kt tells what each of these does.

    fun pin(paths: List<Path>) = deps.pin(paths)

    fun unpin(favorite: Favorite) = deps.unpin(favorite)

    fun reorderFavorites(moved: Favorite, to: Favorite) = deps.reorderFavorites(moved, to)

    // The search box (spec §6.1): SearchState.kt tells what each of these does.

    fun search(query: String, includeSubfolders: Boolean = true) = searches.search(query, includeSubfolders)

    fun searchNow(query: String, includeSubfolders: Boolean = true) = searches.searchNow(query, includeSubfolders)

    fun cancelSearch() = searches.cancel()

    fun clearSearch() = searches.clear()

    /** Sets the view of the active tab's folder; folders without a view of their own take it from now on. */
    fun setView(v: ViewSetting) {
        val key = codec.encode(tabs.value.active.current.location) ?: return
        settings.setView(key, v)
        settings.updateGlobal { it.copy(defaultView = v) }
        shell.update { it.copy(viewRevision = it.viewRevision + 1) }
    }

    fun setGlobal(f: (GlobalSettings) -> GlobalSettings) = settings.updateGlobal(f)

    /**
     * Goes to the path typed in the address bar: made canonical (`..`, links, `/sdcard`), relative
     * to the current folder unless absolute. A file opens its folder with the file selected; a
     * path outside the mounted volumes or one that cannot be read shows a message.
     */
    fun typedPath(text: String) {
        val typed = text.trim()
        if (typed.isEmpty()) return closeOverlay()
        goTo(typed, base = (tabs.value.active.current.location as? Location.Folder)?.path, showFile = true)
    }

    /** The app came back: the active tab is read again, the others are marked out of date (search results neither). */
    fun onResume() {
        // The user may have let the app install apps in the settings meanwhile.
        shell.update { it.copy(obbAllowed = installsAllowed()) }
        // Search results stay as they were found: searching again may take long.
        val reread = tabs.value.tabs.filter { it.current.location !is Location.SearchResults }
        reread.filter { it.id != tabs.value.activeId }.forEach { loader.markStale(it.id) }
        if (here() !is Location.SearchResults) ensureLoaded(refresh = true)
    }

    fun dismissMessage() {
        // First: what closing it does may change what shows once it is gone.
        ops.onDismissed(shell.value.message)
        shell.update { it.copy(message = null, messageAt = null) }
    }

    fun dismissOpsNotice() = ops.dismissNotice()

    /** The window is [expanded] (840 dp wide or more): the navigation pane is pinned, the preview pane may show. */
    fun setExpanded(expanded: Boolean) {
        viewing.setExpanded(expanded)
        if (expanded) setDrawerOpen(false)
    }

    /** The properties of [entry], such as the file a viewer shows. */
    fun showProperties(entry: FileEntry) = shell.update { it.copy(overlay = Overlay.Properties(listOf(entry))) }

    // The file operations, as OperationsUi describes them.

    fun cut() = ops.cut(selectedEntries())

    fun copy() = ops.copy(selectedEntries())

    fun paste() = ops.paste()

    fun delete(permanent: Boolean) = ops.delete(permanent, selectedEntries())

    fun startRename(entry: FileEntry) = ops.startRename(entry)

    fun commitRename(entry: FileEntry, newName: String) = ops.commitRename(entry, newName)

    fun cancelRename() = ops.cancelRename()

    fun newFolder() = ops.newFolder()

    fun newTextFile() = ops.newTextFile()

    fun restore(items: List<BinItem>) = ops.restore(items)

    fun emptyBin() = ops.emptyBin()

    fun answerConfirm(yes: Boolean) = ops.answerConfirm(yes)

    fun cancelOperation(opId: String) = ops.cancel(opId)

    fun runInBackground() = ops.setInBackground(true)

    fun showProgress() = ops.setInBackground(false)

    fun retryFailed(op: FinishedOp) = ops.retryFailed(op)

    fun dismissFailures(op: FinishedOp) = ops.dismissFailures(op)

    // ZIP archives (spec §6.2): ZipUi.kt tells what each of these does.

    fun compress() = zips.compress(selectedEntries())

    fun requestExtract(entry: FileEntry) = zips.requestExtract(entry)

    fun extract(entry: FileEntry, charset: String?) = zips.extract(entry, charset)

    /** Runs a command of the command bar or a menu. */
    fun command(c: Command) {
        when (c) {
            Command.CUT -> cut()
            Command.COPY -> copy()
            Command.PASTE -> paste()
            Command.DELETE -> delete(permanent = false)
            Command.DELETE_PERMANENT -> delete(permanent = true)
            Command.RENAME -> selectedEntries().singleOrNull()?.let(::startRename)
            Command.NEW_FOLDER -> newFolder()
            Command.NEW_TEXT -> newTextFile()
            Command.RESTORE -> restore(ops.binItemsOf(selectedEntries()))
            Command.EMPTY_BIN -> emptyBin()
            Command.SELECT_ALL -> reduce(TabAction.SelectAll(listedRows().map { it.entry.path }))
            Command.SELECT_NONE -> reduce(TabAction.ClearSelection)
            Command.INVERT -> reduce(TabAction.Invert(listedRows().map { it.entry.path }))
            Command.REFRESH -> refresh()
            Command.OPTIONS -> shell.update { it.copy(overlay = Overlay.Options) }
            Command.OPEN -> selectedEntries().singleOrNull()?.let(::open)
            Command.OPEN_WITH -> selectedEntries().singleOrNull()?.let(handOver::openWith)
            Command.SHARE -> handOver.share(selectedEntries().map { it.path })
            Command.PROPERTIES -> showProperties()
            Command.PIN -> deps.pin(pinTargets(selectedEntries(), here()))
            Command.UNPIN -> deps.unpin(pinTargets(selectedEntries(), here()))
            Command.COMPRESS -> compress()
            Command.EXTRACT -> selectedEntries().singleOrNull()?.let(::requestExtract)
        }
    }

    /**
     * The shell started [intent], the one [ExplorerUiState.launch] held, with [result]; a failure
     * shows a message about where the active tab was when the intent was asked for.
     */
    fun launched(intent: Intent, result: OpenResult) {
        val pending = shell.value.launch?.takeIf { it.intent === intent } ?: return
        shell.update { it.copy(launch = null) }
        handOver.launched(pending, result)
    }

    /** What [entries] hold, counted off the main thread while the flow is collected ([countContents]). */
    fun contentsOf(entries: List<FileEntry>): Flow<ContentCount> = countContents(deps.fs, entries).flowOn(deps.io)

    /** Opens the menu of [folder]'s subfolders (nothing when it has none or is on no mounted volume). */
    fun showSubfolders(folder: Path) {
        if (deps.lookup.volumeOf(folder) == null) return
        val options = ListOptions(showHidden = settings.global.value.showHidden)
        val at = here()
        viewModelScope.launch {
            val listed = readOrTell(at) { Listing.list(deps.fs, folder, options) { null } } ?: return@launch
            val children = listed.filter { it.kind == EntryKind.DIR }
            if (children.isNotEmpty()) shell.update { it.copy(overlay = Overlay.Subfolders(folder, children)) }
        }
    }

    /** Shows the address bar as a text box with the real path. */
    fun editAddress() = shell.update { it.copy(overlay = Overlay.AddressEdit) }

    fun closeOverlay() = shell.update { it.copy(overlay = null) }

    /** Opens or closes the navigation drawer (narrow windows). */
    fun setDrawerOpen(open: Boolean) = shell.update { it.copy(drawerOpen = open) }

    /**
     * The system Back: closes what is open (a menu or dialog, then the viewer), clears the selection,
     * closes the drawer or goes back, in that order.
     */
    fun systemBack() {
        when (backOf(tabs.value.active, shell.value, viewing.state.value)) {
            BackTarget.OVERLAY -> if (shell.value.overlay != null) closeOverlay() else viewing.close()
            BackTarget.SELECTION -> reduce(TabAction.ClearSelection)
            BackTarget.DRAWER -> setDrawerOpen(false)
            BackTarget.HISTORY -> back()
            null -> Unit
        }
    }

    /** Where the active tab's list of [location] is scrolled to (first visible item and its offset). */
    fun onScrolled(location: Location, index: Int, offset: Int) {
        scrollSpots[tabs.value.activeId] = ScrollSpot(location, index, offset)
    }

    /** The list scrolled to [ExplorerUiState.reveal]. */
    fun revealed() = shell.update { it.copy(reveal = null) }

    /**
     * [entry] with where [tab]'s list was last scrolled to at its location, or [entry] as it is
     * when the list has not been there since. A list built again for the same place (the activity
     * recreated) starts there, and the entry a tab leaves keeps it for Back.
     */
    fun scrollOf(tab: TabId, entry: HistoryEntry): HistoryEntry {
        val spot = scrollSpots[tab]?.takeIf { it.location == entry.location } ?: return entry
        return entry.copy(scrollIndex = spot.index, scrollOffset = spot.offset)
    }

    private fun reduce(a: TabAction) {
        val before = tabs.value
        val after = TabsReducer.reduce(before, a)
        if (after === before) return
        tabs.value = after
        if (a.changesSelectionOnly()) return
        for (closed in before.tabs.map { it.id } - after.tabs.map { it.id }.toSet()) {
            loader.drop(closed)
            loadedFor.remove(closed)
            searches.forget(closed)
        }
        val stayed = { id: TabId -> after.locationOf(id) == before.locationOf(id) }
        volumeTracking.retain(stayed)
        scrollSpots.keys.retainAll(stayed)
        // A tab put where its volume is mounted now (any tab, not only this one) leaves what is open alone.
        val keepOverlay = a is TabAction.ReplaceCurrent
        shell.update {
            val here = it.at(after.active.current.location)
            if (keepOverlay) here else here.copy(overlay = null, reveal = null)
        }
        settings.saveSession(after, codec)
    }

    /** The active tab's rows as the loader has them now. */
    private fun listedRows(): List<Row> = loader.state(tabs.value.activeId).value.rows()

    /** The active tab's selected items, in listing order. */
    private fun selectedEntries(): List<FileEntry> {
        val selected = tabs.value.active.selection.items
        return listedRows().mapNotNull { row -> row.entry.takeIf { it.path in selected } }
    }

    private fun leavingEntry(): HistoryEntry = tabs.value.active.let { scrollOf(it.id, it.current) }

    /**
     * Reads the active tab's folder, the Recycle Bin or Home, or searches, when it is not read for its current
     * options yet, or always with [refresh]; a folder on no mounted volume is never read (see [placeOffVolume]).
     */
    private fun ensureLoaded(refresh: Boolean = false) {
        val tab = tabs.value.active
        val location = tab.current.location
        if (location == Location.Home) return loadHome(tab.id, refresh)
        val folder = (location as? Location.Folder)?.path
        val search = location as? Location.SearchResults
        if (tab.id in volumeTracking.unavailable.value) return
        if (folder != null && deps.lookup.volumeOf(folder) == null) return placeOffVolume(tab.id, folder)
        val global = settings.global.value
        val options = ListOptions(global.showHidden, viewFor(location, global).sort)
        // What a search found is sorted again as it is: a new sort never searches again.
        if (search != null) searches.sortBy(tab.id, options.sort)
        val key = LoadKey(location, if (search != null) options.copy(sort = SortSpec()) else options)
        val previous = loadedFor[tab.id]
        val stale = loader.isStale(tab.id)
        if (!refresh && !stale && key == previous) return
        loadedFor[tab.id] = key
        val again = refresh || stale || previous?.location == location
        if (search != null) {
            val results = searches.results(tab.id, search, options, shown = if (again) listedRows() else emptyList())
            return loader.setSource(tab.id, results, refresh = again)
        }
        if (folder == null) return loader.setSource(tab.id, ops.binListing())
        loader.load(tab.id, folder, options, refresh = again)
    }

    /** Reads Home for [tab], come there (or out of date) or reading it again; its list of where it was goes. */
    private fun loadHome(tab: TabId, refresh: Boolean) {
        if (!refresh && !loader.isStale(tab) && !home.stale && loadedFor[tab]?.location == Location.Home) return
        loadedFor[tab] = LoadKey(Location.Home, ListOptions())
        loader.setSource(tab, emptyFlow())
        home.load()
    }

    private fun onListing(id: TabId, state: ListingState) {
        if (id != tabs.value.activeId) return
        val denied = state is ListingState.Error && state.error == FsError.AccessDenied
        if (denied && onRawMount()) showAt(here(), restartOffer(getApplication()))
        // All there is here, read or found: what is gone is no longer selected, and its menu not left open unseen.
        val complete = state is ListingState.Loaded && !state.refreshing ||
            state is ListingState.Streaming && state.done
        if (!complete) return
        val listed = state.rows().map { it.entry.path }
        reduce(TabAction.Prune(listed))
        val menu = shell.value.overlay as? Overlay.ContextMenu
        if (menu != null && menu.entry.path !in tabs.value.active.selection.items) closeOverlay()
        // Nothing to scroll to that is not listed (an archive these results do not have, a hidden one).
        if (shell.value.reveal?.let { it !in listed } == true) revealed()
    }

    /** Whether the active tab's folder is on a USB drive this process may not read ([isOnRawMount]). */
    private fun onRawMount(): Boolean = (here() as? Location.Folder)?.path?.let(deps.lookup::isOnRawMount) == true

    /**
     * Resolves [text] (against [base] when relative) off the main thread and goes to the folder,
     * or with [showFile] to a file's folder with the file selected.
     */
    private fun goTo(text: String, base: Path?, showFile: Boolean) {
        val at = here()
        viewModelScope.launch {
            when (val target = readOrTell(at) { resolvePath(deps.fs, deps.lookup, text, base) } ?: return@launch) {
                is Resolved.Folder -> {
                    closeOverlay()
                    navigate(Location.Folder(target.path))
                }
                is Resolved.File -> if (showFile) {
                    closeOverlay()
                    navigate(Location.Folder(target.path.parent))
                    reduce(TabAction.Select(target.path))
                }
                Resolved.Outside -> host.showError(at, UiText.res(R.string.outside_storage))
                is Resolved.Failed -> host.showError(at, errorText(target.error))
            }
        }
    }

    private fun here(): Location = tabs.value.active.current.location

    /** What [read] returns, read on [ExplorerDeps.io]; null when it fails, and a message about [at] says why. */
    private suspend fun <T : Any> readOrTell(at: Location, read: () -> T): T? = try {
        withContext(deps.io) { read() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        host.showError(at, errorText(e.toFsError()))
        null
    }

    /**
     * Shows [message] about [at], where the active tab was when the user asked for what it
     * answers: not at all when the tab has left it since, and it goes when the tab leaves it.
     */
    private fun showAt(at: Location, message: UiMessage) {
        if (here() == at) shell.update { it.copy(message = message, messageAt = at) }
    }

    /** The properties of the selection, else of the current folder (read off the main thread). */
    private fun showProperties() {
        val selected = selectedEntries()
        if (selected.isNotEmpty()) return shell.update { it.copy(overlay = Overlay.Properties(selected)) }
        val at = here()
        val folder = (at as? Location.Folder)?.path ?: return
        viewModelScope.launch {
            val entry = readOrTell(at) { Listing.entry(deps.fs, folder, deps.mime::mimeOf) } ?: return@launch
            if (here() == at) shell.update { it.copy(overlay = Overlay.Properties(listOf(entry))) }
        }
    }

    /** Whether the user lets this app install apps, which also opens `Android/obb` to it. */
    private fun installsAllowed(): Boolean = deps.openWith?.needsInstallPermission() == false

    /**
     * A volume going or mounted ([VolumeTracking.onVolumeEvent]): a tab that loses its folder is
     * read again once it is back, and the moves to where a volume is now are dispatched.
     */
    private fun onVolumeEvent(e: VolumeEvent) {
        if (e is VolumeEvent.Ejecting) viewing.onEjecting(e.uuid)
        val change = volumeTracking.onVolumeEvent(e, tabs.value)
        change.ejected.forEach(loadedFor::remove)
        change.ejected.forEach(searches::stop)
        if (tabs.value.activeId in change.ejected) closeOverlay()
        change.moves.forEach(::reduce)
    }

    /**
     * The tab [tab] is at [folder], on no mounted volume, which is never read there: it shows the
     * drive as unavailable, moves to the same folder where the volume is mounted now, or shows the
     * folder as not found when it is on no volume at all ([VolumeTracking.placeOffVolume]).
     */
    private fun placeOffVolume(tab: TabId, folder: Path) {
        volumeTracking.placeOffVolume(tab, folder)?.let { return reduce(it) }
        // Not what the tab read last any more: Back to that folder reads it again.
        loadedFor.remove(tab)
        if (tab !in volumeTracking.unavailable.value) {
            loader.setSource(tab, flowOf(ListingState.Error(FsError.NotFound)))
        }
    }

    /** The view of [location]'s folder, or the default one. */
    private fun viewFor(location: Location, global: GlobalSettings): ViewSetting =
        codec.encode(location)?.let(settings::viewFor) ?: global.defaultView

    private fun build(
        core: Core,
        env: Env,
        opsState: OperationsUi.State,
        viewed: Viewers.State,
        homeData: HomeData?,
    ): ExplorerUiState {
        val tab = core.tabs.active
        val location = tab.current.location
        val unavailable = core.unavailable[tab.id]
        val (listingTab, shown) = core.listing
        val listing = if (listingTab == tab.id && unavailable == null) shown else ListingState.Loading
        val rows = listing.rows()
        val selected = rows.mapNotNull { row -> row.entry.takeIf { it.path in tab.selection.items } }
        val folder = (location as? Location.Folder)?.path?.takeIf { unavailable == null }
        // Search results are of the folder searched: on its volume, restricted with it.
        val place = folder ?: (location as? Location.SearchResults)?.root
        val volume = place?.let(deps.lookup::volumeOf)
        val restricted = place != null && volume != null &&
            isRestrictedAppDataPath(volume.root, place, obbAllowed = core.shell.obbAllowed)
        val roots = env.volumes.mapNotNull { it.root }
        val readOnly = location != Location.RecycleBin && volume != null &&
            env.volumes.any { it.uuid == volume.uuid && it.state == VolumeState.MOUNTED_READ_ONLY }
        val pinned = allPinned(selected, folder, env.pins.favorites, deps.lookup)
        val traits = Place(unavailable != null, restricted, writable = volume != null && !readOnly)
        val commands = commandsAt(location, selected, rows, env.clipboard, traits, pinned)
        return ExplorerUiState(
            tabs = core.tabs,
            listing = listing,
            viewSetting = viewFor(location, env.global),
            global = env.global,
            volumes = env.volumes,
            clipboard = env.clipboard,
            commands = commands,
            backTarget = backOf(tab, core.shell, viewed),
            unavailable = unavailable,
            restricted = restricted,
            message = core.shell.message,
            readOnly = readOnly,
            overlay = core.shell.overlay,
            drawerOpen = core.shell.drawerOpen,
            selected = selected,
            up = upFrom(location, unavailable != null, roots, deps.lookup),
            launch = core.shell.launch?.intent,
            renaming = opsState.renaming,
            confirm = opsState.confirm,
            progressInBackground = opsState.inBackground,
            failedOp = opsState.failures.firstOrNull(),
            renameError = opsState.renameError,
            opsNotice = opsState.notice,
            renamePending = opsState.renamePending,
            viewer = viewed.viewer,
            previewPane = viewed.pane,
            previewed = viewing.previewed(viewed, rows, selected),
            home = homeData,
            favorites = env.pins.folders,
            unavailableTabs = core.unavailable,
            reveal = core.shell.reveal,
        )
    }

    /** What [handOver], [ops], [searches] and [zips] need of the view model. */
    private inner class Host : ShellHost, SearchHost {
        override fun here(): Location = this@ExplorerViewModel.here()

        override fun searchedTab(): TabState? = tabs.value.active.takeIf { it.id !in volumeTracking.unavailable.value }

        override fun navigate(l: Location) = this@ExplorerViewModel.navigate(l)

        override fun replaceHere(l: Location) = reduce(TabAction.ReplaceCurrent(tabs.value.activeId, l))

        override fun showAt(at: Location, message: UiMessage) = this@ExplorerViewModel.showAt(at, message)

        override fun showUnbound(message: UiMessage) = shell.update { it.copy(message = message, messageAt = null) }

        override fun shown(): UiMessage? = shell.value.message

        override fun dismissMessage() = this@ExplorerViewModel.dismissMessage()

        override fun refresh() = this@ExplorerViewModel.refresh()

        override fun back() = this@ExplorerViewModel.back()

        override fun refreshShowing(affected: (Location) -> Boolean) {
            val model = tabs.value
            for (tab in model.tabs.filter { affected(it.current.location) }) {
                if (tab.id == model.activeId) refresh() else loader.markStale(tab.id)
            }
        }

        override fun selectAt(at: Location, paths: List<Path>, reveal: Boolean) {
            if (here() != at) return
            reduce(TabAction.SetSelection(paths))
            if (reveal) shell.update { it.copy(reveal = paths.firstOrNull()) }
        }

        override fun openAt(at: Location, overlay: Overlay) {
            if (here() == at) shell.update { if (it.overlay == null) it.copy(overlay = overlay) else it }
        }

        override fun closeOverlay() = this@ExplorerViewModel.closeOverlay()

        override fun setLaunch(launch: PendingLaunch) = shell.update { it.copy(launch = launch) }
    }

    private data class Core(
        val tabs: TabsModel,
        val listing: Pair<TabId, ListingState>,
        val unavailable: Map<TabId, DecodedLocation.Unavailable>,
        val shell: ShellState,
    )

    private data class Env(
        val global: GlobalSettings,
        val volumes: List<Volume>,
        val clipboard: Clipboard?,
        val pins: Pins,
    )

    private data class LoadKey(val location: Location, val options: ListOptions)

    private class ScrollSpot(val location: Location, val index: Int, val offset: Int)
}
