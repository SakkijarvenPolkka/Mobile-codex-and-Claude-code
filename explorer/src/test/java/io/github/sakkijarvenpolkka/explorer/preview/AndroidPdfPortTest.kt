// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/**
 * [AndroidPdfPort] on fake pages (PdfRenderer does not run here), and what [AndroidPdfOpener] makes of a
 * file it cannot open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPdfPortTest {

    /** Pages of [sizes] (in points) that count how often they are opened; closing them throws when [closeFails]. */
    private class FakePages(private val sizes: List<IntSize>, private val closeFails: Boolean = false) : PdfPages {
        var opened = 0
        var closeCalls = 0

        override val pageCount: Int get() = sizes.size

        override fun <T> withPage(index: Int, block: (PdfPage) -> T): T {
            opened++
            return block(
                object : PdfPage {
                    override val width: Int get() = sizes[index].width
                    override val height: Int get() = sizes[index].height

                    override fun render(into: Bitmap) = Unit
                },
            )
        }

        override fun close() {
            closeCalls++
            if (closeFails) throw IOException("The descriptor was closed already")
        }
    }

    private fun port(pages: PdfPages) =
        AndroidPdfPort(pages, Dispatchers.IO.limitedParallelism(1), cacheBytes = 1 shl 20)

    @Test
    fun renderFillsThePageSize() = runBlocking {
        val pages = FakePages(listOf(IntSize(595, 842), IntSize(842, 595)))
        val pdf = port(pages)

        pdf.render(1, 500)
        assertEquals(IntSize(842, 595), pdf.pageSize(1))
        // The page was opened once, to render it; its size came with it.
        assertEquals(1, pages.opened)
    }

    @Test
    fun renderNarrowsAPageTooTallForItsWidth() = runBlocking {
        // 500 pixels wide, this strip would be 10,000 tall: it is rendered narrower, no side over 4096.
        val pdf = port(FakePages(listOf(IntSize(10, 200))))

        val bitmap = pdf.render(0, 500)
        assertEquals(pdfRenderWidth(10, 200, 500), bitmap.width)
        assertTrue("${bitmap.width} × ${bitmap.height}", bitmap.height <= 4096)
    }

    @Test
    fun closeFailureIsLoggedNotThrown() {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task).apply { setUncaughtExceptionHandler { _, e -> uncaught += e } }
        }
        val pages = FakePages(listOf(IntSize(595, 842)), closeFails = true)
        val pdf = AndroidPdfPort(pages, executor.asCoroutineDispatcher(), cacheBytes = 1 shl 20)

        pdf.close()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, SECONDS))

        assertEquals(1, pages.closeCalls)
        // Nothing reached the thread: an uncaught exception there would end the app.
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertTrue(ShadowLog.getLogs().any { it.throwable is IOException })
    }

    @Test
    fun openFailuresAreTheFileSystemsErrors() {
        // As Android's ParcelFileDescriptor.open says them. (Robolectric's open cannot be used here: when
        // it fails, it leaves a descriptor it made unclosed, and its CloseGuard prints a warning.)
        val denied = FileNotFoundException("/storage/1A2B-3C4D/a.pdf: open failed: EACCES (Permission denied)")
        val gone = FileNotFoundException("/storage/1A2B-3C4D/a.pdf: open failed: ENOENT (No such file or directory)")
        assertEquals(FsError.AccessDenied, pdfOpenFailure(denied).error)
        assertEquals(FsError.NotFound, pdfOpenFailure(gone).error)
    }
}
