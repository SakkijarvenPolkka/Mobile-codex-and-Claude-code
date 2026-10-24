/*
 * Files — Home (spec §2.4): quick access, the drives and how full they are, the files opened last and the
 * size of the Recycle Bin, read off the main thread; and the favorites, pinned, unpinned and moved in the
 * user's order (spec §3.5).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.util.Log
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.data.VolumeLocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.isPresent
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A folder of quick access: a favorite ([pinned]), with its [label], or a default folder, with its
 * name on disk as [label] (the shell shows a default folder by its name in the app's language).
 */
data class QuickItem(val path: Path, val label: String, val pinned: Boolean)

/** How full [volume] is: [total] bytes, of which [free] are free. */
data class DriveUsage(val volume: Volume, val total: Long, val free: Long)

/**
 * What Home shows: [quickAccess], the favorites in the user's order, then the default folders there are
 * that are not pinned; the mounted [drives] whose size is known; the [recents], newest first, that are
 * still there; and [binSize], the bytes in the Recycle Bins.
 */
data class HomeData(
    val quickAccess: List<QuickItem>,
    val drives: List<DriveUsage>,
    val recents: List<FileEntry>,
    val binSize: Long,
)

/**
 * A favorite as the navigation pane shows it: at [path] on its volume while that is mounted ([available]),
 * else where the volume is usually mounted, kept but dimmed.
 */
data class PinnedFolder(val favorite: Favorite, val path: Path, val available: Boolean)

/** The default folders of quick access, by name under the primary volume's root, with what they are called. */
internal val DEFAULT_FOLDERS: List<Pair<String, Int>> = listOf(
    "Download" to R.string.folder_download,
    "DCIM" to R.string.folder_dcim,
    "Pictures" to R.string.folder_pictures,
    "Documents" to R.string.folder_documents,
    "Music" to R.string.folder_music,
    "Movies" to R.string.folder_movies,
)

/** The default folders under [primaryRoot] that are there (as folders): each one's name and path, in this order. */
fun defaultFolders(primaryRoot: Path): List<Pair<String, Path>> = DEFAULT_FOLDERS
    .map { (name, _) -> name to primaryRoot.resolve(name) }
    .filter { (_, path) -> Files.isDirectory(path) }

/**
 * Where each of [favorites] is (see [PinnedFolder]), as [codec] places them; one that does not decode is
 * left out, as is a second one of the same folder.
 */
fun pinnedFolders(favorites: List<Favorite>, codec: VolumeLocationCodec): List<PinnedFolder> = favorites
    .mapNotNull { favorite ->
        when (val place = codec.decodeFolder(favorite.uuid, favorite.rel)) {
            is DecodedLocation.Ok -> (place.location as? Location.Folder)?.let { PinnedFolder(favorite, it.path, true) }
            // Kept, dimmed, until its volume is back.
            is DecodedLocation.Unavailable -> PinnedFolder(favorite, place.location.path, available = false)
            DecodedLocation.Invalid -> null
        }
    }
    .distinctBy { it.path }

/**
 * Reads Home on [ExplorerDeps.io] (see [HomeData]); [ExplorerDeps.usage] tells how full each drive is. A
 * recent file gone from its mounted volume, or one whose entry does not decode, is forgotten as well
 * ([SettingsStore.removeRecents]); one on a volume that is out, or that cannot be read now, is only left out.
 */
suspend fun loadHome(deps: ExplorerDeps, codec: VolumeLocationCodec): HomeData = withContext(deps.io) {
    val volumes = deps.volumes.volumes.value
    val favorites = pinnedFolders(deps.settings.favorites.value, codec)
    val pinned = favorites.mapTo(HashSet()) { it.path }
    val defaults = volumes.firstOrNull { it.isPrimary }?.root?.let(::defaultFolders).orEmpty()
    val quickAccess = favorites.map { QuickItem(it.path, it.favorite.label, pinned = true) } +
        defaults.filter { (_, path) -> path !in pinned }.map { (name, path) -> QuickItem(path, name, pinned = false) }
    val drives = volumes.mapNotNull { volume ->
        val usage = volume.root?.let(deps.usage)?.takeIf { (total, _) -> total > 0 }
        usage?.let { (total, free) -> DriveUsage(volume, total, free) }
    }
    val binSize = deps.bin?.totalSize(volumes.mapNotNull { it.root }) ?: 0L
    HomeData(quickAccess, drives, recentFiles(deps, codec), binSize)
}

/** The recent files that are there, newest first; see [loadHome] for those that are not. */
private fun recentFiles(deps: ExplorerDeps, codec: VolumeLocationCodec): List<FileEntry> {
    val gone = HashSet<Recent>()
    val files = ArrayList<FileEntry>()
    for (recent in deps.settings.recents.value) {
        val place = codec.decodeFolder(recent.uuid, recent.rel)
        // Its volume may come back.
        if (place is DecodedLocation.Unavailable) continue
        val path = ((place as? DecodedLocation.Ok)?.location as? Location.Folder)?.path
        try {
            if (path == null || !deps.fs.isPresent(path)) gone += recent
            else files += Listing.entry(deps.fs, path, deps.mime::mimeOf)
        } catch (e: IOException) {
            // There but not readable now, or it went just now: the next reading tells.
        }
    }
    if (gone.isNotEmpty()) deps.settings.removeRecents { it in gone }
    return files.distinctBy { it.path }
}

/** What Pin and Unpin act on: the [selected] folders, else the folder [location] is ([allPinned]). */
internal fun pinTargets(selected: List<FileEntry>, location: Location): List<Path> =
    if (selected.isEmpty()) listOfNotNull((location as? Location.Folder)?.path)
    else selected.filter { it.kind == EntryKind.DIR }.map { it.path }

/**
 * Pins the folders [paths] after the favorites there are, each by its name (a volume's root by the
 * volume's label); a folder on no mounted volume, or one pinned already, is left out.
 */
internal fun ExplorerDeps.pin(paths: List<Path>) {
    val favorites = settings.favorites.value
    val added = paths.mapNotNull { path ->
        val volume = lookup.volumeOf(path) ?: return@mapNotNull null
        val rel = volume.root.relativize(path).toString()
        val label = if (rel.isEmpty()) volumes.volumes.value.firstOrNull { it.uuid == volume.uuid }?.label else null
        Favorite(volume.uuid, rel, label ?: path.fileName?.toString() ?: volume.uuid)
    }
    val pinned = (favorites + added).distinctBy { it.uuid to it.rel }
    if (pinned != favorites) settings.setFavorites(pinned)
}

/** Unpins the folders [paths], on mounted volumes. */
internal fun ExplorerDeps.unpin(paths: List<Path>) {
    val places = paths.mapNotNullTo(HashSet()) { path ->
        lookup.volumeOf(path)?.let { it.uuid to it.root.relativize(path).toString() }
    }
    unpinWhere { (it.uuid to it.rel) in places }
}

/** Unpins [favorite], also while its volume is out. */
internal fun ExplorerDeps.unpin(favorite: Favorite) = unpinWhere { it == favorite }

/** Moves the favorite at [from] to [to], the others keeping their order; nothing when either is outside the list. */
internal fun ExplorerDeps.reorderFavorites(from: Int, to: Int) {
    val favorites = settings.favorites.value.toMutableList()
    if (from !in favorites.indices || to !in favorites.indices || from == to) return
    favorites.add(to, favorites.removeAt(from))
    settings.setFavorites(favorites)
}

private fun ExplorerDeps.unpinWhere(gone: (Favorite) -> Boolean) {
    val favorites = settings.favorites.value
    val kept = favorites.filterNot(gone)
    if (kept.size != favorites.size) settings.setFavorites(kept)
}

/**
 * Home's content ([state]), the same for every tab at Home: read when a tab comes to Home or reads it
 * again ([load]), and while one shows it, whenever the favorites, the recent files or the volumes change
 * ([follow]); a change while none shows it makes it [stale]. A load cancels the one before; until it is
 * done, what was read before stays. Main thread only, in [scope].
 */
internal class HomeLoader(
    private val deps: ExplorerDeps,
    private val codec: VolumeLocationCodec,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<HomeData?>(null)

    /** What Home shows; null until it is first read. */
    val state: StateFlow<HomeData?> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Whether what was read is out of date: what Home shows changed since, while no tab showed it. */
    var stale = false
        private set

    fun load() {
        stale = false
        job?.cancel()
        job = scope.launch {
            try {
                mutableState.value = loadHome(deps, codec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read Home; what was read before stays", e)
            }
        }
    }

    /**
     * Reads Home again whenever the favorites, the recent files or the volumes change while [atHome];
     * else what was read is [stale], to be read again when a tab shows Home.
     */
    suspend fun follow(atHome: () -> Boolean) {
        val settings = deps.settings
        combine(settings.favorites, settings.recents, deps.volumes.volumes) { _, _, _ -> }
            .drop(1)
            .collect { if (atHome()) load() else stale = true }
    }

    private companion object {
        const val TAG = "HomeLoader"
    }
}
