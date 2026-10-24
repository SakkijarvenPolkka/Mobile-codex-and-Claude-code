/*
 * Files — extracting a ZIP archive into a new folder, by the write rules of spec §4.3 (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.time.TimeSource

/**
 * One run of [ZipOps.extract] on [fs], of [archive] into [targetDir] on [volume], for the archive named
 * [stem], by [opId]: the temporary folder recorded in [write]'s journal, names numbered with [labels], the
 * progress paced by [time].
 */
internal class Extraction(
    private val fs: FsBackend,
    private val write: WriteProtocol,
    private val labels: NameLabels,
    private val volume: VolumeRoot?,
    private val time: TimeSource,
    private val archive: ZipFile,
    private val targetDir: Path,
    private val stem: String,
    private val opId: String,
) {
    private val temp = targetDir.resolve(tempNameFor(stem, opId))

    /** Whether each file is flushed to the device before it gets its name: on removable media. */
    private val sync = volume?.removable == true

    /** Writes the extracted files, into the temporary folder: the journal records that, which holds them. */
    private val inner = WriteProtocol(fs, NoJournal)

    /** The folders made in [temp], by the archive's path of them (`a/b`), in as many cases as it has. */
    private val folders = HashMap<String, Path>()

    /** The folders the archive names, with the times they get once all files are in. */
    private val folderTimes = ArrayList<Pair<Path, Long>>()
    private var renamed = 0

    suspend fun run(onProgress: (OpProgress) -> Unit): ExtractResult {
        val listed = try {
            archive.entries().toList()
        } catch (e: IllegalArgumentException) {
            // A name the character set does not decode (Android decodes them only here).
            throw FsException(FsError.UnsupportedEncoding, e)
        }
        val names = listed.map { ZipNames.sanitize(it.name) ?: throw FsException(FsError.UnsafeArchive) }
        if (listed.any { it.size < 0 }) throw FsException(FsError.Corrupt)
        checkStorable(names)
        val declared = listed.indices.filter { isFile(names[it]) }.sumOf { listed[it].size }
        checkRoom(declared)
        val progress = Progress(time, onProgress, names.count { it.isNotEmpty() }, declared)
        progress.report(force = true)
        write.journal.add(temp)
        var created = false
        val folder = try {
            fs.createDirectory(temp)
            created = true
            extractAll(names, progress)
            for ((dir, time) in folderTimes) setModified(dir, time)
            rename()
        } catch (e: Exception) {
            // CancellationException included: the cleanup runs, then the cancellation goes on.
            withContext(NonCancellable) { discardAfter(e, created) }
            throw e
        }
        write.journal.remove(temp)
        progress.report(force = true)
        return ExtractResult(folder, renamed)
    }

    /**
     * Fails with [FsError.NameInvalid] if the FAT naming rules of [volume], if it has them, refuse a part
     * of one of [names] (the safe names), as they would a copy of it ([fatProblemOf]).
     */
    private fun checkStorable(names: List<String>) {
        val traits = volume?.traits?.takeIf { it.fatNames } ?: return
        for (name in names) {
            for (part in name.split('/')) {
                if (part.isEmpty()) continue
                fatProblemOf(part, traits)?.let { throw FsException(FsError.NameInvalid(it)) }
            }
        }
    }

    /** Fails with [FsError.NoSpace] if the volume can tell it has no room for [bytes] and 64 MiB more. */
    private fun checkRoom(bytes: Long) {
        val available = try {
            fs.availableBytes(targetDir)
        } catch (e: IOException) {
            0L
        }
        if (available > 0 && bytes > available - SPACE_MARGIN) throw FsException(FsError.NoSpace)
    }

    /** Each entry into [temp], in the archive's order, [names] being their safe names. */
    private suspend fun extractAll(names: List<String>, progress: Progress) {
        val entries = archive.entries()
        var index = 0
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            // Read right after it is listed: ZipFile finds the data of an entry by its name, but of the
            // entry it listed last by its place, so that two entries of one name are each read.
            val entry = entries.nextElement()
            val name = names[index++]
            if (name.isEmpty()) continue
            val path = name.removeSuffix("/")
            progress.currentName = path.substringAfterLast('/')
            val startBytes = progress.doneBytes
            if (isFile(name)) {
                extractFile(entry, path, progress)
                progress.doneBytes = startBytes + entry.size
            } else {
                val dir = folderFor(path)
                timeOf(entry)?.let { folderTimes += dir to it }
            }
            progress.doneItems++
            progress.report()
        }
    }

    /** Writes the file [entry] at [path] in [temp], under a ` (2)` name if that is taken. */
    private suspend fun extractFile(entry: ZipEntry, path: String, progress: Progress) {
        val dir = folderFor(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        val (stem, ext) = splitName(name)
        val startBytes = progress.doneBytes
        val written = claimUniqueName(dir, stem, ext, UniqueStyle.KEEP_BOTH, labels, 1) { candidate ->
            if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
            var read = 0L
            val data = archive.getInputStream(entry) ?: throw FsException(FsError.Corrupt)
            data.use { input ->
                val checked = Checked(input, entry.size, entry.crc)
                inner.write(candidate, opId, sync, false, timeOf(entry), checked, entry.size) { bytes ->
                    read += bytes
                    progress.doneBytes = startBytes + minOf(read, entry.size)
                    progress.report()
                }
            }
        }.first
        if (written.fileName.toString() != name) renamed++
    }

    /** The folder the archive's folder [path] (`a/b`; "" for [temp]) is extracted to, made if need be. */
    private fun folderFor(path: String): Path {
        if (path.isEmpty()) return temp
        folders[path]?.let { return it }
        var folder = temp
        var key = ""
        for (part in path.split('/')) {
            key = if (key.isEmpty()) part else "$key/$part"
            val parent = folder
            folder = folders.getOrPut(key) { folderIn(parent, part) }
        }
        return folder
    }

    /**
     * The folder [name] in [parent]: the one there (in whatever case: a volume that ignores it has one
     * folder for both), else a new one; if a file has the name, a new one numbered ` (2)` on.
     */
    private fun folderIn(parent: Path, name: String): Path {
        val dir = parent.resolve(name)
        if (!fs.exists(dir)) {
            fs.createDirectory(dir)
            return dir
        }
        if (fs.attributes(dir).isDirectory) return dir
        renamed++
        return claimUniqueName(parent, name, "", UniqueStyle.KEEP_BOTH, labels, 2) { fs.createDirectory(it) }.first
    }

    /** Gives [temp] the folder's name, numbered if it is taken: a folder that is there is never merged into. */
    private fun rename(): Path {
        val (folder, _) = claimUniqueName(targetDir, stem, "", UniqueStyle.KEEP_BOTH, labels, 1) { candidate ->
            if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
            try {
                fs.atomicMove(temp, candidate)
            } catch (e: DirectoryNotEmptyException) {
                // Made in the meantime.
                throw FileAlreadyExistsException(candidate.toString())
            }
        }
        return folder
    }

    private fun setModified(dir: Path, time: Long) {
        try {
            fs.setModified(dir, time)
        } catch (e: IOException) {
            // The folder is still extracted; only its time is lost.
        }
    }

    /**
     * After the extraction [failed]: deletes [temp] with all it holds if it was [created], then forgets
     * it, unless something stays (it could not be deleted, or it was there before, left by a crash).
     * Whatever goes wrong in this is added to [failed], which it never replaces.
     */
    private suspend fun discardAfter(failed: Exception, created: Boolean) {
        try {
            if (created) {
                val tree = doomedTree(fs, temp)
                var kept = tree.unreadable.isNotEmpty()
                val fatal = deleteTree(fs, tree, onFailed = { _, _ -> kept = true })
                if (fatal != null || kept) return
            } else if (failed is FileAlreadyExistsException) {
                return
            }
            write.journal.remove(temp)
        } catch (e: CancellationException) {
            // Not rethrown: nothing here can be cancelled (it runs NonCancellable), and the caller
            // rethrows the extraction's own failure, a cancellation included.
            failed.addSuppressed(e)
        } catch (e: Exception) {
            failed.addSuppressed(e)
        }
    }
}

/**
 * [input], the data of an entry, checked as it is read: a byte more than the declared [size], fewer
 * bytes at the end, a CRC-32 there other than [crc], or data that does not inflate are [FsError.Corrupt].
 */
private class Checked(input: InputStream, private val size: Long, private val crc: Long) : FilterInputStream(input) {
    private val sum = CRC32()
    private var count = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = try {
            super.read(b, off, len)
        } catch (e: ZipException) {
            throw FsException(FsError.Corrupt, e)
        } catch (e: EOFException) {
            throw FsException(FsError.Corrupt, e)
        }
        if (read < 0) {
            if (count != size || sum.value != crc) throw FsException(FsError.Corrupt)
            return read
        }
        count += read
        if (count > size) throw FsException(FsError.Corrupt)
        sum.update(b, off, read)
        return read
    }
}

/** Whether the safe entry name [name] ([ZipNames.sanitize]) is a file's: not a folder's, nor empty. */
private fun isFile(name: String) = name.isNotEmpty() && !name.endsWith('/')

/** The modified time of [entry] in epoch milliseconds, if it has one. */
private fun timeOf(entry: ZipEntry): Long? = entry.lastModifiedTime?.toMillis()
