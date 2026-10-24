/*
 * Files — the tab strip: a tab for each place open, with its icon, its title and × to close it, then +
 * for a new tab (spec §2.1).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.state.TabId

/** A tab of the [TabStrip]: the tab [id], its [title] (already made safe to show) and its [icon]. */
data class TabUi(val id: TabId, val title: String, val icon: ImageVector)

/** The test tags of [TabStrip]. */
object TabStripTags {
    /** The whole strip. */
    const val STRIP = "tab_strip"

    /** The + button. */
    const val NEW = "tab_new"

    /** The tab [id]. */
    fun tab(id: TabId): String = "tab:${id.value}"

    /** The × of the tab [id]. */
    fun close(id: TabId): String = "tab_close:${id.value}"
}

/**
 * [tabs] in a row that scrolls sideways, then + for a new tab ([onNew], read as [newTabLabel]), which
 * stays in view. The active tab ([activeId]) is drawn as a card, and scrolled into view whenever it
 * becomes active. Tapping a tab calls [onSelect], long-pressing it [onMenu]; its × ([closeLabel]) calls
 * [onClose]. Each tab is a tab for accessibility services, selected while active; a long title is
 * cut in the middle (spec §2.7).
 */
@Composable
fun TabStrip(
    tabs: List<TabUi>,
    activeId: TabId,
    onSelect: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
    onNew: () -> Unit,
    onMenu: (TabId) -> Unit,
    newTabLabel: String,
    closeLabel: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp).testTag(TabStripTags.STRIP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEach { tab ->
                key(tab.id) {
                    Tab(tab, tab.id == activeId, closeLabel, onSelect = onSelect, onClose = onClose, onMenu = onMenu)
                }
            }
        }
        FluentButton(
            text = null,
            onClick = onNew,
            modifier = Modifier.testTag(TabStripTags.NEW),
            icon = FluentIcons.Add,
            style = ButtonStyle.SUBTLE,
            contentDescription = newTabLabel,
        )
    }
}

@Composable
private fun Tab(
    tab: TabUi,
    active: Boolean,
    closeLabel: String,
    onSelect: (TabId) -> Unit,
    onClose: (TabId) -> Unit,
    onMenu: (TabId) -> Unit,
) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) inView.bringIntoView() }
    val fill = when {
        active -> colors.card
        pressed -> colors.pressed
        hovered -> colors.hover
        else -> Color.Transparent
    }
    Row(
        Modifier
            .bringIntoViewRequester(inView)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onLongClick = { onMenu(tab.id) },
                onClick = { onSelect(tab.id) },
            )
            .semantics { selected = active }
            .testTag(TabStripTags.tab(tab.id))
            .heightIn(min = MinTouchTarget)
            .widthIn(max = TabMaxWidth)
            .background(fill, TabShape)
            .then(if (active) Modifier.border(1.dp, colors.stroke, TabShape) else Modifier)
            .padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (active) colors.text else colors.textSecondary
        FluentIcon(tab.icon, contentDescription = null, tint = content)
        BasicText(
            tab.title,
            modifier = Modifier.weight(1f, fill = false),
            style = FluentTheme.type.body.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        FluentButton(
            text = null,
            onClick = { onClose(tab.id) },
            modifier = Modifier.testTag(TabStripTags.close(tab.id)),
            icon = FluentIcons.Close,
            style = ButtonStyle.SUBTLE,
            contentDescription = closeLabel,
        )
    }
}

/** The widest a tab gets, its × included. */
private val TabMaxWidth = 240.dp

/** A tab's top corners are rounded, its bottom sits on the address bar. */
private val TabShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
