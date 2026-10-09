// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.github.sakkijarvenpolkka.audacity.editor.EditTool
import io.github.sakkijarvenpolkka.audacity.editor.EditorTags
import io.github.sakkijarvenpolkka.audacity.editor.SnapMode
import io.github.sakkijarvenpolkka.audacity.effects.BuiltinEffects
import io.github.sakkijarvenpolkka.audacity.ui.EDITOR_AREA_TAG
import io.github.sakkijarvenpolkka.audacity.ui.EDIT_BAR_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The main window with the editor's mobile edit bar on phone (portrait,
 * small portrait, landscape) and tablet sizes: the bar is at the bottom,
 * inside the window, and the tracks keep a usable height. Also the Effects
 * button → quick effects sheet, and the overflow's editor toggles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class MobileLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: AppViewModel get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun awaitProject() {
        compose.waitUntil(10_000) { vm.engine.snapshot.value.project.open && vm.editor != null }
        compose.waitForIdle()
    }

    /** Runs the main looper (with its clock) until [cond] holds. */
    private fun awaitMain(cond: () -> Boolean) {
        repeat(200) {
            if (cond()) return
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            compose.waitForIdle()
        }
        assertTrue("condition not met", cond())
    }

    /** Edit bar at the bottom, below the tracks; the tracks at least [minEditor] tall. */
    private fun assertEditBarLayout(minEditor: Dp) {
        awaitProject()
        val root = compose.onRoot().getBoundsInRoot()
        val bar = compose.onNodeWithTag(EDIT_BAR_ROW_TAG).getBoundsInRoot()
        val editor = compose.onNodeWithTag(EDITOR_AREA_TAG).getBoundsInRoot()
        compose.onNodeWithTag(EditorTags.MOBILE_EDIT_BAR).assertExists()
        assertTrue("bar $bar inside root $root", bar.bottom <= root.bottom && bar.top >= root.top)
        assertTrue("bar $bar below the tracks $editor", bar.top >= editor.bottom)
        assertTrue("bar $bar at least 56 dp", bar.bottom - bar.top >= 56.dp)
        assertTrue("tracks ${editor.bottom - editor.top} >= $minEditor", editor.bottom - editor.top >= minEditor)
    }

    @Test
    fun phonePortraitHasTheEditBarAtTheBottom() {
        assertEditBarLayout(minEditor = 380.dp)
        // Within ~100 dp of the bottom (only the bar's own height below the transport row)
        val root = compose.onRoot().getBoundsInRoot()
        val bar = compose.onNodeWithTag(EDIT_BAR_ROW_TAG).getBoundsInRoot()
        assertTrue(root.bottom - bar.bottom < 1.dp)
    }

    @Test
    @Config(qualifiers = "w360dp-h600dp-mdpi")
    fun smallPhoneMergesRowsToKeepTrackHeight() = assertEditBarLayout(minEditor = 260.dp)

    @Test
    @Config(qualifiers = "w800dp-h400dp-mdpi")
    fun phoneLandscapeSharesTheBottomRow() = assertEditBarLayout(minEditor = 220.dp)

    @Test
    @Config(qualifiers = "w1000dp-h700dp-mdpi")
    fun tabletHasTheEditBarBelowTheSelectionBar() {
        assertEditBarLayout(minEditor = 380.dp)
        compose.onNodeWithTag("menubar:View").assertExists()
    }

    @Test
    fun effectsButtonOpensTheQuickEffectsSheet() {
        awaitProject()
        // The last button of the bar: scrolled into view first on a narrow phone
        compose.onNode(hasContentDescription("Effects") and hasAnyAncestor(hasTestTag(EditorTags.MOBILE_EDIT_BAR)))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals(AppDialog.QuickEffects, vm.dialogs.lastOrNull())
        compose.onNodeWithTag("quick:fadeIn").assertExists()
        compose.onNodeWithTag("quick:changeTempo").assertExists()
        // The demo project has a selection (2 … 4 s): one tap applies Fade In and closes the sheet
        compose.onNodeWithTag("quick:fadeIn").performClick()
        // The app's fake engine simulates 700 ms of progress on the main looper: advance its clock
        awaitMain { vm.engine.snapshot.value.lastEffect != null && vm.dialogs.isEmpty() }
        assertEquals(BuiltinEffects.FADE_IN, BuiltinEffects.symbolOf(vm.engine.snapshot.value.lastEffect?.id ?: ""))
    }

    @Test
    fun overflowTogglesTheEditorSettings() {
        awaitProject()
        compose.onNodeWithTag("top:more").performClick()
        compose.onNodeWithTag("overflow:SplitTool").performClick()
        compose.waitForIdle()
        assertEquals(EditTool.SPLIT, vm.editor!!.tool)
        assertTrue(vm.uiPrefs.value.splitTool)
        compose.onNodeWithTag(EditorTags.SPLIT_TOOL_BANNER).assertExists()
        // The banner turns it off; the editor's change is saved in the preferences
        compose.onNodeWithTag(EditorTags.SPLIT_TOOL_BANNER).performClick()
        // (Under Robolectric the global snapshot's apply notifications may only be sent at
        // the next frame after an earlier test class: send them, as the app's UI thread does)
        compose.waitUntil(5_000) {
            Snapshot.sendApplyNotifications()
            !vm.uiPrefs.value.splitTool
        }
        assertEquals(EditTool.SELECT, vm.editor!!.tool)

        compose.onNodeWithTag("top:more").performClick()
        compose.onNodeWithTag("overflow:Snapping").performClick()
        compose.waitForIdle()
        assertEquals(SnapMode.OFF, vm.editor!!.snapping)
        assertFalse(vm.uiPrefs.value.snapEnabled)
        compose.onNodeWithTag("top:more").performClick()
        compose.onNodeWithTag("overflow:StopAtTrackEnd").performClick()
        compose.waitForIdle()
        assertFalse(vm.editor!!.stopAtTrackEnd)
        assertFalse(vm.uiPrefs.value.stopAtTrackEnd)
    }
}
