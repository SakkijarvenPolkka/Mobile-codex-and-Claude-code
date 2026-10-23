/*
 * Files — a folder's items in the four views (details, list, tiles, large icons), selection
 * by long press and by dragging along the check column.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.SortKey
import io.github.sakkijarvenpolkka.explorer.fs.SortSpec
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.HistoryEntry
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Row
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcon
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ListRow
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.ui.fluent.iconFor
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale

/** Windows folder yellow, for folder icons in the file list and the navigation pane. */
internal val FolderTint = Color(0xFFFFC83D)

/**
 * Each tab's list and grid positions for the location it shows. A tab keeps its position while
 * it stays at that location; a new location starts where its history entry was scrolled to.
 */
@Stable
class ListPositions {
    private class Spot(val location: Location, val list: LazyListState, val grid: LazyGridState)

    private val spots = HashMap<TabId, Spot>()

    fun list(tab: TabId, entry: HistoryEntry): LazyListState = spot(tab, entry).list

    fun grid(tab: TabId, entry: HistoryEntry): LazyGridState = spot(tab, entry).grid

    private fun spot(tab: TabId, entry: HistoryEntry): Spot {
        spots[tab]?.takeIf { it.location == entry.location }?.let { return it }
        val spot = Spot(
            entry.location,
            LazyListState(entry.scrollIndex, entry.scrollOffset),
            LazyGridState(entry.scrollIndex, entry.scrollOffset),
        )
        spots[tab] = spot
        return spot
    }
}

@Composable
fun rememberListPositions(): ListPositions = remember { ListPositions() }

/** The sort after tapping the column [key]: the other direction of the same column, else [key] ascending. */
fun nextSort(current: SortSpec, key: SortKey): SortSpec =
    if (current.key == key) current.copy(ascending = !current.ascending) else SortSpec(key, ascending = true)

/**
 * The active tab's rows in its folder's view. Details shows the name, date modified, type and
 * size columns under headers that sort (on [WidthClass.COMPACT], the date and size on a second
 * line); list shows names only; tiles and large icons are grids. In selection mode the check
 * column (inset by the system gesture area) toggles a row when tapped and selects from the
 * anchor when dragged along. [contextMenu] is placed with the row of
 * [ExplorerUiState.contextMenuFor], so that it opens next to it.
 */
@Composable
fun FileList(
    ui: ExplorerUiState,
    widthClass: WidthClass,
    positions: ListPositions,
    onTap: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    onMore: (FileEntry) -> Unit,
    onDispatch: (TabAction) -> Unit,
    onSort: (SortKey) -> Unit,
    onScrolled: (Location, Int, Int) -> Unit,
    contextMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab = ui.tabs.active
    val mode = ui.viewSetting.mode
    val selection = tab.selection
    val rows = ui.rows
    val gesturePad = WindowInsets.systemGestures.only(WindowInsetsSides.Start).asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current)
    val look = rememberLook(
        mode, widthClass, selection.active, ui.global.showExtensions, gesturePad, onTap, onLongPress, onMore,
    )
    val modeLabel = stringResource(R.string.selection_mode_on)
    val listModifier = Modifier
        .fillMaxSize()
        .testTag(Tags.fileList(mode))
        .semantics { if (selection.active) stateDescription = modeLabel }
    val reportScroll by rememberUpdatedState(onScrolled)
    val location = ui.location
    val cell: @Composable (Row) -> Unit = { row ->
        Box {
            val selected = row.entry.path in selection.items
            if (mode == ViewMode.DETAILS || mode == ViewMode.LIST) EntryRow(row.entry, selected, look)
            else EntryTile(row.entry, selected, look)
            if (ui.contextMenuFor?.path == row.entry.path) contextMenu()
        }
    }
    when (mode) {
        ViewMode.DETAILS, ViewMode.LIST -> {
            val state = positions.list(tab.id, tab.current)
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                    .collect { (index, offset) -> reportScroll(location, index, offset) }
            }
            Column(modifier) {
                if (mode == ViewMode.DETAILS) {
                    DetailsHeader(ui.viewSetting.sort, widthClass, selection.active, gesturePad, onSort)
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(listModifier, state) {
                        items(rows, key = { it.entry.path.toString() }) { cell(it) }
                    }
                    if (selection.active) {
                        CheckStrip(
                            state = state,
                            rows = rows,
                            onToggle = { onDispatch(TabAction.Toggle(it)) },
                            onRange = { path -> onDispatch(TabAction.RangeTo(path, rows.map { it.entry.path })) },
                            modifier = Modifier.padding(start = gesturePad).width(CHECK_COLUMN).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        ViewMode.TILES, ViewMode.LARGE_ICONS -> {
            val state = positions.grid(tab.id, tab.current)
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                    .collect { (index, offset) -> reportScroll(location, index, offset) }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (mode == ViewMode.TILES) 220.dp else 104.dp),
                modifier = modifier.then(listModifier),
                state = state,
                contentPadding = PaddingValues(4.dp),
            ) {
                gridItems(rows, key = { it.entry.path.toString() }) { cell(it) }
            }
        }
    }
}

/** The width of the check column, which also takes the drag that selects a range. */
private val CHECK_COLUMN = MinTouchTarget
private val DATE_COLUMN = 168.dp
private val TYPE_COLUMN = 128.dp
private val SIZE_COLUMN = 88.dp

/** How rows look and what touching them does; the same for every row of the list. */
private class Look(
    val mode: ViewMode,
    val widthClass: WidthClass,
    val selectionMode: Boolean,
    val showExtensions: Boolean,
    val gesturePad: Dp,
    val locale: Locale,
    val res: Resources,
    val onTap: (FileEntry) -> Unit,
    val onLongPress: (FileEntry) -> Unit,
    val onMore: (FileEntry) -> Unit,
) {
    private val korean = locale.language == "ko"
    private val zone = ZoneId.systemDefault()
    val moreLabel: String = res.getString(R.string.more)

    /** The shown name: without the extension of a file when extensions are hidden. */
    fun name(entry: FileEntry): String =
        DisplayName.of(if (!showExtensions && entry.kind == EntryKind.FILE) stem(entry.name) else entry.name)

    fun date(entry: FileEntry): String = Formats.date(entry.modified, locale, zone)

    /** The size of a file; folders have none. */
    fun size(entry: FileEntry): String? = if (entry.kind == EntryKind.FILE) Formats.size(entry.size, korean) else null

    fun type(entry: FileEntry): String {
        val extension = extension(entry.name)
        return when {
            entry.kind == EntryKind.DIR -> res.getString(R.string.type_folder)
            extension.isNotEmpty() -> res.getString(R.string.type_file_ext, DisplayName.of(extension.uppercase(locale)))
            else -> res.getString(R.string.type_file)
        }
    }
}

@Composable
private fun rememberLook(
    mode: ViewMode,
    widthClass: WidthClass,
    selectionMode: Boolean,
    showExtensions: Boolean,
    gesturePad: Dp,
    onTap: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    onMore: (FileEntry) -> Unit,
): Look {
    val locale = LocalConfiguration.current.locales[0]
    val res = LocalResources.current
    val keys = arrayOf(mode, widthClass, selectionMode, showExtensions, gesturePad, locale, res)
    return remember(*keys, onTap, onLongPress, onMore) {
        Look(mode, widthClass, selectionMode, showExtensions, gesturePad, locale, res, onTap, onLongPress, onMore)
    }
}

/** [name] without its extension (from the last dot that does not start it). */
private fun stem(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

private fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot + 1) else ""
}

@Composable
private fun EntryRow(entry: FileEntry, selected: Boolean, look: Look) {
    ListRow(
        selected = selected,
        selectionMode = look.selectionMode,
        onClick = { look.onTap(entry) },
        onLongClick = { look.onLongPress(entry) },
        modifier = Modifier.testTag(Tags.row(entry.name)),
    ) {
        if (look.selectionMode) {
            // Centred in the check column, which starts after the gesture area like the strip over it.
            Spacer(Modifier.width(look.gesturePad + 2.dp))
            CheckMark(selected)
            Spacer(Modifier.width(14.dp))
        }
        EntryIcon(entry, Modifier)
        Spacer(Modifier.width(12.dp))
        when {
            look.mode == ViewMode.LIST -> Name(look.name(entry), Modifier.weight(1f))
            look.widthClass == WidthClass.COMPACT -> Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Name(look.name(entry))
                Secondary(listOfNotNull(look.date(entry), look.size(entry)).joinToString(" · "))
            }
            else -> {
                Name(look.name(entry), Modifier.weight(1f))
                Secondary(look.date(entry), Modifier.width(DATE_COLUMN).padding(start = 8.dp))
                Secondary(look.type(entry), Modifier.width(TYPE_COLUMN).padding(start = 8.dp))
                Secondary(look.size(entry).orEmpty(), Modifier.width(SIZE_COLUMN).padding(start = 8.dp), TextAlign.End)
            }
        }
        FluentButton(
            text = null,
            onClick = { look.onMore(entry) },
            modifier = Modifier.testTag(Tags.rowMore(entry.name)),
            icon = FluentIcons.More,
            style = ButtonStyle.SUBTLE,
            contentDescription = look.moreLabel,
        )
    }
}

@Composable
private fun EntryTile(entry: FileEntry, selected: Boolean, look: Look) {
    ListRow(
        selected = selected,
        selectionMode = look.selectionMode,
        onClick = { look.onTap(entry) },
        onLongClick = { look.onLongPress(entry) },
        modifier = Modifier.testTag(Tags.row(entry.name)),
    ) {
        if (look.selectionMode) {
            CheckMark(selected)
            Spacer(Modifier.width(8.dp))
        }
        if (look.mode == ViewMode.TILES) {
            EntryIcon(entry, Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Name(look.name(entry))
                Secondary(look.type(entry))
                look.size(entry)?.let { Secondary(it) }
            }
        } else {
            Column(
                Modifier.weight(1f).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EntryIcon(entry, Modifier.size(48.dp))
                BasicText(
                    look.name(entry),
                    style = FluentTheme.type.body.copy(color = FluentTheme.colors.text, textAlign = TextAlign.Center),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EntryIcon(entry: FileEntry, modifier: Modifier) {
    val tint = if (entry.kind == EntryKind.DIR) FolderTint else FluentTheme.colors.text
    FluentIcon(iconFor(entry), contentDescription = null, modifier = modifier, tint = tint)
}

/** A name on one line, cut in the middle so that the extension stays visible. */
@Composable
private fun Name(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = FluentTheme.type.body.copy(color = FluentTheme.colors.text),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    BasicText(
        text,
        modifier = modifier,
        style = FluentTheme.type.caption.copy(color = FluentTheme.colors.textSecondary, textAlign = align),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The selection check box of a row: an accent box with a check mark when [checked], an outline otherwise. */
@Composable
private fun CheckMark(checked: Boolean) {
    val colors = FluentTheme.colors
    val shape = FluentTheme.shapes.control
    val box = if (checked) {
        Modifier.background(colors.accent, shape)
    } else {
        Modifier.border(1.dp, colors.textSecondary, shape)
    }
    Box(Modifier.size(20.dp).then(box), contentAlignment = Alignment.Center) {
        if (checked) FluentIcon(FluentIcons.Check, null, Modifier.size(14.dp), tint = colors.onAccent)
    }
}

/** The column headers of the details view; tapping one sorts by it, again the other way. */
@Composable
private fun DetailsHeader(
    sort: SortSpec,
    widthClass: WidthClass,
    selectionMode: Boolean,
    gesturePad: Dp,
    onSort: (SortKey) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // Over the check column and the icons of the rows.
        Spacer(Modifier.width((if (selectionMode) gesturePad + 36.dp else 0.dp) + 32.dp))
        HeaderCell(SortKey.NAME, sort, onSort, Modifier.weight(1f))
        val expanded = widthClass == WidthClass.EXPANDED
        HeaderCell(SortKey.MODIFIED, sort, onSort, if (expanded) Modifier.width(DATE_COLUMN) else Modifier)
        HeaderCell(SortKey.TYPE, sort, onSort, if (expanded) Modifier.width(TYPE_COLUMN) else Modifier)
        HeaderCell(SortKey.SIZE, sort, onSort, if (expanded) Modifier.width(SIZE_COLUMN) else Modifier)
        // Over the rows' "⋯" buttons.
        Spacer(Modifier.width(MinTouchTarget))
    }
}

@Composable
private fun HeaderCell(key: SortKey, sort: SortSpec, onSort: (SortKey) -> Unit, modifier: Modifier) {
    val colors = FluentTheme.colors
    val sorted = sort.key == key
    val direction = stringResource(if (sort.ascending) R.string.sort_asc else R.string.sort_desc)
    Row(
        modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interactionSource = null, indication = null, role = Role.Button) { onSort(key) }
            .semantics { if (sorted) stateDescription = direction }
            .testTag(Tags.header(key))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(sortLabel(key)),
            modifier = Modifier.weight(1f, fill = false),
            style = FluentTheme.type.caption.copy(color = colors.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sorted) {
            FluentIcon(
                FluentIcons.ChevronDown,
                contentDescription = null,
                modifier = Modifier.size(12.dp).rotate(if (sort.ascending) 180f else 0f),
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * Lies over the check column of the list in selection mode: a tap toggles the row under it,
 * a drag selects from the anchor to the row under the finger.
 */
@Composable
private fun CheckStrip(
    state: LazyListState,
    rows: List<Row>,
    onToggle: (Path) -> Unit,
    onRange: (Path) -> Unit,
    modifier: Modifier,
) {
    val currentRows by rememberUpdatedState(rows)
    val toggle by rememberUpdatedState(onToggle)
    val range by rememberUpdatedState(onRange)
    fun pathAt(y: Float): Path? = state.layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?.let { currentRows.getOrNull(it.index)?.entry?.path }
    Box(
        modifier
            .testTag(Tags.CHECK_STRIP)
            .pointerInput(state) { detectTapGestures { offset -> pathAt(offset.y)?.let(toggle) } }
            .pointerInput(state) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> pathAt(offset.y)?.let(range) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        pathAt(change.position.y)?.let(range)
                    },
                )
            },
    )
}
