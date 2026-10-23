/*
 * Files — Application: owns the process-wide services of the file manager.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.app.Application
import io.github.sakkijarvenpolkka.explorer.platform.VolumeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ExplorerApp : Application() {

    /** Application-wide scope (work that outlives an activity). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The storage volumes, read on first use; [VolumeRegistry.start] follows their changes. */
    val volumes: VolumeRegistry by lazy { VolumeRegistry(this) }
}
