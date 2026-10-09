/*
 * Audacity Android port — menu rendering (ui-reference.md §9.5): a list with
 * push navigation into submenus, used by the menu bar dropdowns (tablets),
 * the menu sheet (phones) and the context menus. Disabled items are greyed
 * but tappable: the app explains why (3.7.9 TellUserWhyDisallowed).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuNode
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.menu.Separator
import io.github.sakkijarvenpolkka.audacity.menu.SubMenu
import io.github.sakkijarvenpolkka.audacity.menu.TopMenu
import io.github.sakkijarvenpolkka.audacity.menu.expand
import io.github.sakkijarvenpolkka.audacity.util.text

/**
 * Menu content with push navigation. [onItem] gets every tap (enabled or not);
 * it returns true when the menu should close.
 */
@Composable
fun MenuList(
    nodes: List<MenuNode>,
    state: MenuState,
    onItem: (MenuItem) -> Boolean,
    onClose: () -> Unit,
    showShortcuts: Boolean,
    modifier: Modifier = Modifier,
) {
    val stack = remember(nodes) { mutableStateListOf<SubMenu>() }
    val current = stack.lastOrNull()
    val shown = (current?.children ?: nodes).expand(state)
    Column(modifier) {
        if (current != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { stack.removeAt(stack.lastIndex) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(current.label.text(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
        for (node in shown) {
            when (node) {
                is MenuItem -> MenuRow(node, state, showShortcuts) { if (onItem(node)) onClose() }
                is SubMenu -> SubMenuRow(node) { stack.add(node) }
                Separator -> HorizontalDivider(Modifier.padding(vertical = 2.dp))
                else -> Unit
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, state: MenuState, showShortcuts: Boolean, onClick: () -> Unit) {
    val enabled = item.enabled(state.flags)
    val checked = item.checked?.invoke(state)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("menu:${item.id}")
            .padding(horizontal = 12.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (checked == true) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            item.labelFor(state).text(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
        val sc = item.shortcut
        if (showShortcuts && sc != null) {
            Spacer(Modifier.width(16.dp))
            Text(sc.display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubMenuRow(sub: SubMenu, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("submenu:${sub.id}")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(32.dp))
        Text(sub.label.text(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
    }
}

/** Desktop-like menu bar: a horizontally scrolling row of menu titles with dropdowns (tablets). */
@Composable
fun MenuBar(menus: List<TopMenu>, state: MenuState, onItem: (MenuItem) -> Boolean, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<String?>(null) }
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        for (m in menus) {
            Box {
                TextButton(onClick = { open = m.id }, modifier = Modifier.testTag("menubar:${m.id}")) {
                    Text(m.label.text(), maxLines = 1)
                }
                DropdownMenu(expanded = open == m.id, onDismissRequest = { open = null }) {
                    MenuDropdownContent(m, state, onItem) { open = null }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MenuDropdownContent(m: TopMenu, state: MenuState, onItem: (MenuItem) -> Boolean, onClose: () -> Unit) {
    MenuList(m.children, state, onItem, onClose, showShortcuts = true, modifier = Modifier.widthIn(min = 260.dp, max = 420.dp))
}

/** Phone menu: a bottom sheet with a tab per top-level menu (ui-reference.md §9.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    menus: List<TopMenu>,
    state: MenuState,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    onItem: (MenuItem) -> Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tab = selectedTab.coerceIn(0, menus.lastIndex)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            menus.forEachIndexed { i, m ->
                Tab(selected = i == tab, onClick = { onTab(i) }, text = { Text(m.label.text()) }, modifier = Modifier.testTag("menutab:${m.id}"))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            MenuList(menus[tab].children, state, onItem, onDismiss, showShortcuts = false)
        }
    }
}

/** Context menu sheet with a title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSheet(title: String, nodes: List<MenuNode>, state: MenuState, onItem: (MenuItem) -> Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            if (title.isNotEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
            }
            MenuList(nodes, state, onItem, onDismiss, showShortcuts = false)
        }
    }
}
