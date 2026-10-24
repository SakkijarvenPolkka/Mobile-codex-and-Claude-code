// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.MainActivity
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.HistoryEntry
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.ui.fluent.TabStripTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.nio.file.Files
import java.nio.file.Path

/** The tab strip of the shell: new, closed, duplicated and cycled tabs, each with its own place (spec §2.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp")
class TabStripTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val settings = SettingsStore(null)
    private lateinit var root: Path
    private lateinit var vm: ExplorerViewModel
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    /** The shell on [root] as the primary volume, which the first tab shows; folders are read at once. */
    private fun show() {
        val volumes = FakeVolumes(FakeVolumes.primary(root))
        val deps = ExplorerDeps(NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined)
        vm = ExplorerViewModel(app, deps)
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                val global by settings.global.collectAsState()
                ExplorerTheme(global) { ExplorerShell(vm, WidthClass.COMPACT) }
            }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.write(root.resolve(rel), ByteArray(0))

    private fun location(): Location = vm.ui.value.location

    private fun ids(): List<TabId> = vm.ui.value.tabs.tabs.map { it.id }

    private fun active(): TabId = vm.ui.value.tabs.activeId

    private fun backOf(id: TabId): List<Location> = vm.ui.value.tabs.tabs.first { it.id == id }.back.map { it.location }

    private fun tab(id: TabId): SemanticsNodeInteraction = rule.onNodeWithTag(TabStripTags.tab(id))

    private fun go(rel: String) {
        vm.navigate(Location.Folder(root.resolve(rel)))
        rule.waitForIdle()
    }

    private fun newTab(): TabId {
        rule.onNodeWithTag(TabStripTags.NEW).performClick()
        rule.waitForIdle()
        return active()
    }

    private fun select(id: TabId) {
        tab(id).performClick()
        rule.waitForIdle()
    }

    private fun close(id: TabId) {
        rule.onNodeWithTag(TabStripTags.close(id)).performClick()
        rule.waitForIdle()
    }

    private fun openMenu(id: TabId) {
        tab(id).performTouchInput { longClick() }
        rule.waitForIdle()
    }

    private fun choose(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ctrl(key: Key) {
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
        rule.waitForIdle()
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun newTabOpensHome() {
        dir("Music")
        show()
        go("Music")
        val first = active()
        tab(first).assert(hasRole(Role.Tab)).assertIsSelected().assert(hasText("Music"))

        rule.onNodeWithTag(TabStripTags.NEW).assert(hasContentDescription(str(R.string.tab_new)))
        val second = newTab()
        assertEquals(listOf(first, second), ids())
        assertNotEquals(first, second)
        assertEquals(Location.Home, location())
        assertEquals(emptyList<Location>(), backOf(second))
        tab(second).assert(hasRole(Role.Tab)).assertIsSelected().assert(hasText(str(R.string.nav_home)))
        tab(first).assertIsNotSelected()
        // The first tab stays where it was.
        assertEquals(Location.Folder(root.resolve("Music")), vm.ui.value.tabs.locationOf(first))

        // Until Home has views of its own, it lists the drives.
        rule.onNode(hasText(str(R.string.volume_internal)) and hasAnyAncestor(hasTestTag(Tags.HOME))).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf(first, second), ids())
    }

    @Test
    fun tabTitlesFollowTheLocation() {
        dir("Music")
        show()
        val id = active()
        // A volume's root is named after the volume.
        tab(id).assert(hasText(str(R.string.volume_internal)))
        go("Music")
        tab(id).assert(hasText("Music"))
        vm.navigate(Location.RecycleBin)
        rule.waitForIdle()
        tab(id).assert(hasText(str(R.string.nav_recycle_bin)))
        vm.navigate(Location.SearchResults(root, "song"))
        rule.waitForIdle()
        tab(id).assert(hasText("Search results for “⁨song⁩”"))
        rule.onNodeWithTag(TabStripTags.close(id)).assert(hasContentDescription(str(R.string.tab_close)))
    }

    @Test
    fun closeTabActivatesNeighbour() {
        dir("a")
        dir("b")
        show()
        go("a")
        val a = active()
        newTab()
        go("b")
        val b = active()
        val c = newTab()
        select(b)
        assertEquals(b, active())

        // Closing the active tab activates the one to its right...
        close(b)
        assertEquals(listOf(a, c), ids())
        assertEquals(c, active())
        tab(b).assertDoesNotExist()
        // ... else, as the last one, the one to its left.
        close(c)
        assertEquals(listOf(a), ids())
        assertEquals(a, active())
        assertEquals(Location.Folder(root.resolve("a")), location())

        // Closing another tab keeps the active one.
        val d = newTab()
        close(a)
        assertEquals(listOf(d), ids())
        assertEquals(d, active())

        // Closing the last tab leaves one Home tab.
        close(d)
        val home = ids().single()
        assertNotEquals(d, home)
        assertEquals(Location.Home, location())
        tab(home).assertIsSelected().assert(hasText(str(R.string.nav_home)))
    }

    @Test
    fun tabMenuDuplicateAndCloseOthers() {
        dir("a/b")
        show()
        go("a")
        go("a/b")
        val first = active()
        val home = newTab()

        openMenu(first)
        rule.onNodeWithTag(Tags.TAB_MENU_DUPLICATE).assert(hasText(str(R.string.tab_duplicate)))
        rule.onNodeWithTag(Tags.TAB_MENU_CLOSE).assert(hasText(str(R.string.tab_close)))
        rule.onNodeWithTag(Tags.TAB_MENU_CLOSE_OTHERS).assert(hasText(str(R.string.tab_close_others))).assertIsEnabled()
        choose(Tags.TAB_MENU_DUPLICATE)
        // The copy comes right after the original, with its history, and is active.
        val twin = ids()[1]
        assertEquals(listOf(first, twin, home), ids())
        assertEquals(twin, active())
        assertEquals(Location.Folder(root.resolve("a/b")), location())
        assertEquals(listOf(Location.Folder(root), Location.Folder(root.resolve("a"))), backOf(twin))
        tab(twin).assertIsSelected().assert(hasText("b"))
        // Going back in the copy leaves the original where it is.
        rule.onNodeWithTag(Tags.BACK).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("a")), location())
        assertEquals(Location.Folder(root.resolve("a/b")), vm.ui.value.tabs.locationOf(first))

        openMenu(home)
        choose(Tags.TAB_MENU_CLOSE_OTHERS)
        assertEquals(listOf(home), ids())
        assertEquals(home, active())
        assertEquals(Location.Home, location())

        // With one tab there are no others to close; Close leaves one Home tab.
        openMenu(home)
        rule.onNodeWithTag(Tags.TAB_MENU_CLOSE_OTHERS).assertIsNotEnabled()
        choose(Tags.TAB_MENU_CLOSE)
        assertNotEquals(home, ids().single())
        assertEquals(Location.Home, location())
        rule.onNodeWithTag(Tags.TAB_MENU_CLOSE).assertDoesNotExist()
    }

    @Test
    fun eachTabKeepsOwnHistoryAndScroll() {
        dir("many")
        repeat(100) { file("many/f${it.toString().padStart(3, '0')}.txt") }
        dir("other")
        show()
        go("many")
        val a = active()
        rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).performScrollToIndex(50)
        rule.waitForIdle()
        assertEquals("f050.txt", firstShownRow())

        val b = newTab()
        go("other")
        assertEquals(listOf(Location.Home), backOf(b))

        select(a)
        assertEquals(Location.Folder(root.resolve("many")), location())
        assertEquals("f050.txt", firstShownRow())
        assertEquals(listOf(Location.Folder(root)), backOf(a))
        // Back goes where this tab was before, not where the other one was.
        rule.onNodeWithTag(Tags.BACK).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root), location())

        select(b)
        assertEquals(Location.Folder(root.resolve("other")), location())
        assertEquals(listOf(Location.Home), backOf(b))
    }

    @Test
    fun ctrlTabCyclesTabs() {
        show()
        val first = active()
        ctrl(Key.T)
        val second = active()
        assertEquals(Location.Home, location())
        ctrl(Key.T)
        val third = active()
        assertEquals(listOf(first, second, third), ids())

        // From the last tab, Ctrl+Tab goes round to the first.
        ctrl(Key.Tab)
        assertEquals(first, active())
        tab(first).assertIsSelected()
        ctrl(Key.Tab)
        assertEquals(second, active())

        ctrl(Key.W)
        assertEquals(listOf(first, third), ids())
        assertEquals(third, active())
    }

    @Test
    fun listPositionsForgetClosedTabs() {
        val starts = mutableListOf<TabId>()
        val positions = ListPositions { tab, entry -> entry.also { starts += tab } }
        val entry = HistoryEntry(Location.Folder(root))
        val kept = positions.list(TabId(1), entry)
        val closed = positions.list(TabId(2), entry)

        positions.retain(setOf(TabId(1)))
        assertSame(kept, positions.list(TabId(1), entry))
        // A tab forgotten starts over (were its id ever seen again).
        assertNotSame(closed, positions.list(TabId(2), entry))
        assertEquals(listOf(TabId(1), TabId(2), TabId(2)), starts)
    }

    @Test
    fun tabsRestoredAfterRecreate() {
        ShadowEnvironment.addExternalDir("emulated/0")
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            "android:manage_external_storage", context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            rule.onNodeWithTag(TabStripTags.NEW).performClick()
            rule.onNodeWithTag(TabStripTags.NEW).performClick()
            rule.waitForIdle()
            val tabs = shownTabs()
            assertEquals(3, tabs.size)
            rule.onNodeWithTag(tabs[1]).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(tabs[1]).assertIsSelected()

            main.recreate()
            rule.waitForIdle()
            assertEquals(tabs, shownTabs())
            rule.onNodeWithTag(tabs[1]).assertIsSelected()
            rule.onNodeWithTag(tabs[0]).assertIsNotSelected()
            rule.onNodeWithTag(tabs[2]).assertIsNotSelected()
        }
    }

    /** The tags of the tabs of the strip, from its start. */
    private fun shownTabs(): List<String> {
        val isTab = hasRole(Role.Tab) and hasAnyAncestor(hasTestTag(TabStripTags.STRIP))
        return rule.onAllNodes(isTab).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.left }
            .map { it.config[SemanticsProperties.TestTag] }
    }

    /** The name of the topmost row the details list shows, at least in part. */
    private fun firstShownRow(): String {
        val listTop = rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).fetchSemanticsNode().boundsInRoot.top
        val isRow = SemanticsMatcher("is a row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(Tags.row("")) == true
        }
        return rule.onAllNodes(isRow).fetchSemanticsNodes()
            .filter { it.boundsInRoot.bottom > listTop }
            .minBy { it.boundsInRoot.top }
            .config[SemanticsProperties.TestTag]
            .removePrefix(Tags.row(""))
    }
}
