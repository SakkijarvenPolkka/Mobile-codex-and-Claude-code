/*
 * Files — what a file operation is, how it is going, the conflicts it asks about and how it ends.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.serialization.Serializable
import java.nio.file.Path

/** What an operation does. */
enum class OpKind { COPY, MOVE, DELETE_TO_BIN, DELETE_PERMANENT, RESTORE, EMPTY_BIN, COMPRESS, EXTRACT }

/**
 * What some kinds need besides paths: the character set of an archive's names to [EXTRACT][OpKind.EXTRACT]
 * with ([zipCharset], null to detect it), and the Recycle Bin items a [RESTORE][OpKind.RESTORE] works on
 * ([binItemIds]).
 */
@Serializable
data class OpOptions(val zipCharset: String? = null, val binItemIds: List<String> = emptyList())

/**
 * One operation as it is queued, journaled and retried: [id] (also in its temporary names), its [kind],
 * the absolute paths of its [sources], and the folder or file it writes to ([target]), if any.
 */
@Serializable
data class OperationSpec(
    val id: String,
    val kind: OpKind,
    val sources: List<String>,
    val target: String? = null,
    val options: OpOptions = OpOptions(),
)

/** How far an operation is, in bytes and items, and the name it is working on. */
data class OpProgress(
    val totalBytes: Long,
    val doneBytes: Long,
    val totalItems: Int,
    val doneItems: Int,
    val currentName: String?,
)

/** What clashes with what: the source's kind first (`FILE_DIR`: a file onto an existing folder). */
enum class ConflictKind { FILE_FILE, DIR_DIR, FILE_DIR, DIR_FILE }

/** A [source] whose name is taken in the target folder by [existing]. */
data class Conflict(val kind: ConflictKind, val source: FileEntry, val existing: FileEntry)

/** An answer to a [Conflict]; [allowedActions] says which ones a kind offers. */
enum class ConflictAction { REPLACE, SKIP, KEEP_BOTH, MERGE }

/**
 * The answers offered for a conflict of [kind]: a file onto a file can replace it, a folder onto a
 * folder can merge into it (never replacing it), and a file and a folder can only skip or keep both.
 */
fun allowedActions(kind: ConflictKind): List<ConflictAction> = when (kind) {
    ConflictKind.FILE_FILE -> listOf(ConflictAction.REPLACE, ConflictAction.SKIP, ConflictAction.KEEP_BOTH)
    ConflictKind.DIR_DIR -> listOf(ConflictAction.MERGE, ConflictAction.SKIP)
    ConflictKind.FILE_DIR, ConflictKind.DIR_FILE -> listOf(ConflictAction.SKIP, ConflictAction.KEEP_BOTH)
}

/** The answer to a conflict; [applyToAll] gives it to the operation's later conflicts of the same kind. */
data class Resolution(val action: ConflictAction, val applyToAll: Boolean = false)

/** Asks what to do about a conflict, suspending until it is answered. */
fun interface ConflictResolver {
    suspend fun resolve(c: Conflict): Resolution
}

/**
 * An item that failed, by its source [path]. [copiedButSourceKept] marks a move whose copy is complete
 * but whose source could not be deleted, so that retrying does not copy it twice. [into] is the folder
 * a copy, a move or a restore was putting the item in, under the name that folder has there (a ` - Copy`
 * or ` (2)` one included), so that retrying puts it there too; null when the item is in place, and for
 * the other kinds.
 */
data class ItemFailure(
    val path: String,
    val error: FsError,
    val copiedButSourceKept: Boolean = false,
    val into: String? = null,
)

/** How an operation ended. */
sealed interface OpResult {
    /**
     * It ran to the end: the top-level items it [created], the items that [failed], for a move, how
     * many items (files and folders) were left inside the source folders it had to keep, because
     * something in them was skipped or failed ([leftInSource]; the kept folders themselves are not
     * counted, unless they are inside another), and, for an extraction, how many entries were given a
     * ` (2)` name because theirs was taken ([renamedDuplicates], [ExtractResult.renamedDuplicates]).
     */
    data class Done(
        val created: List<Path>,
        val failed: List<ItemFailure>,
        val leftInSource: Int = 0,
        val renamedDuplicates: Int = 0,
    ) : OpResult

    /** It stopped at a failure that ends the whole operation, after [done] of [total] items. */
    data class Failed(val error: FsError, val done: Int, val total: Int) : OpResult

    /** It was cancelled before the end. */
    data object Cancelled : OpResult
}
