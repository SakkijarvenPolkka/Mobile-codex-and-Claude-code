// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Paths

/** The Fluent theme, icons and base components: behaviour and accessibility semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FluentComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { FluentTheme(dark = false, accent = null, content = content) }
    }

    @Test
    fun buttonHasButtonRoleAndMinTouchTarget() {
        show {
            Column {
                FluentButton(text = "OK", onClick = {})
                FluentButton(
                    text = null,
                    onClick = {},
                    icon = FluentIcons.Delete,
                    style = ButtonStyle.SUBTLE,
                    contentDescription = "Delete",
                )
                FluentButton(text = "Save", onClick = {}, style = ButtonStyle.ACCENT)
            }
        }
        listOf(
            rule.onNodeWithText("OK"),
            rule.onNodeWithContentDescription("Delete"),
            rule.onNodeWithText("Save"),
        ).forEach { button ->
            button.assert(isButton).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun disabledButtonDoesNotClick() {
        var disabledClicks = 0
        var enabledClicks = 0
        show {
            Column {
                FluentButton(text = "Paste", onClick = { disabledClicks++ }, enabled = false)
                FluentButton(text = "Copy", onClick = { enabledClicks++ })
            }
        }
        rule.onNodeWithText("Paste").assertIsNotEnabled().performClick()
        rule.onNodeWithText("Copy").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(0, disabledClicks)
            assertEquals(1, enabledClicks)
        }
    }

    @Test
    fun listRowExposesSelectedAndToggleableInSelectionMode() {
        show {
            Column {
                ListRow(selected = true, selectionMode = true, onClick = {}, onLongClick = {}, stateDescription = "Selected") {
                    BasicText("photo.jpg")
                }
                ListRow(selected = false, selectionMode = true, onClick = {}, onLongClick = {}, stateDescription = "Not selected") {
                    BasicText("notes.txt")
                }
                ListRow(selected = true, selectionMode = false, onClick = {}, onLongClick = {}) { BasicText("Music") }
                ListRow(selected = false, selectionMode = false, onClick = {}, onLongClick = {}) { BasicText("Videos") }
            }
        }
        rule.onNodeWithText("photo.jpg")
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("notes.txt")
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))
        rule.onNodeWithText("Music")
            .assertIsSelected()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        rule.onNodeWithText("Videos")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test
    fun listRowLongClickCallsOnLongClick() {
        var clicks = 0
        var longClicks = 0
        show {
            ListRow(selected = false, selectionMode = false, onClick = { clicks++ }, onLongClick = { longClicks++ }) {
                BasicText("report.pdf")
            }
        }
        rule.onNodeWithText("report.pdf").performTouchInput { longClick() }
        rule.runOnIdle {
            assertEquals(1, longClicks)
            assertEquals(0, clicks)
        }
        rule.onNodeWithText("report.pdf").performClick()
        rule.runOnIdle {
            assertEquals(1, clicks)
            assertEquals(1, longClicks)
        }
    }

    @Test
    fun commandBarMovesOverflowIntoMoreMenu() {
        val ran = mutableListOf<String>()
        fun command(id: String, label: String, icon: ImageVector?, enabled: Boolean = true) =
            CommandItem(id, label, icon, enabled) { ran += id }
        val primary = listOf(
            command("cut", "Cut", FluentIcons.Cut),
            command("copy", "Copy", FluentIcons.Copy),
            command("paste", "Paste", FluentIcons.Paste),
            command("rename", "Rename", FluentIcons.Rename),
            command("delete", "Delete", FluentIcons.Delete),
        )
        val overflow = listOf(
            command("select_all", "Select all", null),
            command("properties", "Properties", null, enabled = false),
        )
        // Three 48 dp command buttons and the 48 dp "See more" button fit into 200 dp; four do not.
        show { Box(Modifier.width(200.dp)) { CommandBar(primary, overflow, moreLabel = "See more") } }

        listOf("Cut", "Copy", "Paste").forEach { rule.onNodeWithContentDescription(it).assertIsDisplayed() }
        rule.onNodeWithTag("cut").assertIsDisplayed()
        rule.onNodeWithContentDescription("Rename").assertDoesNotExist()
        rule.onNodeWithContentDescription("Delete").assertDoesNotExist()
        rule.onNodeWithText("Select all").assertDoesNotExist()

        rule.onNodeWithContentDescription("See more").assert(isButton).performClick()
        listOf("Rename", "Delete", "Select all", "Properties").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText("Properties").assertIsNotEnabled()
        rule.onNodeWithTag("delete").assertIsDisplayed()

        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle { assertEquals(listOf("delete"), ran) }
        rule.onNodeWithText("Select all").assertDoesNotExist()
    }

    @Test
    fun commandBarShowsEveryCommandWithoutMoreWhenAllFit() {
        val primary = listOf(
            CommandItem("cut", "Cut", FluentIcons.Cut, enabled = true) {},
            CommandItem("sort", "Sort", null, enabled = true) {},
        )
        show { CommandBar(primary, overflow = emptyList(), moreLabel = "See more") }
        rule.onNodeWithContentDescription("Cut").assertIsDisplayed()
        rule.onNodeWithText("Sort").assertIsDisplayed().assert(isButton)
        rule.onNodeWithContentDescription("See more").assertDoesNotExist()
    }

    @Test
    fun contentDialogButtonsInvokeCallbacks() {
        val calls = mutableListOf<String>()
        show {
            ContentDialog(
                title = "Replace or skip files",
                onDismiss = { calls += "dismiss" },
                primary = "Replace" to { calls += "primary" },
                secondary = "Skip" to { calls += "secondary" },
                close = "Cancel" to { calls += "close" },
            ) {
                BasicText("The destination already has a file named a.txt")
            }
        }
        rule.onNodeWithText("Replace or skip files").assertIsDisplayed()
        rule.onNodeWithText("The destination already has a file named a.txt").assertIsDisplayed()
        rule.onNodeWithText("Replace").assert(isButton).performClick()
        rule.onNodeWithText("Skip").assert(isButton).performClick()
        rule.onNodeWithText("Cancel").assert(isButton).performClick()
        rule.runOnIdle { assertEquals(listOf("primary", "secondary", "close"), calls) }
    }

    @Test
    fun iconForPicksByMimeAndKind() {
        fun entry(name: String, kind: EntryKind = EntryKind.FILE, mime: String? = null) = FileEntry(
            path = Paths.get("/storage/emulated/0", name),
            name = name,
            kind = kind,
            size = 0,
            modified = 0,
            mime = mime,
            hidden = name.startsWith("."),
            fileKey = null,
        )
        assertSame(FluentIcons.Folder, iconFor(entry("DCIM", EntryKind.DIR)))
        assertSame(FluentIcons.Folder, iconFor(entry("photos.zip", EntryKind.DIR)))
        assertSame(FluentIcons.Image, iconFor(entry("photo", mime = "image/png")))
        assertSame(FluentIcons.Image, iconFor(entry("photo.png", mime = "image/png")))
        assertSame(FluentIcons.Zip, iconFor(entry("backup.zip")))
        assertSame(FluentIcons.Zip, iconFor(entry("BACKUP.ZIP")))
        assertSame(FluentIcons.Zip, iconFor(entry("backup", mime = "application/zip")))
        assertSame(FluentIcons.Apk, iconFor(entry("app.apk")))
        assertSame(FluentIcons.Apk, iconFor(entry("app", mime = "application/vnd.android.package-archive")))
        assertSame(FluentIcons.Pdf, iconFor(entry("manual.pdf")))
        assertSame(FluentIcons.Video, iconFor(entry("clip.mp4")))
        assertSame(FluentIcons.Audio, iconFor(entry("song", mime = "audio/mpeg")))
        assertSame(FluentIcons.Text, iconFor(entry("notes.txt", mime = "text/plain")))
        assertSame(FluentIcons.Code, iconFor(entry("Main.kt", mime = "text/plain")))
        assertSame(FluentIcons.Text, iconFor(entry("readme", mime = "text/plain; charset=utf-8")))
        assertSame(FluentIcons.File, iconFor(entry("data.xyz")))
        assertSame(FluentIcons.File, iconFor(entry("blob", mime = "application/octet-stream")))
        assertSame(FluentIcons.File, iconFor(entry(".zip")))
        assertSame(FluentIcons.File, iconFor(entry("link.png", EntryKind.SYMLINK)))
    }

    @Test
    fun iconsHaveContentDescriptionsWhenGiven() {
        show {
            Row {
                FluentIcon(FluentIcons.Star, contentDescription = "Favorites")
                FluentIcon(FluentIcons.Folder, contentDescription = null, modifier = Modifier.testTag("decorative"))
                FluentButton(text = null, onClick = {}, icon = FluentIcons.Refresh, contentDescription = "Refresh")
            }
        }
        rule.onNodeWithContentDescription("Favorites")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        rule.onNodeWithTag("decorative")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        rule.onNodeWithContentDescription("Refresh").assert(isButton).assertHasClickAction()
    }

    @Test
    fun everyIconIsATwentyFourDpVectorThatDraws() {
        val icons = with(FluentIcons) {
            listOf(
                Folder, FolderOpen, File, Image, Video, Audio, Text, Pdf, Zip, Apk, Code, Drive, SdCard, Usb,
                RecycleBin, Home, Star, Back, Forward, Up, Menu, More, Add, Cut, Copy, Paste, Rename, Share,
                Delete, Sort, View, Refresh, Search, Close, Check, ChevronRight, ChevronDown, Info, Warning,
                Play, Pause, Restore,
            )
        }
        assertEquals(42, icons.size)
        assertEquals(42, icons.map { it.name }.toSet().size)
        icons.forEach {
            assertEquals(it.name, 24.dp, it.defaultWidth)
            assertEquals(it.name, 24.dp, it.defaultHeight)
            assertEquals(it.name, 24f, it.viewportWidth)
            assertEquals(it.name, 24f, it.viewportHeight)
        }
        show { Column { icons.forEach { FluentIcon(it, contentDescription = it.name) } } }
        rule.onNodeWithContentDescription(FluentIcons.Restore.name).assertExists()
    }

    @Test
    fun themeProvidesSpecColorsShapesAndTypeRamp() {
        val light = FluentColorsDefaults.light()
        assertEquals(Color(0xFFF3F3F3), light.background)
        assertEquals(Color(0xFFFBFBFB), light.card)
        assertEquals(Color(0xFF0067C0), light.accent)
        assertEquals(Color.White, light.onAccent)
        assertEquals(light.accent.copy(alpha = 0.10f), light.selection)
        assertFalse(light.isDark)
        val dark = FluentColorsDefaults.dark()
        assertEquals(Color(0xFF202020), dark.background)
        assertEquals(Color(0xFF2B2B2B), dark.card)
        assertEquals(Color(0xFF4CC2FF), dark.accent)
        assertEquals(Color.Black, dark.onAccent)
        assertTrue(dark.isDark)

        var provided: FluentColors? = null
        rule.setContent {
            FluentTheme(dark = true, accent = Color(0xFF00B294)) { provided = FluentTheme.colors }
        }
        rule.runOnIdle {
            val colors = checkNotNull(provided)
            assertTrue(colors.isDark)
            assertEquals(Color(0xFF202020), colors.background)
            assertEquals(Color(0xFF00B294), colors.accent)
            assertEquals(Color(0xFF00B294).copy(alpha = 0.10f), colors.selection)
        }
        assertEquals(RoundedCornerShape(4.dp), FluentTheme.shapes.control)
        assertEquals(RoundedCornerShape(8.dp), FluentTheme.shapes.overlay)
        assertEquals(
            listOf(12.sp, 14.sp, 14.sp, 20.sp, 28.sp),
            with(FluentTheme.type) { listOf(caption, body, bodyStrong, subtitle, title).map { it.fontSize } },
        )
    }

    @Test
    fun breadcrumbRoutesCrumbChevronAndEmptySpace() {
        val events = mutableListOf<String>()
        val crumbs = listOf(Crumb("PC", "pc"), Crumb("SD", "sd"))
        show {
            Box(Modifier.fillMaxWidth()) {
                Breadcrumb(
                    crumbs,
                    onCrumb = { events += "crumb:${it.key}" },
                    onChildren = { events += "children:${it.key}" },
                    onEdit = { events += "edit" },
                )
            }
        }
        rule.onNodeWithText("SD").assert(isButton).performClick()
        rule.onNodeWithContentDescription("PC")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithContentDescription("SD").performClick()
        rule.onNodeWithContentDescription("PC › SD").performTouchInput { click(Offset(width - 4f, centerY)) }
        rule.runOnIdle { assertEquals(listOf("crumb:sd", "children:pc", "children:sd", "edit"), events) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun textBoxShowsPlaceholderSubmitsAndExposesError() {
        var submitted = 0
        show {
            var value by remember { mutableStateOf(TextFieldValue("")) }
            Column {
                FluentTextBox(value, { value = it }, Modifier.testTag("name"), placeholder = "Name", onSubmit = { submitted++ })
                FluentTextBox(TextFieldValue("a/b"), {}, Modifier.testTag("bad"), error = "A name can't contain /")
            }
        }
        rule.onNodeWithText("Name").assertIsDisplayed()
        rule.onNodeWithTag("name").performTextInput("photos")
        rule.onNodeWithTag("name")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("photos")))
        rule.onNodeWithText("Name").assertDoesNotExist()
        rule.onNodeWithTag("name").performImeAction()
        rule.runOnIdle { assertEquals(1, submitted) }
        // A hardware Enter submits a single-line box too (the address bar: Ctrl+L, type, Enter).
        rule.onNodeWithTag("name").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(2, submitted) }
        rule.onNodeWithTag("bad")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "A name can't contain /"))
        rule.onNodeWithText("A name can't contain /").assertIsDisplayed()
    }

    @Test
    fun infoBarAnnouncesMessageAndRunsActionAndClose() {
        val events = mutableListOf<String>()
        show {
            InfoBar(
                InfoSeverity.WARNING,
                "Restart the app to open this drive",
                action = "Restart" to { events += "action" },
                onClose = { events += "close" },
            )
        }
        rule.onNodeWithText("Restart the app to open this drive")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("Restart").assert(isButton).performClick()
        rule.onNodeWithContentDescription("Close").assert(isButton).performClick()
        rule.runOnIdle { assertEquals(listOf("action", "close"), events) }
    }

    @Test
    fun progressBarExposesDeterminateAndIndeterminateProgress() {
        show {
            Column {
                FluentProgressBar(0.25f, Modifier.testTag("quarter"))
                FluentProgressBar(null, Modifier.testTag("busy"))
            }
        }
        rule.onNodeWithTag("quarter").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f)),
        )
        rule.onNodeWithTag("busy").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
    }

    @Test
    fun toggleSwitchHasSwitchRoleAndReportsTheNewValue() {
        val changes = mutableListOf<Boolean>()
        show {
            Column {
                FluentToggleSwitch(checked = false, onCheckedChange = { changes += it }, label = "Hidden items")
                FluentToggleSwitch(checked = true, onCheckedChange = { changes += it }, label = "File name extensions")
            }
        }
        rule.onNodeWithText("Hidden items")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText("File name extensions").assertIsOn().performClick()
        rule.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test
    fun navPaneMarksSelectionAndDisablesUnavailableItems() {
        val picked = mutableListOf<Any>()
        val sections = listOf(
            NavSection(null, listOf(NavItem("home", "Home", FluentIcons.Home))),
            NavSection(
                "This PC",
                listOf(
                    NavItem("internal", "Internal storage", FluentIcons.Drive, sublabel = "3.50 GB free"),
                    NavItem("sd", "SD card", FluentIcons.SdCard, enabled = false),
                ),
            ),
        )
        show { NavPane(sections, selectedKey = "internal", onSelect = { picked += it.key }) }
        rule.onNodeWithText("Internal storage")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("3.50 GB free").assertIsDisplayed()
        rule.onNodeWithText("This PC").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("Home").assertIsNotSelected().performClick()
        rule.onNodeWithText("SD card").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf<Any>("home"), picked) }
    }
}
