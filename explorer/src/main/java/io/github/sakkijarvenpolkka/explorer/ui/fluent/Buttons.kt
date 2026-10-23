/*
 * Files — Fluent buttons: standard, accent and subtle.
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** [STANDARD]: card fill with a border; [ACCENT]: the default action; [SUBTLE]: no fill until touched. */
enum class ButtonStyle { STANDARD, ACCENT, SUBTLE }

/**
 * A button with [text], an [icon] or both. An icon-only button needs [contentDescription];
 * when given, it is also what accessibility services read for a button with text. The touch
 * target is at least 48 × 48 dp, of which a 40 dp high area is drawn. Pressing changes the
 * fill (no ripple).
 */
@Composable
fun FluentButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.STANDARD,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    ButtonFrame(
        onClick = onClick,
        modifier = modifier,
        style = style,
        enabled = enabled,
        role = Role.Button,
        contentDescription = contentDescription,
        iconOnly = text == null,
    ) { content ->
        if (icon != null) FluentIcon(icon, contentDescription = null, tint = content)
        if (text != null) {
            BasicText(
                text,
                style = FluentTheme.type.body.copy(color = content),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The clickable frame of every button: [role] and [contentDescription] semantics, the 48 dp
 * touch target, the drawn area in [style] and the colour of its [content].
 */
@Composable
internal fun ButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    style: ButtonStyle,
    enabled: Boolean,
    role: Role,
    contentDescription: String?,
    iconOnly: Boolean,
    content: @Composable RowScope.(contentColor: Color) -> Unit,
) {
    val colors = FluentTheme.colors
    val shape = FluentTheme.shapes.control
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fill = buttonFill(style, enabled, pressed, hovered, colors)
    val contentColor = buttonContent(style, enabled, pressed, colors)
    Row(
        modifier
            .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .padding(vertical = 4.dp)
            .background(fill, shape)
            .then(if (style == ButtonStyle.STANDARD) Modifier.border(1.dp, colors.stroke, shape) else Modifier)
            .padding(horizontal = if (iconOnly) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(contentColor)
    }
}

/** The minimum width and height of anything that can be touched (spec §2.6). */
internal val MinTouchTarget = 48.dp

private fun buttonFill(style: ButtonStyle, enabled: Boolean, pressed: Boolean, hovered: Boolean, colors: FluentColors): Color {
    val overlay = when {
        !enabled -> Color.Transparent
        pressed -> colors.pressed
        hovered -> colors.hover
        else -> Color.Transparent
    }
    return when (style) {
        ButtonStyle.STANDARD -> overlay.compositeOver(colors.card)
        ButtonStyle.SUBTLE -> overlay
        ButtonStyle.ACCENT -> when {
            !enabled -> colors.textDisabled
            pressed -> colors.accent.copy(alpha = 0.8f)
            hovered -> colors.accent.copy(alpha = 0.9f)
            else -> colors.accent
        }
    }
}

private fun buttonContent(style: ButtonStyle, enabled: Boolean, pressed: Boolean, colors: FluentColors): Color = when {
    style == ButtonStyle.ACCENT -> if (enabled) colors.onAccent else colors.background
    !enabled -> colors.textDisabled
    pressed -> colors.textSecondary
    else -> colors.text
}
