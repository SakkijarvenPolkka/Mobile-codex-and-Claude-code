// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.FakeVolumes
import io.github.sakkijarvenpolkka.explorer.state.ListingState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.RowExtra
import io.github.sakkijarvenpolkka.explorer.state.SEARCH_DELAY_MS
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.TabsReducer
import io.github.sakkijarvenpolkka.explorer.state.startAt
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The search box and the search results (spec §6.1), shown in Korean on a wide window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-w900dp-h900dp")
class SearchUiTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(null)

    /** Where folders are read and searched: only when the test drains it. */
    private val io = StandardTestDispatcher()
    private var threads: ExecutorCoroutineDispatcher? = null
    private lateinit var root: Path
    private lateinit var volumes: FakeVolumes
    private lateinit var fs: WatchedFs
    private lateinit var vm: ExplorerViewModel

    /** The real file system, telling [onList] (on the thread that reads) of each folder it lists, and keeping them. */
    private class WatchedFs(private val onList: (Path) -> Unit) : FsBackend by NioFsBackend {
        val listed = CopyOnWriteArrayList<Path>()

        override fun list(dir: Path): List<Path> {
            listed.add(dir)
            onList(dir)
            return NioFsBackend.list(dir)
        }
    }

    @Before
    fun setUp() {
        root = folder.newFolder("primary").toPath().toRealPath()
        volumes = FakeVolumes(FakeVolumes.primary(root))
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        threads?.close()
    }

    /**
     * The shell on [root] as the primary volume at [widthClass], started at [root] unless the settings have a
     * session; folders are read on [readOn] through a [WatchedFs] with [onList].
     */
    private fun show(
        widthClass: WidthClass = WidthClass.EXPANDED,
        readOn: CoroutineDispatcher = io,
        onList: (Path) -> Unit = {},
    ) {
        settings.startAt(root, volumes)
        fs = WatchedFs(onList)
        vm = ExplorerViewModel(app, ExplorerDeps(fs, settings, volumes, volumes, MimeResolver(), readOn))
        rule.setContent {
            val global by settings.global.collectAsState()
            ExplorerTheme(global) { ExplorerShell(vm, widthClass) }
        }
        drain()
    }

    /** Runs what is queued on [io], then on the main thread, a few rounds over: each hands work to the other. */
    private fun drain() = repeat(8) {
        io.scheduler.advanceUntilIdle()
        rule.waitForIdle()
    }

    /** Waits for [condition], running what other threads hand the main thread meanwhile. */
    private fun waitFor(condition: () -> Boolean) {
        rule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        rule.waitForIdle()
    }

    /** Runs what is queued on [io], then what is due on the main thread, without the clock moving on. */
    private fun settleMain() {
        io.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Lets [millis] go by on the main thread's clock, where the search box waits, then drains. */
    private fun idleFor(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        drain()
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun dir(rel: String): Path = Files.createDirectories(root.resolve(rel))

    private fun file(rel: String): Path = Files.createFile(root.resolve(rel))

    private val ui get() = vm.ui.value

    private fun location(): Location = ui.location

    private fun names(): List<String> = ui.rows.map { it.entry.name }

    private fun box(): SemanticsNodeInteraction = rule.onNodeWithTag(Tags.SEARCH_BOX)

    /** What the search box holds. */
    private fun boxText(): String = box().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Types [text] into the search box and presses the keyboard's search key. */
    private fun searchFor(text: String) {
        box().performClick()
        box().performTextInput(text)
        box().performImeAction()
        drain()
    }

    /** What a keyboard app talks to the focused text field through. */
    private fun keyboard(): InputConnection {
        fun find(view: View): InputConnection? {
            if (view.onCheckIsTextEditor()) view.onCreateInputConnection(EditorInfo())?.let { return it }
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(rule.activity.window.decorView)) { "No text field has the keyboard" }
    }

    private fun internal(): String = str(R.string.volume_internal)

    /** Whether this is a search that is done. */
    private fun ListingState.isDone(): Boolean = (this as? ListingState.Streaming)?.done == true

    /** The line of the status over the search results that reads [text]. */
    private fun inStatus(text: String): SemanticsNodeInteraction =
        rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(Tags.SEARCH_STATUS)))

    /** The system Back, then what it leads to. */
    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        drain()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun debounceRunsOnce() {
        file("apple.txt")
        file("apricot.txt")
        file("banana.txt")
        show(WidthClass.COMPACT)
        // On a phone, 🔍 in the command bar opens the box, ready for typing.
        val hint = str(R.string.search_hint, DisplayName.inSentence(internal()))
        rule.onNodeWithTag(Tags.CMD_SEARCH).assert(hasContentDescription(hint)).performClick()
        drain()
        box().assertIsFocused().assert(hasText(hint))
        val listedBefore = fs.listed.size

        // Three quick keys (a test's text input takes longer than the wait): nothing is searched yet.
        val keys = keyboard()
        val typingFrom = SystemClock.uptimeMillis()
        for (key in listOf("a", "p", "r")) {
            keys.commitText(key, 1)
            settleMain()
        }
        assertTrue(SystemClock.uptimeMillis() - typingFrom < SEARCH_DELAY_MS)
        assertEquals("apr", boxText())
        assertEquals(Location.Folder(root), location())
        assertEquals(listedBefore, fs.listed.size)

        // Once the typing stops: one search, of what was typed last.
        idleFor(SEARCH_DELAY_MS)
        assertEquals(Location.SearchResults(root, "apr"), location())
        assertEquals(listOf(root), fs.listed.drop(listedBefore))
        assertEquals(listOf("apricot.txt"), names())
        idleFor(1_000)
        assertEquals(listOf(root), fs.listed.drop(listedBefore))
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })

        // Backspace edits the box, as in any text field: it does not go back.
        box().performKeyInput { pressKey(Key.Backspace) }
        idleFor(SEARCH_DELAY_MS)
        assertEquals("ap", boxText())
        assertEquals(Location.SearchResults(root, "ap"), location())
        assertEquals(listOf("apple.txt", "apricot.txt"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
    }

    @Test
    fun composingTextIgnored() {
        file("사과.txt")
        file("사진.jpg")
        file("bee.txt")
        show()
        box().performClick()
        drain()

        // A syllable still being put together by the keyboard is not searched, however long it waits.
        rule.runOnUiThread { keyboard().setComposingText("사", 1) }
        idleFor(1_000)
        assertEquals("사", boxText())
        assertEquals(Location.Folder(root), location())

        // The keyboard's search key searches at once, composed or not.
        box().performImeAction()
        drain()
        assertEquals(Location.SearchResults(root, "사"), location())
        assertEquals(listOf("사과.txt", "사진.jpg"), names())

        // The next syllable waits too; once composed, it is searched after the pause.
        rule.runOnUiThread { keyboard().setComposingText("사진", 1) }
        idleFor(1_000)
        assertEquals(Location.SearchResults(root, "사"), location())
        rule.runOnUiThread { keyboard().finishComposingText() }
        drain()
        assertEquals(Location.SearchResults(root, "사"), location())
        idleFor(SEARCH_DELAY_MS)
        assertEquals(Location.SearchResults(root, "사진"), location())
        assertEquals(listOf("사진.jpg"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
    }

    @Test
    fun resultsShowParentFolder() {
        dir("docs/old")
        file("docs/report.txt")
        file("docs/old/report-1.txt")
        file("report.pdf")
        file("notes.txt")
        show()
        searchFor("report")

        assertEquals(listOf("report-1.txt", "report.pdf", "report.txt"), names())
        assertEquals(RowExtra.SearchInfo(root.resolve("docs")), ui.rows.first { it.entry.name == "report.txt" }.extra)
        // Each result shows the folder it is in, from its drive down.
        val docs = "${internal()} › docs"
        rule.onNodeWithTag(Tags.row("report.txt")).assert(hasText(docs))
        rule.onNodeWithTag(Tags.row("report-1.txt")).assert(hasText("$docs › old"))
        rule.onNodeWithTag(Tags.row("report.pdf")).assert(hasText(internal()))
        // Also as tiles.
        vm.setView(ui.viewSetting.copy(mode = ViewMode.TILES))
        drain()
        rule.onNodeWithTag(Tags.row("report.txt")).assert(hasText(docs))
        // The address bar names the search, after the folder searched.
        val crumb = str(R.string.tab_search_results, DisplayName.inSentence("report"))
        rule.onNode(hasText(crumb) and hasAnyAncestor(hasTestTag(Tags.BREADCRUMB))).assertIsDisplayed()

        // Coming back to the app leaves the results as they were found: nothing is searched again.
        val listed = fs.listed.size
        vm.onResume()
        drain()
        assertEquals(listed, fs.listed.size)
        assertEquals(3, ui.rows.size)
    }

    @Test
    fun progressLineShowsFolderAndSkipped() {
        dir("a-locked")
        dir("b-slow")
        file("b-slow/plan.txt")
        file("plan.md")
        val slow = CountDownLatch(1)
        // Two threads, as on the app's I/O dispatcher: what is found is passed on while the walk waits.
        val pool = Executors.newFixedThreadPool(2).asCoroutineDispatcher().also { threads = it }
        val searching = str(R.string.search_searching, DisplayName.inSentence(internal()))
        try {
            show(readOn = pool) { dir ->
                if (dir.fileName.toString() == "a-locked") throw AccessDeniedException(dir.toString())
                if (dir.fileName.toString() == "b-slow") slow.await(10, TimeUnit.SECONDS)
            }
            waitFor { ui.rows.isNotEmpty() }
            searchFor("plan")
            // While it walks: a bar, and the folder it is in.
            waitFor { (ui.listing as? ListingState.Streaming)?.done == false }
            inStatus(searching).assertIsDisplayed()
        } finally {
            slow.countDown()
        }
        waitFor { ui.listing.isDone() }
        assertEquals(listOf("plan.md", "plan.txt"), names())
        // Done: the folder that could not be read is told; nothing is being searched any more.
        inStatus(str(R.string.search_skipped, 1)).assertIsDisplayed()
        inStatus(searching).assertDoesNotExist()
        rule.onNodeWithTag(Tags.SEARCH_NONE).assertDoesNotExist()

        // Nothing found says so.
        box().performTextInput("zzz")
        box().performImeAction()
        waitFor { location() == Location.SearchResults(root, "planzzz") && ui.listing.isDone() }
        assertTrue(ui.rows.isEmpty())
        rule.onNodeWithTag(Tags.SEARCH_NONE).assert(hasText(str(R.string.search_none))).assertIsDisplayed()
    }

    @Test
    fun hiddenAndWithheldFoldersAreNotSearched() {
        file("plan.txt")
        file(".plan.md")
        dir(".notes")
        file(".notes/plan-a.txt")
        dir("Android/data/app")
        file("Android/data/app/plan-b.txt")
        dir("Android/obb/game")
        file("Android/obb/game/plan-c.txt")
        dir("Android/media")
        file("Android/media/plan-d.txt")
        show()
        searchFor("plan")
        // Hidden items, and what is in hidden folders, only with "Show hidden items"; never what Android withholds.
        assertEquals(listOf("plan-d.txt", "plan.txt"), names())
        vm.setGlobal { it.copy(showHidden = true) }
        drain()
        assertEquals(Location.SearchResults(root, "plan"), location())
        assertEquals(listOf(".plan.md", "plan-a.txt", "plan-d.txt", "plan.txt"), names())
        val withheld = listOf("Android/data", "Android/obb").map(root::resolve)
        assertFalse(fs.listed.any { listed -> withheld.any(listed::startsWith) })
    }

    @Test
    fun backReturnsToFolder() {
        dir("music")
        file("a.txt")
        file("ab.txt")
        file("b.txt")
        show()
        searchFor("a")
        assertEquals(Location.SearchResults(root, "a"), location())
        assertEquals(listOf("a.txt", "ab.txt"), names())

        // A refined search takes the place of the one before.
        box().performTextInput("b")
        idleFor(SEARCH_DELAY_MS)
        assertEquals(Location.SearchResults(root, "ab"), location())
        assertEquals(listOf("ab.txt"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
        // Up goes to the folder searched as well.
        assertEquals(Location.Folder(root), ui.up)

        // Back: the folder, as it was; the box is empty again.
        rule.onNodeWithTag(Tags.BACK).assertIsEnabled().performClick()
        drain()
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf("music", "a.txt", "ab.txt", "b.txt"), names())
        assertTrue(ui.tabs.active.back.isEmpty())
        assertEquals("", boxText())

        // Forward: the search again, its words in the box.
        rule.onNodeWithTag(Tags.FORWARD).performClick()
        drain()
        assertEquals(Location.SearchResults(root, "ab"), location())
        assertEquals(listOf("ab.txt"), names())
        assertEquals("ab", boxText())
    }

    @Test
    fun leavingDropsWaitingSearch() {
        val docs = dir("docs")
        file("docs/abc.md")
        file("ab.txt")
        file("abc.txt")
        show()
        searchFor("ab")
        assertEquals(Location.SearchResults(root, "ab"), location())

        // Back before the typing's pause is over: the tab stays at the folder, what was typed is not searched.
        val typingFrom = SystemClock.uptimeMillis()
        keyboard().commitText("c", 1)
        settleMain()
        vm.back()
        settleMain()
        assertTrue(SystemClock.uptimeMillis() - typingFrom < SEARCH_DELAY_MS)
        idleFor(1_000)
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf("docs", "ab.txt", "abc.txt"), names())
        assertEquals(listOf(Location.SearchResults(root, "ab")), ui.tabs.active.forward.map { it.location })

        // Nor is a folder gone into meanwhile searched.
        val goingFrom = SystemClock.uptimeMillis()
        keyboard().commitText("abc", 1)
        settleMain()
        vm.open(ui.rows.first { it.entry.name == "docs" }.entry)
        settleMain()
        assertTrue(SystemClock.uptimeMillis() - goingFrom < SEARCH_DELAY_MS)
        idleFor(1_000)
        assertEquals(Location.Folder(docs), location())
        assertEquals(listOf("abc.md"), names())
    }

    @Test
    fun pasteDisabledInResults() {
        dir("docs")
        file("docs/a.txt")
        file("b.txt")
        show()
        vm.onLongPress(ui.rows.first { it.entry.name == "b.txt" }.entry)
        vm.command(Command.COPY)
        vm.dispatch(TabAction.ClearSelection)
        drain()
        assertTrue(Command.PASTE in ui.commands)
        rule.onNodeWithTag(Tags.command(Command.PASTE)).assertIsEnabled()

        searchFor("a")
        assertEquals(listOf("a.txt"), names())
        assertFalse(Command.PASTE in ui.commands)
        rule.onNodeWithTag(Tags.command(Command.PASTE)).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.CMD_NEW).assertIsNotEnabled()

        // A result is opened, cut, copied, renamed and deleted all the same.
        vm.onLongPress(ui.rows.single().entry)
        drain()
        val offered = listOf(Command.OPEN, Command.CUT, Command.COPY, Command.RENAME, Command.DELETE, Command.COMPRESS)
        assertTrue(ui.commands.toString(), ui.commands.containsAll(offered))
        assertFalse(Command.PASTE in ui.commands)
        assertFalse(Command.NEW_FOLDER in ui.commands)
    }

    @Test
    fun restoredSearchTabShowsSearchAgain() {
        dir("cats")
        file("cat.jpg")
        file("dog.jpg")
        val codec = VolumeLocationCodec { volumes.volumes.value }
        val searched = TabsReducer.reduce(
            TabsReducer.initial(Location.Folder(root)),
            TabAction.Navigate(Location.SearchResults(root, "cat")),
        )
        settings.saveSession(searched, codec)
        show()

        // The folder searched, not searched again by itself: its own items show.
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf(root), fs.listed)
        assertEquals(listOf("cats", "cat.jpg", "dog.jpg"), names())
        val again = rule.onNode(hasText(str(R.string.search_again)) and hasClickAction())
        val searchedFor = str(R.string.tab_search_results, DisplayName.inSentence("cat"))
        rule.onNode(hasText(searchedFor) and hasAnyAncestor(hasTestTag(Tags.SEARCH_AGAIN))).assertIsDisplayed()

        // The button runs it, as it was when saved: Back goes to the folder.
        again.performClick()
        drain()
        assertEquals(Location.SearchResults(root, "cat"), location())
        assertEquals(listOf("cats", "cat.jpg"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
        assertEquals("cat", boxText())
        rule.onNodeWithTag(Tags.SEARCH_AGAIN).assertDoesNotExist()
        vm.back()
        drain()
        assertEquals(Location.Folder(root), location())
        assertNull(ui.tabs.active.current.searched)
        rule.onNodeWithTag(Tags.SEARCH_AGAIN).assertDoesNotExist()
    }

    @Test
    fun newSearchCancelsOld() {
        val a1 = dir("a1")
        file("a1/x1.txt")
        val z = dir("z")
        file("z/x2.txt")
        file("z/y2.txt")
        file("x0.txt")
        file("y0.txt")
        // What comes while a search waits for a1 to be listed, once; the main thread gets to it meanwhile.
        var inA1: (() -> Unit)? = { vm.searchNow("y") }
        show { listed ->
            val come = inA1.takeIf { listed == a1 }
            if (come != null) {
                inA1 = null
                come()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        var before = fs.listed.size
        vm.searchNow("x")
        drain()

        // The search for "x" went no further than a1: only the one for "y" listed z.
        assertNull(inA1)
        assertEquals(listOf(root, a1, root, a1, z), fs.listed.drop(before))
        assertEquals(Location.SearchResults(root, "y"), location())
        assertEquals(listOf("y0.txt", "y2.txt"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })

        // One waiting for the typing to stop goes too when another is asked for at once.
        vm.search("x")
        vm.searchNow("y2")
        idleFor(1_000)
        assertEquals(Location.SearchResults(root, "y2"), location())
        assertEquals(listOf("y2.txt"), names())

        // Cancelling stops the one waiting...
        vm.search("x")
        vm.cancelSearch()
        idleFor(1_000)
        assertEquals(Location.SearchResults(root, "y2"), location())
        // ... and the one running, which ends with what it had found.
        inA1 = { vm.cancelSearch() }
        before = fs.listed.size
        vm.searchNow("x")
        drain()
        assertNull(inA1)
        assertEquals(listOf(root, a1), fs.listed.drop(before))
        assertEquals(Location.SearchResults(root, "x"), location())
        assertEquals(true, (ui.listing as? ListingState.Streaming)?.done)
        rule.onNodeWithTag(Tags.SEARCH_STATUS).assertDoesNotExist()
    }

    @Test
    fun clearReturnsToTheFolderSearched() {
        dir("docs")
        file("docs/a.txt")
        file("ab.txt")
        show()
        searchFor("a")
        assertEquals(Location.SearchResults(root, "a"), location())

        // × goes back to the folder, as Back does: no results are left under an empty box.
        rule.onNodeWithTag(Tags.SEARCH_CLEAR).performClick()
        drain()
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf("docs", "ab.txt"), names())
        assertEquals("", boxText())
        assertEquals(listOf(Location.SearchResults(root, "a")), ui.tabs.active.forward.map { it.location })

        // Search results that were not come to from their folder (a restored tab's, searched again) make way for it.
        vm.navigate(Location.Folder(root.resolve("docs")))
        drain()
        vm.dispatch(TabAction.ReplaceCurrent(ui.tabs.activeId, Location.SearchResults(root.resolve("docs"), "a")))
        drain()
        assertEquals(listOf("a.txt"), names())
        rule.onNodeWithTag(Tags.SEARCH_CLEAR).performClick()
        drain()
        assertEquals(Location.Folder(root.resolve("docs")), location())
        assertEquals(listOf("a.txt"), names())
        assertEquals(listOf(Location.Folder(root)), ui.tabs.active.back.map { it.location })
    }

    @Test
    fun compactBoxClosesWithClearOrBackWhenEmpty() {
        file("a.txt")
        show(WidthClass.COMPACT)
        rule.onNodeWithTag(Tags.CMD_SEARCH).performClick()
        drain()
        box().assertIsDisplayed()

        // Opened and left empty: × closes it.
        rule.onNodeWithTag(Tags.SEARCH_CLEAR).performClick()
        drain()
        box().assertDoesNotExist()

        // So does Back, which goes nowhere else.
        rule.onNodeWithTag(Tags.CMD_SEARCH).performClick()
        drain()
        box().assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        drain()
        box().assertDoesNotExist()
        assertEquals(Location.Folder(root), location())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun compactBoxBackClearsTheSelectionFirst() {
        dir("docs")
        file("docs/a.txt")
        show(WidthClass.COMPACT)
        vm.navigate(Location.Folder(root.resolve("docs")))
        drain()
        rule.onNodeWithTag(Tags.CMD_SEARCH).performClick()
        drain()
        vm.onLongPress(ui.rows.single().entry)
        drain()
        assertTrue(ui.tabs.active.selection.active)

        // Back clears the selection first: the empty box stays open.
        pressBack()
        assertFalse(ui.tabs.active.selection.active)
        box().assertIsDisplayed()
        assertEquals(Location.Folder(root.resolve("docs")), location())

        // Then it closes the box, before it goes back.
        pressBack()
        box().assertDoesNotExist()
        assertEquals(Location.Folder(root.resolve("docs")), location())
        pressBack()
        assertEquals(Location.Folder(root), location())
    }

    @Test
    fun compactBoxBackClosesTheDrawerFirst() {
        file("a.txt")
        show(WidthClass.COMPACT)
        rule.onNodeWithTag(Tags.CMD_SEARCH).performClick()
        drain()
        vm.setDrawerOpen(true)
        drain()
        rule.onNodeWithTag(Tags.DRAWER).assertIsDisplayed()

        // Back closes the drawer over the box first: the box stays open under it.
        pressBack()
        assertFalse(ui.drawerOpen)
        rule.onNodeWithTag(Tags.DRAWER).assertDoesNotExist()
        box().assertIsDisplayed()

        // Then the box.
        pressBack()
        box().assertDoesNotExist()
        assertEquals(Location.Folder(root), location())
    }

    @Test
    fun stoppedSearchDoesNotSayNothingFound() {
        val a1 = dir("a1")
        file("a1/x1.txt")
        dir("z")
        file("z/y2.txt")
        // Stopped while a1 is listed: nothing found yet.
        var inA1: (() -> Unit)? = { vm.cancelSearch() }
        show { listed ->
            val come = inA1.takeIf { listed == a1 }
            if (come != null) {
                inA1 = null
                come()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        vm.searchNow("y2")
        drain()

        // It ended because it was stopped, not because there is nothing: that is not said.
        assertNull(inA1)
        val search = ui.listing as ListingState.Streaming
        assertTrue(search.done && search.stopped)
        assertTrue(ui.rows.isEmpty())
        rule.onNodeWithTag(Tags.SEARCH_NONE).assertDoesNotExist()
    }

    @Test
    fun sortChangeSortsResultsWithoutSearchingAgain() {
        file("a.txt")
        file("ab.txt")
        file("ba.txt")
        show()
        searchFor("a")
        assertEquals(listOf("a.txt", "ab.txt", "ba.txt"), names())
        val listed = fs.listed.size

        vm.setView(ui.viewSetting.copy(sort = SortSpec(SortKey.NAME, ascending = false)))
        drain()

        assertEquals(listOf("ba.txt", "ab.txt", "a.txt"), names())
        assertEquals(listed, fs.listed.size)
        assertEquals(true, (ui.listing as? ListingState.Streaming)?.done)
    }

    @Test
    fun searchingAgainKeepsTheRowsAndTheScroll() {
        repeat(80) { file("a%02d.txt".format(it)) }
        show()
        searchFor("a")
        assertEquals(80, ui.rows.size)
        rule.onNodeWithTag(Tags.fileList(ViewMode.DETAILS)).performScrollToIndex(60)
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.row("a60.txt")).assertIsDisplayed()
        Files.delete(root.resolve("a79.txt"))

        // As after a file operation under the folder searched: the rows found stay until the new search has them.
        vm.refresh()
        rule.waitForIdle()
        assertEquals(80, ui.rows.size)
        drain()

        assertEquals(79, ui.rows.size)
        assertEquals(true, (ui.listing as? ListingState.Streaming)?.done)
        rule.onNodeWithTag(Tags.row("a60.txt")).assertIsDisplayed()
    }

    @Test
    fun closingATabForgetsItsSearches() {
        file("a.txt")
        show()
        searchFor("a")
        val searched = ui.tabs.activeId
        assertTrue(searched in vm.searches.remembered)

        vm.newTab()
        vm.closeTab(searched)
        drain()

        assertFalse(searched in vm.searches.remembered)
    }

    @Test
    fun blankQueryNeitherSearchesNorNavigates() {
        file("a.txt")
        show()
        val listed = fs.listed.size

        // Spaces, after the pause or at the search key, are no search: they would find everything.
        box().performClick()
        box().performTextInput("   ")
        idleFor(SEARCH_DELAY_MS)
        box().performImeAction()
        drain()

        assertEquals(Location.Folder(root), location())
        assertEquals(listed, fs.listed.size)
        assertTrue(ui.tabs.active.back.isEmpty())
        assertEquals(listOf("a.txt"), names())
    }

    @Test
    fun searchStatusSaysResultsMayBeCapped() {
        val capped = ListingState.Streaming(emptyList(), done = true, capped = true, skippedDirs = 0, currentDir = null)
        val global = settings.global.value
        rule.setContent { ExplorerTheme(global) { SearchStatus(capped, emptyList()) } }

        rule.onNode(hasText(str(R.string.search_capped)) and hasAnyAncestor(hasTestTag(Tags.SEARCH_STATUS)))
            .assertIsDisplayed()
    }
}
