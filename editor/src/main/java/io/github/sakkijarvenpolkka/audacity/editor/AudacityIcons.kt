/*
 * Audacity Android port — toolbar and track-panel glyphs.
 *
 * Original vector drawings in the style of the Audacity 3.7.9 theme images
 * (notes/ui-reference.md §7): drawn on a 24×24 grid in black and tinted by
 * the caller (Play green, Record red, theme glyph colour otherwise).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object AudacityIcons {
    private val black = SolidColor(Color.Black)

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) =
        path(fill = black, pathFillType = PathFillType.NonZero, pathBuilder = block)

    private fun ImageVector.Builder.stroke(width: Float = 2f, block: PathBuilder.() -> Unit) =
        path(
            stroke = black, strokeLineWidth = width, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        )

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close()
    }

    private fun PathBuilder.tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    // --- Transport ----------------------------------------------------

    val Pause: ImageVector by lazy { icon("Pause") { fill { rect(6f, 5f, 10f, 19f); rect(14f, 5f, 18f, 19f) } } }
    val Play: ImageVector by lazy { icon("Play") { fill { tri(7f, 4.5f, 20f, 12f, 7f, 19.5f) } } }
    val Stop: ImageVector by lazy { icon("Stop") { fill { rect(6f, 6f, 18f, 18f) } } }
    val SkipToStart: ImageVector by lazy {
        icon("SkipToStart") { fill { rect(5f, 5.5f, 7.5f, 18.5f); tri(19.5f, 5.5f, 8.5f, 12f, 19.5f, 18.5f) } }
    }
    val SkipToEnd: ImageVector by lazy {
        icon("SkipToEnd") { fill { tri(4.5f, 5.5f, 15.5f, 12f, 4.5f, 18.5f); rect(16.5f, 5.5f, 19f, 18.5f) } }
    }
    val Record: ImageVector by lazy { icon("Record") { fill { circle(12f, 12f, 7f) } } }
    val Loop: ImageVector by lazy {
        icon("Loop") {
            fill {
                rect(5f, 7f, 16f, 9f); tri(15.5f, 4f, 20f, 8f, 15.5f, 12f)
                rect(5f, 7f, 7f, 13f)
                rect(8f, 15f, 19f, 17f); tri(8.5f, 12f, 4f, 16f, 8.5f, 20f)
                rect(17f, 11f, 19f, 17f)
            }
        }
    }

    // --- Edit toolbar -------------------------------------------------

    private fun ImageVector.Builder.magnifier() {
        stroke(2f) { circle(10f, 10f, 6f) }
        stroke(3f) { moveTo(14.5f, 14.5f); lineTo(20f, 20f) }
    }

    val ZoomIn: ImageVector by lazy {
        icon("ZoomIn") { magnifier(); stroke(1.8f) { moveTo(7f, 10f); lineTo(13f, 10f); moveTo(10f, 7f); lineTo(10f, 13f) } }
    }
    val ZoomOut: ImageVector by lazy {
        icon("ZoomOut") { magnifier(); stroke(1.8f) { moveTo(7f, 10f); lineTo(13f, 10f) } }
    }
    val ZoomNormal: ImageVector by lazy {
        icon("ZoomNormal") {
            magnifier()
            stroke(1.6f) { moveTo(8f, 8f); lineTo(8f, 12.5f); moveTo(12f, 8f); lineTo(12f, 12.5f) }
            fill { rect(9.4f, 9f, 10.6f, 10.2f); rect(9.4f, 11.3f, 10.6f, 12.5f) }
        }
    }
    val ZoomToSelection: ImageVector by lazy {
        icon("ZoomToSelection") {
            magnifier()
            stroke(1.6f) {
                moveTo(6.5f, 10f); lineTo(13.5f, 10f)
                moveTo(8.5f, 8f); lineTo(6.5f, 10f); lineTo(8.5f, 12f)
                moveTo(11.5f, 8f); lineTo(13.5f, 10f); lineTo(11.5f, 12f)
            }
        }
    }
    val ZoomFit: ImageVector by lazy {
        icon("ZoomFit") {
            fill { rect(2.5f, 5f, 4.5f, 19f); rect(19.5f, 5f, 21.5f, 19f) }
            stroke(2f) {
                moveTo(7f, 12f); lineTo(17f, 12f)
                moveTo(10f, 9f); lineTo(7f, 12f); lineTo(10f, 15f)
                moveTo(14f, 9f); lineTo(17f, 12f); lineTo(14f, 15f)
            }
        }
    }
    val Trim: ImageVector by lazy {
        icon("Trim") {
            stroke(1.8f) {
                moveTo(6f, 4f); lineTo(3.5f, 4f); lineTo(3.5f, 20f); lineTo(6f, 20f)
                moveTo(18f, 4f); lineTo(20.5f, 4f); lineTo(20.5f, 20f); lineTo(18f, 20f)
            }
            stroke(1.6f) {
                moveTo(7f, 12f); lineTo(8.5f, 8f); lineTo(10f, 16f); lineTo(11.5f, 6f); lineTo(13f, 18f)
                lineTo(14.5f, 9f); lineTo(16f, 14f); lineTo(17f, 12f)
            }
        }
    }
    val Silence: ImageVector by lazy {
        icon("Silence") {
            stroke(1.6f) {
                moveTo(2.5f, 12f); lineTo(3.5f, 8f); lineTo(5f, 16f); lineTo(6.5f, 6f); lineTo(8f, 12f)
                lineTo(16f, 12f)
                lineTo(17.5f, 7f); lineTo(19f, 17f); lineTo(20.5f, 9f); lineTo(21.5f, 12f)
            }
            stroke(1f) { moveTo(8.5f, 5f); lineTo(8.5f, 19f); moveTo(15.5f, 5f); lineTo(15.5f, 19f) }
        }
    }
    val Undo: ImageVector by lazy {
        icon("Undo") {
            stroke(2.2f) {
                moveTo(8f, 9f); lineTo(15f, 9f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 19f)
                lineTo(9f, 19f)
            }
            fill { tri(9f, 4.5f, 3.5f, 9f, 9f, 13.5f) }
        }
    }
    val Redo: ImageVector by lazy {
        icon("Redo") {
            stroke(2.2f) {
                moveTo(16f, 9f); lineTo(9f, 9f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 9f, y1 = 19f)
                lineTo(15f, 19f)
            }
            fill { tri(15f, 4.5f, 20.5f, 9f, 15f, 13.5f) }
        }
    }

    // --- Mobile edit bar -----------------------------------------------

    /** ✂ Split (scissors). */
    val Split: ImageVector by lazy {
        icon("Split") {
            stroke(1.8f) {
                circle(6.5f, 17.5f, 2.6f)
                circle(17.5f, 17.5f, 2.6f)
                moveTo(8.4f, 15.6f); lineTo(18f, 3.5f)
                moveTo(15.6f, 15.6f); lineTo(6f, 3.5f)
            }
        }
    }

    /** Cut: the selected part (dashed) leaves the clip. */
    val Cut: ImageVector by lazy {
        icon("Cut") {
            fill { rect(1.5f, 8f, 7f, 16f); rect(17f, 8f, 22.5f, 16f) }
            stroke(1.4f) {
                moveTo(9f, 6f); lineTo(11f, 6f); moveTo(13f, 6f); lineTo(15f, 6f)
                moveTo(9f, 18f); lineTo(11f, 18f); moveTo(13f, 18f); lineTo(15f, 18f)
                moveTo(9f, 8f); lineTo(9f, 10.5f); moveTo(9f, 13.5f); lineTo(9f, 16f)
                moveTo(15f, 8f); lineTo(15f, 10.5f); moveTo(15f, 13.5f); lineTo(15f, 16f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroke(1.7f) {
                rect(8.5f, 8.5f, 20f, 20f)
                moveTo(5.5f, 15.5f); lineTo(4f, 15.5f); lineTo(4f, 4f); lineTo(15.5f, 4f); lineTo(15.5f, 5.5f)
            }
        }
    }

    val Paste: ImageVector by lazy {
        icon("Paste") {
            stroke(1.7f) {
                moveTo(8f, 5f); lineTo(5f, 5f); lineTo(5f, 21f); lineTo(19f, 21f); lineTo(19f, 5f); lineTo(16f, 5f)
            }
            fill { rect(8.5f, 3f, 15.5f, 7.5f) }
            stroke(1.4f) { moveTo(8.5f, 12f); lineTo(15.5f, 12f); moveTo(8.5f, 16f); lineTo(13.5f, 16f) }
        }
    }

    /** Delete (a waste bin). */
    val Delete: ImageVector by lazy {
        icon("Delete") {
            stroke(1.7f) {
                moveTo(4f, 6.5f); lineTo(20f, 6.5f)
                moveTo(9.5f, 6.5f); lineTo(9.5f, 3.8f); lineTo(14.5f, 3.8f); lineTo(14.5f, 6.5f)
                moveTo(6f, 6.5f); lineTo(7f, 20.5f); lineTo(17f, 20.5f); lineTo(18f, 6.5f)
                moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f)
            }
        }
    }

    val Duplicate: ImageVector by lazy {
        icon("Duplicate") {
            fill { rect(2.5f, 5f, 13f, 11f) }
            stroke(1.5f) { rect(6f, 13.5f, 16.5f, 19.5f) }
            stroke(1.8f) { moveTo(19.5f, 11.5f); lineTo(19.5f, 19.5f); moveTo(15.5f, 15.5f); lineTo(23f, 15.5f) }
        }
    }

    /** Marker: a label flag on its pole. */
    val Marker: ImageVector by lazy {
        icon("Marker") {
            stroke(1.8f) { moveTo(6f, 21f); lineTo(6f, 3.5f) }
            fill { moveTo(6.8f, 4f); lineTo(18.5f, 4f); lineTo(15.5f, 8f); lineTo(18.5f, 12f); lineTo(6.8f, 12f); close() }
        }
    }

    /** Effects (a wand with sparkles). */
    val Effects: ImageVector by lazy {
        icon("Effects") {
            stroke(2f) { moveTo(4f, 20f); lineTo(14f, 10f) }
            fill {
                moveTo(17f, 2.5f); lineTo(18f, 5.5f); lineTo(21f, 6.5f); lineTo(18f, 7.5f)
                lineTo(17f, 10.5f); lineTo(16f, 7.5f); lineTo(13f, 6.5f); lineTo(16f, 5.5f); close()
                moveTo(8f, 3f); lineTo(8.6f, 4.9f); lineTo(10.5f, 5.5f); lineTo(8.6f, 6.1f)
                lineTo(8f, 8f); lineTo(7.4f, 6.1f); lineTo(5.5f, 5.5f); lineTo(7.4f, 4.9f); close()
                moveTo(19f, 13f); lineTo(19.5f, 14.5f); lineTo(21f, 15f); lineTo(19.5f, 15.5f)
                lineTo(19f, 17f); lineTo(18.5f, 15.5f); lineTo(17f, 15f); lineTo(18.5f, 14.5f); close()
            }
        }
    }

    // --- Track control panel -------------------------------------------

    val Close: ImageVector by lazy {
        icon("Close") { stroke(2f) { moveTo(7f, 7f); lineTo(17f, 17f); moveTo(17f, 7f); lineTo(7f, 17f) } }
    }
    val ChevronUp: ImageVector by lazy {
        icon("ChevronUp") { stroke(2.2f) { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) } }
    }
    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") { stroke(2.2f) { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
    }
    val Ellipsis: ImageVector by lazy {
        icon("Ellipsis") { fill { circle(5.5f, 12f, 1.8f); circle(12f, 12f, 1.8f); circle(18.5f, 12f, 1.8f) } }
    }
    val Mixer: ImageVector by lazy {
        icon("Mixer") {
            stroke(1.6f) {
                moveTo(6f, 4f); lineTo(6f, 20f); moveTo(12f, 4f); lineTo(12f, 20f); moveTo(18f, 4f); lineTo(18f, 20f)
            }
            fill { rect(3.5f, 13f, 8.5f, 16f); rect(9.5f, 7f, 14.5f, 10f); rect(15.5f, 11f, 20.5f, 14f) }
        }
    }

    // --- Meters ---------------------------------------------------------

    val Mic: ImageVector by lazy {
        icon("Mic") {
            fill {
                moveTo(9f, 5f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 5f)
                lineTo(15f, 11f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 9f, y1 = 11f)
                close()
            }
            stroke(1.6f) {
                moveTo(6f, 10.5f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18f, y1 = 10.5f)
                moveTo(12f, 16.5f); lineTo(12f, 20f); moveTo(9f, 20f); lineTo(15f, 20f)
            }
        }
    }
    val Speaker: ImageVector by lazy {
        icon("Speaker") {
            fill { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4.5f); lineTo(12f, 19.5f); lineTo(7f, 15f); lineTo(3f, 15f); close() }
            stroke(1.6f) {
                moveTo(15f, 9f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 15f)
                moveTo(17.5f, 6f)
                arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.5f, y1 = 18f)
            }
        }
    }
    val TimelineOptions: ImageVector by lazy {
        icon("TimelineOptions") {
            fill { tri(6f, 7f, 18f, 7f, 12f, 14f) }
            stroke(1.6f) { moveTo(4f, 18f); lineTo(20f, 18f) }
        }
    }
}
