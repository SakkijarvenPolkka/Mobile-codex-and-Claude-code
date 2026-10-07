/*
 * Audacity Android port — Storage Access Framework helpers.
 *
 * Strategy (import-export-project.md §5): SAF Uris are always copied into
 * app storage (`cacheDir/import/<uuid>/<display name>` for audio,
 * `filesDir/Projects/<name>.aup3` for projects) because importers need a
 * real, seekable file with its extension, and SQLite needs a directory.
 * Exports are staged in `cacheDir/export/<uuid>/` and copied to the Uri
 * picked with ACTION_CREATE_DOCUMENT (opened with mode "wt").
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

object SafFiles {

    /** Display name of a content Uri (or the last path segment). */
    fun displayName(cr: ContentResolver, uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Size in bytes, or -1 when unknown. */
    fun size(cr: ContentResolver, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).length() } ?: -1
        return runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
            -1L
        }.getOrDefault(-1L)
    }

    private val MIME_TO_EXT = mapOf(
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav", "audio/vnd.wave" to "wav",
        "audio/flac" to "flac", "audio/x-flac" to "flac",
        "audio/ogg" to "ogg", "application/ogg" to "ogg", "audio/vorbis" to "ogg",
        "audio/opus" to "opus",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a", "audio/aac" to "aac", "audio/aacp" to "aac",
        "audio/amr" to "amr", "audio/amr-wb" to "awb", "audio/3gpp" to "3gp", "video/3gpp" to "3gp",
        "audio/x-aiff" to "aiff", "audio/aiff" to "aiff", "audio/x-wavpack" to "wv",
        "video/mp4" to "mp4", "video/x-matroska" to "mkv", "audio/webm" to "webm", "video/webm" to "webm",
        "text/plain" to "txt",
    )

    private val EXT_TO_MIME = mapOf(
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "m4a" to "audio/mp4", "aac" to "audio/aac", "mp2" to "audio/mpeg",
        "aif" to "audio/x-aiff", "aiff" to "audio/x-aiff", "wv" to "application/octet-stream",
        "amr" to "audio/amr", "aup3" to "application/octet-stream", "txt" to "text/plain",
    )

    fun extensionForMime(mime: String?): String? {
        if (mime == null) return null
        MIME_TO_EXT[mime.lowercase()]?.let { return it }
        return runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) }.getOrNull()
    }

    fun mimeForExtension(ext: String): String {
        val e = ext.lowercase()
        EXT_TO_MIME[e]?.let { return it }
        return runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) }.getOrNull() ?: "application/octet-stream"
    }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** A base name (no directories) safe for a file in app storage. */
    fun sanitizeBaseName(name: String): String {
        val cleaned = name.map { c -> if (c == '/' || c == '\\' || c == '\u0000' || c.code < 32 || c in ":*?\"<>|") '_' else c }
            .joinToString("").trim().trimStart('.')
        return cleaned.ifEmpty { "Untitled" }
    }

    /**
     * Sanitises a picked document name (§5.2 step 3): no `/` or NUL, at most
     * 200 bytes, extension kept; a missing extension is derived from [mime].
     */
    fun sanitizeFileName(name: String?, mime: String?): String {
        var base = sanitizeBaseName(name ?: "audio")
        var ext = extensionOf(base)
        if (ext.isEmpty()) {
            extensionForMime(mime)?.let { ext = it; base = "$base.$it" }
        }
        while (base.toByteArray(Charsets.UTF_8).size > 200) {
            val stem = base.substringBeforeLast('.', base)
            if (stem.length <= 1) break
            base = stem.dropLast(1) + (if (ext.isNotEmpty()) ".$ext" else "")
        }
        return base
    }

    /** `dir/base.ext`, or `dir/base (2).ext`, … when taken. */
    fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base ($n).$ext")
            n++
        }
        return f
    }

    /** A fresh staging directory `cacheDir/<kind>/<uuid>`. */
    fun stagingDir(cacheDir: File, kind: String): File =
        File(File(cacheDir, kind), UUID.randomUUID().toString()).apply { mkdirs() }

    /** True when [file] starts with the SQLite header "SQLite format 3\0". */
    fun isSqlite(file: File): Boolean = runCatching {
        file.inputStream().use { isSqlite(it) }
    }.getOrDefault(false)

    fun isSqlite(input: InputStream): Boolean {
        val header = ByteArray(16)
        var read = 0
        while (read < 16) {
            val n = input.read(header, read, 16 - read)
            if (n < 0) break
            read += n
        }
        return read == 16 && String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3" && header[15] == 0.toByte()
    }

    /** Copies [uri] to [dest] on Dispatchers.IO; cancellable; [onProgress] gets (done, total or -1). */
    suspend fun copyUriToFile(cr: ContentResolver, uri: Uri, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val total = size(cr, uri)
            dest.parentFile?.mkdirs()
            val input = cr.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            try {
                input.use { i -> dest.outputStream().use { o -> pump(i, o, total, onProgress) } }
            } catch (t: Throwable) {
                dest.delete()
                throw t
            }
        }

    /** Copies [src] to the document [uri] (mode "wt": some providers do not truncate with "w"). */
    suspend fun copyFileToUri(cr: ContentResolver, src: File, uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val out = cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
            out.use { o -> src.inputStream().use { i -> pump(i, o, src.length(), onProgress) } }
        }

    suspend fun writeTextToUri(cr: ContentResolver, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val out = cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun readText(cr: ContentResolver, uri: Uri, maxBytes: Int = 8 shl 20): String = withContext(Dispatchers.IO) {
        val input = cr.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { i ->
            val bytes = i.readNBytesCompat(maxBytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (buf.size() < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** Removes a document created for an export that failed or was cancelled (§5.3 step 4). */
    fun deleteDocument(cr: ContentResolver, uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(cr, uri) }
    }

    private suspend fun pump(input: InputStream, output: OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (done - lastReport >= 1 shl 20) {
                lastReport = done
                onProgress(done, total)
            }
        }
        output.flush()
        onProgress(done, total)
    }
}
