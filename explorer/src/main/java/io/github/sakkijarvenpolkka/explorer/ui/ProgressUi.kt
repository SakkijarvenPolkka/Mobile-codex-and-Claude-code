/*
 * Files — how the file operations show while they run and once they end: the progress dialog, its
 * chip in the status bar, and the list of the items an operation could not do.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpProgress
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.RunningOp
import io.github.sakkijarvenpolkka.explorer.state.errorText
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentProgressBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import io.github.sakkijarvenpolkka.explorer.util.text
import java.nio.file.Paths
import kotlin.math.roundToInt

/** How many failures [FailuresDialog] lists; it says how many there are in all. */
private const val MAX_LISTED = 100

/**
 * The operation [op] as it runs: what it does to how many items, the name it is at, a progress bar
 * (by bytes, else by items) and how many items and bytes are left. "Run in background" and Back call
 * [onBackground] (the progress goes on as a chip of the status bar); Cancel calls [onCancel].
 */
@Composable
fun ProgressDialog(op: RunningOp, onBackground: () -> Unit, onCancel: () -> Unit) {
    val progress = op.progress
    val korean = LocalConfiguration.current.locales[0].language == "ko"
    ContentDialog(
        title = titleOf(op),
        onDismiss = onBackground,
        primary = null,
        secondary = stringResource(R.string.progress_background) to onBackground,
        close = stringResource(R.string.cancel) to onCancel,
        // The name changes with each file.
        fullWidth = true,
    ) {
        Column(Modifier.fillMaxWidth().testTag(Tags.PROGRESS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            progress?.currentName?.let { Name(DisplayName.of(it)) }
            FluentProgressBar(shareDone(progress), Modifier.fillMaxWidth())
            if (progress != null) {
                val items = (progress.totalItems - progress.doneItems).coerceAtLeast(0)
                val bytes = Formats.size((progress.totalBytes - progress.doneBytes).coerceAtLeast(0), korean)
                Secondary(stringResource(R.string.progress_remaining, items, bytes))
            }
        }
    }
}

/** The operation [op] in the status bar while it runs in the background: a small bar; [onClick] shows its dialog. */
@Composable
fun ProgressChip(op: RunningOp, onClick: () -> Unit) {
    val title = titleOf(op)
    val share = shareDone(op.progress)
    Row(
        Modifier
            .heightIn(min = MinTouchTarget)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = title }
            .testTag(Tags.PROGRESS_CHIP)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluentProgressBar(share, Modifier.width(48.dp))
        if (share != null) Secondary("${(share * 100).roundToInt()}%")
    }
}

/**
 * The items [op] could not do, each by its name and why, the first [MAX_LISTED] of them; one copied whose
 * source could not be deleted says so. "Retry failed items" calls [onRetry], when there are any to retry
 * (such a one is not copied again); Close and Back call [onDismiss].
 */
@Composable
fun FailuresDialog(op: FinishedOp, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val failed = (op.result as? OpResult.Done)?.failed.orEmpty()
    val retry = stringResource(R.string.retry_failed) to onRetry
    ContentDialog(
        title = stringResource(R.string.op_done_failed, failed.size),
        onDismiss = onDismiss,
        primary = retry.takeIf { failed.any { !it.copiedButSourceKept } },
        close = stringResource(R.string.close) to onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().testTag(Tags.FAILURES), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (failure in failed.take(MAX_LISTED)) {
                Column {
                    Name(DisplayName.of(Paths.get(failure.path).fileName?.toString() ?: failure.path))
                    if (failure.copiedButSourceKept) Secondary(stringResource(R.string.failure_source_kept))
                    Secondary(errorText(failure.error).text())
                }
            }
            if (failed.size > MAX_LISTED) Secondary("…")
        }
    }
}

/** "Copying 3 items": what [op] does, to how many items (its sources until it has counted them). */
@Composable
private fun titleOf(op: RunningOp): String =
    stringResource(titleOf(op.spec.kind), op.progress?.totalItems ?: op.spec.sources.size)

@StringRes
private fun titleOf(kind: OpKind): Int = when (kind) {
    OpKind.COPY -> R.string.progress_title_copy
    OpKind.MOVE, OpKind.RESTORE -> R.string.progress_title_move
    OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.EMPTY_BIN -> R.string.progress_title_delete
    OpKind.COMPRESS -> R.string.progress_title_compress
    OpKind.EXTRACT -> R.string.progress_title_extract
}

/** How much of the work is done, by bytes, else by items; null while that is not known. */
private fun shareDone(progress: OpProgress?): Float? = when {
    progress == null -> null
    progress.totalBytes > 0 -> progress.doneBytes.toFloat() / progress.totalBytes
    progress.totalItems > 0 -> progress.doneItems.toFloat() / progress.totalItems
    else -> null
}
