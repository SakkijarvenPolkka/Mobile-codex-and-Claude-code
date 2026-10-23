/*
 * Files — writing a file so that its final name never holds part of one: into a hidden temporary
 * file first, then renamed in one step (spec §4.3).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Where the temporary files being written are recorded, so that those a crash leaves behind can be
 * found and deleted. [add] comes before a temporary file is created, [remove] once it is gone.
 */
interface TempJournal {
    fun add(temp: Path)

    fun remove(temp: Path)
}

/** A [TempJournal] that records nothing. */
object NoJournal : TempJournal {
    override fun add(temp: Path) = Unit

    override fun remove(temp: Path) = Unit
}

/**
 * The temporary name under which [name] is written by operation [opId]: `.<name>.<opId>.xpart`, hidden
 * and internal ([HiddenNames.isInternal]). If that is longer than 255 UTF-8 bytes, [name] is cut, between
 * code points, until it fits. (An [opId] too long to leave room for any of [name] cannot be fixed here.)
 */
fun tempNameFor(name: String, opId: String): String {
    val suffix = ".$opId${HiddenNames.PART_SUFFIX}"
    return "." + name.takeUtf8(MAX_NAME_BYTES - 1 - utf8Length(suffix)) + suffix
}

/**
 * Writes files so that, whenever the process dies, a final name holds either what it held before or
 * the complete new file, never a cut one; all that can be left behind is a temporary file recorded in
 * [journal]. Copies, moves across volumes, replacements, ZIP compression and extraction and restores
 * by copying all write through this.
 */
class WriteProtocol(
    private val fs: FsBackend,
    private val journal: TempJournal,
    private val bufferSize: Int = 1 shl 20,
) {
    init {
        require(bufferSize > 0) { "bufferSize must be positive, was $bufferSize" }
    }

    /**
     * Writes [source], read to its end and not closed, to [target], and returns the bytes written:
     * 1. records the temporary file [tempNameFor] next to [target] in the journal, then creates it
     *    (CREATE_NEW);
     * 2. copies [source] into it, [bufferSize] bytes at a time, checking for cancellation before each
     *    buffer and passing each buffer's size to [onBytes];
     * 3. if [sync], flushes it to the device ([FsBackend.force]), then closes it;
     * 4. checks that it holds exactly the bytes read from [source] (so a source that grew while it was
     *    read is copied as read);
     * 5. sets its modified time to [mtime], if given; a volume that refuses only loses the time;
     * 6. unless [replaceExisting], refuses a [target] that exists with [FileAlreadyExistsException];
     * 7. renames it to [target] in one step ([FsBackend.atomicMove]): a [target] being replaced stays as
     *    it was until this moment;
     * 8. removes it from the journal.
     *
     * If anything fails or the write is cancelled, the temporary file, and nothing else, is deleted and
     * removed from the journal (if it cannot be deleted it stays recorded, for a later sweep), and the
     * failure is rethrown: an IOException as an [FsException] with its [toFsError], except a taken
     * [target], which stays a [FileAlreadyExistsException]. An [Error] is not caught, so it passes
     * without any cleanup.
     */
    suspend fun write(
        target: Path,
        opId: String,
        sync: Boolean,
        replaceExisting: Boolean,
        mtime: Long?,
        source: InputStream,
        onBytes: (Long) -> Unit,
    ): Long {
        val name = requireNotNull(target.fileName) { "$target has no name" }.toString()
        val temp = target.resolveSibling(tempNameFor(name, opId))
        journal.add(temp)
        var created = false
        val written = try {
            val read = fs.createNew(temp).use { channel ->
                created = true
                copy(source, channel, onBytes).also { if (sync) fs.force(channel) }
            }
            if (fs.attributes(temp).size() != read) throw FsException(FsError.IoError)
            if (mtime != null) setModified(temp, mtime)
            if (!replaceExisting && fs.exists(target)) throw FileAlreadyExistsException(target.toString())
            fs.atomicMove(temp, target)
            read
        } catch (e: Exception) {
            // CancellationException included: the cleanup runs, then the cancellation goes on.
            withContext(NonCancellable) { discard(temp, created) }
            // Only the target can be taken once the temporary file exists; before, it was that name.
            throw if (e is FileAlreadyExistsException && created) e else e.asWriteFailure()
        }
        journal.remove(temp)
        return written
    }

    /** Copies [source] to its end into [channel]; returns the bytes read, all of which were written. */
    private suspend fun copy(source: InputStream, channel: FileChannel, onBytes: (Long) -> Unit): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = source.read(buffer)
            if (read < 0) return total
            val bytes = ByteBuffer.wrap(buffer, 0, read)
            while (bytes.hasRemaining()) channel.write(bytes)
            total += read
            onBytes(read.toLong())
        }
    }

    private fun setModified(temp: Path, mtime: Long) {
        try {
            fs.setModified(temp, mtime)
        } catch (e: IOException) {
            // The file is still written; only its time is lost (some volumes refuse some times).
        }
    }

    /** Deletes [temp] if this write [created] it, then forgets it, unless it is still there. */
    private fun discard(temp: Path, created: Boolean) {
        if (created) {
            try {
                fs.delete(temp)
            } catch (e: NoSuchFileException) {
                // Already gone.
            } catch (e: IOException) {
                return // Still on disk: it stays in the journal, whose sweep deletes it.
            }
        }
        journal.remove(temp)
    }

    /** [this] as [write] reports it: an IOException as an [FsException] with its [toFsError]. */
    private fun Exception.asWriteFailure(): Exception =
        if (this is IOException && this !is FsException) FsException(toFsError(), this) else this
}
