// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.fs.write
import io.github.sakkijarvenpolkka.explorer.ops.FileOpsService
import io.github.sakkijarvenpolkka.explorer.ops.InterruptedOp
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.ops.ServiceStarter
import io.github.sakkijarvenpolkka.explorer.platform.ThumbKind
import io.github.sakkijarvenpolkka.explorer.platform.ThumbnailPort
import io.github.sakkijarvenpolkka.explorer.preview.ImageDecoderPort
import io.github.sakkijarvenpolkka.explorer.preview.MediaPort
import io.github.sakkijarvenpolkka.explorer.preview.MediaViewer
import io.github.sakkijarvenpolkka.explorer.preview.PdfOpener
import io.github.sakkijarvenpolkka.explorer.preview.PdfPort
import io.github.sakkijarvenpolkka.explorer.preview.PdfViewer
import io.github.sakkijarvenpolkka.explorer.preview.TextDocument
import io.github.sakkijarvenpolkka.explorer.preview.TextLoader
import io.github.sakkijarvenpolkka.explorer.preview.ViewerDeps
import io.github.sakkijarvenpolkka.explorer.preview.trackedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The app's one runner, file operations, journal and Recycle Bin, as the app puts them together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExplorerAppTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: ExplorerApp get() = ApplicationProvider.getApplicationContext()

    /** Copies [file] into its own folder with the app's runner, and returns where the copy went. */
    private fun copyInPlace(file: Path): Path = runBlocking {
        app.runner.submit(OperationSpec("t1", OpKind.COPY, listOf(file.toString()), file.parent.toString()))
        val finished = withTimeout(10_000) {
            app.runner.state.first { state -> state.finished.any { it.spec.id == "t1" } }
        }.finished.single { it.spec.id == "t1" }
        (finished.result as OpResult.Done).created.single()
    }

    /**
     * A copy [id] of a new `a.txt` onto an old one, submitted and run until it asks about that: the runner
     * stays busy until the test cancels it.
     */
    private fun submitWaitingCopy(id: String) {
        val dir = folder.root.toPath()
        val a = write(dir, "a.txt", "new")
        val dst = write(dir, "dst/a.txt", "old").parent
        app.runner.submit(OperationSpec(id, OpKind.COPY, listOf(a.toString()), dst.toString()))
        runBlocking { withTimeout(10_000) { app.runner.state.first { it.pending != null } } }
    }

    /** A copy [id] that waits behind the one running. */
    private fun submitQueuedCopy(id: String) {
        val dir = folder.root.toPath()
        app.runner.submit(OperationSpec(id, OpKind.COPY, listOf(dir.resolve("a.txt").toString()), dir.toString()))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Cancels [ids], the first (the one running) last, so that none queued behind it runs. */
    private fun cancel(vararg ids: String) = ids.reversed().forEach(app.runner::cancel)

    /** What the system throws when the app may not start a foreground service now. */
    private fun refusal() = ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")

    /** The app as a new process starts it, over the files the one before left. */
    private fun startAgain(): ExplorerApp = ExplorerApp().apply {
        // What the system does for a new process: the application gets its context, then is created.
        ReflectionHelpers.callInstanceMethod<Any?>(
            this, "attach", ClassParameter.from(Context::class.java, app.baseContext),
        )
        onCreate()
    }

    @Test
    fun runnerUsesTheLabelsOfTheAppsLanguage() {
        val dir = folder.root.toPath()
        val copied = copyInPlace(write(dir, "x.txt", "x"))

        assertEquals(dir.resolve("x - Copy.txt"), copied)
        assertEquals(dir.resolve("New folder"), runBlocking { app.fileOps.createFolder(dir) })
        assertEquals(dir.resolve("New Text Document.txt"), runBlocking { app.fileOps.createTextFile(dir) })
    }

    @Test
    @Config(qualifiers = "ko")
    fun koreanLabels() {
        val dir = folder.root.toPath()
        val copied = copyInPlace(write(dir, "x.txt", "x"))

        assertEquals(dir.resolve("x - 복사본.txt"), copied)
        assertEquals(dir.resolve("새 폴더"), runBlocking { app.fileOps.createFolder(dir) })
        assertEquals(dir.resolve("새 텍스트 문서.txt"), runBlocking { app.fileOps.createTextFile(dir) })
    }

    @Test
    fun journalIsInTheAppsFiles() {
        val dir = folder.root.toPath()
        copyInPlace(write(dir, "x.txt", "x"))

        // Begun and ended: nothing is left of it, in the folder the journal keeps.
        val journal = app.filesDir.resolve("ops")
        assertEquals(emptyList<String>(), journal.list()?.toList())
    }

    @Test
    fun journalLeftBeforeStartIsSweptAndPublished() {
        val dir = folder.root.toPath()
        val copying = OperationSpec("old1", OpKind.COPY, listOf("$dir/a.txt", "$dir/b.txt"), "$dir/dst")
        val part = write(dir, "dst/.b.txt.old1.xpart", "part")
        // The process died while copying the second file.
        OperationJournal(app.filesDir.resolve("ops"), NioFsBackend).apply {
            begin(copying)
            committed(copying.id, 1)
            add(part)
        }

        val restarted = startAgain()
        val interrupted = runBlocking {
            withTimeout(10_000) { restarted.runner.state.first { it.interrupted.isNotEmpty() } }
        }.interrupted

        assertEquals(listOf(InterruptedOp(copying, 1)), interrupted)
        assertFalse(Files.exists(part))
    }

    @Test
    fun workStartsTheForegroundService() {
        val dir = folder.root.toPath()
        copyInPlace(write(dir, "x.txt", "x"))

        val started = shadowOf(app).nextStartedService
        assertEquals(FileOpsService::class.java.name, started?.component?.className)
    }

    @Test
    fun refusedStartSetsNoticeEvenWithoutCollector() {
        app.serviceStarter = ServiceStarter { _, _ -> throw refusal() }
        val dir = folder.root.toPath()

        // No shell listens: the operation runs in the process, and the notice waits for one.
        copyInPlace(write(dir, "x.txt", "x"))

        assertEquals(OpsNotice.ForegroundRefused, app.opsNotice.value)
        app.clearOpsNotice()
        assertNull(app.opsNotice.value)
    }

    @Test
    fun nextSubmitRetriesServiceAfterRefusal() {
        val started = ArrayList<Intent>()
        app.serviceStarter = ServiceStarter { _, intent ->
            started += intent
            if (started.size == 1) throw refusal()
        }
        submitWaitingCopy("t1")
        assertEquals(1, started.size)

        // More work while the runner is busy without the service: it is asked for again.
        submitQueuedCopy("t2")
        assertEquals(2, started.size)
        assertEquals(FileOpsService::class.java.name, started.last().component?.className)
        // Once it runs, more work does not start it again.
        submitQueuedCopy("t3")
        assertEquals(2, started.size)
        cancel("t1", "t2", "t3")
    }

    @Test
    fun oneSubmitStartsTheServiceOnce() {
        val started = ArrayList<Intent>()
        app.serviceStarter = ServiceStarter { _, intent ->
            started += intent
            throw refusal()
        }
        copyInPlace(write(folder.root.toPath(), "x.txt", "x"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, started.size)

        // Refused, then without work: the next operation asks for the service once, as any first one does.
        submitWaitingCopy("t2")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, started.size)
        cancel("t2")
    }

    @Test
    fun nextSubmitRetriesServiceAfterTimeout() {
        val started = ArrayList<Intent>()
        app.serviceStarter = ServiceStarter { _, intent -> started += intent }
        submitWaitingCopy("t1")
        assertEquals(1, started.size)

        // The day's time for data sync services ran out: the service stopped, the work goes on in the process.
        app.onForegroundTimedOut()
        submitQueuedCopy("t2")

        assertEquals(2, started.size)
        cancel("t1", "t2")
    }

    /** The SD card "1A2B-3C4D" mounted at [card] in [state]. */
    private fun cardIn(card: Path, state: String): StorageVolume =
        StorageVolumeBuilder("public:1A2B-3C4D", card.toFile(), "SD card", Process.myUserHandle(), state)
            .setIsPrimary(false)
            .setIsRemovable(true)
            .setFsUuid("1A2B-3C4D")
            .build()

    private val storage get() = shadowOf(app.getSystemService(StorageManager::class.java))

    /** Mounts the SD card at [card]. */
    private fun mount(card: Path) {
        storage.addStorageVolume(cardIn(card, "mounted"))
        app.volumes.refresh()
    }

    /** The card at [card] goes: its root is gone from the volumes before the event comes. */
    private fun eject(card: Path) {
        val ejecting = cardIn(card, "ejecting")
        storage.resetStorageVolumeList()
        storage.addStorageVolume(ejecting)
        app.sendBroadcast(
            Intent(Intent.ACTION_MEDIA_EJECT, Uri.fromFile(card.toFile()))
                .putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, ejecting),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun ejectingAVolumeStopsTheOperationsOnIt() {
        val dir = folder.root.toPath().toRealPath()
        val card = Files.createDirectory(dir.resolve("card"))
        mount(card)
        // A copy to the card, waiting for an answer.
        val a = write(dir, "a.txt", "new")
        write(card, "a.txt", "old")
        app.runner.submit(OperationSpec("t1", OpKind.COPY, listOf(a.toString()), card.toString()))
        runBlocking { withTimeout(10_000) { app.runner.state.first { it.pending != null } } }

        eject(card)

        val finished = runBlocking {
            withTimeout(10_000) { app.runner.state.first { it.finished.isNotEmpty() } }
        }.finished.single()
        assertEquals(OpResult.Failed(FsError.VolumeRemoved, 0, 1), finished.result)
        assertEquals("old", String(Files.readAllBytes(card.resolve("a.txt"))))
    }

    @Test
    fun ejectingAVolumeDropsItsThumbnails() {
        val dir = folder.root.toPath().toRealPath()
        val card = Files.createDirectory(dir.resolve("card"))
        mount(card)
        val loads = CopyOnWriteArrayList<Path>()
        app.thumbnailPort = object : ThumbnailPort {
            override fun load(file: Path, kind: ThumbKind, sizePx: Int, signal: CancellationSignal): Bitmap {
                loads.add(file)
                return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            }
        }
        fun photo(path: Path) = FileEntry(path, path.fileName.toString(), EntryKind.FILE, 1, 1, "image/jpeg", false, null)
        val onCard = photo(card.resolve("a.jpg"))
        val onPhone = photo(dir.resolve("b.jpg"))
        runBlocking { listOf(onCard, onPhone).forEach { app.thumbnails.get(it, 96) } }

        eject(card)

        assertNull(app.thumbnails.cached(onCard, 96))
        runBlocking { listOf(onCard, onPhone).forEach { app.thumbnails.get(it, 96) } }
        assertEquals(listOf(onCard.path, onPhone.path, onCard.path), loads.toList())
    }

    @Test
    fun ejectWhileInTheBackgroundClosesWhatTheViewersHoldOnThatVolume() {
        val dir = folder.root.toPath().toRealPath()
        val card = Files.createDirectory(dir.resolve("card"))
        mount(card)
        // The names of the files let go of.
        val closed = CopyOnWriteArrayList<String>()
        val viewers = ViewerDeps(
            text = object : TextLoader {
                override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = TextDocument.Binary
            },
            images = object : ImageDecoderPort {
                override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap =
                    error("No pictures here")
                override suspend fun sizeOf(file: Path): IntSize = IntSize(1, 1)
            },
            pdf = PdfOpener { file ->
                object : PdfPort {
                    override val pageCount: Int = 0
                    override suspend fun pageSize(index: Int): IntSize = IntSize(1, 1)
                    override suspend fun render(index: Int, widthPx: Int): Bitmap = error("No pages")
                    override fun close() {
                        closed += file.fileName.toString()
                    }
                }
            },
            media = {
                object : MediaPort {
                    private var file: Path? = null
                    override fun prepare(file: Path, video: Boolean, onReady: (Int) -> Unit, onError: () -> Unit) {
                        this.file = file
                    }
                    override fun play() = Unit
                    override fun pause() = Unit
                    override fun seekTo(ms: Int) = Unit
                    override val positionMs: Int = 0
                    override val isPlaying: Boolean = false
                    override fun release() {
                        file?.let { closed += it.fileName.toString() }
                    }
                }
            },
            io = Dispatchers.Unconfined,
        ).trackedIn(app.viewerFiles)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Column {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PdfViewer(card.resolve("a.pdf"), viewers.pdf, onClose = {}, io = viewers.io)
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val context = LocalContext.current
                            val port = remember { viewers.media(context) }
                            MediaViewer(card.resolve("song.mp3"), video = false, port, onClose = {})
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            PdfViewer(dir.resolve("b.pdf"), viewers.pdf, onClose = {}, io = viewers.io)
                        }
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<String>(), closed.toList())

            // The user ejects the card from the system's settings: the app is in the background, where its
            // viewers stay as they were, drawn by nothing.
            scenario.moveToState(Lifecycle.State.CREATED)
            eject(card)

            assertEquals(Lifecycle.State.CREATED, scenario.state)
            assertEquals(listOf("a.pdf", "song.mp3"), closed.sorted())
        }
        // The viewers leave with the activity: the file on the phone closes then, those on the card not again.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("a.pdf", "b.pdf", "song.mp3"), closed.sorted())
    }
}
