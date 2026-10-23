/*
 * Files — the decoders behind the viewers, as interfaces UI tests replace with fakes: the
 * text loader and the image decoder.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.SniffResult
import io.github.sakkijarvenpolkka.explorer.fs.TEXT_PREVIEW_LIMIT
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.fs.TextSniffer
import io.github.sakkijarvenpolkka.explorer.fs.splitDisplayLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

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
