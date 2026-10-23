/*
 * Files — what is saved between runs: the stored forms of tabs and settings, and the
 * location tags they use.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.data

import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.Location
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Turns a [Location] into a tag string and back. */
interface LocationCodec {
    /** The tag for [l], or null when it cannot be saved (a folder on no volume). */
    fun encode(l: Location): String?

    fun decode(s: String): DecodedLocation
}

/** A tag read back. */
sealed interface DecodedLocation {
    data class Ok(val location: Location) : DecodedLocation

    /** A folder [rel] (relative to the root, `""` for the root) on the volume [uuid], which is not mounted now. */
    data class Unavailable(val uuid: String, val rel: String) : DecodedLocation {
        /**
         * Where the folder is when the volume is mounted at its usual place (`/storage/<uuid>`, the
         * primary volume at `/storage/emulated/0`), so a tab can keep it instead of going Home.
         */
        val location: Location get() = Location.Folder(usualRoot(uuid).resolve(rel))
    }

    /** Not a tag, or one that leaves its volume (`..`, an absolute path). */
    data object Invalid : DecodedLocation
}

/**
 * Location tags on the mounted [volumes]: `folder:<uuid>:<relPath>` (the path relative to the
 * volume root, `""` for the root itself), `home` and `bin`. Search results are saved as the
 * folder searched. A folder on no mounted volume is tagged by its usual mount point (see
 * [DecodedLocation.Unavailable.location]), so a tab whose volume is out keeps its place.
 * Decoding does not touch the file system.
 */
class VolumeLocationCodec(private val volumes: () -> List<Volume>) : LocationCodec {

    override fun encode(l: Location): String? = when (l) {
        Location.Home -> HOME
        Location.RecycleBin -> BIN
        is Location.Folder -> folderTag(l.path)
        is Location.SearchResults -> folderTag(l.root)
    }

    override fun decode(s: String): DecodedLocation {
        when (s) {
            HOME -> return DecodedLocation.Ok(Location.Home)
            BIN -> return DecodedLocation.Ok(Location.RecycleBin)
        }
        if (!s.startsWith(FOLDER)) return DecodedLocation.Invalid
        val rest = s.substring(FOLDER.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return DecodedLocation.Invalid
        val uuid = rest.substring(0, colon)
        if (!isTaggable(uuid)) return DecodedLocation.Invalid
        val rel = relativePath(rest.substring(colon + 1)) ?: return DecodedLocation.Invalid
        val root = volumes().firstOrNull { it.uuid == uuid && it.root != null }?.root
            ?: return DecodedLocation.Unavailable(uuid, rel.toString())
        return DecodedLocation.Ok(Location.Folder(root.resolve(rel)))
    }

    private fun folderTag(path: Path): String? {
        val (uuid, root) = mountedVolumeOf(path) ?: usualVolumeOf(path) ?: return null
        if (!isTaggable(uuid)) return null
        return "$FOLDER$uuid:${root.relativize(path)}"
    }

    /**
     * Whether [uuid] can be in a tag: the first ':' ends it, and it must be one name under
     * `/storage` (see [usualRoot]). A volume keyed by a label with ':' or '/' cannot be tagged.
     */
    private fun isTaggable(uuid: String): Boolean =
        uuid.isNotEmpty() && uuid != "." && uuid != ".." && uuid.none { it == ':' || it == '/' || it == '\u0000' }

    /** The mounted volume whose root is the longest prefix of [path], as its uuid and root. */
    private fun mountedVolumeOf(path: Path): Pair<String, Path>? = volumes()
        .mapNotNull { volume -> volume.root?.takeIf { path.startsWith(it) }?.let { volume.uuid to it } }
        .maxByOrNull { (_, root) -> root.nameCount }

    /** The volume [path] would be on at a usual mount point, the inverse of [usualRoot]. */
    private fun usualVolumeOf(path: Path): Pair<String, Path>? {
        if (path.startsWith(PRIMARY_ROOT)) return PRIMARY_UUID to PRIMARY_ROOT
        if (!path.startsWith(STORAGE) || path.nameCount < 2) return null
        val name = path.getName(1).toString()
        if (name == "emulated" || name == "self") return null
        return name to STORAGE.resolve(name)
    }

    /** [text] as a path that stays below the root it is resolved against, or null. */
    private fun relativePath(text: String): Path? {
        val path = try {
            Paths.get(text)
        } catch (e: InvalidPathException) {
            return null
        }
        if (path.isAbsolute || path.any { it.toString() == "." || it.toString() == ".." }) return null
        return path
    }

    private companion object {
        const val HOME = "home"
        const val BIN = "bin"
        const val FOLDER = "folder:"
    }
}

private const val PRIMARY_UUID = "primary"
private val STORAGE: Path = Paths.get("/storage")
private val PRIMARY_ROOT: Path = Paths.get("/storage/emulated/0")

/** Where the volume [uuid] is usually mounted. */
private fun usualRoot(uuid: String): Path = if (uuid == PRIMARY_UUID) PRIMARY_ROOT else STORAGE.resolve(uuid)

/** A tab: its current [location] and its [back] and [forward] stacks (nearest last), as tags. */
@Serializable
data class PersistedTab(val id: Long, val location: String, val back: List<String>, val forward: List<String>)

/** The open tabs in strip order and the [active] one's id. [v] is the format version, always written. */
@Serializable
data class PersistedSession(
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val v: Int = 1,
    val tabs: List<PersistedTab>,
    val active: Long,
)

/** A pinned folder: [rel] on the volume [uuid], shown as [label]. */
@Serializable
data class Favorite(val uuid: String, val rel: String, val label: String)

/** A file opened at [openedAt] (epoch milliseconds): [rel] on the volume [uuid]. */
@Serializable
data class Recent(val uuid: String, val rel: String, val openedAt: Long)

/** How a folder is shown and sorted. */
@Serializable
data class ViewSetting(val mode: ViewMode, val sort: SortSpec)

enum class ViewMode { DETAILS, LIST, TILES, LARGE_ICONS }

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** The settings that hold everywhere; [defaultView] is for folders that have no view of their own. */
@Serializable
data class GlobalSettings(
    val showHidden: Boolean = false,
    val showExtensions: Boolean = true,
    val systemAccent: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val defaultView: ViewSetting = ViewSetting(ViewMode.DETAILS, SortSpec()),
)
