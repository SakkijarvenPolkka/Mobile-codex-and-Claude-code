// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class WriteProtocolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir: Path get() = folder.root.toPath()

    /** 25 bytes: four buffers of [BUFFER] bytes, the last one short. */
    private val content = "The quick brown fox jumps".toByteArray()

    private val old = "old".toByteArray()

    /** A journal in memory: what is still recorded, and every call in order. */
    private class MemoryJournal : TempJournal {
        val events = ArrayList<String>()
        val recorded = HashSet<Path>()

        override fun add(temp: Path) {
            events += "add ${temp.fileName}"
            recorded.add(temp)
        }

        override fun remove(temp: Path) {
            events += "remove ${temp.fileName}"
            recorded.remove(temp)
        }
    }

    private fun protocol(fs: FsBackend = NioFsBackend, journal: TempJournal = NoJournal) =
        WriteProtocol(fs, journal, bufferSize = BUFFER)

    private fun WriteProtocol.writeBlocking(
        target: Path,
        sync: Boolean = false,
        replaceExisting: Boolean = false,
        mtime: Long? = null,
        bytes: ByteArray = content,
        onBytes: (Long) -> Unit = {},
    ): Long = runBlocking {
        write(target, OP, sync, replaceExisting, mtime, ByteArrayInputStream(bytes), onBytes = onBytes)
    }

    private fun names(at: Path = dir): List<String> = NioFsBackend.list(at).map { it.fileName.toString() }.sorted()

    private fun utf8Bytes(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun writesThenRenames() {
        val journal = MemoryJournal()
        val target = dir.resolve("out.txt")
        var reported = 0L

        val written = protocol(journal = journal).writeBlocking(target, onBytes = { reported += it })

        assertEquals(25L, written)
        assertEquals(25L, reported)
        assertArrayEquals(content, Files.readAllBytes(target))
        assertEquals(listOf("out.txt"), names())
        assertEquals(listOf("add .out.txt.op1.xpart", "remove .out.txt.op1.xpart"), journal.events)

        // An empty source makes an empty file.
        val empty = dir.resolve("empty.txt")
        assertEquals(0L, protocol().writeBlocking(empty, bytes = ByteArray(0)))
        assertEquals(0L, Files.size(empty))
    }

    @Test
    fun tempNameIsCutToFit() {
        assertEquals(".a.txt.op1.xpart", tempNameFor("a.txt", "op1"))
        assertEquals("." + "a".repeat(244) + ".op1.xpart", tempNameFor("a".repeat(255), "op1"))
        // 가 is three bytes: 81 of them fill 243 of the 244 bytes left, and none is cut in half.
        val korean = tempNameFor("가".repeat(100), "op1")
        assertEquals("." + "가".repeat(81) + ".op1.xpart", korean)
        assertTrue(utf8Bytes(korean) <= 255)
    }

    @Test
    fun failureDeletesOnlyTemp() {
        Files.write(dir.resolve("other.txt"), old)
        val fs = FaultyFs().apply { failAt(".xpart", 10, "No space left on device") }
        val journal = MemoryJournal()
        val target = dir.resolve("out.txt")

        val e = assertThrows(FsException::class.java) { protocol(fs, journal).writeBlocking(target) }

        assertEquals(FsError.NoSpace, e.error)
        assertFalse(Files.exists(target))
        assertEquals(listOf("other.txt"), names())
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun leftoverTempIsNotTouched() {
        // A part file of the same name that this write did not create (left by a crash).
        val temp = Files.write(dir.resolve(tempNameFor("out.txt", OP)), old)
        val target = dir.resolve("out.txt")
        // Recorded by the run that left it, whose sweep has not deleted it yet.
        val journal = MemoryJournal().apply { recorded.add(temp) }

        val e = assertThrows(FsException::class.java) { protocol(journal = journal).writeBlocking(target) }

        // Not reported as a taken target, and not deleted: it is not this write's to delete.
        assertEquals(FsError.AlreadyExists, e.error)
        assertArrayEquals(old, Files.readAllBytes(temp))
        assertFalse(Files.exists(target))
        // Nor forgotten: it is still on disk, so the journal keeps it for the sweep.
        assertEquals(listOf("add .out.txt.op1.xpart"), journal.events)
        assertEquals(setOf(temp), journal.recorded)
    }

    @Test
    fun undeletableTempStaysInJournal() {
        val faulty = FaultyFs().apply { failAt(".xpart", 10, "No space left on device") }
        val stuck = object : FsBackend by faulty {
            override fun delete(path: Path) {
                throw FileSystemException(path.toString(), null, "Input/output error")
            }
        }
        val journal = MemoryJournal()
        val target = dir.resolve("out.txt")
        val temp = dir.resolve(tempNameFor("out.txt", OP))

        val e = assertThrows(FsException::class.java) { protocol(stuck, journal).writeBlocking(target) }

        // The write's own failure is reported, and the part it could not delete is left for the sweep.
        assertEquals(FsError.NoSpace, e.error)
        assertEquals(listOf(temp.fileName.toString()), names())
        assertEquals(setOf(temp), journal.recorded)
    }

    @Test
    fun cleanupFailureDoesNotHideTheWriteFailure() {
        // A journal that cannot record the removal, as a full disk would refuse it.
        val journal = object : TempJournal {
            override fun add(temp: Path) = Unit

            override fun remove(temp: Path) {
                throw FileSystemException(temp.toString(), null, "No space left on device")
            }
        }
        val faulty = FaultyFs().apply { failAt(".xpart", 10, "Input/output error") }

        val e = assertThrows(FsException::class.java) {
            protocol(faulty, journal).writeBlocking(dir.resolve("out.txt"))
        }

        // The write's own failure is what is reported; the cleanup's goes with it.
        assertEquals(FsError.IoError, e.error)
        assertEquals(1, e.suppressed.size)
        assertEquals(emptyList<String>(), names())

        // Nor does a cleanup failure turn a cancellation into an error.
        val broken = object : FsBackend by NioFsBackend {
            override fun delete(path: Path) = throw IllegalStateException("broken backend")
        }
        var thrown: Throwable? = null
        runBlocking {
            launch {
                try {
                    val source = ByteArrayInputStream(content)
                    protocol(broken).write(dir.resolve("b.txt"), OP, false, false, null, source) { cancel() }
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }.join()
        }
        assertTrue(thrown is CancellationException)
        assertTrue(thrown!!.suppressed.single() is IllegalStateException)
    }

    @Test
    fun bufferIsSizedToTheExpectedSize() {
        val bytes = ByteArray(20_000) { it.toByte() }
        fun writesFor(expectedSize: Long?): Int {
            val fs = FaultyFs()
            val target = folder.newFolder().toPath().resolve("out.bin")
            runBlocking {
                val source = ByteArrayInputStream(bytes)
                WriteProtocol(fs, NoJournal).write(target, OP, false, false, null, source, expectedSize) {}
            }
            assertArrayEquals(bytes, Files.readAllBytes(target))
            return fs.calls.count { it.op == FaultyFs.Op.WRITE }
        }

        // Without a size, one 1 MiB buffer takes it all.
        assertEquals(1, writesFor(null))
        // A small file gets a small buffer (8 KiB at least), so 20,000 bytes take three.
        assertEquals(3, writesFor(10))
        assertEquals(3, writesFor(0))
        // A large one is never given more than the buffer size.
        assertEquals(1, writesFor(1L shl 40))
    }

    @Test
    fun shortFileOnDiskIsAFailure() {
        // A volume that kept one byte less than it was given.
        val lossy = object : FsBackend by NioFsBackend {
            override fun attributes(path: Path): BasicFileAttributes {
                val real = NioFsBackend.attributes(path)
                if (!path.fileName.toString().endsWith(HiddenNames.PART_SUFFIX)) return real
                return object : BasicFileAttributes by real {
                    override fun size(): Long = real.size() - 1
                }
            }
        }
        val target = dir.resolve("out.txt")

        val e = assertThrows(FsException::class.java) { protocol(lossy).writeBlocking(target) }

        assertEquals(FsError.IoError, e.error)
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun replaceKeepsOldTargetOnFailure() {
        val target = Files.write(dir.resolve("out.txt"), old)
        val fs = FaultyFs().apply { failAt(".xpart", 10, "No space left on device") }

        assertThrows(FsException::class.java) { protocol(fs).writeBlocking(target, replaceExisting = true) }

        assertArrayEquals(old, Files.readAllBytes(target))
        assertEquals(listOf("out.txt"), names())
        // Without the failure, the old file is replaced.
        protocol().writeBlocking(target, replaceExisting = true)
        assertArrayEquals(content, Files.readAllBytes(target))
    }

    @Test
    fun refusesExistingWithoutReplace() {
        val target = Files.write(dir.resolve("out.txt"), old)
        val journal = MemoryJournal()

        assertThrows(FileAlreadyExistsException::class.java) { protocol(journal = journal).writeBlocking(target) }

        assertArrayEquals(old, Files.readAllBytes(target))
        assertEquals(listOf("out.txt"), names())
        assertEquals(emptySet<Path>(), journal.recorded)

        // On a case-insensitive volume a name that differs only in case is taken too.
        val sd = folder.newFolder("sd").toPath()
        Files.write(sd.resolve("OUT.txt"), old)
        val fs = FaultyFs().apply { caseInsensitive = true }
        assertThrows(FileAlreadyExistsException::class.java) { protocol(fs).writeBlocking(sd.resolve("out.txt")) }
        assertEquals(listOf("OUT.txt"), names(sd))
        assertArrayEquals(old, Files.readAllBytes(sd.resolve("OUT.txt")))
    }

    @Test
    fun cancelDeletesTemp() {
        val journal = MemoryJournal()
        val target = dir.resolve("out.txt")
        var buffers = 0
        var finished = false

        runBlocking {
            launch {
                protocol(journal = journal).write(target, OP, false, false, null, ByteArrayInputStream(content)) {
                    buffers++
                    cancel()
                }
                finished = true
            }.join()
        }

        assertFalse(finished)
        assertEquals(1, buffers)
        assertFalse(Files.exists(target))
        assertEquals(emptyList<String>(), names())
        assertEquals(listOf("add .out.txt.op1.xpart", "remove .out.txt.op1.xpart"), journal.events)
    }

    @Test
    fun crashLeavesOnlyXpart() {
        for (replacing in listOf(false, true)) {
            val steps = FaultyFs().let { fs ->
                val target = folder.newFolder().toPath().resolve("out.txt")
                if (replacing) Files.write(target, old)
                protocol(fs).writeBlocking(target, sync = true, replaceExisting = replacing, mtime = MTIME)
                fs.steps
            }
            // createNew, four buffers, force, setModified, atomicMove.
            assertEquals(8, steps)
            for (k in 1..steps) {
                val at = folder.newFolder().toPath()
                val target = at.resolve("out.txt")
                val temp = at.resolve(tempNameFor("out.txt", OP))
                if (replacing) Files.write(target, old)
                val fs = FaultyFs().apply { crashAtStep = k }
                val journal = MemoryJournal()

                assertThrows(SimulatedCrash::class.java) {
                    protocol(fs, journal).writeBlocking(target, sync = true, replaceExisting = replacing, mtime = MTIME)
                }

                val what = "replacing=$replacing, crash at step $k"
                // The final name holds the old file, nothing, or the complete new one: never a part.
                if (Files.exists(target)) {
                    val bytes = Files.readAllBytes(target)
                    assertTrue(what, bytes.contentEquals(content) || (replacing && bytes.contentEquals(old)))
                } else {
                    assertFalse(what, replacing)
                }
                // Anything else left is the part file, and the journal still names it.
                assertTrue(what, names(at).all { it == "out.txt" || it == temp.fileName.toString() })
                assertEquals(what, setOf(temp), journal.recorded)
            }
        }
    }

    @Test
    fun setsModifiedTime() {
        val target = dir.resolve("out.txt")
        protocol().writeBlocking(target, mtime = MTIME)
        assertEquals(MTIME, Files.getLastModifiedTime(target).toMillis())

        // A volume that refuses the time still gets the file.
        val refusing = object : FsBackend by NioFsBackend {
            override fun setModified(path: Path, millis: Long) {
                throw FileSystemException(path.toString(), null, "Operation not permitted")
            }
        }
        val other = dir.resolve("other.txt")
        protocol(refusing).writeBlocking(other, mtime = MTIME)
        assertArrayEquals(content, Files.readAllBytes(other))
        assertEquals(listOf("other.txt", "out.txt"), names())
    }

    @Test
    fun syncCallsForceOnlyWhenAsked() {
        val fs = FaultyFs()
        protocol(fs).writeBlocking(dir.resolve("plain.txt"), sync = false)
        assertEquals(emptyList<FaultyFs.Call>(), fs.calls.filter { it.op == FaultyFs.Op.FORCE })

        val synced = dir.resolve("synced.txt")
        val temp = dir.resolve(tempNameFor("synced.txt", OP))
        val before = fs.calls.size
        protocol(fs).writeBlocking(synced, sync = true)
        val calls = fs.calls.drop(before)

        val write = FaultyFs.Call(FaultyFs.Op.WRITE, temp)
        assertEquals(
            listOf(
                FaultyFs.Call(FaultyFs.Op.CREATE_NEW, temp),
                write, write, write, write,
                FaultyFs.Call(FaultyFs.Op.FORCE, temp),
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, temp, synced),
            ),
            calls,
        )
    }

    private companion object {
        const val BUFFER = 8
        const val OP = "op1"
        const val MTIME = 1_600_000_000_000L
    }
}
