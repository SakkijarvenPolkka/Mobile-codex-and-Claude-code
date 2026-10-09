/*
 * Audacity Android port — File ▸ Export Audio... (port of the 3.7.9
 * ExportAudioDialog / ExportFilePanel, src/export/, the Audacity Team,
 * GPL-2.0-or-later): file name, format, generic format options, range,
 * channels, sample rate and metadata. The destination is picked with
 * ACTION_CREATE_DOCUMENT; the engine exports to a staging file that the app
 * copies to it (import-export-project.md §5.3).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.ExportJob
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportDefaults
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportFormat
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOption
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportOptions
import io.github.sakkijarvenpolkka.audacity.engine.model.ExportValue
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import io.github.sakkijarvenpolkka.audacity.ui.AppDialogFrame
import io.github.sakkijarvenpolkka.audacity.ui.Dropdown
import io.github.sakkijarvenpolkka.audacity.ui.SectionHeader
import io.github.sakkijarvenpolkka.audacity.ui.SwitchRow
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import kotlin.math.roundToInt

@Composable
fun ExportDialog(d: AppDialog.Export, vm: AppViewModel) {
    val engine = vm.engine
    val snapshot by engine.snapshot.collectAsState()
    var formats by remember { mutableStateOf<List<ExportFormat>>(emptyList()) }
    var formatKey by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<ExportOptions?>(null) }
    var defaults by remember { mutableStateOf<ExportDefaults?>(null) }
    var range by rememberSaveable { mutableStateOf(if (d.selectionOnly) "selection" else "project") }
    var channels by rememberSaveable { mutableIntStateOf(0) }
    var rate by rememberSaveable { mutableIntStateOf(0) }
    var fileName by rememberSaveable { mutableStateOf("") }
    var skipSilence by rememberSaveable { mutableStateOf(vm.uiPrefs.value.exportSkipSilence) }

    val format = formats.firstOrNull { it.key == formatKey }
    // File names are limited to 255 bytes (about 85 Korean characters); the staging copy is shortened by itself
    val nameTooLong = SafFiles.utf8Length(ExportModel.withExtension(fileName.trim(), format)) > SafFiles.MAX_DOCUMENT_NAME_BYTES

    LaunchedEffect(Unit) {
        try {
            formats = engine.exportFormats()
            if (formatKey.isEmpty()) {
                val last = vm.uiPrefs.value.lastExportFormat
                formatKey = formats.firstOrNull { it.key == last }?.key ?: formats.firstOrNull()?.key ?: ""
            }
        } catch (e: Exception) {
            vm.reportError(e)
        }
    }
    LaunchedEffect(formatKey) {
        if (formatKey.isEmpty()) return@LaunchedEffect
        try {
            val opts = engine.exportOptions(formatKey)
            val defs = engine.exportDefaults(formatKey)
            options = opts
            defaults = defs
            val fmt = formats.firstOrNull { it.key == formatKey } ?: opts.format
            channels = ExportModel.defaultChannels(fmt, defs)
            rate = ExportModel.pickRate(defs.defaultRate, ExportModel.rateChoices(opts, defs))
            if (!defs.hasSelection && range == "selection") range = "project"
            fileName = if (fileName.isBlank()) ExportModel.fileName(snapshot.project.name, fmt) else ExportModel.withExtension(fileName, fmt)
        } catch (e: Exception) {
            vm.reportError(e)
        }
    }

    fun setOption(o: ExportOption, v: ExportValue) {
        vm.launchAction {
            val new = engine.setExportOption(formatKey, o.id, v)
            options = new
            val rates = ExportModel.rateChoices(new, defaults)
            if (rate !in rates) rate = ExportModel.pickRate(rate, rates)
        }
    }

    AppDialogFrame(
        title = stringResource(R.string.ex_title),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            if (format?.canMetaData == true) {
                TextButton(onClick = { vm.open(AppDialog.Tags) }) { Text(stringResource(R.string.ex_edit_metadata)) }
            }
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) }
            Button(
                enabled = format != null && options != null && fileName.isNotBlank() && !nameTooLong && rate > 0 && channels > 0,
                onClick = {
                    vm.startExport(
                        ExportJob(formatKey, ExportModel.withExtension(fileName.trim(), format), range, channels, rate, skipSilence),
                        ExportModel.mimeType(format),
                    )
                    vm.dismiss(d)
                },
                modifier = Modifier.testTag("export:run"),
            ) { Text(stringResource(R.string.ex_export)) }
        },
    ) {
        OutlinedTextField(
            value = fileName, onValueChange = { fileName = it }, singleLine = true,
            label = { Text(stringResource(R.string.ex_file_name)) },
            isError = nameTooLong,
            supportingText = if (nameTooLong) ({ Text(stringResource(R.string.msg_name_too_long)) }) else null,
            modifier = Modifier.fillMaxWidth().testTag("export:fileName"),
        )
        Dropdown(
            stringResource(R.string.ex_format), formats, format, { it.description.ifEmpty { it.key } },
            { formatKey = it.key }, Modifier.fillMaxWidth().testTag("export:format"),
        )
        val opts = options
        if (opts == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            ExportOptionRows(opts) { o, v -> setOption(o, v) }
        }
        SectionHeader(stringResource(R.string.ex_audio_options))
        val defs = defaults
        Dropdown(
            stringResource(R.string.ex_range), listOf("project", "selection"), range,
            { if (it == "project") vm.string(R.string.ex_range_project) else vm.string(R.string.ex_range_selection) },
            { if (it == "project" || defs?.hasSelection == true) range = it }, Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chans = ExportModel.channelChoices(format, defs)
            Dropdown(
                stringResource(R.string.ex_channels), chans, channels.takeIf { it > 0 },
                { if (it == 1) vm.string(R.string.ex_mono) else vm.string(R.string.ex_stereo) }, { channels = it }, Modifier.weight(1f),
            )
            Dropdown(
                stringResource(R.string.ex_rate), ExportModel.rateChoices(opts, defs), rate.takeIf { it > 0 },
                { vm.string(R.string.rate_hz, it) }, { rate = it }, Modifier.weight(1f),
            )
        }
        SwitchRow(stringResource(R.string.ex_skip_silence), skipSilence, { skipSilence = it })
    }
}

/** Generic rows of an ExportOptions snapshot (hidden options are not shown). */
@Composable
fun ExportOptionRows(options: ExportOptions, onSet: (ExportOption, ExportValue) -> Unit) {
    val rows = ExportModel.rows(options)
    if (rows.isEmpty()) return
    SectionHeader(stringResource(R.string.ex_format_options))
    Column(Modifier.fillMaxWidth()) {
        for (row in rows) {
            when (row) {
                is ExportModel.EnumRow -> Dropdown(
                    row.title, row.names.indices.toList(), row.selected, { row.names.getOrElse(it) { "" } },
                    { i -> onSet(row.option, ExportModel.enumValue(row.option, i)) },
                    Modifier.fillMaxWidth().testTag("export:option:${row.option.id}"), enabled = row.enabled,
                )
                is ExportModel.RangeRow -> RangeOption(row, onSet)
                is ExportModel.BoolRow -> SwitchRow(row.title, row.value, { onSet(row.option, ExportModel.boolValue(row.option, it)) }, enabled = row.enabled)
                is ExportModel.NumberRow -> TextOption(
                    row.title, if (row.isInt) row.value.roundToInt().toString() else TimeCodec.number(row.value, 4), row.enabled, true,
                ) { s -> s.replace(',', '.').toDoubleOrNull()?.let { onSet(row.option, ExportModel.numberValue(row.option, it)) } }
                is ExportModel.TextRow -> TextOption(row.title, row.value, row.enabled, false) { s ->
                    onSet(row.option, ExportModel.textValue(row.option, s))
                }
            }
        }
    }
}

@Composable
private fun RangeOption(row: ExportModel.RangeRow, onSet: (ExportOption, ExportValue) -> Unit) {
    var v by remember(row.option.id, row.value) { mutableFloatStateOf(row.value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(if (row.isInt) v.roundToInt().toString() else TimeCodec.number(v.toDouble(), 2))
        }
        val steps = if (row.isInt) ((row.max - row.min).roundToInt() - 1).takeIf { it in 1..200 } ?: 0 else 0
        Slider(
            value = v, onValueChange = { v = it },
            onValueChangeFinished = { onSet(row.option, ExportModel.numberValue(row.option, if (row.isInt) v.roundToInt().toDouble() else v.toDouble())) },
            valueRange = row.min.toFloat()..row.max.toFloat().coerceAtLeast(row.min.toFloat()),
            steps = steps, enabled = row.enabled,
            modifier = Modifier.testTag("export:option:${row.option.id}"),
        )
    }
}

@Composable
private fun TextOption(title: String, value: String, enabled: Boolean, numeric: Boolean, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true, enabled = enabled,
        label = { Text(title) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text != value) onCommit(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { f ->
                if (focused && !f.isFocused && text != value) onCommit(text)
                focused = f.isFocused
            },
    )
}
