// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.editor.EditTool
import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.editor.SnapMode
import io.github.sakkijarvenpolkka.audacity.effects.BuiltinEffects
import io.github.sakkijarvenpolkka.audacity.effects.QuickAction
import io.github.sakkijarvenpolkka.audacity.effects.QuickEffects
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineStatus
import io.github.sakkijarvenpolkka.audacity.engine.FakeAudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectApplyResult
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.engine.model.SplitResult
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.menu.Disallowed
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefsState
import io.github.sakkijarvenpolkka.audacity.share.ShareAudio
import io.github.sakkijarvenpolkka.audacity.share.ShareExport
import io.github.sakkijarvenpolkka.audacity.share.ShareFormat
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/** Records the engine calls of the mobile features (the rest goes to the fake engine). */
class MobileEngine(private val inner: AudacityEngine) : AudacityEngine by inner {
    val calls = CopyOnWriteArrayList<String>()
    val applied = CopyOnWriteArrayList<Pair<String, Map<String, JsonElement>?>>()
    val exports = CopyOnWriteArrayList<ShareExport>()
    val setOptions = CopyOnWriteArrayList<Pair<Int, ExportValue>>()

    override suspend fun applyEffect(id: String, params: Map<String, JsonElement>?, duration: Double?, curve: EqCurve?): EffectApplyResult {
        applied += id to params
        return inner.applyEffect(id, params, duration, curve)
    }
    override suspend fun edit(command: String) { calls += command; inner.edit(command) }
    override suspend fun splitAt(t: Double, trackIds: List<Long>?): SplitResult {
        calls += "splitAt:$t"
        return inner.splitAt(t, trackIds)
    }
    override suspend fun setExportOption(formatKey: String, id: Int, value: ExportValue): ExportOptions {
        setOptions += id to value
        return inner.setExportOption(formatKey, id, value)
    }
    override suspend fun export(path: String, formatKey: String, range: String, channels: Int, rate: Int, skipSilenceAtStart: Boolean): String {
        exports += ShareExport(path, formatKey, range, channels, rate)
        return inner.export(path, formatKey, range, channels, rate, skipSilenceAtStart)
    }
}

/**
 * App side of the mobile features: the quick effects sheet's mapping to
 * effects.apply, the View-menu editor toggles (persisted), Split at Play
 * Head, and File ▸ Share Audio (export call, options restored, share intent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileFeaturesTest {
    private val app: AudacityApp = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakeAudacityEngine
    private lateinit var engine: MobileEngine
    private lateinit var host: TestHost
    private lateinit var effects: EffectList

    @Before
    fun setUp() {
        fake = FakeAudacityEngine(FakeConfig(demoProject = true, autoTick = false, longOperationMillis = 0,
            filesDir = app.filesDir.absolutePath, cacheDir = app.cacheDir.absolutePath))
        engine = MobileEngine(fake)
        host = TestHost(engine)
        effects = runBlocking { fake.effects() }
    }

    @After
    fun tearDown() = fake.dispose()

    private val flags: Long get() = engine.snapshot.value.flags

    private fun id(symbol: String): String = BuiltinEffects.find(effects, symbol)!!.id

    private fun quick(key: String) = (QuickEffects.oneTap(effects) + QuickEffects.dialogs(effects)).first { it.key == key }

    // ---- quick effects ----------------------------------------------------------------

    @Test
    fun effectsAreFoundBySymbolNotByName() {
        assertEquals("Amplify", BuiltinEffects.symbolOf("Effect_Audacity_Audacity_Amplify_Built-in Effect: Amplify"))
        assertEquals("Filter Curve", BuiltinEffects.symbolOf("Effect_Audacity_Audacity_Filter Curve_Built-in Effect: Filter Curve"))
        assertNull(BuiltinEffects.symbolOf("Effect_Nyquist_Audacity_Fade In_/plug-ins/fade.ny"))
        // A translated name does not matter
        val translated = EffectList(effects.effects.map { if (it.name == "Fade In") it.copy(name = "페이드 인") else it })
        assertEquals(id(BuiltinEffects.FADE_IN), BuiltinEffects.find(translated, BuiltinEffects.FADE_IN)?.id)
        assertTrue(BuiltinEffects.changesLength(id(BuiltinEffects.CHANGE_TEMPO)))
        assertTrue(BuiltinEffects.changesLength(id(BuiltinEffects.CHANGE_SPEED)))
        assertFalse(BuiltinEffects.changesLength(id(BuiltinEffects.CHANGE_PITCH)))
    }

    @Test
    fun oneTapEntriesMapToEffectsApplyWithExplicitParams() {
        val oneTap = QuickEffects.oneTap(effects)
        assertEquals(
            listOf("fadeIn", "fadeOut", "normalize", "amplifyUp", "amplifyDown", "reverse", "invert", "silence"),
            oneTap.map { it.key },
        )
        assertEquals(QuickAction.Apply(id(BuiltinEffects.FADE_IN), null), quick("fadeIn").action)
        assertEquals(QuickAction.Apply(id(BuiltinEffects.REVERSE), null), quick("reverse").action)
        assertEquals(QuickAction.Edit("edit.silence"), quick("silence").action)
        val normalize = quick("normalize").action as QuickAction.Apply
        assertEquals(id(BuiltinEffects.NORMALIZE), normalize.effectId)
        assertEquals(-1.0, normalize.params!!["PeakLevel"]!!.jsonPrimitive.double, 0.0)
        assertTrue(normalize.params["ApplyVolume"]!!.jsonPrimitive.boolean)
        val up = quick("amplifyUp").action as QuickAction.Apply
        assertEquals(id(BuiltinEffects.AMPLIFY), up.effectId)
        assertEquals(10.0.pow(3.0 / 20.0), up.params!!["Ratio"]!!.jsonPrimitive.double, 1e-12)
        assertEquals(JsonPrimitive(false), up.params["AllowClipping"])
        val down = quick("amplifyDown").action as QuickAction.Apply
        assertEquals(10.0.pow(-3.0 / 20.0), down.params!!["Ratio"]!!.jsonPrimitive.double, 1e-12)
        assertEquals("Amplify −3 dB", quick("amplifyDown").label.resolve(app.resources))
    }

    @Test
    fun oneTapEffectsRunOnTheSelection() = runBlocking {
        // Demo: Audio 1 selected, 2 … 4 s
        assertTrue(engine.snapshot.value.has(CommandFlags.TS))
        for (key in listOf("amplifyDown", "normalize", "fadeIn", "reverse", "invert")) {
            val item = quick(key).item()
            assertTrue(key, item.enabled(flags))
            item.action(host)
        }
        assertEquals(
            listOf(BuiltinEffects.AMPLIFY, BuiltinEffects.NORMALIZE, BuiltinEffects.FADE_IN, BuiltinEffects.REVERSE, BuiltinEffects.INVERT),
            engine.applied.map { BuiltinEffects.symbolOf(it.first) },
        )
        assertEquals(QuickEffects.NORMALIZE_PARAMS, engine.applied[1].second)
        quick("silence").item().action(host)
        assertEquals(listOf("edit.silence"), engine.calls)
        assertTrue(engine.snapshot.value.history.canUndo)
    }

    @Test
    fun withoutATimeSelectionTilesSayWhy() = runBlocking {
        engine.select(3.0, 3.0)
        val item = quick("fadeOut").item()
        assertFalse(item.enabled(flags))
        val why = Disallowed.reason(item.required, flags, "Fade Out")
        assertEquals(UiText.Res(R.string.why_select_audio, listOf("Fade Out")), why)
        // Noise Reduction explains its two steps
        val nr = quick("noiseReduction")
        assertTrue(nr.noiseReduction)
        assertEquals(R.string.why_noise_reduction, (Disallowed.reason(nr.item().required, flags, "Noise Reduction", nr.noiseReduction) as UiText.Res).id)
    }

    @Test
    fun dialogEntriesOpenTheFullDialogs() = runBlocking {
        val dialogs = QuickEffects.dialogs(effects)
        assertEquals(listOf("noiseReduction", "changeTempo", "changePitch", "changeSpeed", "equalization", "compressor"), dialogs.map { it.key })
        assertEquals(QuickAction.Dialog(id(BuiltinEffects.FILTER_CURVE)), quick("equalization").action)
        for (q in dialogs) q.item().action(host)
        assertEquals(dialogs.map { (it.action as QuickAction.Dialog).effectId }, host.effectsRun)
        assertTrue(engine.applied.isEmpty())
        // Equalization falls back to Graphic EQ, Compressor to Legacy Compressor
        val fewer = EffectList(effects.effects.filter {
            BuiltinEffects.symbolOf(it.id) !in setOf(BuiltinEffects.FILTER_CURVE, BuiltinEffects.COMPRESSOR, BuiltinEffects.CHANGE_SPEED)
        })
        val fallback = QuickEffects.dialogs(fewer).associate { it.key to (it.action as QuickAction.Dialog).effectId }
        assertEquals(id(BuiltinEffects.GRAPHIC_EQ), fallback["equalization"])
        assertEquals(id(BuiltinEffects.LEGACY_COMPRESSOR), fallback["compressor"])
        assertNull("not listed: left out", fallback["changeSpeed"])
        assertTrue(QuickEffects.oneTap(EffectList(emptyList<EffectInfo>())).map { it.key } == listOf("silence"))
    }

    // ---- View menu toggles and preferences ----------------------------------------------

    private fun runMenu(id: String) = runBlocking {
        val st = MenuState(engine.snapshot.value, effects, stopAtTrackEnd = host.uiPrefs.value.stopAtTrackEnd)
        MenuSpec.find(id, st)!!.action(host)
    }

    @Test
    fun viewMenuTogglesUpdateEditorAndPreferences() {
        val e: EditorState = host.editor
        assertEquals(SnapMode.EDGES, UiPrefsState().snapMode)
        runMenu("Snapping")
        assertFalse(host.uiPrefs.value.snapEnabled)
        assertEquals(SnapMode.OFF, e.snapping)
        runMenu("SnapToGrid")   // turns snapping on again, with the grid
        assertEquals(SnapMode.EDGES_AND_GRID, e.snapping)
        runMenu("SnapToGrid")
        assertEquals(SnapMode.EDGES, e.snapping)
        runMenu("StopAtTrackEnd")
        assertFalse(e.stopAtTrackEnd)
        assertFalse(host.uiPrefs.value.stopAtTrackEnd)
        runMenu("SplitTool")
        assertEquals(EditTool.SPLIT, e.tool)
        assertTrue(host.uiPrefs.value.splitTool)
        val st = MenuState(engine.snapshot.value, splitTool = true, snapEnabled = true, snapToGrid = false, stopAtTrackEnd = false)
        assertTrue(MenuSpec.find("SplitTool", st)!!.checked!!(st))
        assertFalse(MenuSpec.find("StopAtTrackEnd", st)!!.checked!!(st))
        assertFalse(MenuSpec.find("SnapToGrid", st)!!.checked!!(st))
    }

    @Test
    fun editorChangesAreSavedAndPersisted() {
        val e = EditorState()
        e.tool = EditTool.SPLIT
        e.snapping = SnapMode.OFF
        e.stopAtTrackEnd = false
        val saved = UiPrefsState(snapToGrid = true).withEditor(e)
        assertTrue(saved.splitTool)
        assertFalse(saved.snapEnabled)
        assertTrue("snapping off keeps the grid choice", saved.snapToGrid)
        assertFalse(saved.stopAtTrackEnd)
        // Persisted in SharedPreferences
        val prefs = UiPrefs(app)
        prefs.update { saved }
        val reloaded = UiPrefs(app).value
        assertEquals(saved, reloaded)
        val fresh = EditorState()
        reloaded.applyTo(fresh)
        assertEquals(EditTool.SPLIT, fresh.tool)
        assertEquals(SnapMode.OFF, fresh.snapping)
        assertFalse(fresh.stopAtTrackEnd)
    }

    // ---- Split at Play Head --------------------------------------------------------------

    @Test
    fun splitAtPlayHeadSplitsAtTheCursorWhenStopped() = runBlocking {
        engine.select(5.0, 5.0)
        val clips = engine.snapshot.value.tracks.first { it.isWave }.clips.size
        runMenu("SplitAtPlayHead")
        assertEquals(listOf("splitAt:5.0"), engine.calls)
        assertEquals(clips + 1, engine.snapshot.value.tracks.first { it.isWave }.clips.size)
        // In a gap there is nothing to split
        engine.calls.clear()
        engine.select(9.0, 9.0)
        runMenu("SplitAtPlayHead")
        assertEquals(listOf("splitAt:9.0"), engine.calls)
        assertEquals(UiText.Res(R.string.msg_nothing_to_split), host.messages.last())
        assertEquals(CommandFlags.WE or CommandFlags.PROJECT_OPEN, MenuSpec.find("SplitAtPlayHead")!!.required)
    }

    @Test
    fun shareAudioMenuOpensTheShareDialog() {
        runMenu("ShareAudio")
        assertEquals(AppDialog.Share, host.dialogs.last())
        assertEquals(CommandFlags.NB or CommandFlags.WE, MenuSpec.find("ShareAudio")!!.required)
    }

    // ---- Share audio -----------------------------------------------------------------------

    private fun newDir(): File = File(app.cacheDir, "share/test-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun quickFormatsFollowTheEngineFormats() = runBlocking {
        val formats = engine.exportFormats()
        assertEquals(listOf(ShareFormat.WAV_16, ShareFormat.MP3_192, ShareFormat.M4A_AAC), ShareAudio.available(formats))
        assertEquals(
            listOf(ShareFormat.WAV_16, ShareFormat.MP3_192),
            ShareAudio.available(formats.filter { it.key != ShareFormat.M4A_AAC.formatKey }),
        )
    }

    @Test
    fun mp3ShareExportsAtConstant192AndRestoresTheOptions() = runBlocking {
        val key = ShareFormat.MP3_192.formatKey
        val before = engine.exportOptions(key).options.associate { it.id to it.value }
        val dir = newDir()
        val call = ShareAudio.export(engine, ShareFormat.MP3_192, selectionOnly = false, dir = dir, projectName = "My Song")
        assertEquals(1, engine.exports.size)
        assertEquals(call, engine.exports.single())
        assertEquals(key, call.formatKey)
        assertEquals("project", call.range)
        assertEquals(File(dir, "My Song.mp3").absolutePath, call.path)
        assertTrue(File(call.path).length() > 0)
        // CBR 192 kbps narrows MP3 to 32 … 48 kHz (ExportMP3.cpp)
        assertTrue(call.rate.toString(), call.rate in 32000..48000)
        assertEquals(2, call.channels)
        // Set for the export: mode CBR (192 kbps is the default constant rate)
        assertEquals("CBR", ExportModel.display(engine.setOptions.first().second))
        // ... and put back afterwards
        assertEquals(before, engine.exportOptions(key).options.associate { it.id to it.value })
    }

    @Test
    fun wavShareUses16BitAndKeepsTheUsersEncoding() = runBlocking {
        val key = ShareFormat.WAV_16.formatKey
        val pcm24 = ExportValue("i", JsonPrimitive(0x0003))
        engine.setExportOption(key, 0x10000, pcm24)
        engine.setOptions.clear()
        engine.select(2.0, 4.0)
        val call = ShareAudio.export(engine, ShareFormat.WAV_16, selectionOnly = true, dir = newDir(), projectName = "Demo")
        assertEquals("selection", call.range)
        assertTrue(call.path.endsWith("/Demo.wav"))
        assertEquals(listOf(0x10000 to ExportValue("i", JsonPrimitive(2)), 0x10000 to pcm24), engine.setOptions.toList())
        assertEquals(pcm24, engine.exportOptions(key).options.first { it.id == 0x10000 }.value)
        // 16-bit PCM WAV of 2 s
        val wav = File(call.path).readBytes()
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(16, (wav[34].toInt() and 0xff) or ((wav[35].toInt() and 0xff) shl 8))
    }

    @Test
    fun selectionShareWithoutSelectionExportsTheProject() = runBlocking {
        engine.select(3.0, 3.0)
        val call = ShareAudio.export(engine, ShareFormat.M4A_AAC, selectionOnly = true, dir = newDir(), projectName = "Demo")
        assertEquals("project", call.range)
        assertTrue(call.path.endsWith("/Demo.m4a"))
        // AAC-LC at 192 kbps (the nearest the encoder offers), then the user's choice again
        assertEquals(listOf(0 to ExportValue("i", JsonPrimitive(192000)), 0 to ExportValue("i", JsonPrimitive(160000))), engine.setOptions.toList())
    }

    @Test
    fun shareIntentGrantsReadAccessThroughTheFileProvider() {
        val dir = File(app.cacheDir, "share/abc").apply { mkdirs() }
        val file = File(dir, "My Song.mp3").apply { writeBytes(ByteArray(16)) }
        val chooser = ShareAudio.shareIntent(app, file, "audio/mpeg", "Share audio")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/mpeg", send.type)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(app.packageName + ".share", uri.authority)
        assertTrue(uri.toString(), uri.path!!.endsWith("/abc/My%20Song.mp3") || uri.path!!.endsWith("/abc/My Song.mp3"))
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { it as ComponentName }
        assertEquals(listOf(ComponentName(app, MainActivity::class.java)), excluded)
        // Only cacheDir/share is served
        val outside = File(app.cacheDir, "export/x.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1)) }
        assertTrue(runCatching { ShareAudio.shareIntent(app, outside, "audio/wav", "x") }.isFailure)
    }

    private fun idleUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    @Test
    fun viewModelStagesTheExportAndAsksForTheShareSheet() {
        app.engineOverride = engine
        val earlier = File(app.cacheDir, "share/earlier").apply { mkdirs() }
        File(earlier, "old.wav").writeBytes(ByteArray(4))
        val vm = AppViewModel(app)
        val requests = CopyOnWriteArrayList<HostRequest>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            scope.launch { vm.requests.collect { requests += it } }
            idleUntil("engine") { engine.snapshot.value.project.open && engine.status.value is EngineStatus.Ready }
            vm.shareAudio(ShareFormat.WAV_16, selectionOnly = true)
            idleUntil("share request") { requests.any { it is HostRequest.ShareFile } }
            val r = requests.filterIsInstance<HostRequest.ShareFile>().single()
            assertEquals("audio/wav", r.mimeType)
            assertTrue(r.file.exists())
            assertEquals(File(app.cacheDir, "share"), r.file.parentFile!!.parentFile)
            assertEquals("selection", engine.exports.single().range)
            assertFalse("earlier shares are removed", earlier.exists())
            assertEquals(ShareFormat.WAV_16.name, vm.uiPrefs.value.lastShareFormat)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oldSharesArePruned() {
        val old = File(app.cacheDir, "share/old").apply { mkdirs(); setLastModified(System.currentTimeMillis() - 2 * ShareAudio.STALE_MS) }
        val recent = File(app.cacheDir, "share/recent").apply { mkdirs() }
        ShareAudio.pruneStaging(app.cacheDir, System.currentTimeMillis() - ShareAudio.STALE_MS)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        ShareAudio.clearStaging(app.cacheDir)
        assertFalse(recent.exists())
    }
}
