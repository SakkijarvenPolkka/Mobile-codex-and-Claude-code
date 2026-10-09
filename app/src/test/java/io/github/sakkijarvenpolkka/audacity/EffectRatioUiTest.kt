// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.editor.AudacityTheme
import io.github.sakkijarvenpolkka.audacity.effects.BuiltinEffects
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.ui.DialogHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The multiplier editor of Change Tempo / Pitch / Speed in the effect dialog (user request 4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
class EffectRatioUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val fake = FakeAudacityEngine(FakeConfig(demoProject = true, longOperationMillis = 0, autoTick = false))
    private val engine = MobileEngine(fake)
    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<AudacityApp>()
        app.engineOverride = engine
        vm = AppViewModel(app)
        runBlocking {
            vm.engine.start()
            vm.reloadEffects()
        }
        compose.setContent { AudacityTheme { DialogHost(vm) } }
    }

    @After
    fun tearDown() = fake.dispose()

    private fun open(symbol: String) {
        val id = BuiltinEffects.find(vm.effectList.value, symbol)!!.id
        compose.runOnIdle { vm.open(AppDialog.Effect(id)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("ratio:Percentage")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun apply(): Double {
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { engine.applied.isNotEmpty() }
        return engine.applied.last().second!!["Percentage"]!!.jsonPrimitive.double
    }

    @Test
    fun tempoChipAndStepperSendExactPercent() {
        open(BuiltinEffects.CHANGE_TEMPO)
        compose.onNodeWithTag("ratio:Percentage:field").assertTextContains("1.0")
        // Named after what it multiplies, not "Percent change"
        compose.onNodeWithText("Tempo multiplier").assertExists()
        compose.onNodeWithTag("ratio:Percentage:chip:1.25").performScrollTo().performClick()
        compose.onNodeWithTag("ratio:Percentage:field").assertTextContains("1.25")
        compose.onNodeWithText("+25% change").assertExists()
        // The demo selection is 2 s long: ×1.25 makes it 1.6 s
        compose.onNodeWithTag("ratio:Percentage:hint").assertTextContains("New length: 00:00:01.600")
        compose.onNodeWithTag("ratio:Percentage:up").performClick()
        compose.onNodeWithTag("ratio:Percentage:field").assertTextContains("1.3")
        compose.onNodeWithTag("ratio:Percentage:down").performClick()
        compose.onNodeWithTag("ratio:Percentage:down").performClick()
        compose.onNodeWithTag("ratio:Percentage:field").assertTextContains("1.2")
        assertEquals(20.0, apply(), 0.0)
    }

    @Test
    fun typedMultiplierWithCommaIsSentAsPercent() {
        open(BuiltinEffects.CHANGE_TEMPO)
        compose.onNodeWithTag("ratio:Percentage:field").performTextReplacement("1,35")
        assertEquals(35.0, apply(), 0.0)
    }

    @Test
    fun pitchShowsSemitones() {
        open(BuiltinEffects.CHANGE_PITCH)
        compose.onNodeWithTag("ratio:Percentage:chip:0.65").performScrollTo().performClick()
        compose.onNodeWithTag("ratio:Percentage:hint").assertTextContains("≈ -7.46 semitones")
        assertEquals(-35.0, apply(), 0.0)
    }
}
