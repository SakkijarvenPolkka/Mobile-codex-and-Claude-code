/*
 * Files — what each tab's list shows, read off the main thread.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** What a tab's list shows. */
sealed interface ListingState {
    /** Nothing to show yet. */
    data object Loading : ListingState

    /** A folder's [rows], sorted; [refreshing] while they are being read again. */
    data class Loaded(val rows: List<Row>, val refreshing: Boolean = false) : ListingState

    /**
     * Rows that arrive over time (search): [done] once all have, or once it was [stopped] (by the user,
     * before the end: there may be more); [capped] when the limit on results stopped it; [skippedDirs]
     * folders that could not be read; [currentDir] the folder being read.
     */
    data class Streaming(
        val rows: List<Row>,
        val done: Boolean,
        val capped: Boolean,
        val skippedDirs: Int,
        val currentDir: Path?,
        val stopped: Boolean = false,
    ) : ListingState

    data class Error(val error: FsError) : ListingState
}

/** A list row: the [entry], and what a Recycle Bin or search row shows besides it. */
data class Row(val entry: FileEntry, val extra: RowExtra? = null)

sealed interface RowExtra {
    /** A Recycle Bin item: where it was deleted from (null when unknown), when, and its bin [itemId]. */
    data class BinInfo(val originalPath: Path?, val deletedAt: Long, val itemId: String) : RowExtra

    /** A search result, in [parentDir]. */
    data class SearchInfo(val parentDir: Path) : RowExtra
}

/**
 * A [StateFlow] of [ListingState] for each tab. Folders are read and sorted on [io]; the results
 * are published from [scope]. A new [load] or [setSource] for a tab cancels what the tab was
 * doing, and nothing the cancelled work read is published.
 */
class ListingLoader(
    private val scope: CoroutineScope,
    private val fs: FsBackend,
    private val io: CoroutineDispatcher,
    private val mime: (String) -> String?,
) {
    /** A tab's state and work. Fields are guarded by the loader. */
    private class Slot {
        val state = MutableStateFlow<ListingState>(ListingState.Loading)
        val readOnly: StateFlow<ListingState> = state.asStateFlow()
        var job: Job? = null

        /** Counts the tab's loads; work publishes only while its own is the last. */
        var generation = 0L
        var stale = false
    }

    private val slots = HashMap<TabId, Slot>()

    /** [tab]'s state; [ListingState.Loading] until something is loaded for it. */
    @Synchronized
    fun state(tab: TabId): StateFlow<ListingState> = slot(tab).readOnly

    /**
     * Reads [dir] for [tab]: [ListingState.Loading], then the rows or the error. With [refresh],
     * rows already shown stay, as `Loaded(rows, refreshing = true)`, until the new ones replace
     * them. Clears the stale mark.
     */
    @Synchronized
    fun load(tab: TabId, dir: Path, options: ListOptions, refresh: Boolean = false) {
        val slot = slot(tab)
        val shown = slot.state.value
        val first = if (refresh && shown is ListingState.Loaded) shown.copy(refreshing = true) else ListingState.Loading
        val generation = restart(slot, first)
        slot.job = scope.launch {
            val next = try {
                val entries = withContext(io) { Listing.list(fs, dir, options, mime) }
                ListingState.Loaded(entries.map { Row(it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ListingState.Error(e.toFsError())
            }
            publish(slot, generation, next)
        }
    }

    /**
     * Shows [source]'s states for [tab] (Recycle Bin, search results): [ListingState.Loading]
     * until it emits, or with [refresh] the rows already shown, as `Loaded(rows, refreshing = true)` or
     * as a [ListingState.Streaming] that is not done. It is collected in the loader's scope, so it moves
     * its own work off the main thread. Clears the stale mark.
     */
    @Synchronized
    fun setSource(tab: TabId, source: Flow<ListingState>, refresh: Boolean = false) {
        val slot = slot(tab)
        val shown = slot.state.value
        val kept = when {
            !refresh -> null
            shown is ListingState.Loaded -> shown.copy(refreshing = true)
            shown is ListingState.Streaming -> shown.copy(done = false, stopped = false)
            else -> null
        }
        val generation = restart(slot, kept ?: ListingState.Loading)
        slot.job = scope.launch {
            try {
                source.collect { publish(slot, generation, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(slot, generation, ListingState.Error(e.toFsError()))
            }
        }
    }

    /** Marks [tab]'s list as out of date (it was not reloaded when the app came back). */
    @Synchronized
    fun markStale(tab: TabId) {
        slot(tab).stale = true
    }

    @Synchronized
    fun isStale(tab: TabId): Boolean = slots[tab]?.stale == true

    /** Forgets [tab] (closed) and stops its work; its old state flow keeps its last value. */
    @Synchronized
    fun drop(tab: TabId) {
        val slot = slots.remove(tab) ?: return
        slot.job?.cancel()
        slot.generation++
    }

    private fun slot(tab: TabId): Slot = slots.getOrPut(tab) { Slot() }

    /** Cancels [slot]'s work, shows [state] and returns the generation of the work that follows. */
    private fun restart(slot: Slot, state: ListingState): Long {
        slot.job?.cancel()
        slot.stale = false
        slot.state.value = state
        return ++slot.generation
    }

    @Synchronized
    private fun publish(slot: Slot, generation: Long, state: ListingState) {
        if (slot.generation == generation) slot.state.value = state
    }
}
