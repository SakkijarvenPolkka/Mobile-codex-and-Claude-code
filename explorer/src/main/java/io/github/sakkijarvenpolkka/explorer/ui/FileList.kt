/*
 * Files — a folder's items in the four views (details, list, tiles, large icons), selection
 * by long press and by dragging along the check column; search results with the folder of each
 * and how the search goes.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.content.res.Resources
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import io.github.sakkijarvenpolkka.explorer.platform.Thumbnails
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.HistoryEntry
import io.github.sakkijarvenpolkka.explorer.state.ListingState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Row
import io.github.sakkijarvenpolkka.explorer.state.RowExtra
import io.github.sakkijarvenpolkka.explorer.state.TabAction
import io.github.sakkijarvenpolkka.explorer.state.TabId
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcon
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentProgressBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ListRow
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.ui.fluent.iconFor
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import io.github.sakkijarvenpolkka.explorer.util.extensionOf
import io.github.sakkijarvenpolkka.explorer.util.extensionStart
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale

/** Windows folder yellow, for folder icons in the file list and the navigation pane. */
internal val FolderTint = Color(0xFFFFC83D)

/**
 * Each tab's list and grid positions for the location it shows. A tab keeps its position while
 * it stays at that location; a list built for a location starts where [start] puts the tab's
 * history entry: where the view model last saw it scrolled there (so that recreating the
 * activity keeps it), else where the entry was left. The positions of closed tabs are dropped
 * ([retain]).
 */
@Stable
class ListPositions(private val start: (TabId, HistoryEntry) -> HistoryEntry) {
    private class Spot(val location: Location, val list: LazyListState, val grid: LazyGridState)

    private val spots = HashMap<TabId, Spot>()

    fun list(tab: TabId, entry: HistoryEntry): LazyListState = spot(tab, entry).list

    fun grid(tab: TabId, entry: HistoryEntry): LazyGridState = spot(tab, entry).grid

    /** Forgets the positions of the tabs not in [open], the tabs that closed. */
    fun retain(open: Set<TabId>) {
        spots.keys.retainAll(open)
    }

    private fun spot(tab: TabId, entry: HistoryEntry): Spot {
        spots[tab]?.takeIf { it.location == entry.location }?.let { return it }
        val from = start(tab, entry)
        val spot = Spot(
            entry.location,
            LazyListState(from.scrollIndex, from.scrollOffset),
            LazyGridState(from.scrollIndex, from.scrollOffset),
        )
        spots[tab] = spot
        return spot
    }
}

/** The sort after tapping the column [key]: the other direction of the same column, else [key] ascending. */
fun nextSort(current: SortSpec, key: SortKey): SortSpec =
    if (current.key == key) current.copy(ascending = !current.ascending) else SortSpec(key, ascending = true)

/**
 * The active tab's rows in its folder's view. Details shows the name, date modified, type and
 * size columns under headers that sort (on [WidthClass.COMPACT], the date and size on a second
 * line); list shows names only; tiles and large icons are grids, which show the thumbnails of
 * pictures, videos and songs from [thumbnails] (icons without). In selection mode the check
 * column (inset by the system gesture area) toggles a row when tapped and selects from the
 * anchor when dragged along. [contextMenu] is placed with the row of
 * [ExplorerUiState.contextMenuFor], so that it opens next to it. The item being renamed
 * ([ExplorerUiState.renaming], such as a new folder) is scrolled into view. Search results show the
 * folder each is in, under a line of how the search goes ([SearchStatus]); none found says so.
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
    thumbnails: Thumbnails? = null,
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
    val renamed = ui.renaming?.path?.let { path -> rows.indexOfFirst { it.entry.path == path } } ?: -1
    val internal = stringResource(R.string.volume_internal)
    val cell: @Composable (Row) -> Unit = { row ->
        Box {
            val selected = row.entry.path in selection.items
            val folder = (row.extra as? RowExtra.SearchInfo)?.let { folderPath(it.parentDir, ui.volumes, internal) }
            if (mode == ViewMode.DETAILS || mode == ViewMode.LIST) EntryRow(row.entry, selected, look, folder)
            else EntryTile(row.entry, selected, look, thumbnails, folder)
            if (ui.contextMenuFor?.path == row.entry.path) contextMenu()
        }
    }
    val search = ui.listing as? ListingState.Streaming
    val nothingFound = search?.done == true && rows.isEmpty()
    when (mode) {
        ViewMode.DETAILS, ViewMode.LIST -> {
            val state = positions.list(tab.id, tab.current)
            LaunchedEffect(state) {
                snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                    .collect { (index, offset) -> reportScroll(location, index, offset) }
            }
            LaunchedEffect(state, renamed) {
                if (renamed >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == renamed }) state.scrollToItem(renamed)
            }
            Column(modifier) {
                if (search != null) SearchStatus(search, ui.volumes)
                if (mode == ViewMode.DETAILS) {
                    DetailsHeader(ui.viewSetting.sort, widthClass, selection.active, gesturePad, onSort)
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(listModifier, state) {
                        items(rows, key = { it.entry.path.toString() }) { cell(it) }
                    }
                    if (nothingFound) NoResults()
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
            LaunchedEffect(state, renamed) {
                if (renamed >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == renamed }) state.scrollToItem(renamed)
            }
            Column(modifier) {
                if (search != null) SearchStatus(search, ui.volumes)
                Box(Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (mode == ViewMode.TILES) 220.dp else 104.dp),
                        modifier = listModifier,
                        state = state,
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        gridItems(rows, key = { it.entry.path.toString() }) { cell(it) }
                    }
                    if (nothingFound) NoResults()
                }
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

    fun type(entry: FileEntry): String = typeLabel(entry, res)
}

/** "File folder", "PDF File" (the extension upper-cased the same in every language), or "File". */
internal fun typeLabel(entry: FileEntry, res: Resources): String {
    val extension = extensionOf(entry.name).uppercase(Locale.ROOT)
    return when {
        entry.kind == EntryKind.DIR -> res.getString(R.string.type_folder)
        extension.isNotEmpty() -> res.getString(R.string.type_file_ext, DisplayName.of(extension))
        else -> res.getString(R.string.type_file)
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

/** [name] without its extension and the dot before it ([extensionStart]). */
private fun stem(name: String): String {
    val dot = extensionStart(name)
    return if (dot < 0) name else name.substring(0, dot)
}

/** A row of the details or list view; a search result's [folder] shows under its name. */
@Composable
private fun EntryRow(entry: FileEntry, selected: Boolean, look: Look, folder: String?) {
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
            look.mode == ViewMode.LIST -> NameIn(look.name(entry), folder, Modifier.weight(1f))
            look.widthClass == WidthClass.COMPACT -> Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Name(look.name(entry))
                Secondary(listOfNotNull(look.date(entry), look.size(entry)).joinToString(" · "))
                folder?.let { Secondary(it) }
            }
            else -> {
                NameIn(look.name(entry), folder, Modifier.weight(1f))
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

/**
 * An item of the tiles or large icons grid, with its thumbnail from [thumbnails] where it has one; a
 * search result's [folder] shows under its name.
 */
@Composable
private fun EntryTile(entry: FileEntry, selected: Boolean, look: Look, thumbnails: Thumbnails?, folder: String?) {
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
            EntryPicture(entry, thumbnails, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Name(look.name(entry))
                Secondary(look.type(entry))
                look.size(entry)?.let { Secondary(it) }
                folder?.let { Secondary(it) }
            }
        } else {
            Column(
                Modifier.weight(1f).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EntryPicture(entry, thumbnails, 48.dp)
                BasicText(
                    look.name(entry),
                    style = FluentTheme.type.body.copy(color = FluentTheme.colors.text, textAlign = TextAlign.Center),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                folder?.let { Secondary(it, align = TextAlign.Center) }
            }
        }
    }
}

/**
 * The thumbnail of [entry], [size] a side, while [thumbnails] has one for it: a cached one at once,
 * else loaded while the item is shown (an item scrolled away stops its load). Its icon otherwise.
 */
@Composable
private fun EntryPicture(entry: FileEntry, thumbnails: Thumbnails?, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    // Looked up once, not on every recomposition of the item.
    val cached = remember(thumbnails, entry, sizePx) { thumbnails?.cached(entry, sizePx) }
    val thumbnail by produceState(cached, thumbnails, entry, sizePx) {
        value = thumbnails?.get(entry, sizePx)
    }
    val bitmap = thumbnail
    if (bitmap == null) {
        EntryIcon(entry, Modifier.size(size))
    } else {
        Image(
            remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = null,
            modifier = Modifier.size(size).testTag(Tags.thumbnail(entry.name)),
            contentScale = ContentScale.Fit,
        )
    }
}

/** The icon of [entry]; folders in Windows folder yellow. */
@Composable
internal fun EntryIcon(entry: FileEntry, modifier: Modifier) {
    val tint = if (entry.kind == EntryKind.DIR) FolderTint else FluentTheme.colors.text
    FluentIcon(iconFor(entry), contentDescription = null, modifier = modifier, tint = tint)
}

/** [name] ([Name]), with the [folder] of a search result under it. */
@Composable
private fun NameIn(name: String, folder: String?, modifier: Modifier) {
    if (folder == null) return Name(name, modifier)
    Column(modifier.padding(vertical = 4.dp)) {
        Name(name)
        Secondary(folder)
    }
}

/**
 * The line over search results: while the search goes on, a bar and the folder it is in; then, or once
 * done, that only the first results show and how many folders could not be read.
 */
@Composable
private fun SearchStatus(search: ListingState.Streaming, volumes: List<Volume>) {
    val res = LocalResources.current
    val internal = stringResource(R.string.volume_internal)
    val current = search.currentDir?.takeIf { !search.done }
    val lines = listOfNotNull(
        current?.let { folderPath(it, volumes, internal) }?.let {
            res.getString(R.string.search_searching, DisplayName.inSentence(it))
        },
        res.getString(R.string.search_capped).takeIf { search.capped },
        res.getString(R.string.search_skipped, search.skippedDirs).takeIf { search.skippedDirs > 0 },
    )
    if (search.done && lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag(Tags.SEARCH_STATUS)) {
        if (!search.done) FluentProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        for (line in lines) Secondary(line, Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
}

/** In place of search results that found nothing. */
@Composable
private fun NoResults() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(
            stringResource(R.string.search_none),
            modifier = Modifier.testTag(Tags.SEARCH_NONE),
            style = FluentTheme.type.body.copy(color = FluentTheme.colors.textSecondary, textAlign = TextAlign.Center),
        )
    }
}

/** A name on one line, cut in the middle so that the extension stays visible. */
@Composable
internal fun Name(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier = modifier,
        style = FluentTheme.type.body.copy(color = FluentTheme.colors.text),
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

/** Secondary text of a row (a date, a size) on one line. */
@Composable
internal fun Secondary(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
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
internal fun CheckMark(checked: Boolean) {
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
