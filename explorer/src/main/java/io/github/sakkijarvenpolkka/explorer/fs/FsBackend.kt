/*
 * Files — the file system calls everything else goes through, and their NIO
 * implementation.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Every query and change of the file system goes through this. Failures are
 * [java.io.IOException]s (see [toFsError]); nothing recurses.
 */
interface FsBackend {
    /** The children of [dir], in no particular order. */
    fun list(dir: Path): List<Path>

    /** Attributes of [path] itself (NOFOLLOW_LINKS). */
    fun attributes(path: Path): BasicFileAttributes

    /** Whether [path] itself exists (NOFOLLOW_LINKS). */
    fun exists(path: Path): Boolean

    /** Whether [a] and [b] are the same file, following links. */
    fun isSameFile(a: Path, b: Path): Boolean

    /** [path] with every link resolved. */
    fun realPath(path: Path): Path

    /** Opens [path] for reading; a link is followed. */
    fun openRead(path: Path): InputStream

    /** A new file opened for writing; fails if anything, a link included, has the name (CREATE_NEW). */
    fun createNew(path: Path): FileChannel

    /** Flushes [channel]'s content and metadata to the device. */
    fun force(channel: FileChannel)

    /** Renames [from] to [to] in one step, never by copying (ATOMIC_MOVE only); Linux replaces an existing [to]. */
    fun atomicMove(from: Path, to: Path)

    /** Deletes a file, a link (not its target) or an empty folder. */
    fun delete(path: Path)

    fun createDirectory(path: Path)

    fun setModified(path: Path, millis: Long)

    /** Bytes this app may still write on [path]'s volume. */
    fun availableBytes(path: Path): Long
}

/** [FsBackend] on `java.nio.file`. */
object NioFsBackend : FsBackend {

    override fun list(dir: Path): List<Path> = Files.newDirectoryStream(dir).use { stream ->
        try {
            stream.toList()
        } catch (e: DirectoryIteratorException) {
            throw e.cause ?: e
        }
    }

    override fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    override fun exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    override fun isSameFile(a: Path, b: Path): Boolean = Files.isSameFile(a, b)

    override fun realPath(path: Path): Path = path.toRealPath()

    override fun openRead(path: Path): InputStream = Files.newInputStream(path)

    override fun createNew(path: Path): FileChannel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

    override fun force(channel: FileChannel) = channel.force(true)

    override fun atomicMove(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(path: Path) = Files.delete(path)

    override fun createDirectory(path: Path) {
        Files.createDirectory(path)
    }

    override fun setModified(path: Path, millis: Long) {
        Files.setLastModifiedTime(path, FileTime.fromMillis(millis))
    }

    /** statvfs, as `StatFs` reads it; a `FileStore` would need a mount table lookup. */
    override fun availableBytes(path: Path): Long {
        if (!Files.exists(path)) throw NoSuchFileException(path.toString())
        return path.toFile().usableSpace
    }
}
