// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.PickerChanges
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.write
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** How the system file picker is told of the folders that changed (pick spec §3.5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentsNotifierTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun parentsOfNamesEachFolderOnce() {
        val root = folder.root.toPath().toRealPath()
        // What a deletion of a hundred files into the Recycle Bin changed: their folder, and a folder each in the bin.
        val files = (1..100).map { write(root, "docs/f$it.txt") }
        val payloads = (1..100).map { write(root, ".Recycle.Bin/$it/x") }
        val walks = AtomicInteger()
        val counting = object : FsBackend by NioFsBackend {
            override fun realPath(path: Path): Path = NioFsBackend.realPath(path).also { walks.incrementAndGet() }
        }
        val ids = DocumentIds(counting) { listOf(VolumeRoot(CARD, root, VolumeTraits(), removable = true)) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val picker = PickerChanges(context)

        DocumentsNotifier.parentsOf(context, ids, files + payloads)

        // Each folder once, and the bin's not at all (the picker is not shown the app's own): the folder's own walk and
        // its volume root's are all the file system is asked.
        assertEquals(listOf(PickerChanges.childrenOf("$CARD:docs")), picker.take())
        assertTrue("${walks.get()} walks", walks.get() <= 2)
    }

    private companion object {
        const val CARD = "1A2B-3C4D"
    }
}
