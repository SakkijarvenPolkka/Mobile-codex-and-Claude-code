// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.Conflict
import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.ConflictKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.allowedActions
import io.github.sakkijarvenpolkka.explorer.ops.CapturedLog
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ConfirmRequest
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.Config
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The dialogs of the file operations, alone and in the shell with real operations (phone-wide window). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h900dp")
class FileOpsUiTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val log = CapturedLog()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)
    private val runnerScope = CoroutineScope(SupervisorJob())

    /** Holds the copies of the gated file system back until it is opened (released after each test). */
    private val gate = CountDownLatch(1)
    private lateinit var root: Path
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
    }

    @After
    fun tearDown() {
        gate.countDown()
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        runnerScope.cancel()
    }

    /** [content] alone, in the light theme. */
    private fun showAlone(content: @Composable () -> Unit) {
        rule.setContent { FluentTheme(dark = false, accent = null, content = content) }
        rule.waitForIdle()
    }

    /** The shell on [root], whose operations run on [fs] on their own threads, with the app's [notice] of them. */
    private fun showShell(fs: FsBackend = NioFsBackend, notice: MutableStateFlow<OpsNotice?> = MutableStateFlow(null)) {
        val volumes = FakeVolumes(FakeVolumes.primary(root))
        val journal = OperationJournal(folder.newFolder("ops"), NioFsBackend)
        val bin = RecycleBin(NioFsBackend, volumes, System::currentTimeMillis, ::newId)
        val fileOps = FileOps(fs, volumes, LABELS, journal, ids = ::newId, bin = bin)
        val runner = OperationRunner(runnerScope, fileOps, journal, Dispatchers.IO, host = null)
        val deps = ExplorerDeps(
            NioFsBackend, settings, volumes, volumes, MimeResolver(), Dispatchers.Unconfined,
            fileOps = fileOps, bin = bin, runner = runner,
            opsNotice = notice, clearOpsNotice = { notice.value = null },
        )
        vm = ExplorerViewModel(ApplicationProvider.getApplicationContext<Application>(), deps)
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, WidthClass.COMPACT) }
        }
        rule.waitForIdle()
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun file(rel: String, content: String = rel): Path = Files.write(root.resolve(rel), content.toByteArray())

    private fun entry(path: Path): FileEntry = Listing.entry(NioFsBackend, path, MimeResolver()::mimeOf)

    private fun row(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.row(name))

    private fun names(): List<String> = vm.ui.value.rows.map { it.entry.name }

    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    private fun inside(tag: String, text: String) = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    /** Waits for [condition], running the main thread's work meanwhile (which waitUntil alone does not here). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) {
        rule.waitForIdle()
        condition()
    }

    @Test
    fun conflictDialogShowsAllowedActionsOnly() {
        val src = entry(file("a.txt"))
        val dir = entry(Files.createDirectory(root.resolve("d")))
        val kind = mutableStateOf(ConflictKind.FILE_FILE)
        showAlone {
            val (source, existing) = if (kind.value == ConflictKind.FILE_FILE) src to src else dir to dir
            ConflictDialog(Conflict(kind.value, source, existing), allowedActions(kind.value), 0, onAnswer = {}, onCancel = {})
        }
        rule.onNodeWithText(str(R.string.conflict_title)).assertExists()
        rule.onNodeWithText(str(R.string.conflict_body, DisplayName.inSentence("a.txt"))).assertExists()
        button(str(R.string.conflict_replace)).assertExists()
        button(str(R.string.conflict_skip)).assertExists()
        button(str(R.string.conflict_keep_both)).assertExists()
        button(str(R.string.conflict_merge)).assertDoesNotExist()

        kind.value = ConflictKind.DIR_DIR
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.conflict_folder_title)).assertExists()
        button(str(R.string.conflict_merge)).assertExists()
        button(str(R.string.conflict_skip)).assertExists()
        button(str(R.string.conflict_replace)).assertDoesNotExist()
        button(str(R.string.conflict_keep_both)).assertDoesNotExist()
    }

    @Test
    fun applyToAllCheckboxSendsFlag() {
        val a = entry(file("a.txt"))
        val answers = mutableListOf<Resolution>()
        showAlone {
            val kind = ConflictKind.FILE_FILE
            ConflictDialog(Conflict(kind, a, a), allowedActions(kind), 3, onAnswer = { answers += it }, onCancel = {})
        }
        button(str(R.string.conflict_skip)).performClick()
        // No count: how many of the items to come clash is not known.
        rule.onNodeWithTag(Tags.APPLY_TO_ALL).assert(hasText(str(R.string.conflict_apply_all))).performClick()
        button(str(R.string.conflict_keep_both)).performClick()

        assertEquals(
            listOf(Resolution(ConflictAction.SKIP, applyToAll = false), Resolution(ConflictAction.KEEP_BOTH, applyToAll = true)),
            answers,
        )
    }

    @Test
    fun renameDialogPreselectsStem() {
        val pdf = entry(file("report.pdf"))
        val dir = entry(Files.createDirectory(root.resolve("My.Folder")))
        val shown = mutableStateOf(pdf)
        showAlone { RenameDialog(shown.value, VolumeTraits(), onCommit = {}, onDismiss = {}) }
        rule.onNodeWithTag(Tags.RENAME_FIELD).assert(selection(TextRange(0, 6)))

        // A folder's name is all selected, dots and all.
        shown.value = dir
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.RENAME_FIELD).assert(selection(TextRange(0, 9)))
    }

    @Test
    fun renameShowsFatErrorWhileTyping() {
        val a = entry(file("a.txt"))
        val committed = mutableListOf<String>()
        showAlone { RenameDialog(a, VolumeTraits(fatNames = true), onCommit = { committed += it }, onDismiss = {}) }
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("a:b.txt")
        rule.onNodeWithText(str(R.string.name_fat_chars)).assertExists()
        button(str(R.string.ok)).assertIsNotEnabled()

        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("ab.txt")
        rule.onNodeWithText(str(R.string.name_fat_chars)).assertDoesNotExist()
        button(str(R.string.ok)).assertIsEnabled().performClick()
        assertEquals(listOf("ab.txt"), committed)
    }

    @Test
    fun renameOkDisabledWhilePending() {
        val a = entry(file("a.txt"))
        val committed = mutableListOf<String>()
        val pending = mutableStateOf(false)
        showAlone {
            RenameDialog(a, VolumeTraits(), onCommit = { committed += it }, onDismiss = {}, pending = pending.value)
        }
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("b.txt")
        pending.value = true
        rule.waitForIdle()
        // Under way: neither OK nor Done renames it again.
        button(str(R.string.ok)).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performImeAction()
        assertEquals(emptyList<String>(), committed)

        pending.value = false
        rule.waitForIdle()
        button(str(R.string.ok)).assertIsEnabled().performClick()
        assertEquals(listOf("b.txt"), committed)
    }

    @Test
    fun extensionChangeAsksConfirm() {
        file("report.pdf")
        showShell()
        row("report.pdf").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.command(Command.RENAME)).performClick()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("report.txt")
        button(str(R.string.ok)).performClick()
        rule.onNodeWithText(str(R.string.ext_change)).assertExists()

        // No goes back to the name as typed.
        button(str(R.string.no)).performClick()
        rule.onNodeWithText(str(R.string.ext_change)).assertDoesNotExist()
        rule.onNodeWithTag(Tags.RENAME_FIELD).assert(hasText("report.txt"))
        button(str(R.string.ok)).performClick()
        button(str(R.string.yes)).performClick()
        waitFor { names() == listOf("report.txt") }
        rule.onNodeWithTag(Tags.RENAME_FIELD).assertDoesNotExist()
        assertFalse(Files.exists(root.resolve("report.pdf")))
    }

    @Test
    fun progressDialogMinimizesToChip() {
        file("a.txt", "content")
        Files.createDirectory(root.resolve("dst"))
        showShell(GatedFs(gate))
        row("a.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.command(Command.COPY)).performClick()
        vm.navigate(Location.Folder(root.resolve("dst")))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.command(Command.PASTE)).performClick()
        waitFor { vm.runner.value.running?.progress != null }

        rule.onNodeWithTag(Tags.PROGRESS).assertExists()
        rule.onNodeWithText(str(R.string.progress_title_copy, 1)).assertExists()
        rule.onNodeWithTag(Tags.PROGRESS_CHIP).assertDoesNotExist()
        button(str(R.string.progress_background)).performClick()
        rule.onNodeWithTag(Tags.PROGRESS).assertDoesNotExist()
        rule.onNodeWithTag(Tags.PROGRESS_CHIP).assertExists()

        // The chip shows the dialog again, which can cancel the operation.
        rule.onNodeWithTag(Tags.PROGRESS_CHIP).performClick()
        rule.onNodeWithTag(Tags.PROGRESS).assertExists()
        button(str(R.string.cancel)).performClick()
        gate.countDown()
        waitFor { vm.runner.value.running == null }
        rule.onNodeWithTag(Tags.PROGRESS).assertDoesNotExist()
        rule.onNodeWithTag(Tags.PROGRESS_CHIP).assertDoesNotExist()
    }

    @Test
    fun recycleBinViewRestoreAndEmpty() {
        file("a.txt")
        file("b.txt")
        showShell()
        row("a.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.CMD_DELETE).performClick()
        waitFor { names() == listOf("b.txt") }

        // To the Recycle Bin through the navigation pane (a drawer on this width).
        rule.onNodeWithTag(Tags.NAV_MENU).performClick()
        inside(Tags.DRAWER, str(R.string.nav_recycle_bin)).performClick()
        waitFor { names() == listOf("a.txt") }
        assertEquals(Location.RecycleBin, vm.ui.value.location)
        inside(Tags.RECYCLE_BIN, DisplayName.of(root.toString())).assertExists()

        row("a.txt").performClick()
        rule.onNodeWithTag(Tags.command(Command.RESTORE)).performClick()
        waitFor { Files.exists(root.resolve("a.txt")) && names().isEmpty() }

        // Back to the folder, delete b.txt, and empty the bin.
        vm.back()
        rule.waitForIdle()
        row("b.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.CMD_DELETE).performClick()
        waitFor { names() == listOf("a.txt") }
        vm.forward()
        waitFor { names() == listOf("b.txt") }
        rule.onNodeWithTag(Tags.command(Command.EMPTY_BIN)).performClick()
        rule.onNodeWithText(str(R.string.empty_bin_body, 1)).assertExists()
        rule.onNodeWithText(str(R.string.cannot_undo)).assertExists()
        button(str(R.string.yes)).performClick()
        waitFor { names().isEmpty() }
        assertFalse(Files.exists(root.resolve("b.txt")))
        assertTrue(Files.exists(root.resolve("a.txt")))
    }

    @Test
    fun deletingForGoodSaysHowManyAndThatItCannotBeUndone() {
        val a = file("a.txt")
        val b = file("b.txt")
        val request = mutableStateOf<ConfirmRequest>(ConfirmRequest.PermanentDelete(listOf(a, b), 2))
        showAlone { ConfirmDialog(request.value, onAnswer = {}) }
        rule.onNodeWithText(str(R.string.delete_permanent_body, 2)).assertExists()

        // The Recycle Bin could not take them: asked how many, once, and that it cannot be undone.
        request.value = ConfirmRequest.BinUnavailable(listOf(a, b))
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.bin_unavailable, 2)).assertExists()
        rule.onNodeWithText(str(R.string.cannot_undo)).assertExists()
        rule.onNodeWithText(str(R.string.delete_permanent_body, 2)).assertDoesNotExist()
    }

    @Test
    fun emptyBinDialogDoesNotRepeat() {
        showAlone { ConfirmDialog(ConfirmRequest.EmptyBin(3), onAnswer = {}) }
        rule.onNodeWithText(str(R.string.empty_bin_body, 3)).assertExists()
        rule.onNodeWithText(str(R.string.cannot_undo)).assertExists()
        // Not asked twice.
        rule.onNodeWithText(str(R.string.delete_permanent_body, 3)).assertDoesNotExist()
    }

    @Test
    fun renameFailureKeepsDialogAndText() {
        file("a.txt")
        file("b.txt")
        showShell()
        row("a.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.command(Command.RENAME)).performClick()
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("b.txt")
        button(str(R.string.ok)).performClick()
        waitFor { vm.ui.value.renameError != null }

        // Still open, with the name as typed and why it failed under it.
        rule.onNodeWithTag(Tags.RENAME_FIELD).assert(hasText("b.txt"))
        rule.onNodeWithText(str(R.string.err_already_exists)).assertExists()
        assertTrue(Files.exists(root.resolve("a.txt")))

        // The error is about that name: another one clears it, and is used.
        rule.onNodeWithTag(Tags.RENAME_FIELD).performTextReplacement("c.txt")
        rule.onNodeWithText(str(R.string.err_already_exists)).assertDoesNotExist()
        button(str(R.string.ok)).performClick()
        waitFor { names() == listOf("b.txt", "c.txt") }
        rule.onNodeWithTag(Tags.RENAME_FIELD).assertDoesNotExist()
    }

    @Test
    fun failuresListTellsWhatWasCopiedButKept() {
        val a = root.resolve("a.txt").toString()
        val b = root.resolve("b.txt").toString()
        val spec = OperationSpec("op", OpKind.MOVE, listOf(a, b), root.resolve("dst").toString())
        val failed = listOf(
            ItemFailure(a, FsError.AccessDenied, copiedButSourceKept = true),
            ItemFailure(b, FsError.AccessDenied),
        )
        showAlone { FailuresDialog(FinishedOp(spec, OpResult.Done(emptyList(), failed)), onRetry = {}, onDismiss = {}) }

        // Each says why; the one copied whose original stays says so too.
        rule.onAllNodesWithText(str(R.string.error_access_denied)).assertCountEquals(2)
        rule.onAllNodesWithText(str(R.string.failure_source_kept)).assertCountEquals(1)
    }

    @Test
    fun opsNoticeHasItsOwnBar() {
        showShell(notice = MutableStateFlow(OpsNotice.ForegroundRefused))
        vm.typedPath("/proc")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.fgs_refused)).assertExists()
        rule.onNodeWithText(str(R.string.outside_storage)).assertExists()

        // The notice is above the message, so its close button comes first.
        val closeButtons = rule.onAllNodes(hasContentDescription(str(R.string.close)) and hasClickAction())
        closeButtons[0].performClick()
        rule.onNodeWithText(str(R.string.fgs_refused)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.outside_storage)).assertExists()
    }

    @Test
    fun noSpaceBarSaysWhatEmptyingTheBinFrees() {
        file("a.txt")
        file("b.txt", "12345")
        val full = Files.createDirectory(root.resolve("full"))
        showShell(object : FsBackend by NioFsBackend {
            override fun availableBytes(path: Path): Long =
                if (path.startsWith(full)) 1L else NioFsBackend.availableBytes(path)
        })
        row("b.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.CMD_DELETE).performClick()
        waitFor { names() == listOf("full", "a.txt") }
        row("a.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.command(Command.COPY)).performClick()
        vm.navigate(Location.Folder(full))
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.command(Command.PASTE)).performClick()
        waitFor { vm.ui.value.message?.hint != null }

        // How far it got, why it stopped, and what would help, each on a line of its own.
        val stopped = str(R.string.op_fatal, 0, 1, str(R.string.op_name_copy))
        val lines = listOf(stopped, str(R.string.err_no_space), str(R.string.hint_empty_bin, "5 bytes"))
        inside(Tags.MESSAGE, lines.joinToString("\n")).assertExists()
        // The size is said in the language of the app.
        val korean = context.createConfigurationContext(Configuration().apply { setLocale(Locale.KOREAN) })
        assertEquals("5바이트", UiText.size(5).resolve(korean.resources))
    }

    private fun selection(range: TextRange) = SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, range)

    /** [NioFsBackend], but a file is opened for reading only once [gate] is open. */
    private class GatedFs(private val gate: CountDownLatch) : FsBackend by NioFsBackend {
        override fun openRead(path: Path): InputStream {
            gate.await(10, TimeUnit.SECONDS)
            return NioFsBackend.openRead(path)
        }
    }

    private companion object {
        val LABELS = NameLabels(newFolder = "New folder", newTextDocument = "New Text Document", copySuffix = " - Copy")
    }
}
