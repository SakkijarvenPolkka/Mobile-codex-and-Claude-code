/*
 * Audacity Android port — UI-side preferences (the engine keeps its own
 * audacity.cfg; these are the settings that only the app reads).
 *
 * Defaults are the 3.7.9 ones (`/GUI/ShowClipping` and `/GUI/ShowRMS` false,
 * GUIPrefs.cpp); the theme follows the system, Audacity's default being Light.
 * Mobile editing defaults differ from the desktop: drags stop at the end of
 * the audio and snap to clip/label edges (desktop: snapping off).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sakkijarvenpolkka.audacity.editor.EditTool
import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.editor.SnapMode
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
    /** EditorState.stopAtTrackEnd: drags stop exactly at the end of the audio (mobile default on). */
    val stopAtTrackEnd: Boolean = true,
    /** Snapping on (View ▸ Snapping); the editor's default is on (SnapMode.EDGES). */
    val snapEnabled: Boolean = true,
    /** Also snap to the ruler's grid lines (SnapMode.EDGES_AND_GRID) while snapping is on. */
    val snapToGrid: Boolean = false,
    /** The razor tool (EditorState.tool = EditTool.SPLIT) is on. */
    val splitTool: Boolean = false,
    /** Last quick format of File ▸ Share Audio ([io.github.sakkijarvenpolkka.audacity.share.ShareFormat] name). */
    val lastShareFormat: String = "",
) {
    /** The editor's snapping mode for these preferences. */
    val snapMode: SnapMode
        get() = when {
            !snapEnabled -> SnapMode.OFF
            snapToGrid -> SnapMode.EDGES_AND_GRID
            else -> SnapMode.EDGES
        }

    /** Applies the editor settings to [state] (start-up, menu toggles). */
    fun applyTo(state: EditorState) {
        if (state.stopAtTrackEnd != stopAtTrackEnd) state.stopAtTrackEnd = stopAtTrackEnd
        if (state.snapping != snapMode) state.snapping = snapMode
        val tool = if (splitTool) EditTool.SPLIT else EditTool.SELECT
        if (state.tool != tool) state.tool = tool
    }

    /** These preferences with the editor settings of [state] (the editor toggles the split tool itself). */
    fun withEditor(state: EditorState): UiPrefsState = copy(
        stopAtTrackEnd = state.stopAtTrackEnd,
        snapEnabled = state.snapping != SnapMode.OFF,
        // Snapping off keeps the grid choice for when it is turned on again
        snapToGrid = if (state.snapping == SnapMode.OFF) snapToGrid else state.snapping == SnapMode.EDGES_AND_GRID,
        splitTool = state.tool == EditTool.SPLIT,
    )
}

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
            stopAtTrackEnd = p.getBoolean(K_STOP_AT_END, true),
            snapEnabled = p.getBoolean(K_SNAP, true),
            snapToGrid = p.getBoolean(K_SNAP_GRID, false),
            splitTool = p.getBoolean(K_SPLIT_TOOL, false),
            lastShareFormat = p.getString(K_SHARE_FORMAT, "") ?: "",
        )
    }

    /** Replaces the state; nothing is written when [transform] changes nothing. */
    @Synchronized
    fun update(transform: (UiPrefsState) -> UiPrefsState) {
        val new = transform(_state.value)
        if (new == _state.value) return
        _state.value = new
        sp?.edit {
            putString(K_THEME, new.theme.name)
            putBoolean(K_CLIP, new.showClipping)
            putBoolean(K_RMS, new.showRms)
            putString(K_EXPORT_FORMAT, new.lastExportFormat)
            putBoolean(K_SKIP_SILENCE, new.exportSkipSilence)
            putBoolean(K_STOP_AT_END, new.stopAtTrackEnd)
            putBoolean(K_SNAP, new.snapEnabled)
            putBoolean(K_SNAP_GRID, new.snapToGrid)
            putBoolean(K_SPLIT_TOOL, new.splitTool)
            putString(K_SHARE_FORMAT, new.lastShareFormat)
        }
    }

    private companion object {
        const val K_THEME = "theme"
        const val K_CLIP = "showClipping"
        const val K_RMS = "showRms"
        const val K_EXPORT_FORMAT = "lastExportFormat"
        const val K_SKIP_SILENCE = "exportSkipSilence"
        const val K_STOP_AT_END = "stopAtTrackEnd"
        const val K_SNAP = "snapEnabled"
        const val K_SNAP_GRID = "snapToGrid"
        const val K_SPLIT_TOOL = "splitTool"
        const val K_SHARE_FORMAT = "lastShareFormat"
    }
}
