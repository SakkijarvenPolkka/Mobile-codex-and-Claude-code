// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.ImportResult
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Records the project/file commands of the app flows (the rest goes to the fake engine). */
private class FlowEngine(val inner: FakeAudacityEngine) : AudacityEngine by inner {
    val calls = CopyOnWriteArrayList<String>()
    override suspend fun importFiles(paths: List<String>, newProject: Boolean): ImportResult {
        calls += "import:$newProject:${paths.size}"
        return ImportResult(emptyList(), emptyList())
    }
    override suspend fun recoverProject(path: String) {
        calls += "recover:$path"
        inner.recoverProject(path)
    }
    override suspend fun export(path: String, formatKey: String, range: String, channels: Int, rate: Int, skipSilenceAtStart: Boolean): String {
        calls += "export"
        return inner.export(path, formatKey, range, channels, rate, skipSilenceAtStart)
    }
    override suspend fun exportLabels(path: String, format: String): Int {
        calls += "exportLabels"
        return inner.exportLabels(path, format)
    }
    override suspend fun setRecordPermission(granted: Boolean) {
        calls += "permission:$granted"
        inner.setRecordPermission(granted)
    }
    override suspend fun record(newTrack: Boolean) {
        calls += "record:$newTrack"
    }
}

/** Crash recovery, intents and activity results of the app view model (review F1-F4, F8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileFlowsTest {
    private val app: AudacityApp = ApplicationProvider.getApplicationContext()
    private val recoverable = listOf(
        ProjectFileEntry("/data/user/0/x/files/SessionData/New Project 1.aup3", "New Project 1", 1_700_000_000_000, 1 shl 20),
    )
    private lateinit var fake: FakeAudacityEngine
    private lateinit var engine: FlowEngine
    private val messages = CopyOnWriteArrayList<String>()
    private val collectors = CoroutineScope(Dispatchers.Unconfined)

    private fun setUpEngine(config: FakeConfig) {
        fake = FakeAudacityEngine(config)
        engine = FlowEngine(fake)
        app.engineOverride = engine
    }

    @Before
    fun setUp() = setUpEngine(FakeConfig(demoProject = true, autoTick = false, recoverable = recoverable))

    @After
    fun tearDown() {
        collectors.cancel()
        fake.dispose()
        SafFiles.freeSpace = { it.usableSpace }
    }

    private fun newVm(saved: SavedStateHandle = SavedStateHandle()): AppViewModel =
        AppViewModel(app, saved).also { vm -> collectors.launch { vm.messages.collect { messages += it } } }

    private fun idleUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun AppViewModel.top(): AppDialog? = dialogs.lastOrNull()

    private fun AppViewModel.awaitRecovery(): AppDialog.Recovery {
        idleUntil("recovery dialog") { dialogs.any { it is AppDialog.Recovery } }
        return dialogs.filterIsInstance<AppDialog.Recovery>().single()
    }

    private fun answer(vm: AppViewModel, choice: SaveChoice) {
        idleUntil("save prompt") { vm.top() is AppDialog.SaveChanges }
        val d = vm.top() as AppDialog.SaveChanges
        d.result.complete(choice)
        vm.dismiss(d)
    }

    @Test
    fun recoverAsksToSaveTheOpenProjectFirst() {
        val vm = newVm()
        vm.skipRecovery(vm.awaitRecovery())
        idleUntil("new project") { engine.snapshot.value.project.open && vm.dialogs.isEmpty() }
        runBlocking { engine.addLabel("unsaved work") }
        assertTrue(engine.snapshot.value.project.dirty)
        val name = engine.snapshot.value.project.name

        // Projects ▸ Recover: replacing the dirty project needs an answer first
        vm.showRecovery()
        val d = vm.awaitRecovery()
        vm.recover(d, d.projects.map { it.path })
        idleUntil("save prompt") { vm.top() is AppDialog.SaveChanges }
        assertTrue(vm.recoveryBusy)
        // A second tap while the prompt is up does nothing
        vm.recover(d, d.projects.map { it.path })
        assertEquals(1, vm.dialogs.count { it is AppDialog.SaveChanges })
        answer(vm, SaveChoice.CANCEL)
        idleUntil("busy cleared") { !vm.recoveryBusy }
        assertFalse(engine.calls.any { it.startsWith("recover:") })
        assertTrue("the recovery dialog stays", d in vm.dialogs)
        assertEquals(name, engine.snapshot.value.project.name)
        assertTrue(engine.snapshot.value.project.dirty)

        // Discard: now it is recovered
        vm.recover(d, d.projects.map { it.path })
        answer(vm, SaveChoice.DISCARD)
        idleUntil("recovered") { engine.calls.contains("recover:${recoverable[0].path}") && vm.dialogs.isEmpty() }
        assertEquals("New Project 1", engine.snapshot.value.project.name)
    }

    @Test
    fun recoveryIsOfferedOncePerEngine() {
        val first = newVm()
        first.awaitRecovery()
        // Not answered yet (e.g. the first Activity went away with the dialog up): offered again
        val second = newVm()
        val d = second.awaitRecovery()
        second.skipRecovery(d)
        idleUntil("skipped") { second.dialogs.isEmpty() && engine.snapshot.value.project.open }
        runBlocking { engine.closeProject() }
        // A new Activity/view model in the same process (stale Ready(recoverable) count): no prompt
        val third = newVm()
        idleUntil("third ready") { engine.snapshot.value.project.open }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(third.dialogs.none { it is AppDialog.Recovery })
    }

    @Test
    fun anIntentWaitsUntilTheRecoveryPromptIsAnswered() {
        val uri = Uri.parse("content://test.provider/audio/shared.wav")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(4096)))
        val vm = newVm()
        vm.handleIntent(Intent(Intent.ACTION_VIEW, uri))
        val d = vm.awaitRecovery()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("nothing imported behind the recovery dialog", engine.calls.none { it.startsWith("import") })
        vm.skipRecovery(d)
        // Into the empty project opened by Skip
        idleUntil("import") { engine.calls.contains("import:false:1") }
        idleUntil("staging removed") { File(app.cacheDir, "import").listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun fileUrisFromIntentsAreRefused() {
        setUpEngine(FakeConfig(demoProject = false, autoTick = false))
        val vm = newVm()
        idleUntil("ready") { engine.snapshot.value.project.open }
        vm.handleIntent(Intent(Intent.ACTION_VIEW, Uri.parse("file:///dev/zero")).setType("audio/wav"))
        vm.handleIntent(Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///dev/zero")))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, vm.localProgress.value)
        assertTrue(engine.calls.none { it.startsWith("import") })
    }

    @Test
    fun anEndlessSharedStreamStopsBeforeTheStorageIsFull() {
        setUpEngine(FakeConfig(demoProject = false, autoTick = false))
        val uri = Uri.parse("content://test.provider/audio/endless.wav")
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        shadowOf(app.contentResolver).registerInputStream(uri, endless)
        val vm = newVm()
        idleUntil("ready") { engine.snapshot.value.project.open }
        // 2 MiB above the reserve: the copy stops there instead of filling the storage
        SafFiles.freeSpace = { SafFiles.RESERVED_BYTES + (2L shl 20) }
        vm.handleIntent(Intent(Intent.ACTION_VIEW, uri))
        idleUntil("refused") { messages.any { it.contains("Not enough free storage") } }
        assertTrue(engine.calls.none { it.startsWith("import") })
        idleUntil("staging removed") { File(app.cacheDir, "import").listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun aCreateDocumentResultFromAKilledProcessWritesNothing() {
        setUpEngine(FakeConfig(demoProject = true, autoTick = false))
        val saved = SavedStateHandle()
        val before = newVm(saved)
        idleUntil("ready") { engine.snapshot.value.project.open }
        before.beginCreateDocument(CreatePurpose.ExportLabels("text", "labels.txt"))
        before.beginRecordPermission(newTrack = true)
        // The process dies with the picker on top; the result goes to a new process
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val after = newVm(restored)
        val uri = Uri.parse("content://test.provider/document/labels.txt")
        after.onDocumentCreated(uri)
        idleUntil("message") { messages.any { it.contains("nothing was saved") } }
        assertTrue(engine.calls.none { it.startsWith("export") })
        // The permission is applied once the engine is ready, but nothing records
        after.onRecordPermissionResult(true)
        idleUntil("permission") { engine.calls.contains("permission:true") }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(engine.calls.none { it.startsWith("record") })
    }

    @Test
    fun aCreateDocumentResultOfThisProcessIsWritten() {
        setUpEngine(FakeConfig(demoProject = true, autoTick = false))
        val vm = newVm()
        idleUntil("ready") { engine.snapshot.value.project.open }
        val uri = Uri.parse("content://test.provider/document/labels.txt")
        val out = java.io.ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, out)
        vm.beginCreateDocument(CreatePurpose.ExportLabels("text", "labels.txt"))
        vm.onDocumentCreated(uri)
        idleUntil("exported") { messages.any { it.startsWith("Exported") } }
        assertTrue(engine.calls.contains("exportLabels"))
        assertTrue(out.size() > 0)
        idleUntil("staging removed") { File(app.cacheDir, "export").listFiles().orEmpty().isEmpty() }
    }

    @Test
    fun cancellingTheSavingCopyRemovesTheStagedExport() {
        setUpEngine(FakeConfig(demoProject = true, autoTick = false))
        val vm = newVm()
        idleUntil("ready") { engine.snapshot.value.project.open }
        val uri = Uri.parse("content://test.provider/document/song.wav")
        val slow = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Thread.sleep(40)
        }
        shadowOf(app.contentResolver).registerOutputStream(uri, slow)
        val key = "WAV (Microsoft)"
        val (rate, channels) = runBlocking {
            val opts = engine.exportOptions(key)
            val defs = engine.exportDefaults(key)
            ExportModel.pickRate(defs.defaultRate, ExportModel.rateChoices(opts, defs)) to 2
        }
        vm.beginCreateDocument(CreatePurpose.ExportAudio(ExportJob(key, "song.wav", "project", channels, rate, false)))
        vm.onDocumentCreated(uri)
        idleUntil("saving") { vm.localProgress.value != null }
        assertTrue(File(app.cacheDir, "export").listFiles().orEmpty().isNotEmpty())
        vm.localProgress.value!!.cancel()
        idleUntil("cancelled") { vm.localProgress.value == null }
        idleUntil("staging removed") { File(app.cacheDir, "export").listFiles().orEmpty().isEmpty() }
        assertFalse(messages.any { it.startsWith("Exported") })
    }

    @Test
    fun purposesSurviveInABundle() {
        val job = ExportJob("MP3 Files", "Song.mp3", "selection", 1, 48000, true)
        for (p in listOf(CreatePurpose.ExportAudio(job), CreatePurpose.BackupProject, CreatePurpose.ExportLabels("subrip", "a.srt"))) {
            assertEquals(p, PurposeCodec.decode(PurposeCodec.encode(p)))
        }
        assertEquals(null, PurposeCodec.decode(null))
    }
}
