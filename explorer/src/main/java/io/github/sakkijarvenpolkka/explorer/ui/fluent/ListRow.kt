/*
 * Files — a list row that can be tapped, long-pressed and selected.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A full-width row of a list, at least 48 dp high. A selected row has the accent at 10 % behind it and
 * a 3 dp accent bar at its start; pressing shows a darker fill instead of a ripple.
 *
 * In [selectionMode] the row is a checkbox for accessibility services (its toggle state is
 * [selected], [stateDescription] is read in place of "checked"); otherwise it reports
 * [selected] as a selected item. The check mark itself is part of the caller's [content].
 */
@Composable
fun ListRow(
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = if (selectionMode) Role.Checkbox else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                if (selectionMode) toggleableState = ToggleableState(selected) else this.selected = selected
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .rowHighlight(selected, pressed, hovered, colors)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The Fluent row background, inset 4 dp from the sides with rounded corners: the selection
 * fill and the 3 dp × 16 dp accent bar at the start when [selected], then the [pressed] or
 * [hovered] overlay.
 */
internal fun Modifier.rowHighlight(selected: Boolean, pressed: Boolean, hovered: Boolean, colors: FluentColors): Modifier =
    drawBehind {
        val inset = Offset(4.dp.toPx(), 1.dp.toPx())
        val area = Size(size.width - 2 * inset.x, size.height - 2 * inset.y)
        if (area.width <= 0f || area.height <= 0f) return@drawBehind
        val corner = CornerRadius(4.dp.toPx())
        if (selected) drawRoundRect(colors.selection, inset, area, corner)
        when {
            pressed -> drawRoundRect(colors.pressed, inset, area, corner)
            hovered -> drawRoundRect(colors.hover, inset, area, corner)
        }
        if (selected) {
            val barWidth = 3.dp.toPx()
            val barHeight = min(16.dp.toPx(), area.height)
            val x = if (layoutDirection == LayoutDirection.Ltr) inset.x else size.width - inset.x - barWidth
            drawRoundRect(
                colors.accent,
                Offset(x, (size.height - barHeight) / 2f),
                Size(barWidth, barHeight),
                CornerRadius(barWidth / 2f),
            )
        }
    }
