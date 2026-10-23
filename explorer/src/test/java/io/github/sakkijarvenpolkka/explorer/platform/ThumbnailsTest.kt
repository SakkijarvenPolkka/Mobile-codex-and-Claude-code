// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThumbnailsTest {

    /** Makes the thumbnails with [make], by default a bitmap of the size asked, and records each load. */
    private class FakePort(
        private val make: (Path, Int, CancellationSignal) -> Bitmap? = { _, size, _ -> bitmap(size) },
    ) : ThumbnailPort {
        val loads = CopyOnWriteArrayList<Pair<Path, Int>>()

        override fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap? {
            loads += file to sizePx
            return make(file, sizePx, signal)
        }
    }

    private fun thumbnails(port: ThumbnailPort) = Thumbnails(port, Dispatchers.IO, maxBytes = 1 shl 20)

    private fun entry(path: String, mime: String? = "image/jpeg", modified: Long = 1_000L, kind: EntryKind = EntryKind.FILE) =
        FileEntry(Paths.get(path), Paths.get(path).fileName.toString(), kind, 100, modified, mime, false, null)

    /** Blocks the thread until [latch] opens, failing after 10 s. */
    private fun await(latch: CountDownLatch) = check(latch.await(10, SECONDS)) { "Timed out" }

    @Test
    fun cachesByPathMtimeSize() = runBlocking {
        val port = FakePort()
        val thumbnails = thumbnails(port)
        val photo = entry("/storage/emulated/0/DCIM/a.jpg")

        val first = thumbnails.get(photo, 96)
        val again = thumbnails.get(photo, 96)
        val bigger = thumbnails.get(photo, 192)

        assertNotNull(first)
        assertSame(first, again)
        assertSame(first, thumbnails.cached(photo, 96))
        assertEquals(192, bigger?.width)
        assertEquals(listOf(photo.path to 96, photo.path to 192), port.loads.toList())
    }

    @Test
    fun mtimeChangeInvalidates() = runBlocking {
        val port = FakePort()
        val thumbnails = thumbnails(port)
        val photo = entry("/storage/emulated/0/DCIM/a.jpg")

        thumbnails.get(photo, 96)
        val changed = photo.copy(modified = 2_000L)
        assertNull(thumbnails.cached(changed, 96))
        thumbnails.get(changed, 96)

        assertEquals(2, port.loads.size)
    }

    @Test
    fun failureCachedAsNegative() = runBlocking {
        val broken = entry("/storage/emulated/0/broken.jpg")
        val empty = entry("/storage/emulated/0/song.mp3", mime = "audio/mpeg")
        val port = FakePort { file, _, _ -> if (file == broken.path) throw IOException("Not an image") else null }
        val thumbnails = thumbnails(port)

        assertNull(thumbnails.get(broken, 96))
        assertNull(thumbnails.get(empty, 96))
        assertNull(thumbnails.get(broken, 96))
        assertNull(thumbnails.get(empty, 96))

        assertEquals(listOf(broken.path, empty.path), port.loads.map { it.first })
    }

    @Test
    fun cancelledRequestCancelsSignal() = runBlocking {
        val loading = CountDownLatch(1)
        val signals = CopyOnWriteArrayList<CancellationSignal>()
        val port = FakePort { _, size, signal ->
            signals += signal
            if (signals.size == 1) {
                // Like ThumbnailUtils: it goes on until the signal stops it.
                val stopped = CountDownLatch(1)
                signal.setOnCancelListener { stopped.countDown() }
                loading.countDown()
                await(stopped)
                throw OperationCanceledException()
            }
            bitmap(size)
        }
        val thumbnails = thumbnails(port)
        val photo = entry("/storage/emulated/0/DCIM/a.jpg")

        val request = launch(Dispatchers.Default) { thumbnails.get(photo, 96) }
        await(loading)
        withTimeout(10_000) { request.cancelAndJoin() }

        assertTrue(signals.single().isCanceled)
        // A cancelled request is not a failure: the next one loads again.
        assertNotNull(thumbnails.get(photo, 96))
        assertEquals(2, port.loads.size)
    }

    @Test
    fun atMostThreeConcurrent() = runBlocking {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val release = CountDownLatch(1)
        val port = FakePort { _, size, _ ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            try {
                await(release)
            } finally {
                running.decrementAndGet()
            }
            bitmap(size)
        }
        val thumbnails = thumbnails(port)

        val requests = (1..10).map { i ->
            async(Dispatchers.Default) { thumbnails.get(entry("/storage/emulated/0/DCIM/$i.jpg"), 96) }
        }
        withTimeout(10_000) { while (running.get() < 3) delay(10) }
        // Time for a fourth to start, if one could.
        delay(300)
        assertEquals(3, most.get())
        release.countDown()

        assertEquals(10, requests.awaitAll().count { it != null })
        assertEquals(3, most.get())
        assertEquals(10, port.loads.size)
    }

    @Test
    fun evictUnderRoot() = runBlocking {
        val card = entry("/storage/1A2B-3C4D/DCIM/a.jpg")
        val unreadable = entry("/storage/1A2B-3C4D/DCIM/b.jpg")
        val lookalike = entry("/storage/1A2B-3C4DE/c.jpg")
        val phone = entry("/storage/emulated/0/d.jpg")
        val unreadableLoads = AtomicInteger()
        val port = FakePort { file, size, _ ->
            // The card was going: one of its files could not be read the first time.
            if (file == unreadable.path && unreadableLoads.incrementAndGet() == 1) throw IOException("Gone")
            bitmap(size)
        }
        val thumbnails = thumbnails(port)
        val all = listOf(card, unreadable, lookalike, phone)
        all.forEach { thumbnails.get(it, 96) }

        thumbnails.evictUnder(Paths.get("/storage/1A2B-3C4D"))
        assertNull(thumbnails.cached(card, 96))
        assertNotNull(thumbnails.cached(phone, 96))
        all.forEach { thumbnails.get(it, 96) }

        assertEquals(all.map { it.path } + listOf(card.path, unreadable.path), port.loads.map { it.first })
        assertNotNull(thumbnails.cached(unreadable, 96))
    }

    @Test
    fun resultLoadedWhileEvictingIsNotCached() = runBlocking {
        val loading = CountDownLatch(1)
        val evicted = CountDownLatch(1)
        val photo = entry("/storage/1A2B-3C4D/DCIM/a.jpg")
        val port = FakePort { _, size, _ ->
            if (loading.count == 1L) {
                loading.countDown()
                await(evicted)
                // The card went while this was read.
                throw IOException("Gone")
            }
            bitmap(size)
        }
        val thumbnails = thumbnails(port)

        val request = async(Dispatchers.Default) { thumbnails.get(photo, 96) }
        await(loading)
        thumbnails.evictUnder(Paths.get("/storage/1A2B-3C4D"))
        evicted.countDown()

        assertNull(request.await())
        assertNotNull(thumbnails.get(photo, 96))
        assertEquals(2, port.loads.size)
    }

    @Test
    fun thumbKindByMimeType() {
        assertEquals(ThumbKind.IMAGE, thumbKindFor(entry("/s/a.heic", mime = "image/heic")))
        assertEquals(ThumbKind.VIDEO, thumbKindFor(entry("/s/a.mp4", mime = "video/mp4")))
        assertEquals(ThumbKind.AUDIO, thumbKindFor(entry("/s/a.mp3", mime = "AUDIO/MPEG")))
        assertNull(thumbKindFor(entry("/s/a.pdf", mime = "application/pdf")))
        assertNull(thumbKindFor(entry("/s/a", mime = null)))
        assertNull(thumbKindFor(entry("/s/Pictures", mime = null, kind = EntryKind.DIR)))
    }

    @Test
    fun noThumbnailWithoutAKind() = runBlocking {
        val port = FakePort()
        assertNull(thumbnails(port).get(entry("/s/a.pdf", mime = "application/pdf"), 96))
        assertEquals(0, port.loads.size)
    }

    private companion object {
        fun bitmap(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    }
}
