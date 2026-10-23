// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class FileOpsMoveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val internal: Path by lazy { folder.newFolder("internal").toPath() }
    private val sd: Path by lazy { folder.newFolder("sd").toPath() }

    private val journal = MemoryJournal()

    private val volumes by lazy {
        volumesOf(
            VolumeRoot("primary", internal, VolumeTraits(), removable = false),
            VolumeRoot("1234-5678", sd, VolumeTraits(fatNames = true), removable = true),
        )
    }

    private fun ops(fs: FsBackend = NioFsBackend) = FileOps(fs, volumes, KO_LABELS, journal, ids = { "r1" })

    /** A backend on which moving between the two volumes is not a rename. */
    private fun twoVolumes() = FaultyFs().apply { exdevBetween(internal, sd) }

    private fun move(vararg sources: Path, into: Path) = spec(OpKind.MOVE, sources.toList(), into)

    private fun key(path: Path): Any? = NioFsBackend.attributes(path).fileKey()

    @Test
    fun sameVolumeMoveIsRename() {
        val d = write(internal, "d/f.txt", "f").parent
        val x = write(internal, "x.txt", "x")
        val target = Files.createDirectory(internal.resolve("t"))
        val keys = listOf(key(d), key(d.resolve("f.txt")), key(x))

        val result = ops().runToEnd(move(d, x, into = target))

        assertEquals(OpResult.Done(listOf(target.resolve("d"), target.resolve("x.txt")), emptyList()), result)
        // The very same files, under their new names.
        val moved = listOf(target.resolve("d"), target.resolve("d/f.txt"), target.resolve("x.txt"))
        assertEquals(keys, moved.map(::key))
        assertEquals(mapOf("t/" to "", "t/d/" to "", "t/d/f.txt" to "f", "t/x.txt" to "x"), tree(internal))
    }

    @Test
    fun crossVolumeMoveCopiesThenDeletes() {
        val d = write(internal, "d/f.txt", "f").parent
        write(internal, "d/g/h.txt", "h")
        val x = write(internal, "x.txt", "x")
        Files.setLastModifiedTime(d, FileTime.fromMillis(TIME))
        val fs = twoVolumes()

        val result = ops(fs).runToEnd(move(d, x, into = sd))

        assertEquals(OpResult.Done(listOf(sd.resolve("d"), sd.resolve("x.txt")), emptyList()), result)
        assertEquals(
            mapOf("d/" to "", "d/f.txt" to "f", "d/g/" to "", "d/g/h.txt" to "h", "x.txt" to "x"),
            tree(sd),
        )
        assertEquals(emptyMap<String, String>(), tree(internal))
        // Each file is flushed before its source goes.
        assertEquals(3, fs.calls.count { it.op == FaultyFs.Op.FORCE })
        assertEquals(TIME, Files.getLastModifiedTime(sd.resolve("d")).toMillis())
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun crossVolumePartialFailureKeepsSourceAndReports() {
        val d = write(internal, "d/1.txt", "one").parent
        val second = write(internal, "d/2.txt", "two")
        val third = write(internal, "d/3.txt", "three")
        val fs = twoVolumes().apply {
            deny(second)
            deny(third)
        }

        val result = ops(fs).runToEnd(move(d, into = sd))

        // The first file moved; the other two stayed, and so did the folder that holds them.
        assertEquals(
            OpResult.Done(
                listOf(sd.resolve("d")),
                listOf(
                    ItemFailure(second.toString(), FsError.AccessDenied, into = "$sd/d"),
                    ItemFailure(third.toString(), FsError.AccessDenied, into = "$sd/d"),
                ),
                leftInSource = 2,
            ),
            result,
        )
        assertEquals(mapOf("d/" to "", "d/2.txt" to "two", "d/3.txt" to "three"), tree(internal))
        assertEquals(mapOf("d/" to "", "d/1.txt" to "one"), tree(sd))
    }

    @Test
    fun leftInSourceCountsEverythingLeftInTheKeptFolders() {
        val d = write(internal, "d/a.txt", "a").parent
        val kept = write(internal, "d/sub/kept.txt", "kept")
        write(internal, "d/sub/b.txt", "b")
        val skipped = write(internal, "d/skipped/c.txt", "c").parent
        write(sd, "d/skipped/c.txt", "old c")
        val faulty = twoVolumes()
        // kept.txt is copied, but cannot be deleted.
        val fs = object : FsBackend by faulty {
            override fun delete(path: Path) {
                if (path == kept) throw AccessDeniedException(path.toString())
                faulty.delete(path)
            }
        }
        val resolver = Answering { c ->
            Resolution(if (c.source.path == skipped) ConflictAction.SKIP else ConflictAction.MERGE)
        }

        val result = ops(fs).runToEnd(move(d, into = sd), resolver)

        // Left: sub/kept.txt, sub (in d), and skipped with all it holds (one item in d).
        assertEquals(
            OpResult.Done(
                listOf(sd.resolve("d")),
                listOf(ItemFailure(kept.toString(), FsError.AccessDenied, copiedButSourceKept = true)),
                leftInSource = 3,
            ),
            result,
        )
        assertEquals(
            mapOf("d/" to "", "d/skipped/" to "", "d/skipped/c.txt" to "c", "d/sub/" to "", "d/sub/kept.txt" to "kept"),
            tree(internal),
        )
    }

    @Test
    fun undeletableEmptiedSourceFolderIsMarked() {
        val d = write(internal, "d/a.txt", "a").parent
        val faulty = twoVolumes()
        val fs = object : FsBackend by faulty {
            override fun delete(path: Path) {
                if (path == d) throw AccessDeniedException(path.toString())
                faulty.delete(path)
            }
        }

        val result = ops(fs).runToEnd(move(d, into = sd))

        // Everything in it moved; the folder itself stayed, and is listed so a retry does not copy it again.
        assertEquals(
            OpResult.Done(
                listOf(sd.resolve("d")),
                listOf(ItemFailure(d.toString(), FsError.AccessDenied, copiedButSourceKept = true)),
            ),
            result,
        )
        assertEquals(mapOf("d/" to ""), tree(internal))
        assertEquals(mapOf("d/" to "", "d/a.txt" to "a"), tree(sd))
    }

    @Test
    fun copiedButUndeletableSourceIsMarked() {
        val x = write(internal, "x.txt", "x")
        val y = write(internal, "y.txt", "y")
        val faulty = twoVolumes()
        val fs = object : FsBackend by faulty {
            override fun delete(path: Path) {
                if (path == x) throw AccessDeniedException(path.toString())
                faulty.delete(path)
            }
        }

        val result = ops(fs).runToEnd(move(x, y, into = sd))

        // x.txt is on the card already: a retry must not copy it again.
        assertEquals(
            OpResult.Done(
                listOf(sd.resolve("x.txt"), sd.resolve("y.txt")),
                listOf(ItemFailure(x.toString(), FsError.AccessDenied, copiedButSourceKept = true)),
            ),
            result,
        )
        assertEquals(mapOf("x.txt" to "x"), tree(internal))
        assertEquals(mapOf("x.txt" to "x", "y.txt" to "y"), tree(sd))
    }

    @Test
    fun sameFolderCutPasteIsNoop() {
        val t = internal.resolve("t")
        val a = write(t, "a.txt", "a")
        val d = write(t, "d/b.txt", "b").parent
        val before = tree(internal)
        val fs = FaultyFs()

        val result = ops(fs).runToEnd(move(a, d, into = t))

        assertEquals(OpResult.Done(emptyList(), emptyList()), result)
        assertEquals(before, tree(internal))
        assertEquals(emptyList<FaultyFs.Call>(), fs.calls)
    }

    @Test
    fun skipKeepsSource() {
        val a = write(internal, "a.txt", "new")
        val d = write(internal, "d/b.txt", "new b").parent
        write(sd, "a.txt", "old")
        write(sd, "d/c.txt", "old c")
        val before = tree(internal)
        val after = tree(sd)
        val skip = Answering { Resolution(ConflictAction.SKIP) }

        val result = ops(twoVolumes()).runToEnd(move(a, d, into = sd), skip)

        assertEquals(OpResult.Done(emptyList(), emptyList()), result)
        assertEquals(listOf(ConflictKind.FILE_FILE, ConflictKind.DIR_DIR), skip.asked.map { it.kind })
        assertEquals(before, tree(internal))
        assertEquals(after, tree(sd))
    }

    @Test
    fun moveMergesAndRemovesTheEmptiedSource() {
        val d = write(internal, "d/a.txt", "a").parent
        write(internal, "d/sub/b.txt", "b")
        val target = internal.resolve("t")
        write(target, "d/c.txt", "c")
        val kept = key(target.resolve("d"))
        val merge = Answering { Resolution(ConflictAction.MERGE) }

        val result = ops().runToEnd(move(d, into = target), merge)

        assertEquals(OpResult.Done(listOf(target.resolve("d")), emptyList()), result)
        assertEquals(
            mapOf(
                "t/" to "",
                "t/d/" to "",
                "t/d/a.txt" to "a",
                "t/d/c.txt" to "c",
                "t/d/sub/" to "",
                "t/d/sub/b.txt" to "b",
            ),
            tree(internal),
        )
        assertEquals(kept, key(target.resolve("d")))
        assertFalse(Files.exists(d))
    }

    @Test
    fun moveNeverCallsMoveWithoutAtomic() {
        // On one volume, a move is one rename per item and nothing else: no copy, no delete.
        val d = write(internal, "d/f.txt", "f").parent
        val x = write(internal, "x.txt", "x")
        val target = Files.createDirectory(internal.resolve("t"))
        val same = FaultyFs()
        ops(same).runToEnd(move(d, x, into = target))
        assertEquals(
            listOf(
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, d, target.resolve("d")),
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, x, target.resolve("x.txt")),
            ),
            same.calls,
        )

        // Across volumes, every move is still a rename: tried for the item, or of a written part into
        // place. A source is deleted only after its copy has its final name.
        val e = write(internal, "e/g.txt", "g").parent
        val y = write(internal, "y.txt", "y")
        val across = twoVolumes()
        ops(across).runToEnd(move(e, y, into = sd))
        val calls = across.calls
        val moves = calls.filter { it.op == FaultyFs.Op.ATOMIC_MOVE }
        assertEquals(4, moves.size)
        for (call in moves) {
            val tried = call.path == e || call.path == y
            val part = call.path.fileName.toString().endsWith(HiddenNames.PART_SUFFIX)
            assertTrue("$call", tried || part)
        }
        for ((source, copy) in listOf(e.resolve("g.txt") to sd.resolve("e/g.txt"), y to sd.resolve("y.txt"))) {
            val committed = calls.indexOfFirst { it.op == FaultyFs.Op.ATOMIC_MOVE && it.to == copy }
            val deleted = calls.indexOf(FaultyFs.Call(FaultyFs.Op.DELETE, source))
            assertTrue("$source", committed in 0 until deleted)
        }
    }

    private companion object {
        const val TIME = 1_600_000_000_000L
    }
}
