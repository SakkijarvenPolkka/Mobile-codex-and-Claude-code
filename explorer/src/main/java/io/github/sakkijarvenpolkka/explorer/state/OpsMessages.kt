/*
 * Files — what the shell tells of a file operation that ended: a message, a question, or the failures
 * to list; and which places it changed.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.util.UiText
import java.nio.file.Path
import java.nio.file.Paths

/** The kinds whose sources are no longer where they were once done: their folders change. */
private val TAKES_SOURCES = setOf(OpKind.MOVE, OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.RESTORE)

/** The kinds that change what the Recycle Bin holds (deleting for good: a bin item, from the bin). */
private val CHANGES_BIN = setOf(OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.RESTORE, OpKind.EMPTY_BIN)

/**
 * What to tell of an operation that ended ([tellingOf]): a [message] about no place (one of
 * [MessageSeverity.SUCCESS] only over nothing that matters more), a [question] to ask once no other is
 * open, and the operation whose failures to list ([listed]), with only those.
 */
internal data class Telling(
    val message: UiMessage? = null,
    val question: ConfirmRequest? = null,
    val listed: FinishedOp? = null,
)

/**
 * What to tell of [op], where emptying the Recycle Bin of its target's volume would free [freeable]
 * bytes:
 * - one that did all it was asked, how many items it did (a success);
 * - a refused one (nothing done), its error only;
 * - one that stopped, how far it got and why;
 * - one out of space, also what emptying the bin would free, when that is anything;
 * - a move that left items in its sources, how many;
 * - an extraction that gave entries a ` (2)` name, theirs being taken, how many (to know, not a success);
 * - a cancelled one, nothing.
 *
 * Its failures are listed, but for a delete to the Recycle Bin: the items the bin could not take (all of
 * them, when there was no bin to use) are asked about ([ConfirmRequest.BinUnavailable]), those already
 * gone need no word, and any other failure is listed.
 */
internal fun tellingOf(op: FinishedOp, freeable: Long = 0): Telling {
    val spec = op.spec
    return when (val result = op.result) {
        OpResult.Cancelled -> Telling()
        is OpResult.Failed -> {
            val hint = UiText.res(R.string.hint_empty_bin, UiText.size(freeable))
                .takeIf { result.error == FsError.NoSpace && freeable > 0 }
            when {
                spec.kind == OpKind.DELETE_TO_BIN && result.error == FsError.BinUnavailable ->
                    Telling(question = ConfirmRequest.BinUnavailable(spec.sources.map { Paths.get(it) }))
                result.done == 0 && result.total == 0 ->
                    Telling(UiMessage(errorText(result.error), MessageSeverity.ERROR, hint = hint))
                else -> {
                    val name = UiText.res(nameOf(spec.kind))
                    val stopped = UiText.res(R.string.op_fatal, result.done, result.total, name)
                    Telling(UiMessage(stopped, MessageSeverity.ERROR, detail = errorText(result.error), hint = hint))
                }
            }
        }
        is OpResult.Done -> tellingOfDone(op, result)
    }
}

/** What to tell of [op], which did what it could ([result]): see [tellingOf]. */
private fun tellingOfDone(op: FinishedOp, result: OpResult.Done): Telling {
    val message = when {
        result.leftInSource > 0 ->
            UiMessage(UiText.res(R.string.left_in_source, result.leftInSource), MessageSeverity.WARNING)
        result.renamedDuplicates > 0 ->
            UiMessage(UiText.res(R.string.zip_duplicates, result.renamedDuplicates), MessageSeverity.INFO)
        result.failed.isEmpty() -> UiMessage(UiText.res(R.string.op_done, op.spec.sources.size), MessageSeverity.SUCCESS)
        else -> null
    }
    if (op.spec.kind != OpKind.DELETE_TO_BIN) return Telling(message, listed = op.takeIf { result.failed.isNotEmpty() })
    val (refused, others) = result.failed.partition { it.error == FsError.BinUnavailable }
    val listed = others.filterNot { it.error == FsError.NotFound }
    val question = if (refused.isEmpty()) null else ConfirmRequest.BinUnavailable(refused.map { Paths.get(it.path) })
    return Telling(message, question, op.copy(result = result.copy(failed = listed)).takeIf { listed.isNotEmpty() })
}

/**
 * Whether [op], which ended, changed what a location shows: a folder it wrote to or took items from (or
 * one it made something in), search results of a folder above one of those, the Recycle Bin for the kinds
 * that change it, and Home always (it shows the Recycle Bin's size and the recent files, which any
 * operation may have moved).
 */
internal fun changedBy(op: FinishedOp): (Location) -> Boolean {
    val spec = op.spec
    val folders = HashSet<Path>()
    // add(), not +=: a Path is an Iterable<Path> of its names.
    spec.target?.let { folders.add(Paths.get(it)) }
    if (spec.kind in TAKES_SOURCES) spec.sources.mapNotNullTo(folders) { Paths.get(it).parent }
    (op.result as? OpResult.Done)?.created?.mapNotNullTo(folders) { it.parent }
    return { location ->
        when (location) {
            is Location.Folder -> location.path in folders
            is Location.SearchResults -> folders.any { it.startsWith(location.root) }
            Location.RecycleBin -> spec.kind in CHANGES_BIN
            Location.Home -> true
        }
    }
}

/** What an operation of [kind] is called in "Stopped …". */
private fun nameOf(kind: OpKind): Int = when (kind) {
    OpKind.COPY -> R.string.op_name_copy
    OpKind.MOVE -> R.string.op_name_move
    OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT -> R.string.op_name_delete
    OpKind.RESTORE -> R.string.op_name_restore
    OpKind.EMPTY_BIN -> R.string.op_name_empty_bin
    OpKind.COMPRESS -> R.string.op_name_compress
    OpKind.EXTRACT -> R.string.op_name_extract
}
