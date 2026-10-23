// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class WalkerTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The walked folder; [file] and [dir] build the tree under it. */
    private val root: Path by lazy { Files.createDirectory(folder.root.toPath().resolve("tree")) }

    private fun file(relative: String, bytes: Int = 0): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.write(path, ByteArray(bytes))
    }

    private fun dir(relative: String): Path = Files.createDirectories(root.resolve(relative))

    /** Everything [walk] visits under [from], descending wherever [descend] says so. */
    private fun items(
        fs: FsBackend = NioFsBackend,
        from: Path = root,
        include: (FileEntry) -> Boolean = { !HiddenNames.isInternal(it.name) },
        onSkipped: (Path, FsError) -> Unit = { _, _ -> },
        descend: (WalkItem) -> Boolean = { true },
    ): List<WalkItem> = runBlocking {
        val seen = ArrayList<WalkItem>()
        walk(fs, from, include = include, onSkipped = onSkipped) { item ->
            seen += item
            descend(item)
        }
        seen
    }

    private fun List<WalkItem>.relatives(): List<String> = map { it.relative.toString() }

    @Test
    fun preOrderAndRelativePaths() {
        file("b/d/e.txt", 3)
        file("b/c.txt")
        file("f.txt")
        file("a.txt")
        dir("b/empty")

        val all = items()
        assertEquals(listOf("a.txt", "b", "b/c.txt", "b/d", "b/d/e.txt", "b/empty", "f.txt"), all.relatives())
        assertEquals(listOf(1, 1, 2, 2, 3, 2, 1), all.map { it.depth })
        for (item in all) {
            assertEquals(root.resolve(item.relative), item.entry.path)
            assertEquals(item.relative.fileName.toString(), item.entry.name)
        }
        val byName = all.associateBy { it.relative.toString() }
        assertEquals(EntryKind.DIR, byName.getValue("b/d").entry.kind)
        assertEquals(EntryKind.FILE, byName.getValue("b/d/e.txt").entry.kind)
        assertEquals(3L, byName.getValue("b/d/e.txt").entry.size)

        // A visit that returns false keeps the walk out of that folder only.
        assertEquals(listOf("a.txt", "b", "f.txt"), items(descend = { it.relative.toString() != "b" }).relatives())
        assertEquals(
            listOf("a.txt", "b", "b/c.txt", "b/d", "b/empty", "f.txt"),
            items(descend = { it.relative.toString() != "b/d" }).relatives(),
        )
        // A file as the root has nothing under it.
        assertEquals(emptyList<String>(), items(from = root.resolve("a.txt")).relatives())
    }

    @Test
    fun doesNotFollowSymlinks() {
        val outside = Files.createDirectories(folder.root.toPath().resolve("outside"))
        Files.write(outside.resolve("secret.txt"), ByteArray(1))
        file("real/f.txt")
        Files.createSymbolicLink(root.resolve("link"), outside)
        // A link back to the root would loop forever if followed.
        Files.createSymbolicLink(root.resolve("real/back"), root)

        val all = items()
        assertEquals(listOf("link", "real", "real/back", "real/f.txt"), all.relatives())
        val byName = all.associateBy { it.relative.toString() }
        assertEquals(EntryKind.SYMLINK, byName.getValue("link").entry.kind)
        assertEquals(EntryKind.SYMLINK, byName.getValue("real/back").entry.kind)
        // A link as the root itself is not followed either.
        assertEquals(emptyList<String>(), items(from = root.resolve("link")).relatives())
    }

    @Test
    fun skipsInternalNames() {
        file(".Recycle.Bin/1234/old.txt")
        file(".report.pdf.op1.xpart", 5)
        file(".unpacked.op1.xpart/inner.txt")
        file("sub/.Recycle.Bin/x.txt")
        file("keep.txt")

        assertEquals(listOf("keep.txt", "sub"), items().relatives())
        // It is the default filter that leaves them out.
        assertTrue(".Recycle.Bin/1234/old.txt" in items(include = { true }).relatives())
    }

    @Test
    fun reportsUnreadableDirs() {
        file("a/x.txt")
        file("b/y.txt")
        file("c.txt")
        val fs = FaultyFs().apply { deny(root.resolve("a")) }
        val skipped = ArrayList<Pair<Path, FsError>>()

        val all = items(fs, onSkipped = { path, error -> skipped += path to error })

        assertEquals(listOf(root.resolve("a") to FsError.AccessDenied), skipped)
        assertEquals(listOf("a", "b", "b/y.txt", "c.txt"), all.relatives())
    }

    @Test
    fun mimeFailureEndsTheWalk() {
        file("a.txt")
        file("b.txt")

        // Only the backend's failures skip an item; the caller's own mime lookup failing is a bug to see.
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                walk(NioFsBackend, root, mime = { error("no mime for $it") }) { true }
            }
        }
    }

    @Test
    fun cancellable() {
        file("a.txt")
        file("b.txt")
        file("c/d.txt")
        var visited = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                walk(NioFsBackend, root) {
                    visited++
                    cancel()
                    true
                }
            }
        }
        assertEquals(1, visited)
    }
}
