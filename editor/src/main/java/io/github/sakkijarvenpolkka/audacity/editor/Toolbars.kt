/*
 * Audacity Android port — Transport, Edit, Time and Selection toolbars
 * (notes/ui-reference.md §4.2, §4.4, §4.7, §4.8).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Runs an engine call, routing failures to [onError]. */
internal fun CoroutineScope.engineCall(onError: (Throwable) -> Unit = {}, block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
        }
    }
}

/**
 * A toolbar button with an Audacity button face (Up/Down/Hilite faces of
 * §6.5). [down] draws the pressed face (toggle buttons).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    down: Boolean = false,
    tint: Color? = null,
    size: Dp = 48.dp,
    glyph: Dp = 24.dp,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val pal = LocalAudacityColors.current
    val face = if (down) pal.buttonDown else pal.buttonUp
    Box(
        modifier
            .size(size)
            .background(face, RoundedCornerShape(6.dp))
            .border(1.dp, if (down) pal.dark else pal.light, RoundedCornerShape(6.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                selected = down
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (!enabled) pal.glyphDisabled else tint ?: pal.glyph,
            modifier = Modifier.size(glyph),
        )
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/**
 * Transport toolbar in Audacity order: Pause, Play, Stop, Skip to Start,
 * Skip to End, Record, Loop (ControlToolBar.cpp:407-413). Record goes
 * through [EditorCallbacks.onRecord] (the app handles the microphone
 * permission); long-press Record = Record New Track.
 */
@Composable
fun TransportToolbar(engine: AudacityEngine, callbacks: EditorCallbacks, modifier: Modifier = Modifier) {
    val pal = LocalAudacityColors.current
    val snapshot by engine.snapshot.collectAsState()
    val transport by engine.transportState.collectAsState()
    val scope = rememberCoroutineScope()
    val onError: (Throwable) -> Unit = { e -> callbacks.onMessage(e.message ?: e.toString()) }
    val state = transport.state
    val playing = state == "playing"
    val recording = state == "recording"
    val paused = state == "paused" || snapshot.has(CommandFlags.PAUSED)
    val active = playing || recording || paused
    val projectOpen = snapshot.project.open || snapshot.has(CommandFlags.PROJECT_OPEN)
    val canPlay = snapshot.has(CommandFlags.PLAYABLE) || snapshot.tracks.any { it.isWave }

    BoxWithConstraints(modifier.background(pal.medium).padding(4.dp)) {
        val spacing = 4.dp
        val size = min(56.dp, (maxWidth - spacing * 8) / 7).coerceAtLeast(36.dp)
        val glyph = min(24.dp, size * 0.5f)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(
                AudacityIcons.Pause, stringResource(R.string.aued_pause),
                { scope.engineCall(onError) { engine.pause() } },
                enabled = active, down = paused, size = size, glyph = glyph,
            )
            ToolButton(
                AudacityIcons.Play, stringResource(R.string.aued_play),
                {
                    scope.engineCall(onError) {
                        when {
                            playing -> engine.stop()
                            paused && !recording -> engine.pause()
                            else -> engine.play()
                        }
                    }
                },
                enabled = projectOpen && !recording && (canPlay || playing), down = playing,
                tint = pal.playGlyph, size = size, glyph = glyph,
            )
            ToolButton(
                AudacityIcons.Stop, stringResource(R.string.aued_stop),
                { scope.engineCall(onError) { engine.stop() } },
                enabled = projectOpen, size = size, glyph = glyph,
            )
            Box(Modifier.width(0.dp))
            ToolButton(
                AudacityIcons.SkipToStart, stringResource(R.string.aued_skip_to_start),
                { scope.engineCall(onError) { engine.skipToStart() } },
                enabled = projectOpen && !recording, size = size, glyph = glyph,
            )
            ToolButton(
                AudacityIcons.SkipToEnd, stringResource(R.string.aued_skip_to_end),
                { scope.engineCall(onError) { engine.skipToEnd() } },
                enabled = projectOpen && !recording, size = size, glyph = glyph,
            )
            ToolButton(
                AudacityIcons.Record, stringResource(R.string.aued_record),
                { if (!recording) callbacks.onRecord(false) },
                enabled = projectOpen && !playing, down = recording,
                tint = pal.recordGlyph, size = size, glyph = glyph,
                onLongClick = { if (!recording) callbacks.onRecord(true) },
                longClickLabel = stringResource(R.string.aued_record_new_track),
            )
            ToolButton(
                AudacityIcons.Loop, stringResource(R.string.aued_loop),
                { scope.engineCall(onError) { engine.togglePlayRegion() } },
                enabled = projectOpen, down = snapshot.playRegion.active, size = size, glyph = glyph,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

/**
 * Edit toolbar (EditToolBar.cpp:163-205): Zoom In, Zoom Out, Zoom to
 * Selection, Fit to Width, Zoom Normal | Trim, Silence | Undo, Redo. Enabled
 * with the same flags as the menu items.
 */
@Composable
fun EditToolbar(engine: AudacityEngine, state: EditorState, modifier: Modifier = Modifier) {
    val pal = LocalAudacityColors.current
    val snapshot by engine.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val hasTracks = snapshot.tracks.isNotEmpty()
    val sel = snapshot.selection
    val nb = CommandFlags.NB
    val editFlags = nb or CommandFlags.TS or CommandFlags.ES
    BoxWithConstraints(modifier.background(pal.medium).padding(4.dp)) {
        val spacing = 2.dp
        val size = min(48.dp, (maxWidth - spacing * 12) / 9).coerceAtLeast(32.dp)
        val glyph = min(24.dp, size * 0.55f)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(AudacityIcons.ZoomIn, stringResource(R.string.aued_zoom_in), { state.zoomIn() },
                enabled = hasTracks && state.pps < EditorState.MAX_ZOOM, size = size, glyph = glyph)
            ToolButton(AudacityIcons.ZoomOut, stringResource(R.string.aued_zoom_out), { state.zoomOut() },
                enabled = hasTracks && state.pps > EditorState.MIN_ZOOM, size = size, glyph = glyph)
            ToolButton(AudacityIcons.ZoomToSelection, stringResource(R.string.aued_zoom_selection),
                { state.zoomToSelection(sel.t0, sel.t1) },
                enabled = hasTracks && sel.t1 > sel.t0, size = size, glyph = glyph)
            ToolButton(AudacityIcons.ZoomFit, stringResource(R.string.aued_zoom_fit),
                { state.zoomToFit(snapshot.projectEnd) },
                enabled = hasTracks, size = size, glyph = glyph)
            ToolButton(AudacityIcons.ZoomNormal, stringResource(R.string.aued_zoom_normal), { state.zoomNormal() },
                enabled = hasTracks, size = size, glyph = glyph)
            Box(Modifier.width(4.dp))
            ToolButton(AudacityIcons.Trim, stringResource(R.string.aued_trim),
                { scope.engineCall { engine.edit("edit.trim") } },
                enabled = snapshot.enabled(editFlags), size = size, glyph = glyph)
            ToolButton(AudacityIcons.Silence, stringResource(R.string.aued_silence),
                { scope.engineCall { engine.edit("edit.silence") } },
                enabled = snapshot.enabled(editFlags), size = size, glyph = glyph)
            Box(Modifier.width(4.dp))
            ToolButton(AudacityIcons.Undo, stringResource(R.string.aued_undo),
                { scope.engineCall { engine.undo() } },
                enabled = snapshot.history.canUndo && snapshot.enabled(nb), size = size, glyph = glyph)
            ToolButton(AudacityIcons.Redo, stringResource(R.string.aued_redo),
                { scope.engineCall { engine.redo() } },
                enabled = snapshot.history.canRedo && snapshot.enabled(nb), size = size, glyph = glyph)
        }
    }
}

// ---------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------

/** Builds the digit-box look of NumericTextCtrl: digits large, unit letters small. */
private fun lcdText(text: String, digitSize: Float) = buildAnnotatedString {
    for (ch in text) {
        if (ch.isDigit() || ch == '-' || ch == '.') {
            withStyle(SpanStyle(fontSize = digitSize.sp, fontWeight = FontWeight.Medium)) { append(ch) }
        } else {
            withStyle(SpanStyle(fontSize = (digitSize * 0.55f).sp)) { append(ch) }
        }
    }
}

/**
 * Time toolbar: read-only "Audio Position" in hh:mm:ss (TimeToolBar.cpp):
 * the play/record position while streaming, else the cursor / selection start.
 */
@Composable
fun TimeToolbar(engine: AudacityEngine, modifier: Modifier = Modifier) {
    val pal = LocalAudacityColors.current
    val snapshot by engine.snapshot.collectAsState()
    val head = rememberHeadState(engine, playEnd = { Double.POSITIVE_INFINITY })
    val shown by remember(snapshot) {
        derivedStateOf {
            val t = head.time
            TimeFormat.hhmmss(if (t.isNaN()) snapshot.selection.t0 else t)
        }
    }
    val desc = stringResource(R.string.aued_audio_position)
    Box(
        modifier
            .background(pal.timeBack, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(lcdText(shown, 22f), color = pal.timeFont, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------

/**
 * Selection toolbar, mode "Start and End" with "hh:mm:ss + milliseconds"
 * (SelectionBar.cpp). Tap a field to type a new time (hh:mm:ss.mmm, mm:ss
 * or seconds); Done/focus loss commits with `select.set`. Typed times are
 * clamped to [0, project end] while [EditorState.stopAtTrackEnd] is on
 * ([state] null = on).
 */
@Composable
fun SelectionToolbar(engine: AudacityEngine, modifier: Modifier = Modifier, state: EditorState? = null) {
    val pal = LocalAudacityColors.current
    val snapshot by engine.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    val sel = snapshot.selection
    fun clamp(v: Double): Double = Snapper.clamp(v, snapshot.projectEnd, state?.stopAtTrackEnd ?: true)
    Row(
        modifier.background(pal.medium).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TimeField(stringResource(R.string.aued_selection_start), sel.t0) { typed ->
            val v = clamp(typed)
            val t1 = clamp(maxOf(sel.t1, v))
            scope.engineCall { engine.select(v, t1) }
        }
        TimeField(stringResource(R.string.aued_selection_end), sel.t1) { typed ->
            val v = clamp(typed)
            val t0 = minOf(clamp(sel.t0), v)
            scope.engineCall { engine.select(t0, v) }
        }
    }
}

@Composable
private fun TimeField(caption: String, value: Double, onCommit: (Double) -> Unit) {
    val pal = LocalAudacityColors.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val editDesc = stringResource(R.string.aued_edit_time, caption)
    fun commit() {
        val v = TimeFormat.parse(text)
        if (v != null) onCommit(v)
        error = v == null && text.isNotBlank()
        editing = false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(caption, color = pal.text, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
        Box(
            Modifier
                .height(32.dp)
                .widthIn(min = 150.dp)
                .background(if (editing) pal.timeBackFocus else pal.timeBack, RoundedCornerShape(3.dp))
                .border(1.dp, if (error) pal.recordHead else Color.Transparent, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp)
                .clickable(onClickLabel = editDesc, role = Role.Button) {
                    text = TimeFormat.editable(value)
                    error = false
                    focused = false
                    editing = true
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (editing) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = pal.timeFontFocus, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                    cursorBrush = SolidColor(pal.timeFontFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .focusRequester(focus)
                        .onFocusChanged {
                            // Commit when the field loses the focus it had (not on the
                            // initial unfocused callback).
                            if (focused && !it.isFocused && editing) commit()
                            focused = it.isFocused
                        }
                        .semantics { contentDescription = editDesc },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            } else {
                Text(
                    lcdText(TimeFormat.hhmmssMillis(value), 15f),
                    color = pal.timeFont, fontFamily = FontFamily.Monospace, maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "$caption ${TimeFormat.hhmmssMillis(value)}" },
                )
            }
        }
    }
}
