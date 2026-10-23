// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

class ListingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val noMime: (String) -> String? = { null }

    private val dir: Path get() = folder.root.toPath()

    private fun file(name: String, bytes: Int = 0): Path =
        Files.write(dir.resolve(name), ByteArray(bytes))

    private fun mkdir(name: String): Path = Files.createDirectory(dir.resolve(name))

    private fun names(options: ListOptions = ListOptions(), fs: FsBackend = NioFsBackend): List<String> =
        Listing.list(fs, dir, options, noMime).map { it.name }

    private fun sorted(key: SortKey, ascending: Boolean = true): List<String> =
        names(ListOptions(showHidden = true, sort = SortSpec(key, ascending)))

    @Test
    fun foldersAlwaysComeFirst() {
        mkdir("b")
        mkdir("z")
        file("a.txt", 3)
        file("y.txt", 1)
        assertEquals(listOf("b", "z", "a.txt", "y.txt"), sorted(SortKey.NAME))
        assertEquals(listOf("z", "b", "y.txt", "a.txt"), sorted(SortKey.NAME, ascending = false))
        for (key in SortKey.entries) {
            for (ascending in listOf(true, false)) {
                assertEquals("$key ascending=$ascending", setOf("b", "z"), sorted(key, ascending).take(2).toSet())
            }
        }
    }

    @Test
    fun hiddenNamesOnlyWhenAsked() {
        file(".hidden")
        file("shown.txt")
        assertEquals(listOf("shown.txt"), names())
        val all = Listing.list(NioFsBackend, dir, ListOptions(showHidden = true), noMime)
        assertEquals(listOf(".hidden", "shown.txt"), all.map { it.name })
        assertEquals(listOf(true, false), all.map { it.hidden })
    }

    @Test
    fun internalNamesAreNeverListed() {
        mkdir(HiddenNames.RECYCLE_BIN)
        file(".a.op1.xpart")
        file("x.txt")
        assertEquals(listOf("x.txt"), names(ListOptions(showHidden = true)))
        assertEquals(listOf("x.txt"), names())
    }

    @Test
    fun symlinksAreNotFollowed() {
        val target = mkdir("target")
        Files.createSymbolicLink(dir.resolve("link"), target)
        Files.createSymbolicLink(dir.resolve("broken"), dir.resolve("missing"))
        val entries = Listing.list(NioFsBackend, dir, ListOptions(), noMime).associateBy { it.name }
        assertEquals(EntryKind.DIR, entries.getValue("target").kind)
        assertEquals(EntryKind.SYMLINK, entries.getValue("link").kind)
        assertEquals(EntryKind.SYMLINK, entries.getValue("broken").kind)
    }

    @Test
    fun entryDescribesFilesAndFolders() {
        val txt = file("a.txt", 4)
        mkdir("docs")
        val entries = Listing.list(NioFsBackend, dir, ListOptions(), { "text/plain" }).associateBy { it.name }
        val a = entries.getValue("a.txt")
        assertEquals(txt, a.path)
        assertEquals(EntryKind.FILE, a.kind)
        assertEquals(4L, a.size)
        assertEquals("text/plain", a.mime)
        assertNotNull(a.fileKey)
        assertNull(entries.getValue("docs").mime)
    }

    @Test
    fun typeSortsByLowerCaseExtensionThenName() {
        mkdir("folder.zip")
        listOf("b.TXT", "a.txt", "c.jpg", "d.Png", "noext", ".bashrc").forEach { file(it) }
        assertEquals(
            listOf("folder.zip", ".bashrc", "noext", "c.jpg", "d.Png", "a.txt", "b.TXT"),
            sorted(SortKey.TYPE),
        )
    }

    @Test
    fun sizeSortCountsFoldersAsZero() {
        val sub = mkdir("folder")
        Files.write(sub.resolve("inside"), ByteArray(100))
        file("big", 5)
        file("small", 1)
        file("empty", 0)
        assertEquals(listOf("folder", "empty", "small", "big"), sorted(SortKey.SIZE))
        assertEquals(listOf("folder", "big", "small", "empty"), sorted(SortKey.SIZE, ascending = false))
        val folderEntry = Listing.list(NioFsBackend, dir, ListOptions(), noMime).first { it.name == "folder" }
        assertEquals(0L, folderEntry.size)
    }

    @Test
    fun modifiedSortUsesEpochMillis() {
        val old = file("old")
        val new = file("new")
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_600_000_000_000))
        Files.setLastModifiedTime(new, FileTime.fromMillis(1_700_000_000_000))
        assertEquals(1_600_000_000_000, Listing.entry(NioFsBackend, old, noMime).modified)
        assertEquals(listOf("old", "new"), sorted(SortKey.MODIFIED))
        assertEquals(listOf("new", "old"), sorted(SortKey.MODIFIED, ascending = false))
    }

    @Test
    fun skipsChildrenThatCannotBeRead() {
        file("ok.txt")
        file("secret.txt")
        val fs = object : FsBackend by NioFsBackend {
            override fun attributes(path: Path): BasicFileAttributes =
                if (path.fileName.toString() == "secret.txt") throw AccessDeniedException(path.toString())
                else NioFsBackend.attributes(path)
        }
        assertEquals(listOf("ok.txt"), names(fs = fs))
        assertThrows(NoSuchFileException::class.java) {
            Listing.list(NioFsBackend, dir.resolve("missing"), ListOptions(), noMime)
        }
    }

    @Test
    fun listsTwentyThousandEntriesQuickly() {
        for (i in 1..20_000) Files.createFile(dir.resolve("f$i.txt"))
        val start = System.nanoTime()
        val listed = Listing.list(NioFsBackend, dir, ListOptions(), noMime)
        val bySize = Listing.sort(listed, SortSpec(SortKey.SIZE, ascending = false))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(20_000, bySize.size)
        assertEquals("f1.txt", listed.first().name)
        assertEquals("f20000.txt", listed.last().name)
        assertTrue("list + sort took $elapsedMs ms", elapsedMs < 3_000)
    }
}
