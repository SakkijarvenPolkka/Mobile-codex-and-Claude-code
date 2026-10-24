/*
 * Files — ZIP archives as the shell asks for them: compressing the selection, and the extract dialog of an
 * archive, opened once the character set of its names is found (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpOptions
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.splitName
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.UUID

/**
 * What the extract dialog is open for: the archive [entry], the character set its names were found to be
 * in ([detected], by its name), and the folder its files go into ([target]): a new one next to it, named
 * after it without its extension (numbered when it runs, if that name is taken then).
 */
data class ExtractRequest(val entry: FileEntry, val detected: String, val target: Path)

/** Whether [entry] is a ZIP archive, which this app extracts: a file whose name ends in `.zip`. */
internal fun isZip(entry: FileEntry): Boolean =
    entry.kind == EntryKind.FILE && entry.name.endsWith(".zip", ignoreCase = true)

/**
 * The ZIP archives of the view model [shell]: what it asks for goes to the app's runner
 * ([ExplorerDeps.runner]); without it, or without [ExplorerDeps.fileOps] to look into an archive, nothing
 * happens. What a compression made is selected once it ends ([OperationsUi]). Main thread only, in [scope].
 */
internal class ZipUi(
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val shell: ShellHost,
) {
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
     * Opens the extract dialog of the archive [entry] once the character set of its names is found
     * ([FileOps.zipCharset]), which reads the archive off the main thread; when that fails (an archive that
     * is damaged, encrypted, unsafe, or whose names no character set decodes), a message says why instead.
     * Neither shows once the active tab has left where it was asked.
     */
    fun requestExtract(entry: FileEntry) {
        val fileOps = deps.fileOps ?: return
        val folder = entry.path.parent ?: return
        val at = shell.here()
        scope.launch {
            val charset = try {
                withContext(deps.io) { fileOps.zipCharset(entry.path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch shell.showError(at, errorText(e.toFsError()))
            }
            val target = folder.resolve(splitName(entry.name).first)
            shell.openAt(at, Overlay.Extract(ExtractRequest(entry, charset.name(), target)))
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
}
