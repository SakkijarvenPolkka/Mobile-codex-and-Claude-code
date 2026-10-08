// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.editor.AudacityTheme
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.ui.DialogHost
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dialogs whose composition goes away, and long lists (review F7, F10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w500dp-h900dp-mdpi")
class DialogLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    /** effects.describe never answers (the engine thread is busy). */
    private class SlowDescribe(inner: FakeAudacityEngine) : AudacityEngine by inner {
        override suspend fun describeEffect(id: String): EffectDescription = awaitCancellation()
    }

    private val fake = FakeAudacityEngine(FakeConfig(demoProject = true, longOperationMillis = 0, autoTick = false))

    @After
    fun tearDown() = fake.dispose()

    private fun vmWith(engine: AudacityEngine): AppViewModel {
        val app = ApplicationProvider.getApplicationContext<AudacityApp>()
        app.engineOverride = engine
        return AppViewModel(app).also { runBlocking { it.engine.start() } }
    }

    @Test
    fun effectDialogStaysWhenItsCompositionIsTornDown() {
        val vm = vmWith(SlowDescribe(fake))
        var shown by mutableStateOf(true)
        compose.setContent { AudacityTheme { if (shown) DialogHost(vm) } }
        val id = runBlocking { fake.effects().effects.first { it.name == "Amplify" }.id }
        val d = AppDialog.Effect(id)
        compose.runOnIdle { vm.open(d) }
        compose.waitForIdle()
        // The Activity is recreated (font scale, language) while describe is pending
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertTrue("the effect dialog is still in the stack", d in vm.dialogs)
    }

    @Test
    fun labelEditorComposesOnlyTheVisibleRows() {
        val vm = vmWith(fake)
        val file = tmp.newFile("many.txt")
        file.writeText((0 until 1000).joinToString("\n") { i -> "$i.0\t$i.5\tLabel $i" })
        runBlocking { fake.importLabels(file.absolutePath) }
        compose.setContent { AudacityTheme { DialogHost(vm) } }
        compose.runOnIdle { vm.open(AppDialog.LabelEditor) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Label 0", substring = false)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Label 999")).fetchSemanticsNodes().isEmpty())
    }
}
