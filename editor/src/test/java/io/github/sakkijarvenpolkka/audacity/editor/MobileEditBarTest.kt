// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.SplitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Records the edit bar's engine calls and forwards them to the fake. */
private class BarEngine(val inner: FakeAudacityEngine) : AudacityEngine by inner {
    val calls = CopyOnWriteArrayList<String>()
    val splits = CopyOnWriteArrayList<Pair<Double, List<Long>?>>()
    val labels = CopyOnWriteArrayList<Pair<Double, Double?>>()

    override suspend fun splitAt(t: Double, trackIds: List<Long>?): SplitResult {
        calls += "splitAt"; splits += t to trackIds
        return inner.splitAt(t, trackIds)
    }
    override suspend fun edit(command: String) {
        calls += command; inner.edit(command)
    }
    override suspend fun addLabel(title: String, t0: Double, t1: Double?): Pair<Long, Int> {
        calls += "addLabel"; labels += t0 to t1
        return inner.addLabel(title, t0, t1)
    }
    override suspend fun addLabel(title: String): Pair<Long, Int> {
        calls += "addLabelAtSelection"
        return inner.addLabel(title)
    }
    override suspend fun undo() { calls += "undo"; inner.undo() }
    override suspend fun redo() { calls += "redo"; inner.redo() }
    override suspend fun stop() { calls += "stop"; inner.stop() }
}

private class BarCallbacks : EditorCallbacks {
    val messages = CopyOnWriteArrayList<String>()
    val events = CopyOnWriteArrayList<String>()
    override fun onTrackMenu(trackId: Long) {}
    override fun onContextMenu(target: ContextTarget) {}
    override fun onRecord(newTrack: Boolean) {}
    override fun onEditLabel(trackId: Long, index: Int) { events += "editLabel:$trackId:$index" }
    override fun onMessage(text: String) { messages += text }
    override fun onQuickEffects() { events += "quickEffects" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class MobileEditBarTest {
    @get:Rule
    val rule = createComposeRule()

    /** A frozen engine clock (far from System.nanoTime()): the play head stays where playback started. */
    private val clock = System.nanoTime() + 3_600_000_000_000L
    private val fake = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false, clock = { clock }))
    private val engine = BarEngine(fake)
    private val callbacks = BarCallbacks()
    private lateinit var state: EditorState

    @After
    fun tearDown() = fake.dispose()

    private val audio1: Long get() = engine.snapshot.value.tracks[0].id

    private fun setBar() {
        rule.setContent {
            AudacityTheme(ThemeChoice.LIGHT) {
                state = rememberEditorState()
                MobileEditBar(engine, state, callbacks, Modifier.fillMaxWidth())
            }
        }
        rule.waitForIdle()
    }

    private fun button(label: String) = rule.onNodeWithContentDescription(label)

    private fun click(label: String) {
        button(label).performScrollTo().performClick()
    }

    @Test
    fun everyButtonIsALargeTouchTargetWithALabel() {
        setBar()
        for (label in listOf("Split", "Undo", "Redo", "Cut", "Copy", "Paste", "Delete", "Trim", "Silence",
            "Duplicate", "Marker", "Effects")) {
            val node = button(label).fetchSemanticsNode()
            assertTrue("$label ${node.size}", node.size.width >= 48 && node.size.height >= 48)
        }
    }

    @Test
    fun splitSplitsAtTheCursor() {
        runBlocking { fake.select(3.0, 3.0, listOf(audio1), audio1) }
        setBar()
        click("Split")
        rule.waitUntil(5_000) { engine.splits.isNotEmpty() }
        assertEquals(3.0 to null, engine.splits.single())
        rule.waitUntil(5_000) { engine.snapshot.value.tracks[0].clips.size == 3 }
        assertTrue(engine.calls.none { it == "stop" })
    }

    @Test
    fun splitWithATimeSelectionSplitsAtBothEdges() {
        setBar()                                   // demo selection: 2…4 s on Audio 1
        click("Split")
        rule.waitUntil(5_000) { engine.calls.contains("edit.split") }
        rule.waitUntil(5_000) { engine.snapshot.value.tracks[0].clips.size == 4 }
    }

    @Test
    fun splitWhilePlayingStopsAndSplitsAtThePlayHead() {
        setBar()
        runBlocking { fake.play(t0 = 5.0) }
        click("Split")
        rule.waitUntil(5_000) { engine.splits.isNotEmpty() }
        assertEquals(listOf("stop", "splitAt"), engine.calls.toList())
        assertEquals(5.0, engine.splits.single().first, 1e-9)
        assertTrue(!engine.readTransport().isActive)
        rule.waitUntil(5_000) { engine.snapshot.value.tracks[0].clips.any { it.start == 5.0 } }
    }

    @Test
    fun splitWhereThereIsNoClipSaysSo() {
        runBlocking { fake.select(9.0, 9.0, listOf(audio1), audio1) }   // the gap between the clips
        setBar()
        click("Split")
        rule.waitUntil(5_000) { callbacks.messages.isNotEmpty() }
        assertEquals("Nothing to split here. Put the cursor inside a clip.", callbacks.messages.single())
    }

    @Test
    fun markerWhilePlayingAddsALabelAtThePlayHead() {
        setBar()
        runBlocking { fake.play(t0 = 6.25) }
        click("Marker")
        rule.waitUntil(5_000) { engine.labels.isNotEmpty() }
        assertEquals(6.25 to null, engine.labels.single())
        assertTrue(engine.readTransport().isActive)          // playback goes on
        assertTrue(callbacks.events.none { it.startsWith("editLabel") })
        val label = engine.snapshot.value.tracks.filter { it.isLabel }.flatMap { it.labels }.single { it.t0 == 6.25 }
        assertEquals(label.t0, label.t1, 0.0)
    }

    @Test
    fun markerWhenStoppedLabelsTheSelectionAndAsksForItsName() {
        setBar()
        click("Marker")
        rule.waitUntil(5_000) { callbacks.events.any { it.startsWith("editLabel") } }
        assertEquals(2.0 to 4.0, engine.labels.single())
        val (trackId, index) = callbacks.events.single().removePrefix("editLabel:").split(':').let { it[0].toLong() to it[1].toInt() }
        val label = engine.snapshot.value.track(trackId)!!.labels[index]
        assertEquals(2.0, label.t0, 0.0)
        assertEquals(4.0, label.t1, 0.0)
    }

    @Test
    fun disabledButtonsExplainWhyInsteadOfActing() {
        runBlocking { fake.select(3.0, 3.0, listOf(audio1), audio1) }   // no time selection
        setBar()
        val cut = button("Cut").fetchSemanticsNode()
        assertEquals("Unavailable: Select a part of the audio first", cut.config[SemanticsProperties.StateDescription])
        click("Cut")
        rule.waitUntil(5_000) { callbacks.messages.isNotEmpty() }
        assertEquals("Select a part of the audio first", callbacks.messages.last())
        click("Paste")
        rule.waitUntil(5_000) { callbacks.messages.size == 2 }
        assertEquals("The clipboard is empty", callbacks.messages.last())
        click("Redo")
        rule.waitUntil(5_000) { callbacks.messages.size == 3 }
        assertEquals("Nothing to redo", callbacks.messages.last())
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun enabledButtonsRunTheirCommands() {
        setBar()                                   // selection 2…4 s, Audio 1 selected
        click("Copy")
        rule.waitUntil(5_000) { engine.calls.contains("edit.copy") }
        click("Duplicate")
        rule.waitUntil(5_000) { engine.calls.contains("edit.duplicate") }
        click("Trim")
        rule.waitUntil(5_000) { engine.calls.contains("edit.trim") }
        click("Undo")
        rule.waitUntil(5_000) { engine.calls.contains("undo") }
        click("Redo")
        rule.waitUntil(5_000) { engine.calls.contains("redo") }
        click("Paste")
        rule.waitUntil(5_000) { engine.calls.contains("edit.paste") }
        click("Effects")
        rule.waitUntil(5_000) { callbacks.events.contains("quickEffects") }
        assertTrue(callbacks.messages.toString(), callbacks.messages.isEmpty())
    }

    @Test
    fun recordingDisablesSplitWithAReason() {
        setBar()
        runBlocking { fake.record(false) }
        rule.waitForIdle()
        click("Split")
        rule.waitUntil(5_000) { callbacks.messages.isNotEmpty() }
        assertEquals("Not available while recording", callbacks.messages.single())
        assertTrue(engine.calls.isEmpty())
        runBlocking { fake.stop() }
    }

    @Test
    fun longPressOnSplitTogglesTheRazorTool() {
        setBar()
        button("Split").assertIsNotSelected()
        button("Split").performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(EditTool.SPLIT, state.tool) }
        button("Split").assertIsSelected()
        button("Split").performTouchInput { longClick() }
        rule.runOnIdle { assertEquals(EditTool.SELECT, state.tool) }
        assertTrue(engine.calls.isEmpty())
    }
}
