// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class ContentCountTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun nonIoFailuresAreSkippedNotThrown() {
        val root = folder.newFolder("root").toPath()
        Files.write(root.resolve("a.txt"), ByteArray(10))
        val bad = Files.createDirectory(root.resolve("bad"))
        Files.write(bad.resolve("b.txt"), ByteArray(20))
        val good = Files.createDirectory(root.resolve("good"))
        Files.write(good.resolve("c.txt"), ByteArray(30))
        val odd = Files.write(good.resolve("odd.txt"), ByteArray(40))
        // A backend may fail with more than IOExceptions (Android: SecurityException).
        val fs = object : FsBackend by NioFsBackend {
            override fun list(dir: Path): List<Path> =
                if (dir == bad) throw SecurityException("not allowed") else NioFsBackend.list(dir)

            override fun attributes(path: Path): BasicFileAttributes =
                if (path == odd) throw IllegalStateException("stale handle") else NioFsBackend.attributes(path)
        }

        val total = runBlocking { countContents(fs, listOf(Listing.entry(fs, root) { null })).last() }

        // bad and good are counted as folders, but neither bad's content nor odd.txt.
        assertEquals(ContentCount(files = 2, folders = 2, bytes = 40), total)
    }
}
