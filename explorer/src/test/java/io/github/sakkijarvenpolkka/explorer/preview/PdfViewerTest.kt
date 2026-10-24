// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class PdfViewerTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * A document of [pages] (sizes in points); records the pages rendered, at which widths, what was asked
     * of it in order, and whether it was closed.
     */
    private class FakePdf(private val pages: List<IntSize>) : PdfPort {
        val rendered = CopyOnWriteArrayList<Pair<Int, Int>>()
        val asked = CopyOnWriteArrayList<String>()

        @Volatile
        var closed = false

        override val pageCount: Int get() = pages.size

        override suspend fun pageSize(index: Int): IntSize {
            asked += "size $index"
            return pages[index]
        }

        override suspend fun render(index: Int, widthPx: Int): Bitmap {
            asked += "render $index"
            rendered += index to widthPx
            val page = pages[index]
            val width = pdfRenderWidth(page.width, page.height, widthPx)
            return Bitmap.createBitmap(width, height(width, page.width, page.height), Bitmap.Config.ARGB_8888)
        }

        override fun close() {
            closed = true
        }
    }

    private val file = Paths.get("/storage/emulated/0/Documents/report.pdf")

    /** Three wide, short pages: all of them fit on the screen at once. */
    private val threePages = List(3) { IntSize(842, 200) }

    /** Waits for [condition], running the main thread's work meanwhile (which waitUntil alone does not here). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) {
        rule.waitForIdle()
        condition()
    }

    private fun textShown(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun show(opener: PdfOpener, onOpenWith: (() -> Unit)? = null) {
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                PdfViewer(file, opener, onClose = {}, onOpenWith = onOpenWith)
            }
        }
    }

    @Test
    fun pdfRenderWidthCaps() {
        // A4 portrait (595 × 842 points) on a screen 1080 pixels wide: as wide as the screen.
        assertEquals(1080, pdfRenderWidth(595, 842, 1080))

        // A 1:20 strip would be 21,600 pixels long: narrower, so that its long side stays within 4096.
        val strip = pdfRenderWidth(300, 6000, 1080)
        assertTrue("strip $strip", height(strip, 300, 6000) <= 4096)
        assertTrue("strip $strip", strip >= 4096 / 20 - 1)

        // A page wider than 4096 pixels is not rendered wider.
        assertEquals(4096, pdfRenderWidth(6000, 300, 8000))

        // A square page on a very wide screen: no more than 16 MP.
        val square = pdfRenderWidth(1000, 1000, 6000)
        assertTrue("square $square", square.toLong() * height(square, 1000, 1000) <= 16_777_216)

        for ((w, h, viewport) in listOf(Triple(595, 842, 1080), Triple(300, 6000, 1080), Triple(6000, 300, 8000), Triple(1000, 1000, 6000))) {
            val width = pdfRenderWidth(w, h, viewport)
            assertTrue(width >= 1)
            assertTrue(max(width, height(width, w, h)) <= 4096)
            assertTrue(width.toLong() * height(width, w, h) <= 16_777_216)
        }
    }

    @Test
    fun showsOneItemPerPage() {
        val pdf = FakePdf(threePages)
        show({ pdf })
        waitFor { pdf.rendered.size == 3 }

        rule.onAllNodesWithTag(PdfViewerTags.PAGE).assertCountEquals(3)
        // Each page is rendered as wide as the view it is shown in.
        val width = rule.onNodeWithTag(PdfViewerTags.PAGES).fetchSemanticsNode().size.width
        assertEquals(listOf(0, 1, 2), pdf.rendered.map { it.first }.sorted())
        assertTrue(pdf.rendered.toString(), pdf.rendered.all { it.second == width })
    }

    @Test
    fun eachPageIsRenderedBeforeItsSizeIsAsked() {
        // The port learns a page's size when it renders it: asked first, it would open the page twice.
        val pdf = FakePdf(threePages)
        show({ pdf })
        waitFor { pdf.rendered.size == 3 }

        for (index in 0..2) {
            val render = pdf.asked.indexOf("render $index")
            val size = pdf.asked.indexOf("size $index")
            assertTrue(pdf.asked.toString(), render >= 0 && (size < 0 || size > render))
        }
    }

    @Test
    fun missingFileSaysNotFound() {
        var openedWith = 0
        show({ throw FsException(FsError.NotFound) }, onOpenWith = { openedWith++ })
        waitFor { textShown("위치를 찾을 수 없습니다") }
        assertFalse(textShown("손상된 파일입니다"))

        rule.onNodeWithText("연결 프로그램").performClick()
        rule.runOnIdle { assertEquals(1, openedWith) }
    }

    @Test
    fun unreadableFileSaysAccessDenied() {
        show({ throw FsException(FsError.AccessDenied) })
        waitFor { textShown("이 폴더에 접근할 수 없습니다") }
        assertFalse(textShown("손상된 파일입니다"))
    }

    @Test
    fun pagesZoomTransformKeepsThePointUnderTheFingers() {
        val view = IntSize(1000, 2000)
        val zoom = PagesZoom()
        // Twice as large about a point 100 pixels right of and 200 below the centre: that point stays put.
        zoom.transform(Offset(600f, 1200f), Offset.Zero, 2f, view)
        assertEquals(2f, zoom.zoom)
        assertNear(Offset(-100f, -200f), zoom.offset)

        // No further than three times, and never smaller than the view.
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 10f, view)
        assertEquals(3f, zoom.zoom)
        zoom.transform(Offset(500f, 1000f), Offset.Zero, 0.01f, view)
        assertEquals(1f, zoom.zoom)
        assertNear(Offset.Zero, zoom.offset)

        // Moved no further than keeps the pages over the view: (zoom - 1) / 2 of it on each side.
        zoom.transform(Offset(500f, 1000f), Offset(5_000f, -5_000f), 3f, view)
        assertNear(Offset(1000f, -2000f), zoom.offset)
    }

    @Test
    fun pagesZoomPansOnlyWhenZoomed() {
        val view = IntSize(1000, 2000)
        val zoom = PagesZoom()
        zoom.pan(Offset(100f, 100f), view)
        assertNear(Offset.Zero, zoom.offset)

        zoom.transform(Offset(500f, 1000f), Offset.Zero, 2f, view)
        zoom.pan(Offset(100f, -50f), view)
        assertNear(Offset(100f, -50f), zoom.offset)
        // As far as the edges.
        zoom.pan(Offset(1_000f, -5_000f), view)
        assertNear(Offset(500f, -1000f), zoom.offset)
        assertEquals(2f, zoom.zoom)
    }

    @Test
    fun pagesZoomReclampsForASmallerView() {
        val zoom = PagesZoom()
        zoom.transform(Offset(500f, 1000f), Offset(5_000f, 5_000f), 3f, IntSize(1000, 2000))
        assertNear(Offset(1000f, 2000f), zoom.offset)

        // The window was made smaller: the pages are moved back over it.
        zoom.clamp(IntSize(500, 600))
        assertEquals(3f, zoom.zoom)
        assertNear(Offset(500f, 600f), zoom.offset)
    }

    @Test
    fun veryTallPageLaysOut() {
        // As wide as the view (320 pixels) the page would be 460,800 pixels tall, more than Compose can measure.
        showVeryTallPage()
    }

    @Test
    @Config(qualifiers = "+w9000dp")
    fun veryTallPageLaysOutInWideWindow() {
        // Over 8190 pixels wide, Compose measures no more than 65,534 pixels of height.
        showVeryTallPage()
    }

    /** Shows a page of 10 × 14,400 points, as long as a PDF page may be: it is shown 60,000 pixels tall at most. */
    private fun showVeryTallPage() {
        val pdf = FakePdf(listOf(IntSize(10, 14_400)))
        show({ pdf })
        waitFor { pdf.rendered.isNotEmpty() }

        val page = rule.onNodeWithTag(PdfViewerTags.PAGE).fetchSemanticsNode().size
        assertTrue("page $page", page.height in 1..60_000)
    }

    @Test
    fun encryptedShowsNoticeAndOpenWith() {
        var openedWith = 0
        show({ throw FsException(FsError.Encrypted) }, onOpenWith = { openedWith++ })
        waitFor { textShown("암호로 보호된 PDF입니다") }
        rule.onAllNodesWithTag(PdfViewerTags.PAGE).assertCountEquals(0)

        rule.onNodeWithText("연결 프로그램").performClick()
        rule.runOnIdle { assertEquals(1, openedWith) }
    }

    @Test
    fun corruptShowsDamagedAndOpenWith() {
        var openedWith = 0
        show({ throw FsException(FsError.Corrupt) }, onOpenWith = { openedWith++ })
        waitFor { textShown("손상된 파일입니다") }

        rule.onNodeWithText("연결 프로그램").performClick()
        rule.runOnIdle { assertEquals(1, openedWith) }
    }

    @Test
    fun leavingClosesDocument() {
        val pdf = FakePdf(threePages)
        var shown by mutableStateOf(true)
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                if (shown) PdfViewer(file, { pdf }, onClose = {})
            }
        }
        waitFor { pdf.rendered.size == 3 }
        assertFalse(pdf.closed)

        shown = false
        waitFor { pdf.closed }
    }

    /** [actual] is [expected], give or take a hundredth of a pixel (and the sign of a zero). */
    private fun assertNear(expected: Offset, actual: Offset) {
        assertEquals("x of $actual", expected.x, actual.x, 0.01f)
        assertEquals("y of $actual", expected.y, actual.y, 0.01f)
    }

    private companion object {
        /** How tall a page of [pageW] × [pageH] is when rendered [width] pixels wide. */
        fun height(width: Int, pageW: Int, pageH: Int): Int = max(1, (width.toDouble() * pageH / pageW).roundToInt())
    }
}
