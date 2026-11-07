// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
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

/** Picking files for another app (pick spec §2.2, §2.3): the real view model, on temporary volumes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickModeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var codec: VolumeLocationCodec
    private lateinit var vm: ExplorerViewModel
    private val made = mutableListOf<ExplorerViewModel>()

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        volumes = FakeVolumes(FakeVolumes.primary(root))
        codec = VolumeLocationCodec { volumes.volumes.value }
    }

    @After
    fun tearDown() = made.forEach { it.viewModelScope.cancel() }

    /** A view model picking files of [types] (several of them, if [multiple]) for another app; folders are read at once. */
    private fun pick(vararg types: String, multiple: Boolean = false): ExplorerViewModel {
        val request = PickRequest(types.toList(), multiple)
        val deps = ExplorerDeps(NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined, pick = request)
        vm = ExplorerViewModel(app, deps).also { made += it }
        settle()
        return vm
    }

    /** [dir] as the folder files were last picked from, where picking starts. */
    private fun lastPickedIn(dir: Path) {
        settings.pickLast = codec.encode(Location.Folder(dir))
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun location() = ui.location

    private fun outcome() = ui.pick?.outcome

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    private fun tap(name: String) {
        vm.onTap(entry(name))
        settle()
    }

    @Test
    fun sessionIsNeitherReadNorWritten() {
        val download = dir("Download")
        dir("Download/old")
        // The app's own session: two tabs.
        val tabs = TabsReducer.reduce(TabsReducer.initial(Location.Folder(root)), TabAction.NewTab(Location.Home))
        settings.saveSession(tabs, codec)
        val saved = settings.loadSession(codec)
        assertEquals(2, saved?.model?.tabs?.size)

        pick("*/*")
        assertEquals(1, ui.tabs.tabs.size)
        assertEquals(Location.Home, location())
        vm.navigate(Location.Folder(download))
        settle()
        tap("old")
        vm.back()
        settle()

        assertEquals(Location.Folder(download), location())
        assertEquals(1, ui.tabs.tabs.size)
        assertEquals(saved, settings.loadSession(codec))
    }

    @Test
    fun startsAtLastPickFolderElseHome() {
        val dcim = dir("DCIM")
        file("DCIM/a.jpg")
        val sdRoot = folder.newFolder("sd").toPath().toRealPath()
        val sdPhotos = Files.createDirectory(sdRoot.resolve("Photos"))
        volumes.volumes.value += FakeVolumes.removable("1A2B-3C4D", "SD card", sdRoot)

        // Nothing picked yet.
        pick("image/*")
        assertEquals(Location.Home, location())

        lastPickedIn(dcim)
        pick("image/*")
        assertEquals(Location.Folder(dcim), location())
        assertEquals(listOf("a.jpg"), names())

        // The folder of the last pick is on a card taken out since.
        lastPickedIn(sdPhotos)
        volumes.update("1A2B-3C4D") { it.copy(root = null, state = VolumeState.UNMOUNTED) }
        pick("image/*")
        assertEquals(Location.Home, location())

        // Not a folder's tag.
        settings.pickLast = "folder:primary:../outside"
        pick("image/*")
        assertEquals(Location.Home, location())
    }

    @Test
    fun singleTapOnMatchingFileChooses() {
        val dcim = dir("DCIM")
        val a = file("DCIM/a.jpg")
        file("DCIM/doc.pdf")
        lastPickedIn(root)
        pick("image/*")
        tap("DCIM")
        assertNull(outcome())

        tap("a.jpg")
        assertEquals(PickOutcome.Chosen(listOf(a)), outcome())
        assertEquals(codec.encode(Location.Folder(dcim)), settings.pickLast)
    }

    @Test
    fun tapOnFolderNavigates() {
        val dcim = dir("DCIM")
        file("DCIM/b.jpg")
        file("a.jpg")
        lastPickedIn(root)
        pick("image/*", multiple = true)
        // A folder is gone into even with a file selected, which a tap toggles.
        tap("a.jpg")
        assertEquals(1, ui.pick?.chosen)

        tap("DCIM")
        assertEquals(Location.Folder(dcim), location())
        assertEquals(listOf("b.jpg"), names())
        assertNull(outcome())
    }

    @Test
    fun tapOnNonMatchingFileDoesNothing() {
        file("a.jpg")
        file("doc.pdf")
        lastPickedIn(root)
        pick("image/*")
        tap("doc.pdf")
        assertNull(outcome())
        assertEquals(Location.Folder(root), location())
        assertNull(ui.message)
        // Nor does a long press, which would select it.
        vm.onLongPress(entry("doc.pdf"))
        settle()
        assertTrue(ui.selected.isEmpty())

        pick("image/*", multiple = true)
        tap("doc.pdf")
        assertTrue(ui.selected.isEmpty())
        assertNull(outcome())
    }

    @Test
    fun multipleTapsToggleAndChooseReturnsListingOrder() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        file("c.jpg")
        lastPickedIn(root)
        pick("image/*", multiple = true)
        tap("b.jpg")
        tap("a.jpg")
        tap("c.jpg")
        tap("c.jpg")
        assertEquals(2, ui.pick?.chosen)
        assertNull(outcome())

        vm.choose()
        settle()
        assertEquals(PickOutcome.Chosen(listOf(a, b)), outcome())
    }

    @Test
    fun chooseVanishedFileShowsNotFound() {
        file("a.jpg")
        val b = file("b.jpg")
        lastPickedIn(root)
        pick("image/*", multiple = true)
        tap("a.jpg")
        tap("b.jpg")
        // Gone since the folder was read.
        Files.delete(b)

        vm.choose()
        settle()
        assertNull(outcome())
        assertEquals(UiText.res(R.string.error_not_found), ui.message?.text)
        // Read again: what is gone is neither listed nor selected any more.
        assertEquals(listOf("a.jpg"), names())
        assertEquals(1, ui.pick?.chosen)
    }

    @Test
    fun tooManyRefused() {
        repeat(PickRequest.MAX_RESULTS + 1) { file("img$it.jpg") }
        lastPickedIn(root)
        pick("image/*", multiple = true)
        vm.command(Command.SELECT_ALL)
        settle()
        assertEquals(501, ui.pick?.chosen)

        vm.choose()
        settle()
        assertNull(outcome())
        assertEquals(UiText.res(R.string.pick_too_many), ui.message?.text)

        // One fewer is not too many.
        tap("img0.jpg")
        vm.choose()
        settle()
        assertEquals(500, (outcome() as? PickOutcome.Chosen)?.paths?.size)
    }

    @Test
    fun commandsAreReadOnly() {
        file("a.jpg")
        dir("DCIM")
        lastPickedIn(root)
        val kept = setOf(
            Command.OPEN, Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.REFRESH,
            Command.PROPERTIES, Command.OPTIONS,
        )
        pick("image/*", multiple = true)
        tap("a.jpg")
        assertEquals(listOf("a.jpg"), ui.selected.map { it.name })
        assertTrue(ui.commands.toString(), kept.containsAll(ui.commands))
        for (c in listOf(Command.DELETE, Command.RENAME, Command.PASTE)) assertFalse(c in ui.commands)
        assertTrue(Command.OPEN in ui.commands && Command.PROPERTIES in ui.commands)

        // One file at a time: nothing selects them all, or the others.
        pick("image/*")
        vm.onLongPress(entry("a.jpg"))
        settle()
        assertEquals(listOf("a.jpg"), ui.selected.map { it.name })
        assertTrue(ui.commands.toString(), (kept - Command.SELECT_ALL - Command.INVERT).containsAll(ui.commands))
        for (c in listOf(Command.DELETE, Command.RENAME, Command.PASTE)) assertFalse(c in ui.commands)
        // Open chooses the file selected.
        vm.command(Command.OPEN)
        settle()
        assertEquals(PickOutcome.Chosen(listOf(root.resolve("a.jpg"))), outcome())
    }

    @Test
    fun backWithNothingToUndoCancels() {
        val dcim = dir("DCIM")
        lastPickedIn(root)
        pick("*/*")
        vm.navigate(Location.Folder(dcim))
        settle()

        // Back goes back first.
        vm.systemBack()
        settle()
        assertEquals(Location.Folder(root), location())
        assertNull(outcome())

        assertNull(ui.backTarget)
        vm.systemBack()
        settle()
        assertEquals(PickOutcome.Cancelled, outcome())
    }

    @Test
    fun chosenFileBecomesRecent() {
        dir("DCIM")
        file("DCIM/a.jpg")
        lastPickedIn(root.resolve("DCIM"))
        pick("image/jpeg")
        tap("a.jpg")

        assertEquals(listOf("DCIM/a.jpg"), settings.recents.value.map { it.rel })
    }

    @Test
    fun recentFileOnHomeIsChosen() {
        val a = file("a.jpg")
        file("doc.pdf")
        settings.addRecent(Recent("primary", "doc.pdf", openedAt = 1))
        settings.addRecent(Recent("primary", "a.jpg", openedAt = 2))
        pick("image/*", multiple = true)
        val recents = checkNotNull(ui.home).recents
        assertEquals(listOf("a.jpg", "doc.pdf"), recents.map { it.name })

        // Opened from Home, a file the request does not take does nothing; one it takes is chosen.
        vm.open(recents.last())
        settle()
        assertNull(outcome())
        vm.open(recents.first())
        settle()
        assertEquals(PickOutcome.Chosen(listOf(a)), outcome())
    }
}
