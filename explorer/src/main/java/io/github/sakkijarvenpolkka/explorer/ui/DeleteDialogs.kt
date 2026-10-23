/*
 * Files — the yes-or-no questions of the file operations: deleting for good (asked, a Recycle Bin
 * that cannot take the items, emptying it), skipping what a drive cannot store, and changing a file's
 * extension.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.state.ConfirmRequest
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme

/**
 * Asks [request]; Yes calls [onAnswer] with true, No and Back with false. Deleting for good always
 * says how many items and that it cannot be undone.
 */
@Composable
fun ConfirmDialog(request: ConfirmRequest, onAnswer: (Boolean) -> Unit) {
    val (title, body) = when (request) {
        is ConfirmRequest.PermanentDelete ->
            R.string.delete_permanent_title to stringResource(R.string.delete_permanent_body, request.count)
        is ConfirmRequest.BinUnavailable -> R.string.delete_permanent_title to stringResource(R.string.bin_unavailable)
        is ConfirmRequest.EmptyBin -> R.string.cmd_empty_bin to stringResource(R.string.empty_bin_body, request.count)
        is ConfirmRequest.SkipUnstorable -> R.string.cmd_paste to stringResource(R.string.precheck_skip, request.count)
        is ConfirmRequest.ExtensionChange -> R.string.rename_title to stringResource(R.string.ext_change)
    }
    ContentDialog(
        title = stringResource(title),
        onDismiss = { onAnswer(false) },
        primary = stringResource(R.string.yes) to { onAnswer(true) },
        close = stringResource(R.string.no) to { onAnswer(false) },
    ) {
        BasicText(body, style = FluentTheme.type.body.copy(color = FluentTheme.colors.text))
    }
}
