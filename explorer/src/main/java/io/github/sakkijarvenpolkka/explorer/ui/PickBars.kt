/*
 * Files — the bars of the shell picking files for another app (pick spec §2.2): its title in place of
 * the tab strip, and Open and Cancel at the bottom when several files may be picked.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme

/** "Choose a file", or "Choose files" when [multiple] files may be picked, with Cancel ([onCancel]) at the end. */
@Composable
fun PickTitleBar(multiple: Boolean, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag(Tags.PICK_TITLE_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(if (multiple) R.string.pick_title_multiple else R.string.pick_title),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = FluentTheme.type.subtitle.copy(color = FluentTheme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FluentButton(
            stringResource(R.string.cancel),
            onCancel,
            Modifier.testTag(Tags.PICK_CANCEL),
            style = ButtonStyle.SUBTLE,
        )
    }
}

/**
 * The end of picking several files: "Open (N)" for the [count] files chosen, the accent button, which calls
 * [onOpen] once there is one; and Cancel, which calls [onCancel].
 */
@Composable
fun PickBottomBar(count: Int, onOpen: () -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(FluentTheme.colors.layer)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(Tags.PICK_BOTTOM_BAR),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluentButton(
            stringResource(R.string.pick_open_count, count),
            onOpen,
            Modifier.testTag(Tags.PICK_OPEN),
            style = ButtonStyle.ACCENT,
            enabled = count >= 1,
        )
        FluentButton(stringResource(R.string.cancel), onCancel)
    }
}
