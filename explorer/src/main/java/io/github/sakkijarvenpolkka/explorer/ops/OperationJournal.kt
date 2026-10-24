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

/** The suffix of the file of an operation's spec, `<opId>.json`. */
private const val SPEC_SUFFIX = ".json"

/** The suffix of the file of a [Record], `<key>.progress.json`. */
private const val RECORD_SUFFIX = ".progress.json"

private val JOURNAL_JSON = Json { ignoreUnknownKeys = true }

private val LOG = Logger.getLogger("io.github.sakkijarvenpolkka.explorer.ops")

/** An operation a crash interrupted: its [spec], and how many of its top-level sources were done ([committed]). */
data class InterruptedOp(val spec: OperationSpec, val committed: Int)

/**
 * What changes while an operation runs, kept apart from its spec so that a change writes a few bytes,
 * however many sources it has: how many of its top-level sources were [committed], the temporary files
 * it may have on disk ([temps]), and the files between the two steps of a rename ([renames], from the
 * temporary name to the final one). A rename, and the temporary files of no operation begun here, have
 * a record of their own, as do those of an operation once it is over.
 */
@Serializable
private data class Record(
    val committed: Int = 0,
    val temps: List<String> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
) {
    /** Whether it says nothing (none committed is what no record says too). */
    val empty: Boolean get() = committed == 0 && temps.isEmpty() && renames.isEmpty()
}

/**
 * The journal of the file operations (spec §5), two files in [dir] for each: `<opId>.json`, its spec,
 * written once when it begins ([begin]), and `<opId>.progress.json`, how many of its top-level sources
 * are done and the temporary files it may have on disk (it is the [TempJournal] they are recorded in),
 * written when a source is done ([committed]) and before each temporary file is made. Only the second is
 * written again, and it stays a few bytes long however many sources there are. Both are deleted when
 * the operation ends ([end]); an operation is interrupted while its spec is there. A rename between its
 * two steps has a record of its own ([addRename]). Each file is written to a temporary file next to it,
 * which is then renamed over it: a crash leaves the old version or the new.
 *
 * At the app's start and before each operation, [sweep] deletes the temporary files recorded (and the
 * temporary folders of extractions, with all they hold) and gives the files a rename left halfway their
 * new names; it returns the operations that never ended, whose journals stay until they are run again or
 * dismissed ([discard]).
 *
 * [fs] reaches the journal and the temporary files alike. A journal file that cannot be written or read
 * is logged and stops nothing: the operation goes on without it. One instance serves the whole app,
 * from any thread.
 */
class OperationJournal(private val dir: File, private val fs: FsBackend) : TempJournal {
    private val folder: Path = dir.toPath()
    private val lock = Any()

    /** The operations begun here and not ended, by id. */
    private val running = HashSet<String>()

    /** The records this instance keeps up to date, by key: an operation's id, or a key of its own. */
    private val live = HashMap<String, Record>()

    /** The key of the record each temporary file or rename recorded here is in. */
    private val holders = HashMap<String, String>()

    /** The key of the record for the temporary files of no operation begun here. */
    private val looseKey = "temps-" + UUID.randomUUID()

    private var folderMade = false

    /** Starts the journal of [spec]; whether its spec is written (else it runs without one). */
    fun begin(spec: OperationSpec): Boolean {
        synchronized(lock) {
            running += spec.id
            return write(specFileOf(spec.id), JOURNAL_JSON.encodeToString(OperationSpec.serializer(), spec))
        }
    }

    /** Notes that the first [count] top-level sources of operation [opId] are done. */
    fun committed(opId: String, count: Int) {
        synchronized(lock) {
            if (opId !in running) return
            put(opId, recordOf(opId).copy(committed = count))
        }
    }

    /**
     * Ends the journal of operation [opId]: its spec is deleted, then the temporary files it still records,
     * and then their record. Those that cannot be deleted stay recorded, without the operation, for a
     * later [sweep].
     */
    fun end(opId: String) {
        synchronized(lock) {
            if (!running.remove(opId)) return
            // From here on, it is not an interrupted operation.
            deleteQuietly(specFileOf(opId))
            val record = live.remove(opId) ?: return
            record.temps.forEach(holders::remove)
            settle(opId, record, tidied(record.copy(committed = 0)))
        }
    }

    /**
     * Deletes the temporary files of the operations not running here and gives a file a rename left at
     * its temporary name the name it was going to (unless that is taken); returns the operations that did
     * not end (their specs), by id. Their journals stay, without what is done now. A temporary file
     * whose folder is not there is kept recorded (its volume may be away), and so is a rename whose new
     * name is taken. A journal that cannot be read now is left as it is, for a later sweep; one that is
     * not a journal (it does not decode, or names a path no file system takes) is deleted.
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
            val names = files.map { it.fileName.toString() }.toSet()
            val keys = sortedSetOf<String>()
            for (name in names) {
                when {
                    // A version a crash cut short; the one it was to replace is whole.
                    name.endsWith(HiddenNames.PART_SUFFIX) -> deleteQuietly(folder.resolve(name))
                    name.endsWith(RECORD_SUFFIX) -> keys += name.removeSuffix(RECORD_SUFFIX)
                    name.endsWith(SPEC_SUFFIX) -> keys += name.removeSuffix(SPEC_SUFFIX)
                }
            }
            val interrupted = ArrayList<InterruptedOp>()
            for (key in keys) {
                if (key in running || key in live) continue
                val (spec, record) = try {
                    val spec = if (key + SPEC_SUFFIX in names) readSpec(key) else null
                    spec to ((if (key + RECORD_SUFFIX in names) readRecord(key) else null) ?: Record())
                } catch (e: IOException) {
                    // Not a file that is not there: how far it got may be in it.
                    LOG.log(Level.WARNING, "Cannot read the journal of $key", e)
                    continue
                }
                // Without its operation, how far that got means nothing.
                val left = tidied(if (spec == null) record.copy(committed = 0) else record)
                spec?.let { interrupted += InterruptedOp(it, left.committed) }
                settle(key, record, left)
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
            if (opId in running || opId in live) return
            deleteQuietly(specFileOf(opId))
            val record = try {
                readRecord(opId)
            } catch (e: IOException) {
                // Left for a sweep, which takes it for the record of no operation.
                LOG.log(Level.WARNING, "Cannot read the journal of $opId", e)
                return
            } ?: return
            settle(opId, record, tidied(record.copy(committed = 0)))
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
            val record = recordOf(key)
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
     * Forgets [temp], which is gone. A record with nothing left in it is deleted at once; one that still
     * says something is written with its next change (until then, a sweep would find the file gone).
     */
    override fun remove(temp: Path) {
        synchronized(lock) {
            val path = temp.toString()
            val key = holders.remove(path) ?: return
            val record = live[key] ?: return
            val left = record.copy(temps = record.temps - path, renames = record.renames - path)
            if (left.empty) {
                live.remove(key)
                deleteQuietly(recordFileOf(key))
            } else {
                live[key] = left
            }
        }
    }

    private fun keyFor(temp: Path): String {
        val name = temp.fileName?.toString().orEmpty()
        return running.firstOrNull { name.endsWith(".$it${HiddenNames.PART_SUFFIX}") } ?: looseKey
    }

    private fun recordOf(key: String): Record = live[key] ?: Record()

    /** [record] with its temporary files deleted and its renames finished: what is left of it. */
    private fun tidied(record: Record): Record = record.copy(
        temps = record.temps.filterNot { deleted(Paths.get(it)) },
        renames = record.renames.filterNot { (temp, final) -> renamed(Paths.get(temp), Paths.get(final)) },
    )

    /**
     * Deletes [temp]; whether it is gone. One not found is not, while its folder is not there either. A
     * temporary folder (an extraction's: named `….xpart`, as every temporary name is) goes with all it
     * holds; anything else is deleted only if it is a file, a link or an empty folder.
     */
    private fun deleted(temp: Path): Boolean = try {
        if (temp.fileName?.toString()?.endsWith(HiddenNames.PART_SUFFIX) == true) deleteContentOf(temp)
        fs.delete(temp)
        true
    } catch (e: NoSuchFileException) {
        exists(temp.parent)
    } catch (e: IOException) {
        false
    }

    /**
     * Deletes everything in [folder], if it is a folder (links are deleted, never followed), one item at a
     * time: the files of each folder as it is listed, then the folders, each after those in it. Throws
     * what keeps an item from being read or deleted, unless it is gone already.
     */
    private fun deleteContentOf(folder: Path) {
        if (!fs.attributes(folder).isDirectory) return
        // In the order they are listed, so that each comes after the folder it is in.
        val folders = arrayListOf(folder)
        var next = 0
        while (next < folders.size) {
            for (child in fs.list(folders[next++])) {
                try {
                    if (fs.attributes(child).isDirectory) folders.add(child) else fs.delete(child)
                } catch (e: NoSuchFileException) {
                    // Gone already.
                }
            }
        }
        for (empty in folders.drop(1).asReversed()) {
            try {
                fs.delete(empty)
            } catch (e: NoSuchFileException) {
                // Gone already.
            }
        }
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

    /** Writes what is [left] of the record [key], which was [before]; deletes its file once it is empty. */
    private fun settle(key: String, before: Record, left: Record) {
        when {
            left.empty -> deleteQuietly(recordFileOf(key))
            left != before -> write(recordFileOf(key), JOURNAL_JSON.encodeToString(Record.serializer(), left))
        }
    }

    private fun put(key: String, record: Record) {
        live[key] = record
        write(recordFileOf(key), JOURNAL_JSON.encodeToString(Record.serializer(), record))
    }

    private fun specFileOf(opId: String): Path = folder.resolve(opId + SPEC_SUFFIX)

    private fun recordFileOf(key: String): Path = folder.resolve(key + RECORD_SUFFIX)

    private fun readSpec(opId: String): OperationSpec? = read(specFileOf(opId)) { text ->
        JOURNAL_JSON.decodeFromString(OperationSpec.serializer(), text).also { spec ->
            (spec.sources + listOfNotNull(spec.target)).forEach { checkPath(it) }
        }
    }

    private fun readRecord(key: String): Record? = read(recordFileOf(key)) { text ->
        JOURNAL_JSON.decodeFromString(Record.serializer(), text).also { record ->
            (record.temps + record.renames.keys + record.renames.values).forEach { checkPath(it) }
        }
    }

    /** Throws [java.nio.file.InvalidPathException], an [IllegalArgumentException], if [path] is not one. */
    private fun checkPath(path: String) {
        Paths.get(path)
    }

    /**
     * What [decode] makes of [file]; null if there is none, or if it is not a journal file: [decode]
     * throws [IllegalArgumentException] (a [kotlinx.serialization.SerializationException] or a path no
     * file system takes), and it is deleted. Throws [IOException] if it cannot be read.
     */
    private fun <T> read(file: Path, decode: (String) -> T): T? = try {
        decode(fs.openRead(file).use { it.readBytes() }.decodeToString())
    } catch (e: NoSuchFileException) {
        null
    } catch (e: IllegalArgumentException) {
        LOG.log(Level.WARNING, "Deleting $file, which is not a journal", e)
        deleteQuietly(file)
        null
    }

    /** Writes [text] to [file] ([writeAtomically]); whether it did (a failure is logged). */
    private fun write(file: Path, text: String): Boolean = try {
        writeAtomically(file, text.toByteArray())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.log(Level.WARNING, "Cannot write $file", e)
        false
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
