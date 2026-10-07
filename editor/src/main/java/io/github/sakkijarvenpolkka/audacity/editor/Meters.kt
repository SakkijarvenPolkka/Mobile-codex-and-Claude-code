/*
 * Audacity Android port — Recording / Playback meter toolbars
 * (notes/ui-reference.md §4.6, port of the 3.7.9 MeterPanel ballistics).
 *
 * dB scale with a 60 dB range, pos = clamp01((20·log10(v) + 60) / 60);
 * peak decay 60 dB/s; RMS smoothing s = 0.9^(frames/1024); a recent-peak
 * line held 3 s; a max-peak line held until reset; clip latch until reset
 * (tap the meter, or a new play/record stream). Gradient green → yellow →
 * red over the last 12 dB (MeterPanel.cpp:474-551). Audacity, the Audacity
 * Team; GPL-2.0-or-later.
 *
 * API.md §6.5: readMeters() resets the engine's accumulators, so exactly one
 * reader may exist per process: all MeterToolbar instances share one
 * [MeterHub] per engine.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.MeterSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Ballistics of one meter (two channels), positions normalized 0..1. */
internal class MeterBallistics(private val dbRange: Float = 60f) {
    val peak = FloatArray(2)
    val rms = FloatArray(2)
    val recentPeak = FloatArray(2)
    private val recentPeakAge = FloatArray(2)
    val maxPeak = FloatArray(2)
    val clipped = BooleanArray(2)
    var channels = 0
        private set

    /** Linear amplitude → normalized position on the dB scale. */
    fun pos(v: Float): Float {
        if (!(v > 0f)) return 0f
        return ((20f * log10(v) + dbRange) / dbRange).coerceIn(0f, 1f)
    }

    /**
     * Applies one reading ([peaks], [rmsValues] linear, [clip] flags) taken
     * after [dt] seconds at [rate] frames/s; [nCh] = 0 means no data (decay).
     */
    fun update(peaks: FloatArray?, rmsValues: FloatArray?, clip: BooleanArray?, nCh: Int, dt: Float, rate: Float) {
        if (nCh > 0) channels = nCh.coerceAtMost(2)
        val decay = 60f * dt / dbRange                 // 60 dB/s
        val s = 0.9f.pow(dt * rate / 1024f)
        for (c in 0 until 2) {
            val newPeak = if (peaks != null && c < nCh) pos(peaks[c]) else 0f
            val newRms = if (rmsValues != null && c < nCh) pos(rmsValues[c]) else 0f
            peak[c] = max(newPeak, peak[c] - decay)
            rms[c] = rms[c] * s + newRms * (1f - s)
            recentPeakAge[c] += dt
            if (newPeak >= recentPeak[c] || recentPeakAge[c] > PEAK_HOLD_SECONDS) {
                recentPeak[c] = max(newPeak, peak[c])
                if (newPeak >= recentPeak[c]) recentPeakAge[c] = 0f
            }
            if (newPeak > maxPeak[c]) maxPeak[c] = newPeak
            if (clip != null && c < nCh && clip[c]) clipped[c] = true
        }
    }

    /** True while something is still visible (keeps decaying). */
    val isLive: Boolean get() = peak[0] > 0f || peak[1] > 0f || rms[0] > 0.001f || rms[1] > 0.001f

    fun reset() {
        peak.fill(0f); rms.fill(0f); recentPeak.fill(0f); recentPeakAge.fill(0f); maxPeak.fill(0f)
        clipped.fill(false)
    }

    companion object {
        const val PEAK_HOLD_SECONDS = 3f
    }
}

/** Shared 30 Hz reader of engine.readMeters() (one per engine). */
@Stable
internal class MeterHub(private val engine: AudacityEngine) {
    val record = MeterBallistics()
    val playback = MeterBallistics()
    /** Bumped on every update; meters observe it to redraw. */
    var frame by mutableIntStateOf(0)
        private set
    private var users = 0
    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var lastState = "stopped"

    fun acquire() {
        if (users++ == 0) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch {
                engine.transportState.collectLatest { ev ->
                    val starting = ev.state == "playing" || ev.state == "recording"
                    if (starting && lastState != ev.state && lastState != "paused") {
                        record.reset(); playback.reset()     // clip flags are reset per stream
                    }
                    lastState = ev.state
                    val active = ev.state != "stopped"
                    poll(active)
                }
            }
        }
    }

    fun release() {
        if (--users == 0) {
            scope?.cancel()
            scope = null
            pollJob = null
        }
    }

    /** Polls while [active], then until the bars decayed to zero. */
    private suspend fun poll(active: Boolean) {
        var last = System.nanoTime()
        while (true) {
            delay(INTERVAL_MS)
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9).toFloat().coerceIn(0f, 0.5f)
            last = now
            val m: MeterSample? = runCatching { engine.readMeters() }.getOrNull()
            val rate = runCatching { engine.readTransport().deviceRate }.getOrDefault(48000.0).toFloat()
                .let { if (it > 0f) it else 48000f }
            if (m != null) {
                record.update(m.recPeak, m.recRms, m.recClip, m.recChannels, dt, rate)
                playback.update(m.playPeak, m.playRms, m.playClip, m.playChannels, dt, rate)
            } else {
                record.update(null, null, null, 0, dt, rate)
                playback.update(null, null, null, 0, dt, rate)
            }
            frame++
            if (!active && !record.isLive && !playback.isLive) break
        }
    }

    fun resetAll() {
        record.reset()
        playback.reset()
        frame++
    }

    companion object {
        const val INTERVAL_MS = 33L
        private val hubs = WeakHashMap<AudacityEngine, MeterHub>()

        fun of(engine: AudacityEngine): MeterHub = synchronized(hubs) { hubs.getOrPut(engine) { MeterHub(engine) } }
    }
}

/**
 * Recording and Playback meters stacked (MeterToolBar ×2): mic/speaker
 * button (tap = start/stop input monitoring), L/R bars with a dB scale,
 * peak hold and clip indicators; tap the bars to reset.
 */
@Composable
fun MeterToolbar(engine: AudacityEngine, modifier: Modifier = Modifier) {
    val pal = LocalAudacityColors.current
    val hub = remember(engine) { MeterHub.of(engine) }
    DisposableEffect(hub) {
        hub.acquire()
        onDispose { hub.release() }
    }
    val snapshot by engine.snapshot.collectAsState()
    val transport by engine.transportState.collectAsState()
    val scope = rememberCoroutineScope()
    val monitoring = transport.state == "monitoring"
    val canMonitor = snapshot.has(CommandFlags.RECORD_PERMISSION) && (transport.state == "stopped" || monitoring)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val scaleStyle = remember(pal) { TextStyle(color = pal.text, fontSize = 8.sp) }
    val recDesc = stringResource(R.string.aued_recording_meter)
    val playDesc = stringResource(R.string.aued_playback_meter)
    val monitorDesc = stringResource(if (monitoring) R.string.aued_stop_monitoring else R.string.aued_start_monitoring)
    val resetDesc = stringResource(R.string.aued_meter_reset)

    Column(modifier.background(pal.medium).padding(horizontal = 4.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        MeterRow(
            hub, hub.record, pal.meterInput, recDesc, resetDesc,
            icon = {
                Box(
                    Modifier
                        .size(24.dp)
                        .clickable(enabled = canMonitor, role = Role.Button, onClickLabel = monitorDesc) {
                            scope.engineCall { engine.monitor(!monitoring) }
                        }
                        .semantics { contentDescription = monitorDesc },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AudacityIcons.Mic, null, tint = if (monitoring) pal.meterInput.peak else pal.glyph, modifier = Modifier.size(18.dp))
                }
            },
        )
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.size(24.dp, 10.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight()) { drawScale(measurer, scaleStyle, pal.text) }
        }
        MeterRow(
            hub, hub.playback, pal.meterOutput, playDesc, resetDesc,
            icon = {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(AudacityIcons.Speaker, null, tint = pal.glyph, modifier = Modifier.size(18.dp))
                }
            },
        )
    }
}

@Composable
private fun MeterRow(
    hub: MeterHub,
    meter: MeterBallistics,
    colors: MeterColors,
    description: String,
    resetLabel: String,
    icon: @Composable () -> Unit,
) {
    val pal = LocalAudacityColors.current
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(role = Role.Button, onClickLabel = resetLabel) { hub.resetAll() }
                .semantics { contentDescription = description }
                .drawWithCache {
                    // Gradient over the bar width (cached per size / palette).
                    val barW = size.width - 5.dp.toPx() - 1.dp.toPx()
                    val gradient = Brush.horizontalGradient(
                        0f to pal.meterGreen, 0.8f to pal.meterGreen, 0.9f to pal.meterYellow, 1f to pal.meterRed,
                        startX = 0f, endX = barW,
                    )
                    onDrawBehind {
                        @Suppress("UNUSED_VARIABLE")
                        val f = hub.frame
                        drawMeter(meter, colors, pal, gradient)
                    }
                },
        )
    }
}

private val SCALE_DB = intArrayOf(-60, -48, -36, -24, -12, 0)
private val SCALE_LABELS = arrayOf("-60", "-48", "-36", "-24", "-12", "0")

private fun DrawScope.drawScale(measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle, color: Color) {
    val clipW = 5.dp.toPx()
    val w = size.width - clipW
    for (i in SCALE_DB.indices) {
        val x = w * (SCALE_DB[i] + 60f) / 60f
        drawLine(color, Offset(x, 0f), Offset(x, 2.dp.toPx()), 1f)
        val tl = measurer.measure(SCALE_LABELS[i], style)
        val tx = (x - tl.size.width / 2f).coerceIn(0f, size.width - tl.size.width)
        drawText(tl, topLeft = Offset(tx, size.height - tl.size.height))
    }
}

private fun DrawScope.drawMeter(m: MeterBallistics, colors: MeterColors, pal: AudacityPalette, gradient: Brush) {
    val clipW = 5.dp.toPx()
    val gap = 1.dp.toPx()
    val barW = size.width - clipW - gap
    val barH = (size.height - 3 * gap) / 2f
    for (c in 0 until 2) {
        val y = gap + c * (barH + gap)
        drawRect(pal.meterBackground, Offset(0f, y), Size(barW, barH))
        val active = m.channels == 0 || c < m.channels || m.channels == 1
        val ch = if (m.channels == 1) 0 else c
        if (active) {
            val pk = m.peak[ch] * barW
            if (pk > 0f) drawRect(gradient, Offset(0f, y), Size(pk, barH))
            val rm = m.rms[ch] * barW
            if (rm > 0f) drawRect(colors.rms.copy(alpha = 0.55f), Offset(0f, y + barH * 0.25f), Size(rm, barH * 0.5f))
            val rp = m.recentPeak[ch] * barW
            if (rp > 0f) drawRect(colors.peak, Offset(rp - 2.dp.toPx(), y), Size(2.dp.toPx(), barH))
            val mp = m.maxPeak[ch] * barW
            if (mp > 0f) drawRect(pal.meterPeak, Offset(mp - 2.dp.toPx(), y), Size(2.dp.toPx(), barH))
        }
        drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, y), Size(barW, barH), style = Stroke(1f))
        // Clip indicator at the hot end.
        val clipped = m.clipped[ch]
        drawRect(if (clipped) colors.clip else pal.meterBackground, Offset(barW + gap, y), Size(clipW, barH))
        drawRect(Color.Black.copy(alpha = 0.6f), Offset(barW + gap, y), Size(clipW, barH), style = Stroke(1f))
    }
}
