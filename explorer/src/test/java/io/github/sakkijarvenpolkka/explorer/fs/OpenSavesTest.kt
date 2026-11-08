// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The saves other apps have open through the system file picker, and what waits for them (pick spec §3.5). */
class OpenSavesTest {

    private val folder: Path = Paths.get("/storage/emulated/0/Docs")
    private val target: Path = folder.resolve("a.txt")
    private val temp: Path = folder.resolve(".a.txt.id.xpart")

    /** The threads the calls wait on, one each: as binder threads, which the provider's calls come on. */
    private val threads = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        threads.shutdownNow()
    }

    /** Runs [settle] on a thread of its own; done once it returns, with how long it took (ms). */
    private fun settling(settle: () -> Unit): CompletableFuture<Long> = CompletableFuture.supplyAsync({
        val start = System.nanoTime()
        settle()
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }, threads)

    @Test
    fun settleReturnsAtOnceWithNothingPending() {
        val saves = OpenSaves(closeWaitMs = 10_000, commitWaitMs = 10_000)

        assertTrue(settling { saves.settle(target) }.get(5, TimeUnit.SECONDS) < 2_000)
        // Saves of other files, or in a folder asked of itself (not of what is in it), are not waited for.
        saves.begin(folder.resolve("b.txt"), folder.resolve(".b.txt.id.xpart")).closing()
        assertTrue(settling { saves.settle(target) }.get(5, TimeUnit.SECONDS) < 2_000)
        saves.begin(target, temp).closing()
        assertTrue(settling { saves.settle(folder) }.get(5, TimeUnit.SECONDS) < 2_000)
    }

    @Test
    fun settleWaitsForAClosingSaveUntilItEnds() {
        val saves = OpenSaves(closeWaitMs = 10_000, commitWaitMs = 10_000)
        val save = saves.begin(target, temp)
        save.closing()

        val itself = settling { saves.settle(target) }
        // A folder holding it, asked with what is in it, waits for it too.
        val holding = settling { saves.settle(folder.parent, under = true) }
        Thread.sleep(300)
        assertFalse(itself.isDone)
        assertFalse(holding.isDone)

        save.end()
        itself.get(5, TimeUnit.SECONDS)
        holding.get(5, TimeUnit.SECONDS)
        // Once is enough: it is gone.
        save.end()
        assertFalse(saves.uses(target))
    }

    @Test
    fun settleWaitsForAnOpenSaveToCloseThenForItToEnd() {
        val saves = OpenSaves(closeWaitMs = 5_000, commitWaitMs = 10_000)
        val save = saves.begin(target, temp)

        val waited = settling { saves.settle(target) }
        Thread.sleep(300)
        // The other app closes the file within the bound: what follows is waited for, past that bound too.
        save.closing()
        Thread.sleep(300)
        assertFalse(waited.isDone)
        save.end()
        waited.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun settleGivesUpOnAnOpenSaveAfterItsBound() {
        val saves = OpenSaves(closeWaitMs = 200, commitWaitMs = 10_000)
        saves.begin(target, temp)

        // The other app is still writing: the call goes on once the bound for a close is past.
        val waited = settling { saves.settle(target) }.get(5, TimeUnit.SECONDS)
        assertTrue("waited $waited ms", waited in 150L..3_000L)
    }

    @Test
    fun settleGivesUpOnAClosingSaveAfterItsBound() {
        val saves = OpenSaves(closeWaitMs = 100, commitWaitMs = 400)
        saves.begin(target, temp).closing()

        val waited = settling { saves.settle(target, under = true) }.get(5, TimeUnit.SECONDS)
        assertTrue("waited $waited ms", waited in 350L..3_000L)
    }

    @Test
    fun usesNamesTheSaveAndTheFoldersHoldingIt() {
        val saves = OpenSaves()
        val save = saves.begin(target, temp)

        for (path in listOf(target, temp, folder, folder.parent, Paths.get("/"))) assertTrue("$path", saves.uses(path))
        for (path in listOf(folder.resolve("b.txt"), folder.resolve("a.txt.bak"), Paths.get("/storage/1A2B-3C4D"))) {
            assertFalse("$path", saves.uses(path))
        }
        // Closed, until it is put in place or dropped.
        save.closing()
        assertTrue(saves.uses(folder))
        save.end()
        assertFalse(saves.uses(folder))
    }
}
