// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import io.github.sakkijarvenpolkka.audacity.menu.ContextMenus
import io.github.sakkijarvenpolkka.audacity.menu.MenuHost
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuNode
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.menu.allItems
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Records the engine calls the menus make (on top of the in-memory fake engine). */
class RecordingEngine(private val inner: AudacityEngine) : AudacityEngine by inner {
    val calls = mutableListOf<String>()
    override suspend fun edit(command: String) { calls += command; inner.edit(command) }
    override suspend fun selectCommand(command: String) { calls += command; inner.selectCommand(command) }
    override suspend fun alignTracks(mode: String) { calls += "tracks.align:$mode"; inner.alignTracks(mode) }
    override suspend fun trackChannelCommand(command: String, id: Long) { calls += "$command:$id"; inner.trackChannelCommand(command, id) }
    override suspend fun mixAndRender(toNewTrack: Boolean) { calls += "tracks.mixAndRender:$toNewTrack"; inner.mixAndRender(toNewTrack) }
    override suspend fun play(loop: Boolean, t0: Double?, t1: Double?) { calls += "transport.play"; inner.play(loop, t0, t1) }
    override suspend fun stop() { calls += "transport.stop"; inner.stop() }
    override suspend fun pause() { calls += "transport.pause"; inner.pause() }
    override suspend fun skipToStart() { calls += "transport.skipToStart"; inner.skipToStart() }
    override suspend fun skipToEnd() { calls += "transport.skipToEnd"; inner.skipToEnd() }
    override suspend fun repeatLastEffect() = inner.repeatLastEffect().also { calls += "effects.repeatLast" }
}

/** A MenuHost that records dialogs/screens/requests and answers input dialogs. */
class TestHost(override val engine: AudacityEngine) : MenuHost {
    override val editor = EditorState()
    override val uiPrefs = UiPrefs(null)
    override var editorHeightDp = 632f
    override var storedSelection: TimeRange? = null
    override var storedCursor: Double? = null

    val dialogs = mutableListOf<AppDialog>()
    val screens = mutableListOf<AppScreen>()
    val requests = mutableListOf<HostRequest>()
    val messages = mutableListOf<UiText>()
    val effectsRun = mutableListOf<String>()
    val records = mutableListOf<Boolean>()
    val calls = mutableListOf<String>()
    var textAnswer: String? = "Intro"
    var timeAnswer: Double? = 1.5

    override fun open(dialog: AppDialog) {
        dialogs += dialog
        when (dialog) {
            is AppDialog.TextInput -> dialog.result.complete(textAnswer)
            is AppDialog.TimeInput -> dialog.result.complete(timeAnswer)
            is AppDialog.Confirm -> dialog.result.complete(true)
            else -> Unit
        }
    }
    override fun show(screen: AppScreen) { screens += screen }
    override fun request(request: HostRequest) { requests += request }
    override fun message(text: UiText) { messages += text }
    override suspend fun newProject() { calls += "newProject"; engine.newProject() }
    override suspend fun saveProject() { calls += "saveProject" }
    override suspend fun saveProjectAs() { calls += "saveProjectAs" }
    override suspend fun closeProject() { calls += "closeProject" }
    override fun record(newTrack: Boolean) { records += newTrack }
    override suspend fun runEffect(effectId: String) { effectsRun += effectId }
    override suspend fun updateSettings(partial: Settings) { engine.setSettings(partial) }
    override fun clipboardText(): String = "Pasted text"
    override suspend fun openProjectFile(path: String) { calls += "open:$path" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenuActionsTest {
    private lateinit var fake: FakeAudacityEngine
    private lateinit var engine: RecordingEngine
    private lateinit var host: TestHost

    @Before
    fun setUp() {
        fake = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false))
        engine = RecordingEngine(fake)
        host = TestHost(engine)
    }

    private val snap: Snapshot get() = engine.snapshot.value
    private fun state() = MenuState(snap)

    private fun run(id: String) = runBlocking {
        val item = MenuSpec.find(id, MenuState(snap, effects = runCatching { fake.effects() }.getOrNull()))
        assertNotNull("menu item $id", item)
        item!!.action(host)
    }

    private fun runNode(nodes: List<MenuNode>, id: String) = runBlocking {
        val item = nodes.allItems(state()).firstOrNull { it.id == id }
        assertNotNull("context item $id", item)
        item!!.action(host)
    }

    private fun selectRange(t0: Double, t1: Double) = runBlocking {
        engine.selectAll()
        engine.select(t0, t1)
    }

    @Test
    fun demoProjectIsOpen() {
        assertTrue(snap.project.open)
        assertTrue(snap.tracks.count { it.isWave } >= 2)
    }

    @Test
    fun editMenuMapsToEditCommands() {
        val expected = listOf(
            "Copy" to "edit.copy", "Cut" to "edit.cut", "Paste" to "edit.paste", "Delete" to "edit.delete",
            "Duplicate" to "edit.duplicate", "SplitCut" to "edit.splitCut", "SplitDelete" to "edit.splitDelete",
            "Silence" to "edit.silence", "Trim" to "edit.trim", "Split" to "edit.split", "SplitNew" to "edit.splitNew",
            "Join" to "edit.join", "Disjoin" to "edit.detachAtSilences", "DeleteKey" to "edit.delete",
        )
        for ((id, command) in expected) {
            selectRange(0.5, 1.0)
            engine.calls.clear()
            runCatching { run(id) }
            assertEquals(id, listOf(command), engine.calls)
        }
    }

    @Test
    fun cutThenUndoRestoresTheProject() {
        selectRange(0.5, 1.5)
        val before = snap.tracks.first { it.isWave }.end
        run("Cut")
        assertTrue(snap.history.canUndo)
        assertTrue(snap.tracks.first { it.isWave }.end < before)
        run("Undo")
        assertEquals(before, snap.tracks.first { it.isWave }.end, 1e-9)
        run("Redo")
        assertTrue(snap.tracks.first { it.isWave }.end < before)
    }

    @Test
    fun selectionCommandsMapToSelectCommands() {
        val expected = listOf(
            "SelAllTracks" to "select.allTracks", "SelTrackStartToCursor" to "select.startToCursor",
            "SelCursorToTrackEnd" to "select.cursorToEnd", "SelTrackStartToEnd" to "select.trackStartToEnd",
            "CursTrackStart" to "select.cursorToTrackStart", "CursTrackEnd" to "select.cursorToTrackEnd",
            "CursPrevClipBoundary" to "select.prevClipBoundary", "CursNextClipBoundary" to "select.nextClipBoundary",
        )
        for ((id, command) in expected) {
            selectRange(1.0, 2.0)
            engine.calls.clear()
            run(id)
            assertEquals(id, listOf(command), engine.calls)
        }
        run("SelectNone")
        assertTrue(snap.tracks.none { it.selected })
        run("SelectAll")
        assertTrue(snap.tracks.all { it.selected })
    }

    @Test
    fun storeAndRetrieveSelection() {
        selectRange(1.0, 2.0)
        run("SelSave")
        selectRange(3.0, 4.0)
        run("SelRestore")
        assertEquals(TimeRange(1.0, 2.0), snap.selection)
    }

    @Test
    fun setLeftBoundaryAsksForATimeWhenStopped() {
        selectRange(1.0, 3.0)
        host.timeAnswer = 0.25
        run("SetLeftSelection")
        assertTrue(host.dialogs.last() is AppDialog.TimeInput)
        assertEquals(TimeRange(0.25, 3.0), snap.selection)
    }

    @Test
    fun tracksMenu() {
        val n = snap.tracks.size
        run("NewMonoTrack")
        assertEquals(n + 1, snap.tracks.size)
        assertEquals(1, snap.tracks.last().channels)
        run("NewStereoTrack")
        assertEquals(2, snap.tracks.last().channels)
        run("NewLabelTrack")
        assertTrue(snap.tracks.last().isLabel)
        run("Undo")
        assertEquals(n + 2, snap.tracks.size)

        runBlocking { engine.selectTracks(listOf(snap.tracks.last().id), "set") }
        run("RemoveTracks")
        assertEquals(n + 1, snap.tracks.size)

        run("MuteAllTracks")
        assertTrue(snap.tracks.filter { it.isWave }.all { it.mute })
        run("UnmuteAllTracks")
        assertTrue(snap.tracks.filter { it.isWave }.none { it.mute })

        selectRange(0.0, 1.0)
        run("PanLeft")
        assertTrue(snap.tracks.filter { it.isWave && it.selected }.all { it.pan == -1.0 })

        engine.calls.clear()
        run("Align_StartToSelStart")
        run("Align_EndToSelEnd")
        run("MixAndRenderToNewTrack")
        assertEquals(listOf("tracks.align:startToCursor", "tracks.align:endToSelEnd", "tracks.mixAndRender:true"), engine.calls)

        run("Resample")
        assertTrue(host.dialogs.last() is AppDialog.Resample)
    }

    @Test
    fun dialogsAndScreens() {
        val dialogs = listOf(
            "Export" to AppDialog.Export::class, "EditMetaData" to AppDialog.Tags::class, "UndoHistory" to AppDialog.History::class,
            "PlotSpectrum" to AppDialog.PlotSpectrum::class, "ContrastAnalyser" to AppDialog.Contrast::class,
            "EditLabels" to AppDialog.LabelEditor::class, "DeviceInfo" to AppDialog.DeviceInfo::class,
        )
        for ((id, cls) in dialogs) {
            run(id)
            assertTrue("$id opened ${host.dialogs.lastOrNull()}", cls.isInstance(host.dialogs.last()))
        }
        run("Preferences"); run("About"); run("Log"); run("Projects")
        assertEquals(listOf(AppScreen.PREFERENCES, AppScreen.ABOUT, AppScreen.LOG, AppScreen.PROJECTS), host.screens)
        run("New"); run("Save"); run("SaveAs"); run("Close")
        assertEquals(listOf("newProject", "saveProject", "saveProjectAs", "closeProject"), host.calls)
    }

    @Test
    fun fileRequests() {
        run("ImportAudio")
        val imp = host.requests.last() as HostRequest.OpenDocuments
        assertEquals(OpenPurpose.IMPORT_AUDIO, imp.purpose)
        assertTrue(imp.multiple)
        run("Open")
        assertEquals(OpenPurpose.OPEN, (host.requests.last() as HostRequest.OpenDocuments).purpose)
        run("SaveCopy")
        val backup = host.requests.last() as HostRequest.CreateDocument
        assertEquals(CreatePurpose.BackupProject, backup.purpose)
        assertTrue(backup.suggestedName.endsWith(".aup3"))
        if (snap.tracks.any { it.isLabel && it.labels.isNotEmpty() }) {
            run("ExportLabels")
            val labels = host.requests.last() as HostRequest.CreateDocument
            val text = (labels.purpose as CreatePurpose.ExportLabels).text
            assertTrue(text, text.lines().first().matches(Regex("\\d+\\.\\d{6}\t\\d+\\.\\d{6}\t.*")))
        }
        run("Manual")
        assertTrue((host.requests.last() as HostRequest.OpenUrl).url.startsWith("https://manual.audacityteam.org"))
    }

    @Test
    fun labelsAskForTheirText() {
        selectRange(1.0, 2.0)
        host.textAnswer = "Chorus"
        run("AddLabel")
        assertTrue(snap.tracks.any { t -> t.isLabel && t.labels.any { it.title == "Chorus" && it.t0 == 1.0 && it.t1 == 2.0 } })
        run("PasteNewLabel")
        assertTrue(snap.tracks.any { t -> t.isLabel && t.labels.any { it.title == "Pasted text" } })
        host.textAnswer = null
        val count = snap.tracks.sumOf { it.labels.size }
        run("AddLabel")
        assertEquals("cancelled label input adds nothing", count, snap.tracks.sumOf { it.labels.size })
    }

    @Test
    fun viewMenuDrivesTheEditorState() {
        val e = host.editor
        val pps = e.pps
        run("ZoomIn")
        assertEquals(pps * 2, e.pps, 1e-9)
        run("ZoomNormal")
        assertEquals(44100.0 / 512.0, e.pps, 1e-9)
        run("ZoomOut")
        assertEquals(44100.0 / 1024.0, e.pps, 1e-9)
        run("CollapseAllTracks")
        assertTrue(snap.tracks.all { e.isCollapsed(it.id) })
        run("ExpandAllTracks")
        assertTrue(snap.tracks.none { e.isCollapsed(it.id) })
        run("FitV")
        val h = (632f - 32f) / snap.tracks.size
        assertTrue(snap.tracks.all { (e.trackHeightDp(it.id) ?: 0f) == h.coerceAtLeast(48f) })
        assertFalse(host.uiPrefs.value.showClipping)
        run("ShowClipping")
        assertTrue(host.uiPrefs.value.showClipping)
    }

    @Test
    fun transportAndRecording() {
        engine.calls.clear()
        run("DefaultPlayStop")
        assertEquals(listOf("transport.play"), engine.calls)
        assertTrue(engine.readTransport().isActive)
        run("DefaultPlayStop")
        assertEquals(listOf("transport.play", "transport.stop"), engine.calls)
        run("CursProjectStart")
        run("CursProjectEnd")
        run("Record1stChoice")
        run("Record2ndChoice")
        assertEquals(listOf(false, true), host.records)

        selectRange(1.0, 2.0)
        run("SetPlayRegionToSelection")
        assertTrue(snap.playRegion.active)
        assertEquals(1.0, snap.playRegion.t0, 1e-9)
        run("TogglePlayRegion")
        assertFalse(snap.playRegion.active)

        val overdub = runBlocking { fake.getSettings().overdub ?: true }
        run("Overdub")
        assertEquals(!overdub, runBlocking { fake.getSettings().overdub })
    }

    @Test
    fun effectItemsRunTheirEffect() {
        val list = runBlocking { fake.effects() }
        val st = MenuState(snap, effects = list)
        val amplify = list.effects.first { it.name == "Amplify" }
        val item = MenuSpec.allItems(st).first { it.id == amplify.id }
        assertEquals("Amplify...", (item.label as UiText.Raw).text)
        runBlocking { item.action(host) }
        assertEquals(listOf(amplify.id), host.effectsRun)
    }

    @Test
    fun trackContextMenu() {
        val track = snap.tracks.first { it.isWave }
        var spectro = false
        val nodes = ContextMenus.trackMenu(track.id, snap, { spectro }, { _, on -> spectro = on })
        host.textAnswer = "Lead"
        runNode(nodes, "TrackName")
        assertEquals("Lead", snap.track(track.id)?.name)
        runNode(nodes, "Rate:48000")
        assertEquals(48000.0, snap.track(track.id)!!.rate, 0.0)
        runNode(nodes, "Format:int16")
        assertEquals("int16", snap.track(track.id)!!.format)
        runNode(nodes, "Spectrogram")
        assertTrue(spectro)
        val ids = nodes.allItems(state()).map { it.id }
        assertTrue(ids.contains("TrackMoveDown"))
        assertFalse("first track cannot move up", ids.contains("TrackMoveUp"))
        runNode(nodes, "TrackMoveDown")
        assertEquals(track.id, snap.tracks[1].id)
    }

    @Test
    fun disabledItemsAreDetectedFromSnapshotFlags() {
        // Busy engine: everything that needs AudioIONotBusy is disabled.
        runBlocking { engine.play() }
        val st = MenuState(snap)
        val cut: MenuItem = MenuSpec.find("Cut", st)!!
        assertFalse(cut.enabled(st.flags))
        assertTrue(MenuSpec.find("Pause", st)!!.enabled(st.flags))
        runBlocking { engine.stop() }
    }
}
