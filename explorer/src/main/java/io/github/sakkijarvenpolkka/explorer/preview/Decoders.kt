/*
 * Files — the decoders behind the viewers, as interfaces UI tests replace with fakes: the
 * text loader, the image decoder, the PDF renderer and the media player.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
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
 * [ImageDecoderPort] on [ImageDecoder], on [io]: the image is decoded at the size it is shown at
 * ([ImageDecoder.setTargetSize]), which keeps big photos small in memory.
 */
class AndroidImageDecoder(private val io: CoroutineDispatcher) : ImageDecoderPort {

    override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap = withContext(io) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file.toFile())) { decoder, info, _ ->
            fit(decoder, info, maxWidth, maxHeight)
        }.asImageBitmap()
    }

    override suspend fun sizeOf(file: Path): IntSize = withContext(io) {
        var size: IntSize? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file.toFile())) { _, info, _ ->
                size = IntSize(info.size.width, info.size.height)
                throw HeaderRead()
            }
        } catch (e: HeaderRead) {
            // Only the header was wanted.
        }
        size ?: throw IOException("No image header in $file")
    }

    override suspend fun decodeAnimation(file: Path, maxWidth: Int, maxHeight: Int): AnimatedImageDrawable? =
        withContext(io) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file.toFile())) { decoder, info, _ ->
                    if (!info.isAnimated) throw HeaderRead()
                    fit(decoder, info, maxWidth, maxHeight)
                } as? AnimatedImageDrawable
            } catch (e: HeaderRead) {
                // A still image: decode() decodes it.
                null
            }
        }

    /** Has [decoder] scale the image of [info] down to fit [maxWidth] × [maxHeight], keeping its proportions. */
    private fun fit(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, maxWidth: Int, maxHeight: Int) {
        val width = info.size.width
        val height = info.size.height
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        if (scale < 1f) {
            decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        }
    }

    /** Thrown from the header listener to stop [ImageDecoder] before it decodes the pixels. */
    private class HeaderRead : RuntimeException()
}

/** An open PDF document for [PdfViewer], its pages counted from 0; [close] lets go of the file. */
interface PdfPort : Closeable {
    val pageCount: Int

    /** The size of page [index] in points, the unit of the document. */
    suspend fun pageSize(index: Int): IntSize

    /** Page [index] drawn [widthPx] pixels wide (as [pdfRenderWidth] picks), as tall as its proportions make it, on white. */
    suspend fun render(index: Int, widthPx: Int): Bitmap
}

/**
 * Opens PDF documents for [PdfViewer]. It blocks while it reads the file, so it is called off the
 * main thread. A password protected document is an [FsException] of [FsError.Encrypted], one that
 * cannot be read one of [FsError.Corrupt].
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

    /** Where a video file is drawn, filling [modifier]'s bounds; ports that draw no video draw nothing. */
    @Composable
    fun VideoSurface(modifier: Modifier) {
    }
}

/**
 * [MediaPort] on the platform's players: a video plays in a [VideoView] given the file's Uri (and
 * drawn by [VideoSurface]), an audio file in a [MediaPlayer] given its file descriptor. Playing asks
 * for the audio focus ([AudioFocusRequest]) and pauses when it is lost or the headphones are unplugged
 * ([AudioManager.ACTION_AUDIO_BECOMING_NOISY]). [context] is the activity's, which the video view is
 * made with.
 */
class AndroidMediaPort(private val context: Context) : MediaPort {

    private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private var video = false
    private var videoView: VideoView? = null
    private var player: MediaPlayer? = null

    /** Whether [player] is prepared (a [VideoView] keeps track of its own player). */
    private var playerReady = false

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    private var listening = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    /** The video view, made the first time it is wanted; it asks for no audio focus of its own. */
    private fun videoView(): VideoView = videoView ?: VideoView(context).also {
        it.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        it.setAudioAttributes(attributes)
        videoView = it
    }

    override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) {
        this.video = video
        if (video) {
            val view = videoView()
            view.setOnPreparedListener { onReady(it.duration) }
            view.setOnErrorListener { _, _, _ ->
                stopListening()
                onError()
                true
            }
            view.setOnCompletionListener { stopListening() }
            view.setVideoURI(Uri.fromFile(file.toFile()))
            return
        }
        val player = MediaPlayer()
        this.player = player
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            playerReady = true
            onReady(it.duration)
        }
        player.setOnErrorListener { _, _, _ ->
            playerReady = false
            stopListening()
            onError()
            true
        }
        player.setOnCompletionListener { stopListening() }
        try {
            FileInputStream(file.toFile()).use { player.setDataSource(it.fd) }
            player.prepareAsync()
        } catch (e: IOException) {
            onError()
        } catch (e: IllegalArgumentException) {
            onError()
        } catch (e: SecurityException) {
            onError()
        }
    }

    override fun play() {
        if (!video && !playerReady) return
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (!listening) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(context, noisy, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        if (video) videoView().start() else player?.start()
    }

    /**
     * Pauses the file if it plays: [MediaPlayer.pause] on a player prepared but never started puts
     * it in its error state ([VideoView.pause] checks the same).
     */
    override fun pause() {
        if (video) videoView?.pause() else if (isPlaying) player?.pause()
        stopListening()
    }

    override fun seekTo(ms: Int) {
        if (video) videoView?.seekTo(ms) else if (playerReady) player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    override val positionMs: Int
        get() = when {
            video -> videoView?.currentPosition ?: 0
            playerReady -> player?.currentPosition ?: 0
            else -> 0
        }

    override val isPlaying: Boolean
        get() = if (video) videoView?.isPlaying == true else playerReady && player?.isPlaying == true

    override fun release() {
        stopListening()
        videoView?.stopPlayback()
        player?.release()
        player = null
        playerReady = false
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(factory = { videoView() }, modifier)
    }

    /** Lets go of the audio focus and stops listening for the headphones. */
    private fun stopListening() {
        if (listening) {
            context.unregisterReceiver(noisy)
            listening = false
        }
        audioManager.abandonAudioFocusRequest(focus)
    }
}
