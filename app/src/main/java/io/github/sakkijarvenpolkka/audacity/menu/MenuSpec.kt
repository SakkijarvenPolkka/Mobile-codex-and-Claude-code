/*
 * Audacity Android port — the menu tree of Audacity 3.7.9 (Linux build),
 * restricted to what makes sense on Android.
 *
 * Source of truth: ui-reference.md §2 (from src/menus/FileMenus.cpp,
 * EditMenus.cpp, SelectMenus.cpp, ViewMenus.cpp, TransportMenus.cpp,
 * TrackMenus.cpp, PluginMenus.cpp, HelpMenus.cpp of Audacity 3.7.9, the
 * Audacity Team, GPL-2.0-or-later). Item order, CommandIDs, enable flags and
 * the default shortcuts of the "Standard" set are kept; † (full-set-only)
 * shortcuts are not bound.
 *
 * Left out on Android (no engine support, desktop-only or later phase):
 *   File: Open From Cloud, Save To Cloud, Export MIDI, Import MIDI, Import Raw
 *         Data, Share Audio (audio.com), Exit.
 *   Edit: Pitch and Speed..., Render Pitch and Speed, Pitch Up/Down (no clip
 *         stretching commands), Typing Creates New Labels (soft keyboard),
 *         Labeled Audio ▸ (no label-region edit commands).
 *   Select: In All Sync-Locked Tracks, Spectral ▸.
 *   View: Mixer, Toolbars ▸, Beats and Measures, Extra Menus.
 *   Transport: Timer Record, Punch and Roll, Scrubbing ▸, sound-activated
 *         recording, Continuous scrolling (pinned head).
 *   Tracks: Add Time Track.
 *   Generate/Effect/Analyze/Tools: Plugin Manager, Add Realtime Effects, Get
 *         more effects, Macros, Reset Configuration, Run Benchmark.
 *   Help: Generate Support Data, MIDI Device Info, audio.com account, Check
 *         for Updates.
 * Android additions: File ▸ Projects... (project manager); File ▸ Compact
 * Project (commented out in 3.7.9's FileMenus.cpp, Bug 2600; phones need the
 * space back), with the desktop's ProjectFileManager::Compact question.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.menu

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppScreen
import io.github.sakkijarvenpolkka.audacity.CreatePurpose
import io.github.sakkijarvenpolkka.audacity.HostRequest
import io.github.sakkijarvenpolkka.audacity.OpenPurpose
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import io.github.sakkijarvenpolkka.audacity.files.LabelFormat
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Flag shorthands of ui-reference.md §1.2.
private const val NB = CommandFlags.NB
private const val BUSY = CommandFlags.BUSY
private const val TS = CommandFlags.TS
private const val WS = CommandFlags.WS
private const val TE = CommandFlags.TE
private const val ES = CommandFlags.ES
private const val AS = CommandFlags.AS
private const val LE = CommandFlags.LE
private const val UA = CommandFlags.UA
private const val RA = CommandFlags.RA
private const val ZI = CommandFlags.ZI
private const val ZO = CommandFlags.ZO
private const val WE = CommandFlags.WE
private const val ST = CommandFlags.ST
private const val CS = CommandFlags.CS
private const val CC = CommandFlags.CC
private const val JC = CommandFlags.JC
private const val LAST_EFF = CommandFlags.LAST_EFF
private const val LAST_GEN = CommandFlags.LAST_GEN
private const val LAST_ANA = CommandFlags.LAST_ANA
private const val LAST_TOOL = CommandFlags.LAST_TOOL
private const val PO = CommandFlags.PROJECT_OPEN

object MenuSpec {

    // ------------------------------------------------------------------
    // builders
    // ------------------------------------------------------------------

    private fun item(
        id: String,
        @StringRes label: Int,
        required: Long = 0L,
        shortcut: Shortcut? = null,
        checked: ((MenuState) -> Boolean)? = null,
        dynamicLabel: ((MenuState) -> UiText?)? = null,
        action: suspend (MenuHost) -> Unit,
    ) = MenuItem(id, UiText.Res(label), required, shortcut, checked, dynamicLabel, false, action)

    private fun sub(id: String, @StringRes label: Int, vararg children: MenuNode) =
        SubMenu(id, UiText.Res(label), children.toList())

    private val SEP = Separator

    private fun key(k: Key, name: String, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        Shortcut(k, name, ctrl, shift, alt)

    private fun ctrl(k: Key, name: String, shift: Boolean = false, alt: Boolean = false) = Shortcut(k, name, true, shift, alt)

    private suspend fun edit(h: MenuHost, command: String) = h.engine.edit(command)

    private fun snap(h: MenuHost): Snapshot = h.engine.snapshot.value

    // ------------------------------------------------------------------
    // File — FileMenus.cpp:457-566
    // ------------------------------------------------------------------

    private fun fileMenu() = TopMenu(
        "File", R.string.menu_file, listOf(
            item("New", R.string.m_new, NB, ctrl(Key.N, "N")) { it.newProject() },
            item("Open", R.string.m_open, NB, ctrl(Key.O, "O")) {
                it.request(HostRequest.OpenDocuments(OpenPurpose.OPEN, listOf("*/*"), multiple = true))
            },
            DynamicItems("RecentFiles") { st ->
                val recent = st.recentProjects.sortedByDescending { p -> p.modifiedMs }.take(12)
                if (recent.isEmpty()) emptyList()
                else listOf(
                    SubMenu("RecentFiles", UiText.Res(R.string.m_recent_files), recent.map { p ->
                        MenuItem("Recent:${p.path}", UiText.Raw(p.name.ifEmpty { p.path.substringAfterLast('/') }), NB) { h ->
                            h.openProjectFile(p.path)
                        }
                    }),
                )
            },
            item("Projects", R.string.m_projects) { it.show(AppScreen.PROJECTS) },
            SEP,
            sub(
                "SaveMenu", R.string.m_save_menu,
                item("Save", R.string.m_save, NB or PO, ctrl(Key.S, "S")) { it.saveProject() },
                item("SaveAs", R.string.m_save_as, NB or PO) { it.saveProjectAs() },
                item("SaveCopy", R.string.m_save_copy, NB or PO) { h ->
                    val name = snap(h).project.name.ifEmpty { "Untitled" }
                    h.request(HostRequest.CreateDocument(CreatePurpose.BackupProject, "$name.aup3", "application/octet-stream"))
                },
            ),
            item("Compact", R.string.m_compact, NB or PO) { h -> compactProject(h) },
            SEP,
            item("Export", R.string.m_export_audio, NB or WE, ctrl(Key.E, "E", shift = true)) { it.open(AppDialog.Export()) },
            sub(
                "ExportOther", R.string.m_export_other,
                item("ExportLabels", R.string.m_export_labels, NB or LE) { h -> exportLabels(h) },
            ),
            sub(
                "Import", R.string.m_import,
                item("ImportAudio", R.string.m_import_audio, NB or PO, ctrl(Key.I, "I", shift = true)) {
                    it.request(HostRequest.OpenDocuments(OpenPurpose.IMPORT_AUDIO, IMPORT_MIME_TYPES, multiple = true))
                },
                item("ImportLabels", R.string.m_import_labels, NB or PO) {
                    it.request(HostRequest.OpenDocuments(OpenPurpose.IMPORT_LABELS, LabelFormat.IMPORT_MIME_TYPES, multiple = false))
                },
            ),
            SEP,
            item("Close", R.string.m_close, NB or PO, ctrl(Key.W, "W")) { it.closeProject() },
        ),
    )

    /** MIME types for the audio import picker (import-export-project.md §5.2). */
    val IMPORT_MIME_TYPES = listOf("audio/*", "video/*", "application/ogg", "application/octet-stream")

    // ------------------------------------------------------------------
    // Edit — EditMenus.cpp:1127-1250, LabelMenus.cpp:693-794
    // ------------------------------------------------------------------

    private fun editMenu() = TopMenu(
        "Edit", R.string.menu_edit, listOf(
            item("Undo", R.string.m_undo, NB or UA, ctrl(Key.Z, "Z"),
                dynamicLabel = { st -> st.snapshot.history.undo.takeIf { st.snapshot.history.canUndo && it.isNotEmpty() }?.let { UiText.Res(R.string.m_undo_fmt, listOf(it)) } },
            ) { it.engine.undo() },
            item("Redo", R.string.m_redo, NB or RA, ctrl(Key.Z, "Z", shift = true),
                dynamicLabel = { st -> st.snapshot.history.redo.takeIf { st.snapshot.history.canRedo && it.isNotEmpty() }?.let { UiText.Res(R.string.m_redo_fmt, listOf(it)) } },
            ) { it.engine.redo() },
            SEP,
            item("Cut", R.string.m_cut, NB or CC, ctrl(Key.X, "X")) { edit(it, "edit.cut") },
            item("Delete", R.string.m_delete, NB or ES or TS, ctrl(Key.K, "K")) { edit(it, "edit.delete") },
            item("Copy", R.string.m_copy, NB or CC, ctrl(Key.C, "C")) { edit(it, "edit.copy") },
            item("Paste", R.string.m_paste, NB or PO, ctrl(Key.V, "V")) { edit(it, "edit.paste") },
            item("Duplicate", R.string.m_duplicate, NB or TS or ES, ctrl(Key.D, "D")) { edit(it, "edit.duplicate") },
            sub(
                "RemoveSpecial", R.string.m_remove_special,
                item("SplitCut", R.string.m_split_cut, NB or TS or ES, ctrl(Key.X, "X", alt = true)) { edit(it, "edit.splitCut") },
                item("SplitDelete", R.string.m_split_delete, NB or TS or ES, ctrl(Key.K, "K", alt = true)) { edit(it, "edit.splitDelete") },
                SEP,
                item("Silence", R.string.m_silence, NB or TS or WS, ctrl(Key.L, "L")) { edit(it, "edit.silence") },
                item("Trim", R.string.m_trim, NB or TS or WS, ctrl(Key.T, "T")) { edit(it, "edit.trim") },
            ),
            SEP,
            sub(
                "ClipsMenu", R.string.m_audio_clips,
                item("Split", R.string.m_split, NB or WS, ctrl(Key.I, "I")) { edit(it, "edit.split") },
                item("SplitNew", R.string.m_split_new, NB or TS or WS) { edit(it, "edit.splitNew") },
                SEP,
                item("Join", R.string.m_join, JC, ctrl(Key.J, "J")) { edit(it, "edit.join") },
                item("Disjoin", R.string.m_disjoin, NB or TS or ES) { edit(it, "edit.detachAtSilences") },
                SEP,
                // CLIPSEL is reserved (0) in v1: rename the clip under the cursor of the first selected wave track.
                item("RenameClip", R.string.m_rename_clip, NB or WS) { h -> renameClipAtCursor(h) },
            ),
            sub(
                "LabelEditMenu", R.string.m_labels,
                item("EditLabels", R.string.m_label_editor, NB or PO) { it.open(AppDialog.LabelEditor) },
                SEP,
                item("AddLabel", R.string.m_add_label, PO, ctrl(Key.B, "B")) { h ->
                    val title = askText(h, R.string.m_add_label, R.string.label_text, "", allowEmpty = true) ?: return@item
                    h.engine.addLabel(title)
                },
                item("AddLabelPlaying", R.string.m_add_label_playing, BUSY, ctrl(Key.M, "M")) { h ->
                    val tr = h.engine.readTransport()
                    val t = tr.headTime(System.nanoTime()).takeIf { !it.isNaN() } ?: snap(h).selection.t0
                    val title = askText(h, R.string.m_add_label, R.string.label_text, "", allowEmpty = true) ?: return@item
                    h.engine.select(t, t)
                    h.engine.addLabel(title)
                },
                item("PasteNewLabel", R.string.m_paste_new_label, NB or PO) { h ->
                    val text = h.clipboardText()
                    if (text.isNullOrEmpty()) h.message(UiText.Res(R.string.msg_clipboard_no_text))
                    else h.engine.addLabel(text)
                },
            ),
            item("EditMetaData", R.string.m_metadata, NB or PO) { it.open(AppDialog.Tags) },
            SEP,
            item("Preferences", R.string.m_preferences, NB, ctrl(Key.P, "P")) { it.show(AppScreen.PREFERENCES) },
        ),
    )

    // ------------------------------------------------------------------
    // Select — SelectMenus.cpp:954-1040, ClipMenus.cpp:744-768
    // ------------------------------------------------------------------

    private fun selectMenu() = TopMenu(
        "Select", R.string.menu_select, listOf(
            item("SelectAll", R.string.m_select_all, TE, ctrl(Key.A, "A")) { it.engine.selectAll() },
            item("SelectNone", R.string.m_select_none, TE) { it.engine.selectNone() },
            sub(
                "Tracks", R.string.m_select_tracks,
                item("SelAllTracks", R.string.m_sel_all_tracks, TE, ctrl(Key.K, "K", shift = true)) { it.engine.selectCommand("select.allTracks") },
            ),
            sub(
                "Region", R.string.m_region,
                item("SetLeftSelection", R.string.m_set_left_selection, TE, key(Key.LeftBracket, "[")) { h -> setBoundary(h, left = true) },
                item("SetRightSelection", R.string.m_set_right_selection, TE, key(Key.RightBracket, "]")) { h -> setBoundary(h, left = false) },
                item("SelTrackStartToCursor", R.string.m_sel_track_start_to_cursor, 0L, key(Key.J, "J", shift = true)) { it.engine.selectCommand("select.startToCursor") },
                item("SelCursorToTrackEnd", R.string.m_sel_cursor_to_track_end, 0L, key(Key.K, "K", shift = true)) { it.engine.selectCommand("select.cursorToEnd") },
                item("SelTrackStartToEnd", R.string.m_sel_track_start_to_end) { it.engine.selectCommand("select.trackStartToEnd") },
                SEP,
                item("SelSave", R.string.m_sel_save, WS) { h -> h.storedSelection = snap(h).selection },
                item("SelRestore", R.string.m_sel_restore, TE) { h ->
                    val s = h.storedSelection
                    if (s == null) h.message(UiText.Res(R.string.msg_no_stored_selection)) else h.engine.select(s.t0, s.t1)
                },
            ),
            sub(
                "Clips", R.string.m_audio_clips,
                item("SelPrevClipBoundaryToCursor", R.string.m_sel_prev_clip_boundary, WE) { h ->
                    val s = snap(h)
                    val b = clipBoundaries(s).lastOrNull { it < s.selection.t0 - EPS }
                    if (b != null) h.engine.select(b, s.selection.t1)
                },
                item("SelCursorToNextClipBoundary", R.string.m_sel_next_clip_boundary, WE) { h ->
                    val s = snap(h)
                    val b = clipBoundaries(s).firstOrNull { it > s.selection.t1 + EPS }
                    if (b != null) h.engine.select(s.selection.t0, b)
                },
                item("SelPrevClip", R.string.m_sel_prev_clip, WE, key(Key.Comma, ",", alt = true)) { it.engine.selectCommand("select.prevClip") },
                item("SelNextClip", R.string.m_sel_next_clip, WE, key(Key.Period, ".", alt = true)) { it.engine.selectCommand("select.nextClip") },
            ),
            SEP,
            item("SelCursorStoredCursor", R.string.m_sel_cursor_stored, TE) { h ->
                val c = h.storedCursor
                val t0 = snap(h).selection.t0
                if (c == null) h.message(UiText.Res(R.string.msg_no_stored_cursor)) else h.engine.select(min(c, t0), max(c, t0))
            },
            item("StoreCursorPosition", R.string.m_store_cursor, WE) { h ->
                val tr = h.engine.readTransport()
                val head = tr.headTime(System.nanoTime())
                h.storedCursor = if (tr.isActive && !head.isNaN()) head else snap(h).selection.t0
            },
            SEP,
            // ES on desktop; the engine also needs the audio idle (NB)
            item("ZeroCross", R.string.m_zero_crossings, NB or ES, key(Key.Z, "Z")) { it.engine.selectCommand("select.zeroCrossing") },
        ),
    )

    // ------------------------------------------------------------------
    // View — ViewMenus.cpp:297-351
    // ------------------------------------------------------------------

    private fun viewMenu() = TopMenu(
        "View", R.string.menu_view, listOf(
            sub(
                "Zoom", R.string.m_zoom,
                item("ZoomIn", R.string.m_zoom_in, ZI, ctrl(Key.One, "1")) { it.editor?.zoomIn() },
                item("ZoomNormal", R.string.m_zoom_normal, TE, ctrl(Key.Two, "2")) { it.editor?.zoomNormal() },
                item("ZoomOut", R.string.m_zoom_out, ZO, ctrl(Key.Three, "3")) { it.editor?.zoomOut() },
                item("ZoomSel", R.string.m_zoom_sel, TS, ctrl(Key.E, "E")) { h ->
                    val s = snap(h).selection
                    h.editor?.zoomToSelection(s.t0, s.t1)
                },
                item("ZoomToggle", R.string.m_zoom_toggle, TE, key(Key.Z, "Z", shift = true)) { h -> zoomToggle(h) },
            ),
            sub(
                "TrackSize", R.string.m_track_size,
                item("FitInWindow", R.string.m_fit_in_window, TE, ctrl(Key.F, "F")) { h -> h.editor?.zoomToFit(snap(h).projectEnd) },
                item("FitV", R.string.m_fit_v, TE, ctrl(Key.F, "F", shift = true)) { h -> fitVertically(h) },
                item("CollapseAllTracks", R.string.m_collapse_all, TE, ctrl(Key.C, "C", shift = true)) { h ->
                    val e = h.editor ?: return@item
                    snap(h).tracks.forEach { t -> if (!e.isCollapsed(t.id)) e.toggleCollapsed(t.id) }
                },
                item("ExpandAllTracks", R.string.m_expand_all, TE, ctrl(Key.X, "X", shift = true)) { h ->
                    val e = h.editor ?: return@item
                    snap(h).tracks.forEach { t -> if (e.isCollapsed(t.id)) e.toggleCollapsed(t.id) }
                },
            ),
            sub(
                "SkipTo", R.string.m_skip_to,
                item("SkipSelStart", R.string.m_skip_sel_start, TS) { h -> h.editor?.scrollToTime(snap(h).selection.t0) },
                item("SkipSelEnd", R.string.m_skip_sel_end, TS) { h -> h.editor?.scrollToTime(snap(h).selection.t1) },
            ),
            SEP,
            item("UndoHistory", R.string.m_history, NB or PO) { it.open(AppDialog.History) },
            SEP,
            sub(
                "Timeline", R.string.m_timeline,
                item("MinutesAndSeconds", R.string.m_minutes_seconds, checked = { true }) { },
            ),
            SEP,
            item("ShowClipping", R.string.m_show_clipping, checked = { it.showClipping }) { h ->
                h.uiPrefs.update { p -> p.copy(showClipping = !p.showClipping) }
            },
            item("ShowRMS", R.string.m_show_rms, checked = { it.showRms }) { h ->
                h.uiPrefs.update { p -> p.copy(showRms = !p.showRms) }
            },
        ),
    )

    // ------------------------------------------------------------------
    // Transport — TransportMenus.cpp:726-845
    // ------------------------------------------------------------------

    private fun transportMenu() = TopMenu(
        "Transport", R.string.menu_transport, listOf(
            sub(
                "PlayStopMenu", R.string.m_playing,
                item("DefaultPlayStop", R.string.m_play_stop, CS or PO, key(Key.Spacebar, "Space")) { h -> playStop(h, setCursor = false) },
                item("PlayStopSelect", R.string.m_play_stop_select, CS or PO, key(Key.X, "X")) { h -> playStop(h, setCursor = true) },
                item("OncePlayStop", R.string.m_play_once, CS or PO, key(Key.Spacebar, "Space", shift = true)) { h -> playStop(h, setCursor = false) },
                item("Pause", R.string.m_pause, CS or PO, key(Key.P, "P")) { it.engine.pause() },
            ),
            sub(
                "RecordMenu", R.string.m_recording,
                item("Record1stChoice", R.string.m_record, NB or CS or PO, key(Key.R, "R")) { it.record(false) },
                item("Record2ndChoice", R.string.m_record_new_track, NB or CS or PO, key(Key.R, "R", shift = true)) { it.record(true) },
                SEP,
                item("Pause", R.string.m_pause, CS or PO) { it.engine.pause() },
            ),
            sub(
                "CursorTo", R.string.m_cursor_to,
                item("CursSelStart", R.string.m_curs_sel_start, TS) { h -> snap(h).selection.t0.let { h.engine.select(it, it) } },
                item("CursSelEnd", R.string.m_curs_sel_end, TS) { h -> snap(h).selection.t1.let { h.engine.select(it, it) } },
                item("CursTrackStart", R.string.m_curs_track_start, ES, key(Key.J, "J")) { it.engine.selectCommand("select.cursorToTrackStart") },
                item("CursTrackEnd", R.string.m_curs_track_end, ES, key(Key.K, "K")) { it.engine.selectCommand("select.cursorToTrackEnd") },
                item("CursPrevClipBoundary", R.string.m_curs_prev_clip_boundary, WE) { it.engine.selectCommand("select.prevClipBoundary") },
                item("CursNextClipBoundary", R.string.m_curs_next_clip_boundary, WE) { it.engine.selectCommand("select.nextClipBoundary") },
                item("CursProjectStart", R.string.m_curs_project_start, NB or CS or PO, key(Key.MoveHome, "Home")) { it.engine.skipToStart() },
                item("CursProjectEnd", R.string.m_curs_project_end, NB or CS or PO, key(Key.MoveEnd, "End")) { it.engine.skipToEnd() },
            ),
            SEP,
            sub(
                "Looping", R.string.m_looping,
                item("TogglePlayRegion", R.string.m_toggle_play_region, PO, key(Key.L, "L"), checked = { it.snapshot.playRegion.active }) {
                    it.engine.togglePlayRegion()
                },
                item("ClearPlayRegion", R.string.m_clear_play_region, PO, key(Key.L, "L", shift = true, alt = true)) { it.engine.clearPlayRegion() },
                item("SetPlayRegionToSelection", R.string.m_set_play_region_to_selection, PO, key(Key.L, "L", shift = true)) { h ->
                    val s = snap(h).selection
                    if (s.t1 > s.t0) h.engine.setPlayRegion(s.t0, s.t1, true)
                },
                item("SetPlayRegionIn", R.string.m_set_play_region_in, PO) { h -> setLoopEdge(h, inPoint = true) },
                item("SetPlayRegionOut", R.string.m_set_play_region_out, PO) { h -> setLoopEdge(h, inPoint = false) },
            ),
            SEP,
            item("RescanDevices", R.string.m_rescan_devices, NB or CS) { h ->
                val d = h.engine.audioDevices()
                h.message(UiText.Res(R.string.msg_devices_rescanned, listOf(d.outputs.size, d.inputs.size)))
            },
            sub(
                "Options", R.string.m_transport_options,
                item("Overdub", R.string.m_overdub, NB or CS, checked = { it.settings?.overdub ?: true }) { h ->
                    val cur = h.engine.getSettings().overdub ?: true
                    h.updateSettings(Settings(overdub = !cur))
                },
                item("SWPlaythrough", R.string.m_sw_playthrough, NB or CS, checked = { it.settings?.swPlaythrough ?: false }) { h ->
                    val cur = h.engine.getSettings().swPlaythrough ?: false
                    h.updateSettings(Settings(swPlaythrough = !cur))
                },
            ),
        ),
    )

    // ------------------------------------------------------------------
    // Tracks — TrackMenus.cpp:1122-1265
    // ------------------------------------------------------------------

    private fun tracksMenu() = TopMenu(
        "Tracks", R.string.menu_tracks, listOf(
            sub(
                "Add", R.string.m_add_new,
                item("NewMonoTrack", R.string.m_new_mono_track, NB or PO) { it.engine.addTrack("mono") },
                item("NewStereoTrack", R.string.m_new_stereo_track, NB or PO) { it.engine.addTrack("stereo") },
                item("NewLabelTrack", R.string.m_new_label_track, NB or PO) { it.engine.addTrack("label") },
            ),
            SEP,
            sub(
                "Mix", R.string.m_mix,
                DynamicItems("StereoToMono") { st ->
                    val fx = st.effects?.effects?.firstOrNull { e -> e.name.equals("Stereo To Mono", ignoreCase = true) }
                    if (fx == null) emptyList()
                    else listOf(MenuItem("Stereo to Mono", UiText.Res(R.string.m_stereo_to_mono), NB or ST or WS) { h -> h.engine.applyEffect(fx.id) })
                },
                item("MixAndRender", R.string.m_mix_and_render, NB or WS) { it.engine.mixAndRender(false) },
                item("MixAndRenderToNewTrack", R.string.m_mix_and_render_new, NB or WS) { it.engine.mixAndRender(true) },
            ),
            item("Resample", R.string.m_resample, NB or WS) { it.open(AppDialog.Resample) },
            SEP,
            item("RemoveTracks", R.string.m_remove_tracks, NB or AS) { h ->
                h.engine.removeTracks(snap(h).selectedTracks.map { t -> t.id })
            },
            SEP,
            sub(
                "MuteUnmute", R.string.m_mute_unmute,
                item("MuteAllTracks", R.string.m_mute_all, TE, ctrl(Key.U, "U")) { it.engine.muteAllTracks(true) },
                item("UnmuteAllTracks", R.string.m_unmute_all, TE, ctrl(Key.U, "U", shift = true)) { it.engine.muteAllTracks(false) },
                item("MuteTracks", R.string.m_mute_tracks, ES, ctrl(Key.U, "U", alt = true)) { h -> setMute(h, playable(snap(h).selectedTracks), true) },
                item("UnmuteTracks", R.string.m_unmute_tracks, ES, ctrl(Key.U, "U", shift = true, alt = true)) { h -> setMute(h, playable(snap(h).selectedTracks), false) },
            ),
            sub(
                "PanTrack", R.string.m_pan,
                item("PanLeft", R.string.m_pan_left, ES) { h -> setPan(h, -1.0) },
                item("PanRight", R.string.m_pan_right, ES) { h -> setPan(h, 1.0) },
                item("PanCenter", R.string.m_pan_center, ES) { h -> setPan(h, 0.0) },
            ),
            SEP,
            sub(
                "Align", R.string.m_align_tracks,
                item("Align_EndToEnd", R.string.m_align_end_to_end, NB or ES) { it.engine.alignTracks("endToEnd") },
                item("Align_Together", R.string.m_align_together, NB or ES) { it.engine.alignTracks("together") },
                SEP,
                // moveSelection = the /GUI/MoveSelectionWithTracks setting (engine default)
                item("Align_StartToZero", R.string.m_align_start_to_zero, NB or ES) { it.engine.alignTracks("startToZero") },
                item("Align_StartToSelStart", R.string.m_align_start_to_sel_start, NB or ES) { it.engine.alignTracks("startToCursor") },
                item("Align_StartToSelEnd", R.string.m_align_start_to_sel_end, NB or ES) { it.engine.alignTracks("startToSelEnd") },
                item("Align_EndToSelStart", R.string.m_align_end_to_sel_start, NB or ES) { it.engine.alignTracks("endToCursor") },
                item("Align_EndToSelEnd", R.string.m_align_end_to_sel_end, NB or ES) { it.engine.alignTracks("endToSelEnd") },
                SEP,
                item("MoveSelectionWithTracks", R.string.m_move_selection_with_tracks, checked = { it.settings?.moveSelectionWithTracks ?: false }) { h ->
                    val cur = h.engine.getSettings().moveSelectionWithTracks ?: false
                    h.updateSettings(Settings(moveSelectionWithTracks = !cur))
                },
            ),
            sub(
                "Sort", R.string.m_sort_tracks,
                item("SortByTime", R.string.m_sort_by_time, TE) { it.engine.sortTracks("time") },
                item("SortByName", R.string.m_sort_by_name, TE) { it.engine.sortTracks("name") },
            ),
            SEP,
            item("SyncLock", R.string.m_sync_lock, checked = { it.settings?.syncLock ?: false }) { h ->
                val cur = h.engine.getSettings().syncLock ?: false
                h.updateSettings(Settings(syncLock = !cur))
            },
        ),
    )

    // ------------------------------------------------------------------
    // Generate / Effect / Analyze / Tools — PluginMenus.cpp:271-554
    // ------------------------------------------------------------------

    private fun repeatLabel(@StringRes generic: Int, name: (Snapshot) -> String?): (MenuState) -> UiText =
        { st -> name(st.snapshot)?.takeIf { it.isNotEmpty() }?.let { UiText.Res(R.string.m_repeat_fmt, listOf(it)) } ?: UiText.Res(generic) }

    private fun generateMenu() = TopMenu(
        "Generate", R.string.menu_generate, listOf(
            item("RepeatLastGenerator", R.string.m_repeat_last_generator, NB or LAST_GEN or PO,
                dynamicLabel = repeatLabel(R.string.m_repeat_last_generator) { it.lastGenerator?.name }) { h ->
                snap(h).lastGenerator?.id?.let { id -> applyRepeat(h, id) }
            },
            SEP,
            effectItems("generate"),
        ),
    )

    private fun effectMenu() = TopMenu(
        "Effect", R.string.menu_effect, listOf(
            item("RepeatLastEffect", R.string.m_repeat_last_effect, NB or TS or WS or LAST_EFF, ctrl(Key.R, "R"),
                dynamicLabel = repeatLabel(R.string.m_repeat_last_effect) { it.lastEffect?.name }) { h ->
                val r = h.engine.repeatLastEffect()
                r.message?.takeIf { it.isNotBlank() }?.let { msg -> h.open(AppDialog.Info(UiText.Raw(snap(h).lastEffect?.name ?: ""), UiText.Raw(msg))) }
            },
            SEP,
            effectItems("effect"),
        ),
    )

    private fun analyzeMenu() = TopMenu(
        "Analyze", R.string.menu_analyze, listOf(
            item("RepeatLastAnalyzer", R.string.m_repeat_last_analyzer, NB or TS or WS or LAST_ANA,
                dynamicLabel = repeatLabel(R.string.m_repeat_last_analyzer) { it.lastAnalyzer?.name }) { h ->
                snap(h).lastAnalyzer?.id?.let { id -> applyRepeat(h, id) }
            },
            SEP,
            item("ContrastAnalyser", R.string.m_contrast, NB or WS or TS) { it.open(AppDialog.Contrast) },
            item("PlotSpectrum", R.string.m_plot_spectrum, NB or WS or TS) { it.open(AppDialog.PlotSpectrum) },
            SEP,
            effectItems("analyze"),
        ),
    )

    private fun toolsMenu() = TopMenu(
        "Tools", R.string.menu_tools, listOf(
            item("RepeatLastTool", R.string.m_repeat_last_tool, NB or LAST_TOOL or PO,
                dynamicLabel = repeatLabel(R.string.m_repeat_last_tool) { it.lastTool?.name }) { h ->
                snap(h).lastTool?.id?.let { id -> applyRepeat(h, id) }
            },
            SEP,
            effectItems("tools"),
        ),
    )

    private suspend fun applyRepeat(h: MenuHost, id: String) {
        val r = h.engine.applyEffect(id)
        r.message?.takeIf { it.isNotBlank() }?.let { h.open(AppDialog.Info(UiText.Raw(""), UiText.Raw(it))) }
    }

    /** Enable flags of plug-in menu items (PluginMenus.cpp:308-511). */
    fun effectFlags(info: EffectInfo): Long = when (info.type) {
        "process", "analyze" -> NB or TS or WS
        else -> NB or PO
    }

    /** Effect-menu section titles of EffectsMenuDefaults.xml, translated by the app. */
    private val SECTION_TITLES: Map<String, Int> = mapOf(
        "Volume and Compression" to R.string.fxg_volume,
        "Fading" to R.string.fxg_fading,
        "Pitch and Tempo" to R.string.fxg_pitch_tempo,
        "EQ and Filters" to R.string.fxg_eq,
        "Noise Removal and Repair" to R.string.fxg_noise,
        "Delay and Reverb" to R.string.fxg_delay,
        "Distortion and Modulation" to R.string.fxg_distortion,
        "Special" to R.string.fxg_special,
        "Spectral Tools" to R.string.fxg_spectral,
        "Legacy" to R.string.fxg_legacy,
    )

    fun sectionTitle(title: String): UiText = SECTION_TITLES[title]?.let { UiText.Res(it) } ?: UiText.Raw(title)

    /** Menu label of a plug-in: "…" appended for interactive ones (MenuHelper.cpp). */
    fun effectLabel(info: EffectInfo): String = if (info.interactive) info.name + "..." else info.name

    private fun effectItems(kind: String) = DynamicItems("effects.$kind") { st ->
        val list = st.effects ?: return@DynamicItems emptyList()
        val byId = list.effects.associateBy { it.id }
        val sections = when (kind) {
            "generate" -> list.menus.generate
            "effect" -> list.menus.effect
            "analyze" -> list.menus.analyze
            else -> list.menus.tools
        }
        val out = ArrayList<MenuNode>()
        for (sec in sections) {
            val items = sec.ids.mapNotNull { byId[it] }.map { info ->
                MenuItem(
                    info.id, UiText.Raw(effectLabel(info)), effectFlags(info), null, null, null,
                    noiseReductionReason = info.special == "noiseReduction",
                ) { h -> h.runEffect(info.id) }
            }
            if (items.isEmpty()) continue
            val title = sec.title
            if (title != null) out += SubMenu("fxsection:$title", sectionTitle(title), items)
            else {
                out += Separator
                out += items
            }
        }
        out
    }

    // ------------------------------------------------------------------
    // Help — HelpMenus.cpp:420-488
    // ------------------------------------------------------------------

    private fun helpMenu() = TopMenu(
        "Help", R.string.menu_help, listOf(
            item("QuickHelp", R.string.m_quick_help) { it.request(HostRequest.OpenUrl("https://manual.audacityteam.org/quick_help.html")) },
            item("Manual", R.string.m_manual) { it.request(HostRequest.OpenUrl("https://manual.audacityteam.org/")) },
            item("AudacitySupport", R.string.m_support) { it.request(HostRequest.OpenUrl("https://support.audacityteam.org/")) },
            SEP,
            sub(
                "Diagnostics", R.string.m_diagnostics,
                item("DeviceInfo", R.string.m_device_info, NB) { it.open(AppDialog.DeviceInfo) },
                item("Log", R.string.m_show_log) { it.show(AppScreen.LOG) },
            ),
            SEP,
            item("About", R.string.m_about) { it.show(AppScreen.ABOUT) },
        ),
    )

    // ------------------------------------------------------------------
    // Keyboard-only commands (Extra menu, ui-reference.md §2.12)
    // ------------------------------------------------------------------

    /** Extra-menu commands bound to keys only (the Extra menu itself is hidden on Android). */
    val keyboardOnly: List<MenuItem> by lazy {
        listOf(
            item("DeleteKey", R.string.m_delete, NB or ES or TS, key(Key.Backspace, "Backspace")) { edit(it, "edit.delete") },
            item("DeleteKey2", R.string.m_delete, NB or ES or TS, key(Key.Delete, "Delete")) { edit(it, "edit.delete") },
            item("CursorLeft", R.string.m_cursor_left, PO, key(Key.DirectionLeft, "Left")) { h -> nudge(h, -1.0) },
            item("CursorRight", R.string.m_cursor_right, PO, key(Key.DirectionRight, "Right")) { h -> nudge(h, 1.0) },
            item("SelExtLeft", R.string.m_sel_ext_left, PO, key(Key.DirectionLeft, "Left", shift = true)) { h -> extend(h, -1.0) },
            item("SelExtRight", R.string.m_sel_ext_right, PO, key(Key.DirectionRight, "Right", shift = true)) { h -> extend(h, 1.0) },
            item("CursorShortJumpLeft", R.string.m_cursor_short_jump_left, PO, key(Key.Comma, ",")) { h -> jump(h, -1.0) },
            item("CursorShortJumpRight", R.string.m_cursor_short_jump_right, PO, key(Key.Period, ".")) { h -> jump(h, 1.0) },
            item("CursorLongJumpLeft", R.string.m_cursor_long_jump_left, PO, key(Key.Comma, ",", shift = true)) { h -> jump(h, -15.0) },
            item("CursorLongJumpRight", R.string.m_cursor_long_jump_right, PO, key(Key.Period, ".", shift = true)) { h -> jump(h, 15.0) },
            // SelectMenus.cpp SelStart / SelEnd (Extra ▸ Selection)
            item("SelStart", R.string.m_sel_to_start, PO, key(Key.MoveHome, "Home", shift = true)) { it.engine.selectCommand("select.toProjectStart") },
            item("SelEnd", R.string.m_sel_to_end, PO, key(Key.MoveEnd, "End", shift = true)) { it.engine.selectCommand("select.toProjectEnd") },
        )
    }

    // ------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------

    /** The top-level menus in 3.7.9 order (MenuRegistry.cpp:204-235). */
    val menus: List<TopMenu> by lazy {
        listOf(fileMenu(), editMenu(), selectMenu(), viewMenu(), transportMenu(), tracksMenu(),
            generateMenu(), effectMenu(), analyzeMenu(), toolsMenu(), helpMenu())
    }

    /** All items reachable with [state] (dynamic sections expanded). */
    fun allItems(state: MenuState): List<MenuItem> = menus.flatMap { it.children.allItems(state) } + keyboardOnly

    /** First item with CommandID [id]. */
    fun find(id: String, state: MenuState = MenuState(Snapshot.EMPTY)): MenuItem? = allItems(state).firstOrNull { it.id == id }

    /** The item bound to a key combination, if any. */
    fun findByShortcut(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, state: MenuState): MenuItem? =
        allItems(state).firstOrNull { it.shortcut?.matches(key, ctrl, shift, alt) == true }

    // ------------------------------------------------------------------
    // helpers (Kotlin re-implementations of small src/menus handlers)
    // ------------------------------------------------------------------

    private const val EPS = 1e-9

    private suspend fun askText(h: MenuHost, @StringRes title: Int, @StringRes label: Int, initial: String, allowEmpty: Boolean = false): String? {
        val d = AppDialog.TextInput(UiText.Res(title), UiText.Res(label), initial, allowEmpty = allowEmpty)
        h.open(d)
        return d.result.await()
    }

    private suspend fun askTime(h: MenuHost, @StringRes title: Int, initial: Double): Double? {
        val d = AppDialog.TimeInput(UiText.Res(title), initial)
        h.open(d)
        return d.result.await()
    }

    /** Set Left/Right Selection Boundary (SelectMenus.cpp OnSetLeftSelection):
     *  the play position while audio is active, else a time-entry dialog. */
    private suspend fun setBoundary(h: MenuHost, left: Boolean) {
        val sel = snap(h).selection
        val tr = h.engine.readTransport()
        val head = tr.headTime(System.nanoTime())
        val t = if (tr.isActive && !head.isNaN()) head
        else askTime(h, if (left) R.string.m_set_left_title else R.string.m_set_right_title, if (left) sel.t0 else sel.t1) ?: return
        if (left) h.engine.select(t, max(t, sel.t1)) else h.engine.select(min(sel.t0, t), t)
    }

    /** Wave tracks the clip commands look at: the selected ones, else all. */
    private fun clipTracks(s: Snapshot): List<TrackState> {
        val sel = s.tracks.filter { it.isWave && it.selected }
        return sel.ifEmpty { s.tracks.filter { it.isWave } }
    }

    fun clipBoundaries(s: Snapshot): List<Double> =
        clipTracks(s).flatMap { t -> t.clips.flatMap { listOf(it.start, it.end) } }.distinct().sorted()

    /**
     * File ▸ Compact Project: the question of ProjectFileManager::Compact
     * with the numbers of project.compactInfo, then project.compact and the
     * "Compacting actually freed %s" message.
     */
    private suspend fun compactProject(h: MenuHost) {
        val info = h.engine.compactInfo()
        val free = if (info.freeBytes >= 0) TimeCodec.formatBytes(info.freeBytes) else "?"
        val d = AppDialog.Confirm(
            UiText.Res(R.string.compact_title),
            UiText.Res(R.string.compact_question, listOf(free, TimeCodec.formatBytes(info.fileBytes), TimeCodec.formatBytes(info.reclaimableBytes))),
            UiText.Res(R.string.btn_yes),
        )
        h.open(d)
        if (!d.result.await()) return
        val freed = h.engine.compactProject()
        h.open(AppDialog.Info(UiText.Res(R.string.compact_title), UiText.Res(R.string.compact_freed, listOf(TimeCodec.formatBytes(freed)))))
    }

    /** File ▸ Export Other ▸ Export Labels...: the file type, then the document. */
    private suspend fun exportLabels(h: MenuHost) {
        val formats = LabelFormat.entries
        val d = AppDialog.Choice(UiText.Res(R.string.lf_title), formats.map { UiText.Res(it.label) })
        h.open(d)
        val f = formats.getOrNull(d.result.await() ?: return) ?: return
        h.request(HostRequest.CreateDocument(CreatePurpose.ExportLabels(f.key, f.fileName), f.fileName, f.mime))
    }

    private suspend fun renameClipAtCursor(h: MenuHost) {
        val s = snap(h)
        val t = s.selection.t0
        for (track in s.tracks.filter { it.isWave && it.selected }) {
            val clip = track.clips.firstOrNull { t >= it.start - EPS && t <= it.end + EPS } ?: continue
            val name = askText(h, R.string.m_rename_clip, R.string.clip_name, clip.name) ?: return
            h.engine.renameClip(track.id, clip.index, s.generation, name)
            return
        }
        h.message(UiText.Res(R.string.msg_no_clip_at_cursor))
    }

    /** View ▸ Zoom ▸ Zoom Toggle: preset 1 "Zoom Default" ↔ preset 2 "4 Pixels per Sample" (TracksPrefs.cpp:221-238). */
    private fun zoomToggle(h: MenuHost) {
        val e = h.editor ?: return
        val z1 = 44100.0 / 512.0
        val z2 = snap(h).project.rate * 4.0
        val target = if (abs(e.pps - z1) < 1e-6) z2 else z1
        e.zoomBy(target / e.pps, e.usableWidthDp / 2.0)
    }

    private fun fitVertically(h: MenuHost) {
        val e = h.editor ?: return
        val tracks = snap(h).tracks
        if (tracks.isEmpty()) return
        val avail = (h.editorHeightDp - 32f).coerceAtLeast(120f)
        val each = (avail / tracks.size).coerceAtLeast(48f)
        tracks.forEach { t ->
            if (e.isCollapsed(t.id)) e.toggleCollapsed(t.id)
            e.setTrackHeight(t.id, each)
        }
    }

    private fun playable(tracks: List<TrackState>) = tracks.filter { it.isWave }

    /** Mute/Unmute Tracks (selected): no engine command; per track like the TCP buttons. */
    private suspend fun setMute(h: MenuHost, tracks: List<TrackState>, mute: Boolean) {
        for (t in tracks) if (t.mute != mute) h.engine.setTrackMute(t.id, mute)
    }

    private suspend fun setPan(h: MenuHost, pan: Double) {
        for (t in playable(snap(h).selectedTracks)) h.engine.setTrackPan(t.id, pan, true)
    }

    /** Play/Stop (Space) and Play/Stop and Set Cursor (X). */
    private suspend fun playStop(h: MenuHost, setCursor: Boolean) {
        val tr = h.engine.readTransport()
        if (tr.isActive) {
            val head = tr.headTime(System.nanoTime())
            h.engine.stop()
            if (setCursor && !head.isNaN()) h.engine.select(head, head)
        } else {
            h.engine.play()
        }
    }

    private suspend fun setLoopEdge(h: MenuHost, inPoint: Boolean) {
        val s = snap(h)
        val tr = h.engine.readTransport()
        val head = tr.headTime(System.nanoTime())
        val pr = s.playRegion
        val hasRegion = pr.t1 > pr.t0
        if (inPoint) {
            val t = if (tr.isActive && !head.isNaN()) head else s.selection.t0
            val end = when {
                hasRegion && pr.t1 > t -> pr.t1
                s.selection.t1 > t -> s.selection.t1
                else -> max(t, s.projectEnd)
            }
            if (end > t) h.engine.setPlayRegion(t, end, true)
        } else {
            val t = if (tr.isActive && !head.isNaN()) head else s.selection.t1
            val start = when {
                hasRegion && pr.t0 < t -> pr.t0
                s.selection.t0 < t -> s.selection.t0
                else -> 0.0
            }
            if (t > start) h.engine.setPlayRegion(start, t, true)
        }
    }

    /** Cursor Left/Right (one pixel at the current zoom) or Seek while playing. */
    private suspend fun nudge(h: MenuHost, dir: Double) {
        val tr = h.engine.readTransport()
        if (tr.isActive) {
            val head = tr.headTime(System.nanoTime())
            if (!head.isNaN()) h.engine.seek(max(0.0, head + dir * 1.0))   // /AudioIO/SeekShortPeriod
            return
        }
        val s = snap(h).selection
        val step = 1.0 / (h.editor?.pps ?: 100.0)
        val t = if (s.t1 > s.t0) (if (dir < 0) s.t0 else s.t1) else max(0.0, s.t0 + dir * step)
        h.engine.select(t, t)
    }

    private suspend fun extend(h: MenuHost, dir: Double) {
        val s = snap(h).selection
        val step = 1.0 / (h.editor?.pps ?: 100.0)
        if (dir < 0) h.engine.select(max(0.0, s.t0 - step), s.t1) else h.engine.select(s.t0, s.t1 + step)
    }

    private suspend fun jump(h: MenuHost, seconds: Double) {
        val s: TimeRange = snap(h).selection
        val t = max(0.0, (if (seconds < 0) s.t0 else s.t1) + seconds)
        h.engine.select(t, t)
    }
}
