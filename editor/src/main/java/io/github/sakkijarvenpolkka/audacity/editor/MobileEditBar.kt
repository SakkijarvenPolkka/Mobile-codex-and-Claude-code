/*
 * Audacity Android port — the mobile edit bar: a bottom row of large
 * touch targets for the frequent Edit menu commands (EditMenus.cpp,
 * LabelMenus.cpp, CutCopyPasteToolBar), with a prominent ✂ Split button.
 *
 * Enable states use the snapshot's command flags like the menus
 * (notes/ui-reference.md §1.2); a disabled button stays tappable and tells
 * why it is unavailable (EditorCallbacks.onMessage). Long-press ✂ toggles
 * the razor tool (EditTool.SPLIT).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import kotlin.math.max

/** Why a command is unavailable, in the user's language. */
internal class UnavailableReasons(
    val noProject: String,
    val busy: String,
    val recording: String,
    val noSelection: String,
    val noAudioTrack: String,
    val noTrack: String,
    val noAudio: String,
    val clipboardEmpty: String,
    val nothingToUndo: String,
    val nothingToRedo: String,
) {
    /**
     * The first unmet requirement of [required] (CommandFlags bits) in [s],
     * or null when the command is enabled. Checked in the order a user can
     * act on: project, recording/playing, selection, tracks, history.
     */
    fun of(s: Snapshot, required: Long): String? {
        if (!s.project.open && !s.has(CommandFlags.PROJECT_OPEN)) return noProject
        fun needs(bit: Long) = (required and bit) != 0L && !s.has(bit)
        if (needs(CommandFlags.CNB)) return recording
        if (needs(CommandFlags.NB)) return if (!s.has(CommandFlags.CNB)) recording else busy
        if (needs(CommandFlags.WE)) return noAudio
        if (needs(CommandFlags.TS)) return noSelection
        if (needs(CommandFlags.WS)) return noAudioTrack
        if (needs(CommandFlags.ES)) return noTrack
        if (needs(CommandFlags.CC)) return noSelection
        if (needs(CommandFlags.UA)) return nothingToUndo
        if (needs(CommandFlags.RA)) return nothingToRedo
        if (needs(CommandFlags.CLIPBOARD)) return clipboardEmpty
        return null
    }
}

@Composable
internal fun rememberUnavailableReasons(): UnavailableReasons = UnavailableReasons(
    noProject = stringResource(R.string.aued_why_no_project),
    busy = stringResource(R.string.aued_why_busy),
    recording = stringResource(R.string.aued_why_recording),
    noSelection = stringResource(R.string.aued_why_no_selection),
    noAudioTrack = stringResource(R.string.aued_why_no_audio_track),
    noTrack = stringResource(R.string.aued_why_no_track),
    noAudio = stringResource(R.string.aued_why_no_audio),
    clipboardEmpty = stringResource(R.string.aued_why_clipboard_empty),
    nothingToUndo = stringResource(R.string.aued_why_nothing_to_undo),
    nothingToRedo = stringResource(R.string.aued_why_nothing_to_redo),
)

/**
 * Bottom action bar for phones: ✂ Split (at the cursor; while playing, stops
 * and splits at the play head; long-press = razor tool), Undo, Redo, Cut,
 * Copy, Paste, Delete, Trim (keep the selection), Silence, Duplicate,
 * Marker (a label at the play head while playing/recording, else at the
 * cursor or the selection) and Effects ([EditorCallbacks.onQuickEffects]).
 * Every button is at least 56 × 56 dp with an icon and a short label; the
 * row scrolls horizontally when the screen is narrow.
 */
@Composable
fun MobileEditBar(engine: AudacityEngine, state: EditorState, callbacks: EditorCallbacks, modifier: Modifier = Modifier) {
    val pal = LocalAudacityColors.current
    val snapshot by engine.snapshot.collectAsState()
    val currentSnapshot by rememberUpdatedState(snapshot)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val why = rememberUnavailableReasons()
    val nothingToSplit = stringResource(R.string.aued_nothing_to_split)
    val notWhileRecording = why.recording
    val onError: (Throwable) -> Unit = { e -> currentCallbacks.onMessage(e.message ?: e.toString()) }
    val onMessage: (String) -> Unit = { currentCallbacks.onMessage(it) }
    val nb = CommandFlags.NB

    fun edit(command: String) = scope.engineCall(onError) { engine.edit(command) }

    Row(
        modifier
            .testTag(EditorTags.MOBILE_EDIT_BAR)
            .background(pal.medium)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val splitTool = state.tool == EditTool.SPLIT
        BarButton(
            AudacityIcons.Split, stringResource(R.string.aued_split), stringResource(R.string.aued_split_desc),
            why.of(snapshot, CommandFlags.CNB or CommandFlags.WE), onMessage,
            prominent = true, selected = splitTool,
            selectedDescription = stringResource(R.string.aued_split_tool),
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                state.tool = if (state.tool == EditTool.SPLIT) EditTool.SELECT else EditTool.SPLIT
            },
            longClickLabel = stringResource(R.string.aued_split_tool_toggle),
        ) {
            scope.engineCall(onError) {
                val s = currentSnapshot
                val sel = s.selection
                val head = EditActions.headTime(engine)
                if (EditActions.isPlaying(engine) && !head.isNaN()) {
                    // Stops, then splits where the play head was shown.
                    val r = EditActions.splitAt(engine, max(0.0, head), null, notWhileRecording)
                    if (r.splits == 0) onMessage(nothingToSplit)
                } else if (sel.t1 > sel.t0 && s.has(CommandFlags.WS) && s.has(nb)) {
                    // A time selection: Split at both of its edges (Edit ▸ Audio Clips ▸ Split).
                    engine.edit("edit.split")
                } else {
                    val r = EditActions.splitAt(engine, sel.t0, null, notWhileRecording)
                    if (r.splits == 0) onMessage(nothingToSplit)
                }
            }
        }
        Box(Modifier.width(4.dp))
        // history.canUndo/canRedo like the Edit toolbar (the UA/RA flags also need NB).
        BarButton(
            AudacityIcons.Undo, stringResource(R.string.aued_undo), null,
            why.of(snapshot, nb) ?: why.nothingToUndo.takeIf { !snapshot.history.canUndo }, onMessage,
        ) { scope.engineCall(onError) { engine.undo() } }
        BarButton(
            AudacityIcons.Redo, stringResource(R.string.aued_redo), null,
            why.of(snapshot, nb) ?: why.nothingToRedo.takeIf { !snapshot.history.canRedo }, onMessage,
        ) { scope.engineCall(onError) { engine.redo() } }
        Box(Modifier.width(4.dp))
        BarButton(AudacityIcons.Cut, stringResource(R.string.aued_cut), null,
            why.of(snapshot, nb or CommandFlags.CC), onMessage) { edit("edit.cut") }
        BarButton(AudacityIcons.Copy, stringResource(R.string.aued_copy), null,
            why.of(snapshot, nb or CommandFlags.CC), onMessage) { edit("edit.copy") }
        BarButton(AudacityIcons.Paste, stringResource(R.string.aued_paste), null,
            why.of(snapshot, nb or CommandFlags.CLIPBOARD), onMessage) { edit("edit.paste") }
        BarButton(AudacityIcons.Delete, stringResource(R.string.aued_delete), null,
            why.of(snapshot, nb or CommandFlags.ES or CommandFlags.TS), onMessage) { edit("edit.delete") }
        BarButton(AudacityIcons.Trim, stringResource(R.string.aued_trim_short), stringResource(R.string.aued_trim),
            why.of(snapshot, nb or CommandFlags.TS or CommandFlags.WS), onMessage) { edit("edit.trim") }
        BarButton(AudacityIcons.Silence, stringResource(R.string.aued_silence_short), stringResource(R.string.aued_silence),
            why.of(snapshot, nb or CommandFlags.TS or CommandFlags.WS), onMessage) { edit("edit.silence") }
        BarButton(AudacityIcons.Duplicate, stringResource(R.string.aued_duplicate), null,
            why.of(snapshot, nb or CommandFlags.TS or CommandFlags.ES), onMessage) { edit("edit.duplicate") }
        Box(Modifier.width(4.dp))
        BarButton(AudacityIcons.Marker, stringResource(R.string.aued_marker), stringResource(R.string.aued_marker_desc),
            why.of(snapshot, 0L), onMessage) {
            scope.engineCall(onError) {
                val sel = currentSnapshot.selection
                val head = EditActions.headTime(engine)
                if (!head.isNaN()) {
                    // Edit ▸ Labels ▸ Add Label at Playback Position: no dialog while audio runs.
                    engine.addLabel("", t0 = max(0.0, head))
                } else {
                    // Add Label at Selection, then name it (desktop: typing into the new label).
                    val (trackId, index) = if (sel.t1 > sel.t0) engine.addLabel("", sel.t0, sel.t1)
                    else engine.addLabel("", t0 = sel.t0)
                    if (index >= 0) currentCallbacks.onEditLabel(trackId, index)
                }
            }
        }
        BarButton(AudacityIcons.Effects, stringResource(R.string.aued_effects), null,
            why.of(snapshot, 0L), onMessage) { currentCallbacks.onQuickEffects() }
    }
}

/**
 * One button of the edit bar: icon over a short label, ≥ 56 × 56 dp. When
 * [unavailable] is not null it is drawn dimmed and a tap shows the reason
 * instead of running [onClick].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(
    icon: ImageVector,
    label: String,
    description: String?,
    unavailable: String?,
    onMessage: (String) -> Unit,
    prominent: Boolean = false,
    selected: Boolean = false,
    selectedDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val pal = LocalAudacityColors.current
    val enabled = unavailable == null
    val shape = RoundedCornerShape(8.dp)
    val back = when {
        selected -> pal.buttonDown
        prominent -> pal.accent
        else -> Color.Transparent
    }
    val fore = when {
        prominent && !selected -> Color.White
        else -> pal.glyph
    }.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val unavailableText = unavailable?.let { stringResource(R.string.aued_unavailable, it) }
    Column(
        Modifier
            .height(56.dp)
            .widthIn(min = if (prominent) 72.dp else 56.dp)
            .background(back, shape)
            .then(if (selected) Modifier.border(2.dp, pal.accent, shape) else Modifier)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = description ?: label,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            ) { if (unavailable != null) onMessage(unavailable) else onClick() }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                this.selected = selected
                when {
                    unavailableText != null -> stateDescription = unavailableText
                    selected && selectedDescription != null -> stateDescription = selectedDescription
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fore, modifier = Modifier.size(24.dp))
        Text(
            label, color = fore, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
