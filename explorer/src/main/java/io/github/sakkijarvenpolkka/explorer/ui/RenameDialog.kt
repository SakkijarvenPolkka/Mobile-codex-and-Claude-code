/*
 * Files — the rename dialog: the name to edit, its stem selected, and what is wrong with it shown
 * while typing.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.NameCheck
import io.github.sakkijarvenpolkka.explorer.fs.NameProblem
import io.github.sakkijarvenpolkka.explorer.fs.NameValidator
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.state.RenameError
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTextBox
import io.github.sakkijarvenpolkka.explorer.util.UiText
import io.github.sakkijarvenpolkka.explorer.util.extensionStart
import io.github.sakkijarvenpolkka.explorer.util.text

/**
 * Renames [entry]: its name in a text box, focused, with a file's stem selected (all of a folder's
 * name, and of a name without an extension, such as one starting with a dot). What is wrong with the
 * name, by the rules of its volume ([traits], [NameValidator]), shows while typing, and OK is enabled
 * only without it; OK and Done call [onCommit] with the name as typed, Cancel and Back [onDismiss].
 * Why renaming to a name failed ([failure]) shows while that name is in the box; the dialog stays open
 * with what was typed until the rename is done.
 */
@Composable
fun RenameDialog(
    entry: FileEntry,
    traits: VolumeTraits,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
    failure: RenameError? = null,
) {
    var value by remember(entry) { mutableStateOf(TextFieldValue(entry.name, preselection(entry))) }
    val problem = (NameValidator.validate(value.text, traits) as? NameCheck.Bad)?.problem
    val commit = { if (problem == null) onCommit(value.text) }
    ContentDialog(
        title = stringResource(R.string.rename_title),
        onDismiss = onDismiss,
        primary = stringResource(R.string.ok) to commit,
        primaryEnabled = problem == null,
        close = stringResource(R.string.cancel) to onDismiss,
    ) {
        val focus = remember { FocusRequester() }
        FluentTextBox(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(Tags.RENAME_FIELD),
            onSubmit = commit,
            error = problem?.let { nameProblemText(it, traits).text() }
                ?: failure?.takeIf { it.name == value.text }?.error?.text(),
        )
        LaunchedEffect(entry) { focus.requestFocus() }
    }
}

/** What of [entry]'s name is selected at first: the stem of a file's, all of anything else's. */
internal fun preselection(entry: FileEntry): TextRange {
    val name = entry.name
    val dot = if (entry.kind == EntryKind.DIR) -1 else extensionStart(name)
    return TextRange(0, if (dot < 0) name.length else dot)
}

/**
 * What is wrong with a name being typed, for a volume with [traits]: the characters FAT refuses (on
 * such a volume, a slash among them), an empty or too long name, or any other name that cannot be used.
 */
internal fun nameProblemText(problem: NameProblem, traits: VolumeTraits): UiText = UiText.res(
    when (problem) {
        is NameProblem.FatChar -> R.string.name_fat_chars
        NameProblem.Slash -> if (traits.fatNames) R.string.name_fat_chars else R.string.name_invalid
        NameProblem.Empty -> R.string.name_empty
        NameProblem.TooLong -> R.string.name_too_long
        NameProblem.DotOrDotDot, NameProblem.ControlChar, NameProblem.BidiControl,
        NameProblem.TrailingDotOrSpace, NameProblem.NotAllowedByVolume,
        -> R.string.name_invalid
    },
)
