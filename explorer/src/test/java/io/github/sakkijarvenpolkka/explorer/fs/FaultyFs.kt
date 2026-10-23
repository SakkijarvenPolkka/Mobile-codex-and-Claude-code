// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * The process dying at a chosen step: thrown by [FaultyFs.crashAtStep] in place of the call. It is an
 * [Error], which production code never catches, so no cleanup runs and the disk stays as a kill would
 * leave it.
 */
class SimulatedCrash : Error()

/**
 * A [FsBackend] around [delegate] that misbehaves on request, for the file system tests:
 * - [caseInsensitive]: every part of a path is matched to an existing name ignoring case (as on FUSE and
 *   vfat), and an [atomicMove] that only changes the case of a name does nothing (as on vfat).
 * - [exdevBetween]: [atomicMove] between two roots fails as across volumes.
 * - [failAt]: a write channel fails once it has written a number of bytes (ENOSPC, EIO, ...).
 * - [deny]: a folder or file this app may not read.
 * - [crashAtStep]: a [SimulatedCrash] at the k-th change.
 *
 * Every change — [createNew], each write to a channel it made, [force], [atomicMove], [delete],
 * [createDirectory], [setModified] — is a step, and is recorded in [calls] before it runs.
 */
class FaultyFs(private val delegate: FsBackend = NioFsBackend) : FsBackend {

    /** The changes [FaultyFs] records. */
    enum class Op { CREATE_NEW, WRITE, FORCE, ATOMIC_MOVE, DELETE, CREATE_DIRECTORY, SET_MODIFIED }

    /** One change as it was asked for: [path] (for [Op.WRITE] and [Op.FORCE], the file's), and [to] for a move. */
    data class Call(val op: Op, val path: Path, val to: Path? = null)

    var caseInsensitive = false

    /** The step (1 is the first change) that throws [SimulatedCrash] instead of running; null: none. */
    var crashAtStep: Int? = null

    private val recorded = ArrayList<Call>()

    /** Every change asked for so far, in order, the one that crashed included. */
    val calls: List<Call> get() = recorded.toList()

    /** How many changes have been asked for so far. */
    val steps: Int get() = recorded.size

    private val exdev = ArrayList<Pair<Path, Path>>()
    private val failures = ArrayList<Failure>()
    private val denied = ArrayList<Path>()

    private class Failure(val pathEndsWith: String, val afterBytes: Long, val reason: String)

    /** Makes [atomicMove] from anywhere under [a] to anywhere under [b], or back, fail with EXDEV. */
    fun exdevBetween(a: Path, b: Path) {
        exdev += a to b
    }

    /**
     * Makes a channel [createNew] opens for a path ending in [pathEndsWith] write [afterBytes] bytes and
     * then throw `FileSystemException(reason)` (a reason such as `No space left on device`).
     */
    fun failAt(pathEndsWith: String, afterBytes: Long, reason: String) {
        failures += Failure(pathEndsWith, afterBytes, reason)
    }

    /**
     * Denies [path] and everything under it, like a folder without read permission: the attributes of
     * [path] itself can still be read, anything else on it or under it throws [AccessDeniedException].
     */
    fun deny(path: Path) {
        denied.add(path)
    }

    override fun list(dir: Path): List<Path> {
        val real = resolve(dir)
        checkAccess(real)
        return delegate.list(real)
    }

    override fun attributes(path: Path): BasicFileAttributes {
        val real = resolve(path)
        checkAccess(real, statOnly = true)
        return delegate.attributes(real)
    }

    override fun exists(path: Path): Boolean {
        val real = resolve(path)
        checkAccess(real, statOnly = true)
        return delegate.exists(real)
    }

    override fun isSameFile(a: Path, b: Path): Boolean {
        val realA = resolve(a)
        val realB = resolve(b)
        checkAccess(realA, statOnly = true)
        checkAccess(realB, statOnly = true)
        return delegate.isSameFile(realA, realB)
    }

    override fun realPath(path: Path): Path {
        val real = resolve(path)
        checkAccess(real, statOnly = true)
        return delegate.realPath(real)
    }

    override fun openRead(path: Path): InputStream {
        val real = resolve(path)
        checkAccess(real)
        return delegate.openRead(real)
    }

    override fun createNew(path: Path): FileChannel {
        step(Op.CREATE_NEW, path)
        val real = resolve(path)
        checkAccess(real)
        val failure = failures.firstOrNull { path.toString().endsWith(it.pathEndsWith) }
        return FaultyChannel(delegate.createNew(real), path, failure)
    }

    override fun force(channel: FileChannel) {
        require(channel is FaultyChannel) { "not a channel of this FaultyFs" }
        step(Op.FORCE, channel.path)
        delegate.force(channel.inner)
    }

    override fun atomicMove(from: Path, to: Path) {
        step(Op.ATOMIC_MOVE, from, to)
        val realFrom = resolve(from)
        val realTo = resolve(to)
        checkAccess(realFrom)
        checkAccess(realTo)
        val crossing = exdev.any { (a, b) ->
            (realFrom.startsWith(a) && realTo.startsWith(b)) || (realFrom.startsWith(b) && realTo.startsWith(a))
        }
        if (crossing) throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "Invalid cross-device link")
        // A rename that only changes the case resolves both names to the existing file, so, as on vfat,
        // it moves the file onto itself: nothing happens.
        delegate.atomicMove(realFrom, realTo)
    }

    override fun delete(path: Path) {
        step(Op.DELETE, path)
        val real = resolve(path)
        checkAccess(real)
        delegate.delete(real)
    }

    override fun createDirectory(path: Path) {
        step(Op.CREATE_DIRECTORY, path)
        val real = resolve(path)
        checkAccess(real)
        delegate.createDirectory(real)
    }

    override fun setModified(path: Path, millis: Long) {
        step(Op.SET_MODIFIED, path)
        val real = resolve(path)
        checkAccess(real)
        delegate.setModified(real, millis)
    }

    override fun availableBytes(path: Path): Long {
        val real = resolve(path)
        checkAccess(real, statOnly = true)
        return delegate.availableBytes(real)
    }

    private fun step(op: Op, path: Path, to: Path? = null) {
        recorded += Call(op, path, to)
        if (recorded.size == crashAtStep) throw SimulatedCrash()
    }

    private fun checkAccess(path: Path, statOnly: Boolean = false) {
        val refused = denied.any { path.startsWith(it) && !(statOnly && path == it) }
        if (refused) throw AccessDeniedException(path.toString())
    }

    /** [path] with each part replaced by an existing name that differs only in case, if [caseInsensitive]. */
    private fun resolve(path: Path): Path {
        if (!caseInsensitive) return path
        var current = path.root ?: return path
        for (part in path) {
            val exact = current.resolve(part.toString())
            current = if (delegate.exists(exact)) exact else sameNameIn(current, part.toString()) ?: exact
        }
        return current
    }

    private fun sameNameIn(dir: Path, name: String): Path? = try {
        delegate.list(dir).firstOrNull { sameNameIgnoringCase(it.fileName.toString(), name) }
    } catch (e: IOException) {
        null
    }

    /** A write channel that counts its writes as steps and fails as [failure] says. */
    private inner class FaultyChannel(val inner: FileChannel, val path: Path, private val failure: Failure?) :
        FileChannel() {

        private var written = 0L

        override fun write(src: ByteBuffer): Int {
            step(Op.WRITE, path)
            if (failure == null) return inner.write(src).also { written += it }
            val room = failure.afterBytes - written
            if (room <= 0) throw FileSystemException(path.toString(), null, failure.reason)
            // Write what still fits, as a filling disk does; the next write fails.
            val limit = src.limit()
            if (src.remaining() > room) src.limit(src.position() + room.toInt())
            try {
                return inner.write(src).also { written += it }
            } finally {
                src.limit(limit)
            }
        }

        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
            var total = 0L
            for (i in offset until offset + length) {
                total += write(srcs[i])
                if (srcs[i].hasRemaining()) break
            }
            return total
        }

        override fun read(dst: ByteBuffer): Int = inner.read(dst)

        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long =
            inner.read(dsts, offset, length)

        override fun read(dst: ByteBuffer, position: Long): Int = inner.read(dst, position)

        override fun position(): Long = inner.position()

        override fun position(newPosition: Long): FileChannel = apply { inner.position(newPosition) }

        override fun size(): Long = inner.size()

        override fun truncate(size: Long): FileChannel = apply { inner.truncate(size) }

        override fun force(metaData: Boolean) = inner.force(metaData)

        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long =
            inner.transferTo(position, count, target)

        // Writes that would bypass the step count and the failure.
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long =
            throw UnsupportedOperationException("FaultyFs writes go through write(ByteBuffer)")

        override fun write(src: ByteBuffer, position: Long): Int =
            throw UnsupportedOperationException("FaultyFs writes go through write(ByteBuffer)")

        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer =
            throw UnsupportedOperationException("FaultyFs writes go through write(ByteBuffer)")

        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = inner.lock(position, size, shared)

        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? =
            inner.tryLock(position, size, shared)

        override fun implCloseChannel() = inner.close()
    }
}
