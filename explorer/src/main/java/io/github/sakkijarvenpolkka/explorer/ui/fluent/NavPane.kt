/*
 * Files — the navigation pane: titled sections of places (Home, Favorites, drives, Recycle Bin).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A place in the [NavPane]; a disabled item (an unavailable drive) is dimmed and cannot be chosen. */
data class NavItem(
    val key: Any,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val sublabel: String? = null,
)

/** A group of [items] under an optional [title], separated from the previous group by a line. */
data class NavSection(val title: String?, val items: List<NavItem>)

/**
 * The [sections] in a vertically scrolling column. The item whose key equals [selectedKey]
 * is drawn selected (accent fill and bar) and reported as the selected tab; choosing an
 * enabled item calls [onSelect]. Section titles are headings for accessibility services.
 */
@Composable
fun NavPane(sections: List<NavSection>, selectedKey: Any?, onSelect: (NavItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = FluentTheme.colors
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        sections.forEachIndexed { index, section ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.stroke),
                )
            }
            if (section.title != null) {
                BasicText(
                    section.title,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                        .semantics { heading() },
                    style = FluentTheme.type.caption.copy(color = colors.textSecondary),
                )
            }
            section.items.forEach { item ->
                key(item.key) { NavRow(item, selected = item.key == selectedKey, onSelect = onSelect) }
            }
        }
    }
}

@Composable
private fun NavRow(item: NavItem, selected: Boolean, onSelect: (NavItem) -> Unit) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val content = if (item.enabled) colors.text else colors.textDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                role = Role.Tab,
                onClick = { onSelect(item) },
            )
            .rowHighlight(selected, pressed, hovered, colors)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluentIcon(item.icon, contentDescription = null, tint = content)
        Column {
            BasicText(
                item.label,
                style = FluentTheme.type.body.copy(color = content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.sublabel != null) {
                BasicText(
                    item.sublabel,
                    style = FluentTheme.type.caption.copy(color = if (item.enabled) colors.textSecondary else colors.textDisabled),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
