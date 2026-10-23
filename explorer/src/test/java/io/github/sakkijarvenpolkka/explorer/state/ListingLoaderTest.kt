// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ListingLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val tab = TabId(1)
    private val options = ListOptions()
    private val noMime: (String) -> String? = { null }

    private fun dir(name: String, vararg files: String): Path {
        val dir = Files.createDirectory(folder.root.toPath().resolve(name))
        for (file in files) Files.createFile(dir.resolve(file))
        return dir
    }

    private fun rows(dir: Path): List<Row> = Listing.list(NioFsBackend, dir, options, noMime).map { Row(it) }

    @Test
    fun loadEmitsLoadingThenLoaded() = runTest(dispatcher) {
        val dir = dir("a", "one.txt", "two.txt")
        val loader = ListingLoader(backgroundScope, NioFsBackend, dispatcher, noMime)
        val seen = mutableListOf<ListingState>()
        backgroundScope.launch { loader.state(tab).toList(seen) }
        runCurrent()

        loader.load(tab, dir, options)
        runCurrent()
        assertEquals(listOf(ListingState.Loading, ListingState.Loaded(rows(dir))), seen)
        assertEquals(2, rows(dir).size)
    }

    @Test
    fun newLoadCancelsPrevious() = runTest(dispatcher) {
        val slow = dir("slow", "late.txt")
        val fast = dir("fast", "early.txt")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> {
                if (dir == slow) {
                    started.countDown()
                    release.await()
                }
                return NioFsBackend.list(dir)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val loader = ListingLoader(backgroundScope, fs, pool.asCoroutineDispatcher(), noMime)
        val seen = mutableListOf<ListingState>()
        backgroundScope.launch { loader.state(tab).toList(seen) }
        try {
            loader.load(tab, slow, options)
            runCurrent()
            assertTrue(started.await(5, TimeUnit.SECONDS))

            loader.load(tab, fast, options)
            assertEquals(ListingState.Loaded(rows(fast)), loader.state(tab).first { it is ListingState.Loaded })
        } finally {
            release.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        // The first read has returned by now; what it read is never shown.
        runCurrent()
        assertEquals(ListingState.Loaded(rows(fast)), loader.state(tab).value)
        assertTrue(seen.none { it is ListingState.Loaded && it.rows == rows(slow) })
    }

    @Test
    fun loaderRunsOffMainAndKeepsRowsOnRefresh() = runTest(dispatcher) {
        val dir = dir("a", "one.txt")
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> {
                threads += Thread.currentThread()
                return NioFsBackend.list(dir)
            }
        }
        val ioThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val pool = Executors.newSingleThreadExecutor { Thread(it, "listing-test-io").also(ioThreads::add) }
        try {
            val loader = ListingLoader(backgroundScope, fs, pool.asCoroutineDispatcher(), noMime)
            loader.load(tab, dir, options)
            val first = loader.state(tab).first { it is ListingState.Loaded }
            assertEquals(ListingState.Loaded(rows(dir)), first)

            Files.createFile(dir.resolve("two.txt"))
            val seen = mutableListOf<ListingState>()
            backgroundScope.launch { loader.state(tab).toList(seen) }
            runCurrent()
            loader.load(tab, dir, options, refresh = true)
            runCurrent()
            val second = loader.state(tab).first { it is ListingState.Loaded && !it.refreshing }
            runCurrent()

            assertEquals(ListingState.Loaded(rows(dir)), second)
            assertEquals(2, rows(dir).size)
            val kept = ListingState.Loaded((first as ListingState.Loaded).rows, refreshing = true)
            assertEquals(listOf(first, kept, second), seen)
            assertEquals(1, ioThreads.size)
            assertEquals(listOf(ioThreads[0], ioThreads[0]), threads)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun errorStateForMissingDir() = runTest(dispatcher) {
        val loader = ListingLoader(backgroundScope, NioFsBackend, dispatcher, noMime)
        loader.load(tab, folder.root.toPath().resolve("gone"), options)
        runCurrent()
        assertEquals(ListingState.Error(FsError.NotFound), loader.state(tab).value)
    }

    @Test
    fun staleFlagClearedByLoad() = runTest(dispatcher) {
        val dir = dir("a", "one.txt")
        val other = TabId(2)
        val loader = ListingLoader(backgroundScope, NioFsBackend, dispatcher, noMime)
        assertFalse(loader.isStale(tab))
        loader.load(tab, dir, options)
        runCurrent()

        loader.markStale(tab)
        loader.markStale(other)
        assertTrue(loader.isStale(tab))
        loader.load(tab, dir, options, refresh = true)
        assertFalse(loader.isStale(tab))
        assertTrue(loader.isStale(other))
        runCurrent()
        assertFalse(loader.isStale(tab))

        loader.setSource(other, MutableStateFlow(ListingState.Loaded(emptyList())))
        assertFalse(loader.isStale(other))
        loader.markStale(other)
        loader.drop(other)
        assertFalse(loader.isStale(other))
    }

    @Test
    fun sourceReplacesLoadUntilDropped() = runTest(dispatcher) {
        val dir = dir("a", "one.txt")
        val loader = ListingLoader(backgroundScope, NioFsBackend, dispatcher, noMime)
        loader.load(tab, dir, options)
        val source = MutableStateFlow<ListingState>(ListingState.Loaded(emptyList()))
        // Set before the load has run: the load is cancelled and the source's states are shown.
        loader.setSource(tab, source)
        assertEquals(ListingState.Loading, loader.state(tab).value)
        runCurrent()
        assertEquals(ListingState.Loaded(emptyList()), loader.state(tab).value)
        source.value = ListingState.Loaded(rows(dir))
        runCurrent()
        assertEquals(ListingState.Loaded(rows(dir)), loader.state(tab).value)

        val dropped = loader.state(tab)
        loader.drop(tab)
        source.value = ListingState.Error(FsError.IoError)
        runCurrent()
        assertEquals(ListingState.Loaded(rows(dir)), dropped.value)
        assertEquals(ListingState.Loading, loader.state(tab).value)

        // A load replaces a source the same way.
        loader.setSource(tab, source)
        runCurrent()
        loader.load(tab, dir, options)
        runCurrent()
        source.value = ListingState.Loaded(emptyList())
        runCurrent()
        assertEquals(ListingState.Loaded(rows(dir)), loader.state(tab).value)
    }
}
