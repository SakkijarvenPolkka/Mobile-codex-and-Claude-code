// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.editor.AudacityTheme
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.ui.DialogHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dialogs rendered by the dialog host over the in-memory engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w500dp-h900dp-mdpi")
class DialogsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AudacityApp>()
        // Instant long operations: no simulated progress delays on the (paused) main looper.
        app.engineOverride = FakeAudacityEngine(FakeConfig(demoProject = true, longOperationMillis = 0, autoTick = false))
        vm = AppViewModel(app)
        runBlocking { vm.engine.start() }
        compose.setContent { AudacityTheme { DialogHost(vm) } }
    }

    private fun effectId(name: String) = runBlocking { vm.engine.effects().effects.first { it.name == name }.id }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun amplifyDialogShowsDbAndApplies() {
        runBlocking {
            vm.engine.selectAll()
            vm.engine.select(0.5, 1.0)
        }
        compose.runOnIdle { vm.open(AppDialog.Effect(effectId("Amplify"))) }
        waitForText("Amplification")
        compose.onNodeWithText("Allow clipping").assertExists()
        compose.onNodeWithText("Presets & settings").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { vm.dialogs.isEmpty() && vm.engine.snapshot.value.history.undo == "Amplify" }
    }

    @Test
    fun generatorDialogHasADurationField() {
        compose.runOnIdle { vm.open(AppDialog.Effect(effectId("Tone"))) }
        waitForText("Frequency")
        compose.onNodeWithText("Duration").assertExists()
        compose.onNodeWithText("Waveform").assertExists()
    }

    @Test
    fun noiseReductionShowsBothSteps() {
        compose.runOnIdle { vm.open(AppDialog.Effect(effectId("Noise Reduction"))) }
        waitForText("Get Noise Profile")
        compose.onNodeWithText("Step 1").assertExists()
        compose.onNodeWithText("Step 2").assertExists()
    }

    @Test
    fun filterCurveShowsTheCurveEditor() {
        compose.runOnIdle { vm.open(AppDialog.Effect(effectId("Filter Curve EQ"))) }
        waitForText("Flatten")
        compose.onNodeWithText("Linear frequency scale").assertExists()
    }

    @Test
    fun exportDialogRequestsADocument() {
        compose.runOnIdle { vm.open(AppDialog.Export()) }
        waitForText("Export Audio")
        waitForText("WAV")
        waitForText("Encoding")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(".wav", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("export:run").performClick()
        val request = runBlocking { withTimeout(5_000) { vm.requests.first() } } as HostRequest.CreateDocument
        val job = (request.purpose as CreatePurpose.ExportAudio).job
        assertTrue(request.suggestedName, request.suggestedName.endsWith(".wav"))
        assertEquals("audio/wav", request.mimeType)
        assertTrue(job.rate > 0 && job.channels in 1..2)
        assertEquals("project", job.range)
    }

    @Test
    fun plotSpectrumAndContrastRender() {
        runBlocking {
            vm.engine.selectAll()
            vm.engine.select(0.0, 2.0)
        }
        compose.runOnIdle { vm.open(AppDialog.PlotSpectrum) }
        waitForText("Frequency Analysis")
        waitForText("Tap the graph")
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { vm.open(AppDialog.Contrast) }
        waitForText("Contrast Analysis")
        compose.onNodeWithText("Measure").assertExists()
    }

    @Test
    fun historyAndTagsRender() {
        compose.runOnIdle { vm.open(AppDialog.History) }
        waitForText("Reclaimable Space")
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { vm.open(AppDialog.Tags) }
        waitForText("Artist Name")
        compose.onNodeWithText("Track Title").assertExists()
    }
}
