/*
 * Audacity Android port — File ▸ Share Audio: exports the selection or the
 * project in a quick format to `cacheDir/share/<uuid>/<name>` and hands it to
 * the Android share sheet through the app's FileProvider. (3.7.9's "Share
 * Audio" uploads to audio.com; on Android sharing means ACTION_SEND.)
 *
 * The quick formats set a few export options for the run (WAV 16-bit PCM,
 * MP3 constant 192 kbps, M4A AAC-LC ~192 kbps) and put the user's own export
 * settings back afterwards: `export.setOption` stores options in the engine
 * preferences at once (API.md §3.3), and sharing must not change what the
 * Export dialog offers next time.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import io.github.sakkijarvenpolkka.audacity.MainActivity
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOption
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Quick formats of the share dialog; [formatKey] is the engine's export format key (API.md §5.6). */
enum class ShareFormat(val formatKey: String, @param:StringRes val label: Int) {
    /** WAV, Signed 16-bit PCM (plays everywhere). */
    WAV_16("WAV (Microsoft)", R.string.share_fmt_wav),

    /** MP3, constant 192 kbps. */
    MP3_192("MP3 Files", R.string.share_fmt_mp3),

    /** M4A (AAC-LC, 192 kbps or the nearest the device's encoder offers); only when the engine has the exporter. */
    M4A_AAC("M4A (AAC) Files", R.string.share_fmt_m4a),
}

/** An `export.run` call of a share (exposed for tests). */
data class ShareExport(
    val path: String,
    val formatKey: String,
    val range: String,
    val channels: Int,
    val rate: Int,
)

object ShareAudio {
    /** `cacheDir/<STAGING>`: the FileProvider serves this directory only (res/xml/file_paths.xml). */
    const val STAGING = "share"

    /** Shared files older than this are removed at start-up (a receiver may still read a newer one). */
    const val STALE_MS: Long = 60L * 60L * 1000L

    /** libsndfile SF_FORMAT_WAV: the id of the WAV "Encoding" option; SF_FORMAT_PCM_16 its 16-bit value. */
    private const val WAV_ENCODING_ID = 0x10000
    private const val SF_FORMAT_PCM_16 = 0x0002

    /** ExportMP3.cpp option ids: bit-rate mode and the constant bit rate (kbps). */
    private const val MP3_MODE_ID = 0
    private const val MP3_CBR_ID = 4
    private const val MP3_KBPS = 192

    /** AndroidAacExport.cpp option ids: the AAC-LC bit rate (bps) and the profile. */
    private const val AAC_BITRATE_ID = 0
    private const val AAC_PROFILE_ID = 1
    private const val AAC_PROFILE_LC = 2
    private const val AAC_BPS = 192_000

    fun authority(context: Context): String = context.packageName + ".share"

    /** The quick formats the engine can write, in dialog order (M4A only with the Android AAC exporter). */
    fun available(formats: List<ExportFormat>): List<ShareFormat> =
        ShareFormat.entries.filter { f -> formats.any { it.key == f.formatKey } }

    /** What one option should be set to for a quick format: null = leave it as it is. */
    private class Want(val id: Int, val pick: (ExportOption) -> ExportValue?)

    private fun intValue(o: ExportOption, wanted: Int): ExportValue? =
        o.values.firstOrNull { it.t == "i" && ExportModel.num(it) == wanted.toDouble() }

    /** The listed value nearest to [wanted] (encoder capabilities differ between devices). */
    private fun nearestInt(o: ExportOption, wanted: Int): ExportValue? =
        o.values.filter { it.t == "i" }.minByOrNull { abs(ExportModel.num(it) - wanted) }

    private fun wants(format: ShareFormat): List<Want> = when (format) {
        ShareFormat.WAV_16 -> listOf(Want(WAV_ENCODING_ID) { intValue(it, SF_FORMAT_PCM_16) })
        // The mode first: it decides which bit-rate option is used
        ShareFormat.MP3_192 -> listOf(
            Want(MP3_MODE_ID) { o -> o.values.firstOrNull { it.t == "s" && ExportModel.display(it) == "CBR" } },
            Want(MP3_CBR_ID) { intValue(it, MP3_KBPS) },
        )
        ShareFormat.M4A_AAC -> listOf(
            Want(AAC_PROFILE_ID) { intValue(it, AAC_PROFILE_LC) },
            Want(AAC_BITRATE_ID) { nearestInt(it, AAC_BPS) },
        )
    }

    /**
     * Sets the options of [format] that differ from the quick format's
     * (skipping read-only and unknown ones) and returns the final options
     * plus the replaced values, to be put back with [restoreOptions] in
     * reverse order.
     */
    suspend fun applyOptions(engine: AudacityEngine, format: ShareFormat): Pair<ExportOptions, List<Pair<Int, ExportValue>>> {
        var options = engine.exportOptions(format.formatKey)
        val replaced = ArrayList<Pair<Int, ExportValue>>()
        try {
            for (w in wants(format)) {
                val o = options.options.firstOrNull { it.id == w.id } ?: continue
                if (o.readOnly) continue
                val v = w.pick(o) ?: continue
                if (ExportModel.same(v, o.value)) continue
                options = engine.setExportOption(format.formatKey, o.id, v)
                o.value?.let { replaced += o.id to it }
            }
        } catch (e: Throwable) {
            restoreOptions(engine, format, replaced)
            throw e
        }
        return options to replaced
    }

    /** Puts back the values [applyOptions] replaced (last first); failures are ignored. */
    suspend fun restoreOptions(engine: AudacityEngine, format: ShareFormat, replaced: List<Pair<Int, ExportValue>>) {
        withContext(NonCancellable) {
            for ((id, v) in replaced.asReversed()) {
                try {
                    engine.setExportOption(format.formatKey, id, v)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** "<project name>.<ext>" for the shared file (receivers show it). */
    fun fileName(projectName: String, format: ExportFormat?): String =
        SafFiles.sanitizeFileName(ExportModel.fileName(projectName, format), null)

    /**
     * Exports the selection ([selectionOnly], when there is selected audio)
     * or the whole project to `[dir]/<name>` in [format]; returns the export
     * call that was made. The user's export options are restored afterwards.
     */
    suspend fun export(engine: AudacityEngine, format: ShareFormat, selectionOnly: Boolean, dir: File, projectName: String): ShareExport {
        val (options, replaced) = applyOptions(engine, format)
        try {
            val defaults = engine.exportDefaults(format.formatKey)
            val fmt = options.format
            val channels = ExportModel.defaultChannels(fmt, defaults)
            val rate = ExportModel.pickRate(defaults.defaultRate, ExportModel.rateChoices(options, defaults))
            val range = if (selectionOnly && defaults.hasSelection) "selection" else "project"
            val file = File(dir, fileName(projectName, fmt))
            val call = ShareExport(file.absolutePath, format.formatKey, range, channels, rate)
            engine.export(call.path, call.formatKey, call.range, call.channels, call.rate)
            return call
        } finally {
            restoreOptions(engine, format, replaced)
        }
    }

    /** Deletes every earlier share in `cacheDir/share`. */
    fun clearStaging(cacheDir: File) {
        File(cacheDir, STAGING).listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Deletes earlier shares in `cacheDir/share` modified before [olderThan] (ms since the epoch). */
    fun pruneStaging(cacheDir: File, olderThan: Long) {
        File(cacheDir, STAGING).listFiles()?.forEach { f ->
            if (f.lastModified() < olderThan) f.deleteRecursively()
        }
    }

    /**
     * The chooser for ACTION_SEND of [file] through the FileProvider: read
     * permission granted to the receiver (also through ClipData, which the
     * chooser forwards), this app excluded (it would import the file).
     */
    fun shareIntent(context: Context, file: File, mimeType: String, chooserTitle: CharSequence): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, chooserTitle)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
