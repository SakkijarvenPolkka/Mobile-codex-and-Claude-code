/*
 * Files — the foreground service the file operations run under while there are any (spec §5), so
 * that they go on with the app in the background: its notification, its wake lock and the system's
 * time limit.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ops

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The longest the wake lock is held at once: the system's daily time for data sync services. */
private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

private const val WAKE_LOCK_TAG = "explorer:ops"

/** The shortest time between two updates of the notification: at most two a second. */
private const val UPDATE_INTERVAL_MS = 500L

/** What the file operations tell the user from outside any activity: the app keeps it until it is dismissed. */
sealed interface OpsNotice {
    /** They could not run in the foreground service: they go on while the app's process lives. */
    data object ForegroundRefused : OpsNotice
}

/**
 * How the app starts [FileOpsService]: with [androidx.core.content.ContextCompat.startForegroundService],
 * or as a test does instead.
 */
fun interface ServiceStarter {
    fun start(context: Context, intent: Intent)
}

/**
 * The application, as [FileOpsService] sees it: it has the app's one [runner], and hears when the service
 * leaves the operations to the process, so that it can start it again for the next one.
 */
interface FileOpsHost {
    val runner: OperationRunner

    /** The service could not go into the foreground ([OpsNotice.ForegroundRefused]); it stops. */
    fun onForegroundRefused()

    /** The day's time for data sync services is used up ([FileOpsService.onTimeout]); the service stops. */
    fun onForegroundTimedOut()
}

/**
 * Keeps the app's file operations going in the foreground while the runner ([FileOpsHost.runner]) has
 * any: the app starts it when work comes ([androidx.core.content.ContextCompat.startForegroundService]),
 * and it stops itself once there is none.
 * - Each start puts it in the foreground at once, as a data sync service, with the notification of the
 *   operations ([OpsNotifications]); that follows the runner's state, at most twice a second counting
 *   what each start posts, and is posted again only when it shows something else. Its cancel action
 *   ([OpsNotifications.cancelIntent]) comes back here as a start that cancels, whether or not the service
 *   can then go into the foreground.
 * - While there is work, it holds a partial wake lock (6 hours at most at once), let go of when there is
 *   none and when the service goes.
 * - When the system's daily time for data sync services runs out ([onTimeout]), it cancels the running
 *   operation, which cleans up as any cancelled one does, tells the app
 *   ([FileOpsHost.onForegroundTimedOut]) and stops.
 * - If it cannot go into the foreground, it tells the app ([FileOpsHost.onForegroundRefused]) and stops;
 *   the operations go on in the process.
 *
 * The system never starts it again by itself ([START_NOT_STICKY]): what a killed process left is
 * offered again from the journal.
 */
class FileOpsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var watching: Job? = null

    /** The id of the last start: stopping for the lack of work keeps a start that came since. */
    private var lastStartId = 0

    /** What the notification shows now, and when ([SystemClock.uptimeMillis]) it was last posted. */
    private var shown: OpsNotifications.Content? = null
    private var shownAt = 0L

    private val host: FileOpsHost get() = application as FileOpsHost

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        OpsNotifications.createChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val runner = host.runner
        // A cancel tap counts, even if the service cannot go into the foreground.
        if (intent?.action == OpsNotifications.ACTION_CANCEL) {
            intent.getStringExtra(OpsNotifications.EXTRA_OP_ID)?.let(runner::cancel)
        }
        try {
            val content = OpsNotifications.contentOf(this, runner.state.value)
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ServiceCompat.startForeground(this, OpsNotifications.ID, OpsNotifications.notification(this, content), type)
            posted(content)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: in the background, or the day's time is used up.
            host.onForegroundRefused()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when {
            watching == null -> watching = watch(runner)
            // Its work ended before this start came, and the watch already stopped for an older one.
            runner.state.value.idle -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * The day's time for data sync services is used up, and the service has to stop within seconds: the
     * running operation is cancelled (it cleans up as any cancelled one does). Those queued go on in the
     * process, until the app starts the service again.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val runner = host.runner
        runner.state.value.running?.let { runner.cancel(it.spec.id) }
        host.onForegroundTimedOut()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Follows [runner] while the service lives: the wake lock and stopping at once, the notification less often. */
    private fun watch(runner: OperationRunner): Job = scope.launch {
        launch {
            runner.state.collect { state ->
                if (state.idle) {
                    releaseWakeLock()
                    stopSelf(lastStartId)
                } else if (!wakeLock.isHeld) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }
        }
        val notifications = getSystemService(NotificationManager::class.java)
        try {
            runner.state.collect {
                // The state as it is once the notification may be posted again, if it shows something else.
                untilNextPost()
                val state = runner.state.value
                if (state.idle) return@collect
                val content = OpsNotifications.contentOf(this@FileOpsService, state)
                if (content == shown) return@collect
                notifications.notify(OpsNotifications.ID, OpsNotifications.notification(this@FileOpsService, content))
                posted(content)
            }
        } finally {
            releaseWakeLock()
        }
    }

    /** Waits until the notification may be posted again: half a second after it was, by a start too. */
    private suspend fun untilNextPost() {
        while (true) {
            val wait = shownAt + UPDATE_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait <= 0) return
            delay(wait)
        }
    }

    private fun posted(content: OpsNotifications.Content) {
        shown = content
        shownAt = SystemClock.uptimeMillis()
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private val RunnerState.idle: Boolean get() = running == null && queue.isEmpty()
}
