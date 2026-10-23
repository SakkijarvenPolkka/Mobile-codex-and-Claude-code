/*
 * Files — telling the media store about the files an operation made (spec §5), so that gallery and
 * music apps see them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.Context
import android.media.MediaScannerConnection
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import java.nio.file.Path
import java.nio.file.Paths

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

/**
 * What the media store is to scan once [op] has ended: the top-level items it made ([OpResult.Done.created])
 * when it copied, extracted, restored or compressed, or moved from another volume ([volumes] tells),
 * which copies. Nothing for a delete, nor for a move within a volume, a rename the media store follows
 * on its own; nothing either for an operation that did not run to its end.
 */
fun scanTargets(op: FinishedOp, volumes: VolumeLookup): List<Path> {
    val created = (op.result as? OpResult.Done)?.created ?: return emptyList()
    val spec = op.spec
    return when (spec.kind) {
        OpKind.COPY, OpKind.EXTRACT, OpKind.RESTORE, OpKind.COMPRESS -> created
        OpKind.MOVE -> {
            val into = spec.target?.let { volumes.volumeOf(Paths.get(it))?.uuid }
            val copied = spec.sources.any { volumes.volumeOf(Paths.get(it))?.uuid != into }
            if (copied) created else emptyList()
        }
        OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.EMPTY_BIN -> emptyList()
    }
}
