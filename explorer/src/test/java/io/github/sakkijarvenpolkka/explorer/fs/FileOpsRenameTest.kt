// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

class FileOpsRenameTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dir: Path get() = folder.root.toPath()

    private fun ops(fs: FsBackend = NioFsBackend, volumes: VolumeLookup = VolumeLookup { null }) =
        FileOps(fs, volumes, KO_LABELS, MemoryJournal(), ids = { "r1" })

    private fun FileOps.renameBlocking(path: Path, name: String): Path = runBlocking { rename(path, name) }

    private fun names(at: Path = dir): List<String> = NioFsBackend.list(at).map { it.fileName.toString() }.sorted()

    @Test
    fun renamesFile() {
        val a = write(dir, "a.txt", "a")
        val d = write(dir, "d/in.txt", "in").parent

        assertEquals(dir.resolve("b.txt"), ops().renameBlocking(a, "b.txt"))
        assertEquals("a", String(Files.readAllBytes(dir.resolve("b.txt"))))
        // The spaces around a typed name are trimmed.
        assertEquals(dir.resolve("e"), ops().renameBlocking(d, "  e "))
        assertEquals(listOf("b.txt", "e"), names())
        assertEquals(listOf("in.txt"), names(dir.resolve("e")))
        // The same name again changes nothing.
        assertEquals(dir.resolve("e"), ops().renameBlocking(dir.resolve("e"), "e"))
    }

    @Test
    fun rejectsExistingName() {
        val a = write(dir, "a.txt", "a")
        write(dir, "b.txt", "b")

        val e = assertThrows(FsException::class.java) { ops().renameBlocking(a, "b.txt") }
        assertEquals(FsError.AlreadyExists, e.error)
        assertEquals(mapOf("a.txt" to "a", "b.txt" to "b"), tree(dir))

        // On a case-insensitive volume, another file whose name differs only in case takes it too.
        val sd = folder.newFolder("sd").toPath()
        val c = write(sd, "c.txt", "c")
        write(sd, "D.txt", "d")
        val fs = FaultyFs().apply { caseInsensitive = true }
        val folded = assertThrows(FsException::class.java) { ops(fs).renameBlocking(c, "d.txt") }
        assertEquals(FsError.AlreadyExists, folded.error)
        assertEquals(mapOf("D.txt" to "d", "c.txt" to "c"), tree(sd))
    }

    @Test
    fun caseOnlyRenameUsesTwoSteps() {
        val photo = write(dir, "photo.jpg", "jpeg")
        val fs = FaultyFs().apply { caseInsensitive = true }

        val renamed = ops(fs).renameBlocking(photo, "Photo.jpg")

        // Not refused as taken by itself, and not lost to the volume folding the name back.
        assertEquals(dir.resolve("Photo.jpg"), renamed)
        assertEquals(listOf("Photo.jpg"), names())
        assertEquals("jpeg", String(Files.readAllBytes(dir.resolve("Photo.jpg"))))
        val temp = dir.resolve(".~ren-r1")
        assertEquals(
            listOf(
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, photo, temp),
                FaultyFs.Call(FaultyFs.Op.ATOMIC_MOVE, temp, dir.resolve("Photo.jpg")),
            ),
            fs.calls,
        )
    }

    @Test
    fun caseOnlyRenameNeverDeletes() {
        val photo = write(dir, "photo.jpg", "jpeg")
        val fs = FaultyFs().apply { caseInsensitive = true }
        ops(fs).renameBlocking(photo, "PHOTO.JPG")
        assertEquals(0, fs.calls.count { it.op == FaultyFs.Op.DELETE })

        // When the second step fails, the first is undone: the file keeps its old name, nothing deleted.
        val faulty = FaultyFs().apply { caseInsensitive = true }
        val failing = object : FsBackend by faulty {
            override fun atomicMove(from: Path, to: Path) {
                // The second step fails; undoing the first goes through.
                if (from.fileName.toString().startsWith(".~ren-") && to.fileName.toString() == "photo.jpg") {
                    throw FileSystemException(to.toString(), null, "Input/output error")
                }
                faulty.atomicMove(from, to)
            }
        }
        val e = assertThrows(FsException::class.java) {
            ops(failing).renameBlocking(dir.resolve("PHOTO.JPG"), "photo.jpg")
        }
        assertEquals(FsError.IoError, e.error)
        assertEquals(listOf("PHOTO.JPG"), names())
        assertEquals("jpeg", String(Files.readAllBytes(dir.resolve("PHOTO.JPG"))))
        assertEquals(0, faulty.calls.count { it.op == FaultyFs.Op.DELETE })
    }

    @Test
    fun caseOnlyRenameIsJournaledBetweenItsSteps() {
        val photo = write(dir, "photo.jpg", "jpeg")
        val journal = MemoryJournal()
        val faulty = FaultyFs().apply { caseInsensitive = true }
        val seen = ArrayList<Map<Path, Path>>()
        val watching = object : FsBackend by faulty {
            override fun atomicMove(from: Path, to: Path) {
                seen += journal.renames.toMap()
                faulty.atomicMove(from, to)
            }
        }

        FileOps(watching, VolumeLookup { null }, KO_LABELS, journal, ids = { "r1" }).renameBlocking(photo, "Photo.jpg")

        // Recorded, with the name it goes to, before the first step; forgotten after the second.
        val temp = dir.resolve(".~ren-r1")
        assertEquals(List(2) { mapOf(temp to dir.resolve("Photo.jpg")) }, seen)
        assertEquals(emptyMap<Path, Path>(), journal.renames)
        assertEquals(emptySet<Path>(), journal.recorded)

        // When the second step fails and so does undoing the first, the file keeps the temporary name, and
        // the record stays, for the journal's sweep to finish.
        val stuck = FaultyFs().apply {
            caseInsensitive = true
            refuse(FaultyFs.Op.ATOMIC_MOVE, "/.~ren-r1", "Input/output error")
        }
        val ops = FileOps(stuck, VolumeLookup { null }, KO_LABELS, journal, ids = { "r1" })
        val e = assertThrows(FsException::class.java) { ops.renameBlocking(dir.resolve("Photo.jpg"), "PHOTO.jpg") }
        assertEquals(FsError.IoError, e.error)
        assertEquals(listOf(".~ren-r1"), names())
        assertEquals(mapOf(temp to dir.resolve("PHOTO.jpg")), journal.renames)
    }

    @Test
    fun invalidNameRejected() {
        val a = write(dir, "a.txt", "a")
        val sd = folder.newFolder("sd").toPath()
        val b = write(sd, "b.txt", "b")
        val volumes = volumesOf(VolumeRoot("1234-5678", sd, VolumeTraits(fatNames = true), removable = true))

        for ((name, problem) in listOf(
            "" to NameProblem.Empty,
            "x/y" to NameProblem.Slash,
            ".." to NameProblem.DotOrDotDot,
            "a\u0000b" to NameProblem.ControlChar,
            "a‮b" to NameProblem.BidiControl,
        )) {
            val e = assertThrows(FsException::class.java) { ops().renameBlocking(a, name) }
            assertEquals(name, FsError.NameInvalid(problem), e.error)
        }
        // The removable drive also refuses what FAT cannot store; internal storage does not.
        val fat = assertThrows(FsException::class.java) { ops(volumes = volumes).renameBlocking(b, "b:c.txt") }
        assertEquals(FsError.NameInvalid(NameProblem.FatChar(':')), fat.error)
        assertEquals(listOf("a.txt", "sd"), names())
        assertEquals(listOf("b.txt"), names(sd))
        assertEquals(dir.resolve("a:c.txt"), ops(volumes = volumes).renameBlocking(a, "a:c.txt"))
    }

    @Test
    fun createFolderAndTextFileUseLabels() {
        val ops = ops()

        val first = runBlocking { ops.createFolder(dir) }
        val second = runBlocking { ops.createFolder(dir) }
        val text = runBlocking { ops.createTextFile(dir) }
        val text2 = runBlocking { ops.createTextFile(dir) }

        assertEquals(dir.resolve("새 폴더"), first)
        assertEquals(dir.resolve("새 폴더 (2)"), second)
        assertEquals(dir.resolve("새 텍스트 문서.txt"), text)
        assertEquals(dir.resolve("새 텍스트 문서 (2).txt"), text2)
        assertTrue(Files.isDirectory(first) && Files.isDirectory(second))
        assertTrue(Files.isRegularFile(text))
        assertEquals(0L, Files.size(text))
        assertEquals(setOf(first, second, text, text2), NioFsBackend.list(dir).toSet())

        // A folder that is not there is reported, not created.
        val e = assertThrows(FsException::class.java) { runBlocking { ops.createFolder(dir.resolve("gone")) } }
        assertEquals(FsError.NotFound, e.error)
    }
}
