/*
 * Audacity Android port — start configuration from the Android context
 * (API.md §2.1).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.sakkijarvenpolkka.audacity.engine.model.StartConfig
import java.io.File
import java.util.Locale

object StartConfigs {
    /** Builds the §2.1 configuration. `nyquistDir`/`pluginsDir` are
     *  `filesDir/audacity/{nyquist,plug-ins}` (the engine requires those last
     *  path components and a common parent). */
    fun fromContext(context: Context): StartConfig {
        val audacityDir = File(context.filesDir, AssetInstaller.ASSET_ROOT)
        val audio = context.getSystemService(AudioManager::class.java)
        return StartConfig(
            filesDir = context.filesDir.absolutePath,
            noBackupDir = context.noBackupFilesDir.absolutePath,
            cacheDir = context.cacheDir.absolutePath,
            nyquistDir = File(audacityDir, "nyquist").absolutePath,
            pluginsDir = File(audacityDir, "plug-ins").absolutePath,
            locale = Locale.getDefault().toString(),
            deviceModel = Build.MODEL ?: "",
            audioOutputSampleRate = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioFramesPerBuffer = audio?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
            recordPermission = hasRecordPermission(context),
        )
    }

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
