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
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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

    /** A view model on [root] as the primary volume, by default; folders are read on the main thread. */
    private fun start(
        settings: SettingsStore = SettingsStore(null),
        volumes: FakeVolumes = FakeVolumes(FakeVolumes.primary(root)),
    ): ExplorerViewModel {
        this.volumes = volumes
        val deps = ExplorerDeps(NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined)
        vm = ExplorerViewModel(ApplicationProvider.getApplicationContext<Application>(), deps)
        settle()
        return vm
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun location() = ui.tabs.active.current.location

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    @Test
    fun startsAtPrimaryRootWithoutSession() {
        dir("Music")
        file("a.txt")
        start()
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf("Music", "a.txt"), names())
        assertNull(ui.unavailable)
    }

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
        assertNull(ui.up)
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
}
