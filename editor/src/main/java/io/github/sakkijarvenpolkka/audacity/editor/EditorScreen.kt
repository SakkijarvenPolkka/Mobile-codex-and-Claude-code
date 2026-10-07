/*
 * Audacity Android port — the editor: timeline ruler + track panel
 * (track control panels, vertical ruler, waveform/label area, play head).
 *
 * Layout (notes/ui-reference.md §5.1, §9): below 600 dp width a compact
 * header row is shown above each track (phone portrait); from 600 dp the
 * desktop TCP column (124/150 dp) and vertical ruler are shown at the left.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.Zoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The editor: timeline ruler and track panel with track control panels.
 * Zoom/scroll live in [state] (persisted with `engine.setView`); every edit
 * goes through [engine]; menus and dialogs are requested via [callbacks].
 */
@Composable
fun EditorScreen(engine: AudacityEngine, state: EditorState, callbacks: EditorCallbacks, modifier: Modifier = Modifier) {
    val snapshot by engine.snapshot.collectAsState()
    val pal = LocalAudacityColors.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val cache = remember(engine) { WaveTileCache(engine, scope) }
    val controller = remember(engine, state) { PanelController(engine, state, scope) }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val painter = remember(cache, measurer) { TrackPainter(cache, measurer) }
    val actions = remember(engine) { TrackActions(engine, scope) { e -> controller.callbacks.onMessage(e.message ?: e.toString()) } }
    val currentSnapshot by rememberUpdatedState(snapshot)

    SideEffect {
        controller.callbacks = callbacks
        controller.density = density
        controller.painter = painter
        controller.haptic = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        state.selectionT0 = snapshot.selection.t0
        state.selectionT1 = snapshot.selection.t1
        state.projectEnd = snapshot.projectEnd
    }
    LaunchedEffect(snapshot) {
        controller.onSnapshot(snapshot)
        cache.syncTracks(snapshot.tracks)
    }

    val head = rememberHeadState(
        engine,
        playEnd = { max(currentSnapshot.projectEnd, currentSnapshot.selection.t1) },
        onFrame = { h ->
            state.streamingHeadTime = if (h.isStreaming) h.time else Double.NaN
            if (h.isStreaming) state.followHead(h.time)
        },
    )

    ViewPersistence(engine, state, snapshot)

    BoxWithConstraints(modifier.background(pal.trackBackground)) {
        val metrics = remember(maxWidth) { EditorMetrics.forWidth(maxWidth.value) }
        val layout = PanelLayout.compute(snapshot.tracks, state, metrics)
        SideEffect {
            controller.layout = layout
            state.totalContentHeightDp = layout.contentHeight
            state.clampVertical()
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(metrics.rulerHeight.dp)) {
                if (!metrics.compact) {
                    TimelineCorner(snapshot, callbacks, Modifier.width(metrics.waveLeft.dp).fillMaxHeight())
                }
                TimelineRuler(engine, state, snapshot, head, callbacks, metrics,
                    Modifier.weight(1f).fillMaxHeight().testTag(EditorTags.RULER))
            }
            TrackPanel(engine, state, snapshot, layout, metrics, controller, cache, painter, head, actions, callbacks,
                Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Left part of the ruler row in the wide layout: the Timeline Options button. */
@Composable
private fun TimelineCorner(snapshot: Snapshot, callbacks: EditorCallbacks, modifier: Modifier) {
    val pal = LocalAudacityColors.current
    val desc = stringResource(R.string.aued_timeline_options)
    Box(modifier.background(pal.medium), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .size(28.dp)
                .clickable { callbacks.onContextMenu(ContextTarget.Timeline(snapshot.selection.t0)) }
                .semantics { contentDescription = desc },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AudacityIcons.TimelineOptions, null, tint = pal.text, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Persists zoom/scroll with `engine.setView` (debounced) and adopts a view
 * the engine reports that is not the echo of our own (a project was opened
 * or a new project reset the view).
 */
@Composable
private fun ViewPersistence(engine: AudacityEngine, state: EditorState, snapshot: Snapshot) {
    val view = snapshot.view
    LaunchedEffect(view) {
        if (state.gestureActive) return@LaunchedEffect
        val echo = EditorState.near(view.zoom, state.lastSentZoom) && EditorState.near(view.hpos, state.lastSentHpos)
        if (echo) return@LaunchedEffect
        if (!(EditorState.near(view.zoom, state.pps) && EditorState.near(view.hpos, state.hpos))) {
            state.adoptView(view.zoom, view.hpos)
        }
        state.lastSentZoom = view.zoom
        state.lastSentHpos = view.hpos
    }
    LaunchedEffect(engine, state) {
        snapshotFlow { state.viewChangeCount }
            .distinctUntilChanged()
            .collectLatest {
                delay(VIEW_COMMIT_DELAY_MS)
                val zoom = state.pps
                val hpos = state.hpos
                if (!EditorState.near(zoom, state.lastSentZoom) || !EditorState.near(hpos, state.lastSentHpos)) {
                    state.lastSentZoom = zoom
                    state.lastSentHpos = hpos
                    runCatching { engine.setView(zoom, hpos) }
                }
            }
    }
}

private const val VIEW_COMMIT_DELAY_MS = 400L
private const val RETRY_DELAY_MS = 300L

@Composable
private fun TrackPanel(
    engine: AudacityEngine,
    state: EditorState,
    snapshot: Snapshot,
    layout: PanelLayout,
    metrics: EditorMetrics,
    controller: PanelController,
    cache: WaveTileCache,
    painter: TrackPainter,
    head: HeadState,
    actions: TrackActions,
    callbacks: EditorCallbacks,
    modifier: Modifier,
) {
    val pal = LocalAudacityColors.current
    val density = LocalDensity.current.density
    val panelDescription = stringResource(R.string.aued_waveform_area)
    val currentLayout by rememberUpdatedState(layout)
    val currentSnapshot by rememberUpdatedState(snapshot)

    // Prefetch waveform tiles for the visible area whenever the view, the
    // layout or the data changes; poll partial tiles while recording.
    LaunchedEffect(cache, state, density) {
        snapshotFlow {
            PrefetchKey(state.pps, state.hpos, state.viewportWidthDp, state.vposDp, state.viewportHeightDp,
                currentSnapshot, currentLayout, state.spectrogramTracks)
        }.collectLatest { key ->
            prefetch(cache, state, key.snapshot, key.layout, density, head.time, head.recordingStart)
        }
    }
    // Engine busy (NOT_READY): retry shortly.
    LaunchedEffect(cache, state, density) {
        snapshotFlow { cache.retryPending }.collectLatest { pending ->
            if (pending) {
                delay(RETRY_DELAY_MS)
                prefetch(cache, state, currentSnapshot, currentLayout, density, head.time, head.recordingStart)
            }
        }
    }
    LaunchedEffect(head.isRecording) {
        if (head.isRecording) {
            while (isActive) {
                delay(100)
                prefetch(cache, state, currentSnapshot, currentLayout, density, head.time, head.recordingStart)
            }
        }
    }
    // Track UI state of deleted tracks.
    val ids = snapshot.tracks.map { it.id }
    LaunchedEffect(ids) { if (ids.isNotEmpty()) state.retainTracks(ids.toSet()) }
    // Scroll a cursor moved by a command (skip to start/end, selection
    // toolbar) into view.
    LaunchedEffect(snapshot.selection) {
        val sel = snapshot.selection
        if (sel.t1 <= sel.t0 && !state.gestureActive && !head.isStreaming) {
            val t = sel.t0
            if (t < state.hpos || t > state.screenEndTime) state.scrollToTime(t)
        }
    }
    // Viewport width for the engine's display caches.
    val widthPx = (state.viewportWidthDp * density).roundToInt()
    LaunchedEffect(widthPx) { if (widthPx > 0) runCatching { engine.setViewportWidth(widthPx) } }

    val params = remember { PaintParams() }
    val clipNameStyle = remember(pal) { TextStyle(color = pal.clipName, fontSize = 11.sp) }
    val labelStyle = remember(pal) { TextStyle(color = pal.labelText, fontSize = 12.sp) }

    Box(
        modifier
            .testTag(EditorTags.TRACK_PANEL)
            .clipToBounds()
            .onSizeChanged { state.viewportHeightDp = it.height / density }
            .semantics { contentDescription = panelDescription }
            .pointerInput(controller) { panelGestures(controller) }
            .pointerInput(controller) { panelWheel(controller) },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (!metrics.compact) {
                // TCP column.
                Box(Modifier.width(metrics.tcpWidth.dp).fillMaxHeight().clipToBounds().background(pal.trackBackground)) {
                    for (g in layout.tracks) {
                        TrackControlPanel(
                            g.track, g.body, g.collapsed, state, actions, callbacks,
                            Modifier
                                .offset { IntOffset(0, ((g.top - state.vposDp) * density).roundToInt()) }
                                .width(metrics.tcpWidth.dp)
                                .height(g.body.dp),
                        )
                    }
                }
                VerticalRuler(layout, state, Modifier.width(metrics.vrWidth.dp).fillMaxHeight())
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag(EditorTags.WAVE_AREA)
                    .onSizeChanged { state.viewportWidthDp = it.width / density },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val sel = controller.previewSelection ?: snapshot.selection
                    val ghost = controller.ghost
                    val pressed = controller.pressedClip
                    params.snapshot = snapshot
                    params.layout = layout
                    params.palette = pal
                    params.pps = state.pps
                    params.hpos = state.hpos
                    params.vposDp = state.vposDp
                    params.showRms = state.showRms
                    params.showClipping = state.showClipping
                    params.spectroTracks = state.spectrogramTracks
                    params.selT0 = sel.t0
                    params.selT1 = sel.t1
                    params.selTracks = controller.previewTracks
                    params.ghostTrackId = ghost?.trackId ?: PaintParams.NO_TRACK
                    params.ghostClipIndex = ghost?.clip?.index ?: -1
                    params.ghostDt = ghost?.dt ?: 0.0
                    params.ghostTargetTrackId = ghost?.targetTrackId ?: PaintParams.NO_TRACK
                    params.pressedTrackId = pressed?.first ?: PaintParams.NO_TRACK
                    params.pressedClipIndex = pressed?.second ?: -1
                    if (head.isRecording) {
                        params.recordHead = head.time
                        params.recordingStart = head.recordingStart
                    } else {
                        params.recordHead = Double.NaN
                        params.recordingStart = Double.NaN
                    }
                    @Suppress("UNUSED_VARIABLE")
                    val revision = cache.revision          // redraw when tiles arrive
                    painter.clipNameStyle = clipNameStyle
                    painter.labelStyle = labelStyle
                    painter.paint(this, params)
                }
                PlayHeadOverlay(state, head, layout, Modifier.fillMaxSize())
                if (snapshot.tracks.isEmpty()) {
                    Text(
                        stringResource(R.string.aued_no_tracks),
                        color = Color(0xFFDDDEE4), fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
            }
        }
        if (metrics.compact) {
            // Compact header rows over the canvas.
            for (g in layout.tracks) {
                TrackHeaderRow(
                    g.track, g.collapsed, state.isMixerOpen(g.track.id), state, actions, callbacks,
                    Modifier
                        .offset { IntOffset(0, ((g.top - state.vposDp) * density).roundToInt()) }
                        .fillMaxWidth()
                        .height(g.header.dp),
                )
            }
        }
    }
}

/** Play/record head line over the tracks (redrawn every frame while streaming). */
@Composable
private fun PlayHeadOverlay(state: EditorState, head: HeadState, layout: PanelLayout, modifier: Modifier) {
    val pal = LocalAudacityColors.current
    Canvas(modifier) {
        val t = head.time
        if (t.isNaN()) return@Canvas
        val x = ((t - state.hpos) * state.pps * density).toFloat()
        if (x < -2f || x > size.width + 2f) return@Canvas
        val bottom = min(size.height, (layout.contentHeight - state.vposDp) * density)
        if (bottom <= 0f) return@Canvas
        drawLine(
            if (head.isRecording) pal.recordHead else pal.playHead,
            Offset(x, 0f), Offset(x, bottom), strokeWidth = max(1f, density),
        )
    }
}

/** Vertical ruler column (wide layout): −1.0…+1.0 per channel (Linear amp). */
@Composable
private fun VerticalRuler(layout: PanelLayout, state: EditorState, modifier: Modifier) {
    val pal = LocalAudacityColors.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val style = remember(pal) { TextStyle(color = pal.text, fontSize = 8.sp) }
    Canvas(modifier.background(pal.trackBackground)) {
        val d = density
        val w = size.width
        for (g in layout.tracks) {
            val top = (g.top - state.vposDp) * d
            val bottom = (g.bottom - state.vposDp) * d
            if (bottom < 0f || top > size.height) continue
            drawRect(if (g.track.selected) pal.trackInfoSelected else pal.trackInfo, Offset(0f, top), Size(w, bottom - top))
            drawLine(Color.Black, Offset(w - d / 2f, top), Offset(w - d / 2f, bottom), d)
            if (!g.track.isWave) continue
            val tb = (g.bodyTop + g.titleBar - state.vposDp) * d
            drawLine(pal.dark, Offset(0f, tb), Offset(w, tb), d)
            val chH = g.channelHeight(TrackPainter.CH_SEP) * d
            val spectro = g.track.id in state.spectrogramTracks
            for (ch in 0 until g.channelCount) {
                val cy = (g.channelTop(ch, TrackPainter.CH_SEP) - state.vposDp) * d
                if (spectro) {
                    // Linear frequency scale 0 … rate/2 (API.md §7.5).
                    val top = measurer.measure(nyquistLabel(g.track.rate), style)
                    drawText(top, topLeft = Offset(w - 6f * d - top.size.width, cy))
                    val zero = measurer.measure("0", style)
                    drawText(zero, topLeft = Offset(w - 6f * d - zero.size.width, cy + chH - zero.size.height))
                    continue
                }
                val full = chH > 80f * d
                val values = if (full) VR_VALUES_FULL else VR_VALUES_SHORT
                val labels = if (full) VR_LABELS_FULL else VR_LABELS_SHORT
                for (vi in values.indices) {
                    val y = cy + TrackPainter.rowOf(values[vi], chH)
                    drawLine(pal.text, Offset(w - 5f * d, y), Offset(w, y), d)
                    val tl = measurer.measure(labels[vi], style)
                    val ty = (y - tl.size.height / 2f).coerceIn(cy, cy + chH - tl.size.height)
                    drawText(tl, topLeft = Offset(w - 6f * d - tl.size.width, ty))
                }
            }
        }
    }
}

private fun nyquistLabel(rate: Double): String {
    val k = rate / 2000.0
    return if (k == floor(k)) "${k.toInt()}k" else String.format(java.util.Locale.ROOT, "%.1fk", k)
}

private val VR_VALUES_FULL = floatArrayOf(1f, 0.5f, 0f, -0.5f, -1f)
private val VR_VALUES_SHORT = floatArrayOf(1f, 0f, -1f)
private val VR_LABELS_FULL = arrayOf("1.0", "0.5", "0.0", "-0.5", "-1.0")
private val VR_LABELS_SHORT = arrayOf("1.0", "0.0", "-1.0")

/** Inputs of one prefetch round. */
private data class PrefetchKey(
    val pps: Double,
    val hpos: Double,
    val width: Float,
    val vpos: Float,
    val height: Float,
    val snapshot: Snapshot,
    val layout: PanelLayout,
    val spectrogram: Set<Long>,
)

/** Requests the tiles / sample windows needed for the visible area (± one tile). */
internal fun prefetch(
    cache: WaveTileCache,
    state: EditorState,
    snapshot: Snapshot,
    layout: PanelLayout,
    density: Float,
    recordHead: Double,
    recordingStart: Double = Double.NaN,
) {
    cache.syncTracks(snapshot.tracks)
    cache.beginPass()
    val pxPerSec = state.pps * density
    val level = Zoom.levelAtOrBelow(pxPerSec)
    val levelPps = Zoom.ppsForLevel(level)
    val t0 = state.hpos
    val t1 = state.hpos + state.usableWidthDp / state.pps
    val margin = Zoom.TILE_COLUMNS / levelPps
    val vTop = state.vposDp - 100f
    val vBottom = state.vposDp + max(state.viewportHeightDp, 200f) + 100f
    for (g in layout.tracks) {
        val track = g.track
        if (!track.isWave) continue
        if (g.bottom < vTop || g.top > vBottom) continue
        val channels = g.channelCount
        for (clip in track.clips) {
            val end = recordingEnd(track, clip, recordHead, recordingStart)
            if (end < t0 - margin || clip.start > t1 + margin) continue
            if (Zoom.needsSampleMode(pxPerSec, clip.rate, clip.stretchRatio)) {
                val a = max(t0, clip.start)
                val b = min(t1, end)
                if (b > a) for (ch in 0 until channels) cache.samples(track.id, ch, a, b, request = true)
                continue
            }
            val a = max(t0 - margin, clip.start)
            val b = min(t1 + margin, end)
            val firstTile = Math.floorDiv(floor(a * levelPps).toLong(), Zoom.TILE_COLUMNS.toLong())
            val lastTile = Math.floorDiv(floor(b * levelPps).toLong(), Zoom.TILE_COLUMNS.toLong())
            val spectro = state.isSpectrogram(track.id)
            var ti = firstTile
            while (ti <= lastTile) {
                if (spectro) {
                    for (ch in 0 until channels) cache.request(track.id, WaveTileCache.SPECTRO + ch, level, ti)
                } else {
                    for (ch in 0 until channels) cache.request(track.id, ch, level, ti)
                    cache.request(track.id, WaveTileCache.ENVELOPE, level, ti)
                }
                ti++
            }
        }
    }
    cache.trim(level)
}
