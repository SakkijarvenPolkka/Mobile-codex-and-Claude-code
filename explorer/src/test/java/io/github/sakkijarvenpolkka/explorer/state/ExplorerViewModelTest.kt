// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.platform.storageUsage
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplorerViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    /**
     * A view model on [root] as the primary volume, by default, started there unless [settings] have a
     * session; folders are read on the main thread.
     */
    private fun start(
        settings: SettingsStore = SettingsStore(null),
        volumes: FakeVolumes = FakeVolumes(FakeVolumes.primary(root)),
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        opsNotice: StateFlow<OpsNotice?> = MutableStateFlow(null),
        clearOpsNotice: () -> Unit = {},
        builtInViewers: Boolean = false,
        usage: (Path) -> Pair<Long, Long>? = ::storageUsage,
    ): ExplorerViewModel {
        this.volumes = volumes
        settings.startAt(root, volumes)
        val deps = ExplorerDeps(
            NioFsBackend, settings, volumes, volumes, MimeResolver(), io,
            opsNotice = opsNotice, clearOpsNotice = clearOpsNotice, builtInViewers = builtInViewers, usage = usage,
        )
        vm = ExplorerViewModel(ApplicationProvider.getApplicationContext<Application>(), deps)
        settle()
        return vm
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs what is queued on [io], then on the main thread, a few rounds over: each hands work to the other. */
    private fun drain(io: TestDispatcher) {
        repeat(5) {
            io.scheduler.advanceUntilIdle()
            settle()
        }
    }

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun location() = ui.tabs.active.current.location

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    @Test
    fun tapFolderNavigates() {
        dir("Music")
        file("Music/song.mp3")
        file("a.txt")
        start()
        vm.onTap(entry("a.txt"))
        settle()
        assertEquals(Location.Folder(root), location())
        assertFalse(ui.tabs.active.selection.active)

        vm.onTap(entry("Music"))
        settle()
        assertEquals(Location.Folder(root.resolve("Music")), location())
        assertEquals(listOf("song.mp3"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
    }

    @Test
    fun typedPathIsCanonicalized() {
        dir("a")
        start()
        vm.typedPath("$root/a/../a")
        settle()
        assertEquals(Location.Folder(root.resolve("a")), location())
        assertNull(ui.message)

        vm.typedPath("/proc")
        settle()
        assertEquals(Location.Folder(root.resolve("a")), location())
        assertEquals(UiText.res(R.string.outside_storage), ui.message?.text)
    }

    @Test
    fun lateMessageIsDroppedOnceTheTabHasMoved() {
        dir("a")
        val io = StandardTestDispatcher()
        start(io = io)
        drain(io)
        vm.typedPath("/proc")
        // The path is still being resolved when the tab moves on.
        vm.navigate(Location.Folder(root.resolve("a")))
        drain(io)
        assertEquals(Location.Folder(root.resolve("a")), location())
        assertNull(ui.message)

        vm.typedPath("/proc")
        drain(io)
        assertEquals(UiText.res(R.string.outside_storage), ui.message?.text)
    }

    @Test
    fun longPressSelectsThenSecondLongPressOpensMenuFlag() {
        file("a.txt")
        file("b.txt")
        start()
        val a = entry("a.txt")
        vm.onLongPress(a)
        settle()
        assertEquals(setOf(a.path), ui.tabs.active.selection.items)
        assertNull(ui.contextMenuFor)

        vm.onLongPress(a)
        settle()
        assertEquals(a, ui.contextMenuFor)
        assertEquals(BackTarget.OVERLAY, ui.backTarget)
        assertEquals(setOf(a.path), ui.tabs.active.selection.items)
    }

    @Test
    fun selectionPrunedAfterRefresh() {
        file("a.txt")
        file("b.txt")
        start()
        vm.onLongPress(entry("a.txt"))
        vm.onTap(entry("b.txt"))
        settle()
        assertEquals(2, ui.tabs.active.selection.items.size)

        Files.delete(root.resolve("b.txt"))
        vm.refresh()
        settle()
        assertEquals(listOf("a.txt"), names())
        assertEquals(setOf(root.resolve("a.txt")), ui.tabs.active.selection.items)

        // The context menu of an item that is gone closes with it.
        vm.onLongPress(entry("a.txt"))
        settle()
        assertEquals(entry("a.txt"), ui.contextMenuFor)
        Files.delete(root.resolve("a.txt"))
        vm.refresh()
        settle()
        assertTrue(ui.rows.isEmpty())
        assertFalse(ui.tabs.active.selection.active)
        assertNull(ui.overlay)
    }

    @Test
    fun perFolderViewRemembered() {
        dir("a")
        dir("b")
        val settings = SettingsStore(null)
        start(settings)
        vm.navigate(Location.Folder(root.resolve("a")))
        settle()
        assertEquals(ViewMode.DETAILS, ui.viewSetting.mode)
        vm.setView(ui.viewSetting.copy(mode = ViewMode.LIST))
        settle()
        assertEquals(ViewMode.LIST, ui.viewSetting.mode)

        vm.navigate(Location.Folder(root.resolve("b")))
        settle()
        // A folder seen for the first time takes the view chosen last.
        assertEquals(ViewMode.LIST, ui.viewSetting.mode)
        vm.setView(ui.viewSetting.copy(mode = ViewMode.TILES))
        settle()

        vm.back()
        settle()
        assertEquals(Location.Folder(root.resolve("a")), location())
        assertEquals(ViewMode.LIST, ui.viewSetting.mode)
        vm.forward()
        settle()
        assertEquals(ViewMode.TILES, ui.viewSetting.mode)

        // Kept in the settings under the folder's location tag.
        assertEquals(ViewMode.LIST, settings.viewFor("folder:primary:a")?.mode)
        assertEquals(ViewMode.TILES, settings.viewFor("folder:primary:b")?.mode)
    }

    @Test
    fun restrictedFlagUnderAndroidData() {
        dir("Android/data/com.example")
        dir("Android/media")
        start()
        vm.navigate(Location.Folder(root.resolve("Android/data")))
        settle()
        assertTrue(ui.restricted)
        assertTrue(Command.NEW_FOLDER !in ui.commands)

        vm.onTap(entry("com.example"))
        settle()
        assertEquals(Location.Folder(root.resolve("Android/data/com.example")), location())
        assertTrue(ui.restricted)

        vm.navigate(Location.Folder(root.resolve("Android")))
        settle()
        assertFalse(ui.restricted)
        assertTrue(Command.NEW_FOLDER in ui.commands)
    }

    @Test
    fun navigationIsSavedForTheNextStart() {
        dir("Music")
        val settings = SettingsStore(null)
        start(settings)
        vm.navigate(Location.Folder(root.resolve("Music")))
        settle()
        vm.viewModelScope.cancel()

        start(settings)
        assertEquals(Location.Folder(root.resolve("Music")), location())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
    }

    @Test
    fun restoredTabOnUnmountedVolumeMovesToWhereItMounts() {
        val usb = folder.newFolder("usb").toPath().toRealPath()
        Files.createDirectory(usb.resolve("docs"))
        Files.createFile(usb.resolve("docs/a.txt"))
        val settings = SettingsStore(null)
        val mounted = FakeVolumes(FakeVolumes.primary(root), FakeVolumes.removable("USB1", "USB drive", usb))
        val saved = TabsReducer.initial(Location.Folder(usb.resolve("docs")))
        settings.saveSession(saved, VolumeLocationCodec { mounted.volumes.value })

        // Started while the drive is out: the tab keeps its place at the usual mount point.
        val out = FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        start(settings, FakeVolumes(FakeVolumes.primary(root), out))
        assertEquals(DecodedLocation.Unavailable("USB1", "docs"), ui.unavailable)
        assertEquals(Location.Folder(Paths.get("/storage/USB1/docs")), location())
        assertNull(ui.up)

        // It mounts somewhere else than the usual place; the tab goes there without a history entry.
        volumes.update("USB1") { it.copy(root = usb, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted("USB1"))
        settle()
        assertNull(ui.unavailable)
        assertEquals(Location.Folder(usb.resolve("docs")), location())
        assertTrue(ui.tabs.active.back.isEmpty())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun restoredTabOnUnmountedVolumeStaysUnavailableThroughCrumbAndBack() {
        val usb = folder.newFolder("usb").toPath().toRealPath()
        Files.createDirectories(usb.resolve("docs/old"))
        val settings = SettingsStore(null)
        val mounted = FakeVolumes(FakeVolumes.primary(root), FakeVolumes.removable("USB1", "USB drive", usb))
        val saved = listOf("docs", "docs/old").fold(TabsReducer.initial(Location.Folder(usb))) { model, rel ->
            TabsReducer.reduce(model, TabAction.Navigate(Location.Folder(usb.resolve(rel))))
        }
        settings.saveSession(saved, VolumeLocationCodec { mounted.volumes.value })

        // Started while the drive is out; it was never mounted while this process ran.
        val out = FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        start(settings, FakeVolumes(FakeVolumes.primary(root), out))
        val guess = Paths.get("/storage/USB1")
        assertEquals(DecodedLocation.Unavailable("USB1", "docs/old"), ui.unavailable)

        // The drive's breadcrumb segment: still the unavailable drive, with no Up above it.
        vm.navigate(Location.Folder(guess))
        settle()
        assertEquals(DecodedLocation.Unavailable("USB1", ""), ui.unavailable)
        assertNull(ui.up)
        vm.up()
        settle()
        assertEquals(Location.Folder(guess), location())
        // Its chevron lists nothing: the drive is not read.
        vm.showSubfolders(guess)
        settle()
        assertNull(ui.overlay)
        assertNull(ui.message)

        // Back to the place left, then to a restored entry: unavailable as well.
        vm.back()
        settle()
        assertEquals(DecodedLocation.Unavailable("USB1", "docs/old"), ui.unavailable)
        vm.back()
        settle()
        assertEquals(Location.Folder(guess.resolve("docs")), location())
        assertEquals(DecodedLocation.Unavailable("USB1", "docs"), ui.unavailable)
        assertNull(ui.up)

        // Mounted elsewhere: Back to a restored entry goes to that folder where the drive is now.
        volumes.update("USB1") { it.copy(root = usb, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted("USB1"))
        settle()
        assertEquals(Location.Folder(usb.resolve("docs")), location())
        vm.back()
        settle()
        assertEquals(Location.Folder(usb), location())
        assertNull(ui.unavailable)
        assertEquals(listOf("docs"), names())
        // From the root of the drive, mounted, Up goes Home.
        assertEquals(Location.Home, ui.up)
    }

    @Test
    fun homeTabShowsWhatChangedWhileAnotherTabWasActive() {
        dir("a")
        file("a/notes.txt")
        val usb = folder.newFolder("usb").toPath().toRealPath()
        val out = FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        start(
            volumes = FakeVolumes(FakeVolumes.primary(root), out),
            builtInViewers = true,
            usage = { if (it == usb) 8L to 2L else null },
        )
        vm.navigate(Location.Home)
        settle()
        val homeTab = ui.tabs.activeId
        assertEquals(HomeData(emptyList(), emptyList(), emptyList(), 0L), ui.home)

        // In a folder tab meanwhile: a folder pinned, a file opened, a drive mounted.
        vm.newTab()
        vm.navigate(Location.Folder(root.resolve("a")))
        settle()
        vm.pin(listOf(root.resolve("a")))
        vm.onTap(entry("notes.txt"))
        settle()
        assertEquals(root.resolve("a/notes.txt"), ui.viewer?.entry?.path)
        vm.viewing.close()
        volumes.update("USB1") { it.copy(root = usb, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted("USB1"))
        settle()

        // Back in the tab at Home, read before: it shows all of that.
        vm.activate(homeTab)
        settle()
        val home = ui.home!!
        assertEquals(listOf(QuickItem(root.resolve("a"), "a", pinned = true)), home.quickAccess)
        assertEquals(listOf(root.resolve("a/notes.txt")), home.recents.map { it.path })
        assertEquals(listOf(DriveUsage(volumes.volumes.value[1], 8L, 2L)), home.drives)
    }

    @Test
    fun locationMessageClearedOnNavigation() {
        dir("a")
        start()
        vm.typedPath("/proc")
        settle()
        assertEquals(UiText.res(R.string.outside_storage), ui.message?.text)
        // Staying in the folder keeps it.
        vm.refresh()
        vm.navigate(Location.Folder(root))
        settle()
        assertEquals(UiText.res(R.string.outside_storage), ui.message?.text)

        vm.navigate(Location.Folder(root.resolve("a")))
        settle()
        assertNull(ui.message)

        // The same for a path that is not there, and for Back.
        vm.typedPath("$root/missing")
        settle()
        assertEquals(UiText.res(R.string.error_not_found), ui.message?.text)
        vm.back()
        settle()
        assertEquals(Location.Folder(root), location())
        assertNull(ui.message)
    }

    @Test
    fun backAfterUnavailableEntryReReadsFolder() {
        val usb = folder.newFolder("usb").toPath().toRealPath()
        Files.createDirectory(usb.resolve("docs"))
        file("a.txt")
        val settings = SettingsStore(null)
        val mounted = FakeVolumes(FakeVolumes.primary(root), FakeVolumes.removable("USB1", "USB drive", usb))
        val saved = TabsReducer.initial(Location.Folder(root))
            .let { TabsReducer.reduce(it, TabAction.Navigate(Location.Folder(usb.resolve("docs")))) }
            .let { TabsReducer.reduce(it, TabAction.Back) }
        settings.saveSession(saved, VolumeLocationCodec { mounted.volumes.value })

        // Started while the drive is out: the folder is read, and Forward reaches the restored entry on the drive.
        val out = FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        start(settings, FakeVolumes(FakeVolumes.primary(root), out))
        assertEquals(listOf("a.txt"), names())
        vm.forward()
        settle()
        assertEquals(DecodedLocation.Unavailable("USB1", "docs"), ui.unavailable)

        // Back to the folder reads it again.
        file("b.txt")
        vm.back()
        settle()
        assertEquals(Location.Folder(root), location())
        assertNull(ui.unavailable)
        assertEquals(listOf("a.txt", "b.txt"), names())
    }

    @Test
    fun propertiesShowTheSelectionOrElseTheFolder() {
        dir("a")
        file("b.txt")
        file("c.txt")
        start()
        vm.command(Command.PROPERTIES)
        settle()
        val folder = (ui.overlay as Overlay.Properties).entries.single()
        assertEquals(root, folder.path)
        assertEquals(EntryKind.DIR, folder.kind)

        vm.closeOverlay()
        vm.onLongPress(entry("c.txt"))
        vm.onTap(entry("b.txt"))
        settle()
        vm.command(Command.PROPERTIES)
        settle()
        // In listing order.
        assertEquals(listOf("b.txt", "c.txt"), (ui.overlay as Overlay.Properties).entries.map { it.name })
        assertEquals(BackTarget.OVERLAY, ui.backTarget)
    }

    @Test
    fun folderOnNoVolumeIsNotRead() {
        val elsewhere = folder.newFolder("elsewhere").toPath().toRealPath()
        Files.createFile(elsewhere.resolve("a.txt"))
        start()
        vm.navigate(Location.Folder(elsewhere))
        settle()
        assertEquals(ListingState.Error(FsError.NotFound), ui.listing)
        assertNull(ui.up)
    }

    @Test
    fun refusedForegroundServiceAsksToKeepTheAppOpen() {
        dir("a")
        val notice = MutableStateFlow<OpsNotice?>(null)
        start(opsNotice = notice)

        notice.value = OpsNotice.ForegroundRefused
        settle()

        val keepOpen = UiMessage(UiText.res(R.string.fgs_refused), MessageSeverity.WARNING)
        // In a bar of its own: the message slot stays free.
        assertEquals(keepOpen, ui.opsNotice)
        assertNull(ui.message)
        // About no place: it stays until it is dismissed.
        vm.navigate(Location.Folder(root.resolve("a")))
        settle()
        assertEquals(keepOpen, ui.opsNotice)
        vm.dismissOpsNotice()
        settle()
        assertNull(ui.opsNotice)
    }

    @Test
    fun opsNoticeShownAndCleared() {
        // Refused before there was a view model: the app kept it.
        val notice = MutableStateFlow<OpsNotice?>(OpsNotice.ForegroundRefused)
        var cleared = 0
        start(opsNotice = notice, clearOpsNotice = { cleared++ })

        assertEquals(UiMessage(UiText.res(R.string.fgs_refused), MessageSeverity.WARNING), ui.opsNotice)
        vm.dismissOpsNotice()
        settle()

        assertNull(ui.opsNotice)
        // The app forgets it, or the next view model would show it again.
        assertEquals(1, cleared)
    }

    @Test
    fun opsNoticeOutlivesOtherMessagesAndDismissedIsForgotten() {
        val notice = MutableStateFlow<OpsNotice?>(null)
        start(opsNotice = notice, clearOpsNotice = { notice.value = null })
        val keepOpen = UiMessage(UiText.res(R.string.fgs_refused), MessageSeverity.WARNING)
        notice.value = OpsNotice.ForegroundRefused
        settle()
        assertEquals(keepOpen, ui.opsNotice)

        // Another message shows in its own slot: the notice stays, and the app keeps it ...
        vm.typedPath("/proc")
        settle()
        assertEquals(UiText.res(R.string.outside_storage), ui.message?.text)
        assertEquals(keepOpen, ui.opsNotice)
        assertEquals(OpsNotice.ForegroundRefused, notice.value)

        // ... until it is dismissed, so that the next refusal is told again.
        vm.dismissOpsNotice()
        settle()
        assertNull(notice.value)
        assertNull(ui.opsNotice)
        notice.value = OpsNotice.ForegroundRefused
        settle()
        assertEquals(keepOpen, ui.opsNotice)
    }
}
