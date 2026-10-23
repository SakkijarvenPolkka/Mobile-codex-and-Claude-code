// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class PropertiesDialogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    private fun entry(path: Path): FileEntry = Listing.entry(NioFsBackend, path, MimeResolver()::mimeOf)

    private fun show(entries: List<FileEntry>, fs: FsBackend = NioFsBackend) {
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                val contents = remember { countContents(fs, entries).flowOn(Dispatchers.IO) }
                PropertiesDialog(entries, contents, onDismiss = {})
            }
        }
    }

    private fun shown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun fileShowsNameTypeLocationSizeAndModified() {
        val docs = folder.newFolder("docs").toPath()
        val pdf = entry(Files.write(docs.resolve("report.pdf"), ByteArray(2048)))
        show(listOf(pdf))

        rule.onNodeWithText("⁨report.pdf⁩ 속성").assertExists()
        rule.onNodeWithText("report.pdf").assertExists()
        rule.onNodeWithText("유형").assertExists()
        rule.onNodeWithText("PDF 파일").assertExists()
        rule.onNodeWithText("위치").assertExists()
        rule.onNodeWithText(docs.toString()).assertExists()
        rule.onNodeWithText("크기").assertExists()
        rule.onNodeWithText("2.00 KB").assertExists()
        rule.onNodeWithText("수정한 날짜").assertExists()
        rule.onNodeWithText(Formats.date(pdf.modified, Locale.KOREAN, ZoneId.systemDefault())).assertExists()
        rule.onNodeWithTag(Tags.PROP_HIDDEN).assertIsOff()
        // A file contains nothing.
        rule.onNodeWithText("파일 1개, 폴더 0개").assertDoesNotExist()
    }

    @Test
    fun folderCountUpdatesWhileCounting() {
        val docs = folder.newFolder(".docs").toPath()
        Files.write(docs.resolve("a.txt"), ByteArray(100))
        val sub = Files.createDirectory(docs.resolve("sub"))
        Files.write(sub.resolve("b.txt"), ByteArray(200))
        // Neither links nor the app's own temporary files count.
        Files.createSymbolicLink(docs.resolve("link"), docs.resolve("a.txt"))
        Files.write(docs.resolve(".a.txt.1.xpart"), ByteArray(50))
        val gate = CountDownLatch(1)
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> {
                if (dir == sub) gate.await(10, TimeUnit.SECONDS)
                return NioFsBackend.list(dir)
            }
        }
        show(listOf(entry(docs)), fs)

        rule.onNodeWithText("파일 폴더").assertExists()
        rule.onNodeWithText("내용").assertExists()
        // No label shows a format template such as 파일 %1$d개, 폴더 %2$d개.
        rule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        rule.waitUntil(5_000) { shown("파일 1개, 폴더 1개") }
        rule.onNodeWithText("100바이트").assertExists()
        gate.countDown()
        rule.waitUntil(5_000) { shown("파일 2개, 폴더 1개") }
        rule.onNodeWithText("300바이트").assertExists()
        rule.onNodeWithTag(Tags.PROP_HIDDEN).assertIsOn()
    }

    @Test
    fun severalItemsCountThemselvesToo() {
        val root = folder.newFolder("root").toPath()
        val a = entry(Files.write(root.resolve("a.jpg"), ByteArray(10)))
        val dir = Files.createDirectory(root.resolve("photos"))
        Files.write(dir.resolve("b.jpg"), ByteArray(20))
        val photos = entry(dir)

        val total = runBlocking { countContents(NioFsBackend, listOf(a, photos)).last() }
        assertEquals(ContentCount(files = 2, folders = 1, bytes = 30), total)
        val first = runBlocking { countContents(NioFsBackend, listOf(a, photos)).first() }
        assertEquals(ContentCount(files = 1, folders = 1, bytes = 10), first)
        // One folder shows what it contains, not itself.
        val one = runBlocking { countContents(NioFsBackend, listOf(photos)).last() }
        assertEquals(ContentCount(files = 1, bytes = 20), one)
    }
}
