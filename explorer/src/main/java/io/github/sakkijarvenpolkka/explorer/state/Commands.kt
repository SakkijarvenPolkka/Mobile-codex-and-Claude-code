/*
 * Files — which commands are available where.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.state.Command.COMPRESS
import io.github.sakkijarvenpolkka.explorer.state.Command.COPY
import io.github.sakkijarvenpolkka.explorer.state.Command.CUT
import io.github.sakkijarvenpolkka.explorer.state.Command.DELETE
import io.github.sakkijarvenpolkka.explorer.state.Command.DELETE_PERMANENT
import io.github.sakkijarvenpolkka.explorer.state.Command.EMPTY_BIN
import io.github.sakkijarvenpolkka.explorer.state.Command.EXTRACT
import io.github.sakkijarvenpolkka.explorer.state.Command.INVERT
import io.github.sakkijarvenpolkka.explorer.state.Command.NEW_FOLDER
import io.github.sakkijarvenpolkka.explorer.state.Command.NEW_TEXT
import io.github.sakkijarvenpolkka.explorer.state.Command.OPEN
import io.github.sakkijarvenpolkka.explorer.state.Command.OPEN_WITH
import io.github.sakkijarvenpolkka.explorer.state.Command.OPTIONS
import io.github.sakkijarvenpolkka.explorer.state.Command.PASTE
import io.github.sakkijarvenpolkka.explorer.state.Command.PIN
import io.github.sakkijarvenpolkka.explorer.state.Command.PROPERTIES
import io.github.sakkijarvenpolkka.explorer.state.Command.REFRESH
import io.github.sakkijarvenpolkka.explorer.state.Command.RENAME
import io.github.sakkijarvenpolkka.explorer.state.Command.RESTORE
import io.github.sakkijarvenpolkka.explorer.state.Command.SELECT_ALL
import io.github.sakkijarvenpolkka.explorer.state.Command.SELECT_NONE
import io.github.sakkijarvenpolkka.explorer.state.Command.SHARE
import io.github.sakkijarvenpolkka.explorer.state.Command.UNPIN

/** The kind of [Location] the active tab shows. */
enum class LocationKind { HOME, FOLDER, BIN, SEARCH }

/** Everything the command bar, the ⋯ menu and the context menu can offer. */
enum class Command {
    OPEN, OPEN_WITH, NEW_FOLDER, NEW_TEXT, CUT, COPY, PASTE, RENAME, SHARE, DELETE, DELETE_PERMANENT,
    SELECT_ALL, SELECT_NONE, INVERT, COMPRESS, EXTRACT, PIN, UNPIN, PROPERTIES, REFRESH, RESTORE, EMPTY_BIN, OPTIONS,
}

/**
 * What the available commands depend on: the [kind] of location; the [selection]; the
 * [clipboard]; whether the folder is [restricted] (`Android/data`, `Android/obb`); whether its
 * volume is [volumeWritable]; whether the folders Pin and Unpin act on (the selected folders,
 * or the current folder when nothing is selected) are [pinned]; whether the Recycle Bin is
 * [binEmpty]; and whether the listing [hasEntries].
 */
data class CommandContext(
    val kind: LocationKind,
    val selection: List<FileEntry>,
    val clipboard: Clipboard?,
    val restricted: Boolean,
    val volumeWritable: Boolean,
    val pinned: Boolean,
    val binEmpty: Boolean,
    val hasEntries: Boolean,
)

/** Commands that change files; a read-only volume offers none of them. */
private val FILE_CHANGES = setOf(
    NEW_FOLDER, NEW_TEXT, CUT, PASTE, RENAME, DELETE, DELETE_PERMANENT, COMPRESS, EXTRACT, RESTORE, EMPTY_BIN,
)

/** Commands that add items to the folder or rename them; a restricted folder offers none of them. */
private val FOLDER_WRITES = setOf(NEW_FOLDER, NEW_TEXT, PASTE, RENAME, COMPRESS, EXTRACT)

/**
 * The commands available in [c]. Options is always available. Home offers Refresh, and Open
 * for a selection. The Recycle Bin offers Restore, Delete permanently and Empty Recycle Bin.
 * Folders and search results offer the file commands; only folders take new and pasted items.
 */
fun commandsFor(c: CommandContext): Set<Command> {
    var offered = when (c.kind) {
        LocationKind.HOME -> homeCommands(c)
        LocationKind.FOLDER, LocationKind.SEARCH -> folderCommands(c)
        LocationKind.BIN -> binCommands(c)
    }
    if (!c.volumeWritable) offered = offered - FILE_CHANGES
    if (c.restricted) offered = offered - FOLDER_WRITES
    return offered
}

private fun homeCommands(c: CommandContext): Set<Command> = buildSet {
    add(REFRESH)
    add(OPTIONS)
    if (c.selection.isNotEmpty()) add(OPEN)
}

private fun binCommands(c: CommandContext): Set<Command> = buildSet {
    add(REFRESH)
    add(OPTIONS)
    if (c.hasEntries) addAll(listOf(SELECT_ALL, INVERT))
    if (!c.binEmpty) add(EMPTY_BIN)
    if (c.selection.isNotEmpty()) addAll(listOf(RESTORE, DELETE_PERMANENT, SELECT_NONE))
}

private fun folderCommands(c: CommandContext): Set<Command> = buildSet {
    val inFolder = c.kind == LocationKind.FOLDER
    val selection = c.selection
    add(REFRESH)
    add(OPTIONS)
    if (c.hasEntries) addAll(listOf(SELECT_ALL, INVERT))
    if (inFolder) {
        addAll(listOf(NEW_FOLDER, NEW_TEXT, PROPERTIES))
        if (!c.clipboard?.paths.isNullOrEmpty()) add(PASTE)
    }
    if (selection.isEmpty()) {
        if (inFolder) add(pinOrUnpin(c.pinned))
        return@buildSet
    }
    addAll(listOf(OPEN, CUT, COPY, DELETE, DELETE_PERMANENT, COMPRESS, PROPERTIES, SELECT_NONE))
    val single = selection.singleOrNull()
    if (single != null) add(RENAME)
    if (single?.kind == EntryKind.FILE) {
        add(OPEN_WITH)
        if (single.name.endsWith(".zip", ignoreCase = true)) add(EXTRACT)
    }
    if (selection.all { it.kind == EntryKind.FILE }) add(SHARE)
    if (selection.all { it.kind == EntryKind.DIR }) add(pinOrUnpin(c.pinned))
}

private fun pinOrUnpin(pinned: Boolean): Command = if (pinned) UNPIN else PIN
