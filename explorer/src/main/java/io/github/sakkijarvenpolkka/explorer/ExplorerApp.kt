/*
 * Files — Application: owns the process-wide services of the file manager, and does what the file
 * operations need around them: the foreground service, the media scans, and stopping those on a
 * volume that goes.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.ops.FileOpsHost
import io.github.sakkijarvenpolkka.explorer.ops.FileOpsService
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.ops.RunnerHost
import io.github.sakkijarvenpolkka.explorer.ops.RunnerState
import io.github.sakkijarvenpolkka.explorer.ops.ServiceStarter
import io.github.sakkijarvenpolkka.explorer.ops.scanTargets
import io.github.sakkijarvenpolkka.explorer.platform.AndroidMediaScan
import io.github.sakkijarvenpolkka.explorer.platform.MediaScan
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.util.UUID

class ExplorerApp : Application(), RunnerHost, FileOpsHost {

    /** Application-wide scope (work that outlives an activity). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The storage volumes, read on first use; [VolumeRegistry.start] follows their changes. */
    val volumes: VolumeRegistry by lazy { VolumeRegistry(this) }

    /** Settings, favorites, recent files, folder views and the saved tabs, read on first use. */
    val settings: SettingsStore by lazy { SettingsStore(this) }

    /** The Recycle Bin, one for the whole app: it knows which items are still going in. */
    val bin: RecycleBin by lazy { RecycleBin(NioFsBackend, volumes, System::currentTimeMillis, ::newId) }

    /** The journal of the file operations, in `files/ops`. */
    val journal: OperationJournal by lazy { OperationJournal(File(filesDir, "ops"), NioFsBackend) }

    /** The file operations, journaled, with the Recycle Bin and the names of the app's language. */
    val fileOps: FileOps by lazy {
        val labels = NameLabels(
            newFolder = getString(R.string.label_new_folder),
            newTextDocument = getString(R.string.label_new_text),
            copySuffix = getString(R.string.label_copy_suffix),
        )
        FileOps(NioFsBackend, volumes, labels, journal, ids = ::newId, bin = bin)
    }

    /** Runs the file operations, one at a time, outside any activity; the app hears of them ([RunnerHost]). */
    override val runner: OperationRunner by lazy {
        OperationRunner(appScope, fileOps, journal, Dispatchers.IO, host = this)
    }

    /** Tells the media store of what the operations made, on the volumes it indexes. */
    val mediaScan: MediaScan by lazy {
        AndroidMediaScan(this, volumes) { root -> volumes.volumes.value.any { it.root == root && it.fuseBacked } }
    }

    /** Whether the user was asked to allow notifications since the process started (main thread only). */
    internal var notificationsAsked = false

    /** How the foreground service is started; a test puts its own. */
    internal var serviceStarter = ServiceStarter(ContextCompat::startForegroundService)

    private val mutableOpsNotice = MutableStateFlow<OpsNotice?>(null)

    /**
     * What the file operations tell the user from outside any activity, kept until the shell has shown it
     * and the user dismissed it ([clearOpsNotice]): a shell that comes later still shows it.
     */
    val opsNotice: StateFlow<OpsNotice?> = mutableOpsNotice.asStateFlow()

    /** Guards [workWithoutService]. */
    private val serviceLock = Any()

    /**
     * The operations (by id) there were when the service last left them to the process (refused, or out
     * of time), while those go on without it; null while it runs them, or while there are none. One
     * submitted since starts it again ([restartServiceForNewWork]).
     */
    private var workWithoutService: Set<String>? = null

    /** Where each volume was last mounted, by uuid: one that ejects has no root any more. Main thread only. */
    private val lastRoots = HashMap<String, Path>()

    override fun onCreate() {
        super.onCreate()
        volumes.start()
        stopOperationsOnEject()
        restartServiceForNewWork()
        // What a crash left behind is cleaned up, and the operations it interrupted are offered again.
        runner.loadInterrupted()
    }

    /** Work came: it goes on in the foreground service ([startService]). */
    override fun onBusy() = startService()

    /** No work left: the service stops itself once it sees that, and no work goes on without it. */
    override fun onIdle() = synchronized(serviceLock) { workWithoutService = null }

    override fun onFinished(op: FinishedOp) = mediaScan.scan(scanTargets(op, volumes))

    /** The user is told to keep the app open: the operations go on in the process only. */
    override fun onForegroundRefused() {
        mutableOpsNotice.value = OpsNotice.ForegroundRefused
        leftWithoutService()
    }

    override fun onForegroundTimedOut() = leftWithoutService()

    /** Forgets the notice of the file operations, once the user has dismissed it. */
    fun clearOpsNotice() {
        mutableOpsNotice.value = null
    }

    /**
     * Starts the foreground service, so that the work goes on with the app in the background. Refused
     * that, it goes on in the process all the same ([onForegroundRefused]). Called without [serviceLock]:
     * the start is a call to the system.
     */
    private fun startService() {
        try {
            serviceStarter.start(this, Intent(this, FileOpsService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is not in the foreground.
            onForegroundRefused()
        }
    }

    /** The work there is now goes on without the service: the next operation submitted starts it again. */
    private fun leftWithoutService() = synchronized(serviceLock) {
        workWithoutService = workOf(runner.state.value).takeIf { it.isNotEmpty() }
    }

    /**
     * Starts the service again whenever an operation is submitted while the runner is busy without it,
     * which [onBusy] does not hear of: the user just did something, so the app is in the foreground now.
     * It wakes only when the work changes, not on each progress report. Whether to start is decided under
     * [serviceLock], where [onIdle] changes what it is compared with, and the work is then no longer
     * without the service (a refusal says so again), so that one submit starts the service once.
     */
    private fun restartServiceForNewWork() {
        appScope.launch(Dispatchers.Main.immediate) {
            runner.state.map(::workOf).distinctUntilChanged().collect {
                val start = synchronized(serviceLock) {
                    val before = workWithoutService ?: return@collect
                    workOf(runner.state.value).any { it !in before }.also { if (it) workWithoutService = null }
                }
                if (start) startService()
            }
        }
    }

    /** The ids of the operations [state] has queued or running. */
    private fun workOf(state: RunnerState): Set<String> =
        (state.queue.map { it.id } + listOfNotNull(state.running?.spec?.id)).toSet()

    /**
     * Stops the operations on a volume that goes as soon as it does ([OperationRunner.cancelTouching]):
     * vold kills the processes that still hold its files open a few seconds later. Both run on the main
     * thread, where the registry changes, so a volume's root is noted before an event needs it.
     */
    private fun stopOperationsOnEject() {
        appScope.launch(Dispatchers.Main.immediate) {
            volumes.volumes.collect { list ->
                for (volume in list) volume.root?.let { lastRoots[volume.uuid] = it }
            }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            volumes.events.collect { event ->
                if (event is VolumeEvent.Ejecting) {
                    lastRoots[event.uuid]?.let { runner.cancelTouching(it, FsError.VolumeRemoved) }
                }
            }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
