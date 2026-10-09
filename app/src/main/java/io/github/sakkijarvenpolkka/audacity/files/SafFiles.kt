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
 * Provider calls (query, getType, deleteDocument) are binder round trips
 * that cloud providers may serve over the network: the suspend variants run
 * them on Dispatchers.IO. Copies into app storage are capped by the free
 * space (a source can be endless, or not report its size).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A copy into app storage would leave less than the reserved free space. */
class InsufficientSpaceException(message: String) : IOException(message)

/** A source that does not report its size gave more than [limit] bytes (it may not end). */
class SourceTooLargeException(val limit: Long) : IOException("more than $limit bytes from a source of unknown size")

object SafFiles {

    /** Free space kept when copying into app storage (the engine's autosave and other apps need it). */
    const val RESERVED_BYTES: Long = 64L shl 20

    /** Longest file name stem in app storage, in UTF-8 bytes: room for an
     *  extension and SQLite's "-wal"/"-shm"/"-journal" companions within NAME_MAX (255). */
    const val MAX_STEM_BYTES: Int = 200

    /** Longest name of a document created with the picker (extension included), in UTF-8
     *  bytes: room for the " (1)" a provider appends to a name that is taken. */
    const val MAX_DOCUMENT_NAME_BYTES: Int = 240

    /** Most bytes copied from a source that does not report its size (a WAV file ends at 4 GiB). */
    const val MAX_UNKNOWN_SIZE_BYTES: Long = 4L shl 30

    /** Name, size (-1 = unknown) and MIME type of a picked document. */
    data class DocInfo(val name: String?, val size: Long, val mime: String?)

    /** [DocInfo] of each of [uris], queried on Dispatchers.IO. */
    suspend fun describe(cr: ContentResolver, uris: List<Uri>): List<DocInfo> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            coroutineContext.ensureActive()
            DocInfo(displayName(cr, uri), size(cr, uri), runCatching { cr.getType(uri) }.getOrNull())
        }
    }

    /** [displayName] on Dispatchers.IO. */
    suspend fun displayNameIo(cr: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) { displayName(cr, uri) }

    /** Free bytes of the file system of an existing directory (statvfs f_bavail, like StatFs.availableBytes). */
    @VisibleForTesting
    internal var freeSpace: (File) -> Long = { it.usableSpace }

    /** Bytes that may be written below [dir] while keeping [reserve] free. */
    fun writableBytes(dir: File, reserve: Long = RESERVED_BYTES): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        val free = d?.let(freeSpace) ?: 0L
        return (free - reserve).coerceAtLeast(0)
    }

    /** Display name of a content Uri (or the last path segment). Blocking: prefer [describe] / [displayNameIo]. */
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

    /** Size in bytes, or -1 when unknown. Blocking: prefer [describe]. */
    fun size(cr: ContentResolver, uri: Uri): Long {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return -1   // not accepted (character devices report 0)
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

    /**
     * A base name (no directories) safe for a file in app storage, at most
     * [maxBytes] UTF-8 bytes (file names are limited to 255 bytes: a long
     * Korean or emoji name would otherwise fail with ENAMETOOLONG).
     */
    fun sanitizeBaseName(name: String, maxBytes: Int = MAX_STEM_BYTES): String {
        val cleaned = name.map { c -> if (c == '/' || c == '\\' || c == '\u0000' || c.code < 32 || c in ":*?\"<>|") '_' else c }
            .joinToString("").trim().trimStart('.')
        return truncateUtf8(cleaned, maxBytes).trimEnd().ifEmpty { "Untitled" }
    }

    /**
     * Sanitises a document name (§5.2 step 3): no `/` or NUL, at most
     * [maxBytes] UTF-8 bytes, extension kept; a missing extension is derived from [mime].
     */
    fun sanitizeFileName(name: String?, mime: String?, maxBytes: Int = MAX_STEM_BYTES): String {
        var base = sanitizeBaseName(name ?: "audio", Int.MAX_VALUE)
        var ext = extensionOf(base)
        if (ext.isEmpty()) {
            extensionForMime(mime)?.let { ext = it; base = "$base.$it" }
        }
        if (utf8Length(base) <= maxBytes) return base
        // An extension longer than a few characters is not one: truncate the whole name
        if (ext.isEmpty() || ext.length > 16) return truncateUtf8(base, maxBytes)
        val stem = base.substring(0, base.length - ext.length - 1)
        return truncateUtf8(stem, maxBytes - ext.length - 1).trimEnd().ifEmpty { "audio" } + "." + ext
    }

    /** UTF-8 length of [s] in bytes. */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return n
    }

    /** The longest prefix of [s] of at most [maxBytes] UTF-8 bytes (whole code points). */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (n + len > maxBytes) return s.substring(0, i)
            n += len
            i += Character.charCount(cp)
        }
        return s
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

    /**
     * Copies [uri] to [dest] on Dispatchers.IO; cancellable; [onProgress] gets
     * (done, total or -1). At most [maxBytes] are written (default: what
     * [writableBytes] allows in dest's directory): a longer source fails with
     * [InsufficientSpaceException] instead of filling the storage. A source
     * that does not report its size is also cut at [unknownSizeLimit]
     * ([SourceTooLargeException]).
     */
    suspend fun copyUriToFile(
        cr: ContentResolver,
        uri: Uri,
        dest: File,
        maxBytes: Long = -1,
        unknownSizeLimit: Long = MAX_UNKNOWN_SIZE_BYTES,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val total = size(cr, uri)
        dest.parentFile?.mkdirs()
        val room = if (maxBytes >= 0) maxBytes else writableBytes(dest.parentFile ?: dest)
        if (total > room) throw InsufficientSpaceException("$total bytes do not fit in ${dest.parent}")
        val capped = total < 0 && unknownSizeLimit < room
        val limit = if (capped) unknownSizeLimit else room
        val input = cr.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        try {
            input.use { i -> dest.outputStream().use { o -> pump(i, o, total, limit, onProgress) } }
        } catch (t: Throwable) {
            dest.delete()
            if (capped && t is InsufficientSpaceException) throw SourceTooLargeException(limit)
            throw t
        }
    }

    /** Copies [src] to the document [uri] (mode "wt": some providers do not truncate with "w"). */
    suspend fun copyFileToUri(cr: ContentResolver, src: File, uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val out = cr.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
            out.use { o -> src.inputStream().use { i -> pump(i, o, src.length(), Long.MAX_VALUE, onProgress) } }
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

    /**
     * Removes a document created for an export that failed or was cancelled
     * (§5.3 step 4), on Dispatchers.IO. Runs also in a cancelled coroutine
     * (it is called from the clean-up of a cancelled export).
     */
    suspend fun deleteDocument(cr: ContentResolver, uri: Uri) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(cr, uri) } }
    }

    /** Deletes [dir] recursively on Dispatchers.IO, also in a cancelled coroutine (finally blocks). */
    suspend fun deleteStaging(dir: File?) {
        if (dir == null) return
        withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
    }

    /** Deletes the project file [aup3] and its SQLite companions (-wal, -shm, -journal), also in a cancelled coroutine. */
    suspend fun deleteProjectFiles(aup3: File) {
        withContext(NonCancellable + Dispatchers.IO) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(aup3.path + suffix).delete()
        }
    }

    /** Copies [input] to [output]; more than [limit] bytes fail with [InsufficientSpaceException]. */
    internal suspend fun pump(input: InputStream, output: OutputStream, total: Long, limit: Long, onProgress: (Long, Long) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (n > limit - done) throw InsufficientSpaceException("more than $limit bytes")
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
