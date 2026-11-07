/*
 * Files — the yes-or-no questions of the file operations: deleting for good (asked, a Recycle Bin
 * that cannot take the items, emptying it), skipping what a drive cannot store, and changing a file's
 * extension; and how a question, or a conflict, takes its answers only once it has been seen.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.state.ConfirmRequest
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import kotlinx.coroutines.delay

/**
 * How long the answers of a question stay disabled once it shows: the second tap of a double tap, meant for
 * the question before, is not taken for an answer to the one that took its place unseen.
 */
internal const val QUESTION_ARMING_MS = 300L

/**
 * [content], the dialog of [question], composed anew for each question object, even one equal to the one before
 * it (the same deletion asked twice): its answers wait [QUESTION_ARMING_MS] again ([rememberArmed]).
 */
@Composable
internal fun Asking(question: Any, content: @Composable () -> Unit) = key(SameObject(question)) { content() }

/**
 * Whether the question composed here has shown for [QUESTION_ARMING_MS], so that its answers may be taken; without
 * [arming] (as it is when this first composes), at once.
 */
@Composable
internal fun rememberArmed(arming: Boolean = true): Boolean {
    var armed by remember { mutableStateOf(!arming) }
    LaunchedEffect(Unit) {
        if (armed) return@LaunchedEffect
        delay(QUESTION_ARMING_MS)
        armed = true
    }
    return armed
}

/** A key equal only to one for the very same object, where an equal one is another question. */
private class SameObject(private val of: Any) {
    override fun equals(other: Any?): Boolean = other is SameObject && other.of === of

    override fun hashCode(): Int = System.identityHashCode(of)
}

/**
 * Asks [request]; Yes calls [onAnswer] with true, No and Back with false (Restart and Cancel, for starting
 * the app again). Its answers are disabled for [QUESTION_ARMING_MS] once it shows ([rememberArmed]); Back
 * answers no at once. Deleting for good always says how many items and that it cannot be undone, once: in
 * one sentence when it is asked ([R.string.delete_permanent_body]), else after what led to it (a Recycle
 * Bin that cannot take the items, emptying it), followed by [R.string.cannot_undo].
 */
@Composable
fun ConfirmDialog(request: ConfirmRequest, onAnswer: (Boolean) -> Unit) {
    val armed = rememberArmed()
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
        ConfirmRequest.RestartBusy -> R.string.restart to listOf(stringResource(R.string.restart_busy))
    }
    val restarting = request == ConfirmRequest.RestartBusy
    val (yes, no) = if (restarting) R.string.restart to R.string.cancel else R.string.yes to R.string.no
    ContentDialog(
        title = stringResource(title),
        onDismiss = { onAnswer(false) },
        primary = stringResource(yes) to { onAnswer(true) },
        close = stringResource(no) to { onAnswer(false) },
        primaryEnabled = armed,
        closeEnabled = armed,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = FluentTheme.type.body.copy(color = FluentTheme.colors.text)
            body.forEach { BasicText(it, style = style) }
        }
    }
}
