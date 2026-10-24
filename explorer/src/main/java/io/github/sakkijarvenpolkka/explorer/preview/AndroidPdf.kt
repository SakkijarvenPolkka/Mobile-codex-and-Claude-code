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
import android.util.LruCache
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/** How many pixels tall a page of [pageW] × [pageH] points is when rendered [widthPx] pixels wide. At least 1. */
private fun pdfRenderHeight(pageW: Int, pageH: Int, widthPx: Int): Int =
    max(1, (widthPx.toDouble() * max(1, pageH) / max(1, pageW)).roundToInt())

/** The screens of pixels an [AndroidPdfOpener] document keeps rendered pages for. */
private const val PDF_CACHED_SCREENS = 3

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
            throw FsException(FsError.Corrupt, e)
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
        return AndroidPdfPort(renderer, io.limitedParallelism(1), cacheBytes)
    }
}

/** A page of a document rendered at a width. */
private data class RenderedPage(val index: Int, val widthPx: Int)

/** The document of [renderer], used only on [serial]. */
private class AndroidPdfPort(
    private val renderer: PdfRenderer,
    private val serial: CoroutineDispatcher,
    cacheBytes: Int,
) : PdfPort {

    override val pageCount: Int = renderer.pageCount

    // Used only on [serial].
    private val sizes = arrayOfNulls<IntSize>(pageCount)
    private var closed = false
    private val cache = object : LruCache<RenderedPage, Bitmap>(max(1, cacheBytes)) {
        override fun sizeOf(key: RenderedPage, value: Bitmap): Int = value.allocationByteCount
    }

    override suspend fun pageSize(index: Int): IntSize = withContext(serial) {
        sizes[index] ?: withPage(index) { IntSize(it.width, it.height) }
    }

    override suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(serial) {
        val key = RenderedPage(index, widthPx)
        cache[key] ?: withPage(index) { page ->
            createBitmap(widthPx, pdfRenderHeight(page.width, page.height, widthPx), Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }.also { cache.put(key, it) }
    }

    /** [block] on page [index], opened for it and closed after; its size is remembered. */
    private inline fun <T> withPage(index: Int, block: (PdfRenderer.Page) -> T): T {
        if (closed) throw IOException("The document is closed")
        return renderer.openPage(index).use { page ->
            sizes[index] = IntSize(page.width, page.height)
            block(page)
        }
    }

    /** Closes the renderer (and with it the file) on [serial], after what runs there now. */
    override fun close() {
        CoroutineScope(serial).launch {
            if (closed) return@launch
            closed = true
            cache.evictAll()
            renderer.close()
        }
    }
}
