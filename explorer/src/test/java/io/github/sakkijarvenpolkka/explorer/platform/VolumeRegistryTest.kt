// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeRegistryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = shadowOf(context.getSystemService(StorageManager::class.java))

    private fun primaryVolume(): StorageVolume =
        StorageVolumeBuilder(
            "emulated;0", File("/storage/emulated/0"), "Internal shared storage", Process.myUserHandle(), "mounted",
        )
            .setIsPrimary(true)
            .setIsEmulated(true)
            .build()

    private fun publicVolume(uuid: String, dir: String, state: String = "mounted"): StorageVolume =
        StorageVolumeBuilder("public:$uuid", File(dir), "SD card", Process.myUserHandle(), state)
            .setIsPrimary(false)
            .setIsRemovable(true)
            .setFsUuid(uuid)
            .build()

    private fun sdState(state: String): Volume =
        volumeFrom("1A2B-3C4D", "SD card", File("/storage/1A2B-3C4D"), state, primary = false, removable = true)

    @Test
    fun volumeFromMapsPrimaryAndRemovable() {
        val primary = volumeFrom(
            null, "Internal shared storage", File("/storage/emulated/0"), "mounted", primary = true, removable = false,
        )
        assertEquals("primary", primary.uuid)
        assertEquals("Internal shared storage", primary.label)
        assertEquals(Paths.get("/storage/emulated/0"), primary.root)
        assertEquals(VolumeState.MOUNTED, primary.state)
        assertTrue(primary.isPrimary)
        assertFalse(primary.isRemovable)
        assertTrue(primary.fuseBacked)
        assertFalse(primary.traits.fatNames)
        assertTrue(primary.traits.caseInsensitive)

        val sd = sdState("mounted")
        assertEquals("1A2B-3C4D", sd.uuid)
        assertEquals(Paths.get("/storage/1A2B-3C4D"), sd.root)
        assertFalse(sd.isPrimary)
        assertTrue(sd.isRemovable)
        assertTrue(sd.fuseBacked)
        assertTrue(sd.traits.fatNames)
        assertTrue(sd.traits.caseInsensitive)

        val usb = volumeFrom(
            "1A2B-3C4D", "USB drive", File("/mnt/media_rw/1A2B-3C4D"), "mounted", primary = false, removable = true,
        )
        assertEquals(Paths.get("/mnt/media_rw/1A2B-3C4D"), usb.root)
        assertFalse(usb.fuseBacked)

        assertEquals(VolumeState.EJECTING, sdState("ejecting").state)
        assertNull(sdState("ejecting").root)
        for (state in listOf("unmounted", "removed", "bad_removal")) {
            assertEquals(state, VolumeState.UNMOUNTED, sdState(state).state)
            assertNull(state, sdState(state).root)
        }
        assertEquals(VolumeState.MOUNTED_READ_ONLY, sdState("mounted_ro").state)
        assertEquals(Paths.get("/storage/1A2B-3C4D"), sdState("mounted_ro").root)
        assertEquals(VolumeState.OTHER, sdState("checking").state)
        assertNull(sdState("checking").root)
    }

    @Test
    fun volumeOfPicksLongestRoot() {
        storage.addStorageVolume(primaryVolume())
        storage.addStorageVolume(publicVolume("1A2B-3C4D", "/storage/1A2B-3C4D"))
        val registry = VolumeRegistry(context)

        val sd = registry.volumeOf(Paths.get("/storage/1A2B-3C4D/DCIM/x"))
        assertEquals("1A2B-3C4D", sd?.uuid)
        assertEquals(Paths.get("/storage/1A2B-3C4D"), sd?.root)
        assertEquals(true, sd?.removable)
        assertEquals(true, sd?.traits?.fatNames)
        assertEquals("primary", registry.volumeOf(Paths.get("/storage/emulated/0/Download"))?.uuid)
        assertEquals("primary", registry.volumeOf(Paths.get("/storage/emulated/0"))?.uuid)
        assertNull(registry.volumeOf(Paths.get("/storage/1A2B-3C4D0/x")))
        assertNull(registry.volumeOf(Paths.get("/proc/self")))

        // A root inside another root wins for the paths under it.
        storage.addStorageVolume(publicVolume("5E6F-7A8B", "/storage/emulated/0/usb"))
        registry.refresh()
        assertEquals("5E6F-7A8B", registry.volumeOf(Paths.get("/storage/emulated/0/usb/a"))?.uuid)
        assertEquals("primary", registry.volumeOf(Paths.get("/storage/emulated/0/usbx"))?.uuid)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ejectAndMountBroadcastsRefreshAndEmitOnce() = runTest {
        storage.addStorageVolume(primaryVolume())
        storage.addStorageVolume(publicVolume("1A2B-3C4D", "/storage/1A2B-3C4D"))
        val registry = VolumeRegistry(context)
        registry.start()
        assertEquals(Paths.get("/storage/emulated/0"), registry.primaryRoot())
        assertEquals("SD card", registry.byUuid("1A2B-3C4D")?.label)
        val events = mutableListOf<VolumeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { registry.events.toList(events) }

        fun broadcast(action: String, state: String) {
            val volume = publicVolume("1A2B-3C4D", "/storage/1A2B-3C4D", state)
            storage.resetStorageVolumeList()
            storage.addStorageVolume(primaryVolume())
            storage.addStorageVolume(volume)
            context.sendBroadcast(
                Intent(action, Uri.fromFile(File("/storage/1A2B-3C4D")))
                    .putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume),
            )
            shadowOf(Looper.getMainLooper()).idle()
        }

        broadcast(Intent.ACTION_MEDIA_EJECT, "ejecting")
        broadcast(Intent.ACTION_MEDIA_EJECT, "ejecting")
        assertEquals(listOf<VolumeEvent>(VolumeEvent.Ejecting("1A2B-3C4D")), events)
        assertEquals(VolumeState.EJECTING, registry.byUuid("1A2B-3C4D")?.state)
        assertNull(registry.volumeOf(Paths.get("/storage/1A2B-3C4D/DCIM")))

        broadcast(Intent.ACTION_MEDIA_MOUNTED, "mounted")
        assertEquals(listOf(VolumeEvent.Ejecting("1A2B-3C4D"), VolumeEvent.Mounted("1A2B-3C4D")), events)
        assertEquals(Paths.get("/storage/1A2B-3C4D"), registry.byUuid("1A2B-3C4D")?.root)
    }
}
