/*
 * Files — another app's save over a file, made through a temporary file next to it that takes its place in one
 * step once all is written (pick spec §3.5).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpenSaves
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
 * on, which a failure of its own may have cut short. Each save is in [saves] from when it begins until it is put in
 * place or dropped: calls on its file wait for it, and nothing moves its file or the folders holding it away.
 */
class SafeSave(private val fs: FsBackend, private val journal: TempJournal, private val saves: OpenSaves) {

    /**
     * Begins a save of [target], there or not yet: its temporary file, `.<name>.<id>.xpart` next to it
     * ([tempNameFor]), is recorded in the journal and the save in [saves] ([OpenSaves.begin]), then the file is created
     * empty (CREATE_NEW: nothing else of that name is used). Throws what the file system says if it cannot be, with
     * nothing of it left.
     */
    fun begin(target: Path, id: String): Pending {
        val name = requireNotNull(target.fileName) { "$target has no name" }.toString()
        val temp = target.resolveSibling(tempNameFor(name, id))
        journal.add(temp)
        val pending = Pending(target, temp, saves.begin(target, temp))
        try {
            fs.createNew(temp).close()
        } catch (e: Exception) {
            try {
                pending.abort()
            } catch (cleanup: IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
        return pending
    }

    /**
     * A save of [target], whose new content is written into [temp], as [save] in [saves]: [closing] once the other app
     * has closed it, then [commit] it, or [abort] it.
     */
    inner class Pending internal constructor(val target: Path, val temp: Path, private val save: OpenSaves.Save) {

        /**
         * The other app closed the file: from now on, a call on it waits until it is put in place or dropped
         * ([OpenSaves.settle]), not only for it to be closed.
         */
        fun closing() = save.closing()

        /**
         * Puts what was written in [target]'s place: [temp] is flushed to the device ([FsBackend.sync]), renamed over
         * [target] in one step, the rename flushed with its folder ([FsBackend.syncFolder]: after a power cut, the file
         * holds what it held or what was saved), then forgotten, and the save ends ([OpenSaves.Save.end]). [target] is
         * as it was until that step. If the flush or the rename fails, the failure is thrown, and [temp] is left for
         * [abort].
         */
        fun commit() {
            fs.sync(temp)
            fs.atomicMove(temp, target)
            fs.syncFolder(requireNotNull(target.parent) { "$target has no folder" })
            journal.remove(temp)
            save.end()
        }

        /**
         * Drops what was written: [temp] is deleted, then forgotten; [target] is not touched. One not found is gone
         * already, unless its folder is not there either (renamed or moved away, or on a drive pulled out): then it
         * may be on disk still, and stays recorded, as the journal's sweep keeps such a one. A [temp] that cannot be
         * deleted stays recorded, for the sweep, and the failure is thrown. The save ends however that goes.
         */
        fun abort() {
            try {
                try {
                    fs.delete(temp)
                } catch (e: NoSuchFileException) {
                    if (!isThere(temp.parent)) return
                }
                journal.remove(temp)
            } finally {
                save.end()
            }
        }
    }

    /** Whether [path] is there, as the journal's sweep tells it ([FsBackend.exists]); not when that cannot be told. */
    private fun isThere(path: Path?): Boolean = try {
        path != null && fs.exists(path)
    } catch (e: IOException) {
        false
    }
}
