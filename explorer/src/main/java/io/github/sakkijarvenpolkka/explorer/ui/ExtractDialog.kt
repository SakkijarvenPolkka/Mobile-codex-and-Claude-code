/*
 * Files — the extract dialog of a ZIP archive: the folder its files go into, and the character set to
 * read its file names with (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.zipCharsets
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.ExtractRequest
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import java.nio.charset.Charset

/**
 * "Extract Compressed (Zipped) Folders" for [request]: the folder the files go into ([ExtractRequest.target],
 * from its drive among [volumes] down), and the character set to read the archive's file names with: Auto,
 * or one of those extracting knows ([zipCharsets]: UTF-8, Korean), the one the names were found to be in
 * chosen at first. Extract calls [onExtract] with the name of the set chosen (null for Auto, which finds it
 * again when it runs); Cancel and Back call [onDismiss].
 */
@Composable
fun ExtractDialog(
    request: ExtractRequest,
    volumes: List<Volume>,
    onExtract: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices: List<Charset?> = remember { listOf(null) + zipCharsets() }
    var chosen by remember(request) { mutableStateOf(choices.firstOrNull { it?.name() == request.detected }) }
    val colors = FluentTheme.colors
    ContentDialog(
        title = stringResource(R.string.extract_title),
        onDismiss = onDismiss,
        primary = stringResource(R.string.extract_button) to { onExtract(chosen?.name()) },
        close = stringResource(R.string.cancel) to onDismiss,
    ) {
        val body = FluentTheme.type.body.copy(color = colors.text)
        BasicText(stringResource(R.string.extract_to), style = body)
        Spacer(Modifier.height(4.dp))
        BasicText(
            folderPath(request.target, volumes, stringResource(R.string.volume_internal)),
            modifier = Modifier.testTag(Tags.EXTRACT_FOLDER),
            style = FluentTheme.type.body.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.height(16.dp))
        BasicText(
            stringResource(R.string.extract_encoding),
            modifier = Modifier.semantics { heading() },
            style = FluentTheme.type.bodyStrong.copy(color = colors.text),
        )
        Column(Modifier.selectableGroup()) {
            for (charset in choices) {
                RadioRow(stringResource(labelOf(charset)), chosen == charset, Tags.extractEncoding(charset?.name())) {
                    chosen = charset
                }
            }
        }
    }
}

/** What the choice of [charset] is called: Auto for none, else UTF-8 or the Korean code page. */
private fun labelOf(charset: Charset?): Int = when (charset) {
    null -> R.string.encoding_auto
    Charsets.UTF_8 -> R.string.encoding_utf8
    else -> R.string.encoding_korean
}
