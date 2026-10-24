/*
 * Files — compressing items into a new ZIP archive, by the write rules of spec §4.3 (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.TimeSource

/** How much of a file is compressed at a time. */
private const val PACK_CHUNK = 64 shl 10

/**
 * [ZipOps.compress] on [fs], the archive written through [write], its names numbered with [labels], its
 * progress paced by [time].
 */
internal class Compression(
    private val fs: FsBackend,
    private val write: WriteProtocol,
    private val labels: NameLabels,
    private val time: TimeSource,
) {

    /** Compresses [sources] into a new archive in [targetDir] and returns it, as [ZipOps.compress] says. */
    suspend fun run(sources: List<Path>, targetDir: Path, opId: String, onProgress: (OpProgress) -> Unit): Path {
        val tops = outermost(sources).map { Listing.entry(fs, it, NO_MIME) }
        val items = packed(tops)
        if (items.isEmpty()) throw FsException(FsError.Unsupported)
        val progress = Progress(time, onProgress, items.size, items.sumOf { it.entry.size })
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
        return zip
    }

    /** [sources], each once, without those in a folder among them, in order. */
    private fun outermost(sources: List<Path>): List<Path> {
        val distinct = LinkedHashSet(sources.map { it.toAbsolutePath().normalize() })
        return distinct.filter { path -> generateSequence(path.parent) { it.parent }.none { it in distinct } }
    }

    /** What goes into the archive of [tops], in order, under its name there (see [ZipOps.compress]). */
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
}

/** A file or folder [ZipOps.compress] puts into an archive, under [name] there (without the `/` of a folder). */
private class Packed(val entry: FileEntry, val name: String)

/** A [ByteArrayOutputStream] whose bytes are copied out a part at a time. */
private class Spill : ByteArrayOutputStream() {
    fun copyTo(from: Int, target: ByteArray, offset: Int, count: Int) =
        System.arraycopy(buf, from, target, offset, count)
}
