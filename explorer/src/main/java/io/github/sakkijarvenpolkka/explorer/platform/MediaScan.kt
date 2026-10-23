/*
 * Files — telling the media store about the files an operation made (spec §5), so that gallery and
 * music apps see them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.Context
import android.media.MediaScannerConnection
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import java.nio.file.Path

/** Has the media store scan files and folders (a folder with all it holds). */
interface MediaScan {
    fun scan(paths: List<Path>)
}

/**
 * [MediaScan] through [MediaScannerConnection], in one request for all the [paths][scan] it takes:
 * those on a volume [volumes] knows whose root [fuseBacked] says is served through FUSE (a volume's
 * [Volume.fuseBacked]), as the media store indexes only those. Nothing is asked when none is.
 */
class AndroidMediaScan(
    context: Context,
    private val volumes: VolumeLookup,
    private val fuseBacked: (Path) -> Boolean,
) : MediaScan {
    private val context: Context = context.applicationContext

    override fun scan(paths: List<Path>) {
        val indexed = paths.filter { path -> volumes.volumeOf(path)?.root?.let(fuseBacked) == true }
        if (indexed.isEmpty()) return
        MediaScannerConnection.scanFile(context, indexed.map { it.toString() }.toTypedArray(), null, null)
    }
}
