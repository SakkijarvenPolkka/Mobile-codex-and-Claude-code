/*
 * Audacity Android port — audio data and DSP helpers of the fake engine.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object Versions {
    private val next = AtomicLong(1)
    fun next(): Long = next.getAndIncrement()
}

/** Min/max/sum-of-squares per 256-sample block (like Audacity's 256 summaries). */
internal class Summary(val min: FloatArray, val max: FloatArray, val sumSq: DoubleArray)

/** Accumulator for column statistics. */
internal class Stats {
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    var sumSq = 0.0
    var count = 0L

    fun reset() { min = Float.POSITIVE_INFINITY; max = Float.NEGATIVE_INFINITY; sumSq = 0.0; count = 0 }
    fun add(v: Float) { if (v < min) min = v; if (v > max) max = v; sumSq += v.toDouble() * v; count++ }
    val rms: Float get() = if (count > 0) sqrt(sumSq / count).toFloat() else 0f
}

/**
 * An immutable clip: [length] frames of audio per channel, starting at
 * absolute time [start]. The arrays may be longer than [length] (the live
 * recording buffer); only the first [length] samples belong to the clip and
 * they are never modified once published.
 *
 * [hiddenLeft]/[hiddenRight] are trimmed audio (per channel) before the play
 * start and after the play end, like WaveClip's trimLeft/trimRight: a split
 * keeps the other part hidden and `clips.trim` can bring it back. Edits
 * that build new clips from [data] drop them.
 */
internal class FClip(
    val name: String,
    val start: Double,
    val rate: Double,
    val data: Array<FloatArray>,
    val length: Int = data[0].size,
    val version: Long = Versions.next(),
    val hiddenLeft: Array<FloatArray>? = null,
    val hiddenRight: Array<FloatArray>? = null,
) {
    val channels: Int get() = data.size
    val duration: Double get() = length / rate
    val end: Double get() = start + duration

    /** Hidden frames before the play start / after the play end. */
    val trimLeftFrames: Int get() = hiddenLeft?.get(0)?.size ?: 0
    val trimRightFrames: Int get() = hiddenRight?.get(0)?.size ?: 0
    /** Seconds, as in the snapshot's clip state. */
    val trimLeft: Double get() = trimLeftFrames / rate
    val trimRight: Double get() = trimRightFrames / rate
    /** All frames of the clip's audio, hidden ones included. */
    val totalFrames: Int get() = trimLeftFrames + length + trimRightFrames
    /** Absolute time of the first (hidden or not) frame. */
    val sequenceStart: Double get() = start - trimLeftFrames / rate

    /** The clip with [leftFrames] / [rightFrames] of its whole audio hidden
     *  (clamped so that one frame stays visible); same object when unchanged. */
    fun retrimmed(leftFrames: Int, rightFrames: Int): FClip {
        val total = totalFrames
        val l = leftFrames.coerceIn(0, total - 1)
        val r = rightFrames.coerceIn(0, total - l - 1)
        if (l == trimLeftFrames && r == trimRightFrames) return this
        val whole = Array(channels) { ch ->
            FloatArray(total).also { out ->
                hiddenLeft?.let { System.arraycopy(it[ch], 0, out, 0, trimLeftFrames) }
                System.arraycopy(data[ch], 0, out, trimLeftFrames, length)
                hiddenRight?.let { System.arraycopy(it[ch], 0, out, trimLeftFrames + length, trimRightFrames) }
            }
        }
        return FClip(name, sequenceStart + l / rate, rate,
            Array(channels) { whole[it].copyOfRange(l, total - r) },
            hiddenLeft = if (l > 0) Array(channels) { whole[it].copyOfRange(0, l) } else null,
            hiddenRight = if (r > 0) Array(channels) { whole[it].copyOfRange(total - r, total) } else null)
    }

    /** WaveTrack::SplitAt at visible frame [i] (0 < i < length): both parts
     *  keep the whole audio, the other part hidden. */
    fun splitHidden(i: Int, rightName: String): Pair<FClip, FClip> {
        val whole = retrimmed(0, 0)
        val cut = trimLeftFrames + i
        val left = whole.retrimmed(trimLeftFrames, whole.totalFrames - cut)
        val right = whole.retrimmed(cut, trimRightFrames)
        return Pair(left.withStart(start),
            FClip(rightName, timeOf(i), rate, right.data, right.length, right.version, right.hiddenLeft, right.hiddenRight))
    }

    @Volatile private var summaries: Array<Summary?> = arrayOfNulls(data.size)

    /** Sample boundary nearest to absolute time [t], clamped to [0, length]. */
    fun index(t: Double): Int {
        val i = floor((t - start) * rate + 0.5)
        return when {
            i <= 0.0 -> 0
            i >= length -> length
            else -> i.toInt()
        }
    }

    fun timeOf(i: Int): Double = start + i / rate

    fun withStart(s: Double) = FClip(name, s, rate, data, length, version, hiddenLeft, hiddenRight)
    fun renamed(n: String) = FClip(n, start, rate, data, length, version, hiddenLeft, hiddenRight)

    /** Copy of frames [i0, i1) as a new clip starting at [newStart]. */
    fun slice(i0: Int, i1: Int, newStart: Double, newName: String = name): FClip {
        val a = i0.coerceIn(0, length)
        val b = i1.coerceIn(a, length)
        return FClip(newName, newStart, rate, Array(channels) { data[it].copyOfRange(a, b) })
    }

    fun channelCopy(ch: Int, i0: Int = 0, i1: Int = length): FloatArray = data[ch].copyOfRange(i0, i1)

    fun summary(ch: Int): Summary {
        summaries[ch]?.let { return it }
        val blocks = (length + 255) / 256
        val mn = FloatArray(blocks)
        val mx = FloatArray(blocks)
        val ss = DoubleArray(blocks)
        val d = data[ch]
        for (b in 0 until blocks) {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            var s = 0.0
            val e = min(length, (b + 1) * 256)
            for (i in b * 256 until e) {
                val v = d[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
                s += v.toDouble() * v
            }
            mn[b] = lo; mx[b] = hi; ss[b] = s
        }
        val result = Summary(mn, mx, ss)
        summaries[ch] = result
        return result
    }

    /** Adds frames [from, to) of channel [ch] to [stats] (uses the block
     *  summaries for long ranges). */
    fun accumulate(ch: Int, from: Int, to: Int, stats: Stats) {
        val a = from.coerceIn(0, length)
        val b = to.coerceIn(a, length)
        val d = data[ch]
        if (b - a <= 1024) {
            for (i in a until b) stats.add(d[i])
            return
        }
        val s = summary(ch)
        var i = a
        while (i < b && (i and 255) != 0) stats.add(d[i++])
        while (i + 256 <= b) {
            val blk = i ushr 8
            if (s.min[blk] < stats.min) stats.min = s.min[blk]
            if (s.max[blk] > stats.max) stats.max = s.max[blk]
            stats.sumSq += s.sumSq[blk]
            stats.count += 256
            i += 256
        }
        while (i < b) stats.add(d[i++])
    }

    fun peak(i0: Int = 0, i1: Int = length): Float {
        val st = Stats()
        for (c in 0 until channels) accumulate(c, i0, i1, st)
        return if (st.count == 0L) 0f else max(abs(st.min), abs(st.max))
    }

    companion object {
        fun of(name: String, start: Double, rate: Double, data: Array<FloatArray>) = FClip(name, start, rate, data)
        fun silent(name: String, start: Double, rate: Double, channels: Int, frames: Int) =
            FClip(name, start, rate, Array(channels) { FloatArray(frames) })
    }
}

/** Deterministic noise and fast oscillators. */
internal object Osc {
    private const val TABLE = 4096
    private val sine = FloatArray(TABLE + 1) { sin(2.0 * PI * it / TABLE).toFloat() }

    /** sin(2π·phase) for phase in cycles (any real). */
    fun sin01(phase: Double): Float {
        val p = phase - floor(phase)
        val x = p * TABLE
        val i = x.toInt()
        val f = (x - i).toFloat()
        return sine[i] + (sine[i + 1] - sine[i]) * f
    }

    /** Integer hash → [-1, 1). Same input, same output, on every platform. */
    fun hash(seed: Long, n: Long): Float {
        var z = seed * -0x61c8864680b583ebL + n * 0x2545F4914F6CDD1DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 40).toFloat() / (1L shl 24).toFloat()) * 2f - 1f
    }
}

/** Small DSP toolbox (no allocations in inner loops). */
internal object Dsp {
    fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)
    fun linToDb(v: Double): Double = if (v <= 0.0) -Double.MAX_VALUE else 20.0 * log10(v)

    fun peak(a: FloatArray, n: Int = a.size): Float {
        var p = 0f
        for (i in 0 until n) { val v = abs(a[i]); if (v > p) p = v }
        return p
    }

    fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from))
    }

    /** Linear-interpolation resampling of [src] to [outLength] frames. */
    fun stretch(src: FloatArray, outLength: Int): FloatArray {
        val out = FloatArray(max(0, outLength))
        if (src.isEmpty() || outLength <= 0) return out
        if (outLength == 1) { out[0] = src[0]; return out }
        val ratio = (src.size - 1).toDouble() / (outLength - 1)
        for (i in 0 until outLength) {
            val pos = i * ratio
            val j = pos.toInt()
            val f = (pos - j).toFloat()
            out[i] = if (j + 1 < src.size) src[j] + (src[j + 1] - src[j]) * f else src[src.size - 1]
        }
        return out
    }

    /** RBJ cookbook biquad, processed in place (Direct Form I). */
    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun process(x: FloatArray, from: Int = 0, to: Int = x.size) {
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (i in from until to) {
                val x0 = x[i].toDouble()
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
                x[i] = y0.toFloat()
            }
        }

        companion object {
            private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

            private fun w0(f: Double, rate: Double) = 2.0 * PI * (f.coerceIn(1.0, rate * 0.49)) / rate

            fun lowPass(f: Double, q: Double, rate: Double): Biquad {
                val w = w0(f, rate); val alpha = sin(w) / (2 * q); val c = cos(w)
                return norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun highPass(f: Double, q: Double, rate: Double): Biquad {
                val w = w0(f, rate); val alpha = sin(w) / (2 * q); val c = cos(w)
                return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun notch(f: Double, q: Double, rate: Double): Biquad {
                val w = w0(f, rate); val alpha = sin(w) / (2 * q); val c = cos(w)
                return norm(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun peaking(f: Double, q: Double, db: Double, rate: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = w0(f, rate); val alpha = sin(w) / (2 * q); val c = cos(w)
                return norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }

            fun lowShelf(f: Double, db: Double, rate: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = w0(f, rate); val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
                return norm(a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                    (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa)
            }

            fun highShelf(f: Double, db: Double, rate: Double): Biquad {
                val a = 10.0.pow(db / 40); val w = w0(f, rate); val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0); val sa = 2 * sqrt(a) * alpha
                return norm(a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                    (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa)
            }
        }
    }

    /** In-place radix-2 complex FFT; n = re.size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Window functions named like `analyze.spectrum`'s `window` argument. */
    fun window(name: String, n: Int): DoubleArray {
        val w = DoubleArray(n)
        val m = (n - 1).toDouble().coerceAtLeast(1.0)
        for (i in 0 until n) {
            val x = i / m
            w[i] = when (name) {
                "rectangular" -> 1.0
                "bartlett" -> 1.0 - abs(2.0 * x - 1.0)
                "hamming" -> 0.54 - 0.46 * cos(2 * PI * x)
                "hann" -> 0.5 - 0.5 * cos(2 * PI * x)
                "blackman" -> 0.42 - 0.5 * cos(2 * PI * x) + 0.08 * cos(4 * PI * x)
                "blackmanHarris" -> 0.35875 - 0.48829 * cos(2 * PI * x) + 0.14128 * cos(4 * PI * x) - 0.01168 * cos(6 * PI * x)
                "welch" -> 1.0 - (2.0 * x - 1.0).pow(2)
                "gaussian25", "gaussian35", "gaussian45" -> {
                    val a = when (name) { "gaussian25" -> 2.5; "gaussian35" -> 3.5; else -> 4.5 }
                    exp(-0.5 * (a * (2.0 * x - 1.0)).pow(2))
                }
                else -> 0.5 - 0.5 * cos(2 * PI * x)
            }
        }
        return w
    }

    val WINDOWS = listOf("rectangular", "bartlett", "hamming", "hann", "blackman", "blackmanHarris", "welch",
        "gaussian25", "gaussian35", "gaussian45")

    fun isPowerOfTwo(n: Int) = n > 0 && (n and (n - 1)) == 0

    fun log2(x: Double) = ln(x) / ln(2.0)
}

/** Minimal RIFF/WAVE reader and writer (PCM 8/16/24/32-bit and IEEE float). */
internal object Wav {
    class Decoded(val rate: Int, val channels: Array<FloatArray>)

    /** Writes [frames] frames of [data] (one array per channel). [encoding]:
     *  8 (unsigned), 16, 24, 32 (signed PCM) or -32/-64 (float). */
    @Throws(IOException::class)
    fun write(file: File, data: Array<FloatArray>, frames: Int, rate: Int, encoding: Int = 16) {
        val ch = data.size
        val isFloat = encoding < 0
        val bits = abs(encoding)
        val bytesPerSample = bits / 8
        val dataSize = frames.toLong() * ch * bytesPerSample
        require(dataSize < Int.MAX_VALUE - 64) { "WAV too large" }
        val buf = ByteBuffer.allocate(44 + dataSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize.toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buf.putShort((if (isFloat) 3 else 1).toShort()).putShort(ch.toShort()).putInt(rate)
        buf.putInt(rate * ch * bytesPerSample).putShort((ch * bytesPerSample).toShort()).putShort(bits.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize.toInt())
        for (i in 0 until frames) for (c in 0 until ch) {
            val v = data[c][i].coerceIn(-1f, 1f)
            when {
                isFloat && bits == 64 -> buf.putDouble(data[c][i].toDouble())
                isFloat -> buf.putFloat(data[c][i])
                bits == 8 -> buf.put(((v * 127.5f) + 128f).toInt().coerceIn(0, 255).toByte())
                bits == 16 -> buf.putShort((v * 32767f).toInt().toShort())
                bits == 24 -> {
                    val s = (v * 8388607f).toInt()
                    buf.put((s and 0xff).toByte()).put(((s shr 8) and 0xff).toByte()).put(((s shr 16) and 0xff).toByte())
                }
                else -> buf.putInt((v.toDouble() * Int.MAX_VALUE).toInt())
            }
        }
        file.parentFile?.mkdirs()
        file.writeBytes(buf.array())
    }

    /** Reads a PCM/float WAV file; null when it is not one. */
    fun read(file: File): Decoded? = try {
        DataInputStream(file.inputStream().buffered()).use { readFrom(it.readBytes()) }
    } catch (e: IOException) {
        null
    }

    fun readFrom(bytes: ByteArray): Decoded? {
        if (bytes.size < 12) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (size < 0) return null
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt() and 0xffff
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                    if (format == 0xFFFE && size >= 26) format = b.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    if (channels <= 0 || rate <= 0 || bits <= 0) return null
                    val bps = bits / 8
                    val avail = min(size, bytes.size - body)
                    val frames = avail / (bps * channels)
                    val out = Array(channels) { FloatArray(frames) }
                    var p = body
                    for (i in 0 until frames) for (c in 0 until channels) {
                        out[c][i] = when {
                            format == 3 && bits == 32 -> b.getFloat(p)
                            format == 3 && bits == 64 -> b.getDouble(p).toFloat()
                            format != 1 -> return null
                            bits == 8 -> ((bytes[p].toInt() and 0xff) - 128) / 128f
                            bits == 16 -> b.getShort(p) / 32768f
                            bits == 24 -> (((bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or
                                (bytes[p + 2].toInt() shl 16))) / 8388608f
                            bits == 32 -> (b.getInt(p) / 2147483648.0).toFloat()
                            else -> return null
                        }
                        p += bps
                    }
                    return Decoded(rate, out)
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }
}
