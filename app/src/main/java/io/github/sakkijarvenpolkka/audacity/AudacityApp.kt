/*
 * Audacity Android port — Application: owns the process-wide engine (the
 * native engine can be started only once per process) and the UI prefs.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.Engines
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        // import-export-project.md §5.2 step 7: staging left behind by a crash.
        appScope.launch(Dispatchers.IO) {
            File(cacheDir, "import").deleteRecursively()
            File(cacheDir, "export").deleteRecursively()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            appScope.launch { runCatching { engine.trimDisplayCaches(8L shl 20) } }
        }
    }
}
