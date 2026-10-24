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

/** The largest animated image played, in bytes (32 MiB): it is read into memory whole. */
private const val ANIMATION_MAX_BYTES = 32L * 1024 * 1024

/**
 * [ImageDecoderPort] on [ImageDecoder], on [io]: the image is decoded at the size it is shown at
 * ([ImageDecoder.setTargetSize], [fitSize]), which keeps big photos small in memory. A still image is
 * read from the file while it is decoded; an animation is played from the file read into memory, as
 * one played from the file would hold it open for as long as it is shown. Tests read files through
 * [fileSource] and [readBytes] (whole).
 */
class AndroidImageDecoder internal constructor(
    private val io: CoroutineDispatcher,
    private val fileSource: (Path) -> ImageDecoder.Source,
    private val readBytes: (Path) -> ByteArray,
) : ImageDecoderPort {

    constructor(io: CoroutineDispatcher) : this(io, { ImageDecoder.createSource(it.toFile()) }, Files::readAllBytes)

    override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap = withContext(io) {
        ImageDecoder.decodeBitmap(fileSource(file)) { decoder, info, _ ->
            fit(decoder, info, maxWidth, maxHeight)
        }.asImageBitmap()
    }

    override suspend fun sizeOf(file: Path): IntSize = withContext(io) {
        var size: IntSize? = null
        try {
            ImageDecoder.decodeBitmap(fileSource(file)) { _, info, _ ->
                size = IntSize(info.size.width, info.size.height)
                throw HeaderRead()
            }
        } catch (e: HeaderRead) {
            // Only the header was wanted.
        }
        size ?: throw IOException("No image header in $file")
    }

    /**
     * Whether [file] is animated is read from its header in the file; only an animation is then decoded,
     * from the bytes of [file] read into memory: an [AnimatedImageDrawable] reads its frames from its
     * source for as long as it plays and cannot be closed, so one decoded from the file would keep it
     * open past its volume going. A file larger than 32 MiB is shown still.
     */
    override suspend fun decodeAnimation(file: Path, maxWidth: Int, maxHeight: Int): AnimatedImageDrawable? =
        withContext(io) {
            if (Files.size(file) > ANIMATION_MAX_BYTES || !isAnimated(file)) return@withContext null
            val source = ImageDecoder.createSource(ByteBuffer.wrap(readBytes(file)))
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                fit(decoder, info, maxWidth, maxHeight)
            } as? AnimatedImageDrawable
        }

    /** Whether the header of [file] says it is animated; nothing else of it is read. */
    private fun isAnimated(file: Path): Boolean {
        var animated = false
        try {
            ImageDecoder.decodeBitmap(fileSource(file)) { _, info, _ ->
                animated = info.isAnimated
                throw HeaderRead()
            }
        } catch (e: HeaderRead) {
            // Only the header was wanted.
        }
        return animated
    }

    /** Has [decoder] scale the image of [info] down to fit [maxWidth] × [maxHeight] ([fitSize]). */
    private fun fit(decoder: ImageDecoder, info: ImageDecoder.ImageInfo, maxWidth: Int, maxHeight: Int) {
        val original = IntSize(info.size.width, info.size.height)
        val target = fitSize(original, maxWidth, maxHeight)
        if (target != original) decoder.setTargetSize(target.width, target.height)
    }

    /** Thrown from the header listener to stop [ImageDecoder] before it decodes the pixels. */
    private class HeaderRead : RuntimeException()
}
