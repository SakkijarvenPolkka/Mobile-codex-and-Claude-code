/*
 * Files — the saves other apps have in flight through the system file picker (pick spec §3.5): what a call on such a
 * file waits for, so that it finds what was saved, and what may not be moved away meanwhile.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The saves in flight, for the whole app: each one another app makes of a file through the picker, from when it opens
 * the file until what it wrote is put in place or dropped. A save is [State.OPEN] while that app may still write,
 * then [State.CLOSING] once it closed the file, until it [ends][Save.end]. [settle] waits for them, at most
 * [closeWaitMs] for one still open to be closed and [commitWaitMs] in all. One instance serves every thread.
 */
class OpenSaves(
    private val closeWaitMs: Long = SETTLE_CLOSE_MS,
    private val commitWaitMs: Long = SETTLE_COMMIT_MS,
) {
    /** Where a save is: written into, or closed and being put in place or dropped. */
    enum class State { OPEN, CLOSING }

    /** Guards [saves] and each save's [Save.state]; [changed] is signalled whenever either changes. */
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val saves = ArrayList<Save>()

    /** One save of [target], written into [temp]. */
    inner class Save internal constructor(val target: Path, val temp: Path) {

        /** Where this save is now. */
        var state: State = State.OPEN
            get() = lock.withLock { field }
            private set

        /** The other app closed the file: what it wrote is about to be put in place, or dropped. */
        fun closing() = lock.withLock {
            state = State.CLOSING
            changed.signalAll()
        }

        /** What was written is in place, or dropped: nothing waits for this save any more. Once is enough. */
        fun end() = lock.withLock {
            if (saves.remove(this)) changed.signalAll()
        }
    }

    /** A save of [target] begins, written into [temp]: open until the other app closes it. */
    fun begin(target: Path, temp: Path): Save = lock.withLock { Save(target, temp).also { saves += it } }

    /**
     * Waits, on the calling thread, while a save of [path] is in flight (with [under], a save of [path] or of
     * anything in it), so that what the call then reads or changes is what was saved: until each closed one has
     * ended, and each one still open has closed, or [closeWaitMs] have gone by (its app may write for long yet, and
     * the call goes on without it), but [commitWaitMs] at most in all. An interrupted thread stops waiting.
     */
    fun settle(path: Path, under: Boolean = false) {
        val start = System.nanoTime()
        val closeBy = start + TimeUnit.MILLISECONDS.toNanos(closeWaitMs)
        val endBy = start + TimeUnit.MILLISECONDS.toNanos(commitWaitMs)
        lock.withLock {
            while (true) {
                val waitedFor = saves.filter { if (under) it.target.startsWith(path) else it.target == path }
                val until = when {
                    waitedFor.any { it.state == State.CLOSING } -> endBy
                    waitedFor.isNotEmpty() -> minOf(closeBy, endBy)
                    else -> return
                }
                val left = until - System.nanoTime()
                if (left <= 0) return
                try {
                    changed.awaitNanos(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * Whether a save in flight uses [path]: [path] is the file it saves or its temporary file, or a folder holding
     * them. Such a path is not renamed, moved or deleted until the save ends.
     */
    fun uses(path: Path): Boolean = lock.withLock {
        saves.any { it.target.startsWith(path) || it.temp.startsWith(path) }
    }

    companion object {
        /** How long a call waits for a save still open to be closed, in milliseconds. */
        const val SETTLE_CLOSE_MS = 2_000L

        /** How long a call waits in all for the saves of what it acts on, in milliseconds. */
        const val SETTLE_COMMIT_MS = 10_000L
    }
}
