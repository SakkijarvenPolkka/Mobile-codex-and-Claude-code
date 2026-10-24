/*
 * Files — the PDF documents of the viewer on the platform's PdfRenderer.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/** How many pixels tall a page of [pageW] × [pageH] points is when rendered [widthPx] pixels wide. At least 1. */
private fun pdfRenderHeight(pageW: Int, pageH: Int, widthPx: Int): Int =
    max(1, (widthPx.toDouble() * max(1, pageH) / max(1, pageW)).roundToInt())

/** The screens of pixels an [AndroidPdfOpener] document keeps rendered pages for. */
private const val PDF_CACHED_SCREENS = 3

private const val TAG = "AndroidPdf"

/**
 * [PdfOpener] on [PdfRenderer], one for each document. A renderer may not be used by two threads at
 * once, so every call to it once it is made, closing included, runs on a view of [io] that runs one
 * thing at a time ([CoroutineDispatcher.limitedParallelism]). Pages are rendered on white into
 * ARGB_8888 bitmaps, the latest kept in a cache of about three screens of [context]'s display in bytes.
 *
 * The renderer is made, and its page count read, on the thread that calls [open], before it is
 * handed to that view: no other thread can reach it yet, and the dispatch to the view publishes it
 * safely. [open] does not suspend, so waiting in it for the view would block a second thread, and
 * would never end if [io] had only the caller's thread.
 */
class AndroidPdfOpener(context: Context, private val io: CoroutineDispatcher) : PdfOpener {

    private val cacheBytes = context.resources.displayMetrics.let { PDF_CACHED_SCREENS * it.widthPixels * it.heightPixels * 4 }

    override fun open(file: Path): PdfPort {
        val fd = try {
            ParcelFileDescriptor.open(file.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw pdfOpenFailure(e)
        }
        // The renderer closes the descriptor when it is closed; until it exists, this does.
        val renderer = try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            fd.close()
            throw FsException(FsError.Encrypted, e)
        } catch (e: IOException) {
            fd.close()
            throw FsException(FsError.Corrupt, e)
        } catch (e: IllegalArgumentException) {
            // A descriptor that cannot seek.
            fd.close()
            throw FsException(FsError.Corrupt, e)
        }
        return AndroidPdfPort(RendererPages(renderer), io.limitedParallelism(1), cacheBytes)
    }
}

/**
 * Why [ParcelFileDescriptor.open] could not open a document ([e]), as the file system tells it: a file
 * gone or not to be read is not a damaged one.
 */
internal fun pdfOpenFailure(e: IOException): FsException = FsException(e.toFsError(), e)

/** A page of a document rendered at a width. */
private data class RenderedPage(val index: Int, val widthPx: Int)

/** A document as [AndroidPdfPort] reads it: a [PdfRenderer], or a fake in tests; [close] lets go of the file. */
internal interface PdfPages : Closeable {
    val pageCount: Int

    /** Opens page [index], hands it to [block] and closes it again. */
    fun <T> withPage(index: Int, block: (PdfPage) -> T): T
}

/** A page open in [PdfPages.withPage]: its size in points, and drawn to fill a bitmap. */
internal interface PdfPage {
    val width: Int
    val height: Int

    fun render(into: Bitmap)
}

/** The pages of [renderer]. */
private class RendererPages(private val renderer: PdfRenderer) : PdfPages {
    override val pageCount: Int get() = renderer.pageCount

    override fun <T> withPage(index: Int, block: (PdfPage) -> T): T = renderer.openPage(index).use { page ->
        block(
            object : PdfPage {
                override val width: Int get() = page.width
                override val height: Int get() = page.height

                override fun render(into: Bitmap) =
                    page.render(into, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            },
        )
    }

    override fun close() = renderer.close()
}

/** The document of [pages], used only on [serial]. */
internal class AndroidPdfPort(
    private val pages: PdfPages,
    private val serial: CoroutineDispatcher,
    cacheBytes: Int,
) : PdfPort {

    override val pageCount: Int = pages.pageCount

    // Used only on [serial].
    private val sizes = arrayOfNulls<IntSize>(pageCount)
    private var closed = false
    private val cache = object : LruCache<RenderedPage, Bitmap>(max(1, cacheBytes)) {
        override fun sizeOf(key: RenderedPage, value: Bitmap): Int = value.allocationByteCount
    }

    override suspend fun pageSize(index: Int): IntSize = withContext(serial) {
        sizes[index] ?: withPage(index) { IntSize(it.width, it.height) }
    }

    /** Page [index] rendered for a view [widthPx] wide, opened once: its size is learnt as it is rendered. */
    override suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(serial) {
        val key = RenderedPage(index, widthPx)
        cache[key] ?: withPage(index) { page ->
            val width = pdfRenderWidth(page.width, page.height, widthPx)
            createBitmap(width, pdfRenderHeight(page.width, page.height, width), Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this)
            }
        }.also { cache.put(key, it) }
    }

    /** [block] on page [index], opened for it and closed after; its size is remembered. */
    private fun <T> withPage(index: Int, block: (PdfPage) -> T): T {
        if (closed) throw IOException("The document is closed")
        return pages.withPage(index) { page ->
            sizes[index] = IntSize(page.width, page.height)
            block(page)
        }
    }

    /**
     * Closes the renderer (and with it the file) on [serial], after what runs there now. Its failure is
     * logged: nothing waits for it, and thrown on that thread it would end the app.
     */
    override fun close() {
        CoroutineScope(serial).launch {
            if (closed) return@launch
            closed = true
            cache.evictAll()
            try {
                pages.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Closing a PDF document failed", e)
            }
        }
    }
}
