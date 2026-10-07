/*
 * Audacity Android port — generic effect dialog built from the engine's
 * EffectDescription (API.md §5.5): replaces the wx effect editors of
 * Audacity 3.7.9 src/effects (the Audacity Team, GPL-2.0-or-later) with
 * sliders, fields, switches and drop-downs; "Presets & settings" menu
 * (user/factory presets, defaults), Preview/Stop and Apply. Special cases:
 * Noise Reduction (two steps), Filter Curve EQ / Graphic EQ (curve editor),
 * Auto Duck (control-track hint), generators (duration).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.effects.ParamCodec
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectDescription
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectParam
import io.github.sakkijarvenpolkka.audacity.engine.model.EqCurve
import io.github.sakkijarvenpolkka.audacity.ui.AppDialogFrame
import io.github.sakkijarvenpolkka.audacity.ui.Dropdown
import io.github.sakkijarvenpolkka.audacity.ui.SwitchRow
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.serialization.json.JsonElement

private val EQ_SPECIALS = setOf("equalization", "graphicEq")

@Composable
fun EffectDialog(d: AppDialog.Effect, vm: AppViewModel) {
    val engine = vm.engine
    val effects by vm.effectList.collectAsState()
    val transport by engine.transportState.collectAsState()
    val info = effects?.effects?.firstOrNull { it.id == d.effectId }

    var desc by remember(d) { mutableStateOf<EffectDescription?>(null) }
    val edits = remember(d) { mutableStateMapOf<String, JsonElement>() }
    var durationText by remember(d) { mutableStateOf("") }
    var curve by remember(d) { mutableStateOf<EqCurve?>(null) }
    var busy by remember(d) { mutableStateOf(false) }
    var previewing by remember(d) { mutableStateOf(false) }

    fun adopt(new: EffectDescription) {
        desc = new
        edits.clear()
        durationText = ParamCodec.formatDuration(new.duration)
        curve = new.curve
    }

    LaunchedEffect(d) {
        try {
            adopt(engine.describeEffect(d.effectId))
        } catch (e: Exception) {
            vm.reportError(e)
            vm.dismiss(d)
        }
    }
    LaunchedEffect(transport.state) { if (transport.state == "stopped") previewing = false }

    /** Pushes the dialog's values (and EQ curve) to the engine; returns params and duration, or null when invalid. */
    suspend fun commit(de: EffectDescription): Pair<Map<String, JsonElement>, Double?>? {
        val params = ParamCodec.paramsFor(de, edits)
        val dur = if (de.supportsDuration) {
            ParamCodec.parseDuration(durationText) ?: run {
                vm.message(R.string.msg_invalid_duration)
                return null
            }
        } else null
        val c = curve
        if (de.special in EQ_SPECIALS && c != null) engine.setEffectParams(de.id, params, dur, c)
        return params to dur
    }

    fun close() {
        if (previewing) vm.launchAction(quiet = true) { engine.stopPreview() }
        vm.dismiss(d)
    }

    val title = desc?.name?.ifEmpty { null } ?: info?.name ?: ""
    AppDialogFrame(
        title = title,
        onDismiss = { close() },
        buttons = {
            val de = desc
            if (de != null && de.type != "analyze" && de.type != "tool") {
                if (previewing && transport.state != "stopped") {
                    TextButton(onClick = { vm.launchAction { engine.stopPreview() } }) { Text(stringResource(R.string.btn_stop_preview)) }
                } else {
                    TextButton(enabled = !busy, onClick = {
                        vm.launchAction {
                            val (params, dur) = commit(de) ?: return@launchAction
                            engine.previewEffect(de.id, params, dur)
                            previewing = true
                        }
                    }) { Text(stringResource(R.string.btn_preview)) }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { close() }) { Text(stringResource(R.string.btn_cancel)) }
            Button(enabled = de != null && !busy, onClick = {
                val cur = desc
                if (cur != null) vm.launchAction {
                    busy = true
                    try {
                        if (previewing) engine.stopPreview()
                        if (cur.special == "autoDuck" && !autoDuckSelectionOk(vm, cur, edits)) return@launchAction
                        val (params, dur) = commit(cur) ?: return@launchAction
                        val r = engine.applyEffect(cur.id, params, dur)
                        vm.dismiss(d)
                        r.message?.takeIf { it.isNotBlank() }?.let { vm.open(AppDialog.Info(UiText.Raw(cur.name), UiText.Raw(it))) }
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.btn_apply)) }
        },
    ) {
        val de = desc
        if (de == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppDialogFrame
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                info?.description ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PresetsMenu(de, vm, onLoaded = { adopt(it) }, commit = { commit(de) != null }, onPresetsChanged = { p -> desc = de.copy(presets = p.presets) })
        }
        when (de.special) {
            "noiseReduction" -> NoiseReductionSteps(de, vm) { vm.dismiss(d) }
            "autoDuck" -> HintCard(stringResource(R.string.fx_autoduck_hint))
            "equalization", "graphicEq" -> {
                val c = curve ?: EqCurve()
                EqCurveEditor(
                    curve = c,
                    dbRange = eqDbRange(de, edits),
                    onChange = { curve = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp).padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (de.curves.isNotEmpty()) {
                        Dropdown(
                            stringResource(R.string.fx_eq_curve), de.curves, null, { it },
                            onSelect = { name ->
                                vm.launchAction { adopt(engine.loadEffectPreset(de.id, "factory", name, de.curves.indexOf(name))) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedButton(onClick = { curve = c.copy(points = c.points.map { it.copy(dB = 0.0) }) }) {
                        Text(stringResource(R.string.fx_eq_flatten))
                    }
                }
                SwitchRow(stringResource(R.string.fx_eq_linear), c.linearFreq, { curve = c.copy(linearFreq = it) })
                Text(stringResource(R.string.fx_eq_help), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (de.supportsDuration) {
            val ok = ParamCodec.parseDuration(durationText) != null
            OutlinedTextField(
                value = durationText, onValueChange = { durationText = it }, singleLine = true,
                label = { Text(stringResource(R.string.fx_duration)) },
                supportingText = { Text(stringResource(R.string.time_hint)) },
                isError = !ok,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        for (p in de.params) {
            if (de.special in EQ_SPECIALS && (p.key == "dBMin" || p.key == "dBMax" || p.key == "DrawMode" || p.key == "DrawGrid")) continue
            ParamEditor(p, ParamCodec.current(p, edits)) { v -> edits[p.key] = v }
        }
        if (de.params.isEmpty() && !de.supportsDuration && de.special == null) {
            Text(stringResource(R.string.fx_no_params), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun eqDbRange(de: EffectDescription, edits: Map<String, JsonElement>): ClosedFloatingPointRange<Double> {
    val lo = de.params.firstOrNull { it.key == "dBMin" }?.let { ParamCodec.number(ParamCodec.current(it, edits)) } ?: -30.0
    val hi = de.params.firstOrNull { it.key == "dBMax" }?.let { ParamCodec.number(ParamCodec.current(it, edits)) } ?: 30.0
    return if (hi > lo) lo..hi else -30.0..30.0
}

/** Auto Duck: the analysed part must not be empty (AutoDuckBase.cpp:164-168, effects.md §6.3). */
private fun autoDuckSelectionOk(vm: AppViewModel, de: EffectDescription, edits: Map<String, JsonElement>): Boolean {
    val sel = vm.engine.snapshot.value.selection
    fun v(key: String) = de.params.firstOrNull { it.key == key }?.let { ParamCodec.number(ParamCodec.current(it, edits)) } ?: 0.0
    val need = v("OuterFadeDownLen") + v("OuterFadeUpLen")
    if (sel.t1 - sel.t0 <= need) {
        vm.message(R.string.fx_autoduck_too_short)
        return false
    }
    return true
}

@Composable
private fun HintCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Noise Reduction step 1 ("Get Noise Profile"); step 2 = the settings below + Apply. */
@Composable
private fun NoiseReductionSteps(de: EffectDescription, vm: AppViewModel, onCaptured: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(stringResource(R.string.nr_step1), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.nr_step1_text), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            vm.launchAction {
                vm.engine.captureNoiseProfile()
                vm.message(R.string.nr_profile_captured)
                onCaptured()
            }
        }, modifier = Modifier.padding(vertical = 4.dp)) { Text(stringResource(R.string.nr_get_profile)) }
        if (de.profileCaptured) {
            Text(stringResource(R.string.nr_profile_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.nr_step2), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.nr_step2_text), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Presets & settings menu (src/effects/EffectUI.cpp "Presets & settings"). */
@Composable
private fun PresetsMenu(
    de: EffectDescription,
    vm: AppViewModel,
    onLoaded: (EffectDescription) -> Unit,
    commit: suspend () -> Boolean,
    onPresetsChanged: (EffectDescription) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("main") }
    val engine = vm.engine
    Box {
        TextButton(onClick = { page = "main"; open = true }) { Text(stringResource(R.string.fx_presets)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun close() {
                open = false
            }
            when (page) {
                "main" -> {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_user_presets)) }, onClick = { page = "user" },
                        enabled = de.presets.user.isNotEmpty())
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_save_preset)) }, onClick = {
                        close()
                        vm.launchAction {
                            val name = vm.askText(UiText.Res(R.string.fx_save_preset), UiText.Res(R.string.fx_preset_name), "") ?: return@launchAction
                            if (!commit()) return@launchAction
                            engine.saveEffectPreset(de.id, name)
                            onPresetsChanged(engine.describeEffect(de.id))
                        }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_delete_preset)) }, onClick = { page = "delete" },
                        enabled = de.presets.user.isNotEmpty())
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_defaults)) }, onClick = {
                        close()
                        vm.launchAction { onLoaded(engine.loadEffectPreset(de.id, "defaults")) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_factory_presets)) }, onClick = { page = "factory" },
                        enabled = de.presets.factory.isNotEmpty())
                }
                "user" -> de.presets.user.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        close()
                        vm.launchAction { onLoaded(engine.loadEffectPreset(de.id, "user", name)) }
                    })
                }
                "factory" -> de.presets.factory.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        close()
                        vm.launchAction { onLoaded(engine.loadEffectPreset(de.id, "factory", name, i)) }
                    })
                }
                "delete" -> de.presets.user.forEach { name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        close()
                        vm.launchAction {
                            val ok = vm.askConfirm(
                                UiText.Res(R.string.fx_delete_preset), UiText.Res(R.string.fx_delete_preset_confirm, listOf(name)),
                                UiText.Res(R.string.btn_delete),
                            )
                            if (!ok) return@launchAction
                            engine.deleteEffectPreset(de.id, name)
                            onPresetsChanged(engine.describeEffect(de.id))
                        }
                    })
                }
            }
        }
    }
}

/** One parameter row: switch, drop-down, text, or slider + number field. */
@Composable
fun ParamEditor(p: EffectParam, value: JsonElement?, onChange: (JsonElement) -> Unit) {
    val label = ParamCodec.label(p)
    when (p.kind) {
        "bool" -> SwitchRow(label, ParamCodec.bool(value), { onChange(ParamCodec.encodeBool(it)) })
        "enum" -> {
            val choices = ParamCodec.choices(p)
            val idx = ParamCodec.enumIndex(p, value)
            Dropdown(label, choices.indices.toList(), idx, { choices.getOrElse(it) { "" } }, { onChange(ParamCodec.encodeEnum(it)) },
                modifier = Modifier.fillMaxWidth())
        }
        "string" -> OutlinedTextField(
            value = ParamCodec.string(value), onValueChange = { onChange(ParamCodec.encodeString(it)) },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        else -> NumberParam(p, label, ParamCodec.number(value) ?: 0.0, onChange)
    }
}

@Composable
private fun NumberParam(p: EffectParam, label: String, raw: Double, onChange: (JsonElement) -> Unit) {
    val range = ParamCodec.sliderRange(p)
    val unit = if (ParamCodec.isDb(p)) "dB" else p.unit
    var focused by remember(p.key) { mutableStateOf(false) }
    var text by remember(p.key) { mutableStateOf("") }
    val formatted = ParamCodec.formatDisplayed(p, raw)
    val display = if (focused) text else formatted
    val invalid = focused && ParamCodec.parseDisplayed(p, text) == null
    fun commitText() {
        ParamCodec.parseDisplayed(p, text)?.let { onChange(ParamCodec.encodeNumber(p, it)) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = display,
                onValueChange = {
                    text = it
                    ParamCodec.parseDisplayed(p, it)?.let { v -> onChange(ParamCodec.encodeNumber(p, v)) }
                },
                singleLine = true,
                isError = invalid,
                suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitText() }),
                modifier = Modifier
                    .width(150.dp)
                    .onFocusChanged { f ->
                        if (f.isFocused && !focused) text = formatted
                        if (focused && !f.isFocused) commitText()
                        focused = f.isFocused
                    },
            )
        }
        if (range != null) {
            val shown = ParamCodec.toDisplay(p, raw).coerceIn(range.start, range.endInclusive)
            val intSteps = if (p.kind == "int") ((range.endInclusive - range.start).toInt() - 1).takeIf { it in 1..200 } ?: 0 else 0
            Slider(
                value = shown.toFloat(),
                onValueChange = { v -> onChange(ParamCodec.encodeDisplayed(p, v.toDouble())) },
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                steps = intSteps,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.size(2.dp))
}
