// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system bars of an activity follow the app theme, which may differ from the system's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Whether the status bar and the navigation bar have dark icons, as on a light background. */
    private fun lightBars(): Pair<Boolean, Boolean> {
        val window = rule.activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        return bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
    }

    @Test
    fun barsFollowTheAppTheme() {
        var theme by mutableStateOf(ThemeChoice.LIGHT)
        rule.setContent { ThemedSystemBars(rule.activity, theme) }
        rule.waitForIdle()
        assertEquals(true to true, lightBars())

        theme = ThemeChoice.DARK
        rule.waitForIdle()
        assertEquals(false to false, lightBars())

        theme = ThemeChoice.LIGHT
        rule.waitForIdle()
        assertEquals(true to true, lightBars())
    }
}
