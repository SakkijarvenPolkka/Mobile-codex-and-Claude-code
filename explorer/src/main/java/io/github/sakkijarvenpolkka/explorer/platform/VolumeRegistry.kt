/*
 * Files — the storage volumes, their state, and their eject and mount events.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/** A volume's state, from `StorageVolume.getState()`. */
enum class VolumeState { MOUNTED, MOUNTED_READ_ONLY, EJECTING, UNMOUNTED, OTHER }

/**
 * A storage volume. [uuid] is `primary` for the primary volume. [root] is null unless the
 * volume is mounted, so also while it ejects. [fuseBacked] means its directory is under
 * `/storage` (shared storage through FUSE) rather than a raw mount such as `/mnt/media_rw/<uuid>`.
 */
data class Volume(
    val uuid: String,
    val label: String,
    val root: Path?,
    val state: VolumeState,
    val isPrimary: Boolean,
    val isRemovable: Boolean,
    val fuseBacked: Boolean,
    val traits: VolumeTraits,
)

/**
 * A [Volume] from a `StorageVolume`'s fields: [state] is its `getState()` string, [directory]
 * its `getDirectory()`. A secondary volume without a file system UUID is keyed by its [label].
 * Removable volumes get the FAT naming rules.
 */
fun volumeFrom(
    uuid: String?,
    label: String,
    directory: File?,
    state: String,
    primary: Boolean,
    removable: Boolean,
): Volume {
    val volumeState = when (state) {
        "mounted" -> VolumeState.MOUNTED
        "mounted_ro" -> VolumeState.MOUNTED_READ_ONLY
        "ejecting" -> VolumeState.EJECTING
        "unmounted", "removed", "bad_removal" -> VolumeState.UNMOUNTED
        else -> VolumeState.OTHER
    }
    val path = directory?.toPath()
    val mounted = volumeState == VolumeState.MOUNTED || volumeState == VolumeState.MOUNTED_READ_ONLY
    return Volume(
        uuid = if (primary) PRIMARY_UUID else uuid ?: label,
        label = label,
        root = if (mounted) path else null,
        state = volumeState,
        isPrimary = primary,
        isRemovable = removable,
        fuseBacked = path?.startsWith(FUSE_ROOT) == true,
        traits = VolumeTraits(fatNames = removable),
    )
}

private const val PRIMARY_UUID = "primary"
private val FUSE_ROOT: Path = Paths.get("/storage")

/** The volumes and their changes; what the UI depends on, so tests can feed their own. */
interface VolumeSource {
    /** Every volume Android reports, mounted or not, in its order (primary first). */
    val volumes: StateFlow<List<Volume>>

    /** Changes of single volumes, after [volumes] shows them. Not replayed. */
    val events: SharedFlow<VolumeEvent>
}

/** A change of the volume [uuid]. */
sealed interface VolumeEvent {
    val uuid: String

    /**
     * The volume is ejecting, unmounted or gone: stop using its files at once, because vold
     * kills the processes that still hold them open a few seconds later.
     */
    data class Ejecting(override val uuid: String) : VolumeEvent

    data class Mounted(override val uuid: String) : VolumeEvent
}

/**
 * The device's volumes from [StorageManager], read when created and on [refresh]; [start]
 * follows their changes through the storage volume callback and the `ACTION_MEDIA_EJECT` and
 * `ACTION_MEDIA_MOUNTED` broadcasts. Each change refreshes [volumes], then sends one event
 * (the callback and the broadcast for the same change send it once).
 */
class VolumeRegistry(context: Context) : VolumeLookup, VolumeSource {

    private val context: Context = context.applicationContext
    private val storage: StorageManager = this.context.getSystemService(StorageManager::class.java)

    private val _volumes = MutableStateFlow(read())
    override val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    private val _events = MutableSharedFlow<VolumeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<VolumeEvent> = _events.asSharedFlow()

    /** The last event sent for each volume. Main thread only, as are [started] and the listeners. */
    private val lastEvent = HashMap<String, VolumeEvent>()
    private var started = false

    private val callback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            refresh()
            val changed = toVolume(volume)
            when (changed.state) {
                VolumeState.EJECTING, VolumeState.UNMOUNTED -> send(VolumeEvent.Ejecting(changed.uuid))
                VolumeState.MOUNTED, VolumeState.MOUNTED_READ_ONLY -> send(VolumeEvent.Mounted(changed.uuid))
                VolumeState.OTHER -> Unit
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
            val volume = IntentCompat.getParcelableExtra(
                intent, StorageVolume.EXTRA_STORAGE_VOLUME, StorageVolume::class.java,
            ) ?: return
            val uuid = toVolume(volume).uuid
            when (intent.action) {
                Intent.ACTION_MEDIA_EJECT -> send(VolumeEvent.Ejecting(uuid))
                Intent.ACTION_MEDIA_MOUNTED -> send(VolumeEvent.Mounted(uuid))
            }
        }
    }

    /** Starts following volume changes for the life of the process. Main thread; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        storage.registerStorageVolumeCallback(context.mainExecutor, callback)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        // System broadcasts still reach a receiver that is not exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    /** Reads the volumes again. */
    @Synchronized
    fun refresh() {
        _volumes.value = read()
    }

    /** The primary volume's root; its usual path while it is not mounted. */
    fun primaryRoot(): Path = volumes.value.firstOrNull { it.isPrimary }?.root ?: defaultPrimaryRoot()

    fun byUuid(uuid: String): Volume? = volumes.value.firstOrNull { it.uuid == uuid }

    override fun volumeOf(path: Path): VolumeRoot? {
        var best: VolumeRoot? = null
        for (volume in volumes.value) {
            val root = volume.root ?: continue
            if (path.startsWith(root) && (best == null || root.nameCount > best.root.nameCount)) {
                best = VolumeRoot(volume.uuid, root, volume.traits, volume.isRemovable)
            }
        }
        return best
    }

    private fun read(): List<Volume> = storage.storageVolumes.map(::toVolume)

    private fun toVolume(volume: StorageVolume): Volume = volumeFrom(
        uuid = volume.uuid,
        label = volume.getDescription(context),
        directory = volume.directory,
        state = volume.state,
        primary = volume.isPrimary,
        removable = volume.isRemovable,
    )

    private fun send(event: VolumeEvent) {
        if (lastEvent.put(event.uuid, event) != event) _events.tryEmit(event)
    }

    /** All files access lets the app use this path, deprecated for everyone else. */
    @Suppress("DEPRECATION")
    private fun defaultPrimaryRoot(): Path = Environment.getExternalStorageDirectory().toPath()
}
