/*
 * Audacity Android port — label files of the fake engine (labels.import /
 * labels.export, API.md §3.3).
 *
 * A Kotlin rendering of LabelStruct::Import / LabelStruct::Export and
 * LabelTrack::Import / Export of lib-label-track/LabelTrack.cpp (Audacity
 * 3.7.9, the Audacity Team, GPL-2.0-or-later): text files (`t0<TAB>t1<TAB>
 * title`, spectral continuation lines start with a backslash), SubRip and
 * WebVTT cues and Podcast 2.0 chapters JSON.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.engine.fake

import java.util.Locale
import kotlin.math.roundToLong

internal object FakeLabels {
    /** Result of [parse]: the labels read and whether lines had to be skipped. */
    class Parsed(val labels: List<FLabel>, val skipped: Boolean)

    /** LabelTrack::FormatForFileName: by extension. */
    fun formatOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "srt" -> "subrip"
        "vtt" -> "webvtt"
        "json" -> "podcastChapters"
        else -> "text"
    }

    fun parse(text: String, format: String): Parsed {
        val lines = text.removePrefix("﻿").lines().map { it.trimEnd('\r') }
        return if (format == "subrip") parseSubRip(lines) else parseText(lines)
    }

    private fun parseText(lines: List<String>): Parsed {
        val out = ArrayList<FLabel>()
        var skipped = false
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("\\")) continue
            val parts = line.split('\t')
            val t0 = parts[0].trim().replace(',', '.').toDoubleOrNull()
            if (t0 == null) { skipped = true; continue }
            // Old files: t0<TAB>title
            val t1 = parts.getOrNull(1)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val title = if (t1 != null) parts.drop(2).joinToString("\t") else parts.drop(1).joinToString("\t")
            val end = t1 ?: t0
            out += FLabel(minOf(t0, end), maxOf(t0, end), title)
        }
        return Parsed(out, skipped)
    }

    private fun subRipTime(ts: String): Double? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{3})""").matchEntire(ts.trim()) ?: return null
        val (h, min, s, ms) = m.destructured
        return h.toInt() * 3600 + min.toInt() * 60 + s.toInt() + ms.toInt() / 1000.0
    }

    private fun parseSubRip(lines: List<String>): Parsed {
        val out = ArrayList<FLabel>()
        var skipped = false
        var i = 0
        while (i < lines.size) {
            if (lines[i].isBlank()) { i++; continue }
            // identifier line, then the timestamps
            val stamp = lines.getOrNull(i + 1) ?: run { skipped = true; break }
            val arrow = stamp.split("-->")
            val t0 = arrow.getOrNull(0)?.let { subRipTime(it) }
            val t1 = arrow.getOrNull(1)?.let { subRipTime(it) }
            i += 2
            val title = StringBuilder(lines.getOrNull(i) ?: "")
            i++
            while (i < lines.size && lines[i].isNotEmpty()) {
                title.append(' ').append(lines[i])
                i++
            }
            if (t0 == null || t1 == null) { skipped = true; continue }
            out += FLabel(minOf(t0, t1), maxOf(t0, t1), title.toString())
        }
        return Parsed(out, skipped)
    }

    private fun subRipStamp(t: Double, webVtt: Boolean): String {
        val ms = (t * 1000).roundToLong().coerceAtLeast(0)
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", h, m, s, if (webVtt) "." else ",", ms % 1000)
    }

    private fun short(v: Double, digits: Int): String {
        // Internat::ToString: fixed digits, trailing zeros removed
        var text = String.format(Locale.ROOT, "%.${digits}f", v)
        if ('.' in text) text = text.trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    private fun json(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> append(c)
        }
    }

    /** All label tracks in track order, like LabelMenus.cpp OnExportLabels. */
    fun export(tracks: List<List<FLabel>>, format: String): String {
        val all = tracks.flatten()
        val sb = StringBuilder()
        when (format) {
            "webvtt" -> {
                sb.append("WEBVTT\n\n")
                tracks.forEach { t -> t.forEachIndexed { i, l -> cue(sb, i, l, true) } }
            }
            "subrip" -> tracks.forEach { t -> t.forEachIndexed { i, l -> cue(sb, i, l, false) } }
            "podcastChapters" -> {
                sb.append("{\n  \"version\": \"1.2.0\",\n  \"chapters\": [\n")
                all.forEachIndexed { i, l ->
                    sb.append("    {\"startTime\": ").append(short(l.t0, 3)).append(", \"title\": \"").append(json(l.title))
                        .append("\"}").append(if (i == all.lastIndex) "" else ",").append('\n')
                }
                sb.append("  ]\n}\n")
            }
            else -> all.forEach { l -> sb.append(short(l.t0, 6)).append('\t').append(short(l.t1, 6)).append('\t').append(l.title).append('\n') }
        }
        return sb.toString()
    }

    private fun cue(sb: StringBuilder, index: Int, l: FLabel, webVtt: Boolean) {
        sb.append(index + 1).append('\n')
        sb.append(subRipStamp(l.t0, webVtt)).append(" --> ").append(subRipStamp(l.t1, webVtt)).append('\n')
        sb.append(l.title).append("\n\n")
    }
}
