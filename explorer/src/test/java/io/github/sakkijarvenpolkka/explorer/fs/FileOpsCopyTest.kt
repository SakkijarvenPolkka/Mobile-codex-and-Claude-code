// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class FileOpsCopyTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val src: Path by lazy { folder.newFolder("src").toPath() }
    private val dst: Path by lazy { folder.newFolder("dst").toPath() }

    private val journal = MemoryJournal()
    private val time = TestTimeSource()

    private fun ops(fs: FsBackend = NioFsBackend, volumes: VolumeLookup = VolumeLookup { null }) =
        FileOps(fs, volumes, KO_LABELS, journal, time, ids = { "r1" })

    private fun copy(vararg sources: Path, into: Path = dst) = spec(OpKind.COPY, sources.toList(), into)

    private fun done(created: List<Path>, failed: List<ItemFailure> = emptyList()) = OpResult.Done(created, failed)

    /** A resolver that gives [action] to every conflict, keeping each in [asked]. */
    private fun always(action: ConflictAction, applyToAll: Boolean = false) =
        Answering { Resolution(action, applyToAll) }

    /** A stream that calls [onRead] after each read of [input] with the bytes it returned. */
    private class Watched(input: InputStream, private val onRead: (Int) -> Unit) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also(onRead)
    }

    /** [NioFsBackend], keeping each folder it is asked to list in [listed]. */
    private class Listed : FsBackend by NioFsBackend {
        val listed = ArrayList<Path>()

        override fun list(dir: Path): List<Path> = NioFsBackend.list(dir).also { listed.add(dir) }
    }

    @Test
    fun copiesFilesAndDirsWithProgress() {
        val a = write(src, "a.txt", "hello")
        val d = write(src, "d/b.txt", "world!").parent
        write(src, "d/e/c.txt", "xyz")
        val reports = ArrayList<OpProgress>()

        val result = ops().runToEnd(copy(a, d), onProgress = { reports += it })

        assertEquals(done(listOf(dst.resolve("a.txt"), dst.resolve("d"))), result)
        assertEquals(tree(src), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
        // The clock stood still: the first report and the final one only.
        assertEquals(
            listOf(OpProgress(14, 0, 5, 0, null), OpProgress(14, 14, 5, 5, "c.txt")),
            reports,
        )

        // With time going on, reports come at most once in 100 ms, then the final one.
        val reported = ArrayList<Pair<Duration, OpProgress>>()
        val start = time.markNow()
        val fs = FaultyFs().apply {
            wrapRead("") { input -> Watched(input) { time += 40.milliseconds } }
        }
        val again = folder.newFolder("again").toPath()
        ops(fs).runToEnd(copy(a, d, into = again), onProgress = { reported += start.elapsedNow() to it })
        assertTrue(reported.size > 2)
        for ((earlier, later) in reported.dropLast(1).zipWithNext()) {
            assertTrue("$earlier then $later", later.first - earlier.first >= 100.milliseconds)
        }
        val last = reported.last().second
        assertEquals(last.totalBytes, last.doneBytes)
        assertEquals(last.totalItems, last.doneItems)
    }

    @Test
    fun reportsEachTopLevelSourceDone() {
        val a = write(src, "a.txt")
        val d = write(src, "d/b.txt").parent
        val committed = ArrayList<Int>()

        runBlocking { ops().run(copy(a, d), NeverAsked, {}) { committed += it } }

        assertEquals(listOf(1, 2), committed)
    }

    @Test
    fun targetMustBeAFolder() {
        val d = write(src, "d/a.txt").parent
        val file = write(dst, "file.txt")
        val fs = Listed()

        // Refused before anything is walked, so nothing is counted.
        assertEquals(OpResult.Failed(FsError.NotFound, 0, 0), ops(fs).runToEnd(copy(d, into = file)))
        assertEquals(OpResult.Failed(FsError.NotFound, 0, 0), ops(fs).runToEnd(copy(d, into = dst.resolve("gone"))))
        assertEquals(emptyList<Path>(), fs.listed)
        assertEquals(mapOf("file.txt" to "file.txt"), tree(dst))
    }

    @Test
    fun resolverOrProgressFailureEndsTheRun() {
        val a = write(src, "a.txt", "new")
        val b = write(src, "b.txt", "b")
        write(dst, "a.txt", "old")
        val broken = ConflictResolver { throw IllegalStateException("broken resolver") }

        // A bug of the caller's is not an item that failed: the run stops with it, and b.txt is not copied.
        val thrown = assertThrows(IllegalStateException::class.java) { ops().runToEnd(copy(a, b), broken) }
        assertEquals("broken resolver", thrown.message)
        assertEquals(mapOf("a.txt" to "old"), tree(dst))

        // Thrown while a file is written, it leaves no part behind.
        var reports = 0
        val fs = FaultyFs().apply { wrapRead("b.txt") { input -> Watched(input) { time += 200.milliseconds } } }
        val onProgress = { _: OpProgress -> if (++reports > 1) throw IllegalStateException("broken progress") }
        val again = assertThrows(IllegalStateException::class.java) {
            ops(fs).runToEnd(copy(b), onProgress = onProgress)
        }
        assertEquals("broken progress", again.message)
        assertEquals(mapOf("a.txt" to "old"), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun zipKindsWithoutZipOpsAreUnsupported() {
        val a = write(src, "a.txt")
        // ops() has no ZipOps; ZipTest runs them with one.
        for (kind in listOf(OpKind.COMPRESS, OpKind.EXTRACT)) {
            val result = ops().runToEnd(spec(kind, listOf(a), dst))
            assertEquals("$kind", OpResult.Failed(FsError.Unsupported, 0, 0), result)
        }
        assertEquals(mapOf("a.txt" to "a.txt"), tree(src))
    }

    @Test
    fun copiesEmptyFileAndEmptyDir() {
        val empty = Files.write(src.resolve("empty.txt"), ByteArray(0))
        val emptyDir = Files.createDirectory(src.resolve("nothing"))

        val result = ops().runToEnd(copy(empty, emptyDir))

        assertEquals(done(listOf(dst.resolve("empty.txt"), dst.resolve("nothing"))), result)
        assertEquals(mapOf("empty.txt" to "", "nothing/" to ""), tree(dst))
        assertTrue(Files.isRegularFile(dst.resolve("empty.txt")))
        assertEquals(0L, Files.size(dst.resolve("empty.txt")))
    }

    @Test
    fun sameFolderCopyMakesCopyOfWithoutPrompt() {
        val bytes = ByteArray(300) { (it * 7).toByte() }
        val photo = Files.write(src.resolve("사진.jpg"), bytes)
        write(src, "앨범/1.jpg", "one")
        val album = src.resolve("앨범")

        // Pasted where they are: copies named "- 복사본", never a question (so never "replace").
        val first = ops().runToEnd(copy(photo, album, into = src))
        val second = ops().runToEnd(copy(photo, into = src))

        assertEquals(done(listOf(src.resolve("사진 - 복사본.jpg"), src.resolve("앨범 - 복사본"))), first)
        assertEquals(done(listOf(src.resolve("사진 - 복사본 (2).jpg"))), second)
        assertArrayEquals(bytes, Files.readAllBytes(photo))
        assertArrayEquals(bytes, Files.readAllBytes(src.resolve("사진 - 복사본.jpg")))
        assertArrayEquals(bytes, Files.readAllBytes(src.resolve("사진 - 복사본 (2).jpg")))
        assertEquals("one", tree(src)["앨범 - 복사본/1.jpg"])
        assertEquals("one", tree(src)["앨범/1.jpg"])
    }

    @Test
    fun fileConflictReplaceSkipKeepBoth() {
        fun setUp(): Pair<Path, Path> {
            val from = folder.newFolder().toPath()
            val to = folder.newFolder().toPath()
            return write(from, "a.txt", "new") to write(to, "a.txt", "old")
        }

        // Replace: the new file takes the name, flushed since it replaces one.
        val (a1, old1) = setUp()
        val fs = FaultyFs()
        val replace = always(ConflictAction.REPLACE)
        assertEquals(done(listOf(old1)), ops(fs).runToEnd(copy(a1, into = old1.parent), replace))
        assertEquals(mapOf("a.txt" to "new"), tree(old1.parent))
        assertTrue(fs.calls.any { it.op == FaultyFs.Op.FORCE })
        val conflict = replace.asked.single()
        assertEquals(ConflictKind.FILE_FILE, conflict.kind)
        assertEquals(a1, conflict.source.path)
        assertEquals(old1, conflict.existing.path)

        // Skip: nothing changes.
        val (a2, old2) = setUp()
        assertEquals(done(emptyList()), ops().runToEnd(copy(a2, into = old2.parent), always(ConflictAction.SKIP)))
        assertEquals(mapOf("a.txt" to "old"), tree(old2.parent))

        // Keep both: the copy is numbered.
        val (a3, old3) = setUp()
        val keep = ops().runToEnd(copy(a3, into = old3.parent), always(ConflictAction.KEEP_BOTH))
        assertEquals(done(listOf(old3.resolveSibling("a (2).txt"))), keep)
        assertEquals(mapOf("a (2).txt" to "new", "a.txt" to "old"), tree(old3.parent))

        for (source in listOf(a1, a2, a3)) assertEquals("new", String(Files.readAllBytes(source)))
    }

    @Test
    fun failedReplaceKeepsTheOldFile() {
        val a = write(src, "a.txt", "new content")
        val old = write(dst, "a.txt", "old")
        val fs = FaultyFs().apply { failAt(".a.txt.$OP_ID.xpart", 3, "No space left on device") }

        val result = ops(fs).runToEnd(copy(a), always(ConflictAction.REPLACE))

        // The file being replaced stays as it was until the new one is complete.
        assertEquals(OpResult.Failed(FsError.NoSpace, 0, 1), result)
        assertEquals(mapOf("a.txt" to "old"), tree(dst))
        assertEquals("old", String(Files.readAllBytes(old)))
        assertEquals("new content", String(Files.readAllBytes(a)))
    }

    @Test
    fun applyToAllAsksOncePerKind() {
        val sources = listOf("a.txt", "b.txt", "c.txt").map { write(src, it, "new $it") }
        write(src, "d/x.txt", "new x")
        for (name in listOf("a.txt", "b.txt", "c.txt", "d/x.txt")) write(dst, name, "old")
        val resolver = Answering { c ->
            when (c.kind) {
                ConflictKind.FILE_FILE -> Resolution(ConflictAction.REPLACE, applyToAll = true)
                else -> Resolution(ConflictAction.MERGE)
            }
        }

        val result = ops().runToEnd(spec(OpKind.COPY, sources + listOf(src.resolve("d")), dst), resolver)

        // a.txt asks for every file, d for itself; b.txt, c.txt and d/x.txt are not asked about.
        assertEquals(listOf(ConflictKind.FILE_FILE, ConflictKind.DIR_DIR), resolver.asked.map { it.kind })
        assertEquals(listOf("a.txt", "d"), resolver.asked.map { it.source.name })
        assertTrue(result is OpResult.Done)
        assertEquals(tree(src), tree(dst))

        // What was given to all is remembered for that operation only.
        val next = Answering { Resolution(ConflictAction.SKIP) }
        ops().runToEnd(spec(OpKind.COPY, sources, dst), next)
        assertEquals(3, next.asked.size)
    }

    @Test
    fun dirDirMergeKeepsTargetOnlyFiles() {
        write(src, "d/a.txt", "new a")
        write(src, "d/b.txt", "b")
        write(src, "d/sub/c.txt", "c")
        write(dst, "d/a.txt", "old a")
        write(dst, "d/only.txt", "only")
        val key = NioFsBackend.attributes(dst.resolve("d")).fileKey()
        val resolver = Answering { c ->
            Resolution(if (c.kind == ConflictKind.DIR_DIR) ConflictAction.MERGE else ConflictAction.SKIP)
        }

        val result = ops().runToEnd(copy(src.resolve("d")), resolver)

        assertEquals(done(listOf(dst.resolve("d"))), result)
        assertEquals(
            mapOf(
                "d/" to "",
                "d/a.txt" to "old a",
                "d/b.txt" to "b",
                "d/only.txt" to "only",
                "d/sub/" to "",
                "d/sub/c.txt" to "c",
            ),
            tree(dst),
        )
        // Merged into, never replaced.
        assertEquals(key, NioFsBackend.attributes(dst.resolve("d")).fileKey())
        assertEquals(listOf(ConflictKind.DIR_DIR, ConflictKind.FILE_FILE), resolver.asked.map { it.kind })
    }

    @Test
    fun fileDirOffersOnlySkipKeepBoth() {
        assertEquals(listOf(ConflictAction.SKIP, ConflictAction.KEEP_BOTH), allowedActions(ConflictKind.FILE_DIR))
        assertEquals(listOf(ConflictAction.SKIP, ConflictAction.KEEP_BOTH), allowedActions(ConflictKind.DIR_FILE))
        val file = write(src, "x", "file x")
        write(dst, "x/inner.txt", "inner")
        val dir = write(src, "y/in.txt", "in").parent
        write(dst, "y", "file y")
        val before = tree(dst)

        // Replacing a folder by a file, or a file by a folder, is not offered: such an answer skips.
        val replace = always(ConflictAction.REPLACE)
        assertEquals(done(emptyList()), ops().runToEnd(copy(file, dir), replace))
        assertEquals(listOf(ConflictKind.FILE_DIR, ConflictKind.DIR_FILE), replace.asked.map { it.kind })
        assertEquals(before, tree(dst))
        // Nor merging a file.
        assertEquals(done(emptyList()), ops().runToEnd(copy(file), always(ConflictAction.MERGE)))
        assertEquals(before, tree(dst))

        val keep = ops().runToEnd(copy(file, dir), always(ConflictAction.KEEP_BOTH))
        assertEquals(done(listOf(dst.resolve("x (2)"), dst.resolve("y (2)"))), keep)
        assertEquals(before + mapOf("x (2)" to "file x", "y (2)/" to "", "y (2)/in.txt" to "in"), tree(dst))
    }

    @Test
    fun refusesCopyIntoItself() {
        val a = write(src, "a/b/c.txt", "c").parent.parent
        val before = tree(src)
        val fs = Listed()

        for (target in listOf(a.resolve("b"), a)) {
            for (kind in listOf(OpKind.COPY, OpKind.MOVE)) {
                val result = ops(fs).runToEnd(spec(kind, listOf(a), target))
                assertEquals("$kind into $target", OpResult.Failed(FsError.CrossesIntoSelf, 0, 0), result)
            }
        }
        assertEquals(before, tree(src))
        // Refused on the sources themselves, before walking what is in them.
        assertEquals(emptyList<Path>(), fs.listed)
    }

    @Test
    fun refusesIntoSelfViaSymlinkAlias() {
        val a = write(src, "a/b/c.txt", "c").parent.parent
        val link = Files.createSymbolicLink(src.resolve("link"), a)
        val before = tree(src)

        // /link/b is /a/b: the walk up from the target goes through /a, not /link.
        val result = ops().runToEnd(copy(a, into = link.resolve("b")))

        assertEquals(OpResult.Failed(FsError.CrossesIntoSelf, 0, 0), result)
        assertEquals(before, tree(src))
    }

    @Test
    fun copiesGrowingSourceAsRead() {
        val grows = write(src, "grows.log", "abc")
        var read = 0
        var appended = false
        val fs = FaultyFs().apply {
            wrapRead("grows.log") { input ->
                Watched(input) { n ->
                    if (n > 0) read += n
                    if (!appended) {
                        appended = true
                        Files.write(grows, "more".toByteArray(), StandardOpenOption.APPEND)
                    }
                }
            }
        }
        val reports = ArrayList<OpProgress>()

        val result = ops(fs).runToEnd(copy(grows), onProgress = { reports += it })

        assertEquals(done(listOf(dst.resolve("grows.log"))), result)
        assertEquals(read.toLong(), Files.size(dst.resolve("grows.log")))
        assertEquals("abcmore".substring(0, read), String(Files.readAllBytes(dst.resolve("grows.log"))))
        // Progress counts the size the file had when the operation was planned.
        assertTrue(reports.all { it.doneBytes <= it.totalBytes })
        assertEquals(3L, reports.last().doneBytes)
    }

    @Test
    fun missingSourceIsPerItemFailure() {
        val a = write(src, "a.txt")
        val b = write(src, "b.txt")
        val c = write(src, "c.txt")
        // c.txt goes once the operation has planned it, as a.txt is read.
        val fs = FaultyFs().apply { wrapRead("a.txt") { input -> input.also { Files.delete(c) } } }

        val result = ops(fs).runToEnd(copy(a, b, c))

        val missing = ItemFailure(c.toString(), FsError.NotFound, into = dst.toString())
        assertEquals(done(listOf(dst.resolve("a.txt"), dst.resolve("b.txt")), listOf(missing)), result)
        assertEquals(mapOf("a.txt" to "a.txt", "b.txt" to "b.txt"), tree(dst))
    }

    @Test
    fun failureNamesTheFolderItWasGoingInto() {
        val d = write(src, "d/a/b.txt", "b").parent.parent
        val e = write(src, "e/f.txt", "f").parent
        write(dst, "e", "file e")
        // Neither b.txt nor f.txt can be written.
        val fs = FaultyFs().apply {
            failAt(".b.txt.$OP_ID.xpart", 0, "Permission denied")
            failAt(".f.txt.$OP_ID.xpart", 0, "Permission denied")
        }

        // Pasted into its own folder, d is copied as "d - 복사본"; e, kept beside the file e, as "e (2)".
        val inPlace = ops(fs).runToEnd(copy(d, into = src))
        val besides = ops(fs).runToEnd(copy(e), always(ConflictAction.KEEP_BOTH))

        val copyOfD = src.resolve("d - 복사본")
        assertEquals(
            done(listOf(copyOfD), listOf(ItemFailure("$d/a/b.txt", FsError.AccessDenied, into = "$copyOfD/a"))),
            inPlace,
        )
        val keptE = dst.resolve("e (2)")
        val fFailed = ItemFailure("$e/f.txt", FsError.AccessDenied, into = "$keptE")
        assertEquals(done(listOf(keptE), listOf(fFailed)), besides)
    }

    @Test
    fun noSpaceIsFatalAndKeepsSources() {
        val sources = listOf("a.txt", "b.txt", "c.txt").map { write(src, it, "0123456789") }
        val before = tree(src)
        val fs = FaultyFs().apply { failAt(".b.txt.$OP_ID.xpart", 4, "No space left on device") }

        val result = ops(fs).runToEnd(copy(*sources.toTypedArray()))

        // It stops at once: a.txt is copied, b.txt's part deleted, c.txt not started.
        assertEquals(OpResult.Failed(FsError.NoSpace, 1, 3), result)
        assertEquals(before, tree(src))
        assertEquals(mapOf("a.txt" to "0123456789"), tree(dst))
        assertEquals(emptyList<Path>(), partsUnder(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun insufficientSpacePrecheck() {
        val a = Files.write(src.resolve("a.bin"), ByteArray(1000))
        val d = Files.createDirectory(src.resolve("d"))
        Files.write(d.resolve("b.bin"), ByteArray(500))
        var available = 0L
        val fs = object : FsBackend by NioFsBackend {
            override fun availableBytes(path: Path): Long = available
        }
        val margin = 64L shl 20

        available = margin + 1499
        assertEquals(Precheck(emptyList(), 1500, margin + 1499), runBlocking { ops(fs).precheck(copy(a, d)) })
        // Less than the files and 64 MiB to spare: nothing is written.
        assertEquals(OpResult.Failed(FsError.NoSpace, 0, 3), ops(fs).runToEnd(copy(a, d)))
        assertEquals(emptyMap<String, String>(), tree(dst))

        available = margin + 1500
        assertTrue(ops(fs).runToEnd(copy(a, d)) is OpResult.Done)
        assertEquals(1000L, Files.size(dst.resolve("a.bin")))

        // A volume that cannot tell (0) does not stop the copy.
        available = 0
        val unknown = folder.newFolder("unknown").toPath()
        assertTrue(ops(fs).runToEnd(copy(a, d, into = unknown)) is OpResult.Done)

        // A move that stays on its volume is a rename: nothing to copy, nothing to check.
        available = 1
        val one = volumesOf(VolumeRoot("primary", folder.root.toPath(), VolumeTraits(), removable = false))
        val moved = folder.newFolder("moved").toPath()
        val rename = spec(OpKind.MOVE, listOf(a), moved)
        assertEquals(Precheck(emptyList(), 0, 1), runBlocking { ops(fs, one).precheck(rename) })
        assertTrue(ops(fs, one).runToEnd(rename) is OpResult.Done)
        // Across volumes, what moves is copied, and checked.
        val two = volumesOf(
            VolumeRoot("primary", src, VolumeTraits(), removable = false),
            VolumeRoot("0000-0001", unknown, VolumeTraits(), removable = false),
        )
        val across = spec(OpKind.MOVE, listOf(d), unknown)
        assertEquals(Precheck(emptyList(), 500, 1), runBlocking { ops(fs, two).precheck(across) })
    }

    @Test
    fun removableTargetPrecheckFlagsFatNamesAndHugeFiles() {
        val internal = src
        val sd = dst
        val volumes = volumesOf(
            VolumeRoot("primary", internal, VolumeTraits(), removable = false),
            VolumeRoot("1234-5678", sd, VolumeTraits(fatNames = true, maxFileSize = 4_294_967_295L), removable = true),
        )
        val colon = write(internal, "a:b.txt", "colon")
        val ok = write(internal, "ok.txt", "ok")
        val huge = write(internal, "huge.bin", "really 4 GiB")
        val edge = write(internal, "edge.bin", "just under 4 GiB")
        val dir = write(internal, "d/q?.txt", "q").parent
        write(internal, "d/fine.txt", "fine")
        val faulty = FaultyFs().apply {
            fakeSize(huge, 4_294_967_296L)
            fakeSize(edge, 4_294_967_295L)
        }
        // Room for the 4 GiB the sizes claim, whatever the disk the test runs on has.
        val fs = object : FsBackend by faulty {
            override fun availableBytes(path: Path): Long = 1L shl 40
        }
        val spec = copy(colon, ok, huge, edge, dir, into = sd)
        val unstorable = listOf(
            ItemFailure(colon.toString(), FsError.NameInvalid(NameProblem.FatChar(':'))),
            ItemFailure(huge.toString(), FsError.TooLargeForVolume),
            ItemFailure(dir.resolve("q?.txt").toString(), FsError.NameInvalid(NameProblem.FatChar('?'))),
        )

        val precheck = runBlocking { ops(fs, volumes).precheck(spec) }
        assertEquals(unstorable, precheck.unstorable)
        assertEquals(2L + 4_294_967_295L + 4L, precheck.copyBytes)

        val result = ops(fs, volumes).runToEnd(spec)

        // What the drive cannot store is not written, and fails; the rest is copied.
        val (colonFailed, hugeFailed, qFailed) = unstorable
        val failed = listOf(colonFailed.copy(into = "$sd"), hugeFailed.copy(into = "$sd"), qFailed.copy(into = "$sd/d"))
        assertEquals(done(listOf(sd.resolve("ok.txt"), sd.resolve("edge.bin"), sd.resolve("d")), failed), result)
        assertEquals(
            mapOf("d/" to "", "d/fine.txt" to "fine", "edge.bin" to "just under 4 GiB", "ok.txt" to "ok"),
            tree(sd),
        )

        // Internal storage takes them all.
        val elsewhere = folder.newFolder().toPath()
        val toInternal = runBlocking { ops(fs, volumes).precheck(copy(colon, huge, into = elsewhere)) }
        assertEquals(emptyList<ItemFailure>(), toInternal.unstorable)
    }

    @Test
    fun removableTargetWithoutKnownSizeLimitWritesHugeFiles() {
        val internal = src
        val usb = dst
        // exFAT, or a file system that could not be told: FAT names, but no size limit.
        val volumes = volumesOf(
            VolumeRoot("primary", internal, VolumeTraits(), removable = false),
            VolumeRoot("5E6F-7A8B", usb, VolumeTraits(fatNames = true), removable = true),
        )
        val huge = write(internal, "huge.bin", "really 5 GiB")
        val ok = write(internal, "ok.txt", "ok")
        val faulty = FaultyFs().apply { fakeSize(huge, 5L shl 30) }
        val fs = object : FsBackend by faulty {
            override fun availableBytes(path: Path): Long = 1L shl 40
        }
        val spec = copy(huge, ok, into = usb)

        val precheck = runBlocking { ops(fs, volumes).precheck(spec) }
        assertEquals(Precheck(emptyList(), (5L shl 30) + 2, 1L shl 40), precheck)
        assertEquals(done(listOf(usb.resolve("huge.bin"), usb.resolve("ok.txt"))), ops(fs, volumes).runToEnd(spec))
        assertEquals(mapOf("huge.bin" to "really 5 GiB", "ok.txt" to "ok"), tree(usb))

        // If the volume turns out not to hold it (FAT32 not told apart), that file fails, and only it.
        val fat = folder.newFolder().toPath()
        faulty.failAt(".huge.bin.$OP_ID.xpart", 4, "File too large")
        val onFat = volumesOf(VolumeRoot("1234-5678", fat, VolumeTraits(fatNames = true), removable = true))
        val result = ops(fs, onFat).runToEnd(copy(huge, ok, into = fat))
        assertEquals(
            done(listOf(fat.resolve("ok.txt")), listOf(ItemFailure("$huge", FsError.TooLargeForVolume, into = "$fat"))),
            result,
        )
        assertEquals(mapOf("ok.txt" to "ok"), tree(fat))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun symlinksAndSpecialFilesSkippedAsUnsupported() {
        val real = write(src, "real.txt", "real")
        val link = Files.createSymbolicLink(src.resolve("link.txt"), real)
        val d = write(src, "d/inner.txt", "inner").parent
        val dirLink = Files.createSymbolicLink(d.resolve("up"), src)

        val result = ops().runToEnd(copy(link, d))

        // A link is not followed, to a file or to a folder (here one that holds the copy).
        val links = listOf(
            ItemFailure(link.toString(), FsError.Unsupported, into = "$dst"),
            ItemFailure(dirLink.toString(), FsError.Unsupported, into = "$dst/d"),
        )
        assertEquals(done(listOf(dst.resolve("d")), links), result)
        assertEquals(mapOf("d/" to "", "d/inner.txt" to "inner"), tree(dst))

        // A pipe is never opened, so it cannot block the copy.
        val p = Files.createDirectory(src.resolve("p"))
        val fifo = p.resolve("pipe")
        val made = try {
            ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0
        } catch (e: IOException) {
            false
        }
        assumeTrue("mkfifo is needed for a special file", made)
        val special = ops().runToEnd(copy(p))
        val unsupported = ItemFailure(fifo.toString(), FsError.Unsupported, into = "$dst/p")
        assertEquals(done(listOf(dst.resolve("p")), listOf(unsupported)), special)
        assertEquals(mapOf("d/" to "", "d/inner.txt" to "inner", "p/" to ""), tree(dst))
    }

    @Test
    fun danglingLinkInTargetIsAConflict() {
        val a = write(src, "a.txt", "a")
        val nowhere = folder.root.toPath().resolve("nowhere")
        Files.createSymbolicLink(dst.resolve("a.txt"), nowhere)

        // The link has the name: asked about like any other item, and kept when both are.
        val keep = always(ConflictAction.KEEP_BOTH)
        assertEquals(done(listOf(dst.resolve("a (2).txt"))), ops().runToEnd(copy(a), keep))
        assertEquals(ConflictKind.FILE_FILE, keep.asked.single().kind)
        assertEquals(EntryKind.SYMLINK, keep.asked.single().existing.kind)
        assertEquals(mapOf("a (2).txt" to "a", "a.txt" to "-> $nowhere"), tree(dst))

        // Replaced, it is the link that goes, not what it points to.
        assertEquals(done(listOf(dst.resolve("a.txt"))), ops().runToEnd(copy(a), always(ConflictAction.REPLACE)))
        assertEquals(mapOf("a (2).txt" to "a", "a.txt" to "a"), tree(dst))
        assertFalse(Files.exists(nowhere))
    }

    @Test
    fun preservesModifiedTime() {
        val file = write(src, "d/f.txt", "f")
        val top = write(src, "top.txt", "top")
        val d = file.parent
        Files.setLastModifiedTime(file, FileTime.fromMillis(FILE_TIME))
        Files.setLastModifiedTime(top, FileTime.fromMillis(TOP_TIME))
        Files.setLastModifiedTime(d, FileTime.fromMillis(DIR_TIME))

        ops().runToEnd(copy(d, top))

        assertEquals(FILE_TIME, Files.getLastModifiedTime(dst.resolve("d/f.txt")).toMillis())
        assertEquals(TOP_TIME, Files.getLastModifiedTime(dst.resolve("top.txt")).toMillis())
        // A folder's time is set once everything in it is written.
        assertEquals(DIR_TIME, Files.getLastModifiedTime(dst.resolve("d")).toMillis())
    }

    @Test
    fun cancelMidFileLeavesNoPartial() {
        val a = write(src, "a.txt", "aaa")
        val b = write(src, "b.txt", "bbbbbbbb")
        lateinit var job: Job
        val fs = FaultyFs().apply { wrapRead("b.txt") { input -> Watched(input) { job.cancel() } } }
        var finished = false

        runBlocking {
            job = launch {
                ops(fs).run(copy(a, b), NeverAsked, {})
                finished = true
            }
            job.join()
        }

        assertFalse(finished)
        assertTrue(job.isCancelled)
        // a.txt was done; of b.txt, neither a part nor a cut file is left.
        assertEquals(mapOf("a.txt" to "aaa"), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
        assertEquals(mapOf("a.txt" to "aaa", "b.txt" to "bbbbbbbb"), tree(src))
    }

    private companion object {
        const val FILE_TIME = 1_500_000_000_000L
        const val TOP_TIME = 1_550_000_000_000L
        const val DIR_TIME = 1_600_000_000_000L
    }
}
