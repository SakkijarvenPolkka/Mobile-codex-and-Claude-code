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
     * Rules 4 and 5 also hold for where its links lead.
     */
    fun resolve(id: String): Path {
        val colon = id.indexOf(':')
        if (colon < 0) throw notFound(id, "not a document id")
        val uuid = id.substring(0, colon)
        val volume = mounted().firstOrNull { it.uuid == uuid } ?: throw notFound(id, "volume not mounted")
        val rel = id.substring(colon + 1)
        val names = if (rel.isEmpty()) emptyList() else rel.split('/')
        if (!names.all(::isPlainName)) throw notFound(id, "not a plain path")
        val path = try {
            volume.root.resolve(rel)
        } catch (e: InvalidPathException) {
            throw notFound(id, "not a path")
        }
        refusal(volume, names, path)?.let { throw notFound(id, it) }
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
    fun volumeOf(path: Path): VolumeRoot? =
        mounted().filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }

    /** Why [path], the [names] under [volume]'s root, breaks rule 3, 4 or 5 of [resolve]; null if it keeps them. */
    private fun refusal(volume: VolumeRoot, names: List<String>, path: Path): String? {
        val (realRoot, real) = try {
            fs.realPath(volume.root) to fs.realPath(path)
        } catch (e: IOException) {
            return "not found"
        }
        if (!real.startsWith(realRoot)) return "out of its volume"
        val realNames = real.drop(realRoot.nameCount).map { it.toString() }
        if ((names + realNames).any(HiddenNames::isInternal)) return "the app's own"
        val withheld = isRestrictedAppDataPath(volume.root, path, obbAllowed = false) ||
            isRestrictedAppDataPath(realRoot, real, obbAllowed = false)
        return if (withheld) "withheld" else null
    }

    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '\u0000' !in name

    private fun notFound(id: String, why: String) = FileNotFoundException("$why: $id")
}
