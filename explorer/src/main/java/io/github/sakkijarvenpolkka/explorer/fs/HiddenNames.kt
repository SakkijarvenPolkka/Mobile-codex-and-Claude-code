/*
 * Files — names that are hidden from listings.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

/** Hidden (dot) names and the app's own internal names. */
object HiddenNames {
    /** The Recycle Bin folder at the root of each volume. */
    const val RECYCLE_BIN = ".Recycle.Bin"

    /** Suffix of temporary files and folders still being written (`.<name>.<opId>.xpart`). */
    const val PART_SUFFIX = ".xpart"

    /**
     * Whether [name] is one of the app's own: never listed, selected, pasted onto or
     * searched, whatever the hidden-items setting. Volumes are treated as case-insensitive.
     */
    fun isInternal(name: String): Boolean =
        name.equals(RECYCLE_BIN, ignoreCase = true) || name.endsWith(PART_SUFFIX, ignoreCase = true)

    /** Whether [name] is hidden unless "show hidden items" is on. */
    fun isHidden(name: String): Boolean = name.startsWith(".")
}
