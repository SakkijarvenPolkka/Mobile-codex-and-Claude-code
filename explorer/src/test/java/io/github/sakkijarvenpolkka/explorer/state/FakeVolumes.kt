// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path

/** Volumes the test controls: set [volumes], then send the matching [events]. */
class FakeVolumes(vararg initial: Volume) : VolumeSource, VolumeLookup {
    override val volumes = MutableStateFlow(initial.toList())
    override val events = MutableSharedFlow<VolumeEvent>(extraBufferCapacity = 16)

    override fun volumeOf(path: Path): VolumeRoot? = volumes.value
        .filter { it.root != null && path.startsWith(it.root) }
        .maxByOrNull { it.root!!.nameCount }
        ?.let { VolumeRoot(it.uuid, it.root!!, it.traits, it.isRemovable) }

    /** Replaces the volume [uuid] with [change] of it. */
    fun update(uuid: String, change: (Volume) -> Volume) {
        volumes.value = volumes.value.map { if (it.uuid == uuid) change(it) else it }
    }

    companion object {
        fun primary(root: Path) = Volume(
            "primary", "Internal shared storage", root, VolumeState.MOUNTED,
            isPrimary = true, isRemovable = false, fuseBacked = true, traits = VolumeTraits(),
        )

        fun removable(uuid: String, label: String, root: Path?, state: VolumeState = VolumeState.MOUNTED) = Volume(
            uuid, label, root, state,
            isPrimary = false, isRemovable = true, fuseBacked = root?.startsWith("/mnt/media_rw") != true,
            traits = VolumeTraits(fatNames = true),
        )
    }
}
