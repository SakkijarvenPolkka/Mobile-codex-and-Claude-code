/*
 * Files — picking files for another app (pick spec §2.2, §2.3): what touching an item does, what is chosen,
 * which commands stay, where picking starts and what it remembers.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.DecodedLocation
import io.github.sakkijarvenpolkka.explorer.data.LocationCodec
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** How picking for another app ended: with the files [Chosen], in listing order, or [Cancelled]. */
sealed interface PickOutcome {
    data class Chosen(val paths: List<Path>) : PickOutcome

    data object Cancelled : PickOutcome
}

/**
 * Picking for another app as the shell shows it: the [request], how many of the selected items Open would
 * choose ([chosen]), and how picking ended ([outcome]; null until it does).
 */
data class PickUi(val request: PickRequest, val chosen: Int, val outcome: PickOutcome?) {
    /** The commands that stay while picking, where [commandsFor] offers them; the shell shows no other. */
    val keptCommands: Set<Command> get() = keptFor(request)
}

/** The commands that stay while picking: none of them changes a file. */
private val PICK_COMMANDS = setOf(
    Command.OPEN, Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.REFRESH, Command.PROPERTIES,
    Command.OPTIONS,
)

/** Those that stay while picking one file: nothing selects all the files, or the others. */
private val PICK_ONE_COMMANDS = PICK_COMMANDS - setOf(Command.SELECT_ALL, Command.INVERT)

/** The commands that stay while picking for [request]. */
private fun keptFor(request: PickRequest): Set<Command> =
    if (request.allowMultiple) PICK_COMMANDS else PICK_ONE_COMMANDS

/**
 * What the view model [shell] does otherwise while it picks files of [request] for another app. A file the
 * request takes is chosen with a tap, or a tap selects it when several are taken; touching one it does not take
 * does nothing. Choosing looks first that the files are still there (off the main thread); then their folder is
 * where picking starts next time (the settings' `pickLast`, a [codec] tag), and they become the files opened
 * last. Main thread only, in [scope].
 */
internal class PickMode(
    private val request: PickRequest,
    private val deps: ExplorerDeps,
    private val codec: LocationCodec,
    private val scope: CoroutineScope,
    private val shell: ShellHost,
) {
    private val ended = MutableStateFlow<PickOutcome?>(null)

    /** How picking ended; null until it does. */
    val outcome: StateFlow<PickOutcome?> = ended.asStateFlow()

    /**
     * Where picking starts: the folder files were last picked from, if its volume is mounted; else Home. Whether
     * that folder is still there is looked at once the tab is in it ([checkStart]).
     */
    fun start(): Location {
        val last = deps.settings.pickLast?.let(codec::decode)
        val folder = (last as? DecodedLocation.Ok)?.location as? Location.Folder
        return folder?.takeIf { deps.lookup.volumeOf(it.path) != null } ?: Location.Home
    }

    /**
     * Picking has just started at [start]: a folder that is gone since files were picked from it (or is a file now, or
     * cannot be read) gives way to Home, with nothing to go back to. Looked at off the main thread; nothing once the
     * tab has gone elsewhere meanwhile.
     */
    fun checkStart() {
        val folder = shell.here() as? Location.Folder ?: return
        scope.launch {
            val usable = try {
                withContext(deps.io) { deps.fs.attributes(folder.path).isDirectory }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!usable && shell.here() == folder) shell.replaceHere(Location.Home)
        }
    }

    /** Whether [entry] can be chosen: a file of a type the request takes. */
    fun canChoose(entry: FileEntry): Boolean = entry.kind == EntryKind.FILE && request.accepts(entry.mime)

    /** Whether [entry] is a file of a type the request does not take: shown dimmed, touching it does nothing. */
    fun dimmed(entry: FileEntry): Boolean = entry.kind == EntryKind.FILE && !canChoose(entry)

    /** Whether a tap on [entry] toggles it: a file that can be chosen, when several can. Any other tap opens. */
    fun toggles(entry: FileEntry): Boolean = request.allowMultiple && canChoose(entry)

    /** Those of the commands [offered] that stay while picking. */
    fun commands(offered: Set<Command>): Set<Command> = offered intersect keptFor(request)

    /** Picking as the shell shows it, with the active tab's [selected] items and [outcome] as last seen. */
    fun ui(selected: List<FileEntry>, outcome: PickOutcome?): PickUi =
        PickUi(request, selected.count(::canChoose), outcome)

    /** Opens the file [entry] (tapped, Open, a recent file on Home): it is chosen, if it can be; else nothing. */
    fun open(entry: FileEntry) {
        if (canChoose(entry)) choose(listOf(entry))
    }

    /** Chooses those of the [selected] items that can be chosen, when several files can; else nothing. */
    fun chooseSelected(selected: List<FileEntry>) {
        if (request.allowMultiple) choose(selected.filter(::canChoose))
    }

    /** Ends picking with nothing chosen, unless it has ended. */
    fun cancel() {
        if (ended.value == null) ended.value = PickOutcome.Cancelled
    }

    /**
     * Chooses [files], once it has looked that they are all still there: one gone since its folder was read shows
     * "not found" and the folder is read again. More than [PickRequest.MAX_RESULTS] are refused with a message, as
     * one answer cannot hold them. Nothing once picking has ended.
     */
    private fun choose(files: List<FileEntry>) {
        if (files.isEmpty() || ended.value != null) return
        val at = shell.here()
        if (files.size > PickRequest.MAX_RESULTS) return shell.showError(at, UiText.res(R.string.pick_too_many))
        val paths = files.map { it.path }
        scope.launch {
            val error = try {
                if (withContext(deps.io) { paths.all(deps.fs::exists) }) null else FsError.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toFsError()
            }
            when (error) {
                null -> chosen(paths)
                FsError.NotFound -> {
                    shell.showError(at, UiText.res(R.string.error_not_found))
                    if (shell.here() == at) shell.refresh()
                }
                else -> shell.showError(at, errorText(error))
            }
        }
    }

    /** Ends picking with [paths]: their folder is where it starts next time, and they become recent files. */
    private fun chosen(paths: List<Path>) {
        if (ended.value != null) return
        codec.encode(Location.Folder(paths.first().parent))?.let { deps.settings.pickLast = it }
        // The first chosen ends up the most recent.
        paths.asReversed().forEach(deps::addRecent)
        ended.value = PickOutcome.Chosen(paths)
    }
}
