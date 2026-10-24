// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class SearchTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The searched folder; [file] builds the tree under it. */
    private val root: Path by lazy { Files.createDirectory(folder.root.toPath().resolve("tree")) }

    private val mime: (String) -> String? = { if (it.endsWith(".txt")) "text/plain" else null }

    private fun file(relative: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.createFile(path)
    }

    /** Every event of a search of [root] for [query]. */
    private suspend fun events(
        query: String,
        fs: FsBackend = NioFsBackend,
        includeSubfolders: Boolean = true,
        excluded: (Path) -> Boolean = { false },
        time: TimeSource = TimeSource.Monotonic,
    ): List<SearchEvent> = search(fs, root, query, includeSubfolders, mime, excluded, time).toList()

    private fun List<SearchEvent>.entries(): List<FileEntry> = filterIsInstance<SearchEvent.Batch>().flatMap { it.hits }

    /** The hits of these events, relative to [root]. */
    private fun List<SearchEvent>.hits(): List<String> = entries().map { root.relativize(it.path).toString() }

    /** [attributes] as a special file (a pipe, a socket, a device) has them. */
    private fun special(attributes: BasicFileAttributes): BasicFileAttributes =
        object : BasicFileAttributes by attributes {
            override fun isRegularFile() = false

            override fun isDirectory() = false

            override fun isSymbolicLink() = false

            override fun isOther() = true
        }

    @Test
    fun substringCaseInsensitive() {
        assertTrue(NameMatcher("rep").matches("Report.pdf"))
        assertTrue(NameMatcher("REP").matches("report.pdf"))
        assertTrue(NameMatcher("t.P").matches("Report.pdf"))
        assertFalse(NameMatcher("rep").matches("Rapport.pdf"))
    }

    @Test
    fun wildcards() {
        val jpg = NameMatcher("*.jpg")
        assertTrue(jpg.matches("a.JPG"))
        assertFalse(jpg.matches("b.jpeg"))
        // A pattern is matched against the whole name.
        assertFalse(jpg.matches("a.jpg.txt"))
        val one = NameMatcher("a?c")
        assertTrue(one.matches("abc"))
        assertFalse(one.matches("ac"))
        assertFalse(one.matches("xabc"))

        // '*' also matches nothing, and gives back what the rest of the pattern needs.
        assertTrue(NameMatcher("a*").matches("a"))
        assertTrue(NameMatcher("*a*b*").matches("xaxxbx"))
        assertTrue(NameMatcher("*ab").matches("aab"))
        assertFalse(NameMatcher("*a*b").matches("xaxxbx"))
        // A '*' in the name is an ordinary character there.
        assertTrue(NameMatcher("*x").matches("*ax"))
        // '?' is one character, one outside the BMP included.
        assertTrue(NameMatcher("?.png").matches("😀.png"))
    }

    @Test
    fun nfcMatching() {
        val name = Normalizer.normalize("한글.txt", Normalizer.Form.NFD)
        assertNotEquals("한글.txt", name)

        assertTrue(NameMatcher("한글").matches(name))
        // Normalized before a pattern is matched: '?' is the syllable, not one of its jamo.
        assertTrue(NameMatcher("?글.txt").matches(name))
        // An NFD query matches an NFC name as well.
        assertTrue(NameMatcher(Normalizer.normalize("한", Normalizer.Form.NFD)).matches("한글.txt"))
        assertFalse(NameMatcher("한국").matches(name))
    }

    @Test
    fun regexCharsAreLiteral() {
        assertTrue(NameMatcher("a+b(1)").matches("a+b(1).txt"))
        assertFalse(NameMatcher("a.c").matches("abc"))
        assertTrue(NameMatcher("[x]").matches("[x] notes"))
        assertFalse(NameMatcher("[x]").matches("x"))
        assertTrue(NameMatcher("\\").matches("a\\b"))
        assertTrue(NameMatcher("a+*").matches("a+b(1).txt"))
        assertFalse(NameMatcher("(*").matches("a+b(1).txt"))
        assertFalse(NameMatcher("^$|(").matches("a"))
    }

    @Test
    fun excludesBinXpartRestrictedAndSpecial() = runTest {
        file("report.txt")
        file(".Recycle.Bin/1700000000000-ab/report.txt")
        file(".report.txt.op1.xpart")
        file(".unpacked.op1.xpart/report.txt")
        file("Android/data/com.example/report.txt")
        file("Android/obb/com.example/report.obb")
        file("Android/media/report.txt")
        val pipe = file("report.pipe")
        val listed = ArrayList<Path>()
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> = NioFsBackend.list(dir).also { listed.add(dir) }

            override fun attributes(path: Path): BasicFileAttributes =
                NioFsBackend.attributes(path).let { if (path == pipe) special(it) else it }
        }

        val events = events("report", fs, excluded = { isRestrictedAppDataPath(root, it) })

        assertEquals(listOf("Android/media/report.txt", "report.txt"), events.hits())
        assertEquals(SearchEvent.Finished(capped = false, skippedDirs = 0), events.last())
        // What is left out is not walked into either.
        assertEquals(
            listOf(root, root.resolve("Android"), root.resolve("Android/media")),
            listed,
        )
    }

    @Test
    fun capsAtTenThousand() = runTest {
        repeat(10_050) { file("f%05d.txt".format(it)) }
        var reads = 0
        val fs = object : FsBackend by NioFsBackend {
            override fun attributes(path: Path): BasicFileAttributes = NioFsBackend.attributes(path).also { reads++ }
        }

        val events = events("f", fs)

        val hits = events.hits()
        assertEquals(SEARCH_CAP, hits.size)
        assertEquals(10_000, hits.toSet().size)
        assertEquals("f09999.txt", hits.last())
        assertEquals(SearchEvent.Finished(capped = true, skippedDirs = 0), events.last())
        // The walk stops there: the root and the first 10,000 are all it reads.
        assertEquals(SEARCH_CAP + 1, reads)
    }

    @Test
    fun countsSkippedDirs() = runTest {
        file("a/match.txt")
        file("b/c/match.txt")
        file("b/match.txt")
        file("match.txt")
        val odd = file("odd-match.txt")
        val time = TestTimeSource()
        val faulty = FaultyFs().apply {
            deny(root.resolve("a"))
            deny(root.resolve("b/c"))
        }
        val fs = object : FsBackend by faulty {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path == odd) throw AccessDeniedException(path.toString())
                if (path == root.resolve("match.txt")) time += 300.milliseconds
                return faulty.attributes(path)
            }
        }

        val events = events("match", fs, time = time)

        assertEquals(listOf("b/match.txt", "match.txt"), events.hits())
        // The two folders that could not be listed; not odd-match.txt, whose attributes could not be read.
        assertEquals(SearchEvent.Finished(capped = false, skippedDirs = 2), events.last())
        assertEquals(
            listOf(SearchEvent.Progress(root, 0), SearchEvent.Progress(root, 2)),
            events.filterIsInstance<SearchEvent.Progress>(),
        )

        // A root that cannot be listed is one folder skipped.
        val denied = FaultyFs().apply { deny(root) }
        assertEquals(
            listOf(SearchEvent.Progress(root, 0), SearchEvent.Finished(capped = false, skippedDirs = 1)),
            events("match", denied),
        )
    }

    @Test
    fun batchesByCountOrTime() = runTest {
        repeat(450) { file("f%03d.txt".format(it)) }
        // Sorted between f250.txt and f251.txt, and not a hit.
        val slow = file("f250.dat")
        val time = TestTimeSource()
        val fs = object : FsBackend by NioFsBackend {
            override fun attributes(path: Path): BasicFileAttributes {
                if (path == slow) time += 300.milliseconds
                return NioFsBackend.attributes(path)
            }
        }

        val events = events("*.txt", fs, time = time)

        // 200 hits; the 50 found when 200 ms have passed; 200 more. No empty batch at the end.
        assertEquals(listOf(200, 50, 200), events.filterIsInstance<SearchEvent.Batch>().map { it.hits.size })
        assertEquals((0 until 450).map { "f%03d.txt".format(it) }, events.hits())
        // The current folder at the start, then once 200 ms have passed.
        assertEquals(
            listOf(SearchEvent.Progress(root, 0), SearchEvent.Progress(root, 0)),
            events.filterIsInstance<SearchEvent.Progress>(),
        )
        assertEquals(SearchEvent.Finished(capped = false, skippedDirs = 0), events.last())
    }

    @Test
    fun cancellable() = runTest {
        repeat(1_000) { file("f%04d.txt".format(it)) }
        val seen = ArrayList<SearchEvent>()
        var reads = 0
        lateinit var job: Job
        val fs = object : FsBackend by NioFsBackend {
            override fun attributes(path: Path): BasicFileAttributes {
                if (++reads == 300) job.cancel()
                return NioFsBackend.attributes(path)
            }
        }

        job = launch { search(fs, root, "f", true, mime, { false }).toList(seen) }
        job.join()

        assertTrue(job.isCancelled)
        // Nothing is read after the cancellation.
        assertEquals(300, reads)
        assertTrue(seen.none { it is SearchEvent.Finished })
    }

    @Test
    fun noSubfoldersOnlyTopLevel() = runTest {
        file("a.txt")
        file("alpha/a2.txt")
        file("beta/a3.txt")
        file("other.bin")
        // Its subfolders are not even listed: beta, which cannot be, is not skipped then.
        val fs = FaultyFs().apply { deny(root.resolve("beta")) }

        val top = events("a", fs, includeSubfolders = false)

        assertEquals(
            listOf("a.txt", "alpha", "beta").map { Listing.entry(NioFsBackend, root.resolve(it), mime) },
            top.entries(),
        )
        assertEquals(SearchEvent.Finished(capped = false, skippedDirs = 0), top.last())

        val all = events("a", fs)
        assertEquals(listOf("a.txt", "alpha", "alpha/a2.txt", "beta"), all.hits())
        assertEquals(SearchEvent.Finished(capped = false, skippedDirs = 1), all.last())
    }
}
