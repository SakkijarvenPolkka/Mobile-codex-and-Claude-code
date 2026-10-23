/*
 * Files — the Fluent look: colours, corner shapes and type ramp (spec §2.6).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours of one theme. [background] is the window (a flat Mica-like layer, no blur),
 * [layer] a raised area on it and [card] cards, menus and controls. [selection] is [accent]
 * at 10 % behind selected rows. [hover] and [pressed] are overlays drawn over a control in
 * place of a ripple.
 */
data class FluentColors(
    val background: Color,
    val layer: Color,
    val card: Color,
    val stroke: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    val selection: Color,
    val hover: Color,
    val pressed: Color,
    val danger: Color,
    val warning: Color,
    val isDark: Boolean,
)

/** The light and dark palettes, each with a replaceable accent (the system accent, for example). */
object FluentColorsDefaults {

    fun light(accent: Color = Color(0xFF0067C0)): FluentColors = FluentColors(
        background = Color(0xFFF3F3F3),
        layer = Color(0xFFF9F9F9),
        card = Color(0xFFFBFBFB),
        stroke = Color(0x14000000),
        text = Color(0xE4000000),
        textSecondary = Color(0x9E000000),
        textDisabled = Color(0x5C000000),
        accent = accent,
        onAccent = contentColorOn(accent),
        selection = accent.copy(alpha = SELECTION_ALPHA),
        hover = Color(0x09000000),
        pressed = Color(0x12000000),
        danger = Color(0xFFC42B1C),
        warning = Color(0xFF9D5D00),
        isDark = false,
    )

    fun dark(accent: Color = Color(0xFF4CC2FF)): FluentColors = FluentColors(
        background = Color(0xFF202020),
        layer = Color(0xFF282828),
        card = Color(0xFF2B2B2B),
        stroke = Color(0x17FFFFFF),
        text = Color(0xFFFFFFFF),
        textSecondary = Color(0xC5FFFFFF),
        textDisabled = Color(0x5DFFFFFF),
        accent = accent,
        onAccent = contentColorOn(accent),
        selection = accent.copy(alpha = SELECTION_ALPHA),
        hover = Color(0x0FFFFFFF),
        pressed = Color(0x1AFFFFFF),
        danger = Color(0xFFFF99A4),
        warning = Color(0xFFFCE100),
        isDark = true,
    )

    private const val SELECTION_ALPHA = 0.10f
}

/** Corner shapes: [control] for buttons, text boxes and rows, [overlay] for menus, dialogs and cards. */
data class FluentShapes(
    val control: Shape = RoundedCornerShape(4.dp),
    val overlay: Shape = RoundedCornerShape(8.dp),
)

/**
 * The Fluent type ramp in the system sans-serif font: Caption 12, Body 14, Body Strong 14,
 * Subtitle 20, Title 28, plus a monospace body. Styles carry no colour; components add it.
 */
data class FluentType(
    val caption: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    val body: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    val bodyStrong: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    val subtitle: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    val title: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    val mono: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace),
)

/** What the components read: the colours provided by the nearest [FluentTheme], the shapes and the type ramp. */
object FluentTheme {
    val colors: FluentColors
        @Composable @ReadOnlyComposable get() = LocalFluentColors.current

    val shapes: FluentShapes = FluentShapes()

    val type: FluentType = FluentType()
}

/**
 * Provides the light or [dark] palette to [content], with [accent] in place of the default
 * accent when it is not null. Text selection handles and highlight follow the accent.
 */
@Composable
fun FluentTheme(dark: Boolean, accent: Color?, content: @Composable () -> Unit) {
    val colors = remember(dark, accent) {
        when {
            dark && accent != null -> FluentColorsDefaults.dark(accent)
            dark -> FluentColorsDefaults.dark()
            accent != null -> FluentColorsDefaults.light(accent)
            else -> FluentColorsDefaults.light()
        }
    }
    val selectionColors = remember(colors.accent) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.4f))
    }
    CompositionLocalProvider(
        LocalFluentColors provides colors,
        LocalTextSelectionColors provides selectionColors,
        content = content,
    )
}

/** Black or white, whichever contrasts more with [background] (WCAG relative luminance). */
internal fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.179f) Color.Black else Color.White

private val LocalFluentColors = staticCompositionLocalOf { FluentColorsDefaults.light() }
