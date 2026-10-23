/*
 * Files — the journal of the file operations (spec §5): what each one is, how far it got and the
 * temporary files it may have on disk, so that what a crash leaves behind is cleaned up and the
 * operation can be run again.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ops

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.TempJournal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** The suffix of a journal file, `<key>.json`. */
private const val JOURNAL_SUFFIX = ".json"

private val JOURNAL_JSON = Json { ignoreUnknownKeys = true }

private val LOG = Logger.getLogger("io.github.sakkijarvenpolkka.explorer.ops")

/** An operation a crash interrupted: its [spec], and how many of its top-level sources were done ([committed]). */
data class InterruptedOp(val spec: OperationSpec, val committed: Int)

/**
 * What a journal file holds: the operation it is for ([spec]; none for a rename, nor for temporary files
 * left once their operation is over), how many of its top-level sources were [committed], the temporary
 * files it may have on disk ([temps]), and the files between the two steps of a rename ([renames], from
 * the temporary name to the final one).
 */
@Serializable
private data class Record(
    val spec: OperationSpec? = null,
    val committed: Int = 0,
    val temps: List<String> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
) {
    /** Whether nothing is left to do for it. */
    val done: Boolean get() = spec == null && temps.isEmpty() && renames.isEmpty()
}

/**
 * The journal of the file operations (spec §5), one file in [dir] for each: `<opId>.json`, with its
 * spec, how many of its top-level sources are done and the temporary files it may have on disk (it is
 * the [TempJournal] they are recorded in). It is written when the operation begins ([begin]), when a
 * source is done ([committed]) and before each temporary file is made, and deleted when it ends ([end]).
 * A rename between its two steps has a file of its own ([addRename]). Each file is written to a
 * temporary file next to it, which is then renamed over it: a crash leaves the old version or the new.
 *
 * At the app's start and before each operation, [sweep] deletes the temporary files recorded and gives
 * the files a rename left halfway their new names; it returns the operations that never ended, whose
 * journals stay until they are run again or dismissed ([discard]).
 *
 * [fs] reaches the journal and the temporary files alike. A journal file that cannot be written or read
 * is logged and stops nothing: the operation goes on without it. One instance serves the whole app,
 * from any thread.
 */
class OperationJournal(private val dir: File, private val fs: FsBackend) : TempJournal {
    private val folder: Path = dir.toPath()
    private val lock = Any()

    /** The records this instance keeps up to date, by key: an operation's id, or a key of its own. */
    private val live = HashMap<String, Record>()

    /** The key of the record each temporary file or rename recorded here is in. */
    private val holders = HashMap<String, String>()

    /** The key of the record for the temporary files of no operation begun here. */
    private val looseKey = "temps-" + UUID.randomUUID()

    private var folderMade = false

    /** Starts the journal of [spec]. */
    fun begin(spec: OperationSpec) {
        synchronized(lock) { put(spec.id, Record(spec)) }
    }

    /** Notes that the first [count] top-level sources of operation [opId] are done. */
    fun committed(opId: String, count: Int) {
        synchronized(lock) {
            val record = live[opId] ?: return
            put(opId, record.copy(committed = count))
        }
    }

    /**
     * Ends the journal of operation [opId]: the temporary files it still records are deleted, and then its
     * file. Those that cannot be deleted stay recorded, without the operation, for a later [sweep].
     */
    fun end(opId: String) {
        synchronized(lock) {
            val record = live.remove(opId) ?: return
            record.temps.forEach(holders::remove)
            settle(opId, record, tidied(record.copy(spec = null, committed = 0)))
        }
    }

    /**
     * Deletes the temporary files of the operations not running here and gives a file a rename left at
     * its temporary name the name it was going to (unless that is taken); returns the operations that did
     * not end, by id. Their journals stay, without what is done now. A temporary file
     * whose folder is not there is kept recorded (its volume may be away), and so is a rename whose new
     * name is taken.
     */
    fun sweep(): List<InterruptedOp> {
        synchronized(lock) {
            val files = try {
                fs.list(folder)
            } catch (e: NoSuchFileException) {
                return emptyList()
            } catch (e: IOException) {
                LOG.log(Level.WARNING, "Cannot read the operations journal", e)
                return emptyList()
            }
            val interrupted = ArrayList<InterruptedOp>()
            for (file in files.sortedBy { it.fileName.toString() }) {
                val name = file.fileName.toString()
                when {
                    // A version a crash cut short; the one it was to replace is whole.
                    name.endsWith(HiddenNames.PART_SUFFIX) -> deleteQuietly(file)
                    name.endsWith(JOURNAL_SUFFIX) -> {
                        val key = name.removeSuffix(JOURNAL_SUFFIX)
                        if (key in live) continue
                        val record = read(file) ?: continue
                        val left = tidied(record)
                        left.spec?.let { interrupted += InterruptedOp(it, left.committed) }
                        settle(key, record, left)
                    }
                }
            }
            return interrupted
        }
    }

    /**
     * Forgets the interrupted operation [opId] (it is run again, or dismissed): like [end], but for an
     * operation [sweep] found.
     */
    fun discard(opId: String) {
        synchronized(lock) {
            if (opId in live) return
            val record = read(fileOf(opId)) ?: return
            settle(opId, record, tidied(record.copy(spec = null, committed = 0)))
        }
    }

    /**
     * Records [temp] in the journal of the operation begun here whose temporary names it has (they end
     * in `.<opId>.xpart`, [io.github.sakkijarvenpolkka.explorer.fs.tempNameFor]), else in one of its own.
     */
    override fun add(temp: Path) {
        synchronized(lock) {
            val path = temp.toString()
            if (path in holders) return
            val key = keyFor(temp)
            val record = live[key] ?: Record()
            holders[path] = key
            put(key, record.copy(temps = record.temps + path))
        }
    }

    override fun addRename(temp: Path, final: Path) {
        synchronized(lock) {
            val key = "rename-" + UUID.randomUUID()
            holders[temp.toString()] = key
            put(key, Record(renames = mapOf(temp.toString() to final.toString())))
        }
    }

    /**
     * Forgets [temp], which is gone. A journal with nothing left in it is deleted at once; that of an
     * operation is written with its next change (until then, a sweep would find the file gone).
     */
    override fun remove(temp: Path) {
        synchronized(lock) {
            val path = temp.toString()
            val key = holders.remove(path) ?: return
            val record = live[key] ?: return
            val left = record.copy(temps = record.temps - path, renames = record.renames - path)
            if (left.done) {
                live.remove(key)
                deleteQuietly(fileOf(key))
            } else {
                live[key] = left
            }
        }
    }

    private fun keyFor(temp: Path): String {
        val name = temp.fileName?.toString().orEmpty()
        return live.values.firstNotNullOfOrNull { record ->
            record.spec?.id?.takeIf { name.endsWith(".$it${HiddenNames.PART_SUFFIX}") }
        } ?: looseKey
    }

    /** [record] with its temporary files deleted and its renames finished: what is left of it. */
    private fun tidied(record: Record): Record = record.copy(
        temps = record.temps.filterNot { deleted(Paths.get(it)) },
        renames = record.renames.filterNot { (temp, final) -> renamed(Paths.get(temp), Paths.get(final)) },
    )

    /** Deletes [temp]; whether it is gone. One not found is not, while its folder is not there either. */
    private fun deleted(temp: Path): Boolean = try {
        fs.delete(temp)
        true
    } catch (e: NoSuchFileException) {
        exists(temp.parent)
    } catch (e: IOException) {
        false
    }

    /**
     * Gives the file at [temp] its [final] name, if it is there and that name is free; whether nothing is
     * left to do: the rename never began or was finished, or now is.
     */
    private fun renamed(temp: Path, final: Path): Boolean = try {
        when {
            !fs.exists(temp) -> exists(temp.parent)
            fs.exists(final) -> {
                LOG.warning("Cannot finish renaming $temp: $final is taken")
                false
            }
            else -> {
                fs.atomicMove(temp, final)
                true
            }
        }
    } catch (e: IOException) {
        false
    }

    private fun exists(path: Path?): Boolean = try {
        path != null && fs.exists(path)
    } catch (e: IOException) {
        false
    }

    /** Writes what is [left] of the record [key], which was [before]; deletes its file once nothing is. */
    private fun settle(key: String, before: Record, left: Record) {
        when {
            left.done -> deleteQuietly(fileOf(key))
            left != before -> write(key, left)
        }
    }

    private fun put(key: String, record: Record) {
        live[key] = record
        write(key, record)
    }

    private fun fileOf(key: String): Path = folder.resolve(key + JOURNAL_SUFFIX)

    /** The record in [file]; null if there is none, or it cannot be read (a corrupt one is deleted). */
    private fun read(file: Path): Record? = try {
        val text = fs.openRead(file).use { it.readBytes() }.decodeToString()
        JOURNAL_JSON.decodeFromString(Record.serializer(), text)
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IOException) {
        LOG.log(Level.WARNING, "Cannot read $file", e)
        null
    } catch (e: IllegalArgumentException) {
        LOG.log(Level.WARNING, "Deleting $file, which is not a journal", e)
        deleteQuietly(file)
        null
    }

    private fun write(key: String, record: Record) {
        try {
            writeAtomically(fileOf(key), JOURNAL_JSON.encodeToString(Record.serializer(), record).toByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "Cannot write the journal of $key", e)
        }
    }

    /**
     * Writes [bytes] to [file] through a temporary file next to it, renamed over it once whole. Nothing
     * is flushed to the device: as for a copy to internal storage (spec §4.3), the journal only has to
     * outlive the process.
     */
    private fun writeAtomically(file: Path, bytes: ByteArray) {
        if (!folderMade) {
            makeFolder()
            folderMade = true
        }
        val temp = file.resolveSibling(file.fileName.toString() + HiddenNames.PART_SUFFIX)
        val channel = try {
            fs.createNew(temp)
        } catch (e: FileAlreadyExistsException) {
            fs.delete(temp) // Left by a crash in the middle of a write.
            fs.createNew(temp)
        }
        try {
            channel.use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
            }
            fs.atomicMove(temp, file)
        } catch (e: IOException) {
            deleteQuietly(temp)
            throw e
        }
    }

    private fun makeFolder() {
        if (fs.exists(folder)) return
        try {
            fs.createDirectory(folder)
        } catch (e: FileAlreadyExistsException) {
            // Made in the meantime.
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            fs.delete(path)
        } catch (e: NoSuchFileException) {
            // Already gone.
        } catch (e: IOException) {
            LOG.log(Level.WARNING, "Cannot delete $path", e)
        }
    }
}
