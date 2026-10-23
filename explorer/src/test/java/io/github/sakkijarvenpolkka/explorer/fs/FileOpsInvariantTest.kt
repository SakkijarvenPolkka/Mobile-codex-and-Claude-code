// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Spec §4.3: whenever the process dies during a copy or a move across volumes, every source file is
 * whole where it was or whole where it was going, no final name holds a cut file, and what is left
 * besides is temporary files the journal names.
 */
class FileOpsInvariantTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Five files, one of them empty, in two folders. */
    private val files = mapOf(
        "top/a.txt" to "alpha",
        "top/b.txt" to "bravo bravo",
        "top/empty.txt" to "",
        "top/sub/c.txt" to "charlie",
        "top/sub/d.txt" to "delta delta delta",
    )

    /** One run in fresh folders, crashing at [crashAt] (null: none); the [Run] it leaves. */
    private fun runOnce(kind: OpKind, crashAt: Int?): Run {
        val base = folder.newFolder().toPath()
        val src = Files.createDirectory(base.resolve("internal"))
        val dst = Files.createDirectory(base.resolve("sd"))
        for ((relative, content) in files) write(src, relative, content)
        val fs = FaultyFs().apply {
            exdevBetween(src, dst)
            crashAtStep = crashAt
        }
        val journal = MemoryJournal()
        val ops = FileOps(fs, VolumeLookup { null }, KO_LABELS, journal, ids = { "r1" })
        val spec = spec(kind, listOf(src.resolve("top")), dst)
        if (crashAt == null) {
            assertTrue(ops.runToEnd(spec) is OpResult.Done)
        } else {
            assertThrows(SimulatedCrash::class.java) { ops.runToEnd(spec) }
        }
        return Run(src, dst, journal, fs.steps)
    }

    private class Run(val src: Path, val dst: Path, val journal: MemoryJournal, val steps: Int)

    /** Runs [kind] once without a crash, checks it took [expectedSteps], then crashes it at each. */
    private fun checkInvariant(kind: OpKind, expectedSteps: Int) {
        val steps = runOnce(kind, crashAt = null).steps
        assertEquals(kind.toString(), expectedSteps, steps)
        for (k in 1..steps) {
            val what = "$kind, crash at step $k of $steps"
            val run = runOnce(kind, crashAt = k)
            val left = tree(run.src)
            val arrived = tree(run.dst)

            for ((relative, content) in files) {
                val whole = left[relative] == content || arrived[relative] == content
                assertTrue("$what: $relative is lost", whole)
                // A copy never touches its sources.
                if (kind == OpKind.COPY) assertEquals(what, content, left[relative])
            }
            for ((root, found) in listOf(run.src to left, run.dst to arrived)) {
                for ((relative, content) in found) {
                    if (relative.endsWith("/")) continue
                    val name = relative.substringAfterLast('/')
                    if (name.endsWith(HiddenNames.PART_SUFFIX)) {
                        assertTrue("$what: $relative is not journaled", root.resolve(relative) in run.journal.recorded)
                    } else {
                        assertEquals("$what: $relative is not whole", files[relative], content)
                    }
                }
            }
        }
    }

    // Each file: create the part, write it (not the empty one), set its time, rename it into place (4 × 4
    // + 3); each folder: create it, set its time once its content is done (2 × 2).
    @Test
    fun copySurvivesACrashAtEveryStep() = checkInvariant(OpKind.COPY, expectedSteps = 19 + 4)

    // The rename tried first; then as a copy, each file also flushed and its source deleted (4 × 6 + 5);
    // each folder also deleted from the source (2 × 3).
    @Test
    fun crossVolumeMoveSurvivesACrashAtEveryStep() = checkInvariant(OpKind.MOVE, expectedSteps = 1 + 29 + 6)
}
