/*
 * Files — the pure reducer for tabs, history and selection.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import java.nio.file.Path

/**
 * A change to the tabs. Navigation and selection act on the active tab; an action naming a tab
 * that is not open changes nothing. Every move between locations clears the selection.
 */
sealed interface TabAction {
    /**
     * Go to [location], pushing the current entry onto the back stack and clearing the forward
     * stack. [leaving] is the current entry with its scroll position, pushed instead when it is
     * for the current location. Going to the current location only clears the selection.
     */
    data class Navigate(val location: Location, val leaving: HistoryEntry? = null) : TabAction

    /** Go to the nearest back entry, if any. */
    data object Back : TabAction

    /** Go to the nearest forward entry, if any. */
    data object Forward : TabAction

    /**
     * Put [location] in place of the current entry of tab [id], active or not (such as its
     * folder where its volume is mounted now), keeping its back and forward stacks; clears its
     * selection.
     */
    data class ReplaceCurrent(val id: TabId, val location: Location) : TabAction

    /** Open a tab at [location] after the last one, and activate it. */
    data class NewTab(val location: Location) : TabAction

    /**
     * Close a tab. Closing the active one activates its right neighbour, else its left; closing
     * the last one leaves a new tab at Home.
     */
    data class Close(val id: TabId) : TabAction

    /** Close every tab but [id], which becomes active. */
    data class CloseOthers(val id: TabId) : TabAction

    /** Open a copy of a tab, with its history but no selection, right after it, and activate it. */
    data class Duplicate(val id: TabId) : TabAction

    /** Make a tab the active one. */
    data class Activate(val id: TabId) : TabAction

    /** Select only [path]; it becomes the anchor. */
    data class Select(val path: Path) : TabAction

    /** Add [path] to the selection or remove it; it becomes the anchor. */
    data class Toggle(val path: Path) : TabAction

    /**
     * Select only the run of [ordered] (the listing) from the anchor to [path], keeping the
     * anchor. Without an anchor in the listing, select only [path] and make it the anchor.
     */
    data class RangeTo(val path: Path, val ordered: List<Path>) : TabAction

    /** Select all of [ordered], the listing. */
    data class SelectAll(val ordered: List<Path>) : TabAction

    /** Select nothing, which leaves selection mode. */
    data object ClearSelection : TabAction

    /** Select the items of [ordered], the listing, that are not selected, and only those. */
    data class Invert(val ordered: List<Path>) : TabAction

    /** Keep only the selected items still in [existing], the refreshed listing, in its order. */
    data class Prune(val existing: List<Path>) : TabAction

    /** Select exactly [paths], in that order (such as the items an operation created). */
    data class SetSelection(val paths: List<Path>) : TabAction
}

/** Whether this action changes only the selection: the tabs stay where they are. */
internal fun TabAction.changesSelectionOnly(): Boolean = when (this) {
    is TabAction.Select, is TabAction.Toggle, is TabAction.RangeTo, is TabAction.SelectAll,
    TabAction.ClearSelection, is TabAction.Invert, is TabAction.Prune, is TabAction.SetSelection,
    -> true
    else -> false
}

/** The single source of truth for tabs, history and selection: a pure function of its input. */
object TabsReducer {
    /** The most entries a back or forward stack keeps; the oldest are dropped first. */
    const val HISTORY_CAP = 50

    private const val FIRST_ID = 1L

    /** One tab showing [location]. */
    fun initial(location: Location): TabsModel {
        val tab = blankTab(TabId(FIRST_ID), location)
        return TabsModel(listOf(tab), tab.id, FIRST_ID + 1)
    }

    /**
     * The model after [a]. Back or Forward with nowhere to go, and an action naming a tab that
     * is not open, return [m] itself.
     */
    fun reduce(m: TabsModel, a: TabAction): TabsModel = when (a) {
        is TabAction.Navigate -> m.updateActive { it.navigate(a.location, a.leaving) }
        TabAction.Back -> m.updateActive { it.goBack() }
        TabAction.Forward -> m.updateActive { it.goForward() }
        is TabAction.ReplaceCurrent -> m.updateTab(a.id) {
            it.copy(current = HistoryEntry(a.location), selection = SelectionState())
        }
        is TabAction.NewTab -> m.open(a.location)
        is TabAction.Close -> m.close(a.id)
        is TabAction.CloseOthers -> m.closeOthers(a.id)
        is TabAction.Duplicate -> m.duplicate(a.id)
        is TabAction.Activate -> if (m.tabs.any { it.id == a.id }) m.copy(activeId = a.id) else m
        is TabAction.Select -> m.updateSelection { SelectionState(linkedSetOf(a.path), a.path, a.path) }
        is TabAction.Toggle -> m.updateSelection { it.toggle(a.path) }
        is TabAction.RangeTo -> m.updateSelection { it.rangeTo(a.path, a.ordered) }
        is TabAction.SelectAll -> m.updateSelection { selectionOf(a.ordered.toOrderedSet(), it.anchor, it.focus) }
        TabAction.ClearSelection -> m.updateSelection { SelectionState() }
        is TabAction.Invert -> m.updateSelection { s ->
            selectionOf(a.ordered.filterTo(LinkedHashSet()) { it !in s.items }, s.anchor, s.focus)
        }
        is TabAction.Prune -> m.updateSelection { it.prune(a.existing) }
        is TabAction.SetSelection -> m.updateSelection {
            selectionOf(a.paths.toOrderedSet(), a.paths.firstOrNull(), a.paths.firstOrNull())
        }
    }

    private fun blankTab(id: TabId, location: Location) =
        TabState(id, HistoryEntry(location), emptyList(), emptyList(), SelectionState())

    private fun List<HistoryEntry>.capped(): List<HistoryEntry> = takeLast(HISTORY_CAP)

    /**
     * Applies [change] to the tab [id]; returns this model itself when that tab is not open or
     * [change] returns the tab itself.
     */
    private inline fun TabsModel.updateTab(id: TabId, change: (TabState) -> TabState): TabsModel {
        val tab = tabs.firstOrNull { it.id == id } ?: return this
        val changed = change(tab)
        if (changed === tab) return this
        return copy(tabs = tabs.map { if (it.id == id) changed else it })
    }

    private inline fun TabsModel.updateActive(change: (TabState) -> TabState): TabsModel = updateTab(activeId, change)

    private inline fun TabsModel.updateSelection(change: (SelectionState) -> SelectionState): TabsModel =
        updateActive { it.copy(selection = change(it.selection)) }

    private fun TabState.navigate(location: Location, leaving: HistoryEntry?): TabState {
        if (location == current.location) return copy(selection = SelectionState())
        val left = leaving?.takeIf { it.location == current.location } ?: current
        return copy(
            current = HistoryEntry(location),
            back = (back + left).capped(),
            forward = emptyList(),
            selection = SelectionState(),
        )
    }

    private fun TabState.goBack(): TabState {
        val target = back.lastOrNull() ?: return this
        return copy(
            current = target,
            back = back.dropLast(1),
            forward = (forward + current).capped(),
            selection = SelectionState(),
        )
    }

    private fun TabState.goForward(): TabState {
        val target = forward.lastOrNull() ?: return this
        return copy(
            current = target,
            back = (back + current).capped(),
            forward = forward.dropLast(1),
            selection = SelectionState(),
        )
    }

    private fun TabsModel.open(location: Location): TabsModel {
        val tab = blankTab(TabId(nextId), location)
        return TabsModel(tabs + tab, tab.id, nextId + 1)
    }

    private fun TabsModel.close(id: TabId): TabsModel {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        if (tabs.size == 1) {
            val home = blankTab(TabId(nextId), Location.Home)
            return TabsModel(listOf(home), home.id, nextId + 1)
        }
        val remaining = tabs.filterIndexed { i, _ -> i != index }
        val newActive = if (id == activeId) remaining[minOf(index, remaining.lastIndex)].id else activeId
        return copy(tabs = remaining, activeId = newActive)
    }

    private fun TabsModel.closeOthers(id: TabId): TabsModel {
        val tab = tabs.firstOrNull { it.id == id } ?: return this
        return copy(tabs = listOf(tab), activeId = id)
    }

    private fun TabsModel.duplicate(id: TabId): TabsModel {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val twin = tabs[index].copy(id = TabId(nextId), selection = SelectionState())
        val reordered = tabs.toMutableList().apply { add(index + 1, twin) }
        return TabsModel(reordered, twin.id, nextId + 1)
    }

    /** A selection of [items], or no selection at all (no anchor either) when there are none. */
    private fun selectionOf(items: Set<Path>, anchor: Path?, focus: Path?): SelectionState =
        if (items.isEmpty()) SelectionState() else SelectionState(items, anchor, focus)

    private fun List<Path>.toOrderedSet(): Set<Path> = toCollection(LinkedHashSet())

    // A Path is an Iterable<Path> of its names, so plain `+`/`-` would add or remove those.
    private fun SelectionState.toggle(path: Path): SelectionState =
        selectionOf(if (path in items) items.minusElement(path) else items.plusElement(path), path, path)

    private fun SelectionState.rangeTo(path: Path, ordered: List<Path>): SelectionState {
        val end = ordered.indexOf(path)
        if (end < 0) return this
        val start = anchor?.let(ordered::indexOf) ?: -1
        if (start < 0) return SelectionState(linkedSetOf(path), path, path)
        val run = ordered.subList(minOf(start, end), maxOf(start, end) + 1)
        return SelectionState(run.toOrderedSet(), anchor, path)
    }

    private fun SelectionState.prune(existing: List<Path>): SelectionState {
        if (!active) return this
        val kept = existing.filterTo(LinkedHashSet()) { it in items }
        return selectionOf(kept, anchor?.takeIf { it in existing }, focus?.takeIf { it in existing })
    }
}
