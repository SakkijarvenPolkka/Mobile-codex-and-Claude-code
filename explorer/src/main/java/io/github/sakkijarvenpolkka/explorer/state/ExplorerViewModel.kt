/*
 * Files — the shell's view model: the tabs, the active tab's listing, and the settings and
 * volumes they depend on, combined into one UI state.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewSetting
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.ContentCount
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.countContents
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.platform.APK_MIME
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.ui.errorText
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

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
        tabs = MutableStateFlow(restored?.model ?: TabsReducer.initial(startLocation()))
        volumeTracking = VolumeTracking(deps.volumes, codec, restored?.unavailable.orEmpty())
    }

    private val activeListing: Flow<Pair<TabId, ListingState>> = tabs
        .map { it.activeId }
        .distinctUntilChanged()
        .flatMapLatest { id -> loader.state(id).map { id to it } }

    val ui: StateFlow<ExplorerUiState> = combine(
        combine(tabs, activeListing, volumeTracking.unavailable, shell) { t, l, u, s -> Core(t, l, u, s) },
        combine(settings.global, deps.volumes.volumes, clipboard.state, settings.favorites) { g, v, c, f ->
            Env(g, v, c, f)
        },
        ::build,
    ).stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        build(
            Core(
                tabs.value, tabs.value.activeId to ListingState.Loading, volumeTracking.unavailable.value, shell.value,
            ),
            Env(settings.global.value, deps.volumes.volumes.value, clipboard.state.value, settings.favorites.value),
        ),
    )

    init {
        viewModelScope.launch { deps.volumes.volumes.collect(volumeTracking::rememberRoots) }
        viewModelScope.launch { deps.volumes.events.collect(::onVolumeEvent) }
        viewModelScope.launch { activeListing.collect { (id, state) -> onListing(id, state) } }
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

    /** Goes to the parent folder; nothing from a volume root or a folder on no mounted volume. */
    fun up() {
        val tab = tabs.value.active
        val roots = deps.volumes.volumes.value.mapNotNull { it.root }
        upFrom(tab.current.location, tab.id in volumeTracking.unavailable.value, roots)?.let(::navigate)
    }

    /** Reads the active tab's folder again, keeping its rows on screen until the new ones arrive. */
    fun refresh() = ensureLoaded(refresh = true)

    /**
     * Opens [entry]: a folder here (a link is followed when it leads to a folder in storage), a
     * file in another app (the built-in viewers come later). An APK needs the user to let this
     * app install apps first, which a message offers to do.
     */
    fun open(entry: FileEntry) {
        when (entry.kind) {
            EntryKind.DIR -> navigate(Location.Folder(entry.path))
            EntryKind.SYMLINK -> goTo(entry.path.toString(), base = null, showFile = false)
            EntryKind.FILE -> handOver(listOf(entry.path)) { openWith ->
                if (entry.mime == APK_MIME && openWith.needsInstallPermission()) {
                    Prepared.Refused(OpenResult.NeedsInstallPermission)
                } else {
                    Prepared.Launch(openWith.viewIntent(entry.path))
                }
            }
            // A pipe, socket or device: no app reads it as a file.
            EntryKind.OTHER -> showError(here(), UiText.res(R.string.open_unsupported))
        }
    }

    /** A tap: toggles the item in selection mode, opens it otherwise. */
    fun onTap(entry: FileEntry) {
        if (tabs.value.active.selection.active) reduce(TabAction.Toggle(entry.path)) else open(entry)
    }

    /**
     * A long press: selects the item (entering selection mode), adds it to the selection in
     * selection mode, or opens its context menu when it is already selected.
     */
    fun onLongPress(entry: FileEntry) {
        val selection = tabs.value.active.selection
        when {
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

    /** The app came back: the active tab is read again, the others are marked out of date. */
    fun onResume() {
        // The user may have let the app install apps in the settings meanwhile.
        shell.update { it.copy(obbAllowed = installsAllowed()) }
        val model = tabs.value
        model.tabs.filter { it.id != model.activeId }.forEach { loader.markStale(it.id) }
        ensureLoaded(refresh = true)
    }

    fun dismissMessage() = shell.update { it.copy(message = null, messageAt = null) }

    /** Runs a command of the command bar or a menu; commands of later phases do nothing yet. */
    fun command(c: Command) {
        when (c) {
            Command.SELECT_ALL -> reduce(TabAction.SelectAll(listedRows().map { it.entry.path }))
            Command.SELECT_NONE -> reduce(TabAction.ClearSelection)
            Command.INVERT -> reduce(TabAction.Invert(listedRows().map { it.entry.path }))
            Command.REFRESH -> refresh()
            Command.OPTIONS -> shell.update { it.copy(overlay = Overlay.Options) }
            Command.OPEN -> selectedEntries().singleOrNull()?.let(::open)
            Command.OPEN_WITH -> selectedEntries().singleOrNull()?.let(::openWith)
            Command.SHARE -> share(selectedEntries().map { it.path })
            Command.PROPERTIES -> showProperties()
            else -> Unit
        }
    }

    /**
     * The shell started [intent], the one [ExplorerUiState.launch] held, with [result]; a failure
     * shows a message about where the active tab was when the intent was asked for.
     */
    fun launched(intent: Intent, result: OpenResult) {
        val pending = shell.value.launch?.takeIf { it.intent === intent } ?: return
        shell.update { it.copy(launch = null) }
        onOpenResult(result, pending.at)
    }

    /** What [entries] hold, counted off the main thread while the flow is collected ([countContents]). */
    fun contentsOf(entries: List<FileEntry>): Flow<ContentCount> = countContents(deps.fs, entries).flowOn(deps.io)

    /** Opens the menu of [folder]'s subfolders (nothing when it has none or is on no mounted volume). */
    fun showSubfolders(folder: Path) {
        if (deps.lookup.volumeOf(folder) == null) return
        val options = ListOptions(showHidden = settings.global.value.showHidden)
        val at = here()
        viewModelScope.launch {
            val children = try {
                withContext(deps.io) {
                    Listing.list(deps.fs, folder, options) { null }.filter { it.kind == EntryKind.DIR }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(at, errorText(e.toFsError()))
                return@launch
            }
            if (children.isNotEmpty()) shell.update { it.copy(overlay = Overlay.Subfolders(folder, children)) }
        }
    }

    /** Shows the address bar as a text box with the real path. */
    fun editAddress() = shell.update { it.copy(overlay = Overlay.AddressEdit) }

    fun closeOverlay() = shell.update { it.copy(overlay = null) }

    /** Opens or closes the navigation drawer (narrow windows). */
    fun setDrawerOpen(open: Boolean) = shell.update { it.copy(drawerOpen = open) }

    /** The system Back: closes what is open, clears the selection, closes the drawer or goes back, in that order. */
    fun systemBack() {
        val tab = tabs.value.active
        val state = shell.value
        when (backTarget(state.overlay != null, tab.selection.active, state.drawerOpen, tab.back.isNotEmpty())) {
            BackTarget.OVERLAY -> closeOverlay()
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
        }
        val stayed = { id: TabId -> after.locationOf(id) == before.locationOf(id) }
        volumeTracking.retain(stayed)
        scrollSpots.keys.retainAll(stayed)
        // A tab put where its volume is mounted now (any tab, not only this one) leaves what is open alone.
        val keepOverlay = a is TabAction.ReplaceCurrent
        shell.update {
            val here = it.at(after.active.current.location)
            if (keepOverlay) here else here.copy(overlay = null)
        }
        settings.saveSession(after, codec)
    }

    private fun TabAction.changesSelectionOnly(): Boolean = when (this) {
        is TabAction.Select, is TabAction.Toggle, is TabAction.RangeTo, is TabAction.SelectAll,
        TabAction.ClearSelection, is TabAction.Invert, is TabAction.Prune, is TabAction.SetSelection,
        -> true
        else -> false
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
     * Reads the active tab's folder when it is not read for its current options yet, or always
     * with [refresh]; a folder on no mounted volume is never read (see [placeOffVolume]).
     */
    private fun ensureLoaded(refresh: Boolean = false) {
        val tab = tabs.value.active
        val folder = (tab.current.location as? Location.Folder)?.path ?: return
        if (tab.id in volumeTracking.unavailable.value) return
        if (deps.lookup.volumeOf(folder) == null) return placeOffVolume(tab.id, folder)
        val global = settings.global.value
        val options = ListOptions(global.showHidden, viewFor(tab.current.location, global).sort)
        val key = LoadKey(folder, options)
        val previous = loadedFor[tab.id]
        val stale = loader.isStale(tab.id)
        if (!refresh && !stale && key == previous) return
        loadedFor[tab.id] = key
        loader.load(tab.id, folder, options, refresh = refresh || stale || previous?.folder == folder)
    }

    private fun onListing(id: TabId, state: ListingState) {
        if (id != tabs.value.activeId) return
        when (state) {
            is ListingState.Loaded -> if (!state.refreshing) {
                reduce(TabAction.Prune(state.rows.map { it.entry.path }))
                // The menu of an item that is gone would stay open unseen.
                val menu = shell.value.overlay as? Overlay.ContextMenu
                if (menu != null && menu.entry.path !in tabs.value.active.selection.items) closeOverlay()
            }
            is ListingState.Error -> if (state.error == FsError.AccessDenied && onRawMount()) offerRestart()
            else -> Unit
        }
    }

    /**
     * Whether the active tab's folder is on a volume outside `/storage` (a USB drive at
     * `/mnt/media_rw/<uuid>`), which this process cannot read when it started before it had
     * all files access.
     */
    private fun onRawMount(): Boolean {
        val folder = (tabs.value.active.current.location as? Location.Folder)?.path ?: return false
        return deps.lookup.volumeOf(folder)?.root?.startsWith(MEDIA_RW) == true
    }

    private fun offerRestart() {
        val restart = UiText.res(R.string.restart) to ::restartApp
        showAt(here(), UiMessage(UiText.res(R.string.usb_restart), InfoSeverity.WARNING, restart))
    }

    /** Starts the app again in a new process, which has all files access from its start. */
    private fun restartApp() {
        val app = getApplication<Application>()
        val component = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component ?: return
        app.startActivity(Intent.makeRestartActivityTask(component))
        Process.killProcess(Process.myPid())
    }

    /**
     * Resolves [text] (against [base] when relative) off the main thread and goes to the folder,
     * or with [showFile] to a file's folder with the file selected.
     */
    private fun goTo(text: String, base: Path?, showFile: Boolean) {
        val at = here()
        viewModelScope.launch {
            val target = try {
                withContext(deps.io) { resolve(text, base) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resolved.Failed(e.toFsError())
            }
            when (target) {
                is Resolved.Folder -> {
                    closeOverlay()
                    navigate(Location.Folder(target.path))
                }
                is Resolved.File -> if (showFile) {
                    closeOverlay()
                    navigate(Location.Folder(target.path.parent))
                    reduce(TabAction.Select(target.path))
                }
                Resolved.Outside -> showError(at, UiText.res(R.string.outside_storage))
                is Resolved.Failed -> showError(at, errorText(target.error))
            }
        }
    }

    private fun resolve(text: String, base: Path?): Resolved {
        val path = try {
            Paths.get(text)
        } catch (e: InvalidPathException) {
            return Resolved.Failed(FsError.NotFound)
        }
        val absolute = if (path.isAbsolute) path else base?.resolve(path) ?: return Resolved.Failed(FsError.NotFound)
        val real = deps.fs.realPath(absolute)
        if (deps.lookup.volumeOf(real) == null) return Resolved.Outside
        return if (deps.fs.attributes(real).isDirectory) Resolved.Folder(real) else Resolved.File(real)
    }

    private fun here(): Location = tabs.value.active.current.location

    private fun showError(at: Location, text: UiText) = showAt(at, UiMessage(text, InfoSeverity.ERROR))

    /**
     * Shows [message] about [at], where the active tab was when the user asked for what it
     * answers: not at all when the tab has left it since, and it goes when the tab leaves it.
     */
    private fun showAt(at: Location, message: UiMessage) {
        if (here() == at) shell.update { it.copy(message = message, messageAt = at) }
    }

    /** "Open with": the system chooser for the file [entry], which asks every time. */
    private fun openWith(entry: FileEntry) {
        if (entry.kind != EntryKind.FILE) return
        val title = getApplication<Application>().getString(R.string.open_with_title)
        handOver(listOf(entry.path)) { Prepared.Launch(it.chooserIntent(entry.path, title)) }
    }

    /** Shares the files [paths] through the system share sheet. */
    private fun share(paths: List<Path>) {
        if (paths.isEmpty()) return
        handOver(paths) { Prepared.Launch(Intent.createChooser(it.shareIntent(paths), null)) }
    }

    /**
     * Hands [files] to another app with the intent [prepare] makes, off the main thread, for the
     * shell to start ([ExplorerUiState.launch]). A file gone since its folder was read shows "not
     * found" and the folder is read again; one the FileProvider does not serve cannot be opened.
     * Nothing is handed over without [ExplorerDeps.openWith].
     */
    private fun handOver(files: List<Path>, prepare: (OpenWith) -> Prepared) {
        val openWith = deps.openWith ?: return
        val at = here()
        viewModelScope.launch {
            val prepared = try {
                withContext(deps.io) {
                    if (files.all(deps.fs::exists)) prepare(openWith) else Prepared.Refused(OpenResult.NotFound)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toFsError()
                if (error == FsError.Unsupported) Prepared.Refused(OpenResult.Unsupported) else Prepared.Failed(error)
            }
            when (prepared) {
                is Prepared.Launch -> shell.update { it.copy(launch = PendingLaunch(prepared.intent, at)) }
                is Prepared.Refused -> onOpenResult(prepared.result, at)
                is Prepared.Failed -> showError(at, errorText(prepared.error))
            }
        }
    }

    /** What [result] of handing a file to another app, asked for at [at], tells the user. */
    private fun onOpenResult(result: OpenResult, at: Location) {
        when (result) {
            OpenResult.Started -> Unit
            OpenResult.NoApp -> showError(at, UiText.res(R.string.open_no_app))
            OpenResult.Unsupported -> showError(at, UiText.res(R.string.open_unsupported))
            OpenResult.NotFound -> {
                showError(at, UiText.res(R.string.error_not_found))
                if (here() == at) refresh()
            }
            OpenResult.NeedsInstallPermission -> {
                val openWith = deps.openWith ?: return
                val allow = UiText.res(R.string.permission_button) to {
                    dismissMessage()
                    shell.update { it.copy(launch = PendingLaunch(openWith.installPermissionIntent(), at)) }
                }
                showAt(at, UiMessage(UiText.res(R.string.install_permission), InfoSeverity.WARNING, allow))
            }
        }
    }

    /** The properties of the selection, else of the current folder (read off the main thread). */
    private fun showProperties() {
        val selected = selectedEntries()
        if (selected.isNotEmpty()) return shell.update { it.copy(overlay = Overlay.Properties(selected)) }
        val at = here()
        val folder = (at as? Location.Folder)?.path ?: return
        viewModelScope.launch {
            val entry = try {
                withContext(deps.io) { Listing.entry(deps.fs, folder, deps.mime::mimeOf) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(at, errorText(e.toFsError()))
                return@launch
            }
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
        val change = volumeTracking.onVolumeEvent(e, tabs.value)
        change.ejected.forEach(loadedFor::remove)
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

    /**
     * Where Up goes from [location]: nowhere while it is [unavailable] or on no mounted volume, so
     * that it never leaves a volume.
     */
    private fun upFrom(location: Location, unavailable: Boolean, roots: List<Path>): Location? = when {
        unavailable -> null
        location is Location.Folder && deps.lookup.volumeOf(location.path) == null -> null
        else -> upTarget(location, roots, homeEnabled = false)
    }

    private fun startLocation(): Location {
        val mounted = deps.volumes.volumes.value.filter { it.root != null }
        val root = (mounted.firstOrNull { it.isPrimary } ?: mounted.firstOrNull())?.root
        return root?.let { Location.Folder(it) } ?: Location.Home
    }

    /** The view of [location]'s folder, or the default one. */
    private fun viewFor(location: Location, global: GlobalSettings): ViewSetting =
        codec.encode(location)?.let(settings::viewFor) ?: global.defaultView

    private fun build(core: Core, env: Env): ExplorerUiState {
        val tab = core.tabs.active
        val location = tab.current.location
        val unavailable = core.unavailable[tab.id]
        val (listingTab, shown) = core.listing
        val listing = if (listingTab == tab.id && unavailable == null) shown else ListingState.Loading
        val rows = listing.rows()
        val selected = rows.mapNotNull { row -> row.entry.takeIf { it.path in tab.selection.items } }
        val folder = (location as? Location.Folder)?.path?.takeIf { unavailable == null }
        val volume = folder?.let(deps.lookup::volumeOf)
        val restricted = folder != null && volume != null &&
            isRestrictedAppDataPath(volume.root, folder, obbAllowed = core.shell.obbAllowed)
        val roots = env.volumes.mapNotNull { it.root }
        val commands = if (unavailable != null) {
            setOf(Command.REFRESH, Command.OPTIONS)
        } else {
            commandsFor(
                CommandContext(
                    kind = kindOf(location),
                    selection = selected,
                    clipboard = env.clipboard,
                    restricted = restricted,
                    volumeWritable = volume != null &&
                        env.volumes.none { it.uuid == volume.uuid && it.state == VolumeState.MOUNTED_READ_ONLY },
                    pinned = pinned(selected, folder, env.favorites),
                    binEmpty = true,
                    hasEntries = rows.isNotEmpty(),
                ),
            )
        }
        return ExplorerUiState(
            tabs = core.tabs,
            listing = listing,
            viewSetting = viewFor(location, env.global),
            global = env.global,
            volumes = env.volumes,
            clipboard = env.clipboard,
            commands = commands,
            backTarget = backTarget(
                overlayOpen = core.shell.overlay != null,
                selectionActive = tab.selection.active,
                drawerOpen = core.shell.drawerOpen,
                canGoBack = tab.back.isNotEmpty(),
            ),
            unavailable = unavailable,
            restricted = restricted,
            message = core.shell.message,
            overlay = core.shell.overlay,
            drawerOpen = core.shell.drawerOpen,
            selected = selected,
            up = upFrom(location, unavailable != null, roots),
            launch = core.shell.launch?.intent,
        )
    }

    /** Whether the folders Pin and Unpin act on (the selection, else [folder]) are all favorites. */
    private fun pinned(selected: List<FileEntry>, folder: Path?, favorites: List<Favorite>): Boolean {
        val targets = if (selected.isEmpty()) listOfNotNull(folder) else selected.map { it.path }
        return targets.isNotEmpty() && targets.all { path ->
            val volume = deps.lookup.volumeOf(path)
            val rel = volume?.root?.relativize(path)?.toString()
            volume != null && favorites.any { it.uuid == volume.uuid && it.rel == rel }
        }
    }

    private fun kindOf(location: Location): LocationKind = when (location) {
        Location.Home -> LocationKind.HOME
        is Location.Folder -> LocationKind.FOLDER
        Location.RecycleBin -> LocationKind.BIN
        is Location.SearchResults -> LocationKind.SEARCH
    }

    /**
     * What is open over the shell, the message and the location it is about ([messageAt], null
     * for one that stays until dismissed), a counter that moves whenever a folder's view changes,
     * the intent for the shell to start, and whether `Android/obb` is open to the app.
     */
    private data class ShellState(
        val overlay: Overlay? = null,
        val drawerOpen: Boolean = false,
        val message: UiMessage? = null,
        val messageAt: Location? = null,
        val viewRevision: Int = 0,
        val launch: PendingLaunch? = null,
        val obbAllowed: Boolean = false,
    ) {
        /** This state with the active tab at [location]: without a message about another place. */
        fun at(location: Location): ShellState =
            if (messageAt == null || messageAt == location) this else copy(message = null, messageAt = null)
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
        val favorites: List<Favorite>,
    )

    private data class LoadKey(val folder: Path, val options: ListOptions)

    /** An [intent] for the shell to start, asked for while the active tab was [at]. */
    private class PendingLaunch(val intent: Intent, val at: Location)

    /** What handing files to another app comes to before anything is started. */
    private sealed interface Prepared {
        class Launch(val intent: Intent) : Prepared
        class Refused(val result: OpenResult) : Prepared
        class Failed(val error: FsError) : Prepared
    }

    private class ScrollSpot(val location: Location, val index: Int, val offset: Int)

    private sealed interface Resolved {
        data class Folder(val path: Path) : Resolved
        data class File(val path: Path) : Resolved
        data object Outside : Resolved
        data class Failed(val error: FsError) : Resolved
    }

    private companion object {
        val MEDIA_RW: Path = Paths.get("/mnt/media_rw")
    }
}
