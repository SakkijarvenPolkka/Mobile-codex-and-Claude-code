/*
 * Audacity Android port — File ▸ Share Audio...: a quick format (WAV 16-bit,
 * MP3 192 kbps, M4A AAC when the device has the encoder) and the range
 * (selection or the whole project), then the Android share sheet
 * (share/ShareAudio.kt). The full Export dialog stays for every other choice.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.share.ShareAudio
import io.github.sakkijarvenpolkka.audacity.share.ShareFormat
import io.github.sakkijarvenpolkka.audacity.ui.AppDialogFrame
import io.github.sakkijarvenpolkka.audacity.ui.SectionHeader
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import kotlinx.coroutines.CancellationException

@Composable
fun ShareDialog(d: AppDialog, vm: AppViewModel) {
    val snapshot by vm.engine.snapshot.collectAsState()
    var formats by remember { mutableStateOf<List<ShareFormat>?>(null) }
    var formatName by rememberSaveable { mutableStateOf(vm.uiPrefs.value.lastShareFormat) }
    val sel = snapshot.selection
    // ExportUtils::HasSelectedAudio: a time selection on selected wave tracks (the engine decides at export)
    val hasSelection = sel.t1 > sel.t0 && snapshot.has(CommandFlags.TS) && snapshot.has(CommandFlags.WS)
    var selectionOnly by rememberSaveable { mutableStateOf(hasSelection) }

    LaunchedEffect(Unit) {
        try {
            val list = ShareAudio.available(vm.engine.exportFormats())
            formats = list
            if (list.none { it.name == formatName }) formatName = list.firstOrNull()?.name ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.reportError(e)
            vm.dismiss(d)
        }
    }

    val format = formats?.firstOrNull { it.name == formatName }
    AppDialogFrame(
        title = stringResource(R.string.share_title),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) }
            Button(
                enabled = format != null,
                onClick = {
                    val f = format ?: return@Button
                    vm.dismiss(d)
                    vm.shareAudio(f, selectionOnly && hasSelection)
                },
                modifier = Modifier.testTag("share:run"),
            ) { Text(stringResource(R.string.share_button)) }
        },
    ) {
        val list = formats
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppDialogFrame
        }
        SectionHeader(stringResource(R.string.ex_format))
        Column(Modifier.selectableGroup()) {
            for (f in list) {
                RadioRow(stringResource(f.label), f.name == formatName, tag = "share:format:${f.name}") { formatName = f.name }
            }
        }
        SectionHeader(stringResource(R.string.ex_range))
        Column(Modifier.selectableGroup()) {
            RadioRow(
                stringResource(R.string.share_range_selection, TimeCodec.format(maxOf(0.0, sel.t1 - sel.t0))),
                selectionOnly && hasSelection, enabled = hasSelection, tag = "share:range:selection",
            ) { selectionOnly = true }
            RadioRow(stringResource(R.string.ex_range_project), !(selectionOnly && hasSelection), tag = "share:range:project") {
                selectionOnly = false
            }
        }
        Text(
            stringResource(R.string.share_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
