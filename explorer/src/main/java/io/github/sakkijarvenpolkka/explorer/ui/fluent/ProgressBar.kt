/*
 * Files — the Fluent progress bar: a thin track with a rounded accent indicator.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Progress from 0 to 1, or an indicator moving along the track when [progress] is null
 * (indeterminate). 160 dp wide unless [modifier] sizes it; accessibility services read the
 * value or "in progress".
 */
@Composable
fun FluentProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val colors = FluentTheme.colors
    val bar = modifier.size(width = 160.dp, height = 4.dp)
    if (progress != null) {
        val value = progress.coerceIn(0f, 1f)
        Canvas(bar.progressSemantics(value)) {
            drawTrack(colors.textDisabled)
            drawIndicator(colors.accent, 0f, value)
        }
    } else {
        val phase by rememberInfiniteTransition(label = "progress").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
            label = "phase",
        )
        Canvas(bar.progressSemantics()) {
            drawTrack(colors.textDisabled)
            val start = -INDETERMINATE_LENGTH + (1f + INDETERMINATE_LENGTH) * phase
            drawIndicator(colors.accent, start.coerceAtLeast(0f), (start + INDETERMINATE_LENGTH).coerceAtMost(1f))
        }
    }
}

/** The share of the track the moving indicator covers. */
private const val INDETERMINATE_LENGTH = 0.4f

private fun DrawScope.drawTrack(color: Color) {
    val y = size.height / 2f
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

/** The accent indicator from [from] to [to] (shares of the width, from the start edge), 3 dp thick with round ends. */
private fun DrawScope.drawIndicator(color: Color, from: Float, to: Float) {
    if (to <= from) return
    val thickness = 3.dp.toPx()
    val inner = size.width - thickness
    val y = size.height / 2f
    fun x(share: Float) = thickness / 2f + inner * (if (layoutDirection == LayoutDirection.Ltr) share else 1f - share)
    drawLine(color, Offset(x(from), y), Offset(x(to), y), thickness, StrokeCap.Round)
}
