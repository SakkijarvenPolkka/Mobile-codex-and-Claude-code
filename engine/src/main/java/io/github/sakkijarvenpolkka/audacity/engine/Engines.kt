/*
 * Audacity Android port — engine factory.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import android.content.Context
import io.github.sakkijarvenpolkka.audacity.engine.fake.FakeConfig

object Engines {
    @Volatile
    private var instance: AudacityEngine? = null

    /**
     * The process-wide engine: [NativeAudacityEngine] when the module was
     * built with the native core (`BuildConfig.NATIVE_ENGINE`) and
     * `libaudacity-bridge.so` loads, otherwise a [FakeAudacityEngine] (which
     * reports [EngineStatus.Ready]; check [AudacityEngine.isFake]). Every call
     * returns the same instance because the native engine can be started only
     * once per process. Call [AudacityEngine.start] afterwards.
     */
    fun create(context: Context): AudacityEngine =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** True when [create] returns (or would return) the fake engine. */
    fun isFake(): Boolean = instance?.isFake ?: !nativeAvailable()

    /** Whether the native engine is built in and its library loads. */
    fun nativeAvailable(): Boolean = BuildConfig.NATIVE_ENGINE && NativeBridge.isLoaded

    private fun build(context: Context): AudacityEngine =
        if (nativeAvailable()) {
            NativeAudacityEngine(context)
        } else {
            FakeAudacityEngine(
                FakeConfig(
                    longOperationMillis = 700,
                    filesDir = context.filesDir.absolutePath,
                    cacheDir = context.cacheDir.absolutePath,
                    recordPermission = StartConfigs.hasRecordPermission(context),
                ),
            )
        }
}
