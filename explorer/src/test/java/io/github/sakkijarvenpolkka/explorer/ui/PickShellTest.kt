// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.PickOutcome
import io.github.sakkijarvenpolkka.explorer.state.PickRequest
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

/** The shell picking files for another app (pick spec §2.2), in Korean on a wide window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w900dp-h900dp")
class PickShellTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        volumes = FakeVolumes(FakeVolumes.primary(root))
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    /**
     * The shell picking files of [types] (several of them, if [multiple]) for another app, started in [at], the
     * folder files were last picked from (at Home without one), with the app's Recycle Bin [bin].
     */
    private fun show(vararg types: String, multiple: Boolean = false, at: Path? = root, bin: RecycleBin? = null) {
        val codec = VolumeLocationCodec { volumes.volumes.value }
        settings.pickLast = at?.let { codec.encode(Location.Folder(it)) }
        val request = PickRequest(types.toList(), multiple)
        val deps = ExplorerDeps(
            NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined, bin = bin, pick = request,
        )
        vm = ExplorerViewModel(app, deps)
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, WidthClass.EXPANDED) }
        }
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private fun row(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.row(name))

    /** The node with [text] inside the element tagged [tag]. */
    private fun inside(tag: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    private fun outcome(): PickOutcome? = vm.ui.value.pick?.outcome

    /** The system Back, then what it leads to. */
    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun titleBarReplacesTabStrip() {
        file("a.jpg")
        show("image/*")

        rule.onNodeWithTag(TabStripTags.STRIP).assertDoesNotExist()
        assertEquals("파일 선택", str(R.string.pick_title))
        inside(Tags.PICK_TITLE_BAR, str(R.string.pick_title)).assertIsDisplayed()
        // The address bar and the search box stay.
        rule.onNodeWithTag(Tags.BREADCRUMB).assertIsDisplayed()
        rule.onNodeWithTag(Tags.SEARCH_BOX).assertIsDisplayed()

        rule.onNodeWithTag(Tags.PICK_CANCEL).assert(hasText(str(R.string.cancel))).performClick()
        rule.waitForIdle()
        assertEquals(PickOutcome.Cancelled, outcome())
    }

    @Test
    fun nonMatchingRowsAreDisabled() {
        file("a.jpg")
        file("doc.pdf")
        Files.createDirectory(root.resolve("DCIM"))
        show("image/*")

        row("doc.pdf").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        rule.onNodeWithTag(Tags.rowMore("doc.pdf")).assertIsNotEnabled()
        row("a.jpg").assertIsEnabled()
        row("DCIM").assertIsEnabled()

        // Touching it does nothing.
        row("doc.pdf").performClick()
        row("doc.pdf").performTouchInput { longClick() }
        rule.waitForIdle()
        assertNull(outcome())
        assertTrue(vm.ui.value.selected.isEmpty())
    }

    @Test
    fun bottomBarCountsAndOpens() {
        val a = file("a.jpg")
        val b = file("b.jpg")
        file("c.jpg")
        file("doc.pdf")
        show("image/*", multiple = true)
        inside(Tags.PICK_TITLE_BAR, str(R.string.pick_title_multiple)).assertIsDisplayed()
        val open = rule.onNodeWithTag(Tags.PICK_OPEN)
        open.assert(hasText(str(R.string.pick_open_count, 0))).assertIsNotEnabled()
        inside(Tags.PICK_BOTTOM_BAR, str(R.string.cancel)).assertIsDisplayed()

        // A tap alone selects, no long press needed.
        row("b.jpg").performClick()
        row("a.jpg").performClick()
        rule.waitForIdle()
        assertEquals("열기 (2)", str(R.string.pick_open_count, 2))
        open.assert(hasText("열기 (2)")).assertIsEnabled()

        open.performClick()
        rule.waitForIdle()
        assertEquals(PickOutcome.Chosen(listOf(a, b)), outcome())
    }

    @Test
    fun commandsThatChangeFilesAreNotShown() {
        file("a.jpg")
        show("image/*", multiple = true)
        row("a.jpg").performClick()
        rule.waitForIdle()

        val changes = listOf(Command.CUT, Command.COPY, Command.PASTE, Command.RENAME, Command.SHARE, Command.DELETE)
        for (tag in listOf(Tags.CMD_NEW) + changes.map(Tags::command)) rule.onNodeWithTag(tag).assertDoesNotExist()
        rule.onNodeWithTag(Tags.CMD_SORT).assertIsDisplayed()
        rule.onNodeWithTag(Tags.CMD_VIEW).assertIsDisplayed()

        rule.onNode(hasContentDescription(str(R.string.more)) and hasAnyAncestor(hasTestTag(Tags.COMMAND_BAR)))
            .performClick()
        rule.waitForIdle()
        for (c in listOf(Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.PROPERTIES, Command.REFRESH)) {
            rule.onNodeWithTag(Tags.command(c)).assertIsDisplayed()
        }
        for (c in listOf(Command.DELETE_PERMANENT, Command.COMPRESS, Command.EXTRACT, Command.PIN, Command.UNPIN)) {
            rule.onNodeWithTag(Tags.command(c)).assertDoesNotExist()
        }
        rule.onNodeWithTag(Tags.CMD_PREVIEW_PANE).assertDoesNotExist()
    }

    @Test
    fun homeChangesNoFavoriteAndDimsWhatIsNotTaken() {
        val dcim = Files.createDirectory(root.resolve("DCIM"))
        val a = file("a.jpg")
        file("doc.pdf")
        settings.addRecent(Recent("primary", "doc.pdf", openedAt = 1))
        settings.addRecent(Recent("primary", "a.jpg", openedAt = 2))
        show("image/*", at = null)

        rule.onNodeWithTag(Tags.recent("a.jpg")).assertIsEnabled()
        val doc = rule.onNodeWithTag(Tags.recent("doc.pdf"))
        doc.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)).performClick()
        rule.waitForIdle()
        assertNull(outcome())

        // A long press on a folder of quick access opens no menu, which would change the favorites: just the folder.
        rule.onNodeWithTag(Tags.quickItem("DCIM")).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.contextCommand(Command.PIN)).assertDoesNotExist()
        assertEquals(Location.Folder(dcim), vm.ui.value.location)

        // A recent file the request takes is chosen.
        vm.back()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.recent("a.jpg")).performClick()
        rule.waitForIdle()
        assertEquals(PickOutcome.Chosen(listOf(a)), outcome())
    }

    @Test
    fun backGestureWithNothingToUndoCancels() {
        val dcim = Files.createDirectory(root.resolve("DCIM"))
        show("image/*")
        row("DCIM").performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(dcim), vm.ui.value.location)

        // Back goes back first; then, with nothing left to undo, the shell still takes it, to end the picking.
        pressBack()
        assertEquals(Location.Folder(root), vm.ui.value.location)
        assertNull(outcome())
        assertNull(vm.ui.value.backTarget)
        pressBack()
        assertEquals(PickOutcome.Cancelled, outcome())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tabShortcutsDoNothing() {
        show("*/*")
        val tab = vm.ui.value.tabs.activeId
        // Each on its own: Ctrl+W would close a tab Ctrl+T opened.
        for (key in listOf(Key.T, Key.W, Key.Tab)) {
            rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
            rule.waitForIdle()
            assertEquals(listOf(tab), vm.ui.value.tabs.tabs.map { it.id })
        }
        assertNull(outcome())
    }

    @Test
    fun binHiddenFromNavPane() {
        val bin = RecycleBin(NioFsBackend, volumes, clock = { 1L }, ids = { "b1" })
        runBlocking { bin.moveToBin(file("old.txt")) }
        show("*/*", at = null, bin = bin)
        assertEquals(Location.Home, vm.ui.value.location)

        val pane = hasAnyAncestor(hasTestTag(Tags.NAV_PANE))
        rule.onNode(hasText(str(R.string.nav_home)) and pane).assertIsDisplayed()
        rule.onNode(hasText(str(R.string.volume_internal)) and pane).assertIsDisplayed()
        rule.onNode(hasText(str(R.string.nav_recycle_bin)) and pane).assertDoesNotExist()
        // Nor is it on Home, where it holds an item.
        assertEquals(1, vm.ui.value.home?.binItems)
        rule.onNodeWithTag(Tags.HOME_BIN).assertDoesNotExist()
    }
}
