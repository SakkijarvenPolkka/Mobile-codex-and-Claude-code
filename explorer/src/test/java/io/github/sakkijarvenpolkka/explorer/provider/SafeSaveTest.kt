// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs
import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs.Call
import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs.Op
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.SimulatedCrash
import io.github.sakkijarvenpolkka.explorer.fs.TempJournal
import io.github.sakkijarvenpolkka.explorer.fs.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/** Another app's save over a file: into a temporary file next to it, which then takes its place (pick spec §3.5). */
class SafeSaveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: Path
    private val fs = FaultyFs()
    private val journal = EventJournal()

    /** A journal that keeps every call, in order. */
    private class EventJournal : TempJournal {
        val events = ArrayList<String>()

        override fun add(temp: Path) {
            events += "add ${temp.fileName}"
        }

        override fun remove(temp: Path) {
            events += "remove ${temp.fileName}"
        }
    }

    @Before
    fun setUp() {
        dir = folder.root.toPath()
    }

    private fun save(target: Path, id: String = "op1") = SafeSave(fs, journal).begin(target, id)

    private fun read(path: Path): String = String(Files.readAllBytes(path))

    private fun names(): List<String> = NioFsBackend.list(dir).map { it.fileName.toString() }.sorted()

    @Test
    fun commitReplacesTarget() {
        val target = write(dir, "a.txt", "old")
        val pending = save(target)

        // The other app writes into a hidden file next to it; until it is done, the file is as it was.
        assertEquals(target, pending.target)
        assertEquals(dir.resolve(".a.txt.op1.xpart"), pending.temp)
        Files.write(pending.temp, "new".toByteArray())
        assertEquals("old", read(target))

        pending.commit()

        assertEquals("new", read(target))
        assertEquals(listOf("a.txt"), names())
        assertEquals(listOf("add .a.txt.op1.xpart", "remove .a.txt.op1.xpart"), journal.events)
    }

    @Test
    fun abortKeepsOriginalAndLeavesNoTemp() {
        val target = write(dir, "a.txt", "old")
        val pending = save(target)
        Files.write(pending.temp, "half".toByteArray())

        pending.abort()

        assertEquals("old", read(target))
        assertEquals(listOf("a.txt"), names())
        assertEquals(listOf("add .a.txt.op1.xpart", "remove .a.txt.op1.xpart"), journal.events)

        // A temporary file that is gone already (swept) is not missed.
        val swept = save(target, "op2")
        Files.delete(swept.temp)
        swept.abort()
        assertEquals("old", read(target))
        assertEquals(listOf("a.txt"), names())
        assertEquals(listOf("add .a.txt.op2.xpart", "remove .a.txt.op2.xpart"), journal.events.drop(2))
    }

    @Test
    fun worksForNewTarget() {
        val target = dir.resolve("new.txt")
        val pending = save(target)
        Files.write(pending.temp, "new".toByteArray())

        pending.commit()

        assertEquals("new", read(target))
        assertEquals(listOf("new.txt"), names())
        assertEquals(listOf("add .new.txt.op1.xpart", "remove .new.txt.op1.xpart"), journal.events)
    }

    @Test
    fun commitSyncsBeforeMove() {
        val target = write(dir, "a.txt", "old")
        val temp = dir.resolve(".a.txt.op1.xpart")

        save(target).commit()

        // Made with nothing else of that name there, flushed to the device, then put in the file's place in one step.
        assertEquals(
            listOf(Call(Op.CREATE_NEW, temp), Call(Op.SYNC, temp), Call(Op.ATOMIC_MOVE, temp, target)),
            fs.calls,
        )
    }

    @Test
    fun beginThatFailsLeavesNothing() {
        val target = write(dir, "a.txt", "old")
        // Its folder takes no new file (its volume went read-only, say).
        fs.refuse(Op.CREATE_NEW, ".a.txt.op1.xpart", "Read-only file system")

        assertThrows(FileSystemException::class.java) { save(target) }

        assertEquals("old", read(target))
        assertEquals(listOf("a.txt"), names())
        assertEquals(listOf("add .a.txt.op1.xpart", "remove .a.txt.op1.xpart"), journal.events)
    }

    @Test
    fun crashBeforeMoveLeavesOriginal() {
        val target = write(dir, "a.txt", "old")
        val pending = save(target)
        Files.write(pending.temp, "new".toByteArray())

        // The process dies as the new content would take the file's place: the step after the flush.
        fs.crashAtStep = fs.steps + 2
        assertThrows(SimulatedCrash::class.java) { pending.commit() }

        assertEquals(Op.ATOMIC_MOVE, fs.calls.last().op)
        assertEquals("old", read(target))
        // What was written stays recorded, for the sweep at the app's next start.
        assertTrue(Files.exists(pending.temp))
        assertEquals(listOf("add .a.txt.op1.xpart"), journal.events)
    }
}
