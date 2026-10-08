/*
 * Audacity Android port — Preferences (a subset of the 3.7.9 Preferences
 * dialog, src/prefs: Devices, Playback/Recording, Quality, Tracks
 * Behaviors, Effects, Interface). Engine settings go through settings.set
 * (API.md §5.1) and apply immediately; interface settings are UI prefs.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppScreen
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.HostRequest
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.editor.ThemeChoice
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDevices
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.export.ExportModel
import io.github.sakkijarvenpolkka.audacity.ui.Dropdown
import io.github.sakkijarvenpolkka.audacity.ui.SectionHeader
import io.github.sakkijarvenpolkka.audacity.ui.SwitchRow
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import kotlin.math.roundToInt

/** /Effects/GroupBy symbols and their 3.7.9 labels (EffectsPrefs.cpp). */
private val GROUP_BY = listOf(
    "sortby:name" to R.string.pref_fx_sort_name,
    "sortby:publisher:name" to R.string.pref_fx_sort_publisher,
    "sortby:type:name" to R.string.pref_fx_sort_type,
    "groupby:publisher" to R.string.pref_fx_group_publisher,
    "groupby:type" to R.string.pref_fx_group_type,
    "default" to R.string.pref_fx_group_category,
)

private val DITHER = listOf("none" to R.string.pref_dither_none, "rectangle" to R.string.pref_dither_rectangle,
    "triangle" to R.string.pref_dither_triangle, "shaped" to R.string.pref_dither_shaped)

private val FORMATS = listOf("int16" to R.string.fmt_int16, "int24" to R.string.fmt_int24, "float" to R.string.fmt_float)

@Composable
fun PreferencesScreen(vm: AppViewModel) {
    val settingsState by vm.settings.collectAsState()
    val prefs by vm.uiPrefs.state.collectAsState()
    val snapshot by vm.engine.snapshot.collectAsState()
    var devices by remember { mutableStateOf<AudioDevices?>(null) }
    LaunchedEffect(Unit) {
        runCatching { vm.settings.value = vm.engine.getSettings() }
        runCatching { devices = vm.engine.audioDevices() }
    }
    fun set(partial: Settings) = vm.launchAction { vm.updateSettings(partial) }
    val s = settingsState ?: Settings()

    ScreenScaffold(title = stringResource(R.string.pref_title), onBack = { vm.show(AppScreen.EDITOR) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .widthIn(max = 720.dp),
        ) {
            // ----- Devices (DevicePrefs) -----
            SectionHeader(stringResource(R.string.pref_devices))
            val outs = devices?.outputs?.map { it.name }.orEmpty()
            val ins = devices?.inputs?.map { it.name }.orEmpty()
            Dropdown(stringResource(R.string.pref_playback_device), outs, s.outputDevice ?: devices?.current?.output, { it },
                { set(Settings(outputDevice = it)) }, Modifier.fillMaxWidth(), enabled = outs.isNotEmpty())
            Dropdown(stringResource(R.string.pref_recording_device), ins, s.inputDevice ?: devices?.current?.input, { it },
                { set(Settings(inputDevice = it)) }, Modifier.fillMaxWidth(), enabled = ins.isNotEmpty())
            val channelLabels = listOf(stringResource(R.string.ex_mono), stringResource(R.string.ex_stereo))
            Dropdown(stringResource(R.string.pref_channels), listOf(1, 2), s.recordChannels ?: 1, { channelLabels[it - 1] },
                { set(Settings(recordChannels = it)) }, Modifier.fillMaxWidth())
            NumberSetting(stringResource(R.string.pref_latency), s.latencyMs, "ms") { set(Settings(latencyMs = it)) }
            NumberSetting(stringResource(R.string.pref_latency_correction), s.latencyCorrectionMs, "ms") { set(Settings(latencyCorrectionMs = it)) }

            // ----- Recording (RecordingPrefs) -----
            SectionHeader(stringResource(R.string.pref_recording))
            SwitchRow(stringResource(R.string.m_overdub), s.overdub ?: true, { set(Settings(overdub = it)) })
            SwitchRow(stringResource(R.string.m_sw_playthrough), s.swPlaythrough ?: false, { set(Settings(swPlaythrough = it)) })
            SwitchRow(stringResource(R.string.pref_record_new_track), s.preferNewTrackRecord ?: false, { set(Settings(preferNewTrackRecord = it)) })
            SwitchRow(stringResource(R.string.pref_detect_dropouts), s.dropoutDetection ?: true, { set(Settings(dropoutDetection = it)) })

            // ----- Quality (QualityPrefs) -----
            SectionHeader(stringResource(R.string.pref_quality))
            Dropdown(stringResource(R.string.pref_default_rate), ExportModel.DEFAULT_RATES, s.defaultRate, { vm.string(R.string.rate_hz, it) },
                { set(Settings(defaultRate = it)) }, Modifier.fillMaxWidth())
            Dropdown(stringResource(R.string.pref_default_format), FORMATS.map { it.first }, s.defaultFormat,
                { f -> vm.string(FORMATS.first { it.first == f }.second) }, { set(Settings(defaultFormat = it)) }, Modifier.fillMaxWidth())
            Dropdown(stringResource(R.string.pref_realtime_dither), DITHER.map { it.first }, s.realtimeDither,
                { k -> vm.string(DITHER.firstOrNull { it.first == k }?.second ?: R.string.pref_dither_none) },
                { set(Settings(realtimeDither = it)) }, Modifier.fillMaxWidth())
            Dropdown(stringResource(R.string.pref_hq_dither), DITHER.map { it.first }, s.hqDither,
                { k -> vm.string(DITHER.firstOrNull { it.first == k }?.second ?: R.string.pref_dither_none) },
                { set(Settings(hqDither = it)) }, Modifier.fillMaxWidth())
            if (snapshot.project.open) {
                Dropdown(stringResource(R.string.pref_project_rate), ExportModel.DEFAULT_RATES, snapshot.project.rate.roundToInt(),
                    { vm.string(R.string.rate_hz, it) }, { r -> vm.launchAction { vm.engine.setProjectRate(r) } }, Modifier.fillMaxWidth())
            }

            // ----- Tracks Behaviors -----
            SectionHeader(stringResource(R.string.pref_tracks))
            val solo = listOf("Simple" to R.string.pref_solo_simple, "Multi" to R.string.pref_solo_multi)
            Dropdown(stringResource(R.string.pref_solo), solo.map { it.first }, s.soloMode, { k -> vm.string(solo.firstOrNull { it.first == k }?.second ?: R.string.pref_solo_simple) },
                { set(Settings(soloMode = it)) }, Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.pref_select_all_on_none), s.selectAllOnNone ?: false, { set(Settings(selectAllOnNone = it)) })
            SwitchRow(stringResource(R.string.pref_clips_can_move), s.editClipsCanMove ?: false, { set(Settings(editClipsCanMove = it)) })
            SwitchRow(stringResource(R.string.pref_paste_as_new_clips), s.pasteAsNewClips ?: false, { set(Settings(pasteAsNewClips = it)) })
            SwitchRow(stringResource(R.string.m_sync_lock), s.syncLock ?: false, { set(Settings(syncLock = it)) })

            // ----- Effects -----
            SectionHeader(stringResource(R.string.pref_effects))
            Dropdown(stringResource(R.string.pref_effects_grouping), GROUP_BY.map { it.first }, s.effectsGroupBy ?: "default",
                { k -> vm.string(GROUP_BY.firstOrNull { it.first == k }?.second ?: R.string.pref_fx_group_category) },
                { set(Settings(effectsGroupBy = it)) }, Modifier.fillMaxWidth())

            // ----- Interface (GUIPrefs; UI side) -----
            SectionHeader(stringResource(R.string.pref_interface))
            val themes = listOf(ThemeChoice.SYSTEM to R.string.pref_theme_system, ThemeChoice.LIGHT to R.string.pref_theme_light,
                ThemeChoice.DARK to R.string.pref_theme_dark)
            Dropdown(stringResource(R.string.pref_theme), themes.map { it.first }, prefs.theme,
                { t -> vm.string(themes.first { it.first == t }.second) }, { t -> vm.uiPrefs.update { it.copy(theme = t) } }, Modifier.fillMaxWidth())
            SwitchRow(stringResource(R.string.m_show_clipping), prefs.showClipping, { v -> vm.uiPrefs.update { it.copy(showClipping = v) } })
            SwitchRow(stringResource(R.string.m_show_rms), prefs.showRms, { v -> vm.uiPrefs.update { it.copy(showRms = v) } })
            // Language of the engine's strings (effect names, history, messages): "system" follows the app's locale at start
            val info by vm.appInfo.collectAsState()
            val languages = listOf("system") + (info?.languages ?: listOf("en"))
            val systemLabel = stringResource(R.string.pref_engine_language_system)
            Dropdown(stringResource(R.string.pref_engine_language), languages, s.language ?: "system",
                { code -> if (code == "system") systemLabel else languageName(code) }, { set(Settings(language = it)) }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.pref_language_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { vm.request(HostRequest.AppLanguageSettings) }) { Text(stringResource(R.string.pref_language_button)) }
            }
            Text(
                stringResource(R.string.pref_audacity_cfg_hint), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

/** "ko" → "한국어" (the language's own name). */
private fun languageName(code: String): String {
    val locale = java.util.Locale.forLanguageTag(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.ifEmpty { code }
}

@Composable
private fun NumberSetting(label: String, value: Double?, unit: String, onCommit: (Double) -> Unit) {
    val shown = value?.let { TimeCodec.number(it, 1) } ?: ""
    var text by remember(shown) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return
        if (v.isFinite() && shown != text) onCommit(v)
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it }, singleLine = true,
        label = { Text(label) }, suffix = { Text(unit) },
        isError = text.isNotEmpty() && text.trim().replace(',', '.').toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { f ->
                if (focused && !f.isFocused) commit()
                focused = f.isFocused
            },
    )
}
