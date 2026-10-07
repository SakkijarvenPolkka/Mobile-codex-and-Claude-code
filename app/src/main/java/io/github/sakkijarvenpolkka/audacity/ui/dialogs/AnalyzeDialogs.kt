/*
 * Audacity Android port — Analyze ▸ Plot Spectrum... and Contrast...
 *
 * Re-implementations of the wx dialogs src/FreqWindow.cpp and
 * src/effects/Contrast.cpp of Audacity 3.7.9 (the Audacity Team,
 * GPL-2.0-or-later) over the engine's analyze.spectrum / analyze.contrast.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.ContrastResult
import io.github.sakkijarvenpolkka.audacity.engine.model.SpectrumResult
import io.github.sakkijarvenpolkka.audacity.ui.AppDialogFrame
import io.github.sakkijarvenpolkka.audacity.ui.Dropdown
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Plot Spectrum options (FreqWindow.cpp:211-258) and helpers (pure; unit-tested). */
object SpectrumPlot {
    val ALGORITHMS = listOf("spectrum", "autocorrelation", "cubeRootAutocorrelation", "enhancedAutocorrelation")
    val ALGORITHM_LABELS = listOf(R.string.ps_alg_spectrum, R.string.ps_alg_autocorr, R.string.ps_alg_cuberoot, R.string.ps_alg_enhanced)
    val WINDOWS = listOf("rectangular", "bartlett", "hamming", "hann", "blackman", "blackmanHarris", "welch", "gaussian25", "gaussian35", "gaussian45")
    /** WindowFuncName (lib-fft/FFT.cpp). */
    val WINDOW_LABELS = listOf("Rectangular", "Bartlett", "Hamming", "Hann", "Blackman", "Blackman-Harris", "Welch",
        "Gaussian(a=2.5)", "Gaussian(a=3.5)", "Gaussian(a=4.5)")
    val SIZES = (7..16).map { 1 shl it }

    private val NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Note name of a frequency, e.g. 440 → "A4" (FreqWindow PitchName). */
    fun pitchName(f: Double): String {
        if (!(f > 0.0)) return ""
        val pitch = 69.0 + 12.0 * (ln(f / 440.0) / ln(2.0))
        val n = pitch.roundToInt()
        if (n < 0) return ""
        return NOTES[n % 12] + (n / 12 - 1)
    }

    /** X value (Hz or seconds) of bin [i]. */
    fun xOf(r: SpectrumResult, i: Int): Double = i * (r.binHz ?: r.binSeconds ?: 1.0)

    /** Index of the local maximum within ±[radius] bins of [i]. */
    fun peakNear(values: List<Float>, i: Int, radius: Int): Int {
        if (values.isEmpty()) return -1
        val c = i.coerceIn(0, values.lastIndex)
        var best = c
        for (j in max(0, c - radius)..min(values.lastIndex, c + radius)) if (values[j] > values[best]) best = j
        return best
    }
}

@Composable
fun PlotSpectrumDialog(d: AppDialog, vm: AppViewModel) {
    var alg by rememberSaveable { mutableIntStateOf(0) }
    var win by rememberSaveable { mutableIntStateOf(3) }
    var sizeIdx by rememberSaveable { mutableIntStateOf(3) }
    var logAxis by rememberSaveable { mutableStateOf(true) }
    var result by remember { mutableStateOf<SpectrumResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var cursorX by remember { mutableFloatStateOf(-1f) }   // fraction of the plot width

    LaunchedEffect(alg, win, sizeIdx) {
        loading = true
        try {
            result = vm.engine.plotSpectrum(SpectrumPlot.ALGORITHMS[alg], SpectrumPlot.WINDOWS[win], SpectrumPlot.SIZES[sizeIdx])
        } catch (e: Exception) {
            vm.reportError(e)
        } finally {
            loading = false
        }
    }

    AppDialogFrame(
        title = stringResource(R.string.ps_title),
        onDismiss = { vm.dismiss(d) },
        buttons = { TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_close)) } },
        maxWidth = 900.dp,
    ) {
        val r = result
        val isSpectrum = alg == 0
        Box(Modifier.fillMaxWidth().height(260.dp)) {
            if (r != null && r.values.isNotEmpty()) {
                SpectrumChart(r, isSpectrum && logAxis, cursorX) { cursorX = it }
            }
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (r != null) {
            r.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Readout(r, isSpectrum, logAxis, cursorX)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val algLabels = SpectrumPlot.ALGORITHM_LABELS.map { stringResource(it) }
            Dropdown(stringResource(R.string.ps_algorithm), algLabels.indices.toList(), alg, { algLabels[it] }, { alg = it }, Modifier.weight(1f))
            Dropdown(stringResource(R.string.ps_size), SpectrumPlot.SIZES.indices.toList(), sizeIdx, { SpectrumPlot.SIZES[it].toString() },
                { sizeIdx = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(stringResource(R.string.ps_function), SpectrumPlot.WINDOWS.indices.toList(), win, { SpectrumPlot.WINDOW_LABELS[it] },
                { win = it }, Modifier.weight(1f))
            val axes = listOf(stringResource(R.string.ps_axis_linear), stringResource(R.string.ps_axis_log))
            Dropdown(stringResource(R.string.ps_axis), listOf(0, 1), if (logAxis) 1 else 0, { axes[it] }, { logAxis = it == 1 },
                Modifier.weight(1f), enabled = isSpectrum)
        }
    }
}

@Composable
private fun Readout(r: SpectrumResult, isSpectrum: Boolean, logAxis: Boolean, cursorX: Float) {
    if (cursorX < 0f || r.values.isEmpty()) {
        Text(stringResource(R.string.ps_tap_hint), style = MaterialTheme.typography.bodySmall)
        return
    }
    val n = r.values.size
    val idx = indexAt(r, isSpectrum && logAxis, cursorX)
    val peak = SpectrumPlot.peakNear(r.values, idx, max(2, n / 100))
    fun describe(i: Int): String {
        val x = SpectrumPlot.xOf(r, i)
        val y = r.values[i]
        return if (isSpectrum) String.format(Locale.ROOT, "%.0f Hz (%s) = %.1f dB", x, SpectrumPlot.pitchName(x), y)
        else String.format(Locale.ROOT, "%.4f sec (%.0f Hz) (%s) = %.3f", x, if (x > 0) 1 / x else 0.0, SpectrumPlot.pitchName(if (x > 0) 1 / x else 0.0), y)
    }
    Text(stringResource(R.string.ps_cursor, describe(idx)), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.ps_peak, describe(peak)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

private fun indexAt(r: SpectrumResult, log: Boolean, fx: Float): Int {
    val n = r.values.size
    if (n == 0) return 0
    if (!log) return (fx * (n - 1)).roundToInt().coerceIn(0, n - 1)
    val lo = log10(1.0)
    val hi = log10((n - 1).toDouble().coerceAtLeast(2.0))
    val bin = 10.0.pow(lo + fx * (hi - lo))
    return bin.roundToInt().coerceIn(1, n - 1)
}

@Composable
private fun SpectrumChart(r: SpectrumResult, log: Boolean, cursorX: Float, onCursor: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val style = TextStyle(color = colors.onSurfaceVariant, fontSize = 9.sp)
    val left = 36f
    val bottom = 16f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(r) { detectTapGestures { p -> onCursor(((p.x - left * density) / (size.width - left * density)).coerceIn(0f, 1f)) } }
            .pointerInput(r) {
                detectDragGestures { ch, _ -> onCursor(((ch.position.x - left * density) / (size.width - left * density)).coerceIn(0f, 1f)) }
            },
    ) {
        val l = left * density
        val b = bottom * density
        val w = size.width - l
        val h = size.height - b
        drawRect(colors.surfaceVariant, Offset(l, 0f), androidx.compose.ui.geometry.Size(w, h))
        val n = r.values.size
        val yMin = r.minValue.takeIf { it < r.maxValue } ?: (r.values.minOrNull()?.toDouble() ?: 0.0)
        val yMax = r.maxValue.takeIf { it > yMin } ?: (yMin + 1.0)
        fun xFor(i: Int): Float = if (log) {
            val hi = log10((n - 1).toDouble().coerceAtLeast(2.0))
            (l + log10(i.coerceAtLeast(1).toDouble()) / hi * w).toFloat()
        } else l + i.toFloat() / (n - 1).coerceAtLeast(1) * w
        fun yFor(v: Double): Float = (h - (v - yMin) / (yMax - yMin) * h).toFloat().coerceIn(0f, h)
        // y grid
        val span = yMax - yMin
        val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0).firstOrNull { span / it <= 8 } ?: (span / 8)
        var gy = floor(yMin / step) * step
        while (gy <= yMax) {
            if (gy >= yMin) {
                val y = yFor(gy)
                drawLine(colors.outlineVariant, Offset(l, y), Offset(l + w, y), 1f)
                val tl = measurer.measure(TimeCodec.number(gy, 1), style)
                drawText(tl, topLeft = Offset((l - tl.size.width - 2f).coerceAtLeast(0f), (y - tl.size.height / 2f).coerceIn(0f, h)))
            }
            gy += step
        }
        // x labels
        val unitStep = r.binHz ?: r.binSeconds ?: 1.0
        val ticks = if (log) listOf(100.0, 1000.0, 10000.0).map { (it / unitStep).roundToInt() }.filter { it in 1 until n }
        else (1..4).map { (n - 1) * it / 5 }
        for (i in ticks) {
            val x = xFor(i)
            drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, h), 1f)
            val v = SpectrumPlot.xOf(r, i)
            val label = if (r.binHz != null) (if (v >= 1000) TimeCodec.number(v / 1000, 1) + "k" else TimeCodec.number(v, 0)) + "Hz"
            else TimeCodec.number(v, 3) + "s"
            val tl = measurer.measure(label, style)
            drawText(tl, topLeft = Offset((x - tl.size.width / 2f).coerceIn(l, size.width - tl.size.width), h + 1f))
        }
        // data
        val path = Path()
        val start = if (log) 1 else 0
        for (i in start until n) {
            val x = xFor(i)
            val y = yFor(r.values[i].toDouble())
            if (i == start) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.primary, style = Stroke(1.5f * density))
        if (cursorX >= 0f) {
            val x = l + cursorX * w
            drawLine(colors.error, Offset(x, 0f), Offset(x, h), 1.5f)
        }
    }
}

// ---------------------------------------------------------------------------
// Contrast (WCAG 2 compliance)
// ---------------------------------------------------------------------------

@Composable
fun ContrastDialog(d: AppDialog, vm: AppViewModel) {
    val sel = remember { vm.engine.snapshot.value.selection }
    var fg0 by rememberSaveable { mutableStateOf(TimeCodec.format(sel.t0)) }
    var fg1 by rememberSaveable { mutableStateOf(TimeCodec.format(sel.t1)) }
    var bg0 by rememberSaveable { mutableStateOf(TimeCodec.format(0.0)) }
    var bg1 by rememberSaveable { mutableStateOf(TimeCodec.format(0.0)) }
    var result by remember { mutableStateOf<ContrastResult?>(null) }

    fun parse(s: String) = TimeCodec.parse(s)
    val valid = listOf(fg0, fg1, bg0, bg1).all { parse(it) != null } &&
        parse(fg1)!! > parse(fg0)!! && parse(bg1)!! > parse(bg0)!!

    AppDialogFrame(
        title = stringResource(R.string.ct_title),
        onDismiss = { vm.dismiss(d) },
        buttons = {
            TextButton(onClick = { vm.dismiss(d) }) { Text(stringResource(R.string.btn_close)) }
            Button(enabled = valid, onClick = {
                vm.launchAction {
                    result = vm.engine.contrast(parse(fg0)!!, parse(fg1)!!, parse(bg0)!!, parse(bg1)!!)
                }
            }) { Text(stringResource(R.string.ct_measure)) }
        },
    ) {
        Text(stringResource(R.string.ct_intro), style = MaterialTheme.typography.bodySmall)
        RangeRow(stringResource(R.string.ct_foreground), fg0, fg1, { fg0 = it }, { fg1 = it }) {
            val s = vm.engine.snapshot.value.selection
            fg0 = TimeCodec.format(s.t0); fg1 = TimeCodec.format(s.t1)
        }
        RangeRow(stringResource(R.string.ct_background), bg0, bg1, { bg0 = it }, { bg1 = it }) {
            val s = vm.engine.snapshot.value.selection
            bg0 = TimeCodec.format(s.t0); bg1 = TimeCodec.format(s.t1)
        }
        result?.let { r ->
            Column(Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.ct_result), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.ct_fg_rms, dbText(r.foregroundDb)))
                Text(stringResource(R.string.ct_bg_rms, dbText(r.backgroundDb)))
                Text(stringResource(R.string.ct_difference, dbText(r.differenceDb)))
                Text(
                    stringResource(if (r.passes) R.string.ct_pass else R.string.ct_fail),
                    color = if (r.passes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun dbText(v: Double): String =
    if (v.isInfinite() || v.isNaN()) (if (v < 0) "-∞" else "∞") else String.format(Locale.ROOT, "%.1f", v)

@Composable
private fun RangeRow(title: String, t0: String, t1: String, on0: (String) -> Unit, on1: (String) -> Unit, useSelection: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t0, on0, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.ct_start)) },
                isError = TimeCodec.parse(t0) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            OutlinedTextField(t1, on1, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.ct_end)) },
                isError = TimeCodec.parse(t1) == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
        }
        OutlinedButton(onClick = useSelection) { Text(stringResource(R.string.ct_use_selection)) }
    }
}
