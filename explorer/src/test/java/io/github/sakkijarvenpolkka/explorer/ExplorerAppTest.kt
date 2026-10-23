// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.write
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path

/** The app's one runner, file operations, journal and Recycle Bin, as the app puts them together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplorerAppTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: ExplorerApp get() = ApplicationProvider.getApplicationContext()

    /** Copies [file] into its own folder with the app's runner, and returns where the copy went. */
    private fun copyInPlace(file: Path): Path = runBlocking {
        app.runner.submit(OperationSpec("t1", OpKind.COPY, listOf(file.toString()), file.parent.toString()))
        val finished = withTimeout(10_000) {
            app.runner.state.first { state -> state.finished.any { it.spec.id == "t1" } }
        }.finished.single { it.spec.id == "t1" }
        (finished.result as OpResult.Done).created.single()
    }

    @Test
    fun runnerUsesTheLabelsOfTheAppsLanguage() {
        val dir = folder.root.toPath()
        val copied = copyInPlace(write(dir, "x.txt", "x"))

        assertEquals(dir.resolve("x - Copy.txt"), copied)
        assertEquals(dir.resolve("New folder"), runBlocking { app.fileOps.createFolder(dir) })
        assertEquals(dir.resolve("New Text Document.txt"), runBlocking { app.fileOps.createTextFile(dir) })
    }

    @Test
    @Config(qualifiers = "ko")
    fun koreanLabels() {
        val dir = folder.root.toPath()
        val copied = copyInPlace(write(dir, "x.txt", "x"))

        assertEquals(dir.resolve("x - 복사본.txt"), copied)
        assertEquals(dir.resolve("새 폴더"), runBlocking { app.fileOps.createFolder(dir) })
        assertEquals(dir.resolve("새 텍스트 문서.txt"), runBlocking { app.fileOps.createTextFile(dir) })
    }

    @Test
    fun journalIsInTheAppsFiles() {
        val dir = folder.root.toPath()
        copyInPlace(write(dir, "x.txt", "x"))

        // Begun and ended: nothing is left of it, in the folder the journal keeps.
        val journal = app.filesDir.resolve("ops")
        assertEquals(emptyList<String>(), journal.list()?.toList())
    }
}
