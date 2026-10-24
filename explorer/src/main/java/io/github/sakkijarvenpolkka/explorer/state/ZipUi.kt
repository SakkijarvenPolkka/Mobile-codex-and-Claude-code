/*
 * Files — ZIP archives as the shell asks for them: compressing the selection, and the extract dialog of an
 * archive, opened once the character set of its names is found (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.util.Log
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpOptions
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.isRestrictedAppDataPath
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.platform.VolumeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID

/**
 * What the extract dialog is open for: the archive [entry], the character set its names were found to be
 * in ([detected], by its name), and the folder its files go into ([target]): a new one next to it, named
 * after it without its extension, numbered if that name was taken when the dialog opened (and numbered
 * again when it runs, if the name is taken by then).
 */
data class ExtractRequest(val entry: FileEntry, val detected: String, val target: Path)

/** Whether [entry] is a ZIP archive, which this app extracts: a file whose name ends in `.zip`. */
internal fun isZip(entry: FileEntry): Boolean =
    entry.kind == EntryKind.FILE && entry.name.endsWith(".zip", ignoreCase = true)

/**
 * The ZIP archives of the view model [shell]: what it asks for goes to the app's runner
 * ([ExplorerDeps.runner]); without it, or without [ExplorerDeps.fileOps] to look into an archive, nothing
 * happens. What a compression made is selected once it ends ([OperationsUi]). [obbAllowed] tells whether
 * `Android/obb` is open to the app now. Main thread only, in [scope].
 */
internal class ZipUi(
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val shell: ShellHost,
    private val obbAllowed: () -> Boolean,
) {
    /** How many archives have been asked to be extracted: only the last one asked opens its dialog. */
    private var asked = 0

    /**
     * Compresses [selected], in listing order (the archive is named after the first), into a new archive in
     * the active tab's folder, or in the folder searched for search results.
     */
    fun compress(selected: List<FileEntry>) {
        val target = when (val at = shell.here()) {
            is Location.Folder -> at.path
            is Location.SearchResults -> at.root
            Location.Home, Location.RecycleBin -> return
        }
        if (selected.isEmpty()) return
        submit(OperationSpec(newId(), OpKind.COMPRESS, selected.map { it.path.toString() }, target.toString()))
    }

    /**
     * Whether opening [entry] opens its extract dialog: a ZIP archive whose own folder takes the new folder,
     * one on a mounted drive that is not read-only, and not one Android keeps to an app (`Android/data`,
     * `Android/obb`). Where the archive is decides, not where the active tab is (Home, for a recent file).
     */
    fun extracts(entry: FileEntry): Boolean {
        if (!isZip(entry)) return false
        val folder = entry.path.parent ?: return false
        val volume = deps.lookup.volumeOf(folder) ?: return false
        val readOnly = deps.volumes.volumes.value
            .any { it.uuid == volume.uuid && it.state == VolumeState.MOUNTED_READ_ONLY }
        return !readOnly && !isRestrictedAppDataPath(volume.root, folder, obbAllowed())
    }

    /**
     * Opens the extract dialog of the archive [entry] once the character set of its names is found
     * ([FileOps.zipCharset]) and the folder it would go into ([FileOps.extractFolder]), both read off the
     * main thread; when that fails (an archive that is damaged, encrypted, unsafe, or whose names no
     * character set decodes), a message says why instead. Neither shows once the active tab has left where
     * it was asked, nor once another archive was asked for since; the dialog does not open over another
     * that was opened meanwhile.
     */
    fun requestExtract(entry: FileEntry) {
        val fileOps = deps.fileOps ?: return
        val folder = entry.path.parent ?: return
        val at = shell.here()
        val ask = ++asked
        scope.launch {
            val request = try {
                withContext(deps.io) {
                    val charset = fileOps.zipCharset(entry.path)
                    ExtractRequest(entry, charset.name(), fileOps.extractFolder(entry.path, folder))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is FsException) Log.w(TAG, "Could not look into an archive", e)
                if (ask == asked) shell.showError(at, errorText(e.toFsError()))
                return@launch
            }
            if (ask == asked) shell.openAt(at, Overlay.Extract(request))
        }
    }

    /**
     * Extracts the archive [entry], alone, into a new folder next to it, its names read in the character set
     * named [charset] (found again when it runs, when null); the extract dialog closes.
     */
    fun extract(entry: FileEntry, charset: String?) {
        shell.closeOverlay()
        val folder = entry.path.parent ?: return
        val options = OpOptions(zipCharset = charset)
        submit(OperationSpec(newId(), OpKind.EXTRACT, listOf(entry.path.toString()), folder.toString(), options))
    }

    private fun submit(spec: OperationSpec) {
        deps.runner?.submit(spec)
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "ZipUi"
    }
}
