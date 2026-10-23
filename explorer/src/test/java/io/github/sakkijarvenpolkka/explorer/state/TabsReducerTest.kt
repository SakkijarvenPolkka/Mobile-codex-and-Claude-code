// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.state.TabsReducer.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class TabsReducerTest {

    private val root: Path = Paths.get("/storage/emulated/0")
    private val a = root.resolve("a")
    private val b = root.resolve("b")
    private val c = root.resolve("c")
    private val d = root.resolve("d")
    private val ordered = listOf(a, b, c, d)

    private fun folder(name: String) = Location.Folder(root.resolve(name))

    private fun TabsModel.then(vararg actions: TabAction): TabsModel =
        actions.fold(this) { model, action -> reduce(model, action) }

    private fun TabsModel.locations(): List<Location> = active.back.map { it.location }

    private fun TabsModel.selected(): List<Path> = active.selection.items.toList()

    @Test
    fun navigatePushesBackAndClearsForward() {
        val start = TabsReducer.initial(folder("A"))
        val m = start.then(TabAction.Navigate(folder("B")), TabAction.Navigate(folder("C")))
        assertEquals(HistoryEntry(folder("C")), m.active.current)
        assertEquals(listOf(folder("A"), folder("B")), m.locations())

        val wentBack = reduce(m, TabAction.Back)
        assertEquals(listOf(HistoryEntry(folder("C"))), wentBack.active.forward)

        val leaving = HistoryEntry(folder("B"), scrollIndex = 12, scrollOffset = 34, focusName = "x.txt")
        val branched = reduce(wentBack, TabAction.Navigate(folder("D"), leaving))
        assertEquals(HistoryEntry(folder("D")), branched.active.current)
        assertEquals(listOf(HistoryEntry(folder("A")), leaving), branched.active.back)
        assertEquals(emptyList<HistoryEntry>(), branched.active.forward)
    }

    @Test
    fun leavingSnapshotOfAnotherLocationIsIgnored() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Navigate(folder("B"), HistoryEntry(folder("Z"), scrollIndex = 5)))
        assertEquals(listOf(HistoryEntry(folder("A"))), m.active.back)
    }

    @Test
    fun navigateToCurrentLocationAddsNoHistory() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Navigate(folder("B")), TabAction.Navigate(folder("C")), TabAction.Back)
            .then(TabAction.Select(a), TabAction.Navigate(folder("B")))
        assertEquals(HistoryEntry(folder("B")), m.active.current)
        assertEquals(listOf(folder("A")), m.locations())
        assertEquals(listOf(HistoryEntry(folder("C"))), m.active.forward)
        assertEquals(SelectionState(), m.active.selection)
    }

    @Test
    fun backAndForwardSwapStacks() {
        val leavingA = HistoryEntry(folder("A"), scrollIndex = 40, scrollOffset = 7, focusName = "a")
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Navigate(folder("B"), leavingA), TabAction.Navigate(folder("C")))

        val once = reduce(m, TabAction.Back)
        assertEquals(HistoryEntry(folder("B")), once.active.current)
        assertEquals(listOf(leavingA), once.active.back)
        assertEquals(listOf(HistoryEntry(folder("C"))), once.active.forward)

        val twice = reduce(once, TabAction.Back)
        assertEquals(leavingA, twice.active.current)
        assertEquals(emptyList<HistoryEntry>(), twice.active.back)
        assertEquals(listOf(HistoryEntry(folder("C")), HistoryEntry(folder("B"))), twice.active.forward)
        assertSame(twice, reduce(twice, TabAction.Back))

        val forward = reduce(twice, TabAction.Forward)
        assertEquals(once, forward)
        val forwardAgain = reduce(forward, TabAction.Forward)
        assertEquals(m, forwardAgain)
        assertSame(forwardAgain, reduce(forwardAgain, TabAction.Forward))
    }

    @Test
    fun historyCappedAtFifty() {
        var m = TabsReducer.initial(folder("F0"))
        for (i in 1..60) m = reduce(m, TabAction.Navigate(folder("F$i")))
        assertEquals(TabsReducer.HISTORY_CAP, m.active.back.size)
        assertEquals(50, m.active.back.size)
        assertEquals(folder("F10"), m.active.back.first().location)
        assertEquals(folder("F59"), m.active.back.last().location)
        assertEquals(folder("F60"), m.active.current.location)

        var back = m
        repeat(50) { back = reduce(back, TabAction.Back) }
        assertEquals(folder("F10"), back.active.current.location)
        assertEquals(50, back.active.forward.size)
    }

    @Test
    fun navigateClearsSelection() {
        val selected = TabsReducer.initial(folder("A")).then(TabAction.Select(a))
        assertTrue(selected.active.selection.active)

        val moved = reduce(selected, TabAction.Navigate(folder("B")))
        assertEquals(SelectionState(), moved.active.selection)
        assertFalse(moved.active.selection.active)

        val back = moved.then(TabAction.Select(b), TabAction.Back)
        assertEquals(SelectionState(), back.active.selection)
        val forward = back.then(TabAction.Select(a), TabAction.Forward)
        assertEquals(SelectionState(), forward.active.selection)
    }

    @Test
    fun closeLastTabLeavesOneHomeTab() {
        val m = TabsReducer.initial(folder("A")).then(TabAction.Navigate(folder("B")))
        val only = m.active.id

        val closed = reduce(m, TabAction.Close(only))
        assertEquals(1, closed.tabs.size)
        val tab = closed.tabs.single()
        assertNotEquals(only, tab.id)
        assertEquals(tab.id, closed.activeId)
        assertEquals(HistoryEntry(Location.Home), tab.current)
        assertEquals(emptyList<HistoryEntry>(), tab.back)
        assertEquals(emptyList<HistoryEntry>(), tab.forward)
        assertEquals(SelectionState(), tab.selection)
    }

    @Test
    fun closeActiveActivatesRightNeighbourElseLeft() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.NewTab(folder("B")), TabAction.NewTab(folder("C")))
        val (first, second, third) = m.tabs.map { it.id }

        val middle = reduce(m.then(TabAction.Activate(second)), TabAction.Close(second))
        assertEquals(listOf(first, third), middle.tabs.map { it.id })
        assertEquals(third, middle.activeId)

        val last = reduce(middle, TabAction.Close(third))
        assertEquals(listOf(first), last.tabs.map { it.id })
        assertEquals(first, last.activeId)

        val inactive = reduce(m.then(TabAction.Activate(first)), TabAction.Close(third))
        assertEquals(first, inactive.activeId)
        assertEquals(listOf(first, second), inactive.tabs.map { it.id })
    }

    @Test
    fun closeOthersKeepsOnlyThatTabActive() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.NewTab(folder("B")), TabAction.NewTab(folder("C")))
        val second = m.tabs[1]

        val closed = reduce(m, TabAction.CloseOthers(second.id))
        assertEquals(listOf(second), closed.tabs)
        assertEquals(second.id, closed.activeId)
    }

    @Test
    fun duplicateCopiesHistory() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Navigate(folder("B")), TabAction.Navigate(folder("C")), TabAction.Back)
            .then(TabAction.Select(a), TabAction.NewTab(folder("X")), TabAction.Activate(TabId(1)))
        val source = m.active

        val duplicated = reduce(m, TabAction.Duplicate(source.id))
        assertEquals(3, duplicated.tabs.size)
        val copy = duplicated.tabs[1]
        assertNotEquals(source.id, copy.id)
        assertEquals(copy.id, duplicated.activeId)
        assertEquals(source.current, copy.current)
        assertEquals(source.back, copy.back)
        assertEquals(source.forward, copy.forward)
        assertEquals(SelectionState(), copy.selection)

        val moved = reduce(duplicated, TabAction.Navigate(folder("D")))
        assertEquals(source, moved.tabs.first { it.id == source.id })
    }

    @Test
    fun selectionActionsChangeOnlyTheActiveTab() {
        val m = TabsReducer.initial(folder("A")).then(TabAction.NewTab(folder("B")))
        val first = m.tabs.first()

        val selected = m.then(TabAction.SelectAll(ordered))
        assertEquals(ordered, selected.selected())
        assertEquals(first, selected.tabs.first())
    }

    @Test
    fun rangeToSelectsFromAnchor() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Select(b), TabAction.RangeTo(d, ordered))
        assertEquals(listOf(b, c, d), m.selected())
        assertEquals(b, m.active.selection.anchor)
        assertEquals(d, m.active.selection.focus)

        val shrunk = reduce(m, TabAction.RangeTo(c, ordered))
        assertEquals(listOf(b, c), shrunk.selected())

        val reversed = reduce(m, TabAction.RangeTo(a, ordered))
        assertEquals(listOf(a, b), reversed.selected())
        assertEquals(b, reversed.active.selection.anchor)
    }

    @Test
    fun rangeToWithoutAnchorSelectsTheTarget() {
        val m = TabsReducer.initial(folder("A")).then(TabAction.RangeTo(c, ordered))
        assertEquals(SelectionState(linkedSetOf(c), anchor = c, focus = c), m.active.selection)
    }

    @Test
    fun toggleLastItemLeavesSelectionMode() {
        val one = TabsReducer.initial(folder("A")).then(TabAction.Toggle(a))
        assertTrue(one.active.selection.active)
        assertEquals(listOf(a), one.selected())

        val two = reduce(one, TabAction.Toggle(c))
        assertEquals(listOf(a, c), two.selected())
        assertEquals(c, two.active.selection.anchor)

        val none = two.then(TabAction.Toggle(a), TabAction.Toggle(c))
        assertFalse(none.active.selection.active)
        assertEquals(SelectionState(), none.active.selection)
    }

    @Test
    fun selectReplacesTheSelection() {
        val m = TabsReducer.initial(folder("A")).then(TabAction.SelectAll(ordered), TabAction.Select(c))
        assertEquals(SelectionState(linkedSetOf(c), anchor = c, focus = c), m.active.selection)
    }

    @Test
    fun invertUsesOrder() {
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Toggle(d), TabAction.Toggle(b), TabAction.Invert(ordered))
        assertEquals(listOf(a, c), m.selected())

        val all = reduce(m, TabAction.Invert(ordered))
        assertEquals(listOf(b, d), all.selected())
        val none = all.then(TabAction.SelectAll(ordered), TabAction.Invert(ordered))
        assertEquals(SelectionState(), none.active.selection)
    }

    @Test
    fun pruneDropsMissing() {
        val e = root.resolve("e")
        val m = TabsReducer.initial(folder("A"))
            .then(TabAction.Select(b), TabAction.RangeTo(d, ordered), TabAction.Toggle(a))
        assertEquals(listOf(b, c, d, a), m.selected())

        val pruned = reduce(m, TabAction.Prune(listOf(e, a, c)))
        assertEquals(listOf(a, c), pruned.selected())
        assertEquals(a, pruned.active.selection.anchor)

        val gone = reduce(m, TabAction.Prune(listOf(e, c)))
        assertEquals(SelectionState(linkedSetOf(c)), gone.active.selection)
        assertEquals(SelectionState(), reduce(gone, TabAction.Prune(listOf(e))).active.selection)
    }

    @Test
    fun clearAndSetSelection() {
        val set = TabsReducer.initial(folder("A")).then(TabAction.SetSelection(listOf(d, a)))
        assertEquals(SelectionState(linkedSetOf(d, a), anchor = d, focus = d), set.active.selection)
        assertEquals(SelectionState(), reduce(set, TabAction.ClearSelection).active.selection)
        assertEquals(SelectionState(), reduce(set, TabAction.SetSelection(emptyList())).active.selection)
    }

    @Test
    fun idsAreStableAndIncreasing() {
        val start = TabsReducer.initial(folder("A"))
        val firstId = start.active.id

        val m = start.then(TabAction.NewTab(folder("B")), TabAction.NewTab(folder("C")))
        val ids = m.tabs.map { it.id.value }
        assertEquals(listOf(firstId.value, firstId.value + 1, firstId.value + 2), ids)
        assertEquals(m.tabs.last().id, m.activeId)

        val closed = reduce(m, TabAction.Close(TabId(ids[1])))
        val reopened = reduce(closed, TabAction.NewTab(folder("D")))
        assertEquals(listOf(ids[0], ids[2], ids[2] + 1), reopened.tabs.map { it.id.value })

        val duplicated = reduce(reopened, TabAction.Duplicate(TabId(ids[0])))
        assertEquals(listOf(ids[0], ids[2] + 2, ids[2], ids[2] + 1), duplicated.tabs.map { it.id.value })
        assertTrue(duplicated.nextId > duplicated.tabs.maxOf { it.id.value })

        val navigated = duplicated.then(TabAction.Activate(TabId(ids[2])), TabAction.Navigate(folder("E")))
        assertEquals(duplicated.tabs.map { it.id }, navigated.tabs.map { it.id })
        assertEquals(TabId(ids[2]), navigated.activeId)

        val lastClosed = navigated.then(TabAction.CloseOthers(TabId(ids[0])), TabAction.Close(TabId(ids[0])))
        assertTrue(lastClosed.active.id.value > duplicated.tabs.maxOf { it.id.value })
    }

    @Test
    fun unknownTabIdsAreIgnored() {
        val m = TabsReducer.initial(folder("A"))
        val unknown = TabId(99)
        listOf(
            TabAction.Close(unknown),
            TabAction.CloseOthers(unknown),
            TabAction.Duplicate(unknown),
            TabAction.Activate(unknown),
        ).forEach { assertSame(m, reduce(m, it)) }
    }

    @Test
    fun upGoesToTheParentFolder() {
        val volumes = listOf(root, Paths.get("/storage/1A2B-3C4D"))
        assertEquals(
            Location.Folder(root),
            upTarget(Location.Folder(root.resolve("DCIM")), volumes, homeEnabled = false),
        )
        assertEquals(
            Location.Folder(Paths.get("/storage/1A2B-3C4D")),
            upTarget(Location.Folder(Paths.get("/storage/1A2B-3C4D/Music")), volumes, homeEnabled = true),
        )
    }

    @Test
    fun upFromAVolumeRootGoesHomeOnlyWhenEnabled() {
        val volumes = listOf(root)
        assertEquals(Location.Home, upTarget(Location.Folder(root), volumes, homeEnabled = true))
        assertNull(upTarget(Location.Folder(root), volumes, homeEnabled = false))
    }

    @Test
    fun upFromHomeOrRecycleBinIsNull() {
        assertNull(upTarget(Location.Home, listOf(root), homeEnabled = true))
        assertNull(upTarget(Location.RecycleBin, listOf(root), homeEnabled = true))
    }

    @Test
    fun upFromSearchResultsGoesToTheSearchRoot() {
        val searchRoot = root.resolve("Download")
        assertEquals(
            Location.Folder(searchRoot),
            upTarget(Location.SearchResults(searchRoot, "rep"), listOf(root), homeEnabled = false),
        )
    }
}
