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
import io.github.sakkijarvenpolkka.explorer.ops.OpsEvent
import io.github.sakkijarvenpolkka.explorer.ops.RunnerHost
import io.github.sakkijarvenpolkka.explorer.ops.scanTargets
import io.github.sakkijarvenpolkka.explorer.platform.AndroidMediaScan
import io.github.sakkijarvenpolkka.explorer.platform.MediaScan
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val mutableOpsEvents = MutableSharedFlow<OpsEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** What the file operations tell the shell from outside any activity. Not replayed. */
    val opsEvents: SharedFlow<OpsEvent> = mutableOpsEvents.asSharedFlow()

    /** Where each volume was last mounted, by uuid: one that ejects has no root any more. Main thread only. */
    private val lastRoots = HashMap<String, Path>()

    override fun onCreate() {
        super.onCreate()
        volumes.start()
        stopOperationsOnEject()
        // What a crash left behind is cleaned up, and the operations it interrupted are offered again.
        runner.loadInterrupted()
    }

    /**
     * Work came: it goes on in the foreground service, so that it does with the app in the background.
     * Refused that, it goes on in the process all the same, and the user is told to keep the app open.
     */
    override fun onBusy() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, FileOpsService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is not in the foreground.
            onForegroundRefused()
        }
    }

    /** Nothing: the service stops itself once it sees that the runner has no work left. */
    override fun onIdle() = Unit

    override fun onFinished(op: FinishedOp) = mediaScan.scan(scanTargets(op, volumes))

    override fun onForegroundRefused() {
        mutableOpsEvents.tryEmit(OpsEvent.ForegroundRefused)
    }

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
