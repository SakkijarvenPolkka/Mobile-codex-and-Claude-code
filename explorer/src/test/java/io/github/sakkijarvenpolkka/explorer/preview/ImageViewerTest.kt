// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import kotlin.math.roundToInt

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

        /** The sizes [decode] was asked for, by file name. */
        val requests = CopyOnWriteArrayList<Pair<String, IntSize>>()

        /** While not null, [sizeOf] waits for it: the page is still being decoded. */
        @Volatile
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun sizeOf(file: Path): IntSize {
            hold?.await()
            if (file.fileName.toString() in broken) throw IOException("Not an image")
            return original
        }

        override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap {
            calls += "decode ${file.fileName}"
            requests += file.fileName.toString() to IntSize(maxWidth, maxHeight)
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
        size: () -> IntSize? = { null },
    ) {
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                // A box of the size given (pixels are dp here), else the whole window.
                val box = size()?.let { Modifier.size(it.width.dp, it.height.dp) } ?: Modifier.fillMaxSize()
                Box(box) { ImageViewer(files, startIndex, decoder, {}, showTitle = true, onOpenWith, onCurrentChange) }
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

    /** The size of the page the image is shown on, in pixels. */
    private fun page(): IntSize = image().fetchSemanticsNode().size

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

    @Test
    fun fitSizeNeverEnlarges() {
        // Scaled down, keeping its proportions, to the side that limits it.
        assertEquals(IntSize(1080, 810), fitSize(IntSize(4000, 3000), 1080, 1920))
        assertEquals(IntSize(960, 1920), fitSize(IntSize(3000, 6000), 1080, 1920))
        // A smaller image keeps its size.
        assertEquals(IntSize(400, 300), fitSize(IntSize(400, 300), 1080, 1920))
        assertEquals(IntSize(1080, 1920), fitSize(IntSize(1080, 1920), 1080, 1920))
        // A very thin image keeps at least a pixel.
        assertEquals(IntSize(100, 1), fitSize(IntSize(10_000, 1), 100, 100))
        assertEquals(IntSize(1, 100), fitSize(IntSize(1, 10_000), 100, 100))
    }

    @Test
    fun decodesAtThePageSize() {
        val decoder = FakeDecoder(IntSize(4000, 3000))
        show(files.take(1), startIndex = 0, decoder)
        waitFor { imageShown("a.jpg") }

        assertEquals(listOf("a.jpg" to page()), decoder.requests.toList())
    }

    @Test
    fun zoomDecodeSizeTargets() {
        val photo = IntSize(4000, 3000)
        val phone = IntSize(1080, 1920)
        // Up to 1.5 times the fit, the bitmap decoded for the page does.
        assertNull(zoomDecodeSize(photo, phone, 1f))
        assertNull(zoomDecodeSize(photo, phone, 1.5f))
        // Above it: the image as large as it is shown, no larger than its own pixels.
        assertEquals(IntSize(2160, 1620), zoomDecodeSize(photo, phone, 2f))
        assertEquals(IntSize(4000, 3000), zoomDecodeSize(photo, phone, 10f))
        // No side longer than 4096 pixels.
        assertEquals(IntSize(4096, 1024), zoomDecodeSize(IntSize(8000, 2000), phone, 20f))
        // An image the page shows whole already has every pixel it has.
        assertNull(zoomDecodeSize(IntSize(400, 300), phone, 2f))
        for (zoom in listOf(1.6f, 2f, 5f, 25f)) {
            for (original in listOf(photo, IntSize(8000, 2000), IntSize(6000, 6000), IntSize(9000, 9000))) {
                val size = zoomDecodeSize(original, phone, zoom) ?: continue
                assertTrue("$original at $zoom: $size", maxOf(size.width, size.height) <= 4096)
                assertTrue("$original at $zoom: $size", size.width.toLong() * size.height <= 16L * 1024 * 1024)
                assertTrue("$original at $zoom: $size", size.width <= original.width && size.height <= original.height)
            }
        }
    }

    @Test
    fun settledZoomDecodesMoreDetail() {
        val decoder = FakeDecoder(IntSize(4000, 3000))
        show(files.take(1), startIndex = 0, decoder)
        waitFor { imageShown("a.jpg") }
        val page = page()

        image().performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(2f, zoom())
        // Twice the fit: decoded again, as large as it is now shown.
        val scale = minOf(2f * page.width / 4000, 2f * page.height / 3000)
        val detail = IntSize((4000 * scale).roundToInt(), (3000 * scale).roundToInt())
        waitFor { decoder.requests.size == 2 }
        assertEquals(listOf("a.jpg" to page, "a.jpg" to detail), decoder.requests.toList())

        // Back to the fit: the bitmap of the page does again, with nothing more decoded.
        image().performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(1f, zoom())
        assertEquals(2, decoder.requests.size)
    }

    @Test
    fun neighboursAreDecodedAhead() {
        val decoder = FakeDecoder()
        show(files, startIndex = 1, decoder)
        waitFor { imageShown("b.png") }

        // The pages on either side are ready before they are swiped to.
        rule.waitUntil(5_000) {
            rule.waitForIdle()
            decoder.requests.map { it.first }.containsAll(listOf("a.jpg", "c.gif"))
        }
    }

    @Test
    fun progressShowsWhileAPageDecodes() {
        val decoder = FakeDecoder()
        val hold = CompletableDeferred<Unit>()
        decoder.hold = hold
        show(files.take(1), startIndex = 0, decoder)
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()

        hold.complete(Unit)
        waitFor { imageShown("a.jpg") }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
    }

    @Test
    fun viewportChangeReclampsZoom() {
        var size by mutableStateOf(IntSize(160, 200))
        show(files.take(1), startIndex = 0, FakeDecoder(IntSize(400, 300)), size = { size })
        waitFor { imageShown("a.jpg") }
        // Fitted at 0.4 screen pixels per pixel of the image: pinched to the most, twice its pixels.
        image().performTouchInput {
            pinch(center - Offset(1f, 0f), centerLeft, center + Offset(1f, 0f), centerRight)
        }
        rule.waitForIdle()
        assertEquals(5f, zoom(), 0.001f)

        // In a page twice as wide it fits at 0.8 already: it is zoomed in no further than twice its pixels.
        size = IntSize(320, 400)
        rule.waitForIdle()
        assertEquals(2.5f, zoom(), 0.001f)
    }
}
