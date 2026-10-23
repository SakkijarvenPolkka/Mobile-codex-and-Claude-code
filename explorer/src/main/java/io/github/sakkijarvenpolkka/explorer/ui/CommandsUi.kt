/*
 * Files — the command bar with its New, Sort and View menus, and the context menu of an item.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.data.ViewSetting
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MenuFlyout

/**
 * New ▾, Cut, Copy, Paste, Rename, Share, Delete, Sort ▾ and View ▾, then "⋯" with the
 * selection commands, Delete permanently, Compress, Extract, Pin or Unpin, Properties,
 * Refresh, the hidden items and file name extensions switches and Options; in the Recycle Bin,
 * Restore, Delete permanently and Empty Recycle Bin, then "⋯" with the selection commands,
 * Refresh and Options. A command not in [ExplorerUiState.commands] is shown disabled; the
 * commands the window has no room for move into "⋯".
 */
@Composable
fun CommandsUi(
    ui: ExplorerUiState,
    onCommand: (Command) -> Unit,
    onSetView: (ViewSetting) -> Unit,
    onSetGlobal: ((GlobalSettings) -> GlobalSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val res = LocalResources.current
    var menu by remember { mutableStateOf<BarMenu?>(null) }
    val offered = ui.commands
    fun item(c: Command) =
        CommandItem(Tags.command(c), res.getString(c.label()), c.icon(), c in offered) { onCommand(c) }
    val canView = ui.location is Location.Folder && ui.unavailable == null
    val canCreate = NEW_COMMANDS.any { it in offered }
    val inBin = ui.location == Location.RecycleBin
    val primary = if (inBin) BIN_COMMANDS.map(::item) else listOf(
        CommandItem(Tags.CMD_NEW, res.getString(R.string.cmd_new), FluentIcons.Add, canCreate) {
            menu = BarMenu.NEW
        },
        item(Command.CUT),
        item(Command.COPY),
        item(Command.PASTE),
        item(Command.RENAME),
        item(Command.SHARE),
        item(Command.DELETE),
        CommandItem(Tags.CMD_SORT, res.getString(R.string.cmd_sort), FluentIcons.Sort, canView) { menu = BarMenu.SORT },
        CommandItem(Tags.CMD_VIEW, res.getString(R.string.cmd_view), FluentIcons.View, canView) { menu = BarMenu.VIEW },
    )
    val global = ui.global
    val pin = if (Command.UNPIN in offered) Command.UNPIN else Command.PIN
    val extensionsLabel = res.getString(R.string.cmd_extensions)
    val overflow = if (inBin) {
        (BIN_OVERFLOW + Command.OPTIONS).map(::item)
    } else {
        OVERFLOW_COMMANDS.map { item(if (it == Command.PIN) pin else it) } + listOf(
            CommandItem(Tags.CMD_HIDDEN, res.getString(R.string.cmd_hidden), checkIf(global.showHidden), true) {
                onSetGlobal { it.copy(showHidden = !it.showHidden) }
            },
            CommandItem(Tags.CMD_EXTENSIONS, extensionsLabel, checkIf(global.showExtensions), true) {
                onSetGlobal { it.copy(showExtensions = !it.showExtensions) }
            },
            item(Command.OPTIONS),
        )
    }
    Box(modifier) {
        CommandBar(primary, overflow, res.getString(R.string.more), Modifier.fillMaxWidth().testTag(Tags.COMMAND_BAR))
        // The menus open under the bar: the bar's buttons cannot hold them.
        val dismiss = { menu = null }
        MenuFlyout(menu == BarMenu.NEW, dismiss, NEW_COMMANDS.map(::item))
        MenuFlyout(menu == BarMenu.SORT, dismiss, sortItems(ui.viewSetting, { res.getString(it) }, onSetView))
        MenuFlyout(menu == BarMenu.VIEW, dismiss, viewItems(ui.viewSetting, { res.getString(it) }, onSetView))
    }
}

/**
 * The context menu of the selection [ExplorerUiState.contextMenuFor] belongs to: Open, Open
 * with, Cut, Copy, Rename, Share, Compress, Extract, Pin or Unpin, Properties and Delete, those
 * the selection offers.
 */
@Composable
fun EntryMenu(ui: ExplorerUiState, onCommand: (Command) -> Unit, onDismiss: () -> Unit) {
    val res = LocalResources.current
    val items = CONTEXT_COMMANDS.filter { it in ui.commands }.map { c ->
        CommandItem(Tags.contextCommand(c), res.getString(c.label()), c.icon(), enabled = true) { onCommand(c) }
    }
    MenuFlyout(expanded = true, onDismiss = onDismiss, items = items)
}

private enum class BarMenu { NEW, SORT, VIEW }

private val NEW_COMMANDS = listOf(Command.NEW_FOLDER, Command.NEW_TEXT)

private val OVERFLOW_COMMANDS = listOf(
    Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.DELETE_PERMANENT, Command.COMPRESS,
    Command.EXTRACT, Command.PIN, Command.PROPERTIES, Command.REFRESH,
)

private val BIN_COMMANDS = listOf(Command.RESTORE, Command.DELETE_PERMANENT, Command.EMPTY_BIN)

private val BIN_OVERFLOW = listOf(Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.REFRESH)

private val CONTEXT_COMMANDS = listOf(
    Command.OPEN, Command.OPEN_WITH, Command.CUT, Command.COPY, Command.RENAME, Command.SHARE, Command.COMPRESS,
    Command.EXTRACT, Command.PIN, Command.UNPIN, Command.PROPERTIES, Command.DELETE,
)

private fun checkIf(on: Boolean): ImageVector? = if (on) FluentIcons.Check else null

private fun sortItems(view: ViewSetting, string: (Int) -> String, onSetView: (ViewSetting) -> Unit): List<CommandItem> {
    val sort = view.sort
    val keys = SortKey.entries.map { key ->
        CommandItem(Tags.sort(key), string(sortLabel(key)), checkIf(sort.key == key), enabled = true) {
            onSetView(view.copy(sort = sort.copy(key = key)))
        }
    }
    val directions = listOf(true to Tags.SORT_ASCENDING, false to Tags.SORT_DESCENDING).map { (ascending, tag) ->
        val label = string(if (ascending) R.string.sort_asc else R.string.sort_desc)
        CommandItem(tag, label, checkIf(sort.ascending == ascending), enabled = true) {
            onSetView(view.copy(sort = SortSpec(sort.key, ascending)))
        }
    }
    return keys + directions
}

private fun viewItems(view: ViewSetting, string: (Int) -> String, onSetView: (ViewSetting) -> Unit): List<CommandItem> =
    ViewMode.entries.map { mode ->
        CommandItem(Tags.view(mode), string(viewLabel(mode)), checkIf(view.mode == mode), enabled = true) {
            onSetView(view.copy(mode = mode))
        }
    }

@StringRes
internal fun sortLabel(key: SortKey): Int = when (key) {
    SortKey.NAME -> R.string.sort_name
    SortKey.MODIFIED -> R.string.sort_modified
    SortKey.TYPE -> R.string.sort_type
    SortKey.SIZE -> R.string.sort_size
}

@StringRes
internal fun viewLabel(mode: ViewMode): Int = when (mode) {
    ViewMode.DETAILS -> R.string.view_details
    ViewMode.LIST -> R.string.view_list
    ViewMode.TILES -> R.string.view_tiles
    ViewMode.LARGE_ICONS -> R.string.view_large
}

@StringRes
private fun Command.label(): Int = when (this) {
    Command.OPEN -> R.string.cmd_open
    Command.OPEN_WITH -> R.string.cmd_open_with
    Command.NEW_FOLDER -> R.string.cmd_new_folder
    Command.NEW_TEXT -> R.string.cmd_new_text
    Command.CUT -> R.string.cmd_cut
    Command.COPY -> R.string.cmd_copy
    Command.PASTE -> R.string.cmd_paste
    Command.RENAME -> R.string.cmd_rename
    Command.SHARE -> R.string.cmd_share
    Command.DELETE -> R.string.cmd_delete
    Command.DELETE_PERMANENT -> R.string.cmd_delete_permanent
    Command.SELECT_ALL -> R.string.cmd_select_all
    Command.SELECT_NONE -> R.string.cmd_select_none
    Command.INVERT -> R.string.cmd_invert
    Command.COMPRESS -> R.string.cmd_compress
    Command.EXTRACT -> R.string.cmd_extract
    Command.PIN -> R.string.cmd_pin
    Command.UNPIN -> R.string.cmd_unpin
    Command.PROPERTIES -> R.string.cmd_properties
    Command.REFRESH -> R.string.cmd_refresh
    Command.RESTORE -> R.string.cmd_restore
    Command.EMPTY_BIN -> R.string.cmd_empty_bin
    Command.OPTIONS -> R.string.cmd_options
}

private fun Command.icon(): ImageVector? = when (this) {
    Command.NEW_FOLDER -> FluentIcons.Folder
    Command.NEW_TEXT -> FluentIcons.Text
    Command.CUT -> FluentIcons.Cut
    Command.COPY -> FluentIcons.Copy
    Command.PASTE -> FluentIcons.Paste
    Command.RENAME -> FluentIcons.Rename
    Command.SHARE -> FluentIcons.Share
    Command.DELETE, Command.DELETE_PERMANENT, Command.EMPTY_BIN -> FluentIcons.Delete
    Command.PIN, Command.UNPIN -> FluentIcons.Star
    Command.PROPERTIES -> FluentIcons.Info
    Command.REFRESH -> FluentIcons.Refresh
    Command.RESTORE -> FluentIcons.Restore
    Command.OPEN, Command.OPEN_WITH, Command.SELECT_ALL, Command.SELECT_NONE, Command.INVERT, Command.COMPRESS,
    Command.EXTRACT, Command.OPTIONS,
    -> null
}
