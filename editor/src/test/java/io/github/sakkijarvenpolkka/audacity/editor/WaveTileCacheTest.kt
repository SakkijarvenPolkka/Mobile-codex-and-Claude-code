// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.ClipState
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Engine whose waveform tiles are programmable; everything else is the fake engine. */
private class TileEngine(val inner: FakeAudacityEngine = FakeAudacityEngine(FakeConfig(demoProject = false, autoTick = false))) :
    AudacityEngine by inner {
    val versions = HashMap<Long, Long>()
    var partial = false
    var forcedStatus: Long? = null
    var calls = 0
    var envelopeCalls = 0

    override suspend fun waveColumnsInto(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        calls++
        forcedStatus?.let { return it }
        for (i in 0 until count) {
            out[i] = -0.5f; out[count + i] = 0.5f; out[2 * count + i] = 0.25f
        }
        val v = versions[trackId] ?: 0L
        return if (partial) v or DisplayStatus.PARTIAL_BIT else v
    }

    var spectroCalls = 0
    override suspend fun spectrogramColumns(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, rows: Int): ByteArray? {
        spectroCalls++
        return ByteArray(count * rows) { (it % rows).toByte() }
    }

    override suspend fun envelopeColumnsInto(trackId: Long, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        envelopeCalls++
        out.fill(1f, 0, count)
        return 0L
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaveTileCacheTest {
    private val engine = TileEngine()

    @After
    fun tearDown() = engine.inner.dispose()

    private fun track(id: Long, version: Long) = TrackState(
        id = id, kind = "wave", name = "T$id", waveVersion = version, end = 10.0,
        clips = listOf(ClipState(index = 0, name = "c", start = 0.0, end = 10.0)),
    )

    private fun TestScope.cache() = WaveTileCache(engine, this, StandardTestDispatcher(testScheduler))

    @Test
    fun fetchesOnceAndStampsVersion() = runTest {
        val c = cache()
        engine.versions[1] = 5
        c.syncTracks(listOf(track(1, 5)))
        c.beginPass()
        c.request(1, 0, 10, 0)
        c.request(1, 0, 10, 0)                  // already loading: no second engine call
        advanceUntilIdle()
        assertEquals(1, engine.calls)
        val t = c.tile(1, 0, 10, 0)!!
        assertNotNull(t.data)
        assertEquals(5L, t.version)
        assertEquals(0L, t.firstColumn)
        assertFalse(c.needsFetch(1, t))
        c.beginPass()
        c.request(1, 0, 10, 0)
        advanceUntilIdle()
        assertEquals(1, engine.calls)
        assertEquals(0.5f, t.data!![256], 0f)
    }

    @Test
    fun newWaveVersionInvalidatesButKeepsDataUntilRefetched() = runTest {
        val c = cache()
        engine.versions[1] = 5
        c.syncTracks(listOf(track(1, 5)))
        c.beginPass()
        c.request(1, 0, 10, 3)
        advanceUntilIdle()
        val t = c.tile(1, 0, 10, 3)!!
        val oldData = t.data
        assertEquals(3L * 256, t.firstColumn)

        c.syncTracks(listOf(track(1, 6)))
        assertTrue(c.needsFetch(1, t))
        assertSame(oldData, t.data)             // stale data is still drawn
        engine.versions[1] = 6
        c.beginPass()
        c.request(1, 0, 10, 3)
        advanceUntilIdle()
        assertEquals(2, engine.calls)
        assertEquals(6L, t.version)
        assertFalse(c.needsFetch(1, t))
    }

    @Test
    fun otherTracksKeepTheirTiles() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 1), track(2, 1)))
        engine.versions[1] = 1; engine.versions[2] = 1
        c.beginPass()
        c.request(1, 0, 0, 0)
        c.request(2, 0, 0, 0)
        advanceUntilIdle()
        c.syncTracks(listOf(track(1, 2), track(2, 1)))
        assertTrue(c.needsFetch(1, c.tile(1, 0, 0, 0)))
        assertFalse(c.needsFetch(2, c.tile(2, 0, 0, 0)))
    }

    @Test
    fun engineVersionMismatchDoesNotLoop() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 7)))
        engine.versions[1] = 99                 // engine reports another value
        c.beginPass()
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertFalse(c.needsFetch(1, c.tile(1, 0, 0, 0)))
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertEquals(1, engine.calls)
    }

    @Test
    fun partialTilesAreRefetched() = runTest {
        val c = cache()
        engine.partial = true
        c.syncTracks(listOf(track(1, 0)))
        c.beginPass()
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        val t = c.tile(1, 0, 0, 0)!!
        assertTrue(t.partial)
        assertTrue(c.needsFetch(1, t))
        engine.partial = false
        c.beginPass()
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertEquals(2, engine.calls)
        assertFalse(t.partial)
    }

    @Test
    fun notReadyIsRetriedAndSampleModeRemembered() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 0)))
        engine.forcedStatus = DisplayStatus.NOT_READY
        c.beginPass()
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        val t = c.tile(1, 0, 0, 0)!!
        assertTrue(t.failed)
        assertTrue(c.needsFetch(1, t))
        engine.forcedStatus = DisplayStatus.SAMPLE_MODE
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertTrue(t.sampleMode)
        assertFalse(c.needsFetch(1, t))
    }

    @Test
    fun removedTracksDropTheirTiles() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 0)))
        c.beginPass()
        c.request(1, 0, 0, 0)
        c.request(1, 1, 0, 0)
        c.request(1, WaveTileCache.ENVELOPE, 0, 0)
        advanceUntilIdle()
        assertEquals(3, c.size)
        assertTrue(c.tile(1, WaveTileCache.ENVELOPE, 0, 0)!!.trivial)
        c.syncTracks(emptyList())
        assertNull(c.tile(1, 0, 0, 0))
        assertEquals(0, c.size)
    }

    @Test
    fun supersededRequestsSkipTheEngine() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 0)))
        c.beginPass()
        c.request(1, 0, 0, 0)                   // queued, then scrolled away (two passes)
        c.beginPass()
        c.beginPass()
        advanceUntilIdle()
        assertEquals(0, engine.calls)
        val t = c.tile(1, 0, 0, 0)!!
        assertTrue(c.needsFetch(1, t))          // fetched when wanted again
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertEquals(1, engine.calls)
    }

    @Test
    fun revisionBumpsWhenDataArrives() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 0)))
        val r0 = c.revision
        c.beginPass()
        c.request(1, 0, 0, 0)
        advanceUntilIdle()
        assertTrue(c.revision > r0)
    }

    @Test
    fun evictsTilesNotWantedRecently() = runTest {
        val c = WaveTileCache(engine, this, StandardTestDispatcher(testScheduler), maxTiles = 8)
        c.syncTracks(listOf(track(1, 0)))
        for (i in 0 until 40L) {
            c.beginPass()
            c.request(1, 0, 0, i)
            advanceUntilIdle()
        }
        assertTrue("size ${c.size}", c.size <= 8)
        assertNotNull(c.tile(1, 0, 0, 39)?.data)
    }

    @Test
    fun spectrogramTilesBecomeImagesAndFollowWaveVersion() = runTest {
        val c = cache()
        c.syncTracks(listOf(track(1, 3)))
        c.beginPass()
        c.request(1, WaveTileCache.SPECTRO + 1, 4, 2)
        advanceUntilIdle()
        assertEquals(1, engine.spectroCalls)
        val t = c.tile(1, WaveTileCache.SPECTRO + 1, 4, 2)!!
        val img = t.image!!
        assertEquals(256, img.width)
        assertEquals(WaveTileCache.SPECTRO_ROWS, img.height)
        assertFalse(c.needsFetch(1, t))
        c.syncTracks(listOf(track(1, 4)))
        assertTrue(c.needsFetch(1, t))
        assertNotNull(t.image)                  // stale image kept until refetched
        assertEquals(0, engine.calls)           // no waveform requests for spectrogram tiles
    }
}
