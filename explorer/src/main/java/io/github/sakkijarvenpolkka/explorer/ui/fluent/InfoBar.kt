/*
 * Files — the Fluent info bar: an inline message with a severity, an action and a close button.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R

/** How an [InfoBar] is coloured and which badge it shows. */
enum class InfoSeverity { INFO, WARNING, ERROR, SUCCESS }

/**
 * A full-width message bar: a severity badge (named after the severity for accessibility
 * services), [message] (announced politely when it appears or changes), an optional [action]
 * button and, with [onClose], a close button.
 */
@Composable
fun InfoBar(severity: InfoSeverity, message: String, action: Pair<String, () -> Unit>? = null, onClose: (() -> Unit)? = null) {
    val colors = FluentTheme.colors
    val shape = FluentTheme.shapes.control
    val tone = severityTone(severity, colors)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .background(severityBackground(severity, colors.isDark), shape)
            .border(1.dp, colors.stroke, shape)
            .padding(start = 16.dp, end = if (onClose != null) 4.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badge = stringResource(severityName(severity))
        Canvas(
            Modifier
                .size(20.dp)
                .semantics {
                    contentDescription = badge
                    role = Role.Image
                },
        ) { drawBadge(severity, tone, contentColorOn(tone)) }
        BasicText(
            message,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = FluentTheme.type.body.copy(color = colors.text),
        )
        if (action != null) FluentButton(action.first, action.second)
        if (onClose != null) {
            FluentButton(
                text = null,
                onClick = onClose,
                icon = FluentIcons.Close,
                style = ButtonStyle.SUBTLE,
                contentDescription = stringResource(R.string.close),
            )
        }
    }
}

@StringRes
private fun severityName(severity: InfoSeverity): Int = when (severity) {
    InfoSeverity.INFO -> R.string.severity_info
    InfoSeverity.WARNING -> R.string.severity_warning
    InfoSeverity.ERROR -> R.string.severity_error
    InfoSeverity.SUCCESS -> R.string.severity_success
}

private fun severityTone(severity: InfoSeverity, colors: FluentColors): Color = when (severity) {
    InfoSeverity.INFO -> colors.accent
    InfoSeverity.WARNING -> colors.warning
    InfoSeverity.ERROR -> colors.danger
    InfoSeverity.SUCCESS -> if (colors.isDark) Color(0xFF6CCB5F) else Color(0xFF0F7B0F)
}

private fun severityBackground(severity: InfoSeverity, dark: Boolean): Color = when (severity) {
    InfoSeverity.INFO -> if (dark) Color(0xFF2D2D2D) else Color(0xFFF6F6F6)
    InfoSeverity.WARNING -> if (dark) Color(0xFF433519) else Color(0xFFFFF4CE)
    InfoSeverity.ERROR -> if (dark) Color(0xFF442726) else Color(0xFFFDE7E9)
    InfoSeverity.SUCCESS -> if (dark) Color(0xFF393D1B) else Color(0xFFDFF6DD)
}

/** A filled [tone] circle with the severity glyph (i, !, × or ✓) in [glyph], on a 20 unit grid. */
private fun DrawScope.drawBadge(severity: InfoSeverity, tone: Color, glyph: Color) {
    val unit = size.minDimension / 20f
    fun at(x: Float, y: Float) = Offset(x * unit, y * unit)
    val width = 1.75f * unit
    drawCircle(tone)
    when (severity) {
        InfoSeverity.INFO -> {
            drawLine(glyph, at(10f, 9f), at(10f, 14.5f), width, StrokeCap.Round)
            drawCircle(glyph, 1.2f * unit, at(10f, 5.75f))
        }
        InfoSeverity.WARNING -> {
            drawLine(glyph, at(10f, 5.5f), at(10f, 11f), width, StrokeCap.Round)
            drawCircle(glyph, 1.2f * unit, at(10f, 14.25f))
        }
        InfoSeverity.ERROR -> {
            drawLine(glyph, at(6.75f, 6.75f), at(13.25f, 13.25f), width, StrokeCap.Round)
            drawLine(glyph, at(13.25f, 6.75f), at(6.75f, 13.25f), width, StrokeCap.Round)
        }
        InfoSeverity.SUCCESS -> {
            val (start, corner, end) = listOf(at(5.75f, 10.25f), at(8.75f, 13.25f), at(14.25f, 7f))
            val check = Path().apply {
                moveTo(start.x, start.y)
                lineTo(corner.x, corner.y)
                lineTo(end.x, end.y)
            }
            drawPath(check, glyph, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
