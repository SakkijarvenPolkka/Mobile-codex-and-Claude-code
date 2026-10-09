/*
 * Audacity Android port — editor callbacks and context-menu targets.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

/** What a long-press (desktop right-click) or an overflow button was aimed at. */
sealed interface ContextTarget {
    /** A track's control panel / its wave area outside clips. */
    data class Track(val trackId: Long) : ContextTarget

    /** A clip (body or title bar); [generation] is the snapshot generation of [clipIndex]. */
    data class Clip(val trackId: Long, val clipIndex: Int, val generation: Long) : ContextTarget

    /** A label of a label track. */
    data class Label(val trackId: Long, val index: Int) : ContextTarget

    /** The timeline ruler at [time] (Timeline Options menu). */
    data class Timeline(val time: Double) : ContextTarget

    /** The empty area below the last track. */
    data object Empty : ContextTarget
}

/**
 * Requests from the editor that need the app (menus, dialogs, permissions).
 * All methods are called on the main thread. Methods with a body are
 * optional (added after the first release; the defaults keep older
 * implementations compiling).
 */
interface EditorCallbacks {
    /** The ⋯ button (or a long-press) of a track control panel. */
    fun onTrackMenu(trackId: Long)

    /** Long-press / overflow button: show the context menu for [target]. */
    fun onContextMenu(target: ContextTarget)

    /** Record button: the app checks the microphone permission, then calls
     *  `engine.record(newTrack)`. Long-press on Record = [newTrack] true
     *  (desktop Shift+R, "Record New Track"). */
    fun onRecord(newTrack: Boolean)

    /** Edit a label's text (long-press / double-tap on a label). */
    fun onEditLabel(trackId: Long, index: Int)

    /** A short message for a snackbar (engine errors of editor actions). */
    fun onMessage(text: String)

    /** Rename a clip (double-tap on its title bar). The default opens the
     *  clip context menu, which contains "Rename Clip...". */
    fun onRenameClip(trackId: Long, clipIndex: Int, generation: Long) {
        onContextMenu(ContextTarget.Clip(trackId, clipIndex, generation))
    }

    /** The "Effects" button of [MobileEditBar]: show a quick list of the
     *  frequently used effects for the selection (the app's effect sheet).
     *  The default does nothing (the button is then inert). */
    fun onQuickEffects() {}
}

/** Test tags of the editor's main areas (Compose UI tests). */
object EditorTags {
    const val TRACK_PANEL: String = "aued_track_panel"
    const val WAVE_AREA: String = "aued_wave_area"
    const val RULER: String = "aued_ruler"
    /** The [MobileEditBar] row. */
    const val MOBILE_EDIT_BAR: String = "aued_mobile_edit_bar"
    /** The razor-tool banner shown over the track panel while [EditTool.SPLIT] is active. */
    const val SPLIT_TOOL_BANNER: String = "aued_split_tool_banner"
}
