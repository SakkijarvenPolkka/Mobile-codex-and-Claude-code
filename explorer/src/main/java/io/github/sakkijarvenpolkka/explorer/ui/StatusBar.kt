/*
 * Files — the status bar: item count, selection, its size, the progress of the operations in the
 * background, and quick view switches.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.ViewMode
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.ops.RunningOp
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.ListingState
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.Formats

/**
 * "N items · M selected  size" and, at the end, the chip of the operation [running] in the
 * background ([ExplorerUiState.progressInBackground]; [onShowProgress] shows its dialog again) and
 * the details and large icons switches ([onSetMode]). The size of the selection shows only when it
 * holds files only; with a folder in it the bar reads "N items selected".
 */
@Composable
fun StatusBar(
    ui: ExplorerUiState,
    onSetMode: (ViewMode) -> Unit,
    modifier: Modifier = Modifier,
    running: RunningOp? = null,
    onShowProgress: () -> Unit = {},
) {
    val listed = ui.listing is ListingState.Loaded || ui.listing is ListingState.Streaming
    val selected = ui.selected
    val korean = LocalConfiguration.current.locales[0].language == "ko"
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (listed) {
                val count = ui.rows.size
                StatusText(pluralStringResource(R.plurals.items_count, count, count), Tags.STATUS_ITEMS)
            }
            if (selected.isNotEmpty()) {
                val filesOnly = selected.all { it.kind == EntryKind.FILE }
                StatusText("·", tag = null)
                val count = selected.size
                val text = if (filesOnly) {
                    stringResource(R.string.selected_count, count)
                } else {
                    pluralStringResource(R.plurals.selected_items, count, count)
                }
                StatusText(text, Tags.STATUS_SELECTION)
                if (filesOnly) StatusText(Formats.size(selected.sumOf { it.size }, korean), Tags.STATUS_SIZE)
            }
        }
        if (running != null && ui.progressInBackground) ProgressChip(running, onShowProgress)
        val mode = ui.viewSetting.mode
        val details = stringResource(R.string.view_details)
        ViewSwitch(FluentIcons.Menu, details, mode == ViewMode.DETAILS, Tags.STATUS_DETAILS) {
            onSetMode(ViewMode.DETAILS)
        }
        val large = stringResource(R.string.view_large)
        ViewSwitch(FluentIcons.View, large, mode == ViewMode.LARGE_ICONS, Tags.STATUS_LARGE_ICONS) {
            onSetMode(ViewMode.LARGE_ICONS)
        }
    }
}

@Composable
private fun StatusText(text: String, tag: String?) {
    BasicText(
        text,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        style = FluentTheme.type.caption.copy(color = FluentTheme.colors.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A quick view switch: outlined while its view is shown. */
@Composable
private fun ViewSwitch(icon: ImageVector, label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    FluentButton(
        text = null,
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { selected = on },
        icon = icon,
        style = if (on) ButtonStyle.STANDARD else ButtonStyle.SUBTLE,
        contentDescription = label,
    )
}
