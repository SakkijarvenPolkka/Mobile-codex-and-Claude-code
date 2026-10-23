/*
 * Files — the Fluent toggle switch with its label.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A full-width row with [label] and a 40 × 20 dp switch at its end; touching anywhere on the
 * row calls [onCheckedChange] with the new value. On: accent track with the knob at the
 * end; off: outlined track with the knob at the start.
 */
@Composable
fun FluentToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "knob")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, modifier = Modifier.weight(1f), style = FluentTheme.type.body.copy(color = colors.text))
        Canvas(Modifier.size(width = 40.dp, height = 20.dp)) {
            val corner = CornerRadius(size.height / 2f)
            val border = 1.dp.toPx()
            val trackFill = colors.accent.copy(alpha = position)
            drawRoundRect(trackFill, cornerRadius = corner)
            if (position < 1f) {
                drawRoundRect(
                    colors.textSecondary.copy(alpha = colors.textSecondary.alpha * (1f - position)),
                    topLeft = Offset(border / 2f, border / 2f),
                    size = size.copy(width = size.width - border, height = size.height - border),
                    cornerRadius = corner,
                    style = Stroke(border),
                )
            }
            val radius = 6.dp.toPx()
            val travel = size.width - 2 * (size.height / 2f)
            val share = if (layoutDirection == LayoutDirection.Ltr) position else 1f - position
            drawCircle(
                lerp(colors.textSecondary, colors.onAccent, position),
                radius = radius,
                center = Offset(size.height / 2f + travel * share, size.height / 2f),
            )
        }
    }
}
