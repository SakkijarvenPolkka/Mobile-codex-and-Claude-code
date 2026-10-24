/*
 * Files — opening files: in the built-in viewers, full screen or in the preview pane of a wide window,
 * else in another app; a file a viewer shows, or another app was started for, becomes the most recent
 * one opened (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.data.Recent
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.isPresent
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.preview.PreviewKind
import io.github.sakkijarvenpolkka.explorer.preview.ViewerRequest
import io.github.sakkijarvenpolkka.explorer.preview.previewKindFor
import io.github.sakkijarvenpolkka.explorer.preview.siblingsOrSelf
import io.github.sakkijarvenpolkka.explorer.ui.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Makes [file] the most recent of the files opened ([SettingsStore.addRecent]), when it is on a mounted volume. */
internal fun ExplorerDeps.addRecent(file: Path) {
    val volume = lookup.volumeOf(file) ?: return
    settings.addRecent(Recent(volume.uuid, volume.root.relativize(file).toString(), clock()))
}

/**
 * Opens the files of the view model [shell]: in the built-in viewers of [ExplorerDeps.viewers] those they
 * show ([previewKindFor]), any other in another app with [handOver]. It keeps what is open: the file
 * shown full screen, whether the preview pane is on, and the file last opened into it. Main thread only,
 * in [scope].
 */
internal class Viewers(
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val shell: ShellHost,
    private val handOver: HandOver,
) {
    /**
     * [viewer] is the file shown full screen; [pane] whether the preview pane is on, which shows while the
     * window is [expanded] (840 dp wide or more); [picked] the file last opened into it.
     */
    data class State(
        val viewer: ViewerRequest? = null,
        val pane: Boolean = false,
        val expanded: Boolean = false,
        val picked: FileEntry? = null,
    ) {
        /** Whether the preview pane shows: it is on, in a wide window. */
        val paneShown: Boolean get() = pane && expanded
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** The volumes (by uuid) of the file shown full screen and of the one picked for the pane. */
    private var viewerVolume: String? = null
    private var pickedVolume: String? = null

    /**
     * Opens the file [entry], one of the active tab's [rows]: a file the viewers show goes in the preview
     * pane while it shows, unless [fullScreen], else full screen (a picture with the other pictures of
     * [rows], in their order, to swipe to); any other file goes to another app. A file gone since its
     * folder was read shows "not found" and the folder is read again. A file a viewer shows becomes a
     * recent one, as does one another app was started for.
     */
    fun open(entry: FileEntry, rows: List<Row>, fullScreen: Boolean = false) {
        val kind = previewKindFor(entry)
        if (deps.viewers == null || kind == null) return handOver.open(entry)
        val at = shell.here()
        scope.launch {
            val error = try {
                if (withContext(deps.io) { deps.fs.isPresent(entry.path) }) null else FsError.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toFsError()
            }
            when {
                error == FsError.NotFound -> handOver.onOpenResult(OpenResult.NotFound, at)
                error != null -> shell.showError(at, errorText(error))
                // The tab went elsewhere meanwhile.
                shell.here() != at -> Unit
                else -> show(entry, kind, rows, fullScreen)
            }
        }
    }

    /** Closes the file shown full screen. */
    fun close() {
        viewerVolume = null
        mutableState.update { it.copy(viewer = null) }
    }

    /** Turns the preview pane on or off; off, it forgets the file picked for it. */
    fun togglePane() {
        pickedVolume = null
        mutableState.update { it.copy(pane = !it.pane, picked = null) }
    }

    /** The window is [expanded] (840 dp wide or more), where the preview pane can show, or not. */
    fun setExpanded(expanded: Boolean) = mutableState.update { it.copy(expanded = expanded) }

    /**
     * The volume [uuid] is going: what is shown of a file on it closes, before vold kills the processes
     * that still hold its files open.
     */
    fun onEjecting(uuid: String) {
        if (viewerVolume == uuid) close()
        if (pickedVolume == uuid) {
            pickedVolume = null
            mutableState.update { it.copy(picked = null) }
        }
    }

    /**
     * What the preview pane of [state] shows in a folder listing [rows] with [selected] in it: the item
     * selected, if only one is; with none, the file last opened into it, while it is listed (as it is now).
     */
    fun previewed(state: State, rows: List<Row>, selected: List<FileEntry>): FileEntry? = when {
        selected.isNotEmpty() -> selected.singleOrNull()
        else -> state.picked?.let { picked -> rows.firstOrNull { it.entry.path == picked.path }?.entry }
    }

    private fun show(entry: FileEntry, kind: PreviewKind, rows: List<Row>, fullScreen: Boolean) {
        deps.addRecent(entry.path)
        val volume = deps.lookup.volumeOf(entry.path)?.uuid
        if (state.value.paneShown && !fullScreen) {
            pickedVolume = volume
            mutableState.update { it.copy(picked = entry) }
            return
        }
        val pictures = when (kind) {
            PreviewKind.Image -> rows.map { it.entry }.filter { previewKindFor(it) == PreviewKind.Image }
            else -> emptyList()
        }
        viewerVolume = volume
        mutableState.update { it.copy(viewer = ViewerRequest(entry, siblingsOrSelf(entry, pictures))) }
    }
}
