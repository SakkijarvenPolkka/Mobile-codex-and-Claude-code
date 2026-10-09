// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.ui.LayoutKind
import io.github.sakkijarvenpolkka.audacity.ui.layoutKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole app shell on the in-memory engine (builds without the native core use it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun phoneMenuSheetOpensMenusAndScreens() {
        compose.onNodeWithTag("top:menu").performClick()
        compose.onNodeWithTag("menutab:Edit").performClick()
        compose.onNodeWithTag("menu:Undo").assertExists()
        compose.onNodeWithTag("menu:Preferences").performClick()
        compose.onNodeWithText("Default Sample Rate").assertExists()
    }

    @Test
    fun menuRowsAreAtLeast48dp() {
        compose.onNodeWithTag("top:menu").performClick()
        compose.onNodeWithTag("menutab:Edit").performClick()
        compose.onNodeWithTag("menu:Undo").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("menu:Redo").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun disabledItemExplainsWhy() {
        compose.onNodeWithTag("top:menu").performClick()
        compose.onNodeWithTag("menutab:Edit").performClick()
        // Nothing was undone in the demo project: Redo is disabled and says why.
        compose.onNodeWithTag("menu:Redo").performClick()
        compose.onNodeWithText("There is nothing to redo.").assertExists()
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-mdpi")
    fun tabletHasAMenuBar() {
        compose.onNodeWithTag("menubar:File").performClick()
        compose.onNodeWithTag("menu:New").assertExists()
        compose.onNodeWithText("Ctrl+N").assertExists()
    }

    @Test
    fun breakpoints() {
        assertEquals(LayoutKind.PHONE_PORTRAIT, layoutKind(400f, 800f))
        assertEquals(LayoutKind.PHONE_LANDSCAPE, layoutKind(800f, 400f))
        assertEquals(LayoutKind.TABLET, layoutKind(1000f, 700f))
    }
}
