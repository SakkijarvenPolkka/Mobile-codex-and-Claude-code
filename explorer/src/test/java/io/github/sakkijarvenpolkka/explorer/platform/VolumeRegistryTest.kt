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
import org.robolectric.util.ReflectionHelpers
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

    /** A USB drive without a file system UUID, in [state]: keyed by its directory, `usbotg`. */
    private fun usbVolume(state: String): StorageVolume =
        StorageVolumeBuilder("public:8,1", File("/storage/usbotg"), "USB drive", Process.myUserHandle(), state)
            .setIsPrimary(false)
            .setIsRemovable(true)
            .setFsUuid(null)
            .build()

    /**
     * Delivers [volume]'s new state to the storage volume callbacks registered with StorageManager.
     * Main thread; the callbacks run at once rather than on the executor they were registered with.
     */
    private fun deliverVolumeState(volume: StorageVolume) {
        val manager = context.getSystemService(StorageManager::class.java)
        val delegates: List<Any> = ReflectionHelpers.getField(manager, "mDelegates")
        for (delegate in delegates.toList()) {
            ReflectionHelpers.getField<StorageManager.StorageVolumeCallback?>(delegate, "mCallback")
                ?.onStateChanged(volume)
        }
    }

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
    fun secondaryVolumeWithoutFsUuidIsKeyedByItsDirectory() {
        fun usb(label: String, directory: File?, state: String = "mounted", mountedKey: String? = null) =
            volumeFrom(null, label, directory, state, primary = false, removable = true, mountedKey = mountedKey)

        // The directory's name, not the localized description: the same in every language, and two
        // drives both described as "USB drive" do not collide.
        assertEquals("usbotg", usb("USB drive", File("/storage/usbotg")).uuid)
        assertEquals("usbotg", usb("USB 드라이브", File("/storage/usbotg")).uuid)
        assertEquals("usbotg2", usb("USB drive", File("/storage/usbotg2")).uuid)
        // Without a directory (Android reports none unless mounted), it keeps the key it had while
        // mounted; only a volume never seen mounted falls back to its description.
        assertEquals("usbotg", usb("USB drive", null, "ejecting", mountedKey = "usbotg").uuid)
        assertEquals("USB drive", usb("USB drive", null, "unmounted").uuid)
    }

    @Test
    fun onlyVfatVolumesGetTheFat32SizeLimit() {
        fun card(fsType: String?) = volumeFrom(
            "1A2B-3C4D", "SD card", File("/storage/1A2B-3C4D"), "mounted", primary = false, removable = true,
            fsType = fsType,
        )

        assertEquals(4_294_967_295L, card("vfat").traits.maxFileSize)
        assertTrue(card("vfat").traits.fatNames)
        // exFAT holds files of any size, and a type that cannot be told sets no limit.
        for (other in listOf("exfat", "fuse", null)) {
            assertNull("$other", card(other).traits.maxFileSize)
            assertTrue("$other", card(other).traits.fatNames)
        }
    }

    @Test
    fun fsTypeForFindsTheMountUnderFuse() {
        val sd = listOf("/storage/1A2B-3C4D", "/mnt/media_rw/1A2B-3C4D")
        val usb = listOf("/storage/5E6F-7A8B", "/mnt/media_rw/5E6F-7A8B")

        // The card's root is the FUSE layer; what it serves is the vfat mount under it.
        assertEquals("vfat", fsTypeFor(sd, MOUNTS))
        assertEquals("exfat", fsTypeFor(usb, MOUNTS))
        assertEquals("vfat", fsTypeFor(listOf("/mnt/media_rw/1A2B-3C4D"), MOUNTS))
        // Only the FUSE layer listed, or nothing: not known.
        val fuseOnly = MOUNTS.lines().filterNot { "/mnt/media_rw/" in it }.joinToString("\n")
        assertNull(fsTypeFor(sd, fuseOnly))
        assertNull(fsTypeFor(listOf("/storage/emulated/0"), MOUNTS))
        assertNull(fsTypeFor(listOf("/storage/1A2B-3C4"), MOUNTS))
        assertNull(fsTypeFor(sd, ""))
        // Spaces in a mount point are written as \040.
        assertEquals("vfat", fsTypeFor(listOf("/mnt/my usb"), "/dev/block/sda1 /mnt/my\\040usb vfat rw 0 0"))
    }

    @Test
    fun registryReadsTheFileSystemTypeOfEachVolume() {
        storage.addStorageVolume(primaryVolume())
        storage.addStorageVolume(publicVolume("1A2B-3C4D", "/storage/1A2B-3C4D"))
        storage.addStorageVolume(publicVolume("5E6F-7A8B", "/storage/5E6F-7A8B"))

        val registry = VolumeRegistry(context, mounts = { MOUNTS })

        assertEquals(4_294_967_295L, registry.byUuid("1A2B-3C4D")?.traits?.maxFileSize)
        assertNull(registry.byUuid("5E6F-7A8B")?.traits?.maxFileSize)
        assertNull(registry.byUuid("primary")?.traits?.maxFileSize)
        // Without the mount table, nothing is known.
        assertNull(VolumeRegistry(context, mounts = { null }).byUuid("1A2B-3C4D")?.traits?.maxFileSize)
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
            deliverBroadcast(
                context,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun volumeWithoutFsUuidKeepsItsKeyWhileItEjects() = runTest {
        storage.addStorageVolume(primaryVolume())
        storage.addStorageVolume(usbVolume("mounted"))
        val registry = VolumeRegistry(context)
        registry.start()
        assertEquals(Paths.get("/storage/usbotg"), registry.byUuid("usbotg")?.root)
        val events = mutableListOf<VolumeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { registry.events.toList(events) }

        // The drive goes to state, told by the broadcast action or, without one, by the callback.
        fun change(state: String, action: String? = null) {
            val volume = usbVolume(state)
            storage.resetStorageVolumeList()
            storage.addStorageVolume(primaryVolume())
            storage.addStorageVolume(volume)
            if (action == null) {
                deliverVolumeState(volume)
            } else {
                deliverBroadcast(
                    context,
                    Intent(action, Uri.fromFile(File("/storage/usbotg")))
                        .putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, volume),
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
        }

        change("mounted")
        // Android reports no directory once the drive is no longer mounted.
        assertNull(usbVolume("ejecting").directory)
        change("ejecting", Intent.ACTION_MEDIA_EJECT)
        assertEquals(VolumeState.EJECTING, registry.byUuid("usbotg")?.state)
        change("mounted", Intent.ACTION_MEDIA_MOUNTED)
        change("ejecting")
        change("unmounted")
        assertEquals(VolumeState.UNMOUNTED, registry.byUuid("usbotg")?.state)
        change("mounted")

        val mounted = VolumeEvent.Mounted("usbotg")
        val ejecting = VolumeEvent.Ejecting("usbotg")
        assertEquals(listOf(mounted, ejecting, mounted, ejecting, mounted), events)
    }

    private companion object {
        /** `/proc/self/mounts` of a phone with a vfat SD card and an exFAT USB drive, both behind FUSE. */
        val MOUNTS = """
            /dev/block/dm-5 / ext4 ro,seclabel,relatime 0 0
            tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
            /dev/block/vold/public:179,129 /mnt/media_rw/1A2B-3C4D vfat rw,dirsync,nosuid,nodev,noexec,uid=1023 0 0
            /dev/fuse /mnt/user/0/1A2B-3C4D fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0 0 0
            /dev/fuse /storage/1A2B-3C4D fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0 0 0
            /dev/block/vold/public:8,1 /mnt/media_rw/5E6F-7A8B exfat rw,dirsync,nosuid,nodev,noexec,uid=1023 0 0
            /dev/fuse /storage/5E6F-7A8B fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0 0 0
            /dev/fuse /storage/emulated fuse rw,lazytime,nosuid,nodev,noexec,noatime,user_id=0 0 0
        """.trimIndent()
    }
}
