/*
 * Files — what a tab shows, and where "Up" goes from there.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import java.nio.file.Path

/** What a tab shows. */
sealed interface Location {
    /** Quick access, drives and recent files. */
    data object Home : Location

    /** A folder; [path] is always the canonical path (`toRealPath()`), under a mounted volume root. */
    data class Folder(val path: Path) : Location

    /** The Recycle Bins of every volume, merged. */
    data object RecycleBin : Location

    /** The items under [root] whose names match [query]. */
    data class SearchResults(val root: Path, val query: String) : Location
}

/**
 * Where "Up" goes from [location]: a folder's parent; from a volume root (one of [volumes]),
 * [Location.Home] when [homeEnabled], else nowhere; from search results, the folder searched.
 * Null means "Up" is disabled.
 */
fun upTarget(location: Location, volumes: List<Path>, homeEnabled: Boolean): Location? = when (location) {
    is Location.Folder -> when {
        location.path in volumes -> if (homeEnabled) Location.Home else null
        else -> location.path.parent?.let { Location.Folder(it) }
    }
    is Location.SearchResults -> Location.Folder(location.root)
    Location.Home, Location.RecycleBin -> null
}
