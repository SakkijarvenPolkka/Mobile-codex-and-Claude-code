// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.fs.koreanCharset
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.text.Charsets.UTF_16BE
import kotlin.text.Charsets.UTF_16LE

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class TextViewerTest {

    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    val rule = createComposeRule()

    /** [FsTextLoader] on the real file system, remembering the encoding asked for in every load. */
    private class RecordingLoader : TextLoader {
        private val inner = FsTextLoader(NioFsBackend, Dispatchers.IO)
        val forced = CopyOnWriteArrayList<TextEncoding?>()

        override suspend fun load(file: Path, forced: TextEncoding?): TextDocument {
            this.forced += forced
            return inner.load(file, forced)
        }
    }

    private fun show(file: Path, loader: TextLoader, onClose: () -> Unit = {}) {
        rule.setContent {
            FluentTheme(dark = false, accent = null) { TextViewer(file, loader, onClose) }
        }
    }

    private fun file(name: String, bytes: ByteArray): Path = Files.write(folder.root.toPath().resolve(name), bytes)

    private fun shown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun encodingShown(label: String): Boolean =
        rule.onAllNodes(hasTestTag(TextViewerTags.ENCODING) and hasText(label)).fetchSemanticsNodes().isNotEmpty()

    /** Waits for [condition], running the main thread's work meanwhile (which waitUntil alone does not here). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) {
        rule.waitForIdle()
        condition()
    }

    private fun choose(tag: String) {
        rule.onNodeWithContentDescription("더 보기").performClick()
        rule.onNodeWithTag(tag).performClick()
    }

    private fun top(node: SemanticsNodeInteraction) = node.getUnclippedBoundsInRoot().top

    @Test
    fun showsLineNumbersAndTruncationNotice() {
        val document = TextDocument.Lines(
            listOf(
                DisplayLine(1, "first line", continuation = false),
                DisplayLine(1, "its continuation", continuation = true),
                DisplayLine(2, "second line", continuation = false),
            ),
            TextEncoding.UTF8,
            truncated = true,
        )
        val loader = object : TextLoader {
            override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = document
        }
        var closed = false
        show(Paths.get("/storage/emulated/0/notes.txt"), loader) { closed = true }
        waitFor { shown("first line") }

        rule.onNodeWithText("notes.txt").assertIsDisplayed()
        rule.onNodeWithText("처음 1MB만 표시합니다").assertIsDisplayed()
        listOf("first line", "its continuation", "second line").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        // The row that continues line 1 has no number of its own.
        rule.onAllNodesWithText("1").assertCountEquals(1)
        assertEquals(top(rule.onNodeWithText("1")), top(rule.onNodeWithText("first line")))
        assertEquals(top(rule.onNodeWithText("2")), top(rule.onNodeWithText("second line")))
        rule.onNodeWithTag(TextViewerTags.ENCODING).assertTextEquals("UTF-8")

        rule.onNodeWithContentDescription("닫기").performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun encodingMenuDecodesAgain() {
        val file = file("memo.txt", "abc\n한글 문서".toByteArray(koreanCharset()))
        val loader = RecordingLoader()
        show(file, loader)
        waitFor { shown("한글 문서") }
        rule.onNodeWithText("abc").assertIsDisplayed()
        rule.onNodeWithTag(TextViewerTags.ENCODING).assertTextEquals("한국어(CP949)")
        rule.onNodeWithText("처음 1MB만 표시합니다").assertDoesNotExist()

        choose(TextViewerTags.ENCODING_UTF8)
        waitFor { encodingShown("UTF-8") }
        rule.onNodeWithText("abc").assertIsDisplayed()
        rule.onNode(hasText("\uFFFD", substring = true)).assertIsDisplayed()
        assertTrue(!shown("한글 문서"))

        choose(TextViewerTags.ENCODING_AUTO)
        waitFor { encodingShown("한국어(CP949)") }
        rule.onNodeWithText("한글 문서").assertIsDisplayed()
        assertEquals(listOf(null, TextEncoding.UTF8, null), loader.forced.toList())
    }

    @Test
    fun binaryFileCanBeReadAsUtf16() {
        // UTF-16 without a byte order mark: NUL bytes, so not text until UTF-16 is chosen.
        val file = file("notes.txt", "abc\n가나".toByteArray(UTF_16LE))
        val loader = RecordingLoader()
        show(file, loader)
        waitFor { shown("이 파일은 텍스트 파일이 아닙니다") }

        choose(TextViewerTags.ENCODING_UTF16)
        waitFor { shown("가나") }
        rule.onNodeWithText("abc").assertIsDisplayed()
        rule.onNodeWithTag(TextViewerTags.ENCODING).assertTextEquals("UTF-16")
        assertTrue(!shown("이 파일은 텍스트 파일이 아닙니다"))
        assertEquals(listOf(null, TextEncoding.UTF16LE), loader.forced.toList())
    }

    @Test
    fun utf16KeepsTheDetectedByteOrder() {
        val file = file("be.txt", byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "가나다".toByteArray(UTF_16BE))
        val loader = RecordingLoader()
        show(file, loader)
        waitFor { shown("가나다") }
        rule.onNodeWithTag(TextViewerTags.ENCODING).assertTextEquals("UTF-16")

        choose(TextViewerTags.ENCODING_KOREAN)
        waitFor { encodingShown("한국어(CP949)") }
        choose(TextViewerTags.ENCODING_UTF16)
        waitFor { encodingShown("UTF-16") }
        rule.onNodeWithText("가나다").assertIsDisplayed()
        assertEquals(listOf(null, TextEncoding.CP949, TextEncoding.UTF16BE), loader.forced.toList())
    }
}
