/*
 * Files — what the file system layer knows about the volume a path is on.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.nio.file.Path

/**
 * How names behave on a volume. Every volume is treated as [caseInsensitive] (FUSE shared
 * storage, vfat and exFAT all are); [fatNames] adds the FAT naming rules of removable media.
 */
data class VolumeTraits(val caseInsensitive: Boolean = true, val fatNames: Boolean = false)

/**
 * A mounted volume: its [uuid] (`primary` for the primary volume), [root], naming [traits],
 * and whether it is [removable] media (SD card, USB drive).
 */
data class VolumeRoot(val uuid: String, val root: Path, val traits: VolumeTraits, val removable: Boolean)

/** Finds the mounted volume a path is on. */
fun interface VolumeLookup {
    /** The volume whose root is the longest prefix of [path] (by name elements), or null if none is. */
    fun volumeOf(path: Path): VolumeRoot?
}
