// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.ops

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.KO_LABELS
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpProgress
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.fs.VolumeRoot
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.fs.volumesOf
import io.github.sakkijarvenpolkka.explorer.fs.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The application of these tests: the runner each test makes, and how often the service was refused or timed out. */
class ServiceTestApp : Application(), FileOpsHost {
    override lateinit var runner: OperationRunner
    var refusals = 0
    var timeouts = 0

    override fun onForegroundRefused() {
        refusals++
    }

    override fun onForegroundTimedOut() {
        timeouts++
    }
}

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ServiceTestApp::class)
class FileOpsServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app: ServiceTestApp get() = ApplicationProvider.getApplicationContext()
    private val volume: Path by lazy { folder.newFolder("volume").toPath().toRealPath() }
    private val scope = CoroutineScope(SupervisorJob())

    /** Where the runner runs: only when the test says ([runOps]). */
    private val dispatcher = StandardTestDispatcher()
    private lateinit var runner: OperationRunner

    @Before
    fun setUp() {
        val journal = OperationJournal(folder.newFolder("ops"), NioFsBackend)
        val volumes = volumesOf(VolumeRoot("primary", volume, VolumeTraits(), removable = false))
        val ops = FileOps(NioFsBackend, volumes, KO_LABELS, journal, ids = { "r1" })
        runner = OperationRunner(scope, ops, journal, dispatcher, host = null)
        app.runner = runner
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the operations as far as they go, then what the service does about it. */
    private fun runOps() {
        dispatcher.scheduler.advanceUntilIdle()
        idle()
    }

    /** Lets the main looper run what is due in [millis] ms too: the notification waits between updates. */
    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    /** A copy of a file onto one of the same name, submitted; it asks about that once it runs. */
    private fun submitConflictingCopy(): Path {
        val a = write(volume, "src/a.txt", "new")
        val dst = write(volume, "dst/a.txt", "old").parent
        runner.submit(OperationSpec("op1", OpKind.COPY, listOf(a.toString()), dst.toString()))
        return dst
    }

    /** [submitConflictingCopy], run until it waits for the answer. */
    private fun waitingCopy(): PendingConflict {
        submitConflictingCopy()
        runOps()
        return requireNotNull(runner.state.value.pending)
    }

    private fun start(
        controller: ServiceController<FileOpsService> = Robolectric.buildService(FileOpsService::class.java),
    ) = controller.create().startCommand(0, 1)

    private fun shownNotification(): Notification? =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(OpsNotifications.ID)

    private fun Notification?.title(): String? = this?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun Notification?.text(): String? = this?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun startsInForegroundWithDataSyncType() {
        waitingCopy()

        val service = start().get()

        val shadow = shadowOf(service)
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(OpsNotifications.ID, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        val notifications = app.getSystemService(NotificationManager::class.java)
        assertEquals("File operations", notifications.getNotificationChannel(OpsNotifications.CHANNEL)?.name)
        assertEquals(OpsNotifications.CHANNEL, shadow.lastForegroundNotification.channelId)
    }

    @Test
    fun stopsWhenRunnerIdle() {
        val pending = waitingCopy()
        val service = start().get()
        idle()
        assertFalse(shadowOf(service).isStoppedBySelf)

        pending.answer(Resolution(ConflictAction.REPLACE))
        runOps()

        assertTrue(shadowOf(service).isStoppedBySelf)
        // Only if no start came since the one it answers.
        assertEquals(1, shadowOf(service).stopSelfId)
        assertEquals(OpResult.Done::class, runner.state.value.finished.single().result::class)
    }

    @Test
    fun stopsWhenStartedAfterTheRunnerWentIdle() {
        val pending = waitingCopy()
        val service = start().get()
        pending.answer(Resolution(ConflictAction.REPLACE))
        runOps()
        assertEquals(1, shadowOf(service).stopSelfId)

        // Started for more work that ended before the start came: nothing would tell it to stop again.
        service.onStartCommand(Intent(app, FileOpsService::class.java), 0, 2)
        idle()

        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test
    fun onTimeoutCancelsAndStops() {
        val dst = submitConflictingCopy()
        runOps()
        val service = start().get()

        // The system's daily time for data sync services is used up.
        service.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
        // The app starts it again for the next operation.
        assertEquals(1, app.timeouts)
        runOps()
        assertEquals(OpResult.Cancelled, runner.state.value.finished.single().result)
        // Cancelled as any operation is: what it was asked about is as it was.
        assertEquals("old", String(Files.readAllBytes(dst.resolve("a.txt"))))
    }

    @Test
    fun wakeLockHeldOnlyWhileRunning() {
        val pending = waitingCopy()
        val controller = start()
        idle()

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        assertEquals("explorer:ops", shadowOf(lock).tag)
        pending.answer(Resolution(ConflictAction.SKIP))
        runOps()
        assertFalse(lock.isHeld)

        // Held again for more work, and let go when the service goes, whatever is left.
        waitingCopy()
        assertTrue(lock.isHeld)
        controller.destroy()
        assertFalse(lock.isHeld)
    }

    @Test
    fun wakeLockHasSixHourTimeout() {
        waitingCopy()
        start()
        idle()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)

        // Should the work never end, the lock goes after six hours: the most a day's data sync time allows.
        ShadowSystemClock.advanceBy(Duration.ofHours(6).minusMillis(1))
        assertTrue(lock.isHeld)
        ShadowSystemClock.advanceBy(Duration.ofMillis(2))
        assertFalse(lock.isHeld)
    }

    @Test
    fun pendingConflictShowsNeedsDecision() {
        submitConflictingCopy()
        // Queued, not yet run: no item done of the one it copies.
        val service = start().get()
        assertEquals("Copying 0 of 1 items", shadowOf(service).lastForegroundNotification.title())

        // It runs until it asks; the notification follows, at most twice a second.
        runOps()
        assertEquals("Copying 0 of 1 items", shownNotification().title())
        idleFor(500)

        val shown = shownNotification()
        assertEquals("Your decision is needed", shown.title())
        assertEquals("Copying 0 of 1 items", shown.text())
        assertNotNull(shown?.contentIntent)
        assertEquals(listOf("Cancel"), shown?.actions?.map { it.title.toString() })
    }

    @Test
    fun firstNotificationPostedOnce() {
        // Long after the device started, as it always is.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))
        // Started with a copy running (it waits for an answer), and the same copy queued after it.
        val pending = waitingCopy()
        val again = OperationSpec("op2", OpKind.COPY, listOf("$volume/src/a.txt"), "$volume/dst")
        runner.submit(again)
        val service = start().get()
        assertEquals("Your decision is needed", shadowOf(service).lastForegroundNotification.title())
        val posted = arrayListOf(shadowOf(service).lastForegroundNotification)
        fun look() {
            val shown = shownNotification() ?: return
            if (posted.none { it === shown }) posted += shown
        }

        // Its first second, looked at every 10 ms: the copy is answered, and the next one runs until it asks.
        look()
        pending.answer(Resolution(ConflictAction.REPLACE))
        runOps()
        repeat(100) {
            idleFor(10)
            look()
        }
        val shown = shownNotification()
        assertEquals("Your decision is needed", shown.title())
        // Of the next copy: the update came.
        val cancels = shadowOf(shown?.actions?.single()?.actionIntent).savedIntent
        assertEquals("op2", cancels.getStringExtra(OpsNotifications.EXTRA_OP_ID))
        // What startForeground posted, and at most one update.
        assertTrue("posted ${posted.size} times", posted.size <= 2)

        // A change it does not show (another operation queued) is not posted again.
        runner.submit(again.copy(id = "op3"))
        idleFor(2_000)
        assertSame(shown, shownNotification())
    }

    @Test
    fun progressBarBranches() {
        val spec = OperationSpec("op1", OpKind.COPY, listOf("$volume/a", "$volume/b"), "$volume/dst")
        // The progress bar shown for it: its steps, how many are done, and whether it is indeterminate.
        fun bar(progress: OpProgress?): Triple<Int, Int, Boolean> {
            val extras = OpsNotifications.progress(app, RunnerState(running = RunningOp(spec, progress))).extras
            return Triple(
                extras.getInt(Notification.EXTRA_PROGRESS_MAX),
                extras.getInt(Notification.EXTRA_PROGRESS),
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
            )
        }

        // By bytes, in 1000 steps, once their total is known.
        assertEquals(Triple(1000, 250, false), bar(OpProgress(400, 100, 2, 1, "a")))
        // By items when only those are known.
        assertEquals(Triple(2, 1, false), bar(OpProgress(0, 0, 2, 1, "a")))
        // Indeterminate when neither is, or before the first report.
        assertEquals(Triple(0, 0, true), bar(OpProgress(0, 0, 0, 0, null)))
        assertEquals(Triple(0, 0, true), bar(null))
    }

    @Test
    fun notificationShowsNamesAsDisplayed() {
        val spec = OperationSpec("op1", OpKind.COPY, listOf("$volume/a"), "$volume/dst")
        fun shown(name: String): String? {
            val running = RunningOp(spec, OpProgress(10, 1, 1, 0, name))
            return OpsNotifications.progress(app, RunnerState(running = running)).text()
        }

        // A name that reorders the text or breaks the line cannot pass for another one.
        assertEquals("a\u00B7txt.exe", shown("a\u202Etxt.exe"))
        assertEquals("a\u240Ab.txt", shown("a\nb.txt"))
    }

    @Test
    fun cancelActionCancelsTheOperation() {
        waitingCopy()
        val service = start().get()
        val cancel = shadowOf(service).lastForegroundNotification.actions.single().actionIntent

        // Tapping it starts the service again, with what it holds.
        service.onStartCommand(shadowOf(cancel).savedIntent, 0, 2)
        runOps()

        assertEquals(OpResult.Cancelled, runner.state.value.finished.single().result)
        assertEquals(2, shadowOf(service).stopSelfId)
    }

    @Test
    fun cancelActionWorksWhenStartForegroundFails() {
        waitingCopy()
        val controller = Robolectric.buildService(FileOpsService::class.java)
        val refusal = "Time limit already exhausted for foreground service type dataSync"
        shadowOf(controller.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException(refusal))
        val service = controller.create().get()

        // A tap on the cancel action of a notification shown before, which cannot go into the foreground.
        service.onStartCommand(shadowOf(OpsNotifications.cancelIntent(app, "op1")).savedIntent, 0, 1)
        runOps()

        assertEquals(OpResult.Cancelled, runner.state.value.finished.single().result)
        assertEquals(1, app.refusals)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun refusedForegroundStopsAndTellsTheApp() {
        waitingCopy()
        val controller = Robolectric.buildService(FileOpsService::class.java)
        val refusal = "Time limit already exhausted for foreground service type dataSync"
        shadowOf(controller.get()).setThrowInStartForeground(ForegroundServiceStartNotAllowedException(refusal))

        val service = start(controller).get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, app.refusals)
        // The operation goes on in the process.
        assertEquals("op1", runner.state.value.running?.spec?.id)
    }
}
