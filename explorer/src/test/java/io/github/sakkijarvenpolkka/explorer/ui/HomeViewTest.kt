// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.HomeData
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.QuickItem
import io.github.sakkijarvenpolkka.explorer.state.defaultFolders
import io.github.sakkijarvenpolkka.explorer.state.startAt
import io.github.sakkijarvenpolkka.explorer.ui.fluent.TabStripTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import kotlin.math.abs

/** Home: quick access, the drives, the recent files and the Recycle Bin (spec §2.4), shown in Korean. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w600dp-h900dp")
class HomeViewTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
    private lateinit var root: Path
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
     * The shell on [root] as the primary volume and [extra] volumes, whose sizes [usage] tells, with the
     * Recycle Bin [bin]; started at Home, or at [root] with [atRoot]. Folders are read at once.
     */
    private fun show(
        extra: List<Volume> = emptyList(),
        usage: (Path) -> Pair<Long, Long>? = { null },
        bin: RecycleBin? = null,
        atRoot: Boolean = false,
    ) {
        val volumes = FakeVolumes(FakeVolumes.primary(root), *extra.toTypedArray())
        if (atRoot) settings.startAt(root, volumes)
        val deps = ExplorerDeps(
            NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined, bin = bin, usage = usage,
        )
        vm = ExplorerViewModel(app, deps)
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, WidthClass.COMPACT) }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String, bytes: Int = 0): Path = Files.write(root.resolve(rel), ByteArray(bytes))

    private val ui get() = vm.ui.value

    private fun location(): Location = ui.location

    private fun inHome(text: String): SemanticsNodeInteraction =
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(Tags.HOME)))

    private fun click(node: SemanticsNodeInteraction) {
        node.performClick()
        rule.waitForIdle()
    }

    @Test
    fun quickAccessShowsExistingDefaultsOnly() {
        dir("Music")
        dir("Pictures")
        dir("Download")
        // A file by a default folder's name is not one.
        file("Documents")
        show()
        assertEquals(
            listOf("Download", "Pictures", "Music").map { it to root.resolve(it) },
            defaultFolders(root),
        )
        assertEquals(
            listOf("Download", "Pictures", "Music").map { QuickItem(root.resolve(it), it, pinned = false) },
            ui.home!!.quickAccess,
        )
        inHome(str(R.string.home_quick_access)).assertIsDisplayed()
        val shown = listOf(R.string.folder_download, R.string.folder_pictures, R.string.folder_music)
        val tops = shown.map { inHome(str(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        for (absent in listOf(R.string.folder_dcim, R.string.folder_documents, R.string.folder_movies)) {
            inHome(str(absent)).assertDoesNotExist()
        }

        click(inHome(str(R.string.folder_pictures)))
        assertEquals(Location.Folder(root.resolve("Pictures")), location())
    }

    @Test
    fun driveBarShowsFreeOfTotal() {
        val gib = 1L shl 30
        val sd = folder.newFolder("sd").toPath().toRealPath()
        show(
            extra = listOf(
                FakeVolumes.removable("1A2B-3C4D", "SD card", sd),
                FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED),
            ),
            usage = { if (it == root) 12 * gib to 3 * gib + gib / 2 else null },
        )
        inHome(str(R.string.home_drives)).assertIsDisplayed()
        // The bar is filled as far as the drive is used: 8.5 of 12 GB.
        val used = SemanticsMatcher("8.5 / 12 used") { node ->
            val info = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            info != null && abs(info.current - 8.5f / 12f) < 0.001f
        }
        val drive = rule.onNodeWithTag(Tags.drive("primary"))
            .assertIsDisplayed()
            .assert(hasText(str(R.string.volume_internal)))
            .assert(hasText("12.0 GB 중 3.50 GB 사용 가능"))
        rule.onNode(used and hasAnyAncestor(hasTestTag(Tags.drive("primary"))), useUnmergedTree = true).assertExists()
        // A drive whose size is not known, or one that is not mounted, has no bar.
        rule.onNodeWithTag(Tags.drive("1A2B-3C4D")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.drive("USB1")).assertDoesNotExist()

        click(drive)
        assertEquals(Location.Folder(root), location())
    }

    @Test
    fun recentsDropVanishedFiles() {
        file("a.txt")
        file("b.pdf")
        // The Recycle Bin shows with its size once something is in it.
        val volumes = FakeVolumes(FakeVolumes.primary(root))
        val bin = RecycleBin(NioFsBackend, volumes, clock = { 1L }, ids = { "b1" })
        runBlocking { bin.moveToBin(file("old.bin", bytes = 2048)) }
        settings.addRecent(Recent("primary", "gone.txt", openedAt = 1))
        settings.addRecent(Recent("USB1", "x.txt", openedAt = 2))
        settings.addRecent(Recent("primary", "b.pdf", openedAt = 3))
        settings.addRecent(Recent("primary", "a.txt", openedAt = 4))
        show(extra = listOf(FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)), bin = bin)

        inHome(str(R.string.home_recent)).assertIsDisplayed()
        assertEquals(listOf("a.txt", "b.pdf"), ui.home!!.recents.map { it.name })
        rule.onNodeWithTag(Tags.recent("a.txt")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.recent("gone.txt")).assertDoesNotExist()
        // Gone from the store too; one on a drive that is out is only not shown.
        assertEquals(
            listOf(Recent("primary", "a.txt", 4), Recent("primary", "b.pdf", 3), Recent("USB1", "x.txt", 2)),
            settings.recents.value,
        )
        rule.onNodeWithTag(Tags.recent("x.txt")).assertDoesNotExist()

        // Gone while Home shows: dropped on the next reading.
        Files.delete(root.resolve("b.pdf"))
        vm.refresh()
        rule.waitForIdle()
        assertEquals(listOf("a.txt"), ui.home!!.recents.map { it.name })
        rule.onNodeWithTag(Tags.recent("b.pdf")).assertDoesNotExist()
        assertEquals(listOf(Recent("primary", "a.txt", 4), Recent("USB1", "x.txt", 2)), settings.recents.value)

        assertEquals(2048L, ui.home!!.binSize)
        click(rule.onNodeWithTag(Tags.HOME_BIN).assert(hasText(str(R.string.bin_size, "2.00 KB"))))
        assertEquals(Location.RecycleBin, location())
    }

    @Test
    fun firstReadFailureShowsEmptyHome() {
        dir("Music")
        show(usage = { error("statfs failed") })
        // Not an endless progress bar: Home with nothing in it.
        assertEquals(HomeData(emptyList(), emptyList(), emptyList(), 0L, 0), ui.home)
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and hasAnyAncestor(hasTestTag(Tags.HOME)))
            .assertDoesNotExist()
    }

    @Test
    fun binRowShowsWhileItHoldsItemsOfNoBytes() {
        val volumes = FakeVolumes(FakeVolumes.primary(root))
        val bin = RecycleBin(NioFsBackend, volumes, clock = { 1L }, ids = { "b1" })
        runBlocking { bin.moveToBin(dir("empty")) }
        show(bin = bin)
        assertEquals(0L, ui.home!!.binSize)
        assertEquals(1, ui.home!!.binItems)
        rule.onNodeWithTag(Tags.HOME_BIN).assert(hasText(str(R.string.bin_size, "0바이트"))).assertIsDisplayed()
    }

    @Test
    fun upFromVolumeRootGoesHome() {
        dir("Music")
        val sd = folder.newFolder("sd").toPath().toRealPath()
        show(extra = listOf(FakeVolumes.removable("1A2B-3C4D", "SD card", sd)), atRoot = true)
        assertEquals(Location.Home, ui.up)
        click(rule.onNodeWithTag(Tags.UP).assertIsEnabled())
        assertEquals(Location.Home, location())
        rule.onNodeWithTag(Tags.HOME).assertIsDisplayed()
        // Nothing is above Home.
        assertNull(ui.up)
        rule.onNodeWithTag(Tags.UP).assertIsNotEnabled()
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })

        // Below a root, Up is the parent folder; from another volume's root, Home as well.
        vm.navigate(Location.Folder(root.resolve("Music")))
        rule.waitForIdle()
        assertEquals(Location.Folder(root), ui.up)
        vm.navigate(Location.Folder(sd))
        rule.waitForIdle()
        assertEquals(Location.Home, ui.up)
    }

    @Test
    fun startsAtHomeWithoutSession() {
        dir("Music")
        show()
        assertEquals(Location.Home, location())
        assertEquals(1, ui.tabs.tabs.size)
        assertTrue(ui.tabs.active.back.isEmpty())
        rule.onNodeWithTag(Tags.HOME).assertIsDisplayed()
        val home = hasText(str(R.string.nav_home))
        rule.onNode(home and hasAnyAncestor(hasTestTag(TabStripTags.STRIP))).assertIsDisplayed()
        rule.onNode(home and hasAnyAncestor(hasTestTag(Tags.BREADCRUMB))).assertIsDisplayed()
        // Home has no items of its own to count.
        rule.onNodeWithTag(Tags.STATUS_ITEMS).assertDoesNotExist()
    }
}
