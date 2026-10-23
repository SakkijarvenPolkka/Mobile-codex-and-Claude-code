/*
 * Files — the Recycle Bin: the items of every volume's bin, with where they were deleted from and
 * when.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.RowExtra
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ListRow
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import java.time.ZoneId

private val LOCATION_COLUMN = 220.dp
private val DATE_COLUMN = 168.dp
private val SIZE_COLUMN = 88.dp

/**
 * The Recycle Bin's rows of [ui]: each item's name, original location (its folder, or "Original
 * location unknown"), date deleted and size, as columns under their headers on [WidthClass.EXPANDED],
 * else on a second and third line. Tapping or long-pressing a row selects it ([onTap], [onLongPress]);
 * the command bar restores, deletes for good and empties.
 */
@Composable
fun RecycleBinView(
    ui: ExplorerUiState,
    widthClass: WidthClass,
    onTap: (FileEntry) -> Unit,
    onLongPress: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = ui.tabs.active.selection
    val locale = LocalConfiguration.current.locales[0]
    val korean = locale.language == "ko"
    val zone = ZoneId.systemDefault()
    val unknown = stringResource(R.string.bin_unknown)
    val modeLabel = stringResource(R.string.selection_mode_on)
    val expanded = widthClass == WidthClass.EXPANDED
    Column(modifier.fillMaxSize().testTag(Tags.RECYCLE_BIN)) {
        if (expanded) Header()
        LazyColumn(Modifier.weight(1f).semantics { if (selection.active) stateDescription = modeLabel }) {
            items(ui.rows, key = { it.entry.path.toString() }) { row ->
                val entry = row.entry
                val info = row.extra as? RowExtra.BinInfo
                val selected = entry.path in selection.items
                val location = info?.originalPath?.parent?.let { DisplayName.of(it.toString()) } ?: unknown
                val date = info?.let { Formats.date(it.deletedAt, locale, zone) }.orEmpty()
                val size = Formats.size(entry.size, korean)
                ListRow(
                    selected = selected,
                    selectionMode = selection.active,
                    onClick = { onTap(entry) },
                    onLongClick = { onLongPress(entry) },
                    modifier = Modifier.testTag(Tags.row(entry.name)),
                ) {
                    if (selection.active) {
                        CheckMark(selected)
                        Spacer(Modifier.width(14.dp))
                    }
                    EntryIcon(entry, Modifier)
                    Spacer(Modifier.width(12.dp))
                    if (expanded) {
                        Name(DisplayName.of(entry.name), Modifier.weight(1f))
                        Secondary(location, Modifier.width(LOCATION_COLUMN).padding(start = 8.dp))
                        Secondary(date, Modifier.width(DATE_COLUMN).padding(start = 8.dp))
                        Secondary(size, Modifier.width(SIZE_COLUMN).padding(start = 8.dp), TextAlign.End)
                    } else {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Name(DisplayName.of(entry.name))
                            Secondary(location)
                            Secondary(listOf(date, size).filter { it.isNotEmpty() }.joinToString(" · "))
                        }
                    }
                }
            }
        }
    }
}

/** The column headers: Name, Original location, Date deleted, Size. */
@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Over the icons of the rows.
        Spacer(Modifier.width(36.dp))
        Secondary(stringResource(R.string.sort_name), Modifier.weight(1f))
        Secondary(stringResource(R.string.bin_original), Modifier.width(LOCATION_COLUMN).padding(start = 8.dp))
        Secondary(stringResource(R.string.bin_deleted), Modifier.width(DATE_COLUMN).padding(start = 8.dp))
        Secondary(stringResource(R.string.sort_size), Modifier.width(SIZE_COLUMN).padding(start = 8.dp), TextAlign.End)
    }
}
