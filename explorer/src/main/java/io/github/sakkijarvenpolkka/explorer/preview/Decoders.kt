/*
 * Files — the decoders behind the viewers, as interfaces UI tests replace with fakes: the
 * text loader.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.SniffResult
import io.github.sakkijarvenpolkka.explorer.fs.TEXT_PREVIEW_LIMIT
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.fs.TextSniffer
import io.github.sakkijarvenpolkka.explorer.fs.splitDisplayLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** A file as [TextViewer] shows it. */
sealed interface TextDocument {
    /**
     * The first [TEXT_PREVIEW_LIMIT] bytes decoded with [encoding] and split into [lines];
     * [truncated] when the file is longer.
     */
    data class Lines(val lines: List<DisplayLine>, val encoding: TextEncoding, val truncated: Boolean) : TextDocument

    /** Not text ([SniffResult.Binary]); a forced encoding shows it anyway. */
    data object Binary : TextDocument
}

/** Reads text files for [TextViewer]. */
interface TextLoader {
    /**
     * [file] decoded with [forced], or with the encoding [TextSniffer] detects when it is null.
     * A file that cannot be read is an [java.io.IOException].
     */
    suspend fun load(file: Path, forced: TextEncoding?): TextDocument
}

/** [TextLoader] on [FsBackend.openRead]: reads the head of the file, decodes it and splits it into rows on [io]. */
class FsTextLoader(private val fs: FsBackend, private val io: CoroutineDispatcher) : TextLoader {

    override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = withContext(io) {
        val (head, truncated) = fs.openRead(file).use { TextSniffer.readHead(it) }
        when (val result = TextSniffer.sniff(head, truncated, forced)) {
            is SniffResult.Text -> TextDocument.Lines(splitDisplayLines(result.text), result.encoding, result.truncated)
            SniffResult.Binary -> TextDocument.Binary
        }
    }
}
