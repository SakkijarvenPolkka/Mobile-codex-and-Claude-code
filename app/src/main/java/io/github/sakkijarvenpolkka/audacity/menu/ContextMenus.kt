/*
 * Audacity Android port — context menus (ui-reference.md §3): track control
 * panel menu, clip menu, wave area menu, label menu and Timeline Options.
 *
 * Ports of src/tracks/ui/CommonTrackControls.cpp, WaveTrackControls.cpp,
 * WaveClipUIUtilities.cpp, WaveChannelView.cpp and AdornedRulerPanel.cpp
 * (Audacity 3.7.9, the Audacity Team, GPL-2.0-or-later). Items that do not
 * apply to a track (e.g. Make Stereo Track without a mono track below) are
 * left out instead of greyed.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.menu

import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.editor.ContextTarget
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import io.github.sakkijarvenpolkka.audacity.util.UiText

object ContextMenus {

    /** Rates of the track menu Rate ▸ (WaveTrackControls.cpp:550-600). */
    val TRACK_RATES = listOf(8000, 11025, 16000, 22050, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000)

    private const val NB = CommandFlags.NB
    private const val PO = CommandFlags.PROJECT_OPEN

    private fun item(id: String, label: UiText, required: Long = 0L, checked: ((MenuState) -> Boolean)? = null,
                     action: suspend (MenuHost) -> Unit) =
        MenuItem(id, label, required, null, checked, null, false, action)

    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    private suspend fun askText(h: MenuHost, title: Int, label: Int, initial: String): String? {
        val d = AppDialog.TextInput(UiText.Res(title), UiText.Res(label), initial)
        h.open(d)
        return d.result.await()
    }

    /** Title of the sheet for [target]. */
    fun title(target: ContextTarget, snapshot: Snapshot): UiText = when (target) {
        is ContextTarget.Track -> UiText.Raw(snapshot.track(target.trackId)?.name ?: "")
        is ContextTarget.Clip -> UiText.Raw(
            snapshot.track(target.trackId)?.clips?.getOrNull(target.clipIndex)?.name?.takeIf { it.isNotEmpty() }
                ?: snapshot.track(target.trackId)?.name ?: "",
        )
        is ContextTarget.Label -> UiText.Raw(
            snapshot.track(target.trackId)?.labels?.firstOrNull { it.index == target.index }?.title ?: "",
        )
        is ContextTarget.Timeline -> res(R.string.cm_timeline_options)
        ContextTarget.Empty -> res(R.string.menu_tracks)
    }

    fun forTarget(target: ContextTarget, snapshot: Snapshot, isSpectrogram: (Long) -> Boolean, setSpectrogram: (Long, Boolean) -> Unit): List<MenuNode> =
        when (target) {
            is ContextTarget.Clip -> clipMenu(target, snapshot)
            is ContextTarget.Track -> waveAreaMenu(target.trackId, snapshot)
            is ContextTarget.Label -> labelMenu(target)
            is ContextTarget.Timeline -> timelineMenu()
            ContextTarget.Empty -> emptyAreaMenu()
        }.let { nodes ->
            if (target is ContextTarget.Track) nodes + Separator + trackMenu(target.trackId, snapshot, isSpectrogram, setSpectrogram)
            else nodes
        }

    /** Clip title bar ⋯ / long-press on a clip (WaveClipUIUtilities.cpp:78-93). */
    private fun clipMenu(t: ContextTarget.Clip, snapshot: Snapshot): List<MenuNode> {
        val track = snapshot.track(t.trackId)
        return listOf(
            item("Cut", res(R.string.m_cut), NB) { h ->
                h.engine.selectClip(t.trackId, t.clipIndex, t.generation)
                h.engine.edit("edit.cut")
            },
            item("Copy", res(R.string.m_copy), NB) { h ->
                h.engine.selectClip(t.trackId, t.clipIndex, t.generation)
                h.engine.edit("edit.copy")
            },
            item("Paste", res(R.string.m_paste), NB or PO) { h -> h.engine.edit("edit.paste") },
            Separator,
            item("Split", res(R.string.cm_split_clip), NB) { h ->
                h.engine.selectTracks(listOf(t.trackId), "set")
                h.engine.edit("edit.split")
            },
            item("Join", res(R.string.cm_join_clips), NB) { h ->
                h.engine.selectTracks(listOf(t.trackId), "set")
                h.engine.edit("edit.join")
            },
            item("TrackMute", res(if (track?.mute == true) R.string.cm_unmute_track else R.string.cm_mute_track), 0L) { h ->
                h.engine.setTrackMute(t.trackId, !(h.engine.snapshot.value.track(t.trackId)?.mute ?: false))
            },
            Separator,
            item("RenameClip", res(R.string.m_rename_clip), NB) { h ->
                val clip = h.engine.snapshot.value.track(t.trackId)?.clips?.getOrNull(t.clipIndex)
                val name = askText(h, R.string.m_rename_clip, R.string.clip_name, clip?.name ?: "") ?: return@item
                h.engine.renameClip(t.trackId, t.clipIndex, t.generation, name)
            },
        )
    }

    /** Long-press in the wave area outside clips (WaveChannelView.cpp:884-912). */
    private fun waveAreaMenu(trackId: Long, snapshot: Snapshot): List<MenuNode> {
        val track = snapshot.track(trackId) ?: return emptyList()
        if (!track.isWave) return emptyList()
        return listOf(
            item("Paste", res(R.string.m_paste), NB or PO) { h -> h.engine.edit("edit.paste") },
            Separator,
            item("TrackMute", res(if (track.mute) R.string.cm_unmute_track else R.string.cm_mute_track)) { h ->
                h.engine.setTrackMute(trackId, !track.mute)
            },
        )
    }

    private fun labelMenu(t: ContextTarget.Label): List<MenuNode> = listOf(
        item("EditLabel", res(R.string.cm_edit_label), PO) { h -> editLabelText(h, t.trackId, t.index) },
        item("SelectLabel", res(R.string.cm_select_label)) { h ->
            val l = h.engine.snapshot.value.track(t.trackId)?.labels?.firstOrNull { it.index == t.index } ?: return@item
            h.engine.selectTracks(listOf(t.trackId), "set")
            h.engine.select(l.t0, l.t1)
        },
        Separator,
        item("DeleteLabel", res(R.string.cm_delete_label), NB) { h -> h.engine.removeLabel(t.trackId, t.index) },
    )

    /** Edit a label's text (double-tap / long-press on a label). */
    suspend fun editLabelText(h: MenuHost, trackId: Long, index: Int) {
        val l = h.engine.snapshot.value.track(trackId)?.labels?.firstOrNull { it.index == index } ?: return
        val d = AppDialog.TextInput(UiText.Res(R.string.cm_edit_label), UiText.Res(R.string.label_text), l.title, allowEmpty = true)
        h.open(d)
        val text = d.result.await() ?: return
        if (text != l.title) h.engine.editLabel(trackId, index, title = text)
    }

    /** Timeline Options (AdornedRulerPanel.cpp:2214-2268). */
    private fun timelineMenu(): List<MenuNode> = listOf(
        item("MinutesAndSeconds", res(R.string.m_minutes_seconds), checked = { true }) { },
        Separator,
        item("TogglePlayRegion", res(R.string.m_toggle_play_region), PO, checked = { it.snapshot.playRegion.active }) { h ->
            h.engine.togglePlayRegion()
        },
        item("ClearPlayRegion", res(R.string.m_clear_play_region), PO) { h -> h.engine.clearPlayRegion() },
        item("SetPlayRegionToSelection", res(R.string.m_set_play_region_to_selection), PO) { h ->
            val s = h.engine.snapshot.value.selection
            if (s.t1 > s.t0) h.engine.setPlayRegion(s.t0, s.t1, true)
        },
        Separator,
        item("UpdateTrackIndicator", res(R.string.cm_scroll_to_playhead), checked = { it.followPlayhead }) { h ->
            h.editor?.let { e -> e.followPlayhead = !e.followPlayhead }
        },
    )

    private fun emptyAreaMenu(): List<MenuNode> = listOf(
        item("NewMonoTrack", res(R.string.m_new_mono_track), NB or PO) { h -> h.engine.addTrack("mono") },
        item("NewStereoTrack", res(R.string.m_new_stereo_track), NB or PO) { h -> h.engine.addTrack("stereo") },
        item("NewLabelTrack", res(R.string.m_new_label_track), NB or PO) { h -> h.engine.addTrack("label") },
        Separator,
        item("SelectNone", res(R.string.m_select_none), CommandFlags.TE) { h -> h.engine.selectNone() },
    )

    /** Track control panel ⋯ menu (CommonTrackControls.cpp:120-170, WaveTrackControls.cpp). */
    fun trackMenu(trackId: Long, snapshot: Snapshot, isSpectrogram: (Long) -> Boolean, setSpectrogram: (Long, Boolean) -> Unit): List<MenuNode> {
        val track = snapshot.track(trackId) ?: return emptyList()
        val idx = snapshot.tracks.indexOfFirst { it.id == trackId }
        val nodes = ArrayList<MenuNode>()
        nodes += item("TrackName", res(R.string.cm_rename_track), NB) { h ->
            val name = askText(h, R.string.cm_rename_track, R.string.track_name, track.name) ?: return@item
            if (name != track.name) h.engine.renameTrack(trackId, name)
        }
        nodes += Separator
        if (idx > 0) {
            nodes += item("TrackMoveUp", res(R.string.cm_move_up), NB) { h -> h.engine.moveTrack(trackId, "up") }
        }
        if (idx < snapshot.tracks.lastIndex) {
            nodes += item("TrackMoveDown", res(R.string.cm_move_down), NB) { h -> h.engine.moveTrack(trackId, "down") }
        }
        if (idx > 0) {
            nodes += item("TrackMoveTop", res(R.string.cm_move_top), NB) { h -> h.engine.moveTrack(trackId, "top") }
        }
        if (idx < snapshot.tracks.lastIndex) {
            nodes += item("TrackMoveBottom", res(R.string.cm_move_bottom), NB) { h -> h.engine.moveTrack(trackId, "bottom") }
        }
        if (track.isWave) nodes += waveTrackItems(track, snapshot, idx, isSpectrogram, setSpectrogram)
        nodes += Separator
        nodes += item("TrackClose", res(R.string.cm_remove_track), NB) { h -> h.engine.removeTracks(listOf(trackId)) }
        return nodes
    }

    private fun waveTrackItems(
        track: TrackState, snapshot: Snapshot, idx: Int,
        isSpectrogram: (Long) -> Boolean, setSpectrogram: (Long, Boolean) -> Unit,
    ): List<MenuNode> {
        val id = track.id
        val out = ArrayList<MenuNode>()
        out += Separator
        // View mode radio group (stored UI-side; WaveformView/SpectrumView menu items).
        out += item("Waveform", res(R.string.cm_waveform), checked = { !isSpectrogram(id) }) { setSpectrogram(id, false) }
        out += item("Spectrogram", res(R.string.cm_spectrogram), checked = { isSpectrogram(id) }) { setSpectrogram(id, true) }
        out += Separator
        val below = snapshot.tracks.getOrNull(idx + 1)
        if (track.channels == 1 && below != null && below.isWave && below.channels == 1) {
            out += item("MakeStereo", res(R.string.cm_make_stereo), NB) { h -> h.engine.trackChannelCommand("tracks.makeStereo", id) }
        }
        if (track.channels == 2) {
            out += item("SwapChannels", res(R.string.cm_swap_channels), NB) { h -> h.engine.trackChannelCommand("tracks.swapChannels", id) }
            out += item("SplitStereo", res(R.string.cm_split_stereo), NB) { h -> h.engine.trackChannelCommand("tracks.splitStereo", id) }
            out += item("SplitStereoToMono", res(R.string.cm_split_stereo_mono), NB) { h -> h.engine.trackChannelCommand("tracks.splitStereoToMono", id) }
        }
        out += Separator
        out += SubMenu(
            "Format", res(R.string.cm_format),
            listOf("int16" to R.string.fmt_int16, "int24" to R.string.fmt_int24, "float" to R.string.fmt_float).map { (fmt, label) ->
                item("Format:$fmt", res(label), NB, checked = { st -> st.snapshot.track(id)?.format == fmt }) { h ->
                    h.engine.setTrackFormat(id, fmt)
                }
            },
        )
        val rateItems = ArrayList<MenuNode>()
        TRACK_RATES.forEach { rate ->
            rateItems += item("Rate:$rate", res(R.string.rate_hz, rate), NB, checked = { st -> st.snapshot.track(id)?.rate?.toInt() == rate }) { h ->
                h.engine.setTrackRate(id, rate)
            }
        }
        rateItems += item("Rate:other", res(R.string.cm_rate_other), NB,
            checked = { st -> st.snapshot.track(id)?.rate?.toInt()?.let { it !in TRACK_RATES } ?: false }) { h ->
            val cur = h.engine.snapshot.value.track(id)?.rate?.toInt() ?: 44100
            val text = askText(h, R.string.cm_set_rate_title, R.string.cm_rate_label, cur.toString()) ?: return@item
            val rate = text.trim().toIntOrNull()
            if (rate == null || rate < 1 || rate > 1_000_000) h.message(UiText.Res(R.string.msg_invalid_rate))
            else h.engine.setTrackRate(id, rate)
        }
        out += SubMenu("Rate", res(R.string.cm_rate), rateItems)
        return out
    }
}
