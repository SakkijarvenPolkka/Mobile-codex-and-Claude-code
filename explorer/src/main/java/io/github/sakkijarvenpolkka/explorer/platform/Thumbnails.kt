/*
 * Files — thumbnails of pictures, videos and songs for the tiles and large icons views, made by
 * ThumbnailUtils a few at a time and kept in memory (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** What a thumbnail is made from: a picture, a video's frame, or the art a song embeds. */
enum class ThumbKind { IMAGE, VIDEO, AUDIO }

/** Makes thumbnails ([AndroidThumbnailPort]); tests put a fake. */
interface ThumbnailPort {
    /**
     * A thumbnail of [file] about [sizePx] pixels a side, or null when it has none; it may also throw
     * (an IOException, for example). It blocks, and stops soon after [signal] is cancelled.
     */
    fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap?
}

/** The thumbnail of the file at [path] as it was when last [modified], [sizePx] pixels a side. */
data class ThumbKey(val path: String, val modified: Long, val sizePx: Int)

/**
 * The kind of thumbnail [entry] has, from its MIME type (parameters and case ignored): pictures,
 * videos and songs. Folders and other files have none.
 */
fun thumbKindFor(entry: FileEntry): ThumbKind? {
    if (entry.kind != EntryKind.FILE) return null
    val mime = entry.mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    return when {
        mime.startsWith("image/") -> ThumbKind.IMAGE
        mime.startsWith("video/") -> ThumbKind.VIDEO
        mime.startsWith("audio/") -> ThumbKind.AUDIO
        else -> null
    }
}

/**
 * The app's thumbnails, made by [port] on [dispatcher], at most [PARALLEL_LOADS] at a time, and kept
 * in an LRU cache of [maxBytes] (the bitmaps' allocation sizes) by [ThumbKey]: a file changed since
 * gets a new one. A file the port makes none of is remembered as having none, until the cache drops
 * that or [evictUnder] its volume. Safe to use from any thread.
 */
class Thumbnails(private val port: ThumbnailPort, dispatcher: CoroutineDispatcher, maxBytes: Int) {

    /** A cached thumbnail; [bitmap] is null for a file that has none. */
    private class Thumb(val bitmap: Bitmap?)

    private val loads = dispatcher.limitedParallelism(PARALLEL_LOADS)

    private val cache = object : LruCache<ThumbKey, Thumb>(maxBytes) {
        override fun sizeOf(key: ThumbKey, value: Thumb): Int = value.bitmap?.allocationByteCount ?: NONE_BYTES
    }

    /** Counts the calls of [evictUnder], so that what was loaded meanwhile is not cached. */
    private val evictions = AtomicInteger()

    /**
     * The thumbnail of [entry], [sizePx] pixels a side, from the cache or made now; null when it has
     * none ([thumbKindFor], or the port made none or failed). Cancelling the call cancels the port's
     * signal; the cancelled load is not remembered.
     */
    suspend fun get(entry: FileEntry, sizePx: Int): Bitmap? {
        val kind = thumbKindFor(entry) ?: return null
        val key = keyOf(entry, sizePx)
        cache.get(key)?.let { return it.bitmap }
        val epoch = evictions.get()
        val bitmap = load(entry.path, kind, sizePx)
        if (evictions.get() == epoch) cache.put(key, Thumb(bitmap))
        return bitmap
    }

    /** The thumbnail of [entry] if [get] has it cached, without making one; null otherwise. */
    fun cached(entry: FileEntry, sizePx: Int): Bitmap? = cache.get(keyOf(entry, sizePx))?.bitmap

    /** Forgets the thumbnails of the files under [root] (a volume that went), those it has none of too. */
    fun evictUnder(root: Path) {
        evictions.incrementAndGet()
        for (key in cache.snapshot().keys) {
            if (Paths.get(key.path).startsWith(root)) cache.remove(key)
        }
    }

    /**
     * [ThumbnailPort.load] on [loads]; null when it throws. This waits for it in a way that can be
     * cancelled ([async]/await), and cancels the port's signal then, which the blocking load needs to
     * stop; the load's own exception never takes the cancellation's place, since it is caught in there.
     */
    private suspend fun load(file: Path, kind: ThumbKind, sizePx: Int): Bitmap? = coroutineScope {
        val signal = CancellationSignal()
        val loading = async(loads) {
            try {
                port.load(file, kind, sizePx, signal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        try {
            loading.await()
        } catch (e: CancellationException) {
            signal.cancel()
            throw e
        }
    }

    private fun keyOf(entry: FileEntry, sizePx: Int) = ThumbKey(entry.path.toString(), entry.modified, sizePx)

    private companion object {
        /** How many thumbnails are made at the same time. */
        const val PARALLEL_LOADS = 3

        /** What remembering that a file has no thumbnail counts for in the cache: its key, roughly. */
        const val NONE_BYTES = 256
    }
}

/**
 * [ThumbnailPort] on [ThumbnailUtils], which uses the thumbnail a file embeds when it is big enough,
 * else decodes it scaled down (for a video, a frame; for a song, its album art).
 */
class AndroidThumbnailPort : ThumbnailPort {
    override fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap? {
        val size = Size(sizePx, sizePx)
        return when (kind) {
            ThumbKind.IMAGE -> ThumbnailUtils.createImageThumbnail(file.toFile(), size, signal)
            ThumbKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(file.toFile(), size, signal)
            ThumbKind.AUDIO -> ThumbnailUtils.createAudioThumbnail(file.toFile(), size, signal)
        }
    }
}
