/*
 * Audacity Android port — small custom vector icons used by the app shell
 * (material-icons-core lacks undo/redo/folder glyphs).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {
    private fun icon(name: String, fill: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (fill) {
                path(fill = SolidColor(Color.Black), pathBuilder = block)
            } else {
                path(
                    stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
                )
            }
        }.build()

    val Undo: ImageVector by lazy {
        icon("Undo") {
            moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
            moveTo(4f, 9f); horizontalLineTo(14.5f)
            curveTo(17.5f, 9f, 20f, 11.5f, 20f, 14.5f)
            curveTo(20f, 17.5f, 17.5f, 20f, 14.5f, 20f)
            horizontalLineTo(11f)
        }
    }

    val Redo: ImageVector by lazy {
        icon("Redo") {
            moveTo(15f, 14f); lineTo(20f, 9f); lineTo(15f, 4f)
            moveTo(20f, 9f); horizontalLineTo(9.5f)
            curveTo(6.5f, 9f, 4f, 11.5f, 4f, 14.5f)
            curveTo(4f, 17.5f, 6.5f, 20f, 9.5f, 20f)
            horizontalLineTo(13f)
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            moveTo(3f, 6f); lineTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 8f); lineTo(11f, 8f); lineTo(9f, 5f); lineTo(3f, 5f); close()
        }
    }

    val Save: ImageVector by lazy {
        icon("Save") {
            moveTo(5f, 4f); lineTo(16f, 4f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(4f, 20f); lineTo(4f, 4f); close()
            moveTo(8f, 4f); lineTo(8f, 9f); lineTo(15f, 9f); lineTo(15f, 4f)
            moveTo(8f, 20f); lineTo(8f, 14f); lineTo(16f, 14f); lineTo(16f, 20f)
        }
    }

    val Export: ImageVector by lazy {
        icon("Export") {
            moveTo(12f, 15f); lineTo(12f, 3f)
            moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
            moveTo(5f, 13f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 13f)
        }
    }

    val Waveform: ImageVector by lazy {
        icon("Waveform") {
            moveTo(3f, 12f); lineTo(5f, 12f); moveTo(7f, 8f); lineTo(7f, 16f); moveTo(10f, 4f); lineTo(10f, 20f)
            moveTo(13f, 7f); lineTo(13f, 17f); moveTo(16f, 10f); lineTo(16f, 14f); moveTo(19f, 12f); lineTo(21f, 12f)
        }
    }
}
