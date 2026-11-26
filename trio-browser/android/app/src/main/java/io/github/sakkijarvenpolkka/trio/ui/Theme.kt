package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF5A49E8)

private val Light: ColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E6FD),
    onPrimaryContainer = Color(0xFF1D1452),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F2F6),
    surfaceContainer = Color(0xFFF4F5F8),
    surfaceContainerHigh = Color(0xFFEDEEF3),
    background = Color(0xFFF4F5F8),
    onSurface = Color(0xFF1A1C21),
    onSurfaceVariant = Color(0xFF5B616D),
    outline = Color(0xFFC5C9D2),
    outlineVariant = Color(0xFFDDE0E6),
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF8E84FF),
    onPrimary = Color(0xFF15131F),
    primaryContainer = Color(0xFF332C66),
    onPrimaryContainer = Color(0xFFE6E2FF),
    surface = Color(0xFF1E1F24),
    surfaceVariant = Color(0xFF2A2B31),
    surfaceContainer = Color(0xFF18191D),
    surfaceContainerHigh = Color(0xFF26272D),
    background = Color(0xFF141519),
    onSurface = Color(0xFFE8E9ED),
    onSurfaceVariant = Color(0xFFA6ABB5),
    outline = Color(0xFF464851),
    outlineVariant = Color(0xFF31333A),
)

@Composable
fun TrioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Accent)

/** The round agent face: monogram, accent ring and a state badge (lock / dot). No provider logos. */
@Composable
fun AgentCircle(monogram: String, accent: String, state: String, size: Dp, busy: Boolean = false) {
    val color = parseColor(accent)
    val signedOut = state == "signedOut"
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (signedOut) MaterialTheme.colorScheme.surfaceVariant else color.copy(alpha = 0.16f))
                .border(if (busy) 3.dp else 2.dp, if (signedOut) color.copy(alpha = 0.45f) else color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val scale = when (monogram.length) { 1 -> 0.44f; 2 -> 0.36f; else -> 0.3f }
            Text(monogram, fontWeight = FontWeight.Bold, fontSize = (size.value * scale).sp, color = if (signedOut) MaterialTheme.colorScheme.onSurfaceVariant else color)
        }
        val badge = size * 0.4f
        Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(badge).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            when (state) {
                "signedOut" -> androidx.compose.material3.Icon(Icons.lock, null, Modifier.size(badge * 0.8f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                "signedIn" -> Box(Modifier.size(badge * 0.72f).clip(CircleShape).background(Color(0xFF1C8F4E)))
                else -> Box(Modifier.size(badge * 0.72f).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            }
        }
    }
}

/** The same line icons as the desktop UI (20 × 20 grid, 1.6 stroke). */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 20f, 20f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val back = icon("back", "M16 10H4M9 5l-5 5 5 5")
    val forward = icon("forward", "M4 10h12M11 5l5 5-5 5")
    val reload = icon("reload", "M16 10a6 6 0 1 1-1.8-4.3", "M16 3.5V7h-3.5")
    val close = icon("close", "M6 6l8 8M14 6l-8 8")
    val plus = icon("plus", "M10 4.5v11M4.5 10h11")
    val more = icon("more", "M10 4.2v1.6M10 9.2v1.6M10 14.2v1.6")
    val lock = icon("lock", "M6.5 9h7a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M7 9V7a3 3 0 0 1 6 0v2")
    val search = icon("search", "M13.8 8.8a5 5 0 1 1-10 0 5 5 0 0 1 10 0z", "m12.6 12.6 3.9 3.9")
    val star = icon("star", "M10 3l2.1 4.3 4.7.6-3.4 3.3.8 4.7L10 13.7l-4.2 2.2.8-4.7-3.4-3.3 4.7-.6z")
    val sparkles = icon("sparkles", "M10 3l1.6 4.4L16 9l-4.4 1.6L10 15l-1.6-4.4L4 9l4.4-1.6z", "M15.5 13l.6 1.5 1.5.6-1.5.6-.6 1.5-.6-1.5-1.5-.6 1.5-.6z")
    val newChat = icon("new-chat", "M4 5.5A1.5 1.5 0 0 1 5.5 4h9A1.5 1.5 0 0 1 16 5.5v6a1.5 1.5 0 0 1-1.5 1.5H9.5L6 16v-3h-.5A1.5 1.5 0 0 1 4 11.5z", "M10 6.3v4.4M7.8 8.5h4.4")
    val external = icon("external", "M11.5 4H16v4.5M16 4l-7 7", "M14 11.5V16H4V6h4.5")
    val minimize = icon("minimize", "M5 10h10")
    val history = icon("history", "M3.6 10a6.4 6.4 0 1 0 1.9-4.5", "M3.5 3.8v3.3h3.3", "M10 6.6V10l2.5 1.6")
    val grip = icon("grip", "M14 14l2 2M10 14l6-6M6 14l10-10")
    val chevronUp = icon("up", "M5.5 12.5 10 8l4.5 4.5")
    val chevronDown = icon("down", "M5.5 8 10 12.5 14.5 8")
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = 0.dp))
}
