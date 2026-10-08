// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Records the editor's engine calls; model calls go to the fake engine,
 *  transport calls are only recorded (keeps the test clock idle). */
private class RecordingEngine(val inner: FakeAudacityEngine) : AudacityEngine by inner {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var lastSelect: Pair<Double, Double>? = null
    @Volatile var lastSelectTracks: List<Long>? = null
    @Volatile var lastMove: String? = null
    @Volatile var lastPlayT0: Double? = null
    @Volatile var waveCalls = 0

    override suspend fun select(t0: Double, t1: Double) {
        calls += "select"; lastSelect = t0 to t1; inner.select(t0, t1)
    }
    override suspend fun selectTracks(ids: List<Long>, mode: String) {
        calls += "selectTracks:$mode"; lastSelectTracks = ids; inner.selectTracks(ids, mode)
    }
    override suspend fun selectTrackHeader(id: Long, shift: Boolean, ctrl: Boolean) {
        calls += "selectTrackHeader:$id"; inner.selectTrackHeader(id, shift, ctrl)
    }
    override suspend fun selectClip(trackId: Long, clipIndex: Int, generation: Long) {
        calls += "selectClip:$trackId:$clipIndex"; inner.selectClip(trackId, clipIndex, generation)
    }
    override suspend fun moveClip(trackId: Long, clipIndex: Int, generation: Long, newStart: Double, toTrackId: Long?) {
        calls += "moveClip"; lastMove = "$trackId:$clipIndex:$newStart:$toTrackId"
        inner.moveClip(trackId, clipIndex, generation, newStart, toTrackId)
    }
    override suspend fun setTrackMute(id: Long, mute: Boolean) {
        calls += "mute:$id:$mute"; inner.setTrackMute(id, mute)
    }
    val gainCalls = CopyOnWriteArrayList<Boolean>()
    override suspend fun setTrackGain(id: Long, gain: Double, final: Boolean) {
        gainCalls += final; inner.setTrackGain(id, gain, final)
    }
    override suspend fun setPlayRegion(t0: Double, t1: Double, active: Boolean) {
        calls += "playRegion:$active"; inner.setPlayRegion(t0, t1, active)
    }
    override suspend fun play(loop: Boolean, t0: Double?, t1: Double?) {
        calls += "play"; lastPlayT0 = t0
    }
    override suspend fun skipToStart() {
        calls += "skipToStart"; inner.skipToStart()
    }
    override suspend fun waveColumnsInto(trackId: Long, channel: Int, zoomLevel: Int, firstColumn: Long, count: Int, out: FloatArray): Long {
        waveCalls++
        return inner.waveColumnsInto(trackId, channel, zoomLevel, firstColumn, count, out)
    }
}

private class RecordingCallbacks : EditorCallbacks {
    val events = CopyOnWriteArrayList<String>()
    val targets = CopyOnWriteArrayList<ContextTarget>()
    override fun onTrackMenu(trackId: Long) { events += "trackMenu:$trackId" }
    override fun onContextMenu(target: ContextTarget) { events += "context"; targets += target }
    override fun onRecord(newTrack: Boolean) { events += "record:$newTrack" }
    override fun onEditLabel(trackId: Long, index: Int) { events += "editLabel:$trackId:$index" }
    override fun onMessage(text: String) { events += "message:$text" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
class EditorScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val fake = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false))
    private val engine = RecordingEngine(fake)
    private val callbacks = RecordingCallbacks()
    private lateinit var state: EditorState

    @After
    fun tearDown() = fake.dispose()

    private val audio1: Long get() = engine.snapshot.value.tracks[0].id
    private val zoom = EditorState.DEFAULT_ZOOM

    private fun setEditor() {
        rule.setContent {
            AudacityTheme(ThemeChoice.LIGHT) {
                state = rememberEditorState()
                EditorScreen(engine, state, callbacks, Modifier.fillMaxSize())
            }
        }
        rule.waitForIdle()
    }

    // Compact layout geometry (dp = px at mdpi), relative to the track panel:
    // Audio 1: header [0,32), title bar [32,56), channel [56,144).
    private val audio1BodyY = 100f
    private val audio1TitleY = 44f

    @Test
    fun composesWithThreeTracksAndFetchesWaveforms() {
        setEditor()
        assertEquals(3, engine.snapshot.value.tracks.size)
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).assertIsDisplayed()
        rule.onNodeWithTag(EditorTags.RULER).assertIsDisplayed()
        rule.onNodeWithText("Audio 1").assertIsDisplayed()
        rule.onNodeWithText("Audio 2").assertIsDisplayed()
        rule.onNodeWithText("Label 1").assertIsDisplayed()
        rule.waitUntil(5_000) { engine.waveCalls > 0 }
        assertTrue(state.viewportWidthDp > 0f)
    }

    @Test
    fun tapSetsCursorAndSelectsOnlyThatTrack() {
        setEditor()
        val x = (2.0 * zoom).toFloat()
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput { click(Offset(x, audio1BodyY)) }
        rule.waitUntil(5_000) { engine.lastSelectTracks != null && engine.lastSelect != null }
        val (t0, t1) = engine.lastSelect!!
        assertEquals(2.0, t0, 0.02)
        assertEquals(t0, t1, 0.0)
        assertEquals(listOf(audio1), engine.lastSelectTracks)
        assertTrue(engine.calls.contains("selectTracks:set"))
    }

    @Test
    fun horizontalDragSelectsTime() {
        setEditor()
        val x0 = (1.0 * zoom).toFloat()
        val x1 = (3.5 * zoom).toFloat()
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput {
            swipe(Offset(x0, audio1BodyY), Offset(x1, audio1BodyY), durationMillis = 400)
        }
        rule.waitUntil(5_000) {
            val s = engine.lastSelect
            s != null && abs(s.second - 3.5) < 0.05
        }
        val (t0, t1) = engine.lastSelect!!
        assertEquals(1.0, t0, 0.02)
        assertEquals(3.5, t1, 0.05)
        rule.waitUntil(5_000) { abs(engine.snapshot.value.selection.t1 - 3.5) < 0.05 }
    }

    @Test
    fun longPressOnClipRequestsClipMenu() {
        setEditor()
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput { longClick(Offset(100f, audio1BodyY)) }
        rule.waitUntil(5_000) { callbacks.targets.isNotEmpty() }
        val target = callbacks.targets.first()
        assertTrue(target is ContextTarget.Clip)
        target as ContextTarget.Clip
        assertEquals(audio1, target.trackId)
        assertEquals(0, target.clipIndex)
        assertEquals(engine.snapshot.value.generation, target.generation)
    }

    @Test
    fun draggingClipTitleBarMovesClip() {
        setEditor()
        val dx = zoom.toFloat()                 // one second
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput {
            swipe(Offset(200f, audio1TitleY), Offset(200f + dx, audio1TitleY), durationMillis = 400)
        }
        rule.waitUntil(5_000) { engine.lastMove != null }
        val parts = engine.lastMove!!.split(':')
        assertEquals(audio1.toString(), parts[0])
        assertEquals("0", parts[1])
        assertEquals(1.0, parts[2].toDouble(), 0.03)
        assertEquals("null", parts[3])
        rule.waitUntil(5_000) { abs(engine.snapshot.value.tracks[0].clips[0].start - 1.0) < 0.03 }
    }

    @Test
    fun tapOnRulerStartsQuickPlay() {
        setEditor()
        val x = (3.0 * zoom).toFloat()
        rule.onNodeWithTag(EditorTags.RULER).performTouchInput { click(Offset(x, 10f)) }
        rule.waitUntil(5_000) { engine.lastPlayT0 != null }
        assertEquals(3.0, engine.lastPlayT0!!, 0.02)
    }

    @Test
    fun dragOnRulerCreatesLoopRegion() {
        setEditor()
        rule.onNodeWithTag(EditorTags.RULER).performTouchInput {
            swipe(Offset(50f, 10f), Offset(250f, 10f), durationMillis = 300)
        }
        rule.waitUntil(5_000) { engine.calls.contains("playRegion:true") }
        rule.waitUntil(5_000) { engine.snapshot.value.playRegion.active }
        val pr = engine.snapshot.value.playRegion
        assertEquals(50.0 / zoom, pr.t0, 0.03)
        assertEquals(250.0 / zoom, pr.t1, 0.03)
    }

    @Test
    fun muteButtonCallsEngine() {
        setEditor()
        rule.onAllNodesWithContentDescription("Mute")[0].performClick()
        rule.waitUntil(5_000) { engine.calls.contains("mute:$audio1:true") }
        rule.waitUntil(5_000) { engine.snapshot.value.tracks[0].mute }
    }

    @Test
    fun tapOnLabelSelectsItLongPressEditsIt() {
        setEditor()
        val labelTrack = engine.snapshot.value.tracks[2]
        // Label track: header [300,332), body [332,396); "Intro" spans 0..2 s.
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput { click(Offset(10f, 345f)) }
        rule.waitUntil(5_000) { engine.lastSelectTracks == listOf(labelTrack.id) }
        assertEquals(0.0 to 2.0, engine.lastSelect)
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput { longClick(Offset(10f, 345f)) }
        rule.waitUntil(5_000) { callbacks.events.contains("editLabel:${labelTrack.id}:0") }
    }

    @Test
    fun pinchZoomsAboutTheFocus() {
        setEditor()
        val before = state.pps
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput {
            pinch(Offset(150f, 100f), Offset(50f, 100f), Offset(250f, 100f), Offset(350f, 100f), durationMillis = 400)
        }
        rule.waitForIdle()
        assertTrue("pps ${state.pps}", state.pps > before * 2)
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-mdpi")
    fun gainSliderSendsFinalOnRelease() {
        setEditor()
        rule.onAllNodesWithContentDescription("Volume")[0].performTouchInput {
            swipe(Offset(centerX - 30f, centerY), Offset(centerX + 30f, centerY), durationMillis = 300)
        }
        rule.waitUntil(5_000) { engine.gainCalls.isNotEmpty() && engine.gainCalls.last() }
        assertTrue(engine.gainCalls.contains(false))
        assertEquals(1, engine.gainCalls.count { it })
        rule.waitUntil(5_000) { engine.snapshot.value.tracks[0].gain > 1.0 }
    }

    @Test
    fun trackMenuButtonUsesCallback() {
        setEditor()
        rule.onAllNodesWithContentDescription("Open menu…")[0].performClick()
        rule.waitUntil(5_000) { callbacks.events.contains("trackMenu:$audio1") }
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-mdpi")
    fun wideLayoutShowsTcpAndTapSelectsTrack() {
        setEditor()
        rule.onNodeWithText("Audio 2").assertIsDisplayed()
        // TCP column is 150 dp wide; Audio 2 starts at 150 + 6 = 156 dp.
        rule.onNodeWithTag(EditorTags.TRACK_PANEL).performTouchInput { click(Offset(75f, 156f + 135f)) }
        val audio2 = engine.snapshot.value.tracks[1].id
        rule.waitUntil(5_000) { engine.calls.contains("selectTrackHeader:$audio2") }
    }

    @Test
    fun toolbarsComposeAndAct() {
        rule.setContent {
            AudacityTheme(ThemeChoice.DARK) {
                state = rememberEditorState()
                Column(Modifier.fillMaxSize()) {
                    TransportToolbar(engine, callbacks, Modifier.fillMaxWidth())
                    EditToolbar(engine, state, Modifier.fillMaxWidth())
                    MeterToolbar(engine, Modifier.fillMaxWidth().height(60.dp))
                    TimeToolbar(engine)
                    SelectionToolbar(engine)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("00 h 00 m 02.000 s", substring = true).assertIsDisplayed()
        val before = state.pps
        rule.onNodeWithContentDescription("Zoom In").performClick()
        rule.waitForIdle()
        assertEquals(before * 2, state.pps, 1e-9)
        rule.onNodeWithContentDescription("Skip to Start").performClick()
        rule.waitUntil(5_000) { engine.calls.contains("skipToStart") }
        rule.onNodeWithContentDescription("Record").performClick()
        rule.waitUntil(5_000) { callbacks.events.contains("record:false") }
        assertNotNull(rule.onNodeWithContentDescription("Audio Position"))
    }

    @Test
    fun selectionToolbarEditsEnd() {
        rule.setContent {
            AudacityTheme(ThemeChoice.LIGHT) { SelectionToolbar(engine) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("00 h 00 m 04.000 s", substring = true).performClick()
        rule.onNodeWithContentDescription("Edit End").performTextReplacement("5.5")
        rule.onNodeWithContentDescription("Edit End").performImeAction()
        rule.waitUntil(5_000) { engine.lastSelect == (2.0 to 5.5) }
        rule.waitUntil(5_000) { engine.snapshot.value.selection.t1 == 5.5 }
        rule.onNodeWithText("00 h 00 m 05.500 s", substring = true).assertIsDisplayed()
    }

    @Test
    fun trackPanelOffersAccessibilityActions() {
        setEditor()
        val actions = rule.onNodeWithTag(EditorTags.TRACK_PANEL).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(
            listOf("Select all", "Select previous clip", "Select next clip", "Menu of the clip at the cursor"),
            actions.map { it.label },
        )
        rule.runOnIdle { actions.last().action() }
        rule.waitUntil(5_000) { callbacks.targets.isNotEmpty() }
        assertTrue(callbacks.targets.single() !is ContextTarget.Empty)
        rule.runOnIdle { actions.first().action() }
        rule.waitUntil(5_000) { engine.snapshot.value.tracks.all { it.selected } }
    }

    @Test
    fun clipAtTheCursorIsTheContextTarget() {
        val audio = engine.snapshot.value.tracks[0]
        val clip = audio.clips[0]
        val t = (clip.start + clip.end) / 2
        kotlinx.coroutines.runBlocking {
            engine.select(t, t, listOf(audio.id), focus = audio.id)
        }
        val s = engine.snapshot.value
        assertEquals(ContextTarget.Clip(audio.id, clip.index, s.generation), contextTargetAtCursor(s))
        kotlinx.coroutines.runBlocking { engine.select(clip.end + 1000.0, clip.end + 1000.0, listOf(audio.id), focus = audio.id) }
        assertEquals(ContextTarget.Track(audio.id), contextTargetAtCursor(engine.snapshot.value))
    }

    @Test
    fun compactMuteAndSoloHaveRowHighTouchAreas() {
        setEditor()
        val mute = rule.onAllNodesWithContentDescription("Mute")[0].fetchSemanticsNode().boundsInRoot
        val solo = rule.onAllNodesWithContentDescription("Solo")[0].fetchSemanticsNode().boundsInRoot
        assertEquals(32f, mute.height, 0.5f)
        assertEquals(40f, mute.width, 0.5f)
        // Adjacent: no dead gap between the two targets
        assertEquals(mute.right, solo.left, 0.5f)
    }
}
