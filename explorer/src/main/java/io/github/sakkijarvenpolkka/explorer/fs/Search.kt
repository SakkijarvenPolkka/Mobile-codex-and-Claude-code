/*
 * Files — searching a folder tree by name.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The most hits a [search] finds. */
const val SEARCH_CAP = 10_000

/** The hits waiting that are sent as a [SearchEvent.Batch] at once. */
private const val BATCH_SIZE = 200

/** The time since the last batch after which waiting hits are sent, and the least between two progresses. */
private val SEND_INTERVAL = 200.milliseconds

private const val ANY_RUN = '*'.code
private const val ANY_ONE = '?'.code

/**
 * Whether a name matches a search [query]. Both are NFC-normalized and lower-cased (`Locale.ROOT`) first,
 * as [NameComparator] folds them. A query with a `*` (any run of characters, none included) or a `?` (any
 * one character, a code point) is a pattern the whole name must match; any other query matches the names
 * it is part of. Every other character, `.`, `[` and `\` included, stands for itself: no regular
 * expression is made from the query.
 */
class NameMatcher(query: String) {
    private val folded = NameComparator.key(query)
    private val pattern: IntArray? =
        if (folded.any { it == '*' || it == '?' }) folded.codePoints().toArray() else null

    fun matches(name: String): Boolean {
        val key = NameComparator.key(name)
        val pattern = pattern ?: return key.contains(folded)
        return matchesWhole(pattern, key.codePoints().toArray())
    }
}

/**
 * Whether [pattern] matches all of [name], both as code points. A `*` first matches nothing and gives
 * way one code point at a time when the rest fails to match; only the last `*` seen ever needs to, so
 * this takes at most pattern × name steps.
 */
private fun matchesWhole(pattern: IntArray, name: IntArray): Boolean {
    var p = 0
    var n = 0
    // The last '*' in the pattern so far, and where in the name the run it matches ends.
    var star = -1
    var runEnd = 0
    while (n < name.size) {
        when {
            p < pattern.size && pattern[p] == ANY_RUN -> {
                star = p++
                runEnd = n
            }
            p < pattern.size && (pattern[p] == ANY_ONE || pattern[p] == name[n]) -> {
                p++
                n++
            }
            star >= 0 -> {
                p = star + 1
                n = ++runEnd
            }
            else -> return false
        }
    }
    while (p < pattern.size && pattern[p] == ANY_RUN) p++
    return p == pattern.size
}

/** What a [search] reports. */
sealed interface SearchEvent {
    /** The hits found since the last batch, in walk order. */
    data class Batch(val hits: List<FileEntry>) : SearchEvent

    /** The folder the walk is in, [currentDir], and how many folders could not be listed so far. */
    data class Progress(val currentDir: Path, val skippedDirs: Int) : SearchEvent

    /** The end: [capped] when the search stopped at [SEARCH_CAP] hits, so there may be more. */
    data class Finished(val capped: Boolean, val skippedDirs: Int) : SearchEvent
}

/**
 * Searches [root] for the items whose names match [query] ([NameMatcher]): everything under it, or only
 * its own items unless [includeSubfolders]. The search is a [walk], so a link is not followed (it is a
 * hit by its own name), and it leaves out the app's internal names (the Recycle Bin, `.xpart` items) and
 * whatever [excluded] says (the folders Android withholds, [isRestrictedAppDataPath]), with everything
 * under them. Special files (pipes, sockets, devices: [EntryKind.OTHER]) are never hits.
 *
 * The hits come in walk order, in [SearchEvent.Batch]es: one once 200 are found, or once 200 ms by
 * [time] have passed since the last and some are. A [SearchEvent.Progress] names the folder the walk is
 * in, at the start and then at most every 200 ms. [SearchEvent.Finished] comes last; [SEARCH_CAP] hits
 * end the walk and make it capped. A folder that cannot be listed, [root] included, is counted as
 * skipped and the search goes on; an item whose attributes cannot be read is left out.
 *
 * The file system is read on the collector's context (collect it on an I/O dispatcher); cancelling the
 * collector stops the walk before its next item.
 */
fun search(
    fs: FsBackend,
    root: Path,
    query: String,
    includeSubfolders: Boolean,
    mime: (String) -> String?,
    excluded: (Path) -> Boolean,
    time: TimeSource = TimeSource.Monotonic,
): Flow<SearchEvent> = channelFlow {
    val matcher = NameMatcher(query)
    val batch = ArrayList<FileEntry>()
    var found = 0
    var skippedDirs = 0
    // The folder the walk lists next. walk lists a folder right after its visit says to descend, so a
    // failure reported for it is a folder that cannot be listed; for any other path, an unreadable item.
    var listing = root
    var batchSent = time.markNow()
    var progressSent = time.markNow()

    suspend fun sendBatch() {
        if (batch.isNotEmpty()) send(SearchEvent.Batch(batch.toList()))
        batch.clear()
        batchSent = time.markNow()
    }

    send(SearchEvent.Progress(root, skippedDirs = 0))
    val capped = try {
        walk(
            fs,
            root,
            mime,
            include = { !HiddenNames.isInternal(it.name) && !excluded(it.path) },
            onSkipped = { path, _ -> if (path == listing) skippedDirs++ },
        ) { item ->
            val entry = item.entry
            if (entry.kind != EntryKind.OTHER && matcher.matches(entry.name)) {
                batch += entry
                found++
            }
            if (batch.size == BATCH_SIZE || (batch.isNotEmpty() && batchSent.elapsedNow() >= SEND_INTERVAL)) {
                sendBatch()
            }
            if (progressSent.elapsedNow() >= SEND_INTERVAL) {
                send(SearchEvent.Progress(entry.path.parent ?: root, skippedDirs))
                progressSent = time.markNow()
            }
            if (found == SEARCH_CAP) throw CapReached()
            val descend = includeSubfolders && entry.kind == EntryKind.DIR
            if (descend) listing = entry.path
            descend
        }
        false
    } catch (e: CapReached) {
        true
    }
    sendBatch()
    send(SearchEvent.Finished(capped, skippedDirs))
}

/** Ends a [search]'s walk from inside it once [SEARCH_CAP] hits are found. */
private class CapReached : Exception()
