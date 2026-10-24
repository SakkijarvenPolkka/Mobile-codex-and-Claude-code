// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerShell
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerTheme
import io.github.sakkijarvenpolkka.explorer.ui.Tags
import io.github.sakkijarvenpolkka.explorer.ui.WidthClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pinned folders: kept in the user's order between runs, in the navigation pane and on Home
 * (spec §2.1, §2.4, §3.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp")
class FavoritesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Kept in the app's SharedPreferences, which a new store reads back as the next run would. */
    private val settings = SettingsStore(app)
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

    /** The shell on [root] as the primary volume and [extra] volumes, started at [root]; folders are read at once. */
    private fun show(extra: List<Volume> = emptyList()) {
        volumes = FakeVolumes(FakeVolumes.primary(root), *extra.toTypedArray())
        settings.startAt(root, volumes)
        val deps = ExplorerDeps(NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined)
        vm = ExplorerViewModel(app, deps)
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, WidthClass.COMPACT) }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int): String = app.getString(id)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun location(): Location = ui.location

    private fun go(location: Location) {
        vm.navigate(location)
        rule.waitForIdle()
    }

    private fun entry(name: String) = ui.rows.first { it.entry.name == name }.entry

    private fun inside(tag: String, matcher: SemanticsMatcher): SemanticsNodeInteraction =
        rule.onNode(matcher and hasAnyAncestor(hasTestTag(tag)))

    private fun openDrawer() {
        rule.onNodeWithTag(Tags.NAV_MENU).performClick()
        rule.waitForIdle()
    }

    private fun quick(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.quickItem(name))

    private fun longPress(node: SemanticsNodeInteraction) {
        node.performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private fun choose(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test
    fun pinUnpinPersists() {
        dir("Music")
        dir("a")
        dir("b")
        show()
        // With nothing selected, the current folder is pinned.
        go(Location.Folder(root.resolve("Music")))
        assertTrue(Command.PIN in ui.commands)
        vm.command(Command.PIN)
        rule.waitForIdle()
        assertEquals(listOf(Favorite("primary", "Music", "Music")), settings.favorites.value)
        assertTrue(Command.UNPIN in ui.commands)

        // The selected folders, in listing order, after those pinned before.
        go(Location.Folder(root))
        vm.onLongPress(entry("b"))
        vm.onTap(entry("a"))
        rule.waitForIdle()
        vm.command(Command.PIN)
        rule.waitForIdle()
        val pinned = listOf("Music", "a", "b").map { Favorite("primary", it, it) }
        assertEquals(pinned, settings.favorites.value)
        // Pinning a folder again adds nothing.
        vm.pin(listOf(root.resolve("a")))
        assertEquals(pinned, settings.favorites.value)
        // Saved for the next run.
        assertEquals(pinned, SettingsStore(app).favorites.value)

        vm.command(Command.UNPIN)
        rule.waitForIdle()
        assertEquals(listOf(Favorite("primary", "Music", "Music")), settings.favorites.value)
        assertEquals(listOf(Favorite("primary", "Music", "Music")), SettingsStore(app).favorites.value)
        assertTrue(Command.PIN in ui.commands)
    }

    @Test
    fun reorderKeepsOrder() {
        dir("a")
        dir("b")
        dir("c")
        show()
        vm.pin(listOf("a", "b", "c").map(root::resolve))
        val (a, b, c) = settings.favorites.value
        // A favorite takes the place of the one it is moved to.
        vm.reorderFavorites(c, to = a)
        rule.waitForIdle()
        assertEquals(listOf("c", "a", "b"), settings.favorites.value.map { it.rel })
        assertEquals(listOf("c", "a", "b"), SettingsStore(app).favorites.value.map { it.rel })
        assertEquals(listOf("c", "a", "b").map(root::resolve), ui.favorites.map { it.path })

        vm.reorderFavorites(c, to = a)
        rule.waitForIdle()
        assertEquals(listOf("a", "c", "b"), settings.favorites.value.map { it.rel })
        // One that is not a favorite moves nothing.
        vm.reorderFavorites(Favorite("primary", "d", "d"), to = b)
        vm.reorderFavorites(a, to = Favorite("primary", "d", "d"))
        assertEquals(listOf("a", "c", "b"), settings.favorites.value.map { it.rel })

        // Home lists them in that order.
        go(Location.Home)
        val quick = ui.home!!.quickAccess
        assertEquals(listOf("a", "c", "b").map(root::resolve), quick.map { it.path })
        assertTrue(quick.all { it.pinned })

        // On Home, a pinned folder's menu moves it up or down.
        longPress(quick("b"))
        rule.onNodeWithTag(Tags.FAVORITE_DOWN).assertIsNotEnabled()
        choose(Tags.FAVORITE_UP)
        assertEquals(listOf("a", "b", "c"), settings.favorites.value.map { it.rel })
        longPress(quick("a"))
        rule.onNodeWithTag(Tags.FAVORITE_UP).assertIsNotEnabled()
        choose(Tags.FAVORITE_DOWN)
        assertEquals(listOf("b", "a", "c"), settings.favorites.value.map { it.rel })
        assertEquals(listOf("b", "a", "c").map(root::resolve), ui.home!!.quickAccess.map { it.path })
    }

    @Test
    fun reorderMovesTheFavoriteShown() {
        dir("a")
        dir("b")
        // Stored between them: one that leads nowhere, which is not shown.
        val stored = listOf(Favorite("primary", "a", "a"), Favorite("primary", "../x", "x"), Favorite("primary", "b", "b"))
        settings.setFavorites(stored)
        show()
        go(Location.Home)
        assertEquals(listOf("a", "b").map(root::resolve), ui.favorites.map { it.path })

        // Up moves b above a, the one shown above it.
        longPress(quick("b"))
        choose(Tags.FAVORITE_UP)
        assertEquals(listOf("b", "a").map(root::resolve), ui.favorites.map { it.path })
        assertEquals(listOf("b", "a").map(root::resolve), ui.home!!.quickAccess.map { it.path })
        assertEquals(listOf(stored[2], stored[0], stored[1]), settings.favorites.value)
    }

    @Test
    fun pinnedVolumeRootShowsTheVolumeName() {
        dir("Music")
        show()
        vm.pin(listOf(root))
        rule.waitForIdle()
        // Pinned under the system's label, shown under the app's name for the drive.
        assertEquals(listOf(Favorite("primary", "", "Internal shared storage")), settings.favorites.value)
        openDrawer()
        val inDrawer = hasAnyAncestor(hasTestTag(Tags.DRAWER))
        inside(Tags.DRAWER, hasText("Internal shared storage")).assertDoesNotExist()
        rule.onAllNodes(hasText(str(R.string.volume_internal)) and inDrawer).assertCountEquals(2)
        inside(Tags.DRAWER, hasText(str(R.string.nav_home))).performClick()
        rule.waitForIdle()
        quick(root.fileName.toString()).assert(hasText(str(R.string.volume_internal)))
    }

    @Test
    fun favoriteOnAbsentVolumeShownUnavailable() {
        dir("Music")
        val usb = folder.newFolder("usb").toPath().toRealPath()
        Files.createDirectory(usb.resolve("docs"))
        val onUsb = Favorite("USB1", "docs", "docs")
        val music = Favorite("primary", "Music", "Music")
        settings.setFavorites(listOf(onUsb, music))
        show(extra = listOf(FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)))

        // Where the drive is usually mounted, dimmed; not removed.
        val usual = Paths.get("/storage/USB1/docs")
        assertEquals(
            listOf(
                PinnedFolder(onUsb, usual, available = false),
                PinnedFolder(music, root.resolve("Music"), available = true),
            ),
            ui.favorites,
        )
        openDrawer()
        inside(Tags.DRAWER, hasText("docs") and hasText(str(R.string.volume_unavailable))).assertIsNotEnabled()
        inside(Tags.DRAWER, hasText("Music")).assertIsEnabled()
        inside(Tags.DRAWER, hasText(str(R.string.nav_home))).performClick()
        rule.waitForIdle()
        assertEquals(Location.Home, location())
        quick("docs").assertIsNotEnabled()
        quick("Music").assertIsEnabled()
        assertEquals(listOf(onUsb, music), settings.favorites.value)

        // Mounted: it is there again, and opens.
        volumes.update("USB1") { it.copy(root = usb, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted("USB1"))
        rule.waitForIdle()
        assertEquals(PinnedFolder(onUsb, usb.resolve("docs"), available = true), ui.favorites.first())
        quick("docs").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(usb.resolve("docs")), location())
    }

    @Test
    fun pinnedFolderAppearsInNavAndHome() {
        dir("Music")
        dir("Download")
        show()
        go(Location.Folder(root.resolve("Music")))
        vm.command(Command.PIN)
        rule.waitForIdle()
        go(Location.Folder(root))

        // The navigation pane: Home, then Favorites with the folder, which it opens.
        openDrawer()
        inside(Tags.DRAWER, hasText(str(R.string.nav_favorites))).assertIsDisplayed()
        inside(Tags.DRAWER, hasText("Music") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("Music")), location())
        openDrawer()
        inside(Tags.DRAWER, hasText("Music")).assertIsSelected()

        // Home: the folder first, then the default folders there are, each pinned or not.
        inside(Tags.DRAWER, hasText(str(R.string.nav_home))).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(
                QuickItem(root.resolve("Music"), "Music", pinned = true),
                QuickItem(root.resolve("Download"), "Download", pinned = false),
            ),
            ui.home!!.quickAccess,
        )
        quick("Music").assertIsDisplayed()
        quick("Download").assert(hasText(str(R.string.folder_download)))

        // A default folder's menu pins it; a pinned one's unpins it, from Home and the navigation pane.
        longPress(quick("Download"))
        choose(Tags.contextCommand(Command.PIN))
        assertEquals(listOf("Music", "Download"), settings.favorites.value.map { it.rel })
        longPress(quick("Music"))
        choose(Tags.contextCommand(Command.UNPIN))
        assertEquals(listOf("Download"), settings.favorites.value.map { it.rel })
        // Music is a default folder too: it stays, unpinned.
        assertEquals(
            listOf(
                QuickItem(root.resolve("Download"), "Download", pinned = true),
                QuickItem(root.resolve("Music"), "Music", pinned = false),
            ),
            ui.home!!.quickAccess,
        )
        assertEquals(listOf(root.resolve("Download")), ui.favorites.map { it.path })

        quick("Download").performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("Download")), location())
    }
}
