/*
 * Audacity Android port — message, question, input, progress and recovery
 * dialogs (BasicUI dialogs of the engine, API.md §4.3/§4.4; ProgressDialog;
 * AutoRecoveryDialog of Audacity 3.7.9).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.LocalProgress
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.SaveChoice
import io.github.sakkijarvenpolkka.audacity.engine.model.DialogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProgressEvent
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import io.github.sakkijarvenpolkka.audacity.util.text
import java.text.DateFormat
import java.util.Date

@Composable
fun InfoDialog(d: AppDialog.Info, vm: AppViewModel) {
    val title = d.title.text()
    AlertDialog(
        onDismissRequest = { vm.dismiss(d) },
        confirmButton = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_ok)) } },
        title = if (title.isNotEmpty()) ({ Text(title) }) else null,
        text = { Text(d.message.text(), Modifier.verticalScroll(rememberScrollState())) },
    )
}

@Composable
fun ConfirmDialog(d: AppDialog.Confirm, vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismiss(d) },
        confirmButton = {
            TextButton(onClick = {
                d.result.complete(true)
                vm.dismiss(d)
            }) { Text(d.ok.text()) }
        },
        dismissButton = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) } },
        title = { Text(d.title.text()) },
        text = { Text(d.message.text()) },
    )
}

@Composable
fun TextInputDialog(d: AppDialog.TextInput, vm: AppViewModel) {
    var value by rememberSaveable(d) { mutableStateOf(d.initial) }
    val focus = remember { FocusRequester() }
    val ok = d.allowEmpty || value.isNotBlank()
    fun submit() {
        if (!ok) return
        d.result.complete(value)
        vm.dismiss(d)
    }
    AlertDialog(
        onDismissRequest = { vm.dismiss(d) },
        confirmButton = { TextButton(onClick = { submit() }, enabled = ok) { Text(stringResource(R.string.btn_ok)) } },
        dismissButton = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) } },
        title = { Text(d.title.text()) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                label = { Text(d.label.text()) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** One of several options (radio buttons), e.g. the label file type. */
@Composable
fun ChoiceDialog(d: AppDialog.Choice, vm: AppViewModel) {
    var sel by rememberSaveable(d) { mutableIntStateOf(d.initial.coerceIn(0, (d.options.size - 1).coerceAtLeast(0))) }
    AlertDialog(
        onDismissRequest = { vm.dismiss(d) },
        title = { Text(d.title.text()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                d.options.forEachIndexed { i, o ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { sel = i },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel == i, onClick = { sel = i })
                        Text(o.text())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                d.result.complete(sel)
                vm.dismiss(d)
            }, enabled = d.options.isNotEmpty()) { Text(stringResource(R.string.btn_ok)) }
        },
        dismissButton = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) } },
    )
}

@Composable
fun TimeInputDialog(d: AppDialog.TimeInput, vm: AppViewModel) {
    var value by rememberSaveable(d) { mutableStateOf(TimeCodec.format(d.initial)) }
    val parsed = TimeCodec.parse(value)
    fun submit() {
        val t = parsed ?: return
        d.result.complete(t)
        vm.dismiss(d)
    }
    AlertDialog(
        onDismissRequest = { vm.dismiss(d) },
        confirmButton = { TextButton(onClick = { submit() }, enabled = parsed != null) { Text(stringResource(R.string.btn_ok)) } },
        dismissButton = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_cancel)) } },
        title = { Text(d.title.text()) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                label = { Text(stringResource(R.string.time_hint)) },
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** "Save changes to %s?" — Yes / No / Cancel (ProjectManager::SaveChangesBeforeClosing). */
@Composable
fun SaveChangesDialog(d: AppDialog.SaveChanges, vm: AppViewModel) {
    fun answer(c: SaveChoice) {
        d.result.complete(c)
        vm.dismiss(d)
    }
    AlertDialog(
        onDismissRequest = { answer(SaveChoice.CANCEL) },
        title = { Text(stringResource(R.string.save_changes_title)) },
        text = { Text(stringResource(R.string.save_changes_message, d.projectName)) },
        confirmButton = { TextButton(onClick = { answer(SaveChoice.SAVE) }) { Text(stringResource(R.string.btn_yes)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { answer(SaveChoice.DISCARD) }) { Text(stringResource(R.string.btn_no)) }
                TextButton(onClick = { answer(SaveChoice.CANCEL) }) { Text(stringResource(R.string.btn_cancel)) }
            }
        },
    )
}

/** Automatic Crash Recovery (src/AutoRecoveryDialog.cpp). */
@Composable
fun RecoveryDialog(d: AppDialog.Recovery, vm: AppViewModel) {
    val checked = remember(d) { mutableStateListOf<String>().apply { addAll(d.projects.map { it.path }) } }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.recovery_message))
                Spacer(Modifier.height(8.dp))
                d.projects.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { if (p.path in checked) checked.remove(p.path) else checked.add(p.path) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = p.path in checked, onCheckedChange = { c -> if (c) checked.add(p.path) else checked.remove(p.path) })
                        Column {
                            Text(p.name.ifEmpty { p.path.substringAfterLast('/') }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fmt.format(Date(p.modifiedMs)) + " · " + TimeCodec.formatBytes(p.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.recover(d, checked.toList()) }, enabled = checked.isNotEmpty()) {
                Text(stringResource(R.string.recovery_recover))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.discardRecoverable(d, checked.toList()) }, enabled = checked.isNotEmpty()) {
                    Text(stringResource(R.string.recovery_discard))
                }
                TextButton(onClick = { vm.skipRecovery(d) }) { Text(stringResource(R.string.recovery_skip)) }
            }
        },
    )
}

/** A `dialog` event of the engine (BasicUI message box, choice or multi-choice). */
@Composable
fun EngineDialog(e: DialogEvent, vm: AppViewModel) {
    val reply: (Int) -> Unit = { b -> vm.engine.replyDialog(e.id, b) }
    if (e.isMultiChoice) {
        MultiChoiceDialog(e, vm)
        return
    }
    if (e.kind == DialogEvent.KIND_CHOICE && e.choices.isNotEmpty()) {
        var sel by remember(e.id) { mutableIntStateOf(e.defaultButton.coerceIn(0, e.choices.lastIndex)) }
        AlertDialog(
            onDismissRequest = { reply(-1) },
            title = { Text(e.title.ifEmpty { "Audacity" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (e.message.isNotEmpty()) Text(e.message)
                    e.choices.forEachIndexed { i, c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) { sel = i },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = sel == i, onClick = { sel = i })
                            Text(c)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reply(sel) }) { Text(stringResource(R.string.btn_ok)) } },
            dismissButton = { TextButton(onClick = { reply(-1) }) { Text(stringResource(R.string.btn_cancel)) } },
        )
        return
    }
    val buttons = e.buttons.ifEmpty { listOf(stringResource(R.string.btn_ok)) }
    AlertDialog(
        onDismissRequest = { if (!e.blocking || buttons.size == 1) reply(if (buttons.size == 1) 0 else -1) },
        title = { Text(e.title.ifEmpty { "Audacity" }) },
        text = { Text(e.message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            Row {
                buttons.forEachIndexed { i, b ->
                    TextButton(onClick = { reply(i) }) { Text(b) }
                }
            }
        },
    )
}

/**
 * `kind:"multiChoice"` (API.md §4.4), e.g. "Select stream(s) to import":
 * check boxes initialised from `defaultChecked`; OK answers the checked
 * indices with replyDialogChoices (none checked is a valid answer), Cancel /
 * back answers replyDialog(id, -1).
 */
@Composable
fun MultiChoiceDialog(e: DialogEvent, vm: AppViewModel) {
    val checked = remember(e.id) { mutableStateListOf<Int>().apply { addAll(e.defaultIndices) } }
    val ok = e.buttons.getOrNull(0) ?: stringResource(R.string.btn_ok)
    val cancel = e.buttons.getOrNull(1) ?: stringResource(R.string.btn_cancel)
    AlertDialog(
        onDismissRequest = { vm.engine.replyDialog(e.id, -1) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(e.title.ifEmpty { "Audacity" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (e.message.isNotEmpty()) {
                    Text(e.message)
                    Spacer(Modifier.height(8.dp))
                }
                e.choices.forEachIndexed { i, c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { if (i in checked) checked.remove(i) else checked.add(i) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = i in checked, onCheckedChange = { c2 -> if (c2) checked.add(i) else checked.remove(i) })
                        Text(c)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.engine.replyDialogChoices(e.id, checked.sorted()) }) { Text(ok) } },
        dismissButton = { TextButton(onClick = { vm.engine.replyDialog(e.id, -1) }) { Text(cancel) } },
    )
}

/** Modal progress of a long engine command (API.md §4.3) with Cancel/Stop. */
@Composable
fun EngineProgressDialog(p: ProgressEvent, vm: AppViewModel) {
    ProgressBox(
        title = p.title.ifEmpty { stringResource(R.string.progress_working) },
        message = p.message,
        fraction = p.fraction,
        onCancel = if (p.cancellable) ({ vm.engine.cancelProgress(p.id, false) }) else null,
        onStop = if (p.stoppable) ({ vm.engine.cancelProgress(p.id, true) }) else null,
    )
}

@Composable
fun LocalProgressDialog(p: LocalProgress) {
    ProgressBox(p.title.text(), "", p.fraction, onCancel = p.cancel, onStop = null)
}

@Composable
private fun ProgressBox(title: String, message: String, fraction: Double, onCancel: (() -> Unit)?, onStop: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(min = 48.dp)) {
                if (message.isNotEmpty()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                if (fraction < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (fraction >= 0) {
                    Text("${(fraction * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Row {
                if (onStop != null) TextButton(onClick = onStop) { Text(stringResource(R.string.btn_stop)) }
                if (onCancel != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.btn_cancel)) }
                }
            }
        },
    )
}
