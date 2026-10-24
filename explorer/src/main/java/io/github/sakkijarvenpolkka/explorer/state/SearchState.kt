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
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.search
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
 * with it, and [cancel] stops one, keeping what it found. Main thread only, in [scope].
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

    /** The walk of each tab's search running now. */
    private val walks = HashMap<TabId, Job>()

    /** Searches for [query], in the tab active now, once nothing else is asked for in [SEARCH_DELAY_MS]. */
    fun search(query: String, includeSubfolders: Boolean) {
        val tab = host.searchedTab()?.id
        waiting?.cancel()
        waiting = scope.launch {
            delay(SEARCH_DELAY_MS)
            if (tab != null && host.searchedTab()?.id == tab) start(query, includeSubfolders, again = false)
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
        host.searchedTab()?.let { walks[it.id]?.cancel() }
    }

    /**
     * What the search [location] finds for [tab], as [ListingState.Streaming]: the hits so far, sorted as
     * [options] say, with the folder it is in and how many folders could not be read, until it is done.
     * Hidden items are searched only with [ListOptions.showHidden] (a hidden folder not at all, nor what is
     * under it), the folders Android withholds never ([isRestrictedAppDataPath]). It walks on
     * [ExplorerDeps.io]; once stopped ([cancel]), what it found shows as done.
     */
    fun results(tab: TabId, location: Location.SearchResults, options: ListOptions): Flow<ListingState> {
        val walked = found(location, options, subfolders[tab] ?: true, obbAllowed())
        return channelFlow {
            var last = ListingState.Streaming(emptyList(), done = false, capped = false, 0, location.root)
            val walk = launch {
                walked.collect {
                    last = it
                    send(it)
                }
            }
            walks[tab] = walk
            try {
                walk.join()
            } finally {
                walks.remove(tab, walk)
            }
            if (!last.done) send(last.copy(done = true, currentDir = null))
        }
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

    /** The walk of [results], on [ExplorerDeps.io]. */
    private fun found(
        location: Location.SearchResults,
        options: ListOptions,
        includeSubfolders: Boolean,
        obbAllowed: Boolean,
    ): Flow<ListingState.Streaming> {
        val root = location.root
        val volumeRoot = deps.lookup.volumeOf(root)?.root
        val excluded = { path: Path ->
            (!options.showHidden && HiddenNames.isHidden(path.fileName?.toString().orEmpty())) ||
                (volumeRoot != null && isRestrictedAppDataPath(volumeRoot, path, obbAllowed))
        }
        return flow {
            val hits = ArrayList<FileEntry>()
            var rows = emptyList<Row>()
            var current = root
            var skipped = 0
            search(deps.fs, root, location.query, includeSubfolders, deps.mime::mimeOf, excluded).collect { event ->
                when (event) {
                    is SearchEvent.Batch -> {
                        hits += event.hits
                        rows = Listing.sort(hits, options.sort).map { hit ->
                            Row(hit, RowExtra.SearchInfo(hit.path.parent ?: root))
                        }
                    }
                    is SearchEvent.Progress -> {
                        current = event.currentDir
                        skipped = event.skippedDirs
                    }
                    is SearchEvent.Finished -> {
                        // Nowhere being searched any more.
                        emit(ListingState.Streaming(rows, done = true, event.capped, event.skippedDirs, null))
                        return@collect
                    }
                }
                emit(ListingState.Streaming(rows, done = false, capped = false, skipped, current))
            }
        }.flowOn(deps.io)
    }
}
