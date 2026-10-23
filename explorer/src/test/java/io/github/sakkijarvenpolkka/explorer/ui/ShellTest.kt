// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.MainActivity
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.state.BackTarget
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The shell on a phone-wide window (every primary command fits the command bar at 600 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp")
class ShellTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
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
     * The shell on [root] as the primary volume and [extra] volumes, with [probe] inside the theme;
     * set through [restoration] when given, so that a test can recreate it.
     */
    private fun show(
        widthClass: WidthClass = WidthClass.COMPACT,
        fs: FsBackend = NioFsBackend,
        extra: List<Volume> = emptyList(),
        restoration: StateRestorationTester? = null,
        probe: @Composable () -> Unit = {},
    ) {
        volumes = FakeVolumes(FakeVolumes.primary(root), *extra.toTypedArray())
        val deps = ExplorerDeps(fs, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined)
        vm = ExplorerViewModel(ApplicationProvider.getApplicationContext<Application>(), deps)
        val content: @Composable () -> Unit = {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) {
                ExplorerShell(vm, widthClass)
                probe()
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String, bytes: Int = 0): Path = Files.write(root.resolve(rel), ByteArray(bytes))

    private fun row(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.row(name))

    private fun location(): Location = vm.ui.value.tabs.active.current.location

    private fun names(): List<String> = vm.ui.value.rows.map { it.entry.name }

    private fun inside(tag: String, matcher: SemanticsMatcher) =
        rule.onNode(matcher and hasAnyAncestor(hasTestTag(tag)))

    private fun openMore() = inside(Tags.COMMAND_BAR, hasContentDescription(str(R.string.more))).performClick()

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun backEnabled(): Boolean {
        var enabled = false
        rule.runOnUiThread { enabled = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private val hasNoRole = SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun breadcrumbTapNavigates() {
        dir("a/b")
        show()
        vm.navigate(Location.Folder(root.resolve("a/b")))
        rule.waitForIdle()

        inside(Tags.BREADCRUMB, hasText("a") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("a")), location())

        inside(Tags.BREADCRUMB, hasText(str(R.string.volume_internal)) and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root), location())
    }

    @Test
    fun editAddressAndSubmit() {
        dir("a/b")
        show()
        // The empty space of the breadcrumb (no role, unlike the segments and their chevrons).
        inside(Tags.BREADCRUMB, hasClickAction() and hasNoRole).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(Tags.ADDRESS_EDIT).assertTextEquals(root.toString())

        rule.onNodeWithTag(Tags.ADDRESS_EDIT).performTextReplacement("$root/a/./b")
        rule.onNodeWithTag(Tags.ADDRESS_EDIT).performImeAction()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("a/b")), location())
        rule.onNodeWithTag(Tags.ADDRESS_EDIT).assertDoesNotExist()
    }

    @Test
    fun commandBarEnablementFollowsSelection() {
        file("a.txt")
        show()
        rule.onNodeWithTag(Tags.CMD_DELETE).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.command(Command.COPY)).assertIsNotEnabled()

        row("a.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.CMD_DELETE).assertIsEnabled()
        rule.onNodeWithTag(Tags.command(Command.COPY)).assertIsEnabled()
    }

    @Test
    fun backClearsSelectionBeforeHistory() {
        dir("a")
        file("a/x.txt")
        show()
        assertFalse(backEnabled())

        row("a").performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("a")), location())
        row("x.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(vm.ui.value.tabs.active.selection.active)
        assertTrue(backEnabled())

        pressBack()
        assertFalse(vm.ui.value.tabs.active.selection.active)
        assertEquals(Location.Folder(root.resolve("a")), location())

        pressBack()
        assertEquals(Location.Folder(root), location())
        assertFalse(backEnabled())
    }

    @Test
    fun statusBarShowsSizeOnlyForFilesOnly() {
        file("a.txt", 1500)
        file("b.txt", 2000)
        dir("d")
        show()
        rule.onNodeWithTag(Tags.STATUS_ITEMS)
            .assertTextEquals(context.resources.getQuantityString(R.plurals.items_count, 3, 3))
        rule.onNodeWithTag(Tags.STATUS_SELECTION).assertDoesNotExist()
        rule.onNodeWithTag(Tags.STATUS_SIZE).assertDoesNotExist()

        row("a.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_SELECTION).assertTextEquals(str(R.string.selected_count, 1))
        rule.onNodeWithTag(Tags.STATUS_SIZE).assertTextEquals(Formats.size(1500, korean = false))

        row("b.txt").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_SELECTION).assertTextEquals(str(R.string.selected_count, 2))
        rule.onNodeWithTag(Tags.STATUS_SIZE).assertTextEquals(Formats.size(3500, korean = false))

        row("d").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.STATUS_SELECTION).assertTextEquals(str(R.string.selected_items, 3))
        rule.onNodeWithTag(Tags.STATUS_SIZE).assertDoesNotExist()
    }

    @Test
    fun drawerListsVolumesAndUnavailableDimmed() {
        show(extra = listOf(FakeVolumes.removable("1A2B-3C4D", "SD card", null, VolumeState.UNMOUNTED)))
        rule.onNodeWithTag(Tags.NAV_PANE).assertDoesNotExist()

        rule.onNodeWithTag(Tags.NAV_MENU).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.DRAWER).assertIsDisplayed()
        assertEquals(BackTarget.DRAWER, vm.ui.value.backTarget)
        inside(Tags.DRAWER, hasText(str(R.string.volume_internal))).assertIsEnabled()
        inside(Tags.DRAWER, hasText("SD card")).assertIsNotEnabled()

        inside(Tags.DRAWER, hasText(str(R.string.volume_internal))).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.DRAWER).assertDoesNotExist()
        assertEquals(Location.Folder(root), location())
    }

    @Test
    fun switchesBetweenFourViews() {
        file("a.txt")
        show()
        rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).assertExists()
        for (mode in listOf(ViewMode.LIST, ViewMode.TILES, ViewMode.LARGE_ICONS, ViewMode.DETAILS)) {
            rule.onNodeWithTag(Tags.CMD_VIEW).performClick()
            rule.onNodeWithTag(Tags.view(mode)).performClick()
            rule.waitForIdle()
            for (other in ViewMode.entries) {
                if (other == mode) rule.onNodeWithTag(Tags.fileList(other)).assertExists()
                else rule.onNodeWithTag(Tags.fileList(other)).assertDoesNotExist()
            }
            row("a.txt").assertExists()
            assertEquals(mode, vm.ui.value.viewSetting.mode)
        }

        rule.onNodeWithTag(Tags.STATUS_LARGE_ICONS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.fileList(ViewMode.LARGE_ICONS)).assertExists()
        rule.onNodeWithTag(Tags.STATUS_DETAILS).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).assertExists()
    }

    @Test
    fun hiddenToggleShowsDotFiles() {
        file(".secret")
        file("a.txt")
        show()
        row("a.txt").assertExists()
        row(".secret").assertDoesNotExist()

        openMore()
        rule.onNodeWithTag(Tags.CMD_HIDDEN).performClick()
        rule.waitForIdle()
        row(".secret").assertExists()
        assertTrue(settings.global.value.showHidden)
    }

    @Test
    fun extensionsToggleHidesExtensions() {
        file("a.pdf")
        dir("b.c")
        show()
        row("a.pdf").assert(hasText("a.pdf"))

        openMore()
        rule.onNodeWithTag(Tags.CMD_EXTENSIONS).performClick()
        rule.waitForIdle()
        row("a.pdf").assert(hasText("a"))
        row("a.pdf").assert(!hasText("a.pdf"))
        row("b.c").assert(hasText("b.c"))
        assertFalse(settings.global.value.showExtensions)
    }

    @Test
    fun restrictedBannerShown() {
        dir("Android/data/com.example")
        show()
        rule.onNodeWithTag(Tags.RESTRICTED_BANNER).assertDoesNotExist()

        vm.navigate(Location.Folder(root.resolve("Android/data")))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.RESTRICTED_BANNER).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.restricted_banner)).assertIsDisplayed()
        row("com.example").assertExists()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp")
    fun expandedWidthPinsNavPane() {
        show(WidthClass.EXPANDED)
        rule.onNodeWithTag(Tags.NAV_PANE).assertIsDisplayed()
        inside(Tags.NAV_PANE, hasText(str(R.string.volume_internal))).assertIsSelected()
        rule.onNodeWithTag(Tags.NAV_MENU).assertDoesNotExist()
        rule.onNodeWithTag(Tags.DRAWER).assertDoesNotExist()
    }

    @Test
    fun crumbChevronListsSubfolders() {
        dir("x")
        dir("y")
        file("z.txt")
        show()
        inside(Tags.BREADCRUMB, hasContentDescription(str(R.string.volume_internal)) and hasRole(Role.DropdownList))
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.crumbChild("x")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.crumbChild("y")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.crumbChild("z.txt")).assertDoesNotExist()

        rule.onNodeWithTag(Tags.crumbChild("y")).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(root.resolve("y")), location())
        rule.onNodeWithTag(Tags.crumbChild("x")).assertDoesNotExist()
    }

    @Test
    fun columnHeaderTapSorts() {
        file("a.txt", 300)
        file("b.txt", 100)
        file("c.txt", 200)
        show()
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), names())

        rule.onNodeWithTag(Tags.header(SortKey.SIZE)).performClick()
        rule.waitForIdle()
        assertEquals(SortSpec(SortKey.SIZE, ascending = true), vm.ui.value.viewSetting.sort)
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), names())

        rule.onNodeWithTag(Tags.header(SortKey.SIZE)).performClick()
        rule.waitForIdle()
        assertEquals(SortSpec(SortKey.SIZE, ascending = false), vm.ui.value.viewSetting.sort)
        assertEquals(listOf("a.txt", "c.txt", "b.txt"), names())
        val tops = listOf("a.txt", "c.txt", "b.txt").map { row(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun dragAlongCheckColumnSelectsRange() {
        for (name in listOf("a", "b", "c", "d", "e")) file("$name.txt")
        show()
        row("a.txt").performTouchInput { longClick() }
        rule.waitForIdle()

        val strip = rule.onNodeWithTag(Tags.CHECK_STRIP).fetchSemanticsNode().boundsInRoot
        fun centerY(name: String) = row(name).fetchSemanticsNode().boundsInRoot.center.y - strip.top
        rule.onNodeWithTag(Tags.CHECK_STRIP).performTouchInput {
            val x = width / 2f
            swipe(Offset(x, centerY("a.txt")), Offset(x, centerY("c.txt")), durationMillis = 400)
        }
        rule.waitForIdle()
        assertEquals(
            listOf("a.txt", "b.txt", "c.txt").map { root.resolve(it) }.toSet(),
            vm.ui.value.tabs.active.selection.items,
        )
    }

    @Test
    fun optionsChangeThemeAndAccent() {
        var dark = false
        var accent = Color.Unspecified
        show(
            probe = {
                val colors = FluentTheme.colors
                SideEffect {
                    dark = colors.isDark
                    accent = colors.accent
                }
            },
        )
        assertFalse(dark)

        openMore()
        rule.onNodeWithTag(Tags.command(Command.OPTIONS)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.theme(ThemeChoice.DARK)).performClick()
        rule.waitForIdle()
        assertTrue(dark)
        assertEquals(ThemeChoice.DARK, settings.global.value.theme)
        rule.onNodeWithTag(Tags.theme(ThemeChoice.DARK)).assertIsSelected()

        rule.onNodeWithTag(Tags.SYSTEM_ACCENT).performClick()
        rule.waitForIdle()
        assertTrue(settings.global.value.systemAccent)
        assertEquals(Color(context.getColor(android.R.color.system_accent1_600)), accent)
        assertTrue(dark)
    }

    @Test
    fun usbAccessDeniedOffersRestart() {
        val usbRoot = Paths.get("/mnt/media_rw/1A2B-3C4D")
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> =
                if (dir.startsWith(usbRoot)) throw AccessDeniedException(dir.toString()) else NioFsBackend.list(dir)
        }
        show(fs = fs, extra = listOf(FakeVolumes.removable("1A2B-3C4D", "USB drive", usbRoot)))
        rule.onNodeWithTag(Tags.NAV_MENU).performClick()
        inside(Tags.DRAWER, hasText("USB drive")).performClick()
        rule.waitForIdle()
        assertEquals(Location.Folder(usbRoot), location())
        rule.onNodeWithText(str(R.string.error_access_denied)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.usb_restart)).assertIsDisplayed()

        inside(Tags.MESSAGE, hasText(str(R.string.restart)) and hasClickAction()).performClick()
        val shadowApp = shadowOf(context as Application)
        val started = generateSequence { shadowApp.nextStartedActivity }.toList()
        val restart = started.single { it.component == ComponentName(context, MainActivity::class.java) }
        assertTrue(restart.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()))
    }

    @Test
    fun ejectedVolumeTabShowsUnavailable() {
        val sd = folder.newFolder("sd").toPath().toRealPath()
        val dcim = Files.createDirectory(sd.resolve("DCIM"))
        Files.createFile(dcim.resolve("photo.jpg"))
        show(extra = listOf(FakeVolumes.removable("1A2B-3C4D", "SD card", sd)))
        vm.navigate(Location.Folder(dcim))
        rule.waitForIdle()
        row("photo.jpg").assertExists()
        rule.onNodeWithTag(Tags.UP).assertIsEnabled()

        volumes.update("1A2B-3C4D") { it.copy(root = null, state = VolumeState.EJECTING) }
        volumes.events.tryEmit(VolumeEvent.Ejecting("1A2B-3C4D"))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.UNAVAILABLE).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.volume_unavailable)).assertIsDisplayed()
        row("photo.jpg").assertDoesNotExist()
        assertEquals(Location.Folder(dcim), location())
        assertEquals(DecodedLocation.Unavailable("1A2B-3C4D", "DCIM"), vm.ui.value.unavailable)
        inside(Tags.BREADCRUMB, hasText("SD card") and hasClickAction()).assertExists()
        // Up never leaves an ejected volume (it would go above the volume root).
        rule.onNodeWithTag(Tags.UP).assertIsNotEnabled()

        volumes.update("1A2B-3C4D") { it.copy(root = sd, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted("1A2B-3C4D"))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.UNAVAILABLE).assertDoesNotExist()
        row("photo.jpg").assertExists()
        assertEquals(Location.Folder(dcim), location())
    }

    @Test
    fun recreatedShellKeepsScrollPosition() {
        repeat(100) { file("f${it.toString().padStart(3, '0')}.txt") }
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)
        rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).performScrollToIndex(50)
        rule.waitForIdle()
        assertEquals("f050.txt", firstShownRow())

        // As when the activity is recreated (rotation, resizing, a dark mode switch): remembered state is gone.
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("f050.txt", firstShownRow())
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
