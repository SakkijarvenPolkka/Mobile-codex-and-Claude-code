// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.WriteProtocol
import io.github.sakkijarvenpolkka.explorer.fs.ZipOps
import io.github.sakkijarvenpolkka.explorer.fs.entryNames
import io.github.sakkijarvenpolkka.explorer.fs.koreanCharset
import io.github.sakkijarvenpolkka.explorer.fs.putZip
import io.github.sakkijarvenpolkka.explorer.fs.zipOf
import io.github.sakkijarvenpolkka.explorer.ops.CapturedLog
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Overlay
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.startAt
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Compressing and extracting ZIP archives from the shell (spec §6.2), in Korean, with real operations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w900dp-h900dp")
class ZipUiTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val log = CapturedLog()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)

    /** Where folders are read, archives looked into and the operations run: only when the test drains it. */
    private val io = StandardTestDispatcher()
    private val runnerScope = CoroutineScope(SupervisorJob())
    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var runner: OperationRunner
    private lateinit var vm: ExplorerViewModel

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        volumes = FakeVolumes(FakeVolumes.primary(root))
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        runnerScope.cancel()
    }

    /**
     * The shell at [root], on a wide window, with the app's file operations and ZIP archives over the real file
     * system, and other apps to open files with.
     */
    private fun show() {
        settings.startAt(root, volumes)
        val journal = OperationJournal(folder.newFolder("ops"), NioFsBackend)
        val zip = ZipOps(NioFsBackend, WriteProtocol(NioFsBackend, journal), LABELS)
        val fileOps = FileOps(NioFsBackend, volumes, LABELS, journal, ids = ::newId, zip = zip)
        runner = OperationRunner(runnerScope, fileOps, journal, io, host = null)
        val mime = MimeResolver()
        val openWith = OpenWith(app, mime)
        val deps = ExplorerDeps(
            NioFsBackend, settings, volumes, volumes, mime, io, openWith = openWith, fileOps = fileOps, runner = runner,
        )
        vm = ExplorerViewModel(app, deps)
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, WidthClass.EXPANDED) }
        }
        drain()
    }

    private fun newId() = UUID.randomUUID().toString()

    /** Runs what is queued on [io], then on the main thread, a few rounds over: each hands work to the other. */
    private fun drain() = repeat(8) {
        io.scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun file(rel: String, content: String = rel): Path = Files.write(root.resolve(rel), content.toByteArray())

    private val ui get() = vm.ui.value

    private fun row(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.row(name))

    private fun button(text: String) = rule.onNode(hasText(text) and hasClickAction())

    /** An archive whose names Windows wrote in the Korean code page, without the UTF-8 flag. */
    private fun koreanZip(): Path = putZip(root, "옛날.zip", zipOf("한글.txt" to "가나다", charset = koreanCharset()))

    @Test
    fun tapZipOpensExtractDialogWithDetectedEncoding() {
        koreanZip()
        show()
        row("옛날.zip").performClick()
        rule.waitForIdle()
        // The archive is looked into off the main thread: the dialog waits for that.
        rule.onNodeWithText(str(R.string.extract_title)).assertDoesNotExist()

        drain()
        rule.onNodeWithText("압축(ZIP) 폴더 풀기").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.extract_to)).assertIsDisplayed()
        // Into a new folder named after it, next to it.
        val target = "${str(R.string.volume_internal)} › 옛날"
        rule.onNodeWithTag(Tags.EXTRACT_FOLDER).assert(hasText(target))
        rule.onNodeWithText(str(R.string.extract_encoding)).assertIsDisplayed()
        // The names were found to be in the Korean code page.
        rule.onNodeWithText("한국어(CP949)").assertIsSelected()
        rule.onNodeWithText(str(R.string.encoding_utf8)).assertIsNotSelected()
        // Auto finds it again when it runs, and says what it found.
        rule.onNodeWithText("자동 (한국어(CP949))").assertIsNotSelected()

        button("압축 풀기").performClick()
        rule.onNodeWithText(str(R.string.extract_title)).assertDoesNotExist()
        drain()
        assertEquals("가나다", String(Files.readAllBytes(root.resolve("옛날/한글.txt"))))
        assertNull(ui.extract)
    }

    @Test
    fun overrideEncodingPassedToSpec() {
        val zip = koreanZip()
        show()
        row("옛날.zip").performClick()
        drain()

        rule.onNodeWithText(str(R.string.encoding_utf8)).performClick()
        rule.onNodeWithText(str(R.string.encoding_utf8)).assertIsSelected()
        rule.onNodeWithText("한국어(CP949)").assertIsNotSelected()
        button(str(R.string.extract_button)).performClick()

        // The one archive, into its folder, its names read as chosen.
        val spec = runner.state.value.queue.single()
        assertEquals(OpKind.EXTRACT, spec.kind)
        assertEquals(listOf(zip.toString()), spec.sources)
        assertEquals(root.toString(), spec.target)
        assertEquals("UTF-8", spec.options.zipCharset)

        // Its names are not UTF-8, and nothing is extracted.
        drain()
        rule.onNode(hasText(str(R.string.zip_encoding), substring = true) and hasAnyAncestor(hasTestTag(Tags.MESSAGE)))
            .assertIsDisplayed()
        assertFalse(Files.exists(root.resolve("옛날")))
    }

    @Test
    fun compressSelectionCreatesZipAndSelectsIt() {
        for (i in 0 until 60) file("a%02d.txt".format(i))
        file("x.txt")
        file("y.txt")
        show()
        // y first, then x: the archive is still named after the first in the list.
        vm.dispatch(TabAction.Select(root.resolve("y.txt")))
        vm.dispatch(TabAction.Toggle(root.resolve("x.txt")))
        rule.waitForIdle()
        row("x.zip").assertDoesNotExist()
        row("a00.txt").assertIsDisplayed()

        vm.command(Command.COMPRESS)
        drain()
        val zip = root.resolve("x.zip")
        assertTrue(Files.exists(zip))
        assertFalse(Files.exists(root.resolve("y.zip")))
        assertEquals(listOf("x.txt", "y.txt"), entryNames(zip))
        // The new archive alone is selected, and the list scrolled to it.
        assertEquals(listOf("x.zip"), ui.selected.map { it.name })
        row("x.zip").assertIsDisplayed()
        assertNull(ui.reveal)
    }

    @Test
    fun compressInSearchResultsTargetsRoot() {
        Files.createDirectories(root.resolve("docs/old"))
        file("docs/old/report.txt")
        file("docs/notes.txt")
        show()
        vm.searchNow("report")
        drain()
        assertEquals(listOf("report.txt"), ui.rows.map { it.entry.name })

        row("report.txt").performTouchInput { longClick() }
        row("report.txt").performTouchInput { longClick() }
        rule.onNodeWithTag(Tags.contextCommand(Command.COMPRESS)).performClick()
        drain()
        // In the folder searched, not in the one the item is in.
        assertTrue(Files.exists(root.resolve("report.zip")))
        assertFalse(Files.exists(root.resolve("docs/old/report.zip")))
        assertEquals(listOf("report.txt"), entryNames(root.resolve("report.zip")))
        // Searched again, the results have it, selected.
        assertTrue(ui.location is Location.SearchResults)
        assertEquals(listOf("report.zip"), ui.selected.map { it.name })
    }

    @Test
    fun unsafeArchiveShowsMessage() {
        putZip(root, "evil.zip", zipOf("ok.txt" to "fine", "../evil.txt" to "gotcha"))
        show()
        row("evil.zip").performClick()
        drain()
        rule.onNodeWithText(str(R.string.encoding_utf8)).assertIsSelected()
        button(str(R.string.extract_button)).performClick()
        drain()

        // Refused as a whole: nothing written, inside the folder or next to it (where ../ would go), no temporary
        // folder left.
        rule.onNode(hasText(str(R.string.zip_unsafe), substring = true) and hasAnyAncestor(hasTestTag(Tags.MESSAGE)))
            .assertIsDisplayed()
        assertFalse(Files.exists(root.resolve("evil")))
        assertFalse(Files.exists(root.resolve("evil.txt")))
        assertEquals(listOf("evil.zip"), Files.list(root).use { list -> list.map { it.fileName.toString() }.toList() })
        assertEquals(listOf("evil.zip"), ui.rows.map { it.entry.name })
    }

    @Test
    fun damagedArchiveShowsMessageAndNoDialog() {
        putZip(root, "bad.zip", "not an archive".toByteArray())
        show()
        row("bad.zip").performClick()
        drain()

        // Looking into it fails: the bar says why, and there is nothing to extract.
        rule.onNode(hasText(str(R.string.zip_corrupt), substring = true) and hasAnyAncestor(hasTestTag(Tags.MESSAGE)))
            .assertIsDisplayed()
        rule.onNodeWithText(str(R.string.extract_title)).assertDoesNotExist()
        assertNull(ui.extract)
    }

    @Test
    fun dialogNamesTheFolderTheExtractionMakes() {
        koreanZip()
        Files.createDirectory(root.resolve("옛날"))
        show()
        row("옛날.zip").performClick()
        drain()

        // That name is taken: the files go into a new folder next to it, never into that one.
        rule.onNodeWithTag(Tags.EXTRACT_FOLDER).assert(hasText("${str(R.string.volume_internal)} › 옛날 (2)"))
        button(str(R.string.extract_button)).performClick()
        drain()
        assertEquals("가나다", String(Files.readAllBytes(root.resolve("옛날 (2)/한글.txt"))))
        assertEquals(emptyList<Path>(), Files.list(root.resolve("옛날")).use { it.toList() })
    }

    @Test
    fun detectionOpensTheDialogOnlyForTheLastTapOverNothingElse() {
        putZip(root, "a.zip", zipOf("a.txt" to "a"))
        putZip(root, "b.zip", zipOf("b.txt" to "b"))
        show()
        // Two archives tapped before either is looked into: the dialog is the last one's.
        row("a.zip").performClick()
        row("b.zip").performClick()
        drain()
        assertEquals("b.zip", ui.extract?.entry?.name)

        vm.closeOverlay()
        row("a.zip").performClick()
        // What opened meanwhile stays open.
        vm.command(Command.OPTIONS)
        drain()
        assertEquals(Overlay.Options, ui.overlay)
        assertNull(ui.extract)
    }

    @Test
    fun revealGoesWhenTheArchiveIsNotListed() {
        file("report.txt")
        show()
        vm.searchNow("*.txt")
        drain()
        vm.dispatch(TabAction.Select(root.resolve("report.txt")))
        vm.command(Command.COMPRESS)
        drain()

        // The archive is made, but these results do not have it: there is nothing to scroll to.
        assertTrue(Files.exists(root.resolve("report.zip")))
        assertEquals(listOf("report.txt"), ui.rows.map { it.entry.name })
        assertNull(ui.reveal)
    }

    @Test
    fun zipWhereNothingIsExtractedOpensAsAnyFile() {
        putZip(root, "a.zip", zipOf("a.txt" to "a"))
        Files.createDirectories(root.resolve("Android/data/app"))
        putZip(root.resolve("Android/data/app"), "b.zip", zipOf("b.txt" to "b"))
        show()

        // A read-only drive takes no new folder: the archive goes to another app (none serves this folder).
        volumes.update("primary") { it.copy(state = VolumeState.MOUNTED_READ_ONLY) }
        drain()
        row("a.zip").performClick()
        drain()
        assertNull(ui.extract)
        assertEquals(UiText.res(R.string.open_unsupported), ui.message?.text)

        // Nor does a folder Android keeps to an app.
        volumes.update("primary") { it.copy(state = VolumeState.MOUNTED) }
        vm.dismissMessage()
        vm.navigate(Location.Folder(root.resolve("Android/data/app")))
        drain()
        row("b.zip").performClick()
        drain()
        assertNull(ui.extract)
        assertEquals(UiText.res(R.string.open_unsupported), ui.message?.text)
    }

    private companion object {
        val LABELS = NameLabels(newFolder = "새 폴더", newTextDocument = "새 텍스트 문서", copySuffix = " - 복사본")
    }
}
