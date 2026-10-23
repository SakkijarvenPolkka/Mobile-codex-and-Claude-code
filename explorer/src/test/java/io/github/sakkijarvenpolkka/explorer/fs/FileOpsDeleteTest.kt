// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FileOpsDeleteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val internal: Path by lazy { folder.newFolder("internal").toPath().toRealPath() }
    private val sd: Path by lazy { folder.newFolder("sd").toPath().toRealPath() }

    private val volumes by lazy {
        volumesOf(
            VolumeRoot("primary", internal, VolumeTraits(), removable = false),
            VolumeRoot("1234-5678", sd, VolumeTraits(fatNames = true), removable = true),
        )
    }

    private var lastId = 0

    private fun bin(fs: FsBackend = NioFsBackend) = RecycleBin(fs, volumes, { DELETED_AT }, { "b${++lastId}" })

    private fun ops(fs: FsBackend = NioFsBackend, bin: RecycleBin? = bin(fs)) =
        FileOps(fs, volumes, KO_LABELS, MemoryJournal(), ids = { "r1" }, bin = bin)

    private fun delete(kind: OpKind, vararg sources: Path) = OperationSpec(OP_ID, kind, sources.map { it.toString() })

    private fun deletes(fs: FaultyFs): List<Path> = fs.calls.filter { it.op == FaultyFs.Op.DELETE }.map { it.path }

    @Test
    fun deleteToBinNoPrompt() {
        val d = write(internal, "d/f.txt", "f").parent
        val x = write(internal, "x.txt", "xx")
        val bin = bin()
        val progress = ArrayList<OpProgress>()

        val result = ops(bin = bin).runToEnd(delete(OpKind.DELETE_TO_BIN, d, x), NeverAsked) { progress += it }

        val binFolder = binOf(internal)
        val payloads = listOf(binFolder.resolve("b1/d"), binFolder.resolve("b2/x.txt"))
        assertEquals(OpResult.Done(payloads, emptyList()), result)
        assertEquals(emptyMap<String, String>(), outsideBin(internal))
        assertEquals(
            listOf(
                BinMeta(originalPath = d.toString(), deletedAt = DELETED_AT, size = 1, isDir = true),
                BinMeta(originalPath = x.toString(), deletedAt = DELETED_AT, size = 2, isDir = false),
            ),
            bin.list(listOf(internal)).sortedBy { it.id }.map { it.meta },
        )
        assertEquals(OpProgress(0, 0, 2, 0, null), progress.first())
        assertEquals(OpProgress(0, 0, 2, 2, "x.txt"), progress.last())
    }

    @Test
    fun permanentDeleteRemovesTreePostOrder() {
        val d = write(internal, "d/a.txt", "aa").parent
        write(internal, "d/sub/b.txt", "b")
        Files.createDirectory(d.resolve("empty"))
        write(internal, "keep.txt", "keep")
        val fs = FaultyFs()
        val progress = ArrayList<OpProgress>()

        val result = ops(fs).runToEnd(delete(OpKind.DELETE_PERMANENT, d), NeverAsked) { progress += it }

        assertEquals(OpResult.Done(emptyList(), emptyList()), result)
        assertEquals(mapOf("keep.txt" to "keep"), tree(internal))
        // Each item before the folder it is in, one at a time; nothing else is changed.
        assertEquals(
            listOf(d.resolve("sub/b.txt"), d.resolve("sub"), d.resolve("empty"), d.resolve("a.txt"), d),
            deletes(fs),
        )
        assertEquals(fs.steps, deletes(fs).size)
        assertEquals(OpProgress(3, 0, 5, 0, null), progress.first())
        assertEquals(OpProgress(3, 3, 5, 5, "d"), progress.last())
    }

    @Test
    fun permanentDeleteKeepsWhatCannotBeDeleted() {
        val d = write(internal, "d/a.txt", "a").parent
        val locked = write(internal, "d/locked/b.txt", "b").parent
        val gone = internal.resolve("gone.txt")
        val fs = FaultyFs().apply { deny(locked) }

        val result = ops(fs).runToEnd(delete(OpKind.DELETE_PERMANENT, d, gone))

        // The folder that cannot be read stays, and so does the one it is in, which could only fail as not empty.
        assertEquals(
            OpResult.Done(
                emptyList(),
                listOf(
                    ItemFailure(locked.toString(), FsError.AccessDenied),
                    ItemFailure(gone.toString(), FsError.NotFound),
                ),
            ),
            result,
        )
        assertEquals(mapOf("d/" to "", "d/locked/" to "", "d/locked/b.txt" to "b"), tree(internal))
        assertEquals(listOf(d.resolve("a.txt")), deletes(fs))
    }

    @Test
    fun deletingDirWithSymlinkKeepsLinkTarget() {
        val target = write(internal, "target/t.txt", "t").parent
        val file = write(internal, "file.txt", "file")
        val d = Files.createDirectory(internal.resolve("d"))
        Files.createSymbolicLink(d.resolve("to-dir"), target)
        Files.createSymbolicLink(d.resolve("to-file"), file)
        val link = Files.createSymbolicLink(internal.resolve("link"), target)

        val result = ops().runToEnd(delete(OpKind.DELETE_PERMANENT, d, link))

        assertEquals(OpResult.Done(emptyList(), emptyList()), result)
        assertEquals(mapOf("file.txt" to "file", "target/" to "", "target/t.txt" to "t"), tree(internal))
    }

    @Test
    fun restoreConflictGoesThroughResolver() {
        val x = write(internal, "a/x.txt", "old")
        val bin = bin()
        val ops = ops(bin = bin)
        ops.runToEnd(delete(OpKind.DELETE_TO_BIN, x))
        write(internal, "a/x.txt", "new")
        val item = bin.list(listOf(internal)).single()

        // Skipped: it stays in the bin, as it was.
        val skip = Answering { Resolution(ConflictAction.SKIP) }
        assertEquals(OpResult.Done(emptyList(), emptyList()), ops.runToEnd(restore(item), skip))
        assertEquals(listOf(item), bin.list(listOf(internal)))

        val keepBoth = Answering { Resolution(ConflictAction.KEEP_BOTH) }
        val result = ops.runToEnd(restore(item), keepBoth)

        val asked = keepBoth.asked.single()
        assertEquals(ConflictKind.FILE_FILE, asked.kind)
        assertEquals(item.payload, asked.source.path)
        assertEquals(x, asked.existing.path)
        assertEquals(OpResult.Done(listOf(internal.resolve("a/x (2).txt")), emptyList()), result)
        assertEquals(mapOf("a/" to "", "a/x (2).txt" to "old", "a/x.txt" to "new"), outsideBin(internal))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(internal)))
    }

    @Test
    fun restoreOfAnItemNoLongerInTheBinFails() {
        val x = write(internal, "x.txt", "x")
        val bin = bin()
        val ops = ops(bin = bin)
        ops.runToEnd(delete(OpKind.DELETE_TO_BIN, x))
        val item = bin.list(listOf(internal)).single()
        val gone = item.copy(id = "gone", payload = binOf(internal).resolve("gone/y.txt"))
        val committed = ArrayList<Int>()

        val result = runBlocking {
            ops.run(restore(gone, item), NeverAsked, onProgress = {}) { committed += it }
        }

        assertEquals(
            OpResult.Done(listOf(x), listOf(ItemFailure(gone.payload.toString(), FsError.NotFound))),
            result,
        )
        assertEquals(mapOf("x.txt" to "x"), outsideBin(internal))
        assertEquals(listOf(2), committed)
    }

    @Test
    fun emptyBinPurgesAll() {
        val d = write(internal, "d/a.txt", "a").parent
        write(internal, "d/sub/b.txt", "b")
        Files.createDirectory(d.resolve("empty"))
        val x = write(internal, "x.txt", "x")
        val e = Files.createDirectory(internal.resolve("e"))
        ops().runToEnd(delete(OpKind.DELETE_TO_BIN, d, x, e))
        val binFolder = binOf(internal)
        write(binFolder, "loose/y.txt", "yy")
        val fs = FaultyFs()
        val progress = ArrayList<OpProgress>()

        val result = ops(fs).runToEnd(OperationSpec(OP_ID, OpKind.EMPTY_BIN, listOf(binFolder.toString()))) {
            progress += it
        }

        assertEquals(OpResult.Done(emptyList(), emptyList()), result)
        assertEquals(emptyMap<String, String>(), tree(binFolder))
        assertEquals(emptyMap<String, String>(), outsideBin(internal))
        // Each item's content goes first, its json last.
        val deleted = deletes(fs)
        for (id in listOf("b1", "b2", "b3")) {
            val json = deleted.indexOf(binFolder.resolve("$id.json"))
            val content = deleted.withIndex().filter { it.value.startsWith(binFolder.resolve(id)) }.map { it.index }
            assertTrue(id, json > 0 && content.isNotEmpty() && content.all { it < json })
        }
        assertEquals(OpProgress(2 + 1 + 2, 0, 4, 0, null), progress.first())
        assertEquals(4, progress.last().doneItems)
    }

    @Test
    fun binUnavailableReportedNotSilentlyDeleted() {
        val readOnly = write(sd, "x.txt", "x")
        val nowhere = write(folder.root.toPath(), "elsewhere/y.txt", "y")
        val fs = FaultyFs().apply {
            refuse(FaultyFs.Op.CREATE_DIRECTORY, HiddenNames.RECYCLE_BIN, "Read-only file system")
        }

        val result = ops(fs).runToEnd(delete(OpKind.DELETE_TO_BIN, readOnly, nowhere))

        assertEquals(
            OpResult.Done(
                emptyList(),
                listOf(
                    ItemFailure(readOnly.toString(), FsError.BinUnavailable),
                    ItemFailure(nowhere.toString(), FsError.BinUnavailable),
                ),
            ),
            result,
        )
        assertEquals(mapOf("x.txt" to "x"), tree(sd))
        assertEquals("y", String(Files.readAllBytes(nowhere)))
        assertEquals(emptyList<Path>(), deletes(fs))
    }

    @Test
    fun binKindsNeedABin() {
        val x = write(internal, "x.txt", "x")
        for (kind in listOf(OpKind.DELETE_TO_BIN, OpKind.RESTORE, OpKind.EMPTY_BIN)) {
            val result = ops(bin = null).runToEnd(delete(kind, x))
            assertEquals("$kind", OpResult.Failed(FsError.BinUnavailable, 0, 0), result)
        }
        assertEquals(mapOf("x.txt" to "x"), tree(internal))
    }
}
