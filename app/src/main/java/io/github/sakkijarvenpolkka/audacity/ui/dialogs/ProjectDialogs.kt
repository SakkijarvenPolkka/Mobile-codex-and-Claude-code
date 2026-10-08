/*
 * Audacity Android port — Metadata Editor, History, Resample, Audio Device
 * Info and Label Editor dialogs (wx dialogs of Audacity 3.7.9 src/:
 * TagsEditor.cpp, HistoryWindow.cpp, TrackMenus.cpp OnResample,
 * HelpMenus.cpp DeviceInfo, LabelDialog.cpp — the Audacity Team,
 * GPL-2.0-or-later).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryList
import io.github.sakkijarvenpolkka.audacity.engine.model.LatencyInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.menu.ContextMenus
import io.github.sakkijarvenpolkka.audacity.ui.AppDialogFrame
import io.github.sakkijarvenpolkka.audacity.ui.Dropdown
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Standard tags in TagsEditor order (lib-tags/Tags.h). */
private val STANDARD_TAGS = listOf(
    "ARTIST" to R.string.tag_artist, "TITLE" to R.string.tag_title, "ALBUM" to R.string.tag_album,
    "TRACKNUMBER" to R.string.tag_track, "YEAR" to R.string.tag_year, "GENRE" to R.string.tag_genre,
    "COMMENTS" to R.string.tag_comments,
)

private class TagRow(name: String, value: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
}

@Composable
fun TagsDialog(d: AppDialog, vm: AppViewModel) {
    val rows = remember { mutableStateListOf<TagRow>() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val tags = vm.engine.getTags()
            rows.clear()
            STANDARD_TAGS.forEach { (k, _) -> rows += TagRow(k, tags.firstOrNull { it.name.equals(k, true) }?.value ?: "") }
            tags.filter { t -> STANDARD_TAGS.none { it.first.equals(t.name, true) } }.forEach { rows += TagRow(it.name, it.value) }
            loaded = true
        } catch (e: CancellationException) {
            throw e   // the composition went away: keep the dialog
        } catch (e: Exception) {
            vm.reportError(e)
            vm.dismiss(d)
        }
    }
    AppDialogFrame(
        title = stringResource(R.string.tags_title),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            TextButton(onClick = { rows += TagRow("", "") }) { Text(stringResource(R.string.tags_add)) }
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) }
            Button(enabled = loaded, onClick = {
                val tags = rows.filter { it.name.isNotBlank() && it.value.isNotBlank() }.map { Tag(it.name.trim(), it.value) }
                vm.launchAction {
                    vm.engine.setTags(tags)
                    vm.dismiss(d)
                }
            }) { Text(stringResource(R.string.btn_ok)) }
        },
    ) {
        Text(stringResource(R.string.tags_hint), style = MaterialTheme.typography.bodySmall)
        rows.forEachIndexed { i, row ->
            val std = STANDARD_TAGS.firstOrNull { it.first == row.name }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (std != null) {
                    Text(stringResource(std.second), Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(row.name, { row.name = it }, Modifier.weight(0.4f), singleLine = true,
                        label = { Text(stringResource(R.string.tags_tag)) })
                }
                OutlinedTextField(row.value, { row.value = it }, Modifier.weight(0.6f), singleLine = true,
                    label = { Text(stringResource(R.string.tags_value)) })
                if (std == null) {
                    IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Filled.Delete, stringResource(R.string.btn_delete)) }
                }
            }
        }
    }
}

/** View ▸ History (src/HistoryWindow.cpp). */
@Composable
fun HistoryDialog(d: AppDialog, vm: AppViewModel) {
    var list by remember { mutableStateOf<HistoryList?>(null) }
    val snapshot by vm.engine.snapshot.collectAsState()
    var levels by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(snapshot.generation) {
        try {
            list = vm.engine.history()
        } catch (e: Exception) {
            vm.reportError(e)
        }
    }
    val l = list
    // Lazy list: a long session has thousands of undo states
    AppDialogFrame(
        title = stringResource(R.string.hist_title),
        onDismiss = { vm.dismiss(d) },
        buttons = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_close)) } },
        scrollable = false,
    ) {
        if (l == null) return@AppDialogFrame
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(R.string.hist_action), Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.hist_space), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(l.states, key = { it.index }) { s ->
                val current = s.index == l.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { vm.launchAction { vm.engine.historyGoto(s.index) } }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.description.ifEmpty { s.shortDescription }, Modifier.weight(1f),
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(TimeCodec.formatBytes(s.sizeBytes), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.hist_total, TimeCodec.formatBytes(l.states.sumOf { it.sizeBytes })), Modifier.padding(top = 8.dp))
        val maxDiscard = l.current
        if (maxDiscard >= 1) {
            if (levels > maxDiscard) levels = maxDiscard.toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hist_levels, levels.roundToInt()), Modifier.weight(1f))
                TextButton(onClick = {
                    val n = levels.roundToInt()
                    vm.launchAction { vm.engine.historyPurge(n) }
                }) { Text(stringResource(R.string.hist_discard)) }
            }
            if (maxDiscard > 1) {
                Slider(levels, { levels = it }, valueRange = 1f..maxDiscard.toFloat(), steps = (maxDiscard - 2).coerceAtLeast(0))
            }
        }
    }
}

/** Tracks ▸ Resample... */
@Composable
fun ResampleDialog(d: AppDialog, vm: AppViewModel) {
    val projectRate = remember { vm.engine.snapshot.value.project.rate.roundToInt() }
    var rate by rememberSaveable { mutableStateOf(projectRate.toString()) }
    val parsed = rate.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 }
    AppDialogFrame(
        title = stringResource(R.string.resample_title),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) }
            Button(enabled = parsed != null, onClick = {
                val r = parsed ?: return@Button
                vm.dismiss(d)
                vm.launchAction { vm.engine.resample(r) }
            }) { Text(stringResource(R.string.btn_ok)) }
        },
    ) {
        Dropdown(stringResource(R.string.resample_rate), ContextMenus.TRACK_RATES, parsed?.takeIf { it in ContextMenus.TRACK_RATES },
            { vm.string(R.string.rate_hz, it) }, { rate = it.toString() }, Modifier.fillMaxWidth())
        OutlinedTextField(
            rate, { rate = it }, Modifier.fillMaxWidth(), singleLine = true, isError = parsed == null,
            label = { Text(stringResource(R.string.cm_rate_label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** Help ▸ Diagnostics ▸ Audio Device Info... */
@Composable
fun DeviceInfoDialog(d: AppDialog, vm: AppViewModel) {
    var devices by remember { mutableStateOf<AudioDevices?>(null) }
    var latency by remember { mutableStateOf<LatencyInfo?>(null) }
    val info by vm.appInfo.collectAsState()
    LaunchedEffect(Unit) {
        try {
            devices = vm.engine.audioDevices()
            latency = vm.engine.latency()
        } catch (e: Exception) {
            vm.reportError(e)
        }
    }
    AppDialogFrame(
        title = stringResource(R.string.m_device_info).removeSuffix("..."),
        onDismiss = { vm.dismiss(d) },
        buttons = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_close)) } },
    ) {
        val text = buildString {
            info?.let { appendLine("Audacity ${it.audacityVersion} · engine ${it.engineVersion} · ${it.abi}") }
            if (vm.engine.isFake) appendLine(vm.string(R.string.about_fake_engine))
            devices?.let { dv ->
                appendLine("==============================")
                appendLine("Default playback: ${dv.current.output}")
                appendLine("Default recording: ${dv.current.input} (${dv.current.recordChannels} ch)")
                if (dv.pending) appendLine("A new device list is applied when the stream stops.")
                appendLine()
                dv.outputs.forEach { appendLine("Output #${it.index}: ${it.name} [${it.hostApi}] ${it.maxOutputChannels} ch, ${it.defaultRate.roundToInt()} Hz${if (it.isDefault) " (default)" else ""}") }
                dv.inputs.forEach { appendLine("Input #${it.index}: ${it.name} [${it.hostApi}] ${it.maxInputChannels} ch, ${it.defaultRate.roundToInt()} Hz${if (it.isDefault) " (default)" else ""}") }
            }
            latency?.let {
                appendLine()
                appendLine("Output latency: ${TimeCodec.number(it.outputLatencyMs, 1)} ms")
                appendLine("Input latency: ${TimeCodec.number(it.inputLatencyMs, 1)} ms")
                appendLine("Duplex offset: ${TimeCodec.number(it.duplexOffsetMs, 1)} ms${if (it.measured) " (measured)" else " (estimated)"}")
                appendLine("User latency trim: ${TimeCodec.number(it.userTrimMs, 1)} ms")
                appendLine("Latency correction: ${TimeCodec.number(it.correctionMs, 1)} ms")
            }
        }
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Edit ▸ Labels ▸ Label Editor (src/LabelDialog.cpp), simplified to a list. */
@Composable
fun LabelEditorDialog(d: AppDialog, vm: AppViewModel) {
    val snapshot by vm.engine.snapshot.collectAsState()
    val tracks = snapshot.tracks
    val labels = remember(tracks) { tracks.filter { it.isLabel }.flatMap { t -> t.labels.map { t to it } } }
    // Lazy list: Label Sounds / Silence Finder can create thousands of labels
    AppDialogFrame(
        title = stringResource(R.string.m_label_editor),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            TextButton(onClick = { vm.launchAction { vm.engine.addLabel("") } }) { Text(stringResource(R.string.tags_add)) }
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_close)) }
        },
        scrollable = false,
    ) {
        if (labels.isEmpty()) Text(stringResource(R.string.le_empty))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(labels, key = { (track, l) -> "${track.id}:${l.index}" }) { (track, l) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.title.ifEmpty { "—" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${track.name} · ${TimeCodec.format(l.t0)} – ${TimeCodec.format(l.t1)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable {
                                // The reference shown now (STALE when the labels change meanwhile)
                                val gen = snapshot.generation
                                vm.launchAction {
                                    val t0 = askTime(vm, R.string.le_start, l.t0) ?: return@launchAction
                                    val t1 = askTime(vm, R.string.le_end, maxOf(l.t1, t0)) ?: return@launchAction
                                    vm.engine.editLabel(track.id, l.index, t0 = minOf(t0, t1), t1 = maxOf(t0, t1), generation = gen)
                                }
                            },
                        )
                    }
                    IconButton(onClick = { vm.launchAction { ContextMenus.editLabelText(vm, track.id, l.index) } }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.cm_edit_label), Modifier.size(20.dp))
                    }
                    IconButton(onClick = { vm.launchAction { vm.engine.removeLabel(track.id, l.index, snapshot.generation) } }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.cm_delete_label), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private suspend fun askTime(vm: AppViewModel, title: Int, initial: Double): Double? {
    val d = AppDialog.TimeInput(UiText.Res(title), initial)
    vm.open(d)
    return d.result.await()
}
