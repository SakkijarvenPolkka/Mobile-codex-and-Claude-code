/*
 * Audacity Android port — label text files (File ▸ Export Other ▸ Export
 * Labels..., File ▸ Import ▸ Labels...).
 *
 * Format of LabelTrack::Export / Import (lib-label-track/LabelTrack.cpp,
 * Audacity 3.7.9, the Audacity Team, GPL-2.0-or-later): one label per line,
 * `t0<TAB>t1<TAB>title` with times printed with 6 decimals; lines that start
 * with a backslash carry spectral selection data and are skipped. Old files
 * with only `t0<TAB>title` are accepted.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.files

import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import java.util.Locale

data class LabelLine(val t0: Double, val t1: Double, val title: String)

object LabelFiles {

    /** All labels of all label tracks, in track order (LabelMenus.cpp OnExportLabels). */
    fun export(snapshot: Snapshot): String = buildString {
        for (track in snapshot.tracks) {
            if (!track.isLabel) continue
            for (l in track.labels) {
                append(String.format(Locale.ROOT, "%.6f\t%.6f\t%s\n", l.t0, l.t1, l.title))
            }
        }
    }

    fun parse(text: String): List<LabelLine> {
        val out = ArrayList<LabelLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("\\")) continue
            val parts = line.split('\t')
            val t0 = parts.getOrNull(0)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val t1 = parts.getOrNull(1)?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val title = when {
                t1 != null -> parts.drop(2).joinToString("\t")
                else -> parts.drop(1).joinToString("\t")
            }
            val end = t1 ?: t0
            out += LabelLine(minOf(t0, end), maxOf(t0, end), title)
        }
        return out
    }
}
