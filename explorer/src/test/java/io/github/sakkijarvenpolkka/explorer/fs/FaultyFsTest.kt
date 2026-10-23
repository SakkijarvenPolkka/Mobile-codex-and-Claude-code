// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.SequenceInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

class FaultyFsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir: Path get() = folder.root.toPath()

    private fun names(at: Path): List<String> = NioFsBackend.list(at).map { it.fileName.toString() }.sorted()

    @Test
    fun caseInsensitiveExistsAndCreateNew() {
        val upper = Files.write(dir.resolve("A.txt"), "a".toByteArray())
        Files.createDirectory(dir.resolve("Docs"))
        Files.write(dir.resolve("Docs/Note.txt"), "n".toByteArray())
        val fs = FaultyFs().apply { caseInsensitive = true }

        assertTrue(fs.exists(dir.resolve("a.txt")))
        assertThrows(FileAlreadyExistsException::class.java) { fs.createNew(dir.resolve("a.txt")).close() }
        // Every part of the path is matched, not only the last.
        assertTrue(fs.exists(dir.resolve("docs/note.TXT")))
        assertThrows(FileAlreadyExistsException::class.java) { fs.createDirectory(dir.resolve("DOCS")) }

        // A rename that only changes the case does nothing, as on vfat.
        fs.atomicMove(upper, dir.resolve("a.txt"))
        assertEquals(listOf("A.txt", "Docs"), names(dir))

        // A new name is created as asked, in the folder that exists.
        fs.createNew(dir.resolve("docs/new.txt")).close()
        assertEquals(listOf("Note.txt", "new.txt"), names(dir.resolve("Docs")))
        assertFalse(fs.exists(dir.resolve("b.txt")))
    }

    @Test
    fun channelCannotBypassTheStepCount() {
        val target = dir.resolve("a.txt")
        val fs = FaultyFs()

        fs.createNew(target).use { channel ->
            // Flushing through the channel itself is a step like FsBackend.force.
            channel.force(true)
            assertEquals(FaultyFs.Call(FaultyFs.Op.FORCE, target), fs.calls.last())
            assertEquals(2, fs.steps)
            // Cutting a file is a change no step stands for: refused.
            assertThrows(UnsupportedOperationException::class.java) { channel.truncate(0) }
        }

        // A crash at that step happens before the flush.
        val crashing = FaultyFs().apply { crashAtStep = 2 }
        crashing.createNew(dir.resolve("b.txt")).use { channel ->
            assertThrows(SimulatedCrash::class.java) { channel.force(false) }
        }
    }

    @Test
    fun wrapReadAndFakeSize() {
        val a = Files.write(dir.resolve("a.txt"), "abc".toByteArray())
        val b = Files.write(dir.resolve("b.txt"), "def".toByteArray())
        val fs = FaultyFs().apply {
            wrapRead("a.txt") { input -> SequenceInputStream(input, "!".byteInputStream()) }
            fakeSize(b, 5_000_000_000L)
        }

        assertEquals("abc!", fs.openRead(a).use { String(it.readBytes()) })
        assertEquals("def", fs.openRead(b).use { String(it.readBytes()) })
        assertEquals(5_000_000_000L, fs.attributes(b).size())
        assertEquals(3L, fs.attributes(a).size())
        assertTrue(fs.attributes(b).isRegularFile)
    }

    @Test
    fun refusedChangeFailsWithItsReason() {
        val a = Files.write(dir.resolve("a.txt"), "a".toByteArray())
        val fs = FaultyFs().apply { refuse(FaultyFs.Op.CREATE_DIRECTORY, "/ro", "Read-only file system") }

        val refused = assertThrows(FileSystemException::class.java) { fs.createDirectory(dir.resolve("ro")) }
        assertEquals("Read-only file system", refused.reason)
        assertEquals(FsError.ReadOnly, refused.toFsError())
        // Still a step, and nothing was made.
        assertEquals(listOf(FaultyFs.Call(FaultyFs.Op.CREATE_DIRECTORY, dir.resolve("ro"))), fs.calls)
        assertFalse(Files.exists(dir.resolve("ro")))

        // Other changes, and the same change elsewhere, run.
        fs.createDirectory(dir.resolve("rw"))
        fs.atomicMove(a, dir.resolve("ro.txt"))
        assertEquals(listOf("ro.txt", "rw"), names(dir))
    }

    @Test
    fun exdevBetweenRoots() {
        val internal = folder.newFolder("internal").toPath()
        val sd = folder.newFolder("sd").toPath()
        val a = Files.write(internal.resolve("a.txt"), "a".toByteArray())
        val b = Files.write(sd.resolve("b.txt"), "b".toByteArray())
        val fs = FaultyFs().apply { exdevBetween(internal, sd) }

        assertThrows(AtomicMoveNotSupportedException::class.java) { fs.atomicMove(a, sd.resolve("a.txt")) }
        assertThrows(AtomicMoveNotSupportedException::class.java) { fs.atomicMove(b, internal.resolve("b.txt")) }
        assertEquals(listOf("a.txt"), names(internal))
        assertEquals(listOf("b.txt"), names(sd))

        // Within one root a move is still a rename.
        fs.atomicMove(a, internal.resolve("c.txt"))
        assertEquals(listOf("c.txt"), names(internal))
    }
}
