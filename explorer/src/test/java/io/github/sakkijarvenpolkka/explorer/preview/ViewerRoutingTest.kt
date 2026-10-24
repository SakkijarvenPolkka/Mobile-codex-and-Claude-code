// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import io.github.sakkijarvenpolkka.explorer.state.BackTarget
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.PreviewKind
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.ViewerRequest
import io.github.sakkijarvenpolkka.explorer.state.previewKindFor
import io.github.sakkijarvenpolkka.explorer.state.siblingsOrSelf
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerShell
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerTheme
import io.github.sakkijarvenpolkka.explorer.ui.Tags
import io.github.sakkijarvenpolkka.explorer.ui.WidthClass
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Which files open in which viewer, full screen or in the preview pane, and what closes them (a wide window). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp")
class ViewerRoutingTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val settings = SettingsStore(null)
    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var vm: ExplorerViewModel

    /** A player that never gets a file ready: the media viewers only show their controls. */
    private class IdlePlayer : MediaPort {
        override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Int) = Unit
        override val positionMs: Int = 0
        override val isPlaying: Boolean = false
        override fun release() = Unit
    }

    /** Every text file reads as one line naming it, every image as a small bitmap; a PDF is damaged. */
    private val viewers = ViewerDeps(
        text = object : TextLoader {
            override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = TextDocument.Lines(
                listOf(DisplayLine(1, "text of ${file.fileName}", continuation = false)), TextEncoding.UTF8, truncated = false,
            )
        },
        images = object : ImageDecoderPort {
            override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap =
                Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).asImageBitmap()

            override suspend fun sizeOf(file: Path): IntSize = IntSize(40, 30)
        },
        pdf = PdfOpener { throw FsException(FsError.Corrupt) },
        media = { IdlePlayer() },
        io = Dispatchers.Unconfined,
    )

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
    }

    /** A view model on [root] as the primary volume and [extra] volumes, with the viewers above; folders are read at once. */
    private fun start(
        fs: FsBackend = NioFsBackend,
        extra: List<Volume> = emptyList(),
        notice: StateFlow<OpsNotice?> = MutableStateFlow(null),
    ) {
        volumes = FakeVolumes(FakeVolumes.primary(root), *extra.toTypedArray())
        val mime = MimeResolver()
        val deps = ExplorerDeps(
            fs, settings, volumes, volumes, mime, Dispatchers.Unconfined, openWith = OpenWith(app, mime),
            opsNotice = notice, builtInViewers = true, clock = { OPENED_AT },
        )
        vm = ExplorerViewModel(app, deps)
        settle()
    }

    /** The width class the shell is shown for. */
    private val width = mutableStateOf(WidthClass.COMPACT)

    /** The shell for the view model, on a window of [widthClass] (changed later through [width]). */
    private fun show(widthClass: WidthClass) {
        width.value = widthClass
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, width.value, viewers) }
        }
        rule.waitForIdle()
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.write(root.resolve(rel), rel.toByteArray())

    private val ui get() = vm.ui.value

    private fun entry(name: String): FileEntry = ui.rows.first { it.entry.name == name }.entry

    private fun row(name: String) = rule.onNodeWithTag(Tags.row(name))

    private fun inside(tag: String, matcher: SemanticsMatcher) = rule.onNode(matcher and hasAnyAncestor(hasTestTag(tag)))

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    /** The SD card [SD] goes, as the registry tells it: no root first, then the event. */
    private fun eject() {
        volumes.update(SD) { it.copy(root = null, state = VolumeState.EJECTING) }
        volumes.events.tryEmit(VolumeEvent.Ejecting(SD))
        settle()
    }

    private fun mount(at: Path) {
        volumes.update(SD) { it.copy(root = at, state = VolumeState.MOUNTED) }
        volumes.events.tryEmit(VolumeEvent.Mounted(SD))
        settle()
    }

    /** A file the FileProvider serves (on an SD card), which the backend says is there. */
    private fun backendWith(file: Path): FsBackend = object : FsBackend by NioFsBackend {
        override fun exists(path: Path): Boolean = path == file || NioFsBackend.exists(path)

        override fun attributes(path: Path): BasicFileAttributes =
            NioFsBackend.attributes(if (path == file) root else path)
    }

    private fun entryAt(path: Path, mime: String?) =
        FileEntry(path, path.fileName.toString(), EntryKind.FILE, 1, 0, mime, hidden = false, fileKey = null)

    @Test
    fun previewKindForTypes() {
        fun kind(name: String, mime: String?) = previewKindFor(entryAt(Paths.get("/storage/emulated/0/$name"), mime))
        for (mime in listOf("jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif").map { "image/$it" }) {
            assertEquals(mime, PreviewKind.Image, kind("picture", mime))
        }
        // The types the app resolves for those pictures' extensions.
        val resolver = MimeResolver()
        for (name in listOf("a.jpg", "a.jpeg", "a.png", "a.webp", "a.gif", "a.heic", "a.heif", "a.bmp", "a.avif")) {
            assertEquals(name, PreviewKind.Image, kind(name, resolver.mimeOf(name)))
        }
        assertEquals(PreviewKind.Image, kind("a.JPG", "Image/JPEG; q=1"))
        assertEquals(PreviewKind.Text, kind("notes.txt", "text/plain"))
        assertEquals(PreviewKind.Text, kind("Main.kt", null))
        assertEquals(PreviewKind.Text, kind("movie.srt", "application/x-subrip"))
        assertEquals(PreviewKind.Audio, kind("song.mp3", "audio/mpeg"))
        assertEquals(PreviewKind.Video, kind("clip.mp4", "video/mp4"))
        // An MPEG transport stream, though "ts" is also on the text viewer's list (TypeScript).
        assertEquals(PreviewKind.Video, kind("clip.ts", "video/mp2t"))
        assertEquals(PreviewKind.Pdf, kind("report.pdf", "application/pdf"))
        assertNull(kind("app.apk", "application/vnd.android.package-archive"))
        assertNull(kind("photos.zip", "application/zip"))
        assertNull(kind("data.bin", null))
        assertNull(kind("README", null))
        // A picture ImageDecoder does not decode.
        assertNull(kind("drawing.svg", "image/svg+xml"))
        // Only files: neither a folder nor a link is shown, whatever its name.
        val folder = FileEntry(Paths.get("/storage/emulated/0/a.jpg"), "a.jpg", EntryKind.DIR, 0, 0, "image/jpeg", false, null)
        assertNull(previewKindFor(folder))
        assertNull(previewKindFor(folder.copy(kind = EntryKind.SYMLINK)))
    }

    @Test
    fun tapImageOpensViewerWithSiblingImagesOnly() {
        listOf("a.jpg", "b.txt", "c.png", "d.mp4", "e.gif").forEach(::file)
        start()
        vm.onTap(entry("c.png"))
        settle()

        // The folder's pictures in its order, to swipe through; nothing goes to another app.
        val viewer = checkNotNull(ui.viewer) { "No viewer" }
        assertEquals(entry("c.png"), viewer.entry)
        assertEquals(listOf("a.jpg", "c.png", "e.gif"), viewer.siblings.map { it.name })
        assertNull(ui.launch)

        // Another kind of file opens by itself.
        vm.viewing.close()
        assertNull(ui.viewer)
        vm.onTap(entry("b.txt"))
        settle()
        assertEquals(ViewerRequest(entry("b.txt"), listOf(entry("b.txt"))), ui.viewer)
    }

    @Test
    fun tapUnknownUsesOpenWith() {
        val data = Paths.get("/storage/1A2B-3C4D/Download/data.bin")
        file("photos.zip")
        start(backendWith(data))
        vm.open(entryAt(data, null))
        settle()
        assertNull(ui.viewer)
        val view = checkNotNull(ui.launch) { "Nothing to start" }
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(data.toString(), view.data?.path)

        // An archive goes to another app too (this one is outside the folders the FileProvider serves).
        vm.onTap(entry("photos.zip"))
        settle()
        assertNull(ui.viewer)
        assertEquals(UiText.res(R.string.open_unsupported), ui.message?.text)
    }

    @Test
    fun openAddsRecent() {
        dir("Docs")
        file("Docs/notes.txt")
        val sd = Paths.get("/storage/1A2B-3C4D")
        val data = sd.resolve("Download/data.bin")
        start(backendWith(data), extra = listOf(FakeVolumes.removable(SD, "SD card", sd)))
        vm.navigate(Location.Folder(root.resolve("Docs")))
        settle()
        vm.onTap(entry("notes.txt"))
        settle()
        assertNotNull(ui.viewer)
        assertEquals(listOf(Recent("primary", "Docs/notes.txt", OPENED_AT)), settings.recents.value)

        // Handed to another app, it is a recent file too, the newest: once an app has it.
        vm.viewing.close()
        vm.open(entryAt(data, null))
        settle()
        vm.launched(checkNotNull(ui.launch), OpenResult.NoApp)
        settle()
        assertEquals(listOf(Recent("primary", "Docs/notes.txt", OPENED_AT)), settings.recents.value)
        vm.open(entryAt(data, null))
        settle()
        vm.launched(checkNotNull(ui.launch), OpenResult.Started)
        settle()
        assertEquals(
            listOf(Recent(SD, "Download/data.bin", OPENED_AT), Recent("primary", "Docs/notes.txt", OPENED_AT)),
            settings.recents.value,
        )
    }

    @Test
    fun ejectClosesViewerOnThatVolume() {
        file("notes.txt")
        val sd = folder.newFolder("sd").toPath().toRealPath()
        val dcim = Files.createDirectory(sd.resolve("DCIM"))
        Files.write(dcim.resolve("photo.jpg"), ByteArray(1))
        start(extra = listOf(FakeVolumes.removable(SD, "SD card", sd)))
        vm.onTap(entry("notes.txt"))
        settle()
        assertEquals("notes.txt", ui.viewer?.entry?.name)

        // A file on another volume stays open.
        eject()
        assertEquals("notes.txt", ui.viewer?.entry?.name)

        mount(sd)
        vm.viewing.close()
        vm.navigate(Location.Folder(dcim))
        settle()
        vm.onTap(entry("photo.jpg"))
        settle()
        assertEquals("photo.jpg", ui.viewer?.entry?.name)
        eject()
        assertNull(ui.viewer)

        // So does the file of the preview pane.
        mount(sd)
        vm.setExpanded(true)
        vm.viewing.togglePane()
        vm.onTap(entry("photo.jpg"))
        settle()
        assertNull(ui.viewer)
        assertEquals("photo.jpg", ui.previewed?.name)
        eject()
        assertNull(ui.previewed)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun previewPaneShowsSelectedFileOnExpanded() {
        file("a.txt")
        file("b.txt")
        file("c.zip")
        start()
        show(WidthClass.EXPANDED)
        rule.onNodeWithTag(ViewerTags.PANE).assertDoesNotExist()

        // ⋯ ▸ Preview pane.
        inside(Tags.COMMAND_BAR, hasContentDescription(str(R.string.more))).performClick()
        rule.onNodeWithTag(Tags.CMD_PREVIEW_PANE).performClick()
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText(str(R.string.preview_none))).assertIsDisplayed()

        row("a.txt").performTouchInput { longClick() }
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText("text of a.txt")).assertIsDisplayed()

        // Two selected: neither is shown.
        row("b.txt").performClick()
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText(str(R.string.preview_none))).assertIsDisplayed()

        // With nothing selected, a tap opens the file in the pane rather than over the shell.
        vm.dispatch(TabAction.ClearSelection)
        rule.waitForIdle()
        row("b.txt").performClick()
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText("text of b.txt")).assertIsDisplayed()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertDoesNotExist()
        // The pane is no overlay: Back has nothing to do here.
        assertNull(ui.backTarget)

        // A file no viewer shows.
        row("c.zip").performTouchInput { longClick() }
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText(str(R.string.preview_unavailable))).assertIsDisplayed()

        // Alt+P closes the pane, and opens it again.
        rule.onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.P) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.PANE).assertDoesNotExist()
        rule.onRoot().performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.P) } }
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.PANE).assertIsDisplayed()

        // A narrow window has no pane: a tap opens the viewer over the shell.
        width.value = WidthClass.COMPACT
        vm.dispatch(TabAction.ClearSelection)
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.PANE).assertDoesNotExist()
        row("a.txt").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun backClosesViewerFirst() {
        dir("a")
        file("a/x.txt")
        start()
        show(WidthClass.COMPACT)
        row("a").performClick()
        rule.waitForIdle()
        row("x.txt").performClick()
        rule.waitForIdle()

        // Full screen: the name in the top bar, the text under it.
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()
        inside(ViewerTags.SCREEN, hasText("x.txt")).assertIsDisplayed()
        inside(ViewerTags.SCREEN, hasText("text of x.txt")).assertIsDisplayed()
        assertEquals(BackTarget.OVERLAY, ui.backTarget)

        // Properties opens over the viewer, and Back closes it first.
        inside(ViewerTags.SCREEN, hasContentDescription(str(R.string.cmd_properties))).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.PROPERTIES).assertExists()
        pressBack()
        rule.onNodeWithTag(Tags.PROPERTIES).assertDoesNotExist()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()

        pressBack()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertDoesNotExist()
        assertEquals(Location.Folder(root.resolve("a")), ui.location)
        row("x.txt").assertIsDisplayed()

        pressBack()
        assertEquals(Location.Folder(root), ui.location)
        assertNull(ui.backTarget)
    }

    @Test
    fun siblingsFallBackToTheEntryAlone() {
        val a = entryAt(Paths.get("/storage/emulated/0/a.jpg"), "image/jpeg")
        val b = entryAt(Paths.get("/storage/emulated/0/b.jpg"), "image/jpeg")
        assertEquals(listOf(a, b), siblingsOrSelf(b, listOf(a, b)))
        // Pictures it is not among would start the viewer on another one.
        assertEquals(listOf(b), siblingsOrSelf(b, listOf(a)))
        assertEquals(listOf(b), siblingsOrSelf(b, emptyList()))
    }

    @Test
    fun previewPaneOpensItsFileFullScreen() {
        listOf("a.jpg", "b.jpg", "c.zip").forEach(::file)
        start()
        show(WidthClass.EXPANDED)
        vm.viewing.togglePane()
        row("b.jpg").performClick()
        rule.waitForIdle()
        assertNull(ui.viewer)

        inside(ViewerTags.PANE, hasText(str(R.string.preview_open))).performClick()
        rule.waitForIdle()
        // Full screen, with the folder's pictures to swipe through, though the pane is on.
        assertEquals(ViewerRequest(entry("b.jpg"), listOf(entry("a.jpg"), entry("b.jpg"))), ui.viewer)
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()

        // A file no viewer shows has nothing to open there.
        vm.viewing.close()
        rule.waitForIdle()
        row("c.zip").performTouchInput { longClick() }
        rule.waitForIdle()
        inside(ViewerTags.PANE, hasText(str(R.string.preview_unavailable))).assertIsDisplayed()
        inside(ViewerTags.PANE, hasText(str(R.string.preview_open))).assertDoesNotExist()
    }

    @Test
    fun topBarSharesAndOpensWith() {
        val sd = Paths.get("/storage/1A2B-3C4D")
        val notes = sd.resolve("Download/notes.txt")
        start(backendWith(notes), extra = listOf(FakeVolumes.removable(SD, "SD card", sd)))
        show(WidthClass.COMPACT)
        vm.open(entryAt(notes, "text/plain"))
        rule.waitForIdle()
        inside(ViewerTags.SCREEN, hasText("text of notes.txt")).assertIsDisplayed()
        val started = shadowOf(rule.activity)

        inside(ViewerTags.SCREEN, hasContentDescription(str(R.string.cmd_share))).performClick()
        rule.waitForIdle()
        val share = checkNotNull(started.nextStartedActivity) { "Nothing shared" }
        assertEquals(Intent.ACTION_CHOOSER, share.action)
        assertEquals(Intent.ACTION_SEND, chosen(share).action)

        inside(ViewerTags.SCREEN, hasText(str(R.string.cmd_open_with))).performClick()
        rule.waitForIdle()
        val openWith = checkNotNull(started.nextStartedActivity) { "Nothing opened" }
        assertEquals(Intent.ACTION_CHOOSER, openWith.action)
        assertEquals(Intent.ACTION_VIEW, chosen(openWith).action)
        assertEquals(notes.toString(), chosen(openWith).data?.path)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun shortcutsWorkAfterTheViewerCloses() {
        file("notes.txt")
        file("other.txt")
        start()
        show(WidthClass.COMPACT)

        // Enter on the focused row opens it: the row, and the focus with it, goes.
        row("notes.txt").requestFocus()
        row("notes.txt").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()
        vm.viewing.close()
        rule.waitForIdle()

        // Back on the list, the shell has the keys again.
        rule.onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.A) } }
        rule.waitForIdle()
        assertEquals(setOf(root.resolve("notes.txt"), root.resolve("other.txt")), ui.tabs.active.selection.items)
    }

    @Test
    fun opsNoticeShowsOverTheViewer() {
        file("notes.txt")
        start(notice = MutableStateFlow(OpsNotice.ForegroundRefused))
        show(WidthClass.COMPACT)
        rule.onNodeWithText(str(R.string.fgs_refused)).assertIsDisplayed()

        row("notes.txt").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(ViewerTags.SCREEN).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.fgs_refused)).assertIsDisplayed()
    }

    /** The intent [chooser] lets the user pick an app for. */
    @Suppress("DEPRECATION")
    private fun chosen(chooser: Intent): Intent = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))

    private companion object {
        const val SD = "1A2B-3C4D"
        const val OPENED_AT = 1_700_000_000_000L
    }
}
