/*
 * Files — what the media store is to scan once an operation has ended (spec §5); platform/MediaScan
 * does the scanning.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ops

import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import java.nio.file.Path
import java.nio.file.Paths

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
