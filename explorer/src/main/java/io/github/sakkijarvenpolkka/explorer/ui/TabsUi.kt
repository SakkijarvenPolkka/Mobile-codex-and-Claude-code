/*
 * Files — the shell's tab strip: a tab for each tab of the view model, named after where it is, and the
 * menu of a tab (Close, Close other tabs, Duplicate).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Overlay
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MenuFlyout
import io.github.sakkijarvenpolkka.explorer.ui.fluent.TabStrip
import io.github.sakkijarvenpolkka.explorer.ui.fluent.TabUi
import io.github.sakkijarvenpolkka.explorer.util.DisplayName

/**
 * The tabs of [ui] in a [TabStrip] for [vm]: + opens a new tab at Home, × closes a tab, and a long press
 * opens its menu ([Overlay.TabMenu]) under the strip. Each tab is named and drawn after its location
 * ([tabTitle], [tabIcon]).
 */
@Composable
fun TabsBar(ui: ExplorerUiState, vm: ExplorerViewModel, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    Box(modifier) {
        TabStrip(
            tabs = ui.tabs.tabs.map { tab ->
                val location = tab.current.location
                TabUi(tab.id, tabTitle(location, ui.volumes, res), tabIcon(location, ui.volumes))
            },
            activeId = ui.tabs.activeId,
            onSelect = vm::activate,
            onClose = vm::closeTab,
            onNew = vm::newTab,
            onMenu = vm::openTabMenu,
            newTabLabel = res.getString(R.string.tab_new),
            closeLabel = res.getString(R.string.tab_close),
        )
        val menu = ui.overlay as? Overlay.TabMenu
        MenuFlyout(
            expanded = menu != null,
            onDismiss = vm::closeOverlay,
            items = menu?.let { tabMenu(it.id, others = ui.tabs.tabs.size > 1, res, vm) }.orEmpty(),
        )
    }
}

/** The menu of the tab [id]: Close, Close other tabs (when there are [others]) and Duplicate. */
private fun tabMenu(id: TabId, others: Boolean, res: Resources, vm: ExplorerViewModel): List<CommandItem> = listOf(
    CommandItem(Tags.TAB_MENU_CLOSE, res.getString(R.string.tab_close), FluentIcons.Close, enabled = true) {
        vm.closeTab(id)
    },
    CommandItem(Tags.TAB_MENU_CLOSE_OTHERS, res.getString(R.string.tab_close_others), null, enabled = others) {
        vm.closeOthers(id)
    },
    CommandItem(Tags.TAB_MENU_DUPLICATE, res.getString(R.string.tab_duplicate), FluentIcons.Copy, enabled = true) {
        vm.duplicate(id)
    },
)

/**
 * What a tab at [location] is called: its folder's name, or the volume's name at a volume's root (one
 * of [volumes]); Home, the Recycle Bin, or the search results with the words searched for.
 */
private fun tabTitle(location: Location, volumes: List<Volume>, res: Resources): String = when (location) {
    Location.Home -> res.getString(R.string.nav_home)
    Location.RecycleBin -> res.getString(R.string.nav_recycle_bin)
    is Location.SearchResults -> res.getString(R.string.tab_search_results, DisplayName.inSentence(location.query))
    is Location.Folder -> volumes.firstOrNull { it.root == location.path }
        ?.let { volumeLabel(it, res.getString(R.string.volume_internal)) }
        ?: DisplayName.of((location.path.fileName ?: location.path).toString())
}

/**
 * The icon of a tab at [location]: a folder, the drive at a volume's root (one of [volumes]), Home, the
 * Recycle Bin or a search.
 */
private fun tabIcon(location: Location, volumes: List<Volume>): ImageVector = when (location) {
    Location.Home -> FluentIcons.Home
    Location.RecycleBin -> FluentIcons.RecycleBin
    is Location.SearchResults -> FluentIcons.Search
    is Location.Folder -> volumes.firstOrNull { it.root == location.path }?.let(::volumeIcon) ?: FluentIcons.Folder
}
