/*
 * Files — what the system file picker's provider works with, found on its first call (pick spec §3.6).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.Context
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpenSaves
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.TempJournal
import io.github.sakkijarvenpolkka.explorer.platform.AndroidThumbnailPort
import io.github.sakkijarvenpolkka.explorer.platform.MediaScan
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.PermissionGate
import io.github.sakkijarvenpolkka.explorer.platform.ThumbnailPort
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.platform.VolumeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.util.UUID

/**
 * The application, as the provider sees it: the app's one set of volumes, settings, file operations, Recycle
 * Bin, journal of temporary files, media scans and saves in flight, which the provider shares with the app
 * ([ProviderDeps.of]), and whether the file operation the app is running uses a path ([busy]).
 */
interface ProviderHost {
    val volumes: VolumeSource
    val settings: SettingsStore
    val fileOps: FileOps
    val bin: RecycleBin
    val journal: TempJournal
    val mediaScan: MediaScan
    val openSaves: OpenSaves

    /**
     * Whether the operation the app is running now uses [path]
     * ([io.github.sakkijarvenpolkka.explorer.fs.OperationSpec.uses]); one queued is not asked.
     */
    fun busy(path: Path): Boolean
}

/**
 * What the provider works with: the file system [fs]; the [volumes] there are now, each time it is called;
 * the app's [settings] (favorites, recent files), [fileOps], Recycle Bin [bin], [journal] of temporary files
 * and [mediaScan]; [thumbnails] to make them with; [mime] for the types of files; whether the app has all
 * files access now ([granted]); what the primary volume is called now ([internalLabel]: the language may change
 * while the process lives); where blocking work runs ([io]); new ids for temporary names ([ids]); the saves in
 * flight, the app's ([openSaves]); and whether the operation the app is running uses a path ([busy]).
 */
class ProviderDeps(
    val fs: FsBackend,
    val volumes: () -> List<Volume>,
    val settings: SettingsStore,
    val fileOps: FileOps,
    val bin: RecycleBin,
    val journal: TempJournal,
    val mediaScan: MediaScan,
    val thumbnails: ThumbnailPort,
    val mime: MimeResolver,
    val granted: () -> Boolean,
    val internalLabel: () -> String,
    val io: CoroutineDispatcher,
    val ids: () -> String,
    val openSaves: OpenSaves,
    val busy: (Path) -> Boolean,
) {
    companion object {
        /** What the provider uses instead of the app's (tests): set before its first call, cleared after. */
        @Volatile
        var override: ProviderDeps? = null

        /**
         * [override], else what the application ([ProviderHost]) has. Not for `ContentProvider.onCreate`, which
         * the system calls before the application's own onCreate.
         */
        fun of(context: Context): ProviderDeps {
            override?.let { return it }
            val host = context.applicationContext as ProviderHost
            return ProviderDeps(
                fs = NioFsBackend,
                volumes = { host.volumes.volumes.value },
                settings = host.settings,
                fileOps = host.fileOps,
                bin = host.bin,
                journal = host.journal,
                mediaScan = host.mediaScan,
                thumbnails = AndroidThumbnailPort(),
                mime = MimeResolver(),
                granted = PermissionGate::granted,
                internalLabel = { context.getString(R.string.volume_internal) },
                io = Dispatchers.IO,
                ids = { UUID.randomUUID().toString() },
                openSaves = host.openSaves,
                busy = host::busy,
            )
        }
    }
}
