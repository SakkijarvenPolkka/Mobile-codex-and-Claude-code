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
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
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

/** What the file operations tell the shell from outside any activity. */
sealed interface OpsEvent {
    /** They could not run in the foreground service: they go on while the app's process lives. */
    data object ForegroundRefused : OpsEvent
}

/** The application, as [FileOpsService] sees it: it has the app's one [runner], and hears of a refusal. */
interface FileOpsHost {
    val runner: OperationRunner

    /** The service could not go into the foreground ([OpsEvent.ForegroundRefused]). */
    fun onForegroundRefused()
}

/**
 * Keeps the app's file operations going in the foreground while the runner ([FileOpsHost.runner]) has
 * any: the app starts it when work comes ([androidx.core.content.ContextCompat.startForegroundService]),
 * and it stops itself once there is none.
 * - Each start puts it in the foreground at once, as a data sync service, with the notification of the
 *   operations ([OpsNotifications]); that follows the runner's state, at most twice a second. Its
 *   cancel action ([OpsNotifications.cancelIntent]) comes back here as a start that cancels.
 * - While there is work, it holds a partial wake lock (6 hours at most at once), let go of when there is
 *   none and when the service goes.
 * - When the system's daily time for data sync services runs out ([onTimeout]), it cancels the running
 *   operation, which cleans up as any cancelled one does, and stops.
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
        try {
            val notification = OpsNotifications.progress(this, runner.state.value)
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ServiceCompat.startForeground(this, OpsNotifications.ID, notification, type)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: in the background, or the day's time is used up.
            host.onForegroundRefused()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == OpsNotifications.ACTION_CANCEL) {
            intent.getStringExtra(OpsNotifications.EXTRA_OP_ID)?.let(runner::cancel)
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
     * process.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val runner = host.runner
        runner.state.value.running?.let { runner.cancel(it.spec.id) }
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
            // A state flow hands a slow collector the latest state only.
            runner.state.collect { state ->
                if (state.idle) return@collect
                notifications.notify(OpsNotifications.ID, OpsNotifications.progress(this@FileOpsService, state))
                delay(UPDATE_INTERVAL_MS)
            }
        } finally {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private val RunnerState.idle: Boolean get() = running == null && queue.isEmpty()
}
