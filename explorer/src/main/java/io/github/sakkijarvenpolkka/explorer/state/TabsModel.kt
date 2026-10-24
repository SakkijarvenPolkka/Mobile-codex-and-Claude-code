/*
 * Files — the tabs, their history and their selection.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import java.nio.file.Path

/** A tab's identity. Never reused while the app runs, so per-tab state can be keyed by it. */
@JvmInline
value class TabId(val value: Long)

/**
 * A place in a tab's history: the [location], and where its list was scrolled to when it was
 * left (first visible item [scrollIndex], its [scrollOffset] in pixels, the focused item's
 * [focusName]), so that going back restores the view. Kept in memory only.
 */
data class HistoryEntry(
    val location: Location,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val focusName: String? = null,
)

/**
 * The selected [items], in listing order where it is known (a [LinkedHashSet]). [anchor] is
 * where range selection starts; [focus] is the item last acted on.
 */
data class SelectionState(
    val items: Set<Path> = emptySet(),
    val anchor: Path? = null,
    val focus: Path? = null,
) {
    /** Selection mode: anything is selected. */
    val active: Boolean get() = items.isNotEmpty()
}

/**
 * One tab: its [current] place, and its [back] and [forward] stacks, both with the nearest
 * entry last.
 */
data class TabState(
    val id: TabId,
    val current: HistoryEntry,
    val back: List<HistoryEntry>,
    val forward: List<HistoryEntry>,
    val selection: SelectionState,
)

/** The open [tabs] in strip order, the active one, and the id the next new tab gets. */
data class TabsModel(val tabs: List<TabState>, val activeId: TabId, val nextId: Long) {
    /** The tab with [activeId]. */
    val active: TabState get() = tabs.first { it.id == activeId }

    /** Where the tab [id] is, or null when it is not open. */
    fun locationOf(id: TabId): Location? = tabs.firstOrNull { it.id == id }?.current?.location

    /** The tab after the active one in strip order, the first after the last. */
    fun afterActive(): TabId = tabs[(tabs.indexOfFirst { it.id == activeId } + 1) % tabs.size].id
}
