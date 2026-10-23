// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class VolumeTrackingTest {

    private val primaryRoot: Path = Paths.get("/storage/emulated/0")
    private val sdRoot: Path = Paths.get("/storage/$SD")
    private val volumes = FakeVolumes(FakeVolumes.primary(primaryRoot), FakeVolumes.removable(SD, "SD card", sdRoot))
    private val codec = VolumeLocationCodec { volumes.volumes.value }

    /** One tab on the SD card (active), one on internal storage. */
    private val model = TabsReducer.initial(Location.Folder(primaryRoot.resolve("Music")))
        .let { TabsReducer.reduce(it, TabAction.NewTab(Location.Folder(sdRoot.resolve("DCIM/Camera")))) }
    private val onPrimary = model.tabs[0].id
    private val onSd = model.tabs[1].id

    /** Sets where the volume [uuid] is, as the volume list shows it before the matching event. */
    private fun VolumeTracking.volumeAt(uuid: String, root: Path?) {
        val state = if (root == null) VolumeState.EJECTING else VolumeState.MOUNTED
        volumes.update(uuid) { it.copy(root = root, state = state) }
        rememberRoots(volumes.volumes.value)
    }

    /** Applies [actions] as the view model does: through the reducer, then the tabs that moved are no longer marked. */
    private fun VolumeTracking.dispatch(m: TabsModel, actions: List<TabAction>): TabsModel {
        val after = actions.fold(m, TabsReducer::reduce)
        retain { id -> after.locationOf(id) == m.locationOf(id) }
        return after
    }

    @Test
    fun ejectMarksTheTabsOnThatVolumeAndMountMovesThem() {
        val tracking = VolumeTracking(volumes, codec)
        tracking.volumeAt(SD, null)
        val ejected = tracking.onVolumeEvent(VolumeEvent.Ejecting(SD), model)
        assertEquals(setOf(onSd), ejected.ejected)
        assertTrue(ejected.moves.isEmpty())
        assertEquals(mapOf(onSd to DecodedLocation.Unavailable(SD, "DCIM/Camera")), tracking.unavailable.value)

        // It comes back at another root: the tab goes to its folder there, from (uuid, rel).
        val elsewhere = Paths.get("/mnt/media_rw/$SD")
        tracking.volumeAt(SD, elsewhere)
        val mounted = tracking.onVolumeEvent(VolumeEvent.Mounted(SD), model)
        assertTrue(mounted.ejected.isEmpty())
        val camera = Location.Folder(elsewhere.resolve("DCIM/Camera"))
        assertEquals(listOf(TabAction.ReplaceCurrent(onSd, camera)), mounted.moves)

        val after = tracking.dispatch(model, mounted.moves)
        assertEquals(camera, after.locationOf(onSd))
        assertEquals(model.locationOf(onPrimary), after.locationOf(onPrimary))
        assertTrue(tracking.unavailable.value.isEmpty())
    }

    @Test
    fun remountAtTheSameRootOnlyEndsTheWait() {
        val tracking = VolumeTracking(volumes, codec)
        tracking.volumeAt(SD, null)
        tracking.onVolumeEvent(VolumeEvent.Ejecting(SD), model)
        tracking.volumeAt(SD, sdRoot)
        val mounted = tracking.onVolumeEvent(VolumeEvent.Mounted(SD), model)
        assertTrue(mounted.moves.isEmpty())
        assertTrue(tracking.unavailable.value.isEmpty())
    }

    @Test
    fun ejectOfAnotherVolumeChangesNothing() {
        val tracking = VolumeTracking(volumes, codec)
        val usb = Paths.get("/mnt/media_rw/USB1")
        volumes.volumes.value += FakeVolumes.removable("USB1", "USB drive", usb)
        tracking.rememberRoots(volumes.volumes.value)
        tracking.volumeAt("USB1", null)
        val ejected = tracking.onVolumeEvent(VolumeEvent.Ejecting("USB1"), model)
        assertTrue(ejected.ejected.isEmpty())
        assertTrue(tracking.unavailable.value.isEmpty())
        tracking.volumeAt("USB1", usb)
        assertTrue(tracking.onVolumeEvent(VolumeEvent.Mounted("USB1"), model).moves.isEmpty())
    }

    @Test
    fun restoredTabFollowsItsVolumeToWhereItMounts() {
        // Saved on a USB drive that is out at start: kept at the usual mount point, /storage/USB1.
        volumes.volumes.value += FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        val gone = DecodedLocation.Unavailable("USB1", "docs")
        val restored = TabsReducer.initial(gone.location)
        val tab = restored.activeId
        val tracking = VolumeTracking(volumes, codec, mapOf(tab to gone))
        assertEquals(mapOf(tab to gone), tracking.unavailable.value)

        val usb = Paths.get("/mnt/media_rw/USB1")
        tracking.volumeAt("USB1", usb)
        val mounted = tracking.onVolumeEvent(VolumeEvent.Mounted("USB1"), restored)
        assertEquals(listOf(TabAction.ReplaceCurrent(tab, Location.Folder(usb.resolve("docs")))), mounted.moves)
    }

    @Test
    fun placeOffVolumeMarksMovesOrLeavesTheTab() {
        val tracking = VolumeTracking(volumes, codec)
        val tab = model.activeId
        // The usual mount point of a drive that is out: unavailable.
        volumes.volumes.value += FakeVolumes.removable("USB1", "USB drive", null, VolumeState.UNMOUNTED)
        assertNull(tracking.placeOffVolume(tab, Paths.get("/storage/USB1/docs")))
        assertEquals(mapOf(tab to DecodedLocation.Unavailable("USB1", "docs")), tracking.unavailable.value)

        // The same place once the drive is mounted elsewhere: the move there.
        tracking.retain { false }
        val usb = Paths.get("/mnt/media_rw/USB1")
        tracking.volumeAt("USB1", usb)
        assertEquals(
            TabAction.ReplaceCurrent(tab, Location.Folder(usb.resolve("docs"))),
            tracking.placeOffVolume(tab, Paths.get("/storage/USB1/docs")),
        )

        // On no volume at all: neither.
        assertNull(tracking.placeOffVolume(tab, Paths.get("/proc/self")))
        assertTrue(tracking.unavailable.value.isEmpty())
    }

    private companion object {
        const val SD = "1A2B-3C4D"
    }
}
