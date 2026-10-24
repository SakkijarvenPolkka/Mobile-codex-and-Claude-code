// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ZipTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val src: Path by lazy { folder.newFolder("src").toPath() }
    private val dst: Path by lazy { folder.newFolder("dst").toPath() }

    private val journal = MemoryJournal()

    private fun zipOps(fs: FsBackend = NioFsBackend) = ZipOps(fs, WriteProtocol(fs, journal), KO_LABELS)

    private fun fileOps(fs: FsBackend = NioFsBackend) =
        FileOps(fs, VolumeLookup { null }, KO_LABELS, journal, ids = { "r1" }, zip = zipOps(fs))

    private fun ZipOps.compressNow(vararg sources: Path, into: Path = dst): Path =
        runBlocking { compress(sources.toList(), into, OP_ID) {} }

    private fun ZipOps.extractNow(zip: Path, into: Path = dst, charset: Charset? = null): ExtractResult =
        runBlocking { extract(zip, into, charset, OP_ID) {} }

    /** Extracting [zips] into [dst]. */
    private fun extracting(vararg zips: Path, options: OpOptions = OpOptions()) =
        OperationSpec(OP_ID, OpKind.EXTRACT, zips.map { it.toString() }, dst.toString(), options)

    /** What [block] throws, which must be an [FsException]. */
    private fun failureOf(block: () -> Unit): FsError = assertThrows(FsException::class.java, block).error

    /** Nothing but the archive is in [dir]: no folder, no temporary one, nothing recorded. */
    private fun assertOnlyTheArchive(zip: Path, dir: Path = zip.parent) {
        assertEquals(setOf(zip.fileName.toString()), tree(dir).keys)
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    private fun mtime(path: Path): Long = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()

    @Test
    fun zipRoundTripKeepsEmptyFileAndDir() {
        write(src, "사진/여름.txt", "바다")
        Files.createDirectories(src.resolve("사진/빈 폴더"))
        Files.write(src.resolve("빈 파일.txt"), ByteArray(0))
        Files.createDirectory(src.resolve("empty"))
        val times = mapOf(
            "사진/여름.txt" to 1_500_000_001_000L,
            "사진/빈 폴더" to 1_500_000_003_000L,
            "사진" to 1_500_000_005_000L,
            "빈 파일.txt" to 1_500_000_007_000L,
            "empty" to 1_500_000_009_000L,
        )
        for ((name, time) in times) Files.setLastModifiedTime(src.resolve(name), FileTime.fromMillis(time))
        val reports = ArrayList<OpProgress>()

        val zip = runBlocking {
            zipOps().compress(listOf("사진", "빈 파일.txt", "empty").map(src::resolve), dst, OP_ID) { reports += it }
        }

        assertEquals(dst.resolve("사진.zip"), zip)
        assertEquals(listOf("사진/", "사진/빈 폴더/", "사진/여름.txt", "빈 파일.txt", "empty/"), entryNames(zip))
        // Every name is in UTF-8, and flagged so (EFS).
        val bytes = Files.readAllBytes(zip)
        assertTrue(centralHeaders(bytes).indices.all { bytes.flags(it) and FLAG_UTF8 != 0 })
        assertEquals(OpProgress(6, 0, 5, 0, null), reports.first())
        assertEquals(OpProgress(6, 6, 5, 5, "empty"), reports.last())

        val result = zipOps().extractNow(zip)

        assertEquals(ExtractResult(dst.resolve("사진"), 0), result)
        assertEquals(tree(src), tree(result.folder))
        for ((name, time) in times) {
            val extracted = mtime(result.folder.resolve(name))
            assertTrue("$name: $extracted vs $time", abs(extracted - time) <= 2000)
        }
        assertEquals(emptyList<Path>(), partsUnder(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun traversalEntryRefusesWholeArchive() {
        val zip = putZip(dst, "x.zip", zipOf("ok.txt" to "ok", "../evil" to "evil"))

        assertEquals(FsError.UnsafeArchive, failureOf { zipOps().extractNow(zip) })

        assertOnlyTheArchive(zip)
        assertFalse(Files.exists(dst.resolveSibling("evil")))
    }

    @Test
    fun absoluteAndBackslashNamesRefused() {
        for (name in listOf("/abs.txt", "\\abs.txt", "..\\evil.txt", "a\\..\\..\\evil.txt", "C:\\x.txt", "c:x.txt")) {
            val zip = putZip(dst, "x.zip", zipOf("ok.txt" to "ok", name to "bad"))

            assertEquals(name, FsError.UnsafeArchive, failureOf { zipOps().extractNow(zip) })

            assertOnlyTheArchive(zip)
        }
    }

    @Test
    fun duplicateAndCaseCollidingNamesKeepBoth() {
        // ZipOutputStream refuses a name twice: the second entry gets the first one's name afterwards.
        val bytes = zipOf("a.txt" to "first", "b.txt" to "second", "A.txt" to "third").apply { rename(1, "a.txt") }
        val zip = putZip(src, "x.zip", bytes)
        val fs = FaultyFs().apply { caseInsensitive = true }

        val result = zipOps(fs).extractNow(zip)

        assertEquals(2, result.renamedDuplicates)
        assertEquals(mapOf("A (3).txt" to "third", "a (2).txt" to "second", "a.txt" to "first"), tree(result.folder))
    }

    @Test
    fun folderAndFileOfOneNameKeepBoth() {
        val zip = putZip(src, "x.zip", zipOf("a" to "file", "a/b.txt" to "b", "c/" to null, "C/d.txt" to "d"))
        val fs = FaultyFs().apply { caseInsensitive = true }

        val result = zipOps(fs).extractNow(zip)

        // The folder a goes elsewhere (one rename); c and C are one folder, on a volume that ignores case.
        assertEquals(1, result.renamedDuplicates)
        assertEquals(
            mapOf("a" to "file", "a (2)/" to "", "a (2)/b.txt" to "b", "c/" to "", "c/d.txt" to "d"),
            tree(result.folder),
        )
    }

    @Test
    fun entryLargerThanDeclaredAborts() {
        val bytes = zipOf("ok.txt" to "ok", "big.txt" to "0123456789".repeat(100))
        // The central directory says big.txt is 10 bytes; it inflates to 1000.
        bytes.put32(centralHeaders(bytes)[1] + 24, 10)
        val zip = putZip(dst, "x.zip", bytes)

        assertEquals(FsError.Corrupt, failureOf { zipOps().extractNow(zip) })

        assertOnlyTheArchive(zip)
    }

    @Test
    fun crcMismatchIsCorrupt() {
        val bytes = zipOf("ok.txt" to "ok", "bad.txt" to "content")
        bytes.put32(centralHeaders(bytes)[1] + 16, 0x12345678)
        val zip = putZip(dst, "x.zip", bytes)

        assertEquals(FsError.Corrupt, failureOf { zipOps().extractNow(zip) })

        assertOnlyTheArchive(zip)
    }

    @Test
    fun damagedDataIsCorrupt() {
        val bytes = zipOf("big.txt" to "0123456789".repeat(100))
        val data = bytes.dataOf(0)
        for (i in data until data + 8) bytes[i] = 0xFF.toByte()
        val zip = putZip(dst, "x.zip", bytes)

        assertEquals(FsError.Corrupt, failureOf { zipOps().extractNow(zip) })

        assertOnlyTheArchive(zip)
    }

    @Test
    fun notAZipIsCorruptNotAnEncoding() {
        val zip = putZip(dst, "x.zip", "not an archive".toByteArray())

        assertEquals(FsError.Corrupt, failureOf { zipOps().detectCharset(zip) })
        assertEquals(FsError.Corrupt, failureOf { zipOps().extractNow(zip) })
        assertEquals(FsError.Corrupt, failureOf { zipOps().extractNow(zip, charset = Charsets.UTF_8) })

        assertOnlyTheArchive(zip)
    }

    @Test
    fun encryptedFixtureIsEncrypted() {
        // An archive with an entry flagged as encrypted (bit 0), as ZipCrypto leaves it, in both headers.
        val bytes = zipOf("a.txt" to "secret").apply { setFlags(0, FLAG_ENCRYPTED) }
        val zip = putZip(dst, "encrypted.zip", bytes)

        assertEquals(FsError.Encrypted, failureOf { zipOps().detectCharset(zip) })
        assertEquals(FsError.Encrypted, failureOf { zipOps().extractNow(zip) })
        assertEquals(FsError.Encrypted, failureOf { zipOps().extractNow(zip, charset = Charsets.UTF_8) })

        assertOnlyTheArchive(zip)
    }

    @Test
    fun extractIntoUniqueFolderNeverMerges() {
        write(dst, "x/old.txt", "old")
        val zip = putZip(src, "x.zip", zipOf("new.txt" to "new"))

        val result = zipOps().extractNow(zip)

        assertEquals(ExtractResult(dst.resolve("x (2)"), 0), result)
        assertEquals(mapOf("x (2)/" to "", "x (2)/new.txt" to "new", "x/" to "", "x/old.txt" to "old"), tree(dst))
    }

    @Test
    fun compressExcludesOutputAndNestedSelection() {
        val d = write(src, "d/x.txt", "x").parent
        write(src, "d/e/y.txt", "y")

        // The folder and a file in it, into the folder itself.
        val zip = zipOps().compressNow(d, d.resolve("x.txt"), into = d)

        assertEquals(d.resolve("d.zip"), zip)
        assertEquals(listOf("d/", "d/e/", "d/e/y.txt", "d/x.txt"), entryNames(zip))
        assertEquals(setOf("d.zip", "e/", "e/y.txt", "x.txt"), tree(d).keys)
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun compressNamesTheArchiveAfterTheFirstItemAndKeepsBoth() {
        val report = write(src, "report.pdf", "pdf")
        val dotted = Files.createDirectory(src.resolve("my.folder"))
        write(dst, "report.zip", "taken")

        assertEquals(dst.resolve("report (2).zip"), zipOps().compressNow(report, dotted))
        // A folder's name is kept whole.
        assertEquals(dst.resolve("my.folder.zip"), zipOps().compressNow(dotted, report))
    }

    @Test
    fun compressKeepsBothOfTwoSourcesWithOneName() {
        // Search results can hold two items of one name, from two folders.
        val first = write(src, "a/x.txt", "first")
        val second = write(src, "b/X.txt", "second")

        val zip = zipOps().compressNow(first, second)

        assertEquals(listOf("x.txt", "X (2).txt"), entryNames(zip))
    }

    @Test
    fun compressSkipsLinks() {
        val a = write(src, "d/a.txt", "a")
        Files.createSymbolicLink(src.resolve("d/link"), a)

        val zip = zipOps().compressNow(src.resolve("d"))

        assertEquals(listOf("d/", "d/a.txt"), entryNames(zip))
    }

    @Test
    fun compressFailureLeavesNoArchive() {
        val a = write(src, "a.txt", "0123456789".repeat(1000))
        val fs = FaultyFs().apply { failAt(".a.zip.$OP_ID.xpart", 10, "No space left on device") }

        assertEquals(FsError.NoSpace, failureOf { zipOps(fs).compressNow(a) })

        assertEquals(emptyMap<String, String>(), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun cancelExtractRemovesTemp() {
        val zip = putZip(src, "x.zip", zipOf("a.txt" to "a", "d/b.txt" to "b", "c.txt" to "c"))
        lateinit var job: Job
        // Cancelled as b.txt is being written, inside the temporary folder.
        val fs = object : FsBackend by NioFsBackend {
            override fun createNew(path: Path): FileChannel =
                NioFsBackend.createNew(path).also { if (path.fileName.toString().startsWith(".b.txt")) job.cancel() }
        }
        var finished = false

        runBlocking {
            job = launch {
                zipOps(fs).extract(zip, dst, null, OP_ID) {}
                finished = true
            }
            job.join()
        }

        assertFalse(finished)
        assertTrue(job.isCancelled)
        assertEquals(emptyMap<String, String>(), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun noSpaceDuringExtractIsFatal() {
        val zip = putZip(src, "x.zip", zipOf("a.txt" to "0123456789", "b.txt" to "0123456789", "c.txt" to "c"))
        val fs = FaultyFs().apply { failAt(".b.txt.$OP_ID.xpart", 3, "No space left on device") }

        assertEquals(FsError.NoSpace, failureOf { zipOps(fs).extractNow(zip) })
        assertEquals(emptyMap<String, String>(), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)

        // As an operation, it ends there: nothing of the archive is kept.
        val result = fileOps(fs).runToEnd(extracting(zip))

        assertEquals(OpResult.Failed(FsError.NoSpace, 0, 3), result)
        assertEquals(emptyMap<String, String>(), tree(dst))
    }

    @Test
    fun crashDuringExtractLeavesOnlyTheRecordedTempFolder() {
        val zip = putZip(src, "x.zip", zipOf("a.txt" to "a", "d/" to null, "d/b.txt" to "b"))
        val probe = FaultyFs()
        zipOps(probe).extractNow(zip, into = folder.newFolder("probe").toPath())
        val temp = ".x.$OP_ID.xpart"

        // The process dies at each change in turn, the last rename included.
        for (step in 1..probe.steps) {
            val into = folder.newFolder("crash$step").toPath()
            journal.recorded.clear()
            val fs = FaultyFs().apply { crashAtStep = step }

            assertThrows(SimulatedCrash::class.java) { zipOps(fs).extractNow(zip, into) }

            // All there is, if anything, is in the temporary folder, and the journal knows of it.
            val left = tree(into).keys
            assertTrue("step $step: $left", left.all { it == "$temp/" || it.startsWith("$temp/") })
            if (left.isNotEmpty()) assertEquals(setOf(into.resolve(temp)), journal.recorded)
        }
    }

    @Test
    fun extractFlushesEachFileOnlyOnRemovableMedia() {
        val zip = putZip(src, "x.zip", zipOf("a.txt" to "a", "d/" to null, "d/b.txt" to "b", "empty.txt" to ""))
        val internal = FaultyFs()

        zipOps(internal).extractNow(zip)

        // Internal storage: renaming the temporary files is enough (spec §4.3).
        assertEquals(emptyList<FaultyFs.Call>(), internal.calls.filter { it.op == FaultyFs.Op.FORCE })

        val card = folder.newFolder("card").toPath()
        val fs = FaultyFs()
        val asked = ArrayList<Path>()
        val volume = VolumeRoot("card", card, VolumeTraits(), removable = true)
        val volumes = VolumeLookup { path ->
            asked.add(path)
            volume.takeIf { path == card }
        }
        val ops = ZipOps(fs, WriteProtocol(fs, journal), KO_LABELS, volumes)

        ops.extractNow(zip, into = card)

        // Removable media: every file, the empty one too, is flushed before it gets its name.
        val temp = card.resolve(tempNameFor("x", OP_ID))
        val forced = fs.calls.filter { it.op == FaultyFs.Op.FORCE }.map { it.path }
        assertEquals(
            listOf(".a.txt", "d/.b.txt", ".empty.txt").map { temp.resolve("$it.$OP_ID.xpart") },
            forced,
        )
        assertEquals(setOf(card), asked.toSet())
    }

    @Test
    fun fatVolumeRefusesNamesItCannotHoldBeforeWriting() {
        val card = folder.newFolder("card").toPath()
        val fat = VolumeRoot("card", card, VolumeTraits(fatNames = true), removable = true)
        val fs = FaultyFs()
        val ops = ZipOps(fs, WriteProtocol(fs, journal), KO_LABELS, volumesOf(fat))
        val refused = mapOf(
            "photo 12:30.jpg" to ':', "d*/x.txt" to '*', "ok/what?.txt" to '?', "\"q\".txt" to '"',
            "<a>.txt" to '<', "dir/pipe|.txt" to '|',
        )

        for ((name, char) in refused) {
            val zip = putZip(src, "x.zip", zipOf("ok.txt" to "ok", name to "bad"))

            // The names FAT refuses refuse the whole archive, as copying them would ("not allowed on the drive").
            assertEquals(name, FsError.NameInvalid(NameProblem.FatChar(char)), failureOf { ops.extractNow(zip, card) })
        }

        // Before anything is written: not a folder, not a byte, nothing recorded.
        assertEquals(emptyList<FaultyFs.Call>(), fs.calls)
        assertEquals(emptyMap<String, String>(), tree(card))
        assertEquals(emptySet<Path>(), journal.recorded)
        // A volume without those rules takes them.
        val zip = putZip(src, "x.zip", zipOf("photo 12:30.jpg" to "fine"))
        assertEquals(mapOf("photo 12:30.jpg" to "fine"), tree(zipOps().extractNow(zip).folder))
    }

    @Test
    fun symlinkEntryBecomesRegularFile() {
        val bytes = zipOf("target.txt" to "t", "link" to "target.txt")
        val central = centralHeaders(bytes)[1]
        // Made by Unix (3), with the mode of a symbolic link (0120777) in the external attributes.
        bytes.put16(central + 4, (3 shl 8) or 20)
        bytes.put32(central + 38, 0xA1FFL shl 16)
        val zip = putZip(src, "x.zip", bytes)

        val link = zipOps().extractNow(zip).folder.resolve("link")

        assertFalse(Files.isSymbolicLink(link))
        assertTrue(Files.isRegularFile(link, LinkOption.NOFOLLOW_LINKS))
        assertEquals("target.txt", String(Files.readAllBytes(link)))
    }

    @Test
    fun fileOpsRunsCompressAndExtractThroughZipOps() {
        val a = write(src, "a.txt", "a")
        val d = write(src, "d/b.txt", "b").parent
        val committed = ArrayList<Int>()
        val ops = fileOps()

        val compressed = runBlocking {
            ops.run(spec(OpKind.COMPRESS, listOf(a, d), dst), NeverAsked, {}) { committed += it }
        }

        assertEquals(OpResult.Done(listOf(dst.resolve("a.zip")), emptyList()), compressed)
        assertEquals(listOf(2), committed)

        val bytes = zipOf("a.txt" to "first", "b.txt" to "second").apply { rename(1, "a.txt") }
        val dup = putZip(src, "dup.zip", bytes)

        val extracted = ops.runToEnd(extracting(dst.resolve("a.zip"), dup))

        val folders = listOf(dst.resolve("a"), dst.resolve("dup"))
        assertEquals(OpResult.Done(folders, emptyList(), renamedDuplicates = 1), extracted)
        assertEquals(tree(src).filterKeys { !it.endsWith(".zip") }, tree(dst.resolve("a")))
        assertEquals(mapOf("a (2).txt" to "second", "a.txt" to "first"), tree(dst.resolve("dup")))
    }

    @Test
    fun fileOpsTellsARefusedArchiveBeforeAnything() {
        val zip = putZip(src, "x.zip", zipOf("../evil" to "evil"))
        val ops = fileOps()

        val result = ops.runToEnd(extracting(zip))

        assertEquals(OpResult.Failed(FsError.UnsafeArchive, 0, 0), result)
        val other = putZip(src, "y.zip", zipOf("a" to "a"))
        val unknown = extracting(other, options = OpOptions(zipCharset = "no-such-set"))
        assertEquals(OpResult.Failed(FsError.UnsupportedEncoding, 0, 0), ops.runToEnd(unknown))
        assertEquals(emptyMap<String, String>(), tree(dst))
    }

    @Test
    fun fileOpsHonoursTheChosenCharset() {
        val zip = putZip(src, "x.zip", zipOf("가.txt" to "x", charset = Charset.forName("MS949")))
        val options = OpOptions(zipCharset = koreanCharset().name())

        val result = fileOps().runToEnd(extracting(zip, options = options))

        assertEquals(OpResult.Done(listOf(dst.resolve("x")), emptyList()), result)
        assertEquals(mapOf("x/" to "", "x/가.txt" to "x"), tree(dst))
    }

    @Test
    fun nothingToCompressIsUnsupported() {
        assertEquals(FsError.Unsupported, failureOf { zipOps().compressNow() })

        val result = runBlocking { fileOps().run(spec(OpKind.COMPRESS, emptyList(), dst), NeverAsked, {}) }

        assertEquals(OpResult.Failed(FsError.Unsupported, 0, 0), result)
        assertEquals(emptyMap<String, String>(), tree(dst))
    }

    @Test
    fun fileOpsKeepsWhatArchivesBeforeAFailedOneExtracted() {
        val a = putZip(src, "a.zip", zipOf("a.txt" to "a"))
        val bad = putZip(src, "bad.zip", "not an archive".toByteArray())
        val c = putZip(src, "c.zip", zipOf("c.txt" to "c"))
        val committed = ArrayList<Int>()

        val result = runBlocking { fileOps().run(extracting(a, bad, c), NeverAsked, {}) { committed += it } }

        // The damaged one is listed, and the others are extracted around it, the one before it kept.
        val failed = listOf(ItemFailure(bad.toString(), FsError.Corrupt))
        assertEquals(OpResult.Done(listOf(dst.resolve("a"), dst.resolve("c")), failed), result)
        assertEquals(listOf(1, 2, 3), committed)
        assertEquals(mapOf("a/" to "", "a/a.txt" to "a", "c/" to "", "c/c.txt" to "c"), tree(dst))
    }

    @Test
    fun fatalFailureAfterAnExtractedArchiveKeepsIt() {
        val a = putZip(src, "a.zip", zipOf("a.txt" to "a"))
        val b = putZip(src, "b.zip", zipOf("b.txt" to "0123456789"))
        val c = putZip(src, "c.zip", zipOf("c.txt" to "c"))
        val fs = FaultyFs().apply { failAt(".b.txt.$OP_ID.xpart", 3, "No space left on device") }

        val result = fileOps(fs).runToEnd(extracting(a, b, c))

        // The volume is full: the archive after it is not tried, as it would not fit either.
        val failed = listOf(b, c).map { ItemFailure(it.toString(), FsError.NoSpace) }
        assertEquals(OpResult.Done(listOf(dst.resolve("a")), failed), result)
        assertEquals(mapOf("a/" to "", "a/a.txt" to "a"), tree(dst))
        assertEquals(emptySet<Path>(), journal.recorded)
    }

    @Test
    fun zipExceptionsTellUnsafeEncryptedOrCorrupt() {
        // What the path check of Android 14 and later throws from the ZipFile constructor.
        assertEquals(FsError.UnsafeArchive, errorOf(ZipException("Invalid zip entry path: ../evil.txt")))
        assertEquals(FsError.Encrypted, errorOf(ZipException("invalid CEN header (encrypted entry)")))
        assertEquals(FsError.Corrupt, errorOf(ZipException("zip END header not found")))
    }

    @Test
    fun progressIsPacedByTheInjectedTimeSource() {
        val names = listOf("a.txt", "b.txt", "c.txt")
        val files = names.map { write(src, it, it) }
        val time = TestTimeSource()
        // A second goes by as each file is read, and as each is written out of the archive.
        val fs = object : FsBackend by NioFsBackend {
            override fun openRead(path: Path): InputStream = NioFsBackend.openRead(path).also { time += 1.seconds }

            override fun createNew(path: Path): FileChannel = NioFsBackend.createNew(path).also { time += 1.seconds }
        }
        val ops = ZipOps(fs, WriteProtocol(fs, journal), KO_LABELS, time = time)
        val packed = ArrayList<OpProgress>()
        val unpacked = ArrayList<OpProgress>()

        val zip = runBlocking { ops.compress(files, dst, OP_ID) { packed += it } }
        runBlocking { ops.extract(zip, dst, null, OP_ID) { unpacked += it } }

        // So each file gets a report, not only the last one.
        assertEquals(names, packed.mapNotNull { it.currentName }.distinct())
        assertEquals(names, unpacked.mapNotNull { it.currentName }.distinct())

        // A run of FileOps is paced by its own time source.
        val untimed = ZipOps(fs, WriteProtocol(fs, journal), KO_LABELS)
        val fileOps = FileOps(fs, VolumeLookup { null }, KO_LABELS, journal, time = time, ids = { "r1" }, zip = untimed)
        val run = ArrayList<OpProgress>()

        val again = folder.newFolder("again").toPath()

        runBlocking { fileOps.run(spec(OpKind.COMPRESS, files, again), NeverAsked, { run += it }) }

        assertEquals(names, run.mapNotNull { it.currentName }.distinct())
    }

    @Test
    fun cancelledCompressIsACancellation() {
        val a = write(src, "a.txt", "a")
        lateinit var job: Job
        val fs = object : FsBackend by NioFsBackend {
            override fun createNew(path: Path): FileChannel = NioFsBackend.createNew(path).also { job.cancel() }
        }
        var thrown: Throwable? = null

        runBlocking {
            job = launch {
                try {
                    fileOps(fs).run(spec(OpKind.COMPRESS, listOf(a), dst), NeverAsked, {})
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            job.join()
        }

        assertTrue(thrown is CancellationException)
        assertEquals(emptyMap<String, String>(), tree(dst))
    }
}
