/*
 * Audacity Android port — UI-side preferences (the engine keeps its own
 * audacity.cfg; these are the settings that only the app reads).
 *
 * Defaults are the 3.7.9 ones (`/GUI/ShowClipping` and `/GUI/ShowRMS` false,
 * GUIPrefs.cpp); the theme follows the system, Audacity's default being Light.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.prefs

import android.content.Context
import android.content.SharedPreferences
import io.github.sakkijarvenpolkka.audacity.editor.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiPrefsState(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val showClipping: Boolean = false,
    val showRms: Boolean = false,
    val lastExportFormat: String = "",
    val exportSkipSilence: Boolean = false,
)

/** Small wrapper around SharedPreferences exposing a [StateFlow]. A null
 *  [context] keeps the values in memory only (tests). */
class UiPrefs(context: Context?) {
    private val sp: SharedPreferences? = context?.getSharedPreferences("audacity_ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiPrefsState> = _state.asStateFlow()
    val value: UiPrefsState get() = _state.value

    private fun load(): UiPrefsState {
        val p = sp ?: return UiPrefsState()
        return UiPrefsState(
            theme = runCatching { ThemeChoice.valueOf(p.getString(K_THEME, ThemeChoice.SYSTEM.name)!!) }
                .getOrDefault(ThemeChoice.SYSTEM),
            showClipping = p.getBoolean(K_CLIP, false),
            showRms = p.getBoolean(K_RMS, false),
            lastExportFormat = p.getString(K_EXPORT_FORMAT, "") ?: "",
            exportSkipSilence = p.getBoolean(K_SKIP_SILENCE, false),
        )
    }

    fun update(transform: (UiPrefsState) -> UiPrefsState) {
        val new = transform(_state.value)
        _state.value = new
        sp?.edit()
            ?.putString(K_THEME, new.theme.name)
            ?.putBoolean(K_CLIP, new.showClipping)
            ?.putBoolean(K_RMS, new.showRms)
            ?.putString(K_EXPORT_FORMAT, new.lastExportFormat)
            ?.putBoolean(K_SKIP_SILENCE, new.exportSkipSilence)
            ?.apply()
    }

    private companion object {
        const val K_THEME = "theme"
        const val K_CLIP = "showClipping"
        const val K_RMS = "showRms"
        const val K_EXPORT_FORMAT = "lastExportFormat"
        const val K_SKIP_SILENCE = "exportSkipSilence"
    }
}
