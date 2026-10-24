/*
 * Files — the test tags of the shell's elements.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.state.Command
import java.util.Locale

/** Every `testTag` of the app's screens. Tags made from a file name use the raw name. */
object Tags {
    const val PERMISSION_SCREEN = "permission_screen"
    const val PERMISSION_BUTTON = "permission_button"

    const val SHELL = "shell"
    const val NAV_MENU = "nav_menu"
    const val BACK = "back"
    const val FORWARD = "forward"
    const val UP = "up"
    const val BREADCRUMB = "breadcrumb"
    const val ADDRESS_EDIT = "address_edit"
    const val COMMAND_BAR = "command_bar"
    const val NAV_PANE = "nav_pane"
    const val DRAWER = "drawer"
    const val MESSAGE = "message"
    const val RESTRICTED_BANNER = "restricted_banner"
    const val UNAVAILABLE = "unavailable"
    const val LISTING_ERROR = "listing_error"
    const val CHECK_STRIP = "check_strip"

    const val STATUS_ITEMS = "status_items"
    const val STATUS_SELECTION = "status_selection"
    const val STATUS_SIZE = "status_size"
    const val STATUS_DETAILS = "status_details"
    const val STATUS_LARGE_ICONS = "status_large_icons"

    const val CMD_NEW = "cmd_new"
    const val CMD_SORT = "cmd_sort"
    const val CMD_VIEW = "cmd_view"
    const val CMD_HIDDEN = "cmd_hidden"
    const val CMD_EXTENSIONS = "cmd_extensions"
    const val CMD_PREVIEW_PANE = "cmd_preview_pane"
    val CMD_DELETE = command(Command.DELETE)

    const val SORT_ASCENDING = "sort_ascending"
    const val SORT_DESCENDING = "sort_descending"
    const val SYSTEM_ACCENT = "system_accent"
    const val OPTION_HIDDEN = "option_hidden"
    const val OPTION_EXTENSIONS = "option_extensions"
    const val PROPERTIES = "properties"
    const val PROP_HIDDEN = "prop_hidden"

    const val RENAME_FIELD = "rename_field"
    const val APPLY_TO_ALL = "apply_to_all"
    const val PROGRESS = "progress"
    const val PROGRESS_CHIP = "progress_chip"
    const val FAILURES = "failures"
    const val RECYCLE_BIN = "recycle_bin"

    /** [c] in the command bar or its "⋯" menu. */
    fun command(c: Command): String = "cmd_${c.name.lowercase(Locale.ROOT)}"

    /** [c] in an item's context menu. */
    fun contextCommand(c: Command): String = "ctx_${c.name.lowercase(Locale.ROOT)}"

    /** The choice of [mode] in the View menu. */
    fun view(mode: ViewMode): String = "view_${mode.name.lowercase(Locale.ROOT)}"

    /** The choice of [key] in the Sort menu. */
    fun sort(key: SortKey): String = "sort_${key.name.lowercase(Locale.ROOT)}"

    /** The column header that sorts by [key] (details view). */
    fun header(key: SortKey): String = "header_${key.name.lowercase(Locale.ROOT)}"

    /** The thumbnail drawn for the item [name] in the tiles and large icons views. */
    fun thumbnail(name: String): String = "thumbnail_$name"

    /** The list or grid of the folder's items in [mode]. */
    fun fileList(mode: ViewMode): String = "file_list_${mode.name.lowercase(Locale.ROOT)}"

    /** The row or tile of the item [name]. */
    fun row(name: String): String = "row:$name"

    /** The "⋯" button of the item [name]. */
    fun rowMore(name: String): String = "row_more:$name"

    /** The subfolder [name] in the menu of a breadcrumb chevron. */
    fun crumbChild(name: String): String = "crumb_child:$name"

    /** The theme [choice] in the options dialog. */
    fun theme(choice: ThemeChoice): String = "theme_${choice.name.lowercase(Locale.ROOT)}"
}
