/*
 * Audacity Android port — editor theme.
 *
 * Exact colours of the Audacity 3.7.9 "Light" and "Dark" themes, taken from
 * lib-theme-resources/{light,dark}/Components/Colors.txt (see
 * notes/ui-reference.md §6.2/§6.3/§6.5). Names in the KDoc are the theme
 * names (`clr…`) of AllThemeResources.h.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Theme preference: Audacity Light, Audacity Dark, or follow the system. */
enum class ThemeChoice { LIGHT, DARK, SYSTEM }

/** Colours of one meter direction (MeterInput… / MeterOutput…). */
@Immutable
data class MeterColors(
    val peak: Color,
    val rms: Color,
    val clip: Color,
)

/**
 * Semantic Audacity palette. Every value is an exact `Colors.txt` entry of the
 * theme (or a documented computed colour, ui-reference §6.4).
 */
@Immutable
data class AudacityPalette(
    val isDark: Boolean,
    /** Medium: toolbar / panel background. */
    val medium: Color,
    /** Light / Dark: bevels and separators. */
    val light: Color,
    val dark: Color,
    /** TrackBackground: below the last track and between tracks. */
    val trackBackground: Color,
    /** TrackInfo / TrackInfoSelected: TCP and vertical ruler background. */
    val trackInfo: Color,
    val trackInfoSelected: Color,
    /** TrackPanelText: text in the TCP and rulers. */
    val text: Color,
    /** Unselected / Selected: clip body outside / inside the time selection. */
    val clipBody: Color,
    val clipBodySelected: Color,
    /** Blank / BlankSelected: wave area without clips. */
    val blank: Color,
    val blankSelected: Color,
    /** Sample, Sample2..4 (Instrument 1..4 wave colours). */
    val sample: Color,
    val rms: Color,
    val sampleInstruments: List<Color>,
    val rmsInstruments: List<Color>,
    val muteSample: Color,
    val muteRms: Color,
    val clipped: Color,
    val muteClipped: Color,
    /** DragSample: highlighted sample dot. */
    val dragSample: Color,
    val zeroLine: Color,
    val envelope: Color,
    val envelopeBackground: Color,
    /** Edit cursor (computed by AColor::CursorColour, ui-reference §5.8). */
    val cursor: Color,
    /** PlaybackPen / RecordingPen: play and record head lines. */
    val playHead: Color,
    val recordHead: Color,
    /** Ruler pointers (bmpPlayPointer / bmpRecordPointer). */
    val playPointer: Color,
    val recordPointer: Color,
    val snapGuide: Color,
    /** ClipAffordance…: clip title bar. */
    val clipBar: Color,
    val clipBarActive: Color,
    val clipOutline: Color,
    val clipStroke: Color,
    val clipButton: Color,
    val clipName: Color,
    /** TimelineRulerBackground and its overlays (blends of §5.10). */
    val rulerBackground: Color,
    val rulerSelected: Color,
    val loopOn: Color,
    val loopOff: Color,
    val loopOnSelected: Color,
    /** TimeBack / TimeFont (the dark "LCD" time display). */
    val timeBack: Color,
    val timeFont: Color,
    val timeBackFocus: Color,
    val timeFontFocus: Color,
    val meterInput: MeterColors,
    val meterOutput: MeterColors,
    val meterPeak: Color,
    val meterBackground: Color,
    /** Gradient green / yellow / red, adjusted for the background luminance (MeterPanel.cpp:474-489). */
    val meterGreen: Color,
    val meterYellow: Color,
    val meterRed: Color,
    /** Label track. */
    val labelText: Color,
    val labelBox: Color,
    val labelBoxEdit: Color,
    val labelBarUnselected: Color,
    val labelBarSelected: Color,
    val labelSurround: Color,
    val labelTextSelection: Color,
    val sliderMain: Color,
    val sliderLight: Color,
    /** Button faces (UpButtonLarge / DownButtonLarge / HiliteUpButtonLarge). */
    val buttonUp: Color,
    val buttonDown: Color,
    val buttonHilite: Color,
    /** Mute/Solo faces: UpButtonExpand / DownButtonExpand. */
    val toggleOff: Color,
    val toggleOn: Color,
    val toggleOnText: Color,
    /** Glyph colours of the toolbar icons. */
    val glyph: Color,
    val glyphDisabled: Color,
    val playGlyph: Color,
    val recordGlyph: Color,
    val trackFocus: Color,
    val progressDone: Color,
    val progressNotYet: Color,
    val accent: Color,
) {
    /** Wave colour of instrument [index] (0..3), muted variant when [muted]. */
    fun sampleColor(index: Int, muted: Boolean): Color =
        if (muted) muteSample else sampleInstruments.getOrElse(index) { sample }

    fun rmsColor(index: Int, muted: Boolean): Color =
        if (muted) muteRms else rmsInstruments.getOrElse(index) { rms }
}

private fun hex(v: Long): Color = Color(0xFF000000L or v)

/** Meter gradient colours: subtract 100 below luminance 0.25, 50 below 0.5. */
private fun meterGradient(background: Color): Triple<Color, Color, Color> {
    val lum = background.luminance()
    val sub = when {
        lum < 0.25f -> 100
        lum < 0.50f -> 50
        else -> 0
    }
    fun c(r: Int, g: Int, b: Int) = Color(
        (r - sub).coerceAtLeast(0), (g - sub).coerceAtLeast(0), (b - sub).coerceAtLeast(0),
    )
    return Triple(c(117, 215, 112), c(255, 255, 0), c(255, 0, 0))
}

/** The built-in Audacity palettes. */
object AudacityColors {
    val Light: AudacityPalette = run {
        val (g, y, r) = meterGradient(hex(0xF8F8F9))
        AudacityPalette(
            isDark = false,
            medium = hex(0xF8F8F9),
            light = hex(0xF0F1F5),
            dark = hex(0xDFDFE4),
            trackBackground = hex(0x323545),
            trackInfo = hex(0xF8F8F9),
            trackInfoSelected = hex(0xDBE7FF),
            text = hex(0x14151A),
            clipBody = hex(0xF0F3FF),
            clipBodySelected = hex(0xAAC3F2),
            blank = hex(0x45485A),
            blankSelected = hex(0x526283),
            sample = hex(0x6464D3),
            rms = hex(0x9797FD),
            sampleInstruments = listOf(hex(0x6464D3), hex(0x286CCA), hex(0x5E8954), hex(0x383838)),
            rmsInstruments = listOf(hex(0x9797FD), hex(0x8AB6F4), hex(0x95E084), hex(0x969696)),
            muteSample = hex(0x888890),
            muteRms = hex(0x888890),
            clipped = hex(0xEF476F),
            muteClipped = hex(0x9295A6),
            dragSample = hex(0x14151A),
            zeroLine = hex(0x000000),
            envelope = hex(0x677CE4),
            envelopeBackground = hex(0xD5E3FF),
            cursor = hex(0x14151A),
            playHead = hex(0x14151A),
            recordHead = hex(0xB0001C),
            playPointer = hex(0x75D570),
            recordPointer = hex(0xCF4646),
            snapGuide = hex(0xFFFF00),
            clipBar = hex(0xCFD6F4),
            clipBarActive = hex(0xAEB6DB),
            clipOutline = hex(0x14151A),
            clipStroke = hex(0xFFFFFF),
            clipButton = hex(0x949BBA),
            clipName = hex(0x14151A),
            rulerBackground = hex(0xE9E9EB),
            rulerSelected = hex(0xBBCCF3),
            loopOn = hex(0xC4C6C8),
            loopOff = hex(0xE9E9EB),
            loopOnSelected = hex(0xA4B7DE),
            timeBack = hex(0x323545),
            timeFont = hex(0xF8F8F9),
            timeBackFocus = hex(0x677CE4),
            timeFontFocus = hex(0xF8F8F9),
            meterInput = MeterColors(hex(0xEF476F), hex(0xFF4C76), hex(0xEF476F)),
            meterOutput = MeterColors(hex(0x18A999), hex(0x1AB8A6), hex(0xEF476F)),
            meterPeak = hex(0x677CE4),
            meterBackground = hex(0xF8F8F9),
            meterGreen = g,
            meterYellow = y,
            meterRed = r,
            labelText = hex(0x14151A),
            labelBox = hex(0xC2CBF4),
            labelBoxEdit = hex(0xF8F8F9),
            labelBarUnselected = hex(0xC0C0C0),
            labelBarSelected = hex(0xEBF2FF),
            labelSurround = hex(0x14151A),
            labelTextSelection = hex(0xB7CAE2),
            sliderMain = hex(0xC3D2E0),
            sliderLight = hex(0xF0F1F5),
            buttonUp = hex(0xDDDEE4),
            buttonDown = hex(0xC2C4CF),
            buttonHilite = hex(0xCFD1D9),
            toggleOff = hex(0xDDDEE4),
            toggleOn = hex(0x677CE4),
            toggleOnText = hex(0xFFFFFF),
            glyph = hex(0x000000),
            glyphDisabled = hex(0x9295A6),
            playGlyph = hex(0x27734D),
            recordGlyph = hex(0x953130),
            trackFocus = hex(0x97A8FD),
            progressDone = hex(0x677CE4),
            progressNotYet = hex(0xDDDEE4),
            accent = hex(0x677CE4),
        )
    }

    val Dark: AudacityPalette = run {
        val (g, y, r) = meterGradient(hex(0x404040))
        AudacityPalette(
            isDark = true,
            medium = hex(0x292E33),
            light = hex(0x2F353B),
            dark = hex(0x44494F),
            trackBackground = hex(0x23272B),
            trackInfo = hex(0x292E33),
            trackInfoSelected = hex(0x434857),
            text = hex(0xF0F5FA),
            clipBody = hex(0x343B4E),
            clipBodySelected = hex(0x525D7D),
            blank = hex(0x2E3236),
            blankSelected = hex(0x555A72),
            sample = hex(0x7373E5),
            rms = hex(0x9898ED),
            sampleInstruments = listOf(hex(0x7373E5), hex(0xA00A0A), hex(0x236E23), hex(0x000000)),
            rmsInstruments = listOf(hex(0x9898ED), hex(0xE65050), hex(0x4BC84B), hex(0x646464)),
            muteSample = hex(0x7E7E80),
            muteRms = hex(0x9B9B9D),
            clipped = hex(0xEF476F),
            muteClipped = hex(0x9295A6),
            dragSample = hex(0x000000),
            zeroLine = hex(0x000000),
            envelope = hex(0x677CE4),
            envelopeBackground = hex(0x303030),
            // CursorColour(): CursorPen (#14151A) is too close to Medium → clrSelected.
            cursor = hex(0x525D7D),
            playHead = hex(0x14151A),
            recordHead = hex(0xF7B6B6),
            playPointer = hex(0x75D570),
            recordPointer = hex(0xCF4646),
            snapGuide = hex(0xBB8B48),
            clipBar = hex(0x404966),
            clipBarActive = hex(0x505B80),
            clipOutline = hex(0x636A79),
            clipStroke = hex(0xFFFFFF),
            clipButton = hex(0x636B85),
            clipName = hex(0xF0F5FA),
            rulerBackground = hex(0x292E33),
            rulerSelected = hex(0x535761),
            loopOn = hex(0x4A4D59),
            loopOff = hex(0x292E33),
            loopOnSelected = hex(0x676A78),
            timeBack = hex(0x191F24),
            timeFont = hex(0xF0F5FA),
            timeBackFocus = hex(0x484F57),
            timeFontFocus = hex(0xF0F5FA),
            meterInput = MeterColors(hex(0xEF476F), hex(0xFF4C76), hex(0xFF3535)),
            meterOutput = MeterColors(hex(0x18A999), hex(0x1AB8A6), hex(0xFF3535)),
            meterPeak = hex(0x677CE4),
            meterBackground = hex(0x404040),
            meterGreen = g,
            meterYellow = y,
            meterRed = r,
            labelText = hex(0xF0F5FA),
            labelBox = hex(0x15181B),
            labelBoxEdit = hex(0x2F353B),
            labelBarUnselected = hex(0x606060),
            labelBarSelected = hex(0x8B8571),
            labelSurround = hex(0xF0F5FA),
            labelTextSelection = hex(0xB7CAE2),
            sliderMain = hex(0x484F57),
            sliderLight = hex(0x484F57),
            buttonUp = hex(0x495057),
            buttonDown = hex(0x757F8A),
            buttonHilite = hex(0x535A62),
            toggleOff = hex(0x484F57),
            toggleOn = hex(0x677CE4),
            toggleOnText = hex(0xFFFFFF),
            glyph = hex(0xFFFFFF),
            glyphDisabled = hex(0x6B717A),
            playGlyph = hex(0x51FF00),
            recordGlyph = hex(0xDA0200),
            trackFocus = hex(0x677CE4),
            progressDone = hex(0x677CE4),
            progressNotYet = hex(0xDDDEE4),
            accent = hex(0x677CE4),
        )
    }

    /** The palette for [theme] given whether the system is in dark mode. */
    fun forChoice(theme: ThemeChoice, systemDark: Boolean): AudacityPalette = when (theme) {
        ThemeChoice.LIGHT -> Light
        ThemeChoice.DARK -> Dark
        ThemeChoice.SYSTEM -> if (systemDark) Dark else Light
    }
}

/** The current Audacity palette (provided by [AudacityTheme]). */
val LocalAudacityColors: ProvidableCompositionLocal<AudacityPalette> =
    staticCompositionLocalOf { AudacityColors.Light }

/**
 * Applies the Audacity palette ([LocalAudacityColors]) and a Material 3 colour
 * scheme derived from it, so Material components (dialogs, menus, sliders)
 * blend with the Audacity look.
 */
@Composable
fun AudacityTheme(theme: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val palette = AudacityColors.forChoice(theme, isSystemInDarkTheme())
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.clipBodySelected,
            onPrimaryContainer = palette.text,
            secondary = palette.meterOutput.peak,
            tertiary = palette.meterInput.peak,
            background = palette.trackBackground,
            onBackground = palette.text,
            surface = palette.medium,
            onSurface = palette.text,
            surfaceVariant = palette.light,
            onSurfaceVariant = palette.text,
            surfaceContainer = palette.medium,
            surfaceContainerHigh = palette.light,
            surfaceContainerHighest = palette.dark,
            outline = palette.clipOutline,
            error = palette.clipped,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.trackInfoSelected,
            onPrimaryContainer = palette.text,
            secondary = palette.meterOutput.peak,
            tertiary = palette.meterInput.peak,
            background = palette.medium,
            onBackground = palette.text,
            surface = palette.medium,
            onSurface = palette.text,
            surfaceVariant = palette.light,
            onSurfaceVariant = palette.text,
            surfaceContainer = palette.medium,
            surfaceContainerHigh = palette.light,
            surfaceContainerHighest = palette.dark,
            outline = palette.clipOutline,
            error = palette.recordHead,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalAudacityColors provides palette, content = content)
    }
}
