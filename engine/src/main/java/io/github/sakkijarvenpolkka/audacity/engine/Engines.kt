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
     * built with the native core (`BuildConfig.NATIVE_ENGINE`), otherwise a
     * [FakeAudacityEngine] (which reports [EngineStatus.Ready]; check
     * [AudacityEngine.isFake]). A native build never falls back to the fake
     * engine: when `libaudacity-jni.so` cannot be loaded, [AudacityEngine.start]
     * reports [EngineStatus.Failed] with the reason. Cheap (does not load the
     * library: start() does, off the main thread), so it may run on the main
     * thread. Every call returns the same instance because the native engine
     * can be started only once per process. Call [AudacityEngine.start]
     * afterwards.
     */
    fun create(context: Context): AudacityEngine =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** True when [create] returns (or would return) the fake engine. */
    fun isFake(): Boolean = instance?.isFake ?: !BuildConfig.NATIVE_ENGINE

    /** Whether the native engine is built in and its library loads. Loads the
     *  library on first use: not on the main thread. */
    fun nativeAvailable(): Boolean = BuildConfig.NATIVE_ENGINE && NativeBridge.isLoaded

    private fun build(context: Context): AudacityEngine =
        if (BuildConfig.NATIVE_ENGINE) {
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
