/*
 * Files — ZIP archives: compressing items into one and extracting one into a new folder, by the write
 * rules of spec §4.3, and the file operations that run them (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.time.TimeSource

/** How much of a file is compressed at a time. */
private const val PACK_CHUNK = 64 shl 10

/** What `ZipFile` says of a name the path check of Android 14 and later refuses (`ZipPathValidator`). */
private const val INVALID_PATH = "Invalid zip entry path"

/**
 * What an extraction made: the [folder] the archive is in, and how many of its entries were given a
 * ` (2)` name because theirs was taken ([renamedDuplicates]: an entry of the same name before it, or one
 * that differs only in case, on a volume that ignores case).
 */
data class ExtractResult(val folder: Path, val renamedDuplicates: Int)

/**
 * ZIP archives on [fs] (spec §6.2): [compress] writes items into a new one, [extract] puts the entries of
 * one into a new folder. Files are written through [write]: the archive under a temporary name, renamed
 * once whole, and each extracted file the same way, in a temporary folder that [write]'s journal records
 * and that gets its name once everything is in it. [labels] number the names that are taken. Archives
 * are read with `java.util.zip.ZipFile`, which reads the file itself, not through [fs]. Nothing here
 * switches threads: the caller runs it where blocking calls belong.
 */
class ZipOps(private val fs: FsBackend, private val write: WriteProtocol, private val labels: NameLabels) {

    /** Writes the extracted files, into the temporary folder: the journal records that, which holds them. */
    private val inner = WriteProtocol(fs, NoJournal)

    /**
     * The character set the entry names of [zip] are in ([chooseZipCharset]). Fails with [FsException]:
     * [FsError.UnsupportedEncoding] when no set it tries decodes them, but [FsError.UnsafeArchive] for an
     * archive the path check of Android 14 and later refuses, [FsError.Encrypted] for one with an
     * encrypted entry and [FsError.Corrupt] for one that cannot be read whatever its names are in.
     */
    fun detectCharset(zip: Path): Charset = asZipFailure {
        try {
            chooseZipCharset { charset -> ZipFile(zip.toFile(), charset).use(::namesOf) }
        } catch (e: FsException) {
            if (e.error != FsError.UnsupportedEncoding) throw e
            throw FsException(undecodable(zip, e.cause), e.cause)
        }
    }

    /**
     * Compresses [sources] into a new archive in [targetDir] and returns it. A source in a folder that is
     * among them too is left out (the folder holds it), and so is any repeat. The archive is named after
     * the first: a file's name without its extension, a folder's whole name, then `.zip`, numbered if
     * that is taken ([UniqueStyle.KEEP_BOTH]). In it, each source is at its top under its own name (two
     * of one name, say from search results, are numbered the same way) with everything under a folder,
     * in walk order ([walk], the app's internal names left out): its folders as entries of their own (so
     * an empty one is kept), each item with its modified time, every name in UTF-8 with the EFS flag.
     * Links and special files are left out; anything that cannot be read fails the whole.
     *
     * The walk is over before anything is written, so neither the archive nor its temporary file is in
     * it. The archive is written as [WriteProtocol.write] writes a file, under [opId]'s temporary name, and
     * flushed to the device. [onProgress] gets the totals first (the items and the bytes of their files),
     * then at most one report each 100 ms as the files are read, then a final one. Fails with
     * [FsException]: nothing at all to compress is [FsError.Unsupported].
     */
    suspend fun compress(
        sources: List<Path>,
        targetDir: Path,
        opId: String,
        onProgress: (OpProgress) -> Unit,
    ): Path = asZipFailure {
        require(sources.isNotEmpty()) { "nothing to compress" }
        val tops = outermost(sources).map { Listing.entry(fs, it, NO_MIME) }
        val items = packed(tops)
        if (items.isEmpty()) throw FsException(FsError.Unsupported)
        val progress = Progress(TimeSource.Monotonic, onProgress, items.size, items.sumOf { it.entry.size })
        progress.report(force = true)
        val first = tops.first()
        val stem = if (first.kind == EntryKind.DIR) first.name else splitName(first.name).first
        val zip = claimUniqueName(targetDir, stem, ".zip", UniqueStyle.KEEP_BOTH, labels, 1) { candidate ->
            if (fs.exists(candidate)) throw FileAlreadyExistsException(candidate.toString())
            Packer(items, progress).use { packer ->
                write.write(candidate, opId, sync = true, replaceExisting = false, mtime = null, source = packer) {}
            }
        }.first
        progress.report(force = true)
        zip
    }

    /**
     * Extracts [zip] into a new folder in [targetDir], named after it without its extension and numbered
     * if that is taken ([UniqueStyle.KEEP_BOTH]): never into a folder that is there. The names are read
     * with [charset], or the one [detectCharset] finds when it is null, and checked before anything is
     * written ([ZipNames.sanitize]): one unsafe name refuses the whole archive. So does too little room
     * for the sizes the archive declares, when the volume can tell (64 MiB are left free).
     *
     * Everything goes first into the hidden temporary folder `.<name>.<opId>.xpart` there, which the
     * journal records before it is made: each entry as [WriteProtocol.write] writes a file, with the
     * entry's modified time, created only under a name that is free. One whose name is taken there (an
     * entry of that name before it, or of a name that differs only in case) gets a ` (2)` name, and is
     * counted in [ExtractResult.renamedDuplicates]; so does a folder whose name a file has. Folders the
     * archive names get their times once all files are in. A link stored in the archive becomes a file
     * holding its target's path; nothing is followed. Once all is in, the temporary folder is renamed in
     * one step to the new folder's name. The files are not flushed to the device one by one: as for a
     * copy to internal storage (spec §4.3), the temporary folder is what a dying process leaves.
     *
     * [onProgress] gets the totals first (the entries and their declared sizes), then at most one report
     * each 100 ms, then a final one. An entry that holds more bytes than it declares is stopped there; one
     * that holds fewer, or whose CRC-32 is not the declared one, or whose data does not inflate is
     * [FsError.Corrupt], as is an archive that cannot be read; an encrypted entry is
     * [FsError.Encrypted]. Fails with [FsException], also when the names are in no character set that
     * decodes them ([FsError.UnsupportedEncoding]) or unsafe ([FsError.UnsafeArchive]); a full volume is
     * [FsError.NoSpace]. If anything fails or the extraction is cancelled, the temporary folder is deleted
     * with all it holds, and only what cannot be deleted stays, recorded in the journal.
     */
    suspend fun extract(
        zip: Path,
        targetDir: Path,
        charset: Charset?,
        opId: String,
        onProgress: (OpProgress) -> Unit,
    ): ExtractResult = asZipFailure {
        val names = charset ?: detectCharset(zip)
        open(zip, names).use { archive ->
            Extraction(archive, targetDir, splitName(zip.fileName.toString()).first, opId).run(onProgress)
        }
    }

    /** [zip] opened with its names in [charset]; fails with [FsException], as [detectCharset] does. */
    private fun open(zip: Path, charset: Charset): ZipFile = try {
        ZipFile(zip.toFile(), charset)
    } catch (e: ZipException) {
        // Names the set does not decode are a ZipException too ("bad entry name").
        throw FsException(undecodable(zip, e), e)
    }

    /**
     * Why [zip] could not be opened with a character set, the last try failing with [failure]: what that
     * says if it is not damage ([errorOf]), else [FsError.UnsupportedEncoding] if the archive opens with
     * names that always decode (ISO-8859-1 has a character for every byte), else what keeps it closed.
     */
    private fun undecodable(zip: Path, failure: Throwable?): FsError {
        val error = (failure as? ZipException)?.let(::errorOf)
        if (error != null && error != FsError.Corrupt) return error
        return try {
            ZipFile(zip.toFile(), Charsets.ISO_8859_1).use(::namesOf)
            FsError.UnsupportedEncoding
        } catch (e: ZipException) {
            errorOf(e)
        } catch (e: IllegalArgumentException) {
            // An entry flagged as UTF-8 that is not: no character set reads it.
            FsError.Corrupt
        }
    }

    /** [sources], each once, without those in a folder among them, in order. */
    private fun outermost(sources: List<Path>): List<Path> {
        val distinct = LinkedHashSet(sources.map { it.toAbsolutePath().normalize() })
        return distinct.filter { path -> generateSequence(path.parent) { it.parent }.none { it in distinct } }
    }

    /** What goes into the archive of [tops], in order, under its name there (see [compress]). */
    private suspend fun packed(tops: List<FileEntry>): List<Packed> {
        val items = ArrayList<Packed>()
        val taken = HashSet<String>()
        for (top in tops) {
            if (!isPackable(top)) continue
            val name = topName(top, taken)
            items += Packed(top, name)
            if (top.kind != EntryKind.DIR) continue
            walk(fs, top.path, onSkipped = { _, error -> throw FsException(error) }) { item ->
                if (isPackable(item.entry)) items += Packed(item.entry, name + "/" + item.relative.joinToString("/"))
                true
            }
        }
        return items
    }

    /** The name [top] has at the top of the archive: its own, numbered if one in [taken] differs only in case. */
    private fun topName(top: FileEntry, taken: MutableSet<String>): String {
        val (stem, ext) = if (top.kind == EntryKind.DIR) top.name to "" else splitName(top.name)
        return (1..MAX_UNIQUE_NUMBER).asSequence()
            .map { candidateName(stem, ext, UniqueStyle.KEEP_BOTH, it, labels) }
            .firstOrNull { taken.add(NameComparator.key(it)) }
            ?: throw FsException(FsError.AlreadyExists)
    }

    private fun isPackable(entry: FileEntry) = entry.kind == EntryKind.FILE || entry.kind == EntryKind.DIR

    /**
     * The archive of [items], made as it is read: each read compresses a little more of them, so that
     * [WriteProtocol] writes it as it copies a file, with no other thread and at most a chunk of it in
     * memory. [progress] follows the items as they are read.
     */
    private inner class Packer(private val items: List<Packed>, private val progress: Progress) : InputStream() {
        private val out = Spill()
        private val zip = ZipOutputStream(out, Charsets.UTF_8)
        private val chunk = ByteArray(PACK_CHUNK)
        private var next = 0

        /** The file being compressed: what it is read from, its size as walked, and what of it was read. */
        private var input: InputStream? = null
        private var size = 0L
        private var read = 0L
        private var startBytes = 0L

        /** Where the bytes of [out] not yet read start. */
        private var position = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == out.size()) {
                if (finished) return -1
                out.reset()
                position = 0
                step()
            }
            val count = minOf(len, out.size() - position)
            out.copyTo(position, b, off, count)
            position += count
            return count
        }

        /** Compresses the next chunk of the current file, else starts the next item, else ends the archive. */
        private fun step() {
            val file = input
            if (file != null) {
                val count = file.read(chunk)
                if (count < 0) return endFile(file)
                zip.write(chunk, 0, count)
                read += count
                // The size walked, not what a growing file turned out to have.
                progress.doneBytes = startBytes + minOf(read, size)
                progress.report()
                return
            }
            if (next == items.size) {
                zip.finish()
                finished = true
                return
            }
            val item = items[next++]
            progress.currentName = item.entry.name
            val isDir = item.entry.kind == EntryKind.DIR
            val entry = ZipEntry(if (isDir) item.name + "/" else item.name)
            entry.setLastModifiedTime(FileTime.fromMillis(item.entry.modified))
            if (isDir) {
                zip.putNextEntry(entry)
                zip.closeEntry()
                done()
            } else {
                input = fs.openRead(item.entry.path)
                size = item.entry.size
                read = 0
                startBytes = progress.doneBytes
                zip.putNextEntry(entry)
            }
        }

        private fun endFile(file: InputStream) {
            file.close()
            input = null
            zip.closeEntry()
            progress.doneBytes = startBytes + size
            done()
        }

        private fun done() {
            progress.doneItems++
            progress.report()
        }

        override fun close() {
            input?.close()
            try {
                zip.close()
            } catch (e: IOException) {
                // Only memory is written to: what was made is being thrown away.
            }
        }
    }

    /** One run of [extract], of [archive] into [targetDir], for the archive named [stem], by [opId]. */
    private inner class Extraction(
        private val archive: ZipFile,
        private val targetDir: Path,
        private val stem: String,
        private val opId: String,
    ) {
        private val temp = targetDir.resolve(tempNameFor(stem, opId))

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
            val declared = listed.indices.filter { isFile(names[it]) }.sumOf { listed[it].size }
            checkRoom(declared)
            val progress = Progress(TimeSource.Monotonic, onProgress, names.count { it.isNotEmpty() }, declared)
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
                    inner.write(candidate, opId, false, false, timeOf(entry), checked, entry.size) { bytes ->
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
}

/** A file or folder [compress] puts into an archive, under [name] there (without the `/` of a folder). */
private class Packed(val entry: FileEntry, val name: String)

/** A [ByteArrayOutputStream] whose bytes are copied out a part at a time. */
private class Spill : ByteArrayOutputStream() {
    fun copyTo(from: Int, target: ByteArray, offset: Int, count: Int) =
        System.arraycopy(buf, from, target, offset, count)
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

/** The names of [file]'s entries; [IllegalArgumentException] for one its character set does not decode. */
private fun namesOf(file: ZipFile): List<String> = file.entries().toList().map { it.name }

/** The modified time of [entry] in epoch milliseconds, if it has one. */
private fun timeOf(entry: ZipEntry): Long? = entry.lastModifiedTime?.toMillis()

/**
 * What a [ZipException] of `ZipFile` stands for: an unsafe path (the check of Android 14 and later), an
 * encrypted entry (it refuses those, general purpose flag bit 0, by name), or else damage.
 */
private fun errorOf(e: ZipException): FsError {
    val message = e.message.orEmpty()
    return when {
        INVALID_PATH in message -> FsError.UnsafeArchive
        message.contains("encrypted", ignoreCase = true) -> FsError.Encrypted
        else -> FsError.Corrupt
    }
}

/**
 * [block], with a [ZipException] it throws as an [FsException] of its [errorOf], and any other IOException
 * as one of its [toFsError].
 */
private inline fun <T> asZipFailure(block: () -> T): T = try {
    block()
} catch (e: FsException) {
    throw e
} catch (e: ZipException) {
    throw FsException(errorOf(e), e)
} catch (e: IOException) {
    throw FsException(e.toFsError(), e)
}

/**
 * [OpKind.COMPRESS] of [spec], as [FileOps.run] describes it: its sources into a new archive in its
 * target folder ([ZipOps.compress]).
 */
internal suspend fun ZipOps.compressRun(
    spec: OperationSpec,
    onProgress: (OpProgress) -> Unit,
    onCommitted: (Int) -> Unit,
): OpResult {
    val target = Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
    val tracked = Tracked(onProgress)
    return try {
        val zip = compress(spec.sources.map { Paths.get(it) }, target, spec.id, tracked::report)
        onCommitted(spec.sources.size)
        OpResult.Done(listOf(zip), emptyList())
    } catch (e: FsException) {
        tracked.failed(e.error)
    }
}

/**
 * [OpKind.EXTRACT] of [spec], as [FileOps.run] describes it: each of its sources, an archive, into a
 * new folder in its target folder ([ZipOps.extract]), in turn.
 */
internal suspend fun ZipOps.extractRun(
    spec: OperationSpec,
    onProgress: (OpProgress) -> Unit,
    onCommitted: (Int) -> Unit,
): OpResult {
    val target = Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
    val charset = try {
        spec.options.zipCharset?.let { Charset.forName(it) }
    } catch (e: IllegalArgumentException) {
        // A name this runtime does not know (UnsupportedCharsetException) or that is none.
        return OpResult.Failed(FsError.UnsupportedEncoding, 0, 0)
    }
    val created = ArrayList<Path>()
    var renamed = 0
    for ((index, source) in spec.sources.withIndex()) {
        val tracked = Tracked(onProgress)
        try {
            val result = extract(Paths.get(source), target, charset, spec.id, tracked::report)
            created.add(result.folder)
            renamed += result.renamedDuplicates
        } catch (e: FsException) {
            return tracked.failed(e.error)
        }
        onCommitted(index + 1)
    }
    return OpResult.Done(created, emptyList(), renamedDuplicates = renamed)
}

/** [onProgress] of a run that writes or extracts one archive, keeping how many items it said it has. */
private class Tracked(private val onProgress: (OpProgress) -> Unit) {
    private var totalItems = 0

    fun report(progress: OpProgress) {
        totalItems = progress.totalItems
        onProgress(progress)
    }

    /**
     * The run's end at [error]: nothing of the archive is kept (one is written or extracted whole), out
     * of the items it counted; 0 of 0 when it was refused before it counted them.
     */
    fun failed(error: FsError) = OpResult.Failed(error, 0, totalItems)
}
