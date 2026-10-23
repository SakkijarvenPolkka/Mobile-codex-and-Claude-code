/*
 * Files — the folders Android withholds from file managers.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.nio.file.Path
import java.util.Locale

/**
 * Whether [path] is `<volumeRoot>/Android/data` or `<volumeRoot>/Android/obb`, or anything
 * under them, ignoring case. `Android/obb` is not restricted when [obbAllowed] (the app may
 * install packages). `Android` itself and its other folders are not restricted.
 */
fun isRestrictedAppDataPath(volumeRoot: Path, path: Path, obbAllowed: Boolean = false): Boolean {
    val root = volumeRoot.normalize()
    val target = path.normalize()
    if (!target.startsWith(root)) return false
    val relative = root.relativize(target)
    if (relative.nameCount < 2 || folded(relative.getName(0)) != "android") return false
    return when (folded(relative.getName(1))) {
        "data" -> true
        "obb" -> !obbAllowed
        else -> false
    }
}

/**
 * Lower case. No other character lower-cases to the ASCII letters of `android`, `data` or
 * `obb`, so this matches them as the volume's case folding does.
 */
private fun folded(name: Path): String = name.toString().lowercase(Locale.ROOT)
