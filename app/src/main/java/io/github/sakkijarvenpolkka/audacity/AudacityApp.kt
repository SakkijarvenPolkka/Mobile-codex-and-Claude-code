/*
 * Audacity Android port — Application: owns the process-wide engine (the
 * native engine can be started only once per process) and the UI prefs.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.sakkijarvenpolkka.audacity.audio.AudioDeviceMonitor
import io.github.sakkijarvenpolkka.audacity.audio.TransportForeground
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineStatus
import io.github.sakkijarvenpolkka.audacity.engine.Engines
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import io.github.sakkijarvenpolkka.audacity.share.ShareAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class AudacityApp : Application() {

    /** Application-wide scope (engine start, staging clean-up). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val defaultEngine: AudacityEngine by lazy { Engines.create(this) }

    /** Replaces the engine (tests); null = the process-wide engine of [Engines.create]. */
    @VisibleForTesting
    var engineOverride: AudacityEngine? = null

    val engine: AudacityEngine get() = engineOverride ?: defaultEngine
    val prefs: UiPrefs by lazy { UiPrefs(this) }

    private var engineServices: AudacityEngine? = null
    private var deviceMonitor: AudioDeviceMonitor? = null

    /**
     * Starts the process-wide companions of [engine] once: the foreground
     * service that keeps recording/playback alive in the background, and the
     * device-list injection (audio.setDevices) once the engine is ready.
     * Called by the view model (which owns the engine start); they outlive it.
     */
    fun attachEngineServices(engine: AudacityEngine) {
        if (engineServices != null) return
        engineServices = engine
        TransportForeground.follow(this, engine, appScope)
        appScope.launch(Dispatchers.Main) {
            // Whenever it becomes ready (not only the first start outcome: a
            // start interrupted by a closed activity is finished by the next one)
            engine.status.first { it is EngineStatus.Ready }
            if (deviceMonitor == null) {
                deviceMonitor = AudioDeviceMonitor(this@AudacityApp, engine, appScope).also { it.start() }
            }
        }
    }

    /**
     * Removal of the staging directories left behind by an earlier process
     * (import-export-project.md §5.2 step 7). New staging waits for it
     * ([awaitStagingCleanup]): an activity result delivered to a new process
     * (picker or share on top when the old one was killed) may stage a copy
     * right away, which the clean-up would otherwise delete mid-copy.
     */
    @VisibleForTesting
    internal var stagingCleanup: Job = CompletableDeferred(Unit)

    /** Suspends until the start-up clean-up of `cacheDir/import`, `cacheDir/export` and old shares is done. */
    suspend fun awaitStagingCleanup() {
        stagingCleanup.join()
    }

    override fun onCreate() {
        super.onCreate()
        stagingCleanup = appScope.launch(Dispatchers.IO) {
            File(cacheDir, "import").deleteRecursively()
            File(cacheDir, "export").deleteRecursively()
            // Shared files: an app that received one may still read it (through the
            // FileProvider, which starts this process), so only old ones go
            ShareAudio.pruneStaging(cacheDir, System.currentTimeMillis() - ShareAudio.STALE_MS)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            appScope.launch { runCatching { engine.trimDisplayCaches(8L shl 20) } }
        }
    }
}
