/*
 * Files — another app's save over a file, made through a temporary file next to it that takes its place in one
 * step once all is written (pick spec §3.5).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.TempJournal
import io.github.sakkijarvenpolkka.explorer.fs.tempNameFor
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Saves over files for other apps on [fs], so that whenever a save is dropped or the process dies, a file holds what it
 * held before or all that was saved, never part of it. What an app writes goes into a temporary file next to the
 * file, recorded in [journal] while it is on disk, which then takes the file's place in one step
 * ([FsBackend.atomicMove]). One that a crash leaves behind is deleted with the others the journal records. Whether a
 * save is put in place or dropped is the caller's to decide: what the other app wrote is whatever it closed the file
 * on, which a failure of its own may have cut short.
 */
class SafeSave(private val fs: FsBackend, private val journal: TempJournal) {

    /**
     * Begins a save of [target], there or not yet: its temporary file, `.<name>.<id>.xpart` next to it
     * ([tempNameFor]), is recorded in the journal, then created empty (CREATE_NEW: nothing else of that name is
     * used). Throws what the file system says if it cannot be, with nothing of it left.
     */
    fun begin(target: Path, id: String): Pending {
        val name = requireNotNull(target.fileName) { "$target has no name" }.toString()
        val pending = Pending(target, target.resolveSibling(tempNameFor(name, id)))
        journal.add(pending.temp)
        try {
            fs.createNew(pending.temp).close()
        } catch (e: IOException) {
            try {
                pending.abort()
            } catch (cleanup: IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
        return pending
    }

    /** A save of [target], whose new content is written into [temp]: [commit] it, or [abort] it. */
    inner class Pending internal constructor(val target: Path, val temp: Path) {

        /**
         * Puts what was written in [target]'s place: [temp] is flushed to the device ([FsBackend.sync]), renamed over
         * [target] in one step, the rename flushed with its folder ([FsBackend.syncFolder]: after a power cut, the file
         * holds what it held or what was saved), then forgotten. [target] is as it was until that step. If the flush
         * or the rename fails, the failure is thrown, and [temp] is left for [abort].
         */
        fun commit() {
            fs.sync(temp)
            fs.atomicMove(temp, target)
            fs.syncFolder(requireNotNull(target.parent) { "$target has no folder" })
            journal.remove(temp)
        }

        /**
         * Drops what was written: [temp] is deleted (one gone already is not missed), then forgotten; [target] is not
         * touched. A [temp] that cannot be deleted stays recorded, for the journal's sweep, and the failure is thrown.
         */
        fun abort() {
            try {
                fs.delete(temp)
            } catch (e: NoSuchFileException) {
                // Gone already.
            }
            journal.remove(temp)
        }
    }
}
