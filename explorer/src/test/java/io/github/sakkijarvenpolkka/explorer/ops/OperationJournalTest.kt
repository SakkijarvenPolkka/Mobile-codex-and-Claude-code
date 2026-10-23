// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ops

import io.github.sakkijarvenpolkka.explorer.fs.FaultyFs
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.KO_LABELS
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OP_ID
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.SimulatedCrash
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.fs.tree
import io.github.sakkijarvenpolkka.explorer.fs.write
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class OperationJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Where the journal keeps its files. */
    private val dir: File by lazy { folder.newFolder("ops") }

    /** A volume the operations write to. */
    private val volume: Path by lazy { folder.newFolder("volume").toPath() }

    private val spec by lazy {
        OperationSpec(OP_ID, OpKind.COPY, listOf("/src/a.txt", "/src/b.txt", "/src/c"), volume.toString())
    }

    /** The journal as a new process finds it. */
    private fun restarted(at: File = dir) = OperationJournal(at, NioFsBackend)

    private fun files(at: File = dir): Set<String> = at.list().orEmpty().toSet()

    @Test
    fun sweepDeletesRecordedTemps() {
        val first = write(volume, "d/.a.txt.op1.xpart", "part")
        val second = write(volume, ".b.txt.op1.xpart", "part")
        // One of no operation begun here.
        val loose = write(volume, ".x.txt.other.xpart", "part")
        write(volume, "c.txt", "c")
        val journal = OperationJournal(dir, NioFsBackend)
        journal.begin(spec)
        journal.add(first)
        journal.add(second)
        journal.add(volume.resolve(".gone.txt.op1.xpart"))
        journal.add(loose)

        // The process dies here.
        val interrupted = restarted().sweep()

        assertEquals(listOf(InterruptedOp(spec, 0)), interrupted)
        assertEquals(mapOf("c.txt" to "c", "d/" to ""), tree(volume))
        // Only the operation's journal is left, for it to be retried or dismissed.
        assertEquals(setOf("op1.json"), files())
    }

    @Test
    fun sweepKeepsTempsWhoseFolderIsAway() {
        // On a volume that is not mounted at the moment.
        val away = volume.resolve("sd/d/.a.txt.op1.xpart")
        val journal = OperationJournal(dir, NioFsBackend)
        journal.begin(spec)
        journal.add(away)
        journal.end(OP_ID)

        // Not an interrupted operation, but the temporary file is still to be deleted.
        assertEquals(emptyList<InterruptedOp>(), restarted().sweep())
        assertEquals(1, files().size)
        write(volume, "sd/d/.a.txt.op1.xpart", "part")

        assertEquals(emptyList<InterruptedOp>(), restarted().sweep())

        assertEquals(mapOf("sd/" to "", "sd/d/" to ""), tree(volume))
        assertEquals(emptySet<String>(), files())
    }

    @Test
    fun interruptedOpSurvivesRestart() {
        val journal = OperationJournal(dir, NioFsBackend)
        journal.begin(spec)
        journal.committed(OP_ID, 2)

        val again = restarted()

        assertEquals(listOf(InterruptedOp(spec, 2)), again.sweep())
        // The journal stays until the operation is retried or dismissed.
        assertEquals(listOf(InterruptedOp(spec, 2)), restarted().sweep())
        again.discard(OP_ID)
        assertEquals(emptyList<InterruptedOp>(), restarted().sweep())
        assertEquals(emptySet<String>(), files())
    }

    @Test
    fun endRemovesJournal() {
        // A temporary file its write could not delete.
        val temp = write(volume, ".a.txt.op1.xpart", "part")
        val journal = OperationJournal(dir, NioFsBackend)
        journal.begin(spec)
        journal.add(temp)
        journal.committed(OP_ID, 1)
        journal.add(volume.resolve(".b.txt.op1.xpart"))
        journal.remove(volume.resolve(".b.txt.op1.xpart"))

        journal.end(OP_ID)

        assertEquals(emptySet<String>(), files())
        assertFalse(Files.exists(temp))
        assertEquals(emptyList<InterruptedOp>(), restarted().sweep())
    }

    @Test
    fun journalWrittenAtomically() {
        /** Begins [spec] and commits 1 in a fresh folder, then commits 2, crashing at [crashAt] of that write. */
        fun commitOnce(crashAt: Int?): Pair<File, Int> {
            val at = folder.newFolder()
            val fs = FaultyFs()
            val journal = OperationJournal(at, fs)
            journal.begin(spec)
            journal.committed(OP_ID, 1)
            val before = fs.steps
            if (crashAt == null) {
                journal.committed(OP_ID, 2)
            } else {
                fs.crashAtStep = before + crashAt
                assertThrows(SimulatedCrash::class.java) { journal.committed(OP_ID, 2) }
                // The new version got no further than its temporary file; the old one is whole.
                val left = if (crashAt == 1) setOf("op1.json") else setOf("op1.json", "op1.json.xpart")
                assertEquals("crash at step $crashAt", left, files(at))
            }
            return at to fs.steps - before
        }

        // Written to a temporary file (created, written), which is then renamed over the journal.
        val steps = commitOnce(crashAt = null).second
        assertEquals(3, steps)
        for (k in 1..steps) {
            val at = commitOnce(crashAt = k).first

            assertEquals("crash at step $k", listOf(InterruptedOp(spec, 1)), restarted(at).sweep())
            assertEquals("crash at step $k", setOf("op1.json"), files(at))
        }
        assertEquals(listOf(InterruptedOp(spec, 2)), restarted(commitOnce(crashAt = null).first).sweep())
    }

    @Test
    fun sweepFinishesAnInterruptedRename() {
        for (crashAt in 1..2) {
            val at = folder.newFolder()
            val volume = folder.newFolder().toPath()
            val photo = write(volume, "photo.jpg", "jpeg")
            val fs = FaultyFs().apply {
                caseInsensitive = true
                crashAtStep = crashAt
            }
            val ops = FileOps(fs, VolumeLookup { null }, KO_LABELS, OperationJournal(at, NioFsBackend), ids = { "r1" })
            assertThrows(SimulatedCrash::class.java) { runBlocking { ops.rename(photo, "Photo.jpg") } }
            val left = if (crashAt == 1) "photo.jpg" else ".~ren-r1"
            assertEquals(setOf(left), tree(volume).keys)

            assertEquals(emptyList<InterruptedOp>(), restarted(at).sweep())

            // Before the first step it is as it was; between the two, it gets the name it was going to.
            val name = if (crashAt == 1) "photo.jpg" else "Photo.jpg"
            assertEquals("crash at step $crashAt", mapOf(name to "jpeg"), tree(volume))
            assertEquals("crash at step $crashAt", emptySet<String>(), files(at))
        }
    }
}
