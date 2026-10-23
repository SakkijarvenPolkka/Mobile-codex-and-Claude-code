/*
 * Files — Application: owns the process-wide services of the file manager.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.app.Application
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.platform.VolumeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

class ExplorerApp : Application() {

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

    /** Runs the file operations, one at a time, outside any activity. */
    val runner: OperationRunner by lazy { OperationRunner(appScope, fileOps, journal, Dispatchers.IO, host = null) }

    override fun onCreate() {
        super.onCreate()
        volumes.start()
        // What a crash left behind is cleaned up, and the operations it interrupted are offered again.
        runner.loadInterrupted()
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
