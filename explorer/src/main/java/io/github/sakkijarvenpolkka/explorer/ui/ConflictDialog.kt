/*
 * Files — the dialog that asks what to do about an item whose name the destination already has.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.Conflict
import io.github.sakkijarvenpolkka.explorer.fs.ConflictAction
import io.github.sakkijarvenpolkka.explorer.fs.ConflictKind
import io.github.sakkijarvenpolkka.explorer.fs.Resolution
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.util.DisplayName

/**
 * Asks about [conflict]: "Merge folders" for two folders, else "Replace or Skip Files", with a button
 * for each of the [allowed] answers ([io.github.sakkijarvenpolkka.explorer.fs.allowedActions]), which
 * calls [onAnswer]. While [remaining] items of the operation are still to come, a check box gives the
 * answer to the conflicts of the same kind among them too (how many of them clash is not known, so it
 * says no number). Cancel and Back call [onCancel], which stops the operation.
 */
@Composable
fun ConflictDialog(
    conflict: Conflict,
    allowed: List<ConflictAction>,
    remaining: Int,
    onAnswer: (Resolution) -> Unit,
    onCancel: () -> Unit,
) {
    var toAll by remember(conflict) { mutableStateOf(false) }
    val title = if (conflict.kind == ConflictKind.DIR_DIR) R.string.conflict_folder_title else R.string.conflict_title
    ContentDialog(
        title = stringResource(title),
        onDismiss = onCancel,
        primary = null,
        close = stringResource(R.string.cancel) to onCancel,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                stringResource(R.string.conflict_body, DisplayName.inSentence(conflict.existing.name)),
                style = FluentTheme.type.body.copy(color = FluentTheme.colors.text),
            )
            for (action in allowed) {
                FluentButton(stringResource(labelOf(action)), { onAnswer(Resolution(action, toAll)) }, Modifier.fillMaxWidth())
            }
            if (remaining > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .toggleable(toAll, interactionSource = null, indication = null, role = Role.Checkbox) { toAll = it }
                        .testTag(Tags.APPLY_TO_ALL),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckMark(toAll)
                    BasicText(
                        stringResource(R.string.conflict_apply_all),
                        style = FluentTheme.type.body.copy(color = FluentTheme.colors.text),
                    )
                }
            }
        }
    }
}

@StringRes
private fun labelOf(action: ConflictAction): Int = when (action) {
    ConflictAction.REPLACE -> R.string.conflict_replace
    ConflictAction.SKIP -> R.string.conflict_skip
    ConflictAction.KEEP_BOTH -> R.string.conflict_keep_both
    ConflictAction.MERGE -> R.string.conflict_merge
}
