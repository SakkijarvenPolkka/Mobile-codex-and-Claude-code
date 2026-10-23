// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.ConflictKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.ops.CapturedLog
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The file operations as the shell asks for them, with a real [FileOps], Recycle Bin and runner on a temporary volume. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplorerViewModelOpsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val log = CapturedLog()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Where the folders are read and the operations run: only when the test drains it. */
    private val io = StandardTestDispatcher()
    private val runnerScope = CoroutineScope(SupervisorJob())
    private lateinit var root: Path
    private lateinit var journalDir: File
    private lateinit var bin: RecycleBin
    private lateinit var runner: OperationRunner
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        journalDir = folder.newFolder("ops")
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        runnerScope.cancel()
    }

    /** A view model on [root] as the primary volume and [extra] volumes, with the app's parts over them. */
    private fun start(extra: List<Volume> = emptyList()) {
        val volumes = FakeVolumes(FakeVolumes.primary(root), *extra.toTypedArray())
        val journal = OperationJournal(journalDir, NioFsBackend)
        bin = RecycleBin(NioFsBackend, volumes, { 1_700_000_000_000L }, ::newId)
        val fileOps = FileOps(NioFsBackend, volumes, LABELS, journal, ids = ::newId, bin = bin)
        runner = OperationRunner(runnerScope, fileOps, journal, io, host = null)
        val deps = ExplorerDeps(
            NioFsBackend, SettingsStore(null), volumes, volumes, MimeResolver(), io,
            fileOps = fileOps, bin = bin, runner = runner,
        )
        vm = ExplorerViewModel(app, deps)
        drain()
    }

    private fun newId() = UUID.randomUUID().toString()

    /** Runs what is queued on [io], then on the main thread, a few rounds over: each hands work to the other. */
    private fun drain() {
        repeat(8) {
            io.scheduler.advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String, content: String = rel): Path = Files.write(root.resolve(rel), content.toByteArray())

    private fun read(path: Path): String = String(Files.readAllBytes(path))

    private val ui get() = vm.ui.value

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    /** Selects [names] in this order, as long presses and taps would. */
    private fun select(vararg names: String) {
        vm.onLongPress(entry(names.first()))
        names.drop(1).forEach { vm.onTap(entry(it)) }
    }

    private fun goTo(path: Path) {
        vm.navigate(Location.Folder(path))
        drain()
    }

    @Test
    fun copyPasteIntoSameFolderCreatesCopy() {
        file("a.txt", "a")
        start()
        select("a.txt")
        vm.copy()
        vm.paste()
        drain()

        assertEquals(listOf("a - Copy.txt", "a.txt"), names())
        assertEquals("a", read(root.resolve("a - Copy.txt")))
        // What the operation made is the new selection; a copy stays on the clipboard.
        assertEquals(listOf(root.resolve("a - Copy.txt")), ui.selected.map { it.path })
        assertEquals(Clipboard(listOf(root.resolve("a.txt")), ClipMode.COPY), ui.clipboard)
        assertNull(ui.message)
    }

    @Test
    fun cutPasteMovesAndClearsMovedOnly() {
        file("a.txt")
        file("b.txt")
        val dst = dir("dst")
        file("dst/b.txt", "old")
        start()
        select("a.txt", "b.txt")
        vm.cut()
        goTo(dst)
        vm.paste()
        drain()

        val pending = checkNotNull(vm.runner.value.pending) { "No conflict waits" }
        assertEquals(ConflictKind.FILE_FILE, pending.conflict.kind)
        assertEquals("b.txt", pending.conflict.existing.name)
        pending.answer(Resolution(ConflictAction.SKIP))
        drain()

        assertEquals(listOf("a.txt", "b.txt"), names())
        assertEquals("old", read(dst.resolve("b.txt")))
        assertFalse(Files.exists(root.resolve("a.txt")))
        assertTrue(Files.exists(root.resolve("b.txt")))
        // The skipped item stays cut, for another try.
        assertEquals(Clipboard(listOf(root.resolve("b.txt")), ClipMode.CUT), ui.clipboard)
    }

    @Test
    fun pasteRevalidatesClipboard() {
        file("a.txt")
        file("b.txt")
        val dst = dir("dst")
        start()
        select("a.txt", "b.txt")
        vm.cut()
        Files.delete(root.resolve("a.txt"))
        goTo(dst)
        vm.paste()
        drain()

        assertEquals(listOf("b.txt"), names())
        assertNull(ui.message)
        assertNull(ui.failedOp)
        assertNull(ui.clipboard)
    }

    @Test
    fun deleteMovesToBinWithoutConfirm() {
        file("a.txt")
        file("b.txt")
        start()
        select("a.txt")
        vm.delete(permanent = false)
        assertNull(ui.confirm)
        drain()

        assertEquals(listOf("b.txt"), names())
        assertEquals(listOf("a.txt"), bin.list(listOf(root)).map { it.name })
        assertNull(ui.message)
    }

    @Test
    fun permanentDeleteNeedsConfirm() {
        val a = file("a.txt")
        start()
        select("a.txt")
        vm.delete(permanent = true)
        drain()
        assertEquals(ConfirmRequest.PermanentDelete(listOf(a), 1), ui.confirm)
        assertTrue(Files.exists(a))

        // No keeps it.
        vm.answerConfirm(yes = false)
        drain()
        assertNull(ui.confirm)
        assertTrue(Files.exists(a))

        vm.delete(permanent = true)
        vm.answerConfirm(yes = true)
        drain()
        assertNull(ui.confirm)
        assertFalse(Files.exists(a))
        assertEquals(emptyList<String>(), names())
        assertEquals(0, bin.list(listOf(root)).size)
    }

    @Test
    fun binUnavailableAsksPermanent() {
        val a = file("a.txt")
        // Something that is not a folder where the Recycle Bin goes: nothing can go in.
        Files.write(root.resolve(HiddenNames.RECYCLE_BIN), ByteArray(0))
        start()
        select("a.txt")
        vm.delete(permanent = false)
        drain()

        assertEquals(ConfirmRequest.BinUnavailable(listOf(a)), ui.confirm)
        assertTrue(Files.exists(a))
        assertNull(ui.failedOp)
        vm.answerConfirm(yes = true)
        drain()
        assertNull(ui.confirm)
        assertFalse(Files.exists(a))
    }

    @Test
    fun renameBusyPathRefused() {
        val a = file("a.txt", "new")
        val dst = dir("dst")
        file("dst/a.txt", "old")
        start()
        select("a.txt")
        vm.copy()
        goTo(dst)
        vm.paste()
        drain()
        // The copy waits for an answer about dst/a.txt: it is still running on a.txt.
        assertNotNull(vm.runner.value.pending)
        vm.back()
        drain()

        vm.startRename(entry("a.txt"))
        assertNull(ui.renaming)
        assertEquals(UiText.res(R.string.err_busy), ui.message?.text)

        // Asked for once the dialog was open, it is refused too.
        vm.dismissMessage()
        vm.commitRename(entry("a.txt"), "b.txt")
        drain()
        assertEquals(UiText.res(R.string.err_busy), ui.message?.text)
        assertTrue(Files.exists(a))
        assertFalse(Files.exists(root.resolve("b.txt")))
        vm.runner.value.pending?.answer(Resolution(ConflictAction.SKIP))
        drain()
    }

    @Test
    fun newFolderStartsRename() {
        file("a.txt")
        start()
        vm.newFolder()
        drain()

        val made = root.resolve("New folder")
        assertTrue(Files.isDirectory(made))
        assertEquals(listOf("New folder", "a.txt"), names())
        assertEquals(listOf(made), ui.selected.map { it.path })
        assertEquals(made, ui.renaming?.path)

        vm.commitRename(ui.renaming!!, "Docs")
        drain()
        assertNull(ui.renaming)
        assertEquals(listOf("Docs", "a.txt"), names())
        assertEquals(listOf(root.resolve("Docs")), ui.selected.map { it.path })
    }

    @Test
    fun finishedOpRefreshesEveryTabShowingAffectedFolder() {
        file("a.txt")
        val dst = dir("dst")
        start()
        val first = ui.tabs.activeId
        select("a.txt")
        vm.copy()
        goTo(dst)
        vm.dispatch(TabAction.Duplicate(first))
        drain()
        val second = ui.tabs.activeId
        assertEquals(emptyList<String>(), names())

        vm.dispatch(TabAction.Activate(first))
        drain()
        vm.paste()
        drain()
        assertEquals(listOf("a.txt"), names())

        vm.dispatch(TabAction.Activate(second))
        drain()
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun interruptedOpShowsRetryBar() {
        val a = file("a.txt")
        val b = file("b.txt")
        val dst = dir("dst")
        interruptedCopy(listOf(a, b), dst, committed = 1)
        start()
        runner.loadInterrupted()
        drain()

        val message = checkNotNull(ui.message) { "No message" }
        assertEquals(UiText.res(R.string.op_interrupted, 1, 2), message.text)
        assertEquals(InfoSeverity.WARNING, message.severity)
        val (label, retry) = checkNotNull(message.action) { "No action" }
        assertEquals(UiText.res(R.string.retry), label)

        retry()
        drain()
        // Only what it had not done: the second source.
        assertTrue(Files.exists(dst.resolve("b.txt")))
        assertFalse(Files.exists(dst.resolve("a.txt")))
        assertNull(ui.message)
        assertEquals(emptyList<Any>(), vm.runner.value.interrupted)
    }

    @Test
    fun unboundMessageSurvivesNavigation() {
        val a = file("a.txt")
        val dst = dir("dst")
        interruptedCopy(listOf(a), dst, committed = 0)
        start()
        runner.loadInterrupted()
        drain()
        val bar = checkNotNull(ui.message) { "No message" }

        // About no place: it stays wherever the tab goes.
        goTo(dst)
        assertEquals(bar, ui.message)
        vm.back()
        drain()
        assertEquals(bar, ui.message)

        // Closing it forgets the operation.
        vm.dismissMessage()
        drain()
        assertNull(ui.message)
        assertEquals(emptyList<Any>(), vm.runner.value.interrupted)
    }

    @Test
    fun removablePrecheckAsksToSkip() {
        val sd = folder.newFolder("sd").toPath().toRealPath()
        file("a:b.txt")
        file("c.txt")
        start(extra = listOf(FakeVolumes.removable("1A2B-3C4D", "SD card", sd)))
        select("a:b.txt", "c.txt")
        vm.copy()
        goTo(sd)
        vm.paste()
        drain()

        val confirm = ui.confirm as ConfirmRequest.SkipUnstorable
        assertEquals(1, confirm.count)
        assertFalse(Files.exists(sd.resolve("c.txt")))
        vm.answerConfirm(yes = true)
        drain()
        assertNull(ui.confirm)
        assertEquals(listOf("c.txt"), names())
        assertNull(ui.failedOp)
    }

    @Test
    fun refusedRunShowsTheErrorOnly() {
        val d = dir("d")
        start()
        select("d")
        vm.copy()
        goTo(d)
        vm.paste()
        drain()

        // Not "0 of 0 done": nothing was done.
        assertEquals(UiMessage(UiText.res(R.string.err_into_self), InfoSeverity.ERROR), ui.message)
    }

    @Test
    fun failuresAreListedUntilDismissed() {
        file("a.txt")
        Files.createSymbolicLink(root.resolve("link"), root.resolve("a.txt"))
        val dst = dir("dst")
        start()
        select("a.txt", "link")
        vm.copy()
        goTo(dst)
        vm.paste()
        drain()

        assertEquals(listOf("a.txt"), names())
        val failed = checkNotNull(ui.failedOp) { "No failures listed" }
        val result = failed.result as OpResult.Done
        assertEquals(listOf(FsError.Unsupported), result.failed.map { it.error })
        vm.dismissFailures(failed)
        drain()
        assertNull(ui.failedOp)
    }

    /** A copy of [sources] into [target] that a crash stopped after [committed] of them, as its journal shows. */
    private fun interruptedCopy(sources: List<Path>, target: Path, committed: Int) {
        val crashed = OperationJournal(journalDir, NioFsBackend)
        val spec = OperationSpec("old", OpKind.COPY, sources.map { it.toString() }, target.toString())
        crashed.begin(spec)
        crashed.committed(spec.id, committed)
    }

    private companion object {
        val LABELS = NameLabels(newFolder = "New folder", newTextDocument = "New Text Document", copySuffix = " - Copy")
    }
}
