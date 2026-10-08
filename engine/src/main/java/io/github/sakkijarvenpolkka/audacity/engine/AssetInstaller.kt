/*
 * Audacity Android port — extraction of the Nyquist runtime, the plug-ins
 * and the engine's translations.
 *
 * The APK carries assets/audacity/{nyquist,plug-ins,locale} (packaged by the
 * :engine Gradle task packageAudacityAssets from native/audacity/{nyquist,
 * plug-ins} using the file lists of their CMakeLists.txt, and the compiled
 * catalogs native/audacity/locale/<lang>/LC_MESSAGES/audacity.mo). The engine
 * reads them with fopen, so they are copied to filesDir/audacity/{nyquist,
 * plug-ins,locale} (API.md §2.1) whenever the app version changes. Only those
 * directories are replaced: filesDir/audacity also holds audacity.cfg and the
 * other preferences.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class AssetInstaller(
    private val source: AssetSource,
    /** `filesDir/audacity` */
    val audacityDir: File,
) {
    /** Read access to the packaged assets (AssetManager on Android). */
    interface AssetSource {
        /** Children of an asset directory; empty for files and missing paths. */
        fun list(path: String): List<String>
        fun open(path: String): InputStream
    }

    /** True when the stamp matches and both directories exist. */
    fun isUpToDate(versionStamp: String): Boolean {
        val stamp = File(audacityDir, STAMP_FILE)
        return stamp.isFile &&
            runCatching { stamp.readText() }.getOrNull() == versionStamp &&
            SUBDIRS.all { File(audacityDir, it).isDirectory }
    }

    /**
     * Extracts the assets unless [isUpToDate]. Each directory is extracted
     * next to its destination first and then swapped in, so an interrupted
     * extraction never leaves a half-written runtime behind a valid stamp.
     * @return the number of files written (0 when already up to date).
     */
    @Throws(IOException::class)
    fun installIfNeeded(versionStamp: String): Int {
        if (isUpToDate(versionStamp)) return 0
        if (!audacityDir.isDirectory && !audacityDir.mkdirs()) throw IOException("cannot create $audacityDir")
        File(audacityDir, STAMP_FILE).delete()
        val roots = source.list(ASSET_ROOT).toSet()
        var written = 0
        for (sub in SUBDIRS) {
            val dest = File(audacityDir, sub)
            val tmp = File(audacityDir, ".$sub.tmp")
            tmp.deleteRecursively()
            if (sub in roots) {
                written += copyTree("$ASSET_ROOT/$sub", tmp)
            } else {
                // Not packaged (e.g. a unit-test build): install an empty directory
                if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
            }
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("cannot rename $tmp to $dest")
        }
        File(audacityDir, STAMP_FILE).writeText(versionStamp)
        return written
    }

    private fun copyTree(assetPath: String, dest: File): Int {
        val children = source.list(assetPath)
        if (children.isEmpty()) {
            // A file (AssetManager lists files as empty directories)
            dest.parentFile?.mkdirs()
            try {
                source.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            } catch (e: FileNotFoundException) {
                // An empty directory: AAPT does not package those, nothing to copy
                dest.mkdirs()
                return 0
            }
            return 1
        }
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("cannot create $dest")
        var n = 0
        for (child in children) n += copyTree("$assetPath/$child", File(dest, child))
        return n
    }

    companion object {
        const val ASSET_ROOT = "audacity"
        /** Replaced on every version change; the engine finds `locale/<lang>/LC_MESSAGES/audacity.mo`. */
        val SUBDIRS = listOf("nyquist", "plug-ins", "locale")
        const val STAMP_FILE = ".assets-version"

        fun forContext(context: Context): AssetInstaller {
            val assets = context.assets
            return AssetInstaller(
                object : AssetSource {
                    override fun list(path: String): List<String> =
                        try { assets.list(path)?.toList() ?: emptyList() } catch (e: IOException) { emptyList() }
                    override fun open(path: String): InputStream = assets.open(path)
                },
                File(context.filesDir, ASSET_ROOT),
            )
        }

        /** Changes with every app update (also for debug builds that keep
         *  versionCode 1, through lastUpdateTime). */
        @Suppress("DEPRECATION")
        fun versionStamp(context: Context): String = try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
            "${info.longVersionCode}:${info.versionName}:${info.lastUpdateTime}:${BuildConfig.NATIVE_ENGINE}"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}
