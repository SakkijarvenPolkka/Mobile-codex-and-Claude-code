// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class ImageViewerTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Every file is an [original]-sized still image, decoded to a small bitmap; the files named in
     * [broken] cannot be decoded. Records what it was asked to decode.
     */
    private class FakeDecoder(
        private val original: IntSize = IntSize(4000, 3000),
        private val broken: Set<String> = emptySet(),
    ) : ImageDecoderPort {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun sizeOf(file: Path): IntSize {
            if (file.fileName.toString() in broken) throw IOException("Not an image")
            return original
        }

        override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap {
            calls += "decode ${file.fileName}"
            if (file.fileName.toString() in broken) throw IOException("Not an image")
            // ImageBitmap(w, h) asks for a colour space, which Robolectric's legacy bitmaps lack.
            return Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        override suspend fun decodeAnimation(file: Path, maxWidth: Int, maxHeight: Int): AnimatedImageDrawable? {
            calls += "animation ${file.fileName}"
            return null
        }
    }

    private fun entry(name: String, mime: String) =
        FileEntry(Paths.get("/storage/emulated/0/DCIM/$name"), name, EntryKind.FILE, 1_000, 0, mime, false, null)

    private val files = listOf(entry("a.jpg", "image/jpeg"), entry("b.png", "image/png"), entry("c.gif", "image/gif"))

    private fun show(
        files: List<FileEntry>,
        startIndex: Int,
        decoder: ImageDecoderPort,
        onOpenWith: (() -> Unit)? = null,
        onCurrentChange: (FileEntry) -> Unit = {},
    ) {
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                ImageViewer(files, startIndex, decoder, onClose = {}, onOpenWith = onOpenWith, onCurrentChange = onCurrentChange)
            }
        }
    }

    /** Waits for [condition], running the main thread's work meanwhile (which waitUntil alone does not here). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) {
        rule.waitForIdle()
        condition()
    }

    private fun imageShown(name: String): Boolean =
        rule.onAllNodes(hasTestTag(ImageViewerTags.IMAGE) and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    private fun textShown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun image(): SemanticsNodeInteraction = rule.onNodeWithTag(ImageViewerTags.IMAGE)

    private fun zoom(): Float = image().fetchSemanticsNode().config[ImageZoom]

    private fun swipe(left: Boolean) {
        rule.onNodeWithTag(ImageViewerTags.PAGER).performTouchInput { if (left) swipeLeft() else swipeRight() }
        rule.waitForIdle()
    }

    @Test
    fun swipeShowsNextImageInListOrder() {
        val decoder = FakeDecoder()
        val current = mutableListOf<String>()
        show(files, startIndex = 1, decoder, onCurrentChange = { current += it.name })
        waitFor { imageShown("b.png") }
        rule.onNodeWithText("b.png").assertIsDisplayed()

        swipe(left = true)
        waitFor { imageShown("c.gif") }
        rule.onNodeWithContentDescription("c.gif").assertIsDisplayed()
        rule.onNodeWithText("c.gif").assertIsDisplayed()

        swipe(left = false)
        swipe(left = false)
        waitFor { imageShown("a.jpg") }
        rule.onNodeWithContentDescription("a.jpg").assertIsDisplayed()
        rule.onNodeWithText("a.jpg").assertIsDisplayed()
        // The first is the first: no swiping past it.
        swipe(left = false)
        rule.onNodeWithText("a.jpg").assertIsDisplayed()

        assertEquals(listOf("b.png", "c.gif", "b.png", "a.jpg"), current)
        // A GIF may be animated: its animation is asked for, then its still image when it has none.
        assertTrue(decoder.calls.containsAll(listOf("animation c.gif", "decode c.gif")))
        assertFalse("animation a.jpg" in decoder.calls)
        assertFalse("animation b.png" in decoder.calls)
    }

    @Test
    fun decodeFailureShowsErrorAndOpenWith() {
        var openedWith = 0
        show(files.take(2), startIndex = 1, FakeDecoder(broken = setOf("b.png")), onOpenWith = { openedWith++ })
        waitFor { textShown("이미지를 표시할 수 없습니다") }
        assertFalse(imageShown("b.png"))

        rule.onNodeWithText("연결 프로그램").performClick()
        rule.runOnIdle { assertEquals(1, openedWith) }

        // Only that image: the one before it is shown.
        swipe(left = false)
        waitFor { imageShown("a.jpg") }
    }

    @Test
    fun decodeFailureWithoutOpenWithHasNoButton() {
        show(files.take(1), startIndex = 0, FakeDecoder(broken = setOf("a.jpg")))
        waitFor { textShown("이미지를 표시할 수 없습니다") }
        rule.onNodeWithText("연결 프로그램").assertDoesNotExist()
    }

    @Test
    fun doubleTapTogglesZoom() {
        show(files.take(1), startIndex = 0, FakeDecoder(IntSize(4000, 3000)))
        waitFor { imageShown("a.jpg") }
        assertEquals(1f, zoom())

        image().performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertTrue("zoom ${zoom()}", zoom() > 1f)

        image().performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(1f, zoom())
    }

    @Test
    fun pinchZoomStopsAtTwiceTheOriginalPixels() {
        show(files.take(1), startIndex = 0, FakeDecoder(IntSize(400, 300)))
        waitFor { imageShown("a.jpg") }
        val page = image().fetchSemanticsNode().size
        // On screen pixels per pixel of the image when it fits.
        val fit = minOf(page.width / 400f, page.height / 300f, 2f)

        image().performTouchInput {
            pinch(center - Offset(10f, 0f), centerLeft, center + Offset(10f, 0f), centerRight)
        }
        rule.waitForIdle()

        assertEquals(2f / fit, zoom(), 0.001f)
    }
}
