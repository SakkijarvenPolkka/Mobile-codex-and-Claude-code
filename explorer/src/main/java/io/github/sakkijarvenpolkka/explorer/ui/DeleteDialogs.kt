/*
 * Files — the yes-or-no questions of the file operations: deleting for good (asked, a Recycle Bin
 * that cannot take the items, emptying it), skipping what a drive cannot store, and changing a file's
 * extension.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.state.ConfirmRequest
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme

/**
 * Asks [request]; Yes calls [onAnswer] with true, No and Back with false. Deleting for good always
 * says how many items and that it cannot be undone, once: in one sentence when it is asked
 * ([R.string.delete_permanent_body]), else after what led to it (a Recycle Bin that cannot take the
 * items, emptying it), followed by [R.string.cannot_undo].
 */
@Composable
fun ConfirmDialog(request: ConfirmRequest, onAnswer: (Boolean) -> Unit) {
    val (title, body) = when (request) {
        is ConfirmRequest.PermanentDelete ->
            R.string.delete_permanent_title to listOf(stringResource(R.string.delete_permanent_body, request.count))
        is ConfirmRequest.BinUnavailable -> R.string.delete_permanent_title to listOf(
            stringResource(R.string.bin_unavailable, request.paths.size),
            stringResource(R.string.cannot_undo),
        )
        is ConfirmRequest.EmptyBin -> R.string.cmd_empty_bin to listOf(
            stringResource(R.string.empty_bin_body, request.count),
            stringResource(R.string.cannot_undo),
        )
        is ConfirmRequest.SkipUnstorable ->
            R.string.cmd_paste to listOf(stringResource(R.string.precheck_skip, request.count))
        is ConfirmRequest.ExtensionChange -> R.string.rename_title to listOf(stringResource(R.string.ext_change))
    }
    ContentDialog(
        title = stringResource(title),
        onDismiss = { onAnswer(false) },
        primary = stringResource(R.string.yes) to { onAnswer(true) },
        close = stringResource(R.string.no) to { onAnswer(false) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = FluentTheme.type.body.copy(color = FluentTheme.colors.text)
            body.forEach { BasicText(it, style = style) }
        }
    }
}
