// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class RecycleBinTest {

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

    /** A bin whose items are `b1`, `b2`, ..., all deleted at [DELETED_AT]. */
    private fun bin(fs: FsBackend = NioFsBackend, on: VolumeLookup = volumes) =
        RecycleBin(fs, on, clock = { DELETED_AT }, ids = { "b${++lastId}" })

    private fun ops(bin: RecycleBin) =
        FileOps(NioFsBackend, volumes, KO_LABELS, MemoryJournal(), ids = { "r1" }, bin = bin)

    private fun RecycleBin.move(path: Path): BinItem = runBlocking { moveToBin(path) }

    private fun modified(path: Path): Long = Files.getLastModifiedTime(path).toMillis()

    @Test
    fun moveWritesMetaBeforePayload() {
        val file = write(internal, "a/x.txt", "x")
        val fs = FaultyFs()

        val item = bin(fs).move(file)

        val binFolder = binOf(internal)
        val meta = binFolder.resolve("b1.json")
        val temp = binFolder.resolve(tempNameFor("b1.json", "b1"))
        val payload = binFolder.resolve("b1/x.txt")
        assertEquals(
            listOf(
                FaultyFs.Call(FaultyFs.Op.CREATE_DIRECTORY, binFolder),
                FaultyFs.Call(FaultyFs.Op.CREATE_DIRECTORY, binFolder.resolve("b1")),
                FaultyFs.Call(FaultyFs.Op.CREATE_NEW, temp),
                FaultyFs.Call(FaultyFs.Op.FORCE, temp),
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, temp, meta),
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, file, payload),
            ),
            fs.calls.filter { it.op != FaultyFs.Op.WRITE },
        )
        val expected = BinMeta(originalPath = file.toString(), deletedAt = DELETED_AT, size = 1, isDir = false)
        assertEquals(BinItem("b1", internal, payload, "x.txt", expected, modified(payload)), item)
        // Every field is written, the format version included.
        val written = Json.parseToJsonElement(String(Files.readAllBytes(meta))).jsonObject
        assertEquals(setOf("v", "originalPath", "deletedAt", "size", "isDir"), written.keys)
        assertEquals(listOf(item), bin(fs).list(listOf(internal)))
    }

    @Test
    fun binRoundTripKeepsEmptyDir() {
        val docs = Files.createDirectory(internal.resolve("docs"))
        write(docs, "d/f.txt", "f")
        write(docs, "d/zero.txt", "")
        Files.createDirectory(docs.resolve("d/empty"))
        val e = Files.createDirectory(docs.resolve("e"))
        val z = write(docs, "z.txt", "")
        val before = tree(docs)
        val bin = bin()
        val ops = ops(bin)

        val deleted = ops.runToEnd(OperationSpec(OP_ID, OpKind.DELETE_TO_BIN, listOf("$docs/d", "$e", "$z")))
        assertTrue(deleted is OpResult.Done && deleted.failed.isEmpty())
        assertEquals(emptyMap<String, String>(), tree(docs))

        val items = bin.list(listOf(internal))
        assertEquals(setOf("d", "e", "z.txt"), items.map { it.name }.toSet())
        val restored = ops.runToEnd(restore(*items.toTypedArray()))

        assertEquals(OpResult.Done(items.map { docs.resolve(it.name) }, emptyList()), restored)
        assertEquals(before, tree(docs))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(internal)))
        assertEquals(emptyMap<String, String>(), tree(binOf(internal)))
    }

    @Test
    fun binRoundTripKeepsLink() {
        val docs = Files.createDirectory(internal.resolve("docs"))
        val target = write(internal, "target/t.txt", "t")
        val links = listOf(
            Files.createSymbolicLink(docs.resolve("to-file"), target),
            Files.createSymbolicLink(docs.resolve("to-dir"), target.parent),
            Files.createSymbolicLink(docs.resolve("dangling"), internal.resolve("gone")),
        )
        val targets = links.map(Files::readSymbolicLink)
        val before = tree(internal)
        val bin = bin()
        val ops = ops(bin)

        val deleted = ops.runToEnd(OperationSpec(OP_ID, OpKind.DELETE_TO_BIN, links.map { it.toString() }))
        assertTrue(deleted is OpResult.Done && deleted.failed.isEmpty())
        // The links themselves went, not what they point to.
        assertEquals(mapOf("docs/" to "", "target/" to "", "target/t.txt" to "t"), outsideBin(internal))

        val items = bin.list(listOf(internal)).sortedBy { it.id }
        assertEquals(links.map { it.fileName.toString() }, items.map { it.name })
        val restored = ops.runToEnd(restore(*items.toTypedArray()))

        assertEquals(OpResult.Done(links, emptyList()), restored)
        for ((link, linkTarget) in links.zip(targets)) {
            assertTrue("$link", Files.isSymbolicLink(link))
            assertEquals("$link", linkTarget, Files.readSymbolicLink(link))
        }
        assertEquals(before, outsideBin(internal))
        assertEquals(emptyList<BinItem>(), bin.list(listOf(internal)))
        assertEquals(emptyMap<String, String>(), tree(binOf(internal)))
    }

    @Test
    fun restoreRecreatesMissingParent() {
        val file = write(internal, "a/b/c.txt", "c")
        val bin = bin()
        val item = bin.move(file)
        Files.delete(internal.resolve("a/b"))
        Files.delete(internal.resolve("a"))

        val result = ops(bin).runToEnd(restore(item))

        assertEquals(OpResult.Done(listOf(file), emptyList()), result)
        assertEquals(mapOf("a/" to "", "a/b/" to "", "a/b/c.txt" to "c"), outsideBin(internal))
        assertEquals(emptyMap<String, String>(), tree(binOf(internal)))
    }

    @Test
    fun listReconcilesOrphans() {
        val binFolder = binOf(internal)
        val meta = """{"v":1,"originalPath":"$internal/x.txt","deletedAt":1,"size":1,"isDir":false}"""
        // A json whose content is gone, with or without its folder.
        write(binFolder, "gone.json", meta)
        Files.createDirectories(binFolder.resolve("emptied"))
        write(binFolder, "emptied.json", meta)
        // A folder a crash left empty, before its json was written.
        Files.createDirectories(binFolder.resolve("hollow"))
        // Content whose json is gone.
        val loose = write(binFolder, "loose/y.txt", "y")

        val items = bin().list(listOf(internal))

        assertEquals(listOf(BinItem("loose", internal, loose, "y.txt", null, modified(loose))), items)
        assertEquals(mapOf("loose/" to "", "loose/y.txt" to "y"), tree(binFolder))
    }

    @Test
    fun corruptJsonTreatedAsUnknown() {
        val binFolder = binOf(internal)
        val x = write(binFolder, "c1/x.txt", "x")
        write(binFolder, "c1.json", "{not json")
        val y = write(binFolder, "c2/y.txt", "y")
        write(binFolder, "c2.json", """{"v":1,"originalPath":"$internal/a/y.txt"}""")
        val z = write(binFolder, "c3/z.txt", "z")
        // A version this app does not know.
        val future = """{"v":2,"originalPath":"$internal/a/z.txt","deletedAt":1,"size":1,"isDir":false}"""
        write(binFolder, "c3.json", future)
        val bin = bin()

        val items = bin.list(listOf(internal)).sortedBy { it.id }

        assertEquals(listOf(x, y, z), items.map { it.payload })
        assertEquals(listOf(null, null, null), items.map { it.meta })
        assertEquals(listOf(null, null, null), items.map(bin::originalLocation))
        // Restored to the volume's root, under their own names.
        assertEquals(listOf("x.txt", "y.txt", "z.txt").map(internal::resolve), items.map(bin::restoreTarget))
        // Content is still there, so its json is not deleted.
        assertTrue(Files.exists(binFolder.resolve("c1.json")))
    }

    @Test
    fun originalLocationValidated() {
        val bin = bin()
        fun item(originalPath: String, name: String = "x.txt") = BinItem(
            "b1",
            internal,
            binOf(internal).resolve("b1/$name"),
            name,
            BinMeta(originalPath = originalPath, deletedAt = DELETED_AT, size = 1, isDir = false),
            modified = 0,
        )

        assertEquals(internal.resolve("a/x.txt"), bin.originalLocation(item("$internal/a/x.txt")))
        assertEquals(internal.resolve("x.txt"), bin.originalLocation(item("$internal/x.txt")))
        val invalid = listOf(
            "$internal/a/../x.txt",
            "$internal/a/./x.txt",
            "a/x.txt",
            "",
            "$sd/x.txt",
            "/elsewhere/x.txt",
            "$internal/Android/data/com.example/x.txt",
            "$internal/android/OBB/com.example/x.txt",
            "$internal/.Recycle.Bin/b2/x.txt",
            "$internal/a/y.txt",
            "$internal",
        )
        for (originalPath in invalid) {
            val unknown = item(originalPath)
            assertNull(originalPath, bin.originalLocation(unknown))
            assertEquals(originalPath, internal.resolve("x.txt"), bin.restoreTarget(unknown))
        }
        assertNull(bin.originalLocation(item("$internal/a/x.txt").copy(meta = null)))
    }

    @Test
    fun cannotBinTheBinOrItsContent() {
        val bin = bin()
        val item = bin.move(write(internal, "x.txt"))
        val binFolder = binOf(internal)
        assertEquals(binFolder, bin.binDir(internal))

        val refused = listOf(
            binFolder,
            internal.resolve(".recycle.bin"),
            item.payload.parent,
            item.payload,
            binFolder.resolve("b1.json"),
            internal,
        )
        for (path in refused) {
            val e = assertThrows(path.toString(), FsException::class.java) { bin.move(path) }
            assertEquals(path.toString(), FsError.BinUnavailable, e.error)
        }
        assertEquals(listOf(item), bin.list(listOf(internal)))
        assertEquals(mapOf("b1/" to "", "b1/x.txt" to "x.txt"), tree(binFolder).filterKeys { !it.endsWith(".json") })
    }

    @Test
    fun binOnReadOnlyVolumeIsUnavailable() {
        val file = write(internal, "x.txt", "x")
        val fs = FaultyFs().apply { refuse(FaultyFs.Op.CREATE_DIRECTORY, "", "Read-only file system") }

        val e = assertThrows(FsException::class.java) { bin(fs).move(file) }

        assertEquals(FsError.BinUnavailable, e.error)
        assertEquals(mapOf("x.txt" to "x"), tree(internal))
    }

    @Test
    fun failedMoveLeavesNothingInTheBin() {
        val file = write(internal, "x.txt", "x")
        val fs = FaultyFs().apply { refuse(FaultyFs.Op.ATOMIC_MOVE, "/x.txt", "Permission denied") }

        val e = assertThrows(FsException::class.java) { bin(fs).move(file) }

        assertEquals(FsError.BinUnavailable, e.error)
        // What was made for it is deleted again, its folder before its json.
        assertEquals(mapOf(".Recycle.Bin/" to "", "x.txt" to "x"), tree(internal))
        val deleted = fs.calls.filter { it.op == FaultyFs.Op.DELETE }.map { it.path }
        assertEquals(listOf(binOf(internal).resolve("b1"), binOf(internal).resolve("b1.json")), deleted)
    }

    @Test
    fun missingItemIsNotFound() {
        val e = assertThrows(FsException::class.java) { bin().move(internal.resolve("gone.txt")) }

        assertEquals(FsError.NotFound, e.error)
        assertEquals(emptyMap<String, String>(), tree(internal))
    }

    @Test
    fun listDuringMoveLeavesItAlone() {
        val file = write(internal, "x.txt", "x")
        lateinit var bin: RecycleBin
        var seen: List<BinItem>? = null
        // A listing (from another thread, say) just before the item goes in: its json is there, it is not.
        val fs = object : FsBackend by NioFsBackend {
            override fun atomicMove(from: Path, to: Path) {
                if (from == file) seen = bin.list(listOf(internal))
                NioFsBackend.atomicMove(from, to)
            }
        }
        bin = bin(fs)

        val item = bin.move(file)

        assertEquals(emptyList<BinItem>(), seen)
        assertEquals(listOf(item), bin.list(listOf(internal)))
    }

    @Test
    fun totalSizeAddsUpItems() {
        val d = write(internal, "d/a.txt", "aaa").parent
        write(internal, "d/sub/b.txt", "bb")
        val f = write(internal, "f.txt", "f")
        val bin = bin()

        val folderItem = bin.move(d)
        bin.move(f)
        write(binOf(internal), "loose/y.txt", "yyyy")

        val expected = BinMeta(originalPath = d.toString(), deletedAt = DELETED_AT, size = 5, isDir = true)
        assertEquals(expected, folderItem.meta)
        // A folder counts what it holds; an item without its json, its own size.
        assertEquals(3L + 2 + 1 + 4, bin.totalSize(listOf(internal, sd)))
    }

    @Test
    fun crashBetweenStepsReconciles() {
        val content = mapOf("d/f.txt" to "f", "d/sub/g.txt" to "g", "d/zero.txt" to "")
        val whole = content + mapOf("d/" to "", "d/empty/" to "", "d/sub/" to "")

        /** Moves `d` into the bin of a fresh volume, crashing at [crashAt] (null: none); the volume and the steps. */
        fun moveOnce(crashAt: Int?): Pair<Path, Int> {
            val root = folder.newFolder().toPath().toRealPath()
            for ((relative, text) in content) write(root, relative, text)
            Files.createDirectory(root.resolve("d/empty"))
            val fs = FaultyFs().apply { crashAtStep = crashAt }
            val lookup = volumesOf(VolumeRoot("primary", root, VolumeTraits(), removable = false))
            val bin = RecycleBin(fs, lookup, clock = { DELETED_AT }, ids = { "b1" })
            if (crashAt == null) {
                bin.move(root.resolve("d"))
            } else {
                assertThrows(SimulatedCrash::class.java) { bin.move(root.resolve("d")) }
            }
            return root to fs.steps
        }

        // The bin and the item's folder made, the json written (create, write, flush, rename), the item moved in.
        val steps = moveOnce(crashAt = null).second
        assertEquals(7, steps)
        for (k in 1..steps + 1) {
            val what = if (k <= steps) "crash at step $k" else "no crash"
            val root = moveOnce(crashAt = k.takeIf { it <= steps }).first
            // After a restart: a new bin, with nothing in flight.
            val lookup = volumesOf(VolumeRoot("primary", root, VolumeTraits(), removable = false))
            val items = bin(NioFsBackend, lookup).list(listOf(root))

            val binFolder = binOf(root)
            val inBin = if (Files.exists(binFolder)) tree(binFolder) else emptyMap()
            if (items.isEmpty()) {
                assertEquals(what, whole, outsideBin(root))
                assertEquals(what, emptyMap<String, String>(), inBin)
            } else {
                assertEquals(what, root.resolve("d").toString(), items.single().meta?.originalPath)
                assertEquals(what, emptyMap<String, String>(), outsideBin(root))
                assertEquals(what, whole, tree(binFolder.resolve("b1")))
                assertEquals(what, setOf("b1.json", "b1/") + whole.keys.map { "b1/$it" }, inBin.keys)
            }
        }
    }
}
