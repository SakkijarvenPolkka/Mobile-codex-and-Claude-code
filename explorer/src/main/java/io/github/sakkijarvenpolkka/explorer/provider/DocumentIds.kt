/*
 * Files — the document ids the system file picker knows files and folders by, and the checks that keep
 * them to what the app shows (pick spec §3.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The document id of the provider's own top folder, which holds the favorites and the drives. */
const val ROOT_DOC_ID = "root"

/** The picker's failure for the document [id]: [why], and what the file system said ([cause]), if anything. */
internal fun notFound(why: String, id: String, cause: IOException? = null): FileNotFoundException =
    FileNotFoundException("$why: $id").apply { if (cause != null) initCause(cause) }

/**
 * The ids of the files and folders on the [mounted] volumes, and the paths they name. An id is
 * `<uuid>:<rel>`: the volume's [VolumeRoot.uuid], then the path from its root with `/` between the names
 * (empty for the root itself). It is split at its first colon only, since names may have more.
 */
class DocumentIds(private val fs: FsBackend, private val mounted: () -> List<VolumeRoot>) {

    /**
     * The path [id] names: its volume's root, then its names. Throws [FileNotFoundException] unless, in order:
     * 1. its volume is mounted now;
     * 2. none of its names has a NUL or is empty (so no leading `/`), `.` or `..`;
     * 3. the path is there, and its real path is under its volume root's (no link leads out of the volume);
     * 4. none of its names is one of the app's own ([HiddenNames.isInternal]);
     * 5. it is not in a folder Android withholds ([isRestrictedAppDataPath]), not even `Android/obb`.
     *
     * Rules 4 and 5 also hold for where its links lead. And the path is on its volume ([volumeOf]), not on one
     * mounted inside it, so that an item has one id. When the file system cannot tell where the path leads (it is
     * not there, a link is broken, ...), what it said is the refusal's cause.
     */
    fun resolve(id: String): Path {
        val colon = id.indexOf(':')
        if (colon < 0) throw notFound("not a document id", id)
        val uuid = id.substring(0, colon)
        // One reading of the volumes for all the rules.
        val volumes = mounted()
        val volume = volumes.firstOrNull { it.uuid == uuid } ?: throw notFound("volume not mounted", id)
        val rel = id.substring(colon + 1)
        val names = if (rel.isEmpty()) emptyList() else rel.split('/')
        if (!names.all(::isPlainName)) throw notFound("not a plain path", id)
        val path = try {
            volume.root.resolve(rel)
        } catch (e: InvalidPathException) {
            throw notFound("not a path", id)
        }
        if (volumeOf(path, volumes)?.uuid != uuid) throw notFound("on another volume", id)
        refusal(volume, names, path)?.let { throw notFound(it.why, id, it.cause) }
        return path
    }

    /** The id [resolve] gives [path] back for, or null if there is none: on no mounted volume, or against a rule. */
    fun idOf(path: Path): String? {
        val volume = volumeOf(path) ?: return null
        val names = path.drop(volume.root.nameCount).map { it.toString() }
        if (!names.all(::isPlainName) || refusal(volume, names, path) != null) return null
        return volume.uuid + ":" + names.joinToString("/")
    }

    /** The mounted volume whose root is the longest start of [path] (by whole names, as written), or null. */
    fun volumeOf(path: Path): VolumeRoot? = volumeOf(path, mounted())

    private fun volumeOf(path: Path, volumes: List<VolumeRoot>): VolumeRoot? =
        volumes.filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }

    /** Why [path], the [names] under [volume]'s root, breaks rule 3, 4 or 5 of [resolve]; null if it keeps them. */
    private fun refusal(volume: VolumeRoot, names: List<String>, path: Path): Refusal? {
        val (realRoot, real) = try {
            fs.realPath(volume.root) to fs.realPath(path)
        } catch (e: IOException) {
            return Refusal("not found", e)
        }
        if (!real.startsWith(realRoot)) return Refusal("out of its volume")
        val realNames = real.drop(realRoot.nameCount).map { it.toString() }
        if ((names + realNames).any(HiddenNames::isInternal)) return Refusal("the app's own")
        val withheld = isRestrictedAppDataPath(volume.root, path, obbAllowed = false) ||
            isRestrictedAppDataPath(realRoot, real, obbAllowed = false)
        return if (withheld) Refusal("withheld") else null
    }

    /** Why a path is refused, and the failure of the file system that told, if one did. */
    private class Refusal(val why: String, val cause: IOException? = null)

    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '\u0000' !in name
}
