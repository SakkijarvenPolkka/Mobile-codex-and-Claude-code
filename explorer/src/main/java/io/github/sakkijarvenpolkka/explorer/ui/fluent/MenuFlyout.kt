/*
 * Files — a Fluent menu that opens next to the element it is placed in.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

/**
 * A menu of [items] while [expanded], below the layout it is placed in (above it, or moved
 * sideways, when the window has no room). Choosing an enabled item calls [onDismiss] and then
 * the item's action; touching outside the menu or Back calls [onDismiss]. Each item is tagged
 * with its [CommandItem.id] for tests.
 */
@Composable
fun MenuFlyout(expanded: Boolean, onDismiss: () -> Unit, items: List<CommandItem>) {
    if (!expanded || items.isEmpty()) return
    val margin = with(LocalDensity.current) { ShadowRoom.roundToPx() }
    val position = remember(margin) { FlyoutPosition(margin) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val colors = FluentTheme.colors
        val shape = FluentTheme.shapes.overlay
        val reserveIcon = items.any { it.icon != null }
        Box(Modifier.padding(ShadowRoom)) {
            Column(
                Modifier
                    .shadow(8.dp, shape)
                    .background(colors.card, shape)
                    .border(1.dp, colors.stroke, shape)
                    .widthIn(min = 160.dp, max = 320.dp)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                items.forEach { item ->
                    key(item.id) {
                        MenuItem(item, reserveIcon) {
                            onDismiss()
                            item.onClick()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(item: CommandItem, reserveIcon: Boolean, onClick: () -> Unit) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val content = if (item.enabled) colors.text else colors.textDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .testTag(item.id)
            .clickable(interaction, indication = null, enabled = item.enabled, role = Role.Button, onClick = onClick)
            .rowHighlight(selected = false, pressed = pressed, hovered = hovered, colors = colors)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            item.icon != null -> FluentIcon(item.icon, contentDescription = null, tint = content)
            reserveIcon -> Spacer(Modifier.width(20.dp))
        }
        if (reserveIcon) Spacer(Modifier.width(12.dp))
        BasicText(
            item.label,
            style = FluentTheme.type.body.copy(color = content),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Transparent room around the menu inside the popup window, so that its shadow is not cut off. */
private val ShadowRoom = 8.dp

/**
 * Puts the visible menu (the popup minus [margin] on every side) under the anchor, aligned
 * with its start edge, else its end edge, else pushed inside the window; above the anchor
 * when it does not fit below.
 */
private class FlyoutPosition(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val leftAligned = anchorBounds.left - margin
        val rightAligned = anchorBounds.right - width + margin
        val (preferred, other) =
            if (layoutDirection == LayoutDirection.Ltr) leftAligned to rightAligned else rightAligned to leftAligned
        val fits = { x: Int -> x >= -margin && x + width <= windowSize.width + margin }
        val x = when {
            fits(preferred) -> preferred
            fits(other) -> other
            else -> preferred.coerceIn(0, max(0, windowSize.width - width))
        }
        val below = anchorBounds.bottom - margin
        val above = anchorBounds.top - height + margin
        val y = when {
            below + height <= windowSize.height + margin -> below
            above >= -margin -> above
            else -> below.coerceIn(0, max(0, windowSize.height - height))
        }
        return IntOffset(x, y)
    }
}
