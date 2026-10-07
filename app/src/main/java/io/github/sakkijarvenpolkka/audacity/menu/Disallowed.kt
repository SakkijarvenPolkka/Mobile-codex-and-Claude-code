/*
 * Audacity Android port — why a menu item is disabled.
 *
 * Port of CommandManager::TellUserWhyDisallowed (lib-menus/CommandManager.cpp)
 * with the messages of src/CommonCommandFlags.cpp (Audacity 3.7.9, the
 * Audacity Team, GPL-2.0-or-later): the AudioIONotBusy message has priority 1,
 * then the flags in definition order. Flags without a 3.7.9 message get a
 * short Android-specific explanation instead of the desktop catch-all text.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.menu

import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.util.UiText

object Disallowed {

    /**
     * Message for an item named [name] that requires [required] while the
     * engine reports [flags]; null when nothing is missing.
     */
    fun reason(required: Long, flags: Long, name: String, noiseReduction: Boolean = false): UiText? {
        val missing = required and flags.inv()
        if (missing == 0L) return null
        fun has(f: Long) = (missing and f) != 0L
        fun selectAudio() =
            if (noiseReduction) UiText.Res(R.string.why_noise_reduction, listOf(name, name, name))
            else UiText.Res(R.string.why_select_audio, listOf(name))
        return when {
            // Priority 1 (AudioIONotBusyFlag().Priority(1)).
            has(CommandFlags.NB) -> UiText.Res(R.string.why_audio_busy)
            has(CommandFlags.PROJECT_OPEN) -> UiText.Res(R.string.why_no_project)
            has(CommandFlags.ST) -> UiText.Res(R.string.why_stereo)
            has(CommandFlags.TS) -> selectAudio()
            has(CommandFlags.WS) -> UiText.Res(R.string.why_wave_selected)
            has(CommandFlags.ES) || has(CommandFlags.AS) -> UiText.Res(R.string.why_tracks_selected, listOf(name))
            // CutCopyAvailable / JoinClipsAvailable disable the default message on
            // desktop; explain with their underlying conditions.
            has(CommandFlags.CC) -> if ((flags and CommandFlags.TS) == 0L) selectAudio()
            else UiText.Res(R.string.why_tracks_selected, listOf(name))
            has(CommandFlags.JC) -> if ((flags and CommandFlags.TS) == 0L) selectAudio()
            else UiText.Res(R.string.why_join)
            has(CommandFlags.RECORD_PERMISSION) -> UiText.Res(R.string.why_record_permission)
            has(CommandFlags.BUSY) -> UiText.Res(R.string.why_not_playing)
            has(CommandFlags.UA) -> UiText.Res(R.string.why_nothing_to_undo)
            has(CommandFlags.RA) -> UiText.Res(R.string.why_nothing_to_redo)
            has(CommandFlags.CLIPBOARD) -> UiText.Res(R.string.why_clipboard_empty)
            has(CommandFlags.LE) -> UiText.Res(R.string.why_no_labels)
            has(CommandFlags.TE) || has(CommandFlags.WE) || has(CommandFlags.HW) || has(CommandFlags.PLAYABLE) ->
                UiText.Res(R.string.why_no_tracks)
            has(CommandFlags.LAST_EFF) || has(CommandFlags.LAST_GEN) || has(CommandFlags.LAST_ANA) || has(CommandFlags.LAST_TOOL) ->
                UiText.Res(R.string.why_no_last_effect)
            has(CommandFlags.ZI) || has(CommandFlags.ZO) -> UiText.Res(R.string.why_zoom_limit)
            else -> UiText.Res(R.string.why_generic, listOf(name))
        }
    }
}
