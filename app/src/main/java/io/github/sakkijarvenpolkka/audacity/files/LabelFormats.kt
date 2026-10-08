/*
 * Audacity Android port — label file formats of File ▸ Export Other ▸
 * Export Labels... (the file types of LabelMenus.cpp OnExportLabels /
 * LabelTrack.cpp of Audacity 3.7.9, the Audacity Team, GPL-2.0-or-later).
 * Reading and writing is done by the engine (labels.import / labels.export,
 * API.md §3.3).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.files

import androidx.annotation.StringRes
import io.github.sakkijarvenpolkka.audacity.R

/** [key] is the `format` argument of `labels.export`. */
enum class LabelFormat(val key: String, val extension: String, val mime: String, @param:StringRes val label: Int) {
    TEXT("text", "txt", "text/plain", R.string.lf_text),
    SUBRIP("subrip", "srt", "application/x-subrip", R.string.lf_subrip),
    WEBVTT("webvtt", "vtt", "text/vtt", R.string.lf_webvtt),
    PODCAST_CHAPTERS("podcastChapters", "json", "application/json", R.string.lf_podcast_chapters);

    /** Default file name (desktop: "labels.txt"). */
    val fileName: String get() = "labels.$extension"

    companion object {
        fun ofKey(key: String): LabelFormat = entries.firstOrNull { it.key == key } ?: TEXT

        /** MIME types offered by the Import ▸ Labels picker (text and SubRip files). */
        val IMPORT_MIME_TYPES = listOf("text/plain", "application/x-subrip", "text/*", "application/octet-stream")
    }
}
