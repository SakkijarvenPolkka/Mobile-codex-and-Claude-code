/*
 * Audacity Android port — immutable project model of the fake engine and
 * the editing operations on it (simplified ports of the WaveTrack /
 * LabelTrack operations behind Audacity's Edit menu).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import io.github.sakkijarvenpolkka.audacity.engine.model.Tag
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FLabel(val t0: Double, val t1: Double, val title: String)

internal data class FTrack(
    val id: Long,
    val kind: String,
    val name: String,
    val selected: Boolean = false,
    val channels: Int = 1,
    val rate: Double = 44100.0,
    val format: String = "float",
    val gain: Double = 1.0,
    val pan: Double = 0.0,
    val mute: Boolean = false,
    val solo: Boolean = false,
    /** Sorted by start time, never overlapping. */
    val clips: List<FClip> = emptyList(),
    /** Sorted by (t0, t1). */
    val labels: List<FLabel> = emptyList(),
) {
    val isWave: Boolean get() = kind == WAVE
    val isLabel: Boolean get() = kind == LABEL

    val start: Double get() = if (isWave) clips.firstOrNull()?.start ?: 0.0 else labels.minOfOrNull { it.t0 } ?: 0.0
    val end: Double get() = if (isWave) clips.maxOfOrNull { it.end } ?: 0.0 else labels.maxOfOrNull { it.t1 } ?: 0.0
    val isEmpty: Boolean get() = if (isWave) clips.isEmpty() else labels.isEmpty()

    /** In [0, 2^62): changes whenever samples or clip geometry change. */
    val waveVersion: Long
        get() {
            var h = 1125899906842597L
            h = 31 * h + rate.toBits()
            for (c in clips) {
                h = 31 * h + c.version
                h = 31 * h + c.start.toBits()
                h = 31 * h + c.length
            }
            return h and ((1L shl 62) - 1)
        }

    fun withClips(list: List<FClip>) = copy(clips = list.filter { it.length > 0 }.sortedBy { it.start })
    fun withLabels(list: List<FLabel>) = copy(labels = list.sortedWith(compareBy({ it.t0 }, { it.t1 })))

    /** Name for a new clip: "<track> #n" with the smallest unused n. */
    fun newClipName(extra: Collection<String> = emptyList()): String {
        val used = clips.map { it.name }.toHashSet() + extra
        var n = 1
        while ("$name #$n" in used) n++
        return "$name #$n"
    }

    fun clipsIntersecting(t0: Double, t1: Double): List<FClip> = clips.filter { it.start < t1 && it.end > t0 }

    companion object {
        const val WAVE = "wave"
        const val LABEL = "label"
    }
}

internal data class FModel(
    val tracks: List<FTrack> = emptyList(),
    val selection: TimeRange = TimeRange(),
    val focusedId: Long? = null,
    val tags: List<Tag> = emptyList(),
) {
    val end: Double get() = tracks.maxOfOrNull { it.end } ?: 0.0
    val start: Double get() = tracks.filter { !it.isEmpty }.minOfOrNull { it.start } ?: 0.0
    val selectedTracks: List<FTrack> get() = tracks.filter { it.selected }
    fun track(id: Long): FTrack? = tracks.firstOrNull { it.id == id }
    fun replace(track: FTrack): FModel = copy(tracks = tracks.map { if (it.id == track.id) track else it })
    fun mapTracks(f: (FTrack) -> FTrack): FModel = copy(tracks = tracks.map(f))
}

/** One track of the clipboard: clips/labels relative to the copied t0. */
internal class ClipboardTrack(
    val kind: String,
    val name: String,
    val channels: Int,
    val rate: Double,
    val clips: List<FClip>,
    val labels: List<FLabel>,
)

internal class FakeClipboard(val tracks: List<ClipboardTrack>, val t0: Double, val t1: Double) {
    val duration: Double get() = t1 - t0
}

// ---------------------------------------------------------------------------
// Editing operations (pure functions)
// ---------------------------------------------------------------------------

internal object Edits {
    private const val EPS = 1e-9

    /** Removes [t0, t1). With [shift] (Delete/Cut) later audio moves left
     *  and a clip containing the whole range stays one clip; without it
     *  (Split Delete/Split Cut) a gap remains. */
    fun clear(track: FTrack, t0: Double, t1: Double, shift: Boolean): FTrack {
        if (t1 <= t0) return track
        val len = t1 - t0
        if (track.isLabel) {
            val out = ArrayList<FLabel>()
            for (l in track.labels) {
                when {
                    l.t1 <= t0 -> out += l
                    l.t0 >= t1 -> out += if (shift) l.copy(t0 = l.t0 - len, t1 = l.t1 - len) else l
                    l.t0 >= t0 && l.t1 <= t1 -> {} // inside: removed
                    l.t0 < t0 && l.t1 > t1 -> out += if (shift) l.copy(t1 = l.t1 - len) else l
                    l.t0 < t0 -> out += l.copy(t1 = t0)
                    else -> out += if (shift) l.copy(t0 = t0, t1 = l.t1 - len) else l.copy(t0 = t1)
                }
            }
            return track.withLabels(out)
        }
        val out = ArrayList<FClip>()
        for (c in track.clips) {
            when {
                c.end <= t0 + EPS -> out += c
                c.start >= t1 - EPS -> out += if (shift) c.withStart(c.start - len) else c
                else -> {
                    val i0 = c.index(t0)
                    val i1 = c.index(t1)
                    val hasLeft = i0 > 0
                    val hasRight = i1 < c.length
                    if (shift && hasLeft && hasRight) {
                        val data = Array(c.channels) { ch ->
                            val a = FloatArray(i0 + (c.length - i1))
                            System.arraycopy(c.data[ch], 0, a, 0, i0)
                            System.arraycopy(c.data[ch], i1, a, i0, c.length - i1)
                            a
                        }
                        out += FClip(c.name, c.start, c.rate, data)
                    } else {
                        if (hasLeft) out += c.slice(0, i0, c.start)
                        if (hasRight) {
                            val rightStart = c.timeOf(i1) - if (shift) len else 0.0
                            out += c.slice(i1, c.length, rightStart, if (hasLeft) track.newClipName(listOf(c.name)) else c.name)
                        }
                    }
                }
            }
        }
        return track.withClips(out)
    }

    fun copy(track: FTrack, t0: Double, t1: Double): ClipboardTrack {
        if (track.isLabel) {
            val labels = track.labels.filter { it.t1 >= t0 && it.t0 <= t1 && !(it.t1 == t0 && it.t0 < t0) }
                .map { FLabel(max(it.t0, t0) - t0, min(it.t1, t1) - t0, it.title) }
            return ClipboardTrack(FTrack.LABEL, track.name, 1, track.rate, emptyList(), labels)
        }
        val clips = track.clipsIntersecting(t0, t1).mapNotNull { c ->
            val i0 = c.index(t0)
            val i1 = c.index(t1)
            if (i1 <= i0) null else c.slice(i0, i1, c.timeOf(i0) - t0)
        }
        return ClipboardTrack(FTrack.WAVE, track.name, track.channels, track.rate, clips, emptyList())
    }

    /** Converts clipboard audio to the track's channel count and rate. */
    private fun adapt(clip: FClip, channels: Int, rate: Double): Array<FloatArray> {
        var data: Array<FloatArray> = when {
            clip.channels == channels -> Array(channels) { clip.channelCopy(it) }
            channels == 1 -> {
                val m = FloatArray(clip.length)
                for (i in 0 until clip.length) {
                    var s = 0f
                    for (c in 0 until clip.channels) s += clip.data[c][i]
                    m[i] = s / clip.channels
                }
                arrayOf(m)
            }
            else -> Array(channels) { clip.channelCopy(min(it, clip.channels - 1)) }
        }
        if (abs(clip.rate - rate) > 1e-6) {
            val frames = (clip.length * rate / clip.rate).roundToInt()
            data = Array(data.size) { Dsp.stretch(data[it], frames) }
        }
        return data
    }

    /** Inserts [cb] at [t], moving later audio right by [duration]
     *  (Paste with "editing a clip can move other clips"). */
    fun paste(track: FTrack, t: Double, cb: ClipboardTrack, duration: Double): FTrack {
        if (track.isLabel) {
            val shifted = track.labels.map { l ->
                when {
                    l.t0 >= t -> l.copy(t0 = l.t0 + duration, t1 = l.t1 + duration)
                    l.t1 > t -> l.copy(t1 = l.t1 + duration)
                    else -> l
                }
            }
            return track.withLabels(shifted + cb.labels.map { it.copy(t0 = it.t0 + t, t1 = it.t1 + t) })
        }
        val pasted = cb.clips.map { c ->
            FClip(track.newClipName(), t + c.start, track.rate, adapt(c, track.channels, track.rate))
        }
        val out = ArrayList<FClip>()
        var host: FClip? = null
        var hostSplit = 0
        for (c in track.clips) {
            when {
                c.end <= t + EPS -> out += c
                c.start >= t - EPS -> out += c.withStart(c.start + duration)
                else -> { host = c; hostSplit = c.index(t) }
            }
        }
        val h = host
        if (h != null) {
            val single = pasted.singleOrNull()
            if (single != null && abs(single.start - t) < 0.5 / track.rate &&
                abs(single.length / track.rate - duration) < 1.5 / track.rate
            ) {
                // Pasting one clip into a clip: the result is one clip
                val data = Array(h.channels) { ch ->
                    val a = FloatArray(h.length + single.length)
                    System.arraycopy(h.data[ch], 0, a, 0, hostSplit)
                    System.arraycopy(single.data[ch], 0, a, hostSplit, single.length)
                    System.arraycopy(h.data[ch], hostSplit, a, hostSplit + single.length, h.length - hostSplit)
                    a
                }
                out += FClip(h.name, h.start, h.rate, data)
                return track.withClips(out)
            }
            out += h.slice(0, hostSplit, h.start)
            out += h.slice(hostSplit, h.length, h.timeOf(hostSplit) + duration, track.newClipName(listOf(h.name)))
        }
        out += pasted
        return track.withClips(out)
    }

    fun silence(track: FTrack, t0: Double, t1: Double): FTrack {
        if (!track.isWave) return track
        return track.withClips(track.clips.map { c ->
            if (c.end <= t0 || c.start >= t1) c else {
                val i0 = c.index(t0); val i1 = c.index(t1)
                FClip(c.name, c.start, c.rate, Array(c.channels) { ch -> c.channelCopy(ch).also { it.fill(0f, i0, i1) } })
            }
        })
    }

    fun trim(track: FTrack, t0: Double, t1: Double): FTrack {
        if (track.isLabel) return track.withLabels(track.labels.filter { it.t1 >= t0 && it.t0 <= t1 })
        return track.withClips(track.clipsIntersecting(t0, t1).map { c ->
            val i0 = c.index(t0); val i1 = c.index(t1)
            if (i0 == 0 && i1 == c.length) c else c.slice(i0, i1, c.timeOf(i0))
        })
    }

    fun splitAt(track: FTrack, t: Double): FTrack {
        if (!track.isWave) return track
        val out = ArrayList<FClip>()
        for (c in track.clips) {
            val i = c.index(t)
            if (c.start < t - EPS && c.end > t + EPS && i > 0 && i < c.length) {
                out += c.slice(0, i, c.start)
                out += c.slice(i, c.length, c.timeOf(i), track.newClipName(out.map { it.name }))
            } else out += c
        }
        return track.withClips(out)
    }

    fun join(track: FTrack, t0: Double, t1: Double): FTrack {
        if (!track.isWave) return track
        val group = track.clipsIntersecting(t0, t1)
        if (group.size < 2) return track
        val first = group.first()
        val endTime = group.maxOf { it.end }
        val frames = ((endTime - first.start) * track.rate).roundToInt()
        val data = Array(track.channels) { FloatArray(frames) }
        for (c in group) {
            val off = ((c.start - first.start) * track.rate).roundToInt()
            for (ch in 0 until track.channels) {
                val n = min(c.length, frames - off)
                if (n > 0) System.arraycopy(c.data[ch], 0, data[ch], off, n)
            }
        }
        val rest = track.clips.filter { it !in group }
        return track.withClips(rest + FClip(first.name, first.start, track.rate, data))
    }

    /** Splits clips at runs of silence (|x| < -60 dB) longer than 10 ms within [t0, t1). */
    fun detachAtSilences(track: FTrack, t0: Double, t1: Double): FTrack {
        if (!track.isWave) return track
        val threshold = 0.001f
        val out = ArrayList<FClip>()
        val names = ArrayList<String>()
        for (c in track.clips) {
            if (c.end <= t0 || c.start >= t1) { out += c; names += c.name; continue }
            val i0 = c.index(t0); val i1 = c.index(t1)
            val minRun = (0.01 * c.rate).toInt().coerceAtLeast(1)
            val keep = ArrayList<IntRange>()
            var segStart = 0
            var i = i0
            while (i < i1) {
                var silent = true
                for (ch in 0 until c.channels) if (abs(c.data[ch][i]) >= threshold) { silent = false; break }
                if (silent) {
                    var j = i
                    while (j < i1 && (0 until c.channels).all { abs(c.data[it][j]) < threshold }) j++
                    if (j - i >= minRun) {
                        if (i > segStart) keep += segStart until i
                        segStart = j
                    }
                    i = j
                } else i++
            }
            if (segStart < c.length) keep += segStart until c.length
            if (keep.size == 1 && keep[0].first == 0 && keep[0].last == c.length - 1) { out += c; names += c.name; continue }
            keep.forEachIndexed { k, r ->
                val name = if (k == 0) c.name else track.newClipName(names)
                names += name
                out += c.slice(r.first, r.last + 1, c.timeOf(r.first), name)
            }
        }
        return track.withClips(out)
    }

    /**
     * Replaces the audio of [t0, t1) in every clip of the track with
     * `fn(segment, rate)`. The result may have another length: later clips
     * of the track move by the difference. Returns the new track and the
     * largest length change in seconds.
     */
    fun process(track: FTrack, t0: Double, t1: Double, fn: (Array<FloatArray>, Double) -> Array<FloatArray>): Pair<FTrack, Double> {
        if (!track.isWave) return track to 0.0
        var clips = track.clips.toMutableList()
        var maxDelta = 0.0
        for (k in clips.indices.reversed()) {
            val c = clips[k]
            if (c.end <= t0 || c.start >= t1) continue
            val i0 = c.index(t0); val i1 = c.index(t1)
            if (i1 <= i0) continue
            val seg = Array(c.channels) { c.channelCopy(it, i0, i1) }
            val res = fn(seg, c.rate)
            val newLen = res[0].size
            val data = Array(c.channels) { ch ->
                val a = FloatArray(i0 + newLen + (c.length - i1))
                System.arraycopy(c.data[ch], 0, a, 0, i0)
                System.arraycopy(res[min(ch, res.size - 1)], 0, a, i0, newLen)
                System.arraycopy(c.data[ch], i1, a, i0 + newLen, c.length - i1)
                a
            }
            clips[k] = FClip(c.name, c.start, c.rate, data)
            val delta = (newLen - (i1 - i0)) / c.rate
            if (delta != 0.0) {
                if (abs(delta) > abs(maxDelta)) maxDelta = delta
                clips = clips.mapIndexed { j, o -> if (j > k) o.withStart(o.start + delta) else o }.toMutableList()
            }
        }
        return track.withClips(clips) to maxDelta
    }

    /** Generated audio replacing [t0, t1) (or inserted at t0 when t0 == t1). */
    fun insertGenerated(track: FTrack, t0: Double, t1: Double, data: Array<FloatArray>): FTrack {
        val cleared = if (t1 > t0) clear(track, t0, t1, shift = true) else track
        val clip = FClip("", 0.0, track.rate, data)
        val duration = clip.length / track.rate
        return paste(cleared, t0, ClipboardTrack(FTrack.WAVE, track.name, data.size, track.rate, listOf(clip), emptyList()), duration)
    }
}

// ---------------------------------------------------------------------------
// Rendering and mixing
// ---------------------------------------------------------------------------

internal object Mixer {
    /** Wave tracks that are heard: the soloed ones if any track is soloed,
     *  else the unmuted ones (ExportUtils::FindExportWaveTracks). */
    fun audible(tracks: List<FTrack>, selectedOnly: Boolean = false): List<FTrack> {
        val waves = tracks.filter { it.isWave && (!selectedOnly || it.selected) }
        val anySolo = tracks.any { it.isWave && it.solo }
        return waves.filter { if (anySolo) it.solo else !it.mute }
    }

    /** Track audio from [t0], [frames] frames at [rate] (linear
     *  interpolation), zeros in gaps; one array per track channel. */
    fun render(track: FTrack, t0: Double, rate: Double, frames: Int): Array<FloatArray> {
        val out = Array(track.channels) { FloatArray(frames) }
        for (c in track.clips) {
            if (c.end <= t0 || c.start >= t0 + frames / rate) continue
            val fStart = max(0.0, ceil((c.start - t0) * rate)).toInt()
            val fEnd = min(frames.toDouble(), ceil((c.end - t0) * rate)).toInt()
            val step = c.rate / rate
            for (f in fStart until fEnd) {
                val pos = (t0 + f / rate - c.start) * c.rate
                val i = floor(pos).toInt()
                if (i < 0 || i >= c.length) continue
                val fr = (pos - i).toFloat()
                for (ch in 0 until track.channels) {
                    val d = c.data[min(ch, c.channels - 1)]
                    out[ch][f] = if (step == 1.0 || i + 1 >= c.length) d[i] else d[i] + (d[i + 1] - d[i]) * fr
                }
            }
        }
        return out
    }

    /** Pan law of Audacity's mixer: the far channel is attenuated linearly. */
    fun panGains(pan: Double): Pair<Float, Float> {
        val p = pan.coerceIn(-1.0, 1.0)
        return (if (p > 0) 1.0 - p else 1.0).toFloat() to (if (p < 0) 1.0 + p else 1.0).toFloat()
    }

    /** Mix of [tracks] (gain and pan applied) into [outChannels] channels. */
    fun mix(tracks: List<FTrack>, t0: Double, rate: Double, frames: Int, outChannels: Int): Array<FloatArray> {
        val out = Array(outChannels) { FloatArray(frames) }
        for (t in tracks) {
            val src = render(t, t0, rate, frames)
            val g = t.gain.toFloat()
            val (lg, rg) = panGains(t.pan)
            val l = src[0]
            val r = src[min(1, src.size - 1)]
            if (outChannels == 1) {
                val m = out[0]
                for (i in 0 until frames) m[i] += g * (l[i] * lg + r[i] * rg) / 2f
            } else {
                val oL = out[0]; val oR = out[1]
                for (i in 0 until frames) {
                    oL[i] += g * l[i] * lg
                    oR[i] += g * r[i] * rg
                }
            }
        }
        return out
    }
}
