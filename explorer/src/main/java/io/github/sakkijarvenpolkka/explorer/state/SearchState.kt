/*
 * Files — the searches of the search box (spec §6.1): when one starts, where its results go in the tab,
 * and the listing of what it finds.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.HiddenNames
import io.github.sakkijarvenpolkka.explorer.fs.ListOptions
import io.github.sakkijarvenpolkka.explorer.fs.Listing
import io.github.sakkijarvenpolkka.explorer.fs.SearchEvent
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.search
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.nio.file.Path

/** How long the search box waits after the last change of its text before it searches. */
internal const val SEARCH_DELAY_MS = 300L

/** What [Searches] needs of the view model. */
internal interface SearchHost {
    /** The active tab; null while its folder is on a drive that is not mounted, where nothing is searched. */
    fun searchedTab(): TabState?

    /** Goes to [l] in the active tab; Back comes back. */
    fun navigate(l: Location)

    /** Puts [l] in place of the active tab's location: Back goes where it went from there. */
    fun replaceHere(l: Location)

    /** Reads the active tab's location again. */
    fun refresh()

    /** Goes back in the active tab. */
    fun back()
}

/**
 * The search results of [query] from [here]: under its folder, or under the folder its search results
 * are of. Null for a blank query (which would match everything) and for a place that is no folder.
 */
internal fun searchTarget(here: Location, query: String): Location.SearchResults? {
    if (query.isBlank()) return null
    val root = when (here) {
        is Location.Folder -> here.path
        is Location.SearchResults -> here.root
        Location.Home, Location.RecycleBin -> return null
    }
    return Location.SearchResults(root, query)
}

/**
 * The search box's searches, in the active tab of [host]. [search] waits [SEARCH_DELAY_MS] for the typing
 * to stop, [searchNow] does not (the keyboard's action key); either cancels the one waiting. A search goes
 * to [Location.SearchResults] in the same tab, pushed on its history from a folder; refined (from search
 * results, or from the folder a restored tab had its search results at), it takes their place, so that Back
 * goes to the folder. The tab's listing then comes from [results]; a new search there cancels the one before
 * with it, [cancel] stops one, keeping what it found, and [clear] goes back to the folder. Main thread only,
 * in [scope].
 */
internal class Searches(
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val host: SearchHost,
    private val obbAllowed: () -> Boolean,
) {
    private var waiting: Job? = null

    /** Whether each tab's searches go into subfolders, as last asked for there. */
    private val subfolders = HashMap<TabId, Boolean>()

    /** How each tab's search results are sorted: a change sorts what was found again, without searching. */
    private val sorts = HashMap<TabId, MutableStateFlow<SortSpec>>()

    /** What stops the walk of each tab's search, once cancelled. */
    private val stops = HashMap<TabId, Job>()

    /** The tabs it keeps anything for. */
    val remembered: Set<TabId> get() = subfolders.keys + sorts.keys + stops.keys

    /**
     * Searches for [query], in the tab active now, once nothing else is asked for in [SEARCH_DELAY_MS]; not
     * when that tab is no longer active by then, or has gone elsewhere (Back, a folder opened).
     */
    fun search(query: String, includeSubfolders: Boolean) {
        val tab = host.searchedTab()
        waiting?.cancel()
        waiting = scope.launch {
            delay(SEARCH_DELAY_MS)
            val now = host.searchedTab()
            if (tab != null && now?.id == tab.id && now.current.location == tab.current.location) {
                start(query, includeSubfolders, again = false)
            }
        }
    }

    /** Searches for [query] at once; the same search as the tab shows runs again. */
    fun searchNow(query: String, includeSubfolders: Boolean) {
        waiting?.cancel()
        start(query, includeSubfolders, again = true)
    }

    /** Stops the search waiting to start, and the active tab's search: what it found stays, as all there is. */
    fun cancel() {
        waiting?.cancel()
        host.searchedTab()?.let { stop(it.id) }
    }

    /**
     * The search box emptied (its ×): [cancel], and from search results back to the folder searched, as
     * Back goes there; where Back goes elsewhere, the folder takes their place.
     */
    fun clear() {
        cancel()
        val tab = host.searchedTab() ?: return
        val folder = Location.Folder((tab.current.location as? Location.SearchResults)?.root ?: return)
        if (tab.back.lastOrNull()?.location == folder) host.back() else host.replaceHere(folder)
    }

    /** Stops the walk of [tab]'s search, if one runs: what it found stays. */
    fun stop(tab: TabId) {
        stops[tab]?.cancel()
    }

    /** Sorts [tab]'s search results by [sort] from now on: those found so far too, without searching again. */
    fun sortBy(tab: TabId, sort: SortSpec) {
        sorts[tab]?.value = sort
    }

    /** Forgets the closed tab [tab], stopping its search. */
    fun forget(tab: TabId) {
        stop(tab)
        subfolders.remove(tab)
        sorts.remove(tab)
        stops.remove(tab)
    }

    /**
     * What the search [location] finds for [tab], as [ListingState.Streaming]: the hits so far, sorted as
     * [options] say (or as [sortBy] says since), with the folder it is in and how many folders could not be
     * read, until it is done. Hidden items are searched only with [ListOptions.showHidden] (a hidden folder
     * not at all, nor what is under it), the folders Android withholds never ([isRestrictedAppDataPath]).
     * It walks and sorts on [ExplorerDeps.io]; once stopped ([cancel]), what it found shows as done and
     * stopped. A search run again shows the rows it had found before ([shown]) until it has found as many,
     * or is done.
     */
    fun results(
        tab: TabId,
        location: Location.SearchResults,
        options: ListOptions,
        shown: List<Row> = emptyList(),
    ): Flow<ListingState> {
        val walked = found(location, options.showHidden, subfolders[tab] ?: true, obbAllowed())
        val stop = Job().also { stops[tab] = it }
        val sort = MutableStateFlow(options.sort).also { sorts[tab] = it }
        val before = shown.map { it.entry }
        return flow {
            // Sorted again only when there are new hits, or a new sort: not at each report of where it is.
            var sorted: Pair<List<FileEntry>, SortSpec>? = null
            var rows = emptyList<Row>()
            val listed = combine(untilStopped(walked, stop, location.root), sort) { found, by ->
                // Until it has caught up, what was found before stays: no list emptied, no scroll lost.
                val hits = if (!found.done && found.hits.size < before.size) before else found.hits
                if (sorted?.first !== hits || sorted?.second != by) {
                    rows = Listing.sort(hits, by).map { Row(it, RowExtra.SearchInfo(it.path.parent ?: location.root)) }
                    sorted = hits to by
                }
                ListingState.Streaming(rows, found.done, found.capped, found.skipped, found.current, found.stopped)
            }
            emitAll(listed)
        }.flowOn(deps.io)
    }

    private fun start(query: String, includeSubfolders: Boolean, again: Boolean) {
        val tab = host.searchedTab() ?: return
        val here = tab.current.location
        val target = searchTarget(here, query) ?: return
        val changed = (subfolders.put(tab.id, includeSubfolders) ?: true) != includeSubfolders
        when {
            target == here -> if (again || changed) host.refresh()
            here is Location.SearchResults || tab.current.searched != null -> host.replaceHere(target)
            else -> host.navigate(target)
        }
    }

    /**
     * [walked] until [stop] is cancelled, which cancels the walk at once: then what it had found, as done
     * and stopped. It starts searching [root], having found nothing.
     */
    private fun untilStopped(walked: Flow<Found>, stop: Job, root: Path): Flow<Found> = channelFlow {
        var last = Found(emptyList(), done = false, capped = false, skipped = 0, current = root)
        val walk = launch {
            walked.collect {
                last = it
                send(it)
            }
        }
        val stopping = stop.invokeOnCompletion { walk.cancel() }
        try {
            walk.join()
        } finally {
            stopping.dispose()
        }
        if (!last.done) send(last.copy(done = true, current = null, stopped = true))
    }

    /** The walk of [results], unsorted. */
    private fun found(
        location: Location.SearchResults,
        showHidden: Boolean,
        includeSubfolders: Boolean,
        obbAllowed: Boolean,
    ): Flow<Found> {
        val root = location.root
        val volumeRoot = deps.lookup.volumeOf(root)?.root
        val excluded = { path: Path ->
            (!showHidden && HiddenNames.isHidden(path.fileName?.toString().orEmpty())) ||
                (volumeRoot != null && isRestrictedAppDataPath(volumeRoot, path, obbAllowed))
        }
        return flow {
            // The same list until there are more: what is found is copied once for each batch.
            var hits = emptyList<FileEntry>()
            var current = root
            var skipped = 0
            search(deps.fs, root, location.query, includeSubfolders, deps.mime::mimeOf, excluded).collect { event ->
                when (event) {
                    is SearchEvent.Batch -> hits = hits + event.hits
                    is SearchEvent.Progress -> {
                        current = event.currentDir
                        skipped = event.skippedDirs
                    }
                    is SearchEvent.Finished -> {
                        // Nowhere being searched any more.
                        emit(Found(hits, done = true, event.capped, event.skippedDirs, current = null))
                        return@collect
                    }
                }
                emit(Found(hits, done = false, capped = false, skipped, current))
            }
        }
    }
}

/**
 * What a search found so far: its [hits] in the order found, and how it goes ([ListingState.Streaming]):
 * [done], [capped], how many folders it [skipped], the [current] folder, and whether it was [stopped].
 */
private data class Found(
    val hits: List<FileEntry>,
    val done: Boolean,
    val capped: Boolean,
    val skipped: Int,
    val current: Path?,
    val stopped: Boolean = false,
)
