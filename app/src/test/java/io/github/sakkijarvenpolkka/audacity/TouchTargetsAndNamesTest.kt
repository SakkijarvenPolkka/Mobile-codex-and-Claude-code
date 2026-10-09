// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.editor.AudacityTheme
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.ui.DialogHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * File names that fit in 255 bytes (review F11) and 48 dp touch targets in
 * app dialogs (F13). The AlertDialog text inputs are tested without
 * composing them (FileFlowsTest): under Robolectric, a dialog of platform
 * default width that contains a text field never becomes idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w500dp-h900dp-mdpi")
class TouchTargetsAndNamesTest {
    @get:Rule
    val compose = createComposeRule()

    private val fake = FakeAudacityEngine(FakeConfig(demoProject = true, longOperationMillis = 0, autoTick = false))
    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AudacityApp>()
        app.engineOverride = fake
        vm = AppViewModel(app)
        runBlocking { vm.engine.start() }
        compose.setContent { AudacityTheme { DialogHost(vm) } }
    }

    @After
    fun tearDown() = fake.dispose()

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anExportFileNameMustFit() {
        compose.runOnIdle { vm.open(AppDialog.Export()) }
        waitFor(hasText(".wav", substring = true))
        compose.onNodeWithTag("export:fileName").performTextReplacement("노래".repeat(40) + ".wav")
        compose.onNodeWithText("The name is too long.").assertExists()
        compose.onNodeWithTag("export:run").assertIsNotEnabled()
        compose.onNodeWithTag("export:fileName").performTextReplacement("노래".repeat(33) + ".wav")   // 202 bytes: a long project name fits
        compose.onNodeWithText("The name is too long.").assertDoesNotExist()
        compose.onNodeWithTag("export:run").assertIsEnabled()
    }

    @Test
    fun historyRowsAreAtLeast48dp() {
        runBlocking { fake.addLabel("first") }
        compose.runOnIdle { vm.open(AppDialog.History) }
        waitFor(tagPrefix("hist:"))
        compose.onAllNodes(tagPrefix("hist:")).onFirst().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun labelEditorRowsAreAtLeast48dpAndSayWhatTheyDo() {
        runBlocking { fake.addLabel("first") }
        compose.runOnIdle { vm.open(AppDialog.LabelEditor) }
        waitFor(tagPrefix("le:times:"))
        val row = compose.onAllNodes(tagPrefix("le:times:")).onFirst()
        row.assertHeightIsAtLeast(48.dp)
        // Not clicked: under Robolectric a platform-width dialog with a text field (the
        // time input it opens) never becomes idle
        assertEquals("Edit start and end time", row.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }
}
