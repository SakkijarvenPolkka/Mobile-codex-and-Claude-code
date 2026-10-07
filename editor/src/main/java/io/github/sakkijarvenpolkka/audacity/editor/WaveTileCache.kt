/*
 * Audacity Android port — Kotlin side of the waveform display data path
 * (notes/waveform-data.md §2.10, API.md §7).
 *
 * Tiles of 256 absolute columns per (trackId, channel, zoomLevel, tile) are
 * fetched asynchronously through AudacityEngine.waveColumnsInto (off the main
 * thread, at most two requests in flight) and stamped with the track's
 * waveVersion. When a snapshot reports a new waveVersion the track's tiles
 * become stale: they are still drawn (no flicker while recording or after an
 * edit) and refetched. Partial tiles (recording tail) are refetched on every
 * poll. Sample-mode clips use per-channel sample runs (API.md §7.4).
 *
 * All bookkeeping happens on the main thread; only the engine call runs on
 * the fetch dispatcher, filling a buffer that is swapped in on the main
 * thread, so drawing never sees a half-written tile.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableLongObjectMap
import androidx.collection.mutableLongObjectMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.SampleRun
import io.github.sakkijarvenpolkka.audacity.engine.model.DisplayStatus
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One 256-column tile: `data` = min[256], max[256], rms[256]. */
internal class WaveTile(val firstColumn: Long) {
    /** Current data (null until the first successful fetch). */
    var data: FloatArray? = null
    /** waveVersion reported by the engine for [data]; −1 before the first fetch. */
    var version: Long = -1L
    /** Snapshot waveVersion the last completed fetch was made for. */
    var fetchedFor: Long = Long.MIN_VALUE
    /** True when [data] is partial (recording tail) and must be refetched. */
    var partial: Boolean = false
    /** The whole tile needs sample mode (status SAMPLE_MODE). */
    var sampleMode: Boolean = false
    /** For envelope tiles: all values 1 or NaN (no multiplication needed). */
    var trivial: Boolean = true
    @Volatile var loading: Boolean = false
    /** Request pass in which the tile was last wanted (LRU + superseding). */
    @Volatile var wantedPass: Long = 0L
    /** Failed fetch (NOT_READY etc.): retried on the next pass. */
    var failed: Boolean = false
    /** Spectrogram tiles: 256 × [WaveTileCache.SPECTRO_ROWS] colour image, row 0 = top (highest frequency). */
    var image: ImageBitmap? = null
}

/** Sample runs of one channel for a time window (sample mode). */
internal class SampleWindow(val t0: Double, val t1: Double, val version: Long, val runs: List<SampleRun>)

internal class WaveTileCache(
    private val engine: AudacityEngine,
    private val scope: CoroutineScope,
    private val fetchDispatcher: CoroutineDispatcher = FetchDispatcher,
    private val maxTiles: Int = 1536,
) {
    private class TrackEntry {
        var waveVersion: Long = Long.MIN_VALUE
        /** channel (0, 1, ENVELOPE, SPECTRO + 0/1) → level → tile index → tile. */
        val channels = arrayOfNulls<MutableIntObjectMap<MutableLongObjectMap<WaveTile>>>(5)
        val samples = arrayOfNulls<SampleWindow>(2)
        val samplesLoading = BooleanArray(2)
    }

    private val tracks: MutableLongObjectMap<TrackEntry> = mutableLongObjectMapOf()
    private var tileCount = 0
    private val pool = ArrayList<FloatArray>()

    /** Incremented whenever new data arrived (drawing observes it). */
    var revision by mutableIntStateOf(0)
        private set

    /** A fetch returned NOT_READY: request the visible tiles again soon. */
    var retryPending by mutableStateOf(false)
        private set

    /** Current request pass; [beginPass] increments it. */
    @Volatile
    var pass: Long = 1L
        private set

    /** Number of engine requests issued (tests, diagnostics). */
    var requestCount: Int = 0
        private set

    /** Starts a new request pass (call once per prefetch round). */
    fun beginPass(): Long {
        retryPending = false
        return ++pass
    }

    /**
     * Updates the known waveVersion of every track; tracks whose version
     * changed keep their tiles (marked stale) until refetched; tiles of
     * removed tracks are dropped.
     */
    fun syncTracks(list: List<TrackState>) {
        // Remove entries of tracks that disappeared.
        var removed = false
        tracks.removeIf { id, entry ->
            val gone = list.none { it.id == id }
            if (gone) {
                releaseEntry(entry)
                removed = true
            }
            gone
        }
        for (t in list) {
            if (!t.isWave) continue
            val e = tracks[t.id] ?: TrackEntry().also { tracks[t.id] = it }
            e.waveVersion = t.waveVersion
        }
        if (removed) revision++
    }

    /** The tile, or null when never requested. No allocation. */
    fun tile(trackId: Long, channel: Int, level: Int, tileIndex: Long): WaveTile? =
        tracks[trackId]?.channels?.getOrNull(channel)?.get(level)?.get(tileIndex)

    /** waveVersion last reported for [trackId] by [syncTracks]. */
    fun knownVersion(trackId: Long): Long = tracks[trackId]?.waveVersion ?: Long.MIN_VALUE

    /** True when [tile] must be (re)fetched. */
    fun needsFetch(trackId: Long, tile: WaveTile?): Boolean {
        if (tile == null) return true
        if (tile.loading) return false
        if (tile.failed || tile.partial) return true
        return tile.fetchedFor != knownVersion(trackId)
    }

    /**
     * Requests a waveform tile (channel 0/1) or envelope tile ([ENVELOPE])
     * if it is missing, stale, partial or failed. Returns the tile.
     */
    fun request(trackId: Long, channel: Int, level: Int, tileIndex: Long): WaveTile {
        val entry = tracks[trackId] ?: TrackEntry().also { tracks[trackId] = it }
        val levels = entry.channels[channel] ?: MutableIntObjectMap<MutableLongObjectMap<WaveTile>>().also {
            entry.channels[channel] = it
        }
        val tiles = levels[level] ?: mutableLongObjectMapOf<WaveTile>().also { levels[level] = it }
        val tile = tiles[tileIndex] ?: WaveTile(tileIndex * Zoom.TILE_COLUMNS).also {
            tiles[tileIndex] = it
            tileCount++
        }
        tile.wantedPass = pass
        if (needsFetch(trackId, tile)) fetch(trackId, channel, level, tile, entry.waveVersion)
        return tile
    }

    private fun fetch(trackId: Long, channel: Int, level: Int, tile: WaveTile, requestedFor: Long) {
        if (channel >= SPECTRO) {
            fetchSpectrogram(trackId, channel - SPECTRO, level, tile, requestedFor)
            return
        }
        tile.loading = true
        requestCount++
        scope.launch {
            val out = obtainBuffer()
            val status = try {
                withContext(fetchDispatcher) {
                    when {
                        // Not wanted in the last two passes (scrolled away while
                        // queued, e.g. during a fling): skip the engine call.
                        tile.wantedPass < pass - 1 -> SUPERSEDED
                        channel == ENVELOPE ->
                            engine.envelopeColumnsInto(trackId, level, tile.firstColumn, Zoom.TILE_COLUMNS, out)
                        else ->
                            engine.waveColumnsInto(trackId, channel, level, tile.firstColumn, Zoom.TILE_COLUMNS, out)
                    }
                }
            } catch (e: CancellationException) {
                tile.loading = false
                recycle(out)
                throw e
            } catch (_: Throwable) {
                DisplayStatus.NOT_READY
            }
            tile.loading = false
            applyResult(channel, tile, status, out, requestedFor)
        }
    }

    private fun fetchSpectrogram(trackId: Long, channel: Int, level: Int, tile: WaveTile, requestedFor: Long) {
        tile.loading = true
        requestCount++
        scope.launch {
            var superseded = false
            val image = try {
                withContext(fetchDispatcher) {
                    if (tile.wantedPass < pass - 1) {
                        superseded = true
                        null
                    } else {
                        engine.spectrogramColumns(trackId, channel, level, tile.firstColumn, Zoom.TILE_COLUMNS, SPECTRO_ROWS)
                            ?.let { spectrogramImage(it, Zoom.TILE_COLUMNS, SPECTRO_ROWS) }
                    }
                }
            } catch (e: CancellationException) {
                tile.loading = false
                throw e
            } catch (_: Throwable) {
                null
            }
            tile.loading = false
            if (superseded) return@launch
            if (image != null) {
                if (tile.image == null) spectroCount++
                tile.image = image
            }
            // No status from spectrogramColumns: a null result (unsupported,
            // sample mode, engine busy) is not retried until the data changes.
            tile.fetchedFor = requestedFor
            tile.failed = false
            evictSpectrograms()
            revision++
        }
    }

    private var spectroCount = 0

    /** Keeps at most [MAX_SPECTRO_TILES] spectrogram images (≈ 128 KiB each). */
    private fun evictSpectrograms() {
        if (spectroCount <= MAX_SPECTRO_TILES) return
        var threshold = pass - 6
        while (spectroCount > MAX_SPECTRO_TILES * 3 / 4 && threshold <= pass) {
            val th = threshold
            tracks.forEachValue { entry ->
                for (ch in SPECTRO until SPECTRO + 2) {
                    entry.channels[ch]?.forEachValue { tiles ->
                        tiles.removeIf { _, t ->
                            if (!t.loading && t.wantedPass < th) {
                                if (t.image != null) spectroCount--
                                t.image = null
                                tileCount--
                                true
                            } else false
                        }
                    }
                }
            }
            threshold += 2
        }
    }

    /** Takes ownership of [buffer] (stores or recycles it). */
    private fun applyResult(channel: Int, tile: WaveTile, status: Long, buffer: FloatArray, requestedFor: Long) {
        when {
            status == SUPERSEDED -> {
                recycle(buffer)
                return
            }
            status >= 0L || status == DisplayStatus.PARTIAL_EMPTY -> {
                val old = tile.data
                tile.data = buffer
                if (old != null) recycle(old)
                tile.partial = status < 0L || DisplayStatus.isPartial(status)
                tile.version = if (status >= 0L) DisplayStatus.version(status) else requestedFor
                tile.fetchedFor = requestedFor
                tile.trivial = channel != ENVELOPE || isTrivialEnvelope(buffer)
                tile.sampleMode = false
                tile.failed = false
            }
            status == DisplayStatus.SAMPLE_MODE -> {
                recycle(buffer)
                tile.sampleMode = true
                tile.partial = false
                tile.failed = false
                tile.fetchedFor = requestedFor
            }
            status == DisplayStatus.NOT_READY -> {
                recycle(buffer)
                tile.failed = true
                retryPending = true
            }
            else -> {                                  // NO_TRACK, UNSUPPORTED: nothing to draw
                recycle(buffer)
                val old = tile.data
                tile.data = null
                if (old != null) recycle(old)
                tile.failed = false
                tile.partial = false
                tile.trivial = true
                tile.fetchedFor = requestedFor
            }
        }
        evictIfNeeded()
        revision++
    }

    // ------------------------------------------------------------------
    // Sample mode
    // ------------------------------------------------------------------

    /** Sample runs covering [t0, t1] for the channel, or null; requests them
     *  (with a margin) when missing or stale. */
    fun samples(trackId: Long, channel: Int, t0: Double, t1: Double, request: Boolean): SampleWindow? {
        val entry = tracks[trackId] ?: return null
        val w = entry.samples[channel]
        val fresh = w != null && w.version == entry.waveVersion && w.t0 <= t0 && w.t1 >= t1
        if (!fresh && request && !entry.samplesLoading[channel]) {
            val margin = (t1 - t0) * 0.5
            val r0 = maxOf(0.0, t0 - margin)
            val r1 = t1 + margin
            val version = entry.waveVersion
            entry.samplesLoading[channel] = true
            requestCount++
            scope.launch {
                val runs = try {
                    withContext(fetchDispatcher) { engine.waveSamples(trackId, channel, r0, r1) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                entry.samplesLoading[channel] = false
                if (runs != null) entry.samples[channel] = SampleWindow(r0, r1, version, runs)
                revision++
            }
        }
        return w
    }

    /** The last fetched sample window of a channel (no request). */
    fun sampleWindow(trackId: Long, channel: Int): SampleWindow? = tracks[trackId]?.samples?.getOrNull(channel)

    // ------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------

    /** Drops tiles of levels other than [keepLevel] that were not wanted recently. */
    fun trim(keepLevel: Int) {
        if (tileCount <= maxTiles / 2) return
        val threshold = pass - 2
        tracks.forEachValue { entry ->
            for (levels in entry.channels) {
                if (levels == null) continue
                levels.removeIf { level, tiles ->
                    if (level == keepLevel) false
                    else {
                        tiles.removeIf { _, t -> dropIf(t, threshold) }
                        tiles.isEmpty()
                    }
                }
            }
        }
    }

    private fun evictIfNeeded() {
        if (tileCount <= maxTiles) return
        // Drop tiles not wanted in the last passes, oldest first (two sweeps).
        var threshold = pass - 8
        while (tileCount > maxTiles * 3 / 4 && threshold <= pass) {
            val th = threshold
            tracks.forEachValue { entry ->
                for (levels in entry.channels) {
                    levels?.forEachValue { tiles -> tiles.removeIf { _, t -> dropIf(t, th) } }
                }
            }
            threshold += 4
        }
    }

    private fun dropIf(t: WaveTile, threshold: Long): Boolean {
        if (t.loading || t.wantedPass >= threshold) return false
        t.data?.let { recycle(it) }
        t.data = null
        if (t.image != null) spectroCount--
        t.image = null
        tileCount--
        return true
    }

    private fun releaseEntry(entry: TrackEntry) {
        for (levels in entry.channels) {
            levels?.forEachValue { tiles ->
                tiles.forEachValue { t ->
                    if (!t.loading) t.data?.let { recycle(it) }
                    t.data = null
                    if (t.image != null) spectroCount--
                    t.image = null
                    tileCount--
                }
            }
        }
    }

    /** Number of tiles held (tests). */
    val size: Int get() = tileCount

    private fun obtainBuffer(): FloatArray = synchronized(pool) {
        if (pool.isEmpty()) FloatArray(3 * Zoom.TILE_COLUMNS) else pool.removeAt(pool.size - 1)
    }

    private fun recycle(a: FloatArray) {
        synchronized(pool) { if (pool.size < 64) pool.add(a) }
    }

    companion object {
        const val ENVELOPE = 2
        /** Spectrogram pseudo-channels: SPECTRO + channel. */
        const val SPECTRO = 3
        const val SPECTRO_ROWS = 128
        const val MAX_SPECTRO_TILES = 64
        private const val SUPERSEDED = Long.MIN_VALUE

        @OptIn(ExperimentalCoroutinesApi::class)
        val FetchDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

        /** Colour image of spectrogram bytes (column-major, r = 0 lowest frequency). */
        fun spectrogramImage(bytes: ByteArray, columns: Int, rows: Int): ImageBitmap {
            val pixels = IntArray(columns * rows)
            for (c in 0 until columns) {
                val base = c * rows
                if (base + rows > bytes.size) break
                for (r in 0 until rows) {
                    pixels[(rows - 1 - r) * columns + c] = SpectrogramColors.argb(bytes[base + r].toInt() and 0xFF)
                }
            }
            val bmp = android.graphics.Bitmap.createBitmap(pixels, columns, rows, android.graphics.Bitmap.Config.ARGB_8888)
            return bmp.asImageBitmap()
        }

        fun isTrivialEnvelope(a: FloatArray): Boolean {
            for (i in 0 until Zoom.TILE_COLUMNS) {
                val v = a[i]
                if (!v.isNaN() && v != 1f) return false
            }
            return true
        }
    }
}
