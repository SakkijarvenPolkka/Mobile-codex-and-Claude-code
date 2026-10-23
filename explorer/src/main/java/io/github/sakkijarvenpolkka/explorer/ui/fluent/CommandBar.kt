/*
 * Files — the command bar: a row of commands whose remainder goes into a "See more" menu.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics

/** A command shown in a [CommandBar] or a [MenuFlyout]; [id] is also its test tag. */
data class CommandItem(
    val id: String,
    val label: String,
    val icon: ImageVector?,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

/**
 * The [primary] commands in a row, each a subtle button (icon only, with its label as the
 * content description, when it has an icon), followed by a "⋯" button labelled [moreLabel].
 * The primary commands that do not fit the width move, in order, to the front of the "⋯"
 * menu, before the [overflow] commands. Without overflow and when everything fits, there is
 * no "⋯" button.
 */
@Composable
fun CommandBar(primary: List<CommandItem>, overflow: List<CommandItem>, moreLabel: String, modifier: Modifier = Modifier) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // Measure every button once in a disabled, semantics-free copy that is never placed.
        val widths = subcompose(Slot.MeasurePrimary) {
            primary.forEach { item -> key(item.id) { Unseen { CommandButton(item.copy(enabled = false)) } } }
        }.map { it.measure(loose).width }
        val moreWidth = subcompose(Slot.MeasureMore) {
            Unseen { MoreButton(moreLabel, items = emptyList(), enabled = false) }
        }.sumOf { it.measure(loose).width }

        val shown = fittingCount(widths, moreWidth, constraints.maxWidth, hasOverflow = overflow.isNotEmpty())
        val placeables = subcompose(Slot.Bar) {
            primary.take(shown).forEach { item -> key(item.id) { CommandButton(item) } }
            if (shown < primary.size || overflow.isNotEmpty()) {
                MoreButton(moreLabel, items = primary.drop(shown) + overflow, enabled = true)
            }
        }.map { it.measure(loose) }

        val width = placeables.sumOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}

private enum class Slot { MeasurePrimary, MeasureMore, Bar }

/** How many of the buttons [widths] fit into [maxWidth], leaving room for the "⋯" button when it is needed. */
private fun fittingCount(widths: List<Int>, moreWidth: Int, maxWidth: Int, hasOverflow: Boolean): Int {
    if (!hasOverflow && widths.sum() <= maxWidth) return widths.size
    var used = moreWidth
    widths.forEachIndexed { index, width ->
        if (used + width > maxWidth) return index
        used += width
    }
    return widths.size
}

@Composable
private fun Unseen(content: @Composable () -> Unit) {
    Box(Modifier.clearAndSetSemantics {}) { content() }
}

@Composable
private fun CommandButton(item: CommandItem) {
    FluentButton(
        text = if (item.icon == null) item.label else null,
        onClick = item.onClick,
        modifier = Modifier.testTag(item.id),
        icon = item.icon,
        style = ButtonStyle.SUBTLE,
        enabled = item.enabled,
        contentDescription = if (item.icon != null) item.label else null,
    )
}

@Composable
private fun MoreButton(label: String, items: List<CommandItem>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FluentButton(
            text = null,
            onClick = { expanded = true },
            icon = FluentIcons.More,
            style = ButtonStyle.SUBTLE,
            enabled = enabled,
            contentDescription = label,
        )
        MenuFlyout(expanded, onDismiss = { expanded = false }, items = items)
    }
}
