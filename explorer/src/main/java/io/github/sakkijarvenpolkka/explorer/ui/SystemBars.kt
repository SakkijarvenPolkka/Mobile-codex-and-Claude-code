/*
 * Files — the system bars of an activity of the app, which it draws under (edge to edge), styled for the app theme.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice

/** The scrims `enableEdgeToEdge` puts behind three-button navigation by default. */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Styles the system bars of [activity], drawn under edge to edge, for the app [theme]: their icons follow it, which
 * may differ from the system's.
 */
@Composable
fun ThemedSystemBars(activity: ComponentActivity, theme: ThemeChoice) {
    val dark = isDarkTheme(theme)
    DisposableEffect(activity, dark) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
        onDispose {}
    }
}
