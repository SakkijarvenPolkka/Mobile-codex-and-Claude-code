/*
 * Files — the image decoder of the viewer on the platform's ImageDecoder.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/** The largest animated image played, in bytes (32 MiB): it is read into memory whole. */
private const val ANIMATION_MAX_BYTES = 32L * 1024 * 1024

/**
 * [ImageDecoderPort] on [ImageDecoder], on [io]: the image is decoded at the size it is shown at
 * ([ImageDecoder.setTargetSize]), which keeps big photos small in memory. A still image is read from
 * the file while it is decoded; an animation is played from the file read into memory, as one played
 * from the file would hold it open for as long as it is shown.
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

    /**
     * The animation is decoded from the bytes of [file], read into memory: an [AnimatedImageDrawable]
     * reads its frames from its source for as long as it plays and cannot be closed, so one decoded from
     * the file would keep it open past its volume going. A file larger than 32 MiB is shown still.
     */
    override suspend fun decodeAnimation(file: Path, maxWidth: Int, maxHeight: Int): AnimatedImageDrawable? =
        withContext(io) {
            if (Files.size(file) > ANIMATION_MAX_BYTES) return@withContext null
            val source = ImageDecoder.createSource(ByteBuffer.wrap(Files.readAllBytes(file)))
            try {
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
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
