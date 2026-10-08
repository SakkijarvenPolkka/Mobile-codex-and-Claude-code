/*
 * Audacity Android port — track control panel (TCP), notes/ui-reference.md §5.4.
 *
 * Wide layout: the 150 dp desktop TCP — [×] name [⌃][⋯] / Mute Solo /
 * Volume / Pan / channels-rate-format info, lines hidden from the bottom up
 * when the track is short (CommonTrackInfo::HideTopItem). Compact (phone)
 * layout: one 32 dp header row per track (§9.2) with an optional gain/pan
 * row. Sliders send `final = false` while dragging (throttled) and
 * `final = true` on release (one undo entry).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.TrackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Gain slider range (ASlider DB_SLIDER: −36…+36 dB). */
internal const val GAIN_MIN_DB = -36f
internal const val GAIN_MAX_DB = 36f

internal fun gainToDb(gain: Double): Float =
    if (gain <= 0.0) GAIN_MIN_DB else (20.0 * log10(gain)).toFloat().coerceIn(GAIN_MIN_DB, GAIN_MAX_DB)

internal fun dbToGain(db: Float): Double = 10.0.pow(db / 20.0)

/**
 * Sends slider values to the engine: intermediate values at most every
 * [intervalMs] (`final = false`), the release value with `final = true`
 * after any in-flight intermediate one.
 */
internal class ThrottledSender(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 50L,
    private val onError: (Throwable) -> Unit = {},
    private val send: suspend (value: Double, final: Boolean) -> Unit,
) {
    private var pending = Double.NaN
    private var job: Job? = null

    fun update(value: Double, final: Boolean) {
        if (final) {
            pending = Double.NaN
            val previous = job
            job = scope.launch {
                previous?.join()
                safe { send(value, true) }
            }
            return
        }
        pending = value
        if (job?.isActive == true) return
        job = scope.launch {
            while (!pending.isNaN()) {
                val v = pending
                pending = Double.NaN
                safe { send(v, false) }
                delay(intervalMs)
            }
        }
    }

    private suspend fun safe(block: suspend () -> Unit) {
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
 * Audacity-style horizontal slider (LWSlider look): a thin track and a round
 * thumb. Tap/drag sets the value, double tap resets to [defaultValue].
 * [onValueChange] gets `final = true` when the gesture ends.
 */
@Composable
internal fun AudacitySlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    contentDescription: String,
    valueText: (Float) -> String,
    onValueChange: (value: Float, final: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
) {
    val pal = LocalAudacityColors.current
    var dragValue by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragValue.isNaN()) value else dragValue
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentRange by rememberUpdatedState(valueRange)
    fun valueAt(x: Float, width: Int): Float {
        val r = currentRange
        val inset = 8f
        val frac = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return r.start + frac * (r.endInclusive - r.start)
    }
    Canvas(
        modifier
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = valueText(shown)
                progressBarRangeInfo = ProgressBarRangeInfo(shown, valueRange)
                setProgress { v ->
                    currentOnChange(v.coerceIn(valueRange.start, valueRange.endInclusive), true)
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { currentOnChange(defaultValue, true) },
                    onTap = { o -> currentOnChange(valueAt(o.x, size.width), true) },
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragValue = valueAt(o.x, size.width)
                        currentOnChange(dragValue, false)
                    },
                    onDragEnd = {
                        val v = dragValue
                        dragValue = Float.NaN
                        if (!v.isNaN()) currentOnChange(v, true)
                    },
                    onDragCancel = {
                        val v = dragValue
                        dragValue = Float.NaN
                        if (!v.isNaN()) currentOnChange(v, true)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragValue = valueAt(change.position.x, size.width)
                        currentOnChange(dragValue, false)
                    },
                )
            },
    ) {
        val inset = 8.dp.toPx()
        val cy = size.height / 2f
        val trackH = 3.dp.toPx()
        val w = size.width - 2 * inset
        drawRoundRect(pal.sliderMain, Offset(inset, cy - trackH / 2), Size(w, trackH), CornerRadius(trackH / 2))
        val frac = ((shown - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        if (centered) {
            val mid = inset + w / 2f
            drawLine(pal.text.copy(alpha = 0.5f), Offset(mid, cy - 5.dp.toPx()), Offset(mid, cy + 5.dp.toPx()), 1.dp.toPx())
        }
        val tx = inset + frac * w
        drawCircle(pal.sliderLight, 6.5.dp.toPx(), Offset(tx, cy))
        drawCircle(pal.accent, 6.5.dp.toPx(), Offset(tx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

/** Mute / Solo button face (UpButtonExpand / DownButtonExpand). */
@Composable
internal fun ToggleFace(text: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, face: Modifier? = null) {
    val pal = LocalAudacityColors.current
    val look = Modifier
        .background(if (checked) pal.toggleOn else pal.toggleOff, RoundedCornerShape(3.dp))
        .border(1.dp, pal.dark, RoundedCornerShape(3.dp))
    val label: @Composable () -> Unit = {
        Text(
            text, color = if (checked) pal.toggleOnText else pal.text, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
    val touch = Modifier
        .clickable(role = Role.Checkbox, onClick = onClick)
        .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
    if (face == null) {
        Box(modifier.then(look).then(touch), contentAlignment = Alignment.Center) { label() }
    } else {
        // [modifier] is the touch area, larger than the drawn [face]
        Box(modifier.then(touch), contentAlignment = Alignment.Center) {
            Box(face.then(look), contentAlignment = Alignment.Center) { label() }
        }
    }
}

/** Small glyph button of the TCP (×, ⌃, ⋯). */
@Composable
internal fun TcpIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 24) {
    val pal = LocalAudacityColors.current
    Box(
        modifier
            .size(size.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = pal.text, modifier = Modifier.size((size * 0.7f).dp))
    }
}

/** Per-track engine actions of the TCP. */
internal class TrackActions(
    private val engine: AudacityEngine,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    private val gainSenders = HashMap<Long, ThrottledSender>()
    private val panSenders = HashMap<Long, ThrottledSender>()

    fun gain(id: Long, db: Float, final: Boolean) =
        gainSenders.getOrPut(id) {
            ThrottledSender(scope, onError = onError) { v, f -> engine.setTrackGain(id, v, f) }
        }.update(dbToGain(db), final)

    fun pan(id: Long, pan: Float, final: Boolean) =
        panSenders.getOrPut(id) {
            ThrottledSender(scope, onError = onError) { v, f -> engine.setTrackPan(id, v, f) }
        }.update(pan.toDouble(), final)

    fun mute(id: Long, mute: Boolean) = launch { engine.setTrackMute(id, mute) }
    fun solo(id: Long, solo: Boolean) = launch { engine.setTrackSolo(id, solo) }
    fun remove(id: Long) = launch { engine.removeTracks(listOf(id)) }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }
}

@Composable
internal fun panText(pan: Float): String {
    val pct = (kotlin.math.abs(pan) * 100f).roundToInt()
    return when {
        pct == 0 -> stringResource(R.string.aued_pan_center)
        pan < 0f -> stringResource(R.string.aued_pan_left, pct)
        else -> stringResource(R.string.aued_pan_right, pct)
    }
}

internal fun dbText(db: Float): String = String.format(Locale.ROOT, "%+.1f", db)

@Composable
internal fun formatName(format: String): String = when (format) {
    "int16" -> stringResource(R.string.aued_format_int16)
    "int24" -> stringResource(R.string.aued_format_int24)
    else -> stringResource(R.string.aued_format_float)
}

@Composable
private fun GainSlider(track: TrackState, actions: TrackActions, modifier: Modifier) {
    val dbFormat = stringResource(R.string.aued_db_value)
    AudacitySlider(
        value = gainToDb(track.gain),
        valueRange = GAIN_MIN_DB..GAIN_MAX_DB,
        defaultValue = 0f,
        contentDescription = stringResource(R.string.aued_volume),
        valueText = { String.format(dbFormat, dbText(it)) },
        onValueChange = { v, final -> actions.gain(track.id, (v * 10f).roundToInt() / 10f, final) },
        modifier = modifier,
    )
}

@Composable
private fun PanSlider(track: TrackState, actions: TrackActions, modifier: Modifier) {
    val center = stringResource(R.string.aued_pan_center)
    val left = stringResource(R.string.aued_pan_left)
    val right = stringResource(R.string.aued_pan_right)
    AudacitySlider(
        value = track.pan.toFloat(),
        valueRange = -1f..1f,
        defaultValue = 0f,
        contentDescription = stringResource(R.string.aued_pan),
        valueText = { p ->
            val pct = (kotlin.math.abs(p) * 100f).roundToInt()
            when {
                pct == 0 -> center
                p < 0 -> String.format(left, pct)
                else -> String.format(right, pct)
            }
        },
        onValueChange = { v, final -> actions.pan(track.id, (v * 100f).roundToInt() / 100f, final) },
        modifier = modifier,
        centered = true,
    )
}

@Composable
private fun trackInfoText(track: TrackState): String {
    val ch = if (track.channels >= 2) stringResource(R.string.aued_stereo) else stringResource(R.string.aued_mono)
    val rate = stringResource(R.string.aued_rate_hz, track.rate.roundToInt())
    return "$ch, $rate\n${formatName(track.format)}"
}

/** The full desktop TCP for the wide layout. [height] is the track height in dp. */
@Composable
internal fun TrackControlPanel(
    track: TrackState,
    height: Float,
    collapsed: Boolean,
    state: EditorState,
    actions: TrackActions,
    callbacks: EditorCallbacks,
    modifier: Modifier = Modifier,
) {
    val pal = LocalAudacityColors.current
    Column(
        modifier
            .background(if (track.selected) pal.trackInfoSelected else pal.trackInfo)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            TcpIconButton(AudacityIcons.Close, stringResource(R.string.aued_remove_track), { actions.remove(track.id) })
            Text(
                track.name, color = pal.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
            )
            TcpIconButton(
                if (collapsed) AudacityIcons.ChevronDown else AudacityIcons.ChevronUp,
                stringResource(if (collapsed) R.string.aued_expand else R.string.aued_collapse),
                { state.toggleCollapsed(track.id) },
            )
            TcpIconButton(AudacityIcons.Ellipsis, stringResource(R.string.aued_track_menu), { callbacks.onTrackMenu(track.id) })
        }
        if (!track.isWave) return@Column
        var remaining = height - 28f
        if (remaining >= 26f) {
            remaining -= 26f
            Row(
                Modifier.fillMaxWidth().height(24.dp).padding(vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToggleFace(stringResource(R.string.aued_mute), track.mute, { actions.mute(track.id, !track.mute) },
                    Modifier.weight(1f).fillMaxHeight())
                ToggleFace(stringResource(R.string.aued_solo), track.solo, { actions.solo(track.id, !track.solo) },
                    Modifier.weight(1f).fillMaxHeight())
            }
        }
        if (remaining >= 28f) {
            remaining -= 28f
            SliderRow("−", "+") { GainSlider(track, actions, it) }
        }
        if (remaining >= 28f) {
            remaining -= 28f
            SliderRow(stringResource(R.string.aued_left), stringResource(R.string.aued_right)) { PanSlider(track, actions, it) }
        }
        if (remaining >= 30f) {
            Text(
                trackInfoText(track), color = pal.text.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 12.sp,
                maxLines = 2, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun SliderRow(start: String, end: String, slider: @Composable (Modifier) -> Unit) {
    val pal = LocalAudacityColors.current
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(start, color = pal.text, fontSize = 10.sp, modifier = Modifier.width(10.dp))
        slider(Modifier.weight(1f).fillMaxHeight())
        Text(end, color = pal.text, fontSize = 10.sp, modifier = Modifier.width(10.dp))
    }
}

/** Compact (phone) header row: [×] name [M][S] [mixer] [⌃] [⋯]. */
@Composable
internal fun TrackHeaderRow(
    track: TrackState,
    collapsed: Boolean,
    mixerOpen: Boolean,
    state: EditorState,
    actions: TrackActions,
    callbacks: EditorCallbacks,
    modifier: Modifier = Modifier,
) {
    val pal = LocalAudacityColors.current
    Column(modifier.background(if (track.selected) pal.trackInfoSelected else pal.trackInfo)) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TcpIconButton(AudacityIcons.Close, stringResource(R.string.aued_remove_track), { actions.remove(track.id) }, size = 32)
            Text(
                track.name, color = pal.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (track.isWave) {
                val muteLabel = stringResource(R.string.aued_mute)
                val soloLabel = stringResource(R.string.aued_solo)
                // Touch areas span the row height and meet (no dead gap between M and S)
                ToggleFace(muteLabel.take(1).uppercase(), track.mute,
                    { actions.mute(track.id, !track.mute) }, Modifier.size(width = 40.dp, height = 32.dp)
                        .semantics { contentDescription = muteLabel },
                    face = Modifier.size(width = 32.dp, height = 24.dp))
                ToggleFace(soloLabel.take(1).uppercase(), track.solo,
                    { actions.solo(track.id, !track.solo) }, Modifier.size(width = 40.dp, height = 32.dp)
                        .semantics { contentDescription = soloLabel },
                    face = Modifier.size(width = 32.dp, height = 24.dp))
                TcpIconButton(AudacityIcons.Mixer, stringResource(R.string.aued_show_mixer), { state.toggleMixer(track.id) }, size = 32)
            }
            TcpIconButton(
                if (collapsed) AudacityIcons.ChevronDown else AudacityIcons.ChevronUp,
                stringResource(if (collapsed) R.string.aued_expand else R.string.aued_collapse),
                { state.toggleCollapsed(track.id) }, size = 32,
            )
            TcpIconButton(AudacityIcons.Ellipsis, stringResource(R.string.aued_track_menu), { callbacks.onTrackMenu(track.id) }, size = 32)
        }
        if (mixerOpen && track.isWave) {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AudacityIcons.Speaker, null, tint = pal.text, modifier = Modifier.size(16.dp))
                GainSlider(track, actions, Modifier.weight(1.2f).fillMaxHeight())
                Text(stringResource(R.string.aued_left), color = pal.text, fontSize = 10.sp)
                PanSlider(track, actions, Modifier.weight(1f).fillMaxHeight())
                Text(stringResource(R.string.aued_right), color = pal.text, fontSize = 10.sp)
                Text(
                    trackInfoText(track), color = pal.text.copy(alpha = 0.75f), fontSize = 9.sp, lineHeight = 10.sp,
                    maxLines = 2, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
