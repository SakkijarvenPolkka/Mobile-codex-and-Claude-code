/*
 * Files — the tabs on volumes that are not mounted, and where they go when their volume comes
 * back.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.LocationCodec
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path

/**
 * What a volume event did to the tabs: the tabs [ejected] from their folders (unavailable now),
 * and the [moves] that put tabs where their volume is mounted now.
 */
data class VolumeChange(val ejected: Set<TabId> = emptySet(), val moves: List<TabAction> = emptyList())

/**
 * Which tabs are in folders of a volume that is not mounted ([unavailable]: they keep their
 * place and show the drive as unavailable), and where each volume was last mounted, to find the
 * tabs on one once it is gone. It moves no tab itself: it returns the [TabAction.ReplaceCurrent]s
 * for the view model to dispatch, and a mark lasts until the tab moves ([retain]) or its folder is
 * on the volume again. [restored] are the tabs a saved session left on volumes that were out at
 * start. Main thread only.
 */
class VolumeTracking(
    private val volumes: VolumeSource,
    private val codec: LocationCodec,
    restored: Map<TabId, DecodedLocation.Unavailable> = emptyMap(),
) {
    private val marks = MutableStateFlow(restored)

    /** The unavailable tabs, with where on their volume each one is. */
    val unavailable: StateFlow<Map<TabId, DecodedLocation.Unavailable>> = marks.asStateFlow()

    private val lastRoots = HashMap<String, Path>()

    init {
        rememberRoots(volumes.volumes.value)
    }

    /** Notes where the mounted volumes of [list] are; called with every change of the volume list. */
    fun rememberRoots(list: List<Volume>) {
        for (volume in list) volume.root?.let { lastRoots[volume.uuid] = it }
    }

    /**
     * What [e] does to the tabs of [model]. Ejecting: the tabs in folders of that volume become
     * unavailable. Mounted: the tabs waiting for it whose folder is where it was are no longer;
     * the others (it mounted at another root, or a saved tab only guessed the mount point) get
     * the move to their folder decoded from (uuid, rel) on its root now, without a history entry.
     */
    fun onVolumeEvent(e: VolumeEvent, model: TabsModel): VolumeChange = when (e) {
        is VolumeEvent.Ejecting -> VolumeChange(ejected = markEjected(e.uuid, model))
        is VolumeEvent.Mounted -> VolumeChange(moves = remount(e.uuid, model))
    }

    /**
     * [tab] is at [folder], which is on no mounted volume (a place kept from before its volume
     * went out or moved). While that volume is out, the tab is marked unavailable; when it is
     * mounted elsewhere, the result is the move to the same folder there. Null after marking it,
     * and when the folder is on no volume at all ([unavailable] tells the two apart).
     */
    fun placeOffVolume(tab: TabId, folder: Path): TabAction? = when (val place = placeOf(folder)) {
        is DecodedLocation.Unavailable -> {
            marks.update { it + (tab to place) }
            null
        }
        is DecodedLocation.Ok -> TabAction.ReplaceCurrent(tab, place.location)
        DecodedLocation.Invalid -> null
    }

    /** Keeps the marks of the tabs that [stayed] where they were marked; the others moved or closed. */
    fun retain(stayed: (TabId) -> Boolean) = marks.update { it.filterKeys(stayed) }

    /** Marks the tabs of [model] in folders of the volume [uuid], which is going; returns them. */
    private fun markEjected(uuid: String, model: TabsModel): Set<TabId> {
        val root = lastRoots[uuid] ?: return emptySet()
        val gone = model.tabs.mapNotNull { tab ->
            val path = (tab.current.location as? Location.Folder)?.path?.takeIf { it.startsWith(root) }
            path?.let { tab.id to DecodedLocation.Unavailable(uuid, root.relativize(it).toString()) }
        }.toMap()
        if (gone.isNotEmpty()) marks.update { it + gone }
        return gone.keys
    }

    /** See [onVolumeEvent]; the tabs that move stop waiting once they have moved ([retain]). */
    private fun remount(uuid: String, model: TabsModel): List<TabAction> {
        val root = volumes.volumes.value.firstOrNull { it.uuid == uuid }?.root ?: return emptyList()
        val places = marks.value.filterValues { it.uuid == uuid }.mapValues { (_, gone) ->
            Location.Folder(root.resolve(gone.rel))
        }
        val (there, elsewhere) = places.entries.partition { (id, place) -> model.locationOf(id) == place }
        if (there.isNotEmpty()) marks.update { it - there.map { (id, _) -> id }.toSet() }
        return elsewhere.map { (id, place) -> TabAction.ReplaceCurrent(id, place) }
    }

    /**
     * Which volume [path], on no mounted one, belongs to: one that was mounted at the root it is
     * under and is not now, else the one whose usual mount point it is under (as [codec] places
     * it: unavailable, or the same folder where the volume is mounted now). Invalid for neither.
     */
    private fun placeOf(path: Path): DecodedLocation {
        val known = lastRoots.entries.filter { path.startsWith(it.value) }.maxByOrNull { it.value.nameCount }
        if (known != null && volumes.volumes.value.none { it.uuid == known.key && it.root != null }) {
            return DecodedLocation.Unavailable(known.key, known.value.relativize(path).toString())
        }
        return codec.encode(Location.Folder(path))?.let(codec::decode) ?: DecodedLocation.Invalid
    }
}
