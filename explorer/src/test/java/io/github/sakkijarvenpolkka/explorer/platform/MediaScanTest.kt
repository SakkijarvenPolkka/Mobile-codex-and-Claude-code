// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ItemFailure
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.volumesOf
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.scanTargets
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaScannerConnection
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaScanTest {

    private val internal: Path = Paths.get("/storage/emulated/0")
    private val sd: Path = Paths.get("/storage/1A2B-3C4D")
    private val usb: Path = Paths.get("/mnt/media_rw/5E6F-7A8B")

    private val volumes = volumesOf(
        VolumeRoot("primary", internal, VolumeTraits(), removable = false),
        VolumeRoot("1A2B-3C4D", sd, VolumeTraits(fatNames = true), removable = true),
        VolumeRoot("5E6F-7A8B", usb, VolumeTraits(fatNames = true), removable = true),
    )

    @Before
    fun forgetScans() {
        ShadowMediaScannerConnection.reset()
    }

    private fun finished(kind: OpKind, sources: List<Path>, target: Path?, result: OpResult) =
        FinishedOp(OperationSpec("op1", kind, sources.map { it.toString() }, target?.toString()), result)

    @Test
    fun scanTargets() {
        val dcim = internal.resolve("DCIM")
        val created = listOf(dcim.resolve("a.jpg"), dcim.resolve("Trip"))
        // What a failure kept from being made is not there to be scanned.
        val done = OpResult.Done(created, listOf(ItemFailure("$internal/Download/c.jpg", FsError.AccessDenied)))
        val fromInternal = listOf(internal.resolve("Download/a.jpg"), internal.resolve("Download/Trip"))
        val fromSd = listOf(sd.resolve("a.jpg"), sd.resolve("Trip"))
        val expected = mapOf(
            OpKind.COPY to created,
            OpKind.EXTRACT to created,
            OpKind.RESTORE to created,
            OpKind.COMPRESS to created,
            // Only renamed: the media store follows a rename on its own.
            OpKind.MOVE to emptyList(),
            OpKind.DELETE_TO_BIN to emptyList(),
            OpKind.DELETE_PERMANENT to emptyList(),
            OpKind.EMPTY_BIN to emptyList(),
        )
        assertEquals(OpKind.entries.toSet(), expected.keys)

        for ((kind, paths) in expected) {
            assertEquals(kind.name, paths, scanTargets(finished(kind, fromInternal, dcim, done), volumes))
        }
        // A move to another volume made the files there.
        assertEquals(created, scanTargets(finished(OpKind.MOVE, fromSd, dcim, done), volumes))
        assertEquals(created, scanTargets(finished(OpKind.MOVE, fromInternal + fromSd, dcim, done), volumes))
        // Nothing is taken for made when it did not run to its end.
        for (result in listOf(OpResult.Failed(FsError.NoSpace, 1, 2), OpResult.Cancelled)) {
            assertEquals(emptyList<Path>(), scanTargets(finished(OpKind.COPY, fromSd, dcim, result), volumes))
        }
    }

    @Test
    fun skipsNonFuseVolumes() {
        val scan = AndroidMediaScan(ApplicationProvider.getApplicationContext(), volumes) { root ->
            root.startsWith("/storage")
        }

        // A drive outside /storage, and a path on no volume, are not for the media store.
        scan.scan(listOf(usb.resolve("b.jpg"), Paths.get("/data/local/tmp/c.jpg")))
        assertEquals(emptySet<String>(), ShadowMediaScannerConnection.getSavedPaths())
        scan.scan(listOf(internal.resolve("DCIM/a.jpg"), usb.resolve("b.jpg"), sd.resolve("Trip")))

        assertEquals(setOf("$internal/DCIM/a.jpg", "$sd/Trip"), ShadowMediaScannerConnection.getSavedPaths())
    }
}
