/*
 * Files — the decoders behind the viewers, as interfaces UI tests replace with fakes: the
 * text loader, the image decoder, the PDF renderer and the media player.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.fs.SniffResult
import io.github.sakkijarvenpolkka.explorer.fs.TEXT_PREVIEW_LIMIT
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.fs.TextSniffer
import io.github.sakkijarvenpolkka.explorer.fs.splitDisplayLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A file as [TextViewer] shows it. */
sealed interface TextDocument {
    /**
     * The first [TEXT_PREVIEW_LIMIT] bytes decoded with [encoding] and split into [lines];
     * [truncated] when the file is longer.
     */
    data class Lines(val lines: List<DisplayLine>, val encoding: TextEncoding, val truncated: Boolean) : TextDocument

    /** Not text ([SniffResult.Binary]); a forced encoding shows it anyway. */
    data object Binary : TextDocument
}

/** Reads text files for [TextViewer]. */
interface TextLoader {
    /**
     * [file] decoded with [forced], or with the encoding [TextSniffer] detects when it is null.
     * A file that cannot be read is an [java.io.IOException].
     */
    suspend fun load(file: Path, forced: TextEncoding?): TextDocument
}

/** [TextLoader] on [FsBackend.openRead]: reads the head of the file, decodes it and splits it into rows on [io]. */
class FsTextLoader(private val fs: FsBackend, private val io: CoroutineDispatcher) : TextLoader {

    override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = withContext(io) {
        val (head, truncated) = fs.openRead(file).use { TextSniffer.readHead(it) }
        when (val result = TextSniffer.sniff(head, truncated, forced)) {
            is SniffResult.Text -> TextDocument.Lines(splitDisplayLines(result.text), result.encoding, result.truncated)
            SniffResult.Binary -> TextDocument.Binary
        }
    }
}

/**
 * Decodes images for [ImageViewer]. A file that cannot be decoded is an [java.io.IOException]
 * (decoding errors included).
 */
interface ImageDecoderPort {
    /**
     * [file] as a still image (the first frame of an animation), scaled down to fit [maxWidth] ×
     * [maxHeight] pixels, keeping its proportions; a smaller one keeps its size.
     */
    suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap

    /**
     * The size of [file] in pixels, as [decode] would turn it (by its orientation) before scaling it
     * down: what its own pixels are, which the zoom is measured in. Only the header is read.
     */
    suspend fun sizeOf(file: Path): IntSize

    /**
     * [file] as an animation (an animated GIF or WebP), scaled as [decode] scales it; null when it is a
     * still image. Decoders that play no animations have none.
     */
    suspend fun decodeAnimation(file: Path, maxWidth: Int, maxHeight: Int): AnimatedImageDrawable? = null
}

/**
 * The size an image of [original] pixels is decoded at to fit [maxWidth] × [maxHeight] ([ImageDecoderPort.decode]):
 * scaled down to the side that limits it, keeping its proportions, at least a pixel a side; an image that
 * fits keeps its size, never enlarged.
 */
fun fitSize(original: IntSize, maxWidth: Int, maxHeight: Int): IntSize {
    val scale = minOf(maxWidth.toFloat() / original.width, maxHeight.toFloat() / original.height)
    if (scale >= 1f) return original
    return IntSize(max(1, (original.width * scale).roundToInt()), max(1, (original.height * scale).roundToInt()))
}

/** An open PDF document for [PdfViewer], its pages counted from 0; [close] lets go of the file. */
interface PdfPort : Closeable {
    val pageCount: Int

    /** The size of page [index] in points, the unit of the document. */
    suspend fun pageSize(index: Int): IntSize

    /**
     * Page [index] drawn for a view [widthPx] pixels wide: that wide, or narrower when [pdfRenderWidth]
     * caps a page of its size, as tall as its proportions make it, on white. Rendering a page tells its
     * size too: [pageSize] asked after it has it at once.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap
}

/**
 * Opens PDF documents for [PdfViewer]. It blocks while it reads the file, so it is called off the
 * main thread. A password protected document is an [FsException] of [FsError.Encrypted], one that
 * cannot be read as a PDF one of [FsError.Corrupt]; a file that cannot be opened at all fails as the
 * file system says ([FsError.NotFound], [FsError.AccessDenied], ...).
 */
fun interface PdfOpener {
    fun open(file: Path): PdfPort
}

/** The longest side of a rendered PDF page, in pixels. */
private const val PDF_MAX_SIDE = 4096

/** The most pixels of a rendered PDF page (16 MP). */
private const val PDF_MAX_AREA = 16.0 * 1024 * 1024

/**
 * How many pixels wide a page of [pageW] × [pageH] points is rendered for a view [viewportW] pixels
 * wide: the width of the view, narrower when the page would otherwise be longer than 4096 pixels on a
 * side or larger than 16 MP. At least 1.
 */
fun pdfRenderWidth(pageW: Int, pageH: Int, viewportW: Int): Int {
    // Width per pixel of height.
    val ratio = max(1, pageW).toDouble() / max(1, pageH)
    val bySide = PDF_MAX_SIDE * min(1.0, ratio)
    val byArea = sqrt(PDF_MAX_AREA * ratio)
    return max(1, minOf(viewportW.toDouble(), bySide, byArea).toInt())
}

/**
 * Plays one audio or video file for [MediaViewer], called on the main thread: [prepare] it, then
 * [play], [pause] and [seekTo] once it is ready, until [release].
 */
interface MediaPort {
    /**
     * Starts preparing [file] (a [video] or an audio file). [onReady] hears its duration when it can
     * play, and again whenever it was prepared anew (a video whose surface came back after the app
     * was in the background); [onError] hears that it cannot be played.
     */
    fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit)

    fun play()

    fun pause()

    fun seekTo(ms: Int)

    val positionMs: Int

    /** Whether it plays now: false once paused, finished, or stopped by losing the audio focus or the headphones. */
    val isPlaying: Boolean

    /** Stops playing and lets go of the file and the player. */
    fun release()

    /**
     * Where a video file is drawn, filling [modifier]'s bounds; ports that draw no video draw nothing.
     * One port feeds one VideoSurface: a view can be in one place only, so each viewer has a port of its own.
     */
    @Composable
    fun VideoSurface(modifier: Modifier) {
    }
}
