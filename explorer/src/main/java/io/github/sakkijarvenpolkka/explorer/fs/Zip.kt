/*
 * Files — ZIP archives: compressing items into one and extracting one into a new folder, by the write
 * rules of spec §4.3, and the file operations that run them (spec §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** What `ZipFile` says of a name the path check of Android 14 and later refuses (`ZipPathValidator`). */
private const val INVALID_PATH = "Invalid zip entry path"

/**
 * What an extraction made: the [folder] the archive is in, and how many of its entries were given a
 * ` (2)` name because theirs was taken ([renamedDuplicates]: an entry of the same name before it, or one
 * that differs only in case, on a volume that ignores case).
 */
data class ExtractResult(val folder: Path, val renamedDuplicates: Int)

/**
 * ZIP archives on [fs] (spec §6.2): [compress] writes items into a new one, [extract] puts the entries of
 * one into a new folder. Files are written through [write]: the archive under a temporary name, renamed
 * once whole, and each extracted file the same way, in a temporary folder that [write]'s journal records
 * and that gets its name once everything is in it. [labels] number the names that are taken.
 * [removable] says whether a folder is on removable media, where each extracted file is flushed to the
 * device. Archives are read with `java.util.zip.ZipFile`, which reads the file itself, not through [fs].
 * Nothing here switches threads: the caller runs it where blocking calls belong.
 */
class ZipOps(
    private val fs: FsBackend,
    private val write: WriteProtocol,
    private val labels: NameLabels,
    private val removable: (Path) -> Boolean = { false },
) {
    /**
     * The character set the entry names of [zip] are in ([chooseZipCharset]). Fails with [FsException]:
     * [FsError.UnsupportedEncoding] when no set it tries decodes them, but [FsError.UnsafeArchive] for an
     * archive the path check of Android 14 and later refuses, [FsError.Encrypted] for one with an
     * encrypted entry and [FsError.Corrupt] for one that cannot be read whatever its names are in.
     */
    fun detectCharset(zip: Path): Charset = asZipFailure {
        try {
            chooseZipCharset { charset -> ZipFile(zip.toFile(), charset).use(::namesOf) }
        } catch (e: FsException) {
            if (e.error != FsError.UnsupportedEncoding) throw e
            throw FsException(undecodable(zip, e.cause), e.cause)
        }
    }

    /**
     * Compresses [sources] into a new archive in [targetDir] and returns it. A source in a folder that is
     * among them too is left out (the folder holds it), and so is any repeat. The archive is named after
     * the first: a file's name without its extension, a folder's whole name, then `.zip`, numbered if
     * that is taken ([UniqueStyle.KEEP_BOTH]). In it, each source is at its top under its own name (two
     * of one name, say from search results, are numbered the same way) with everything under a folder,
     * in walk order ([walk], the app's internal names left out): its folders as entries of their own (so
     * an empty one is kept), each item with its modified time, every name in UTF-8 with the EFS flag.
     * Links and special files are left out; anything that cannot be read fails the whole.
     *
     * The walk is over before anything is written, so neither the archive nor its temporary file is in
     * it. The archive is written as [WriteProtocol.write] writes a file, under [opId]'s temporary name, and
     * flushed to the device. [onProgress] gets the totals first (the items and the bytes of their files),
     * then at most one report each 100 ms as the files are read, then a final one. Fails with
     * [FsException]: nothing at all to compress is [FsError.Unsupported].
     */
    suspend fun compress(
        sources: List<Path>,
        targetDir: Path,
        opId: String,
        onProgress: (OpProgress) -> Unit,
    ): Path = asZipFailure { Compression(fs, write, labels).run(sources, targetDir, opId, onProgress) }

    /**
     * Extracts [zip] into a new folder in [targetDir], named after it without its extension and numbered
     * if that is taken ([UniqueStyle.KEEP_BOTH]): never into a folder that is there. The names are read
     * with [charset], or the one [detectCharset] finds when it is null, and checked before anything is
     * written ([ZipNames.sanitize]): one unsafe name refuses the whole archive. So does too little room
     * for the sizes the archive declares, when the volume can tell (64 MiB are left free).
     *
     * Everything goes first into the hidden temporary folder `.<name>.<opId>.xpart` there, which the
     * journal records before it is made: each entry as [WriteProtocol.write] writes a file, with the
     * entry's modified time, created only under a name that is free. One whose name is taken there (an
     * entry of that name before it, or of a name that differs only in case) gets a ` (2)` name, and is
     * counted in [ExtractResult.renamedDuplicates]; so does a folder whose name a file has. Folders the
     * archive names get their times once all files are in. A link stored in the archive becomes a file
     * holding its target's path; nothing is followed. Once all is in, the temporary folder is renamed in
     * one step to the new folder's name. When [removable] says [targetDir] is on removable media, each
     * file is flushed to the device before it gets its name, as a copy there is (spec §4.3); elsewhere
     * renaming it is enough, and the temporary folder is what a dying process leaves.
     *
     * [onProgress] gets the totals first (the entries and their declared sizes), then at most one report
     * each 100 ms, then a final one. An entry that holds more bytes than it declares is stopped there; one
     * that holds fewer, or whose CRC-32 is not the declared one, or whose data does not inflate is
     * [FsError.Corrupt], as is an archive that cannot be read; an encrypted entry is
     * [FsError.Encrypted]. Fails with [FsException], also when the names are in no character set that
     * decodes them ([FsError.UnsupportedEncoding]) or unsafe ([FsError.UnsafeArchive]); a full volume is
     * [FsError.NoSpace]. If anything fails or the extraction is cancelled, the temporary folder is deleted
     * with all it holds, and only what cannot be deleted stays, recorded in the journal.
     */
    suspend fun extract(
        zip: Path,
        targetDir: Path,
        charset: Charset?,
        opId: String,
        onProgress: (OpProgress) -> Unit,
    ): ExtractResult = asZipFailure {
        val names = charset ?: detectCharset(zip)
        open(zip, names).use { archive ->
            val stem = splitName(zip.fileName.toString()).first
            Extraction(fs, write, labels, removable(targetDir), archive, targetDir, stem, opId).run(onProgress)
        }
    }

    /** [zip] opened with its names in [charset]; fails with [FsException], as [detectCharset] does. */
    private fun open(zip: Path, charset: Charset): ZipFile = try {
        ZipFile(zip.toFile(), charset)
    } catch (e: ZipException) {
        // Names the set does not decode are a ZipException too ("bad entry name").
        throw FsException(undecodable(zip, e), e)
    }

    /**
     * Why [zip] could not be opened with a character set, the last try failing with [failure]: what that
     * says if it is not damage ([errorOf]), else [FsError.UnsupportedEncoding] if the archive opens with
     * names that always decode (ISO-8859-1 has a character for every byte), else what keeps it closed.
     */
    private fun undecodable(zip: Path, failure: Throwable?): FsError {
        val error = (failure as? ZipException)?.let(::errorOf)
        if (error != null && error != FsError.Corrupt) return error
        return try {
            ZipFile(zip.toFile(), Charsets.ISO_8859_1).use(::namesOf)
            FsError.UnsupportedEncoding
        } catch (e: ZipException) {
            errorOf(e)
        } catch (e: IllegalArgumentException) {
            // An entry flagged as UTF-8 that is not: no character set reads it.
            FsError.Corrupt
        }
    }
}

/** The names of [file]'s entries; [IllegalArgumentException] for one its character set does not decode. */
private fun namesOf(file: ZipFile): List<String> = file.entries().toList().map { it.name }

/**
 * What a [ZipException] of `ZipFile` stands for: an unsafe path (the check of Android 14 and later), an
 * encrypted entry (it refuses those, general purpose flag bit 0, by name), or else damage.
 */
private fun errorOf(e: ZipException): FsError {
    val message = e.message.orEmpty()
    return when {
        INVALID_PATH in message -> FsError.UnsafeArchive
        message.contains("encrypted", ignoreCase = true) -> FsError.Encrypted
        else -> FsError.Corrupt
    }
}

/**
 * [block], with a [ZipException] it throws as an [FsException] of its [errorOf], and any other IOException
 * as one of its [toFsError].
 */
private inline fun <T> asZipFailure(block: () -> T): T = try {
    block()
} catch (e: FsException) {
    throw e
} catch (e: ZipException) {
    throw FsException(errorOf(e), e)
} catch (e: IOException) {
    throw FsException(e.toFsError(), e)
}

/**
 * [OpKind.COMPRESS] of [spec], as [FileOps.run] describes it: its sources into a new archive in its
 * target folder ([ZipOps.compress]).
 */
internal suspend fun ZipOps.compressRun(
    spec: OperationSpec,
    onProgress: (OpProgress) -> Unit,
    onCommitted: (Int) -> Unit,
): OpResult {
    val target = Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
    val tracked = Tracked(onProgress)
    return try {
        val zip = compress(spec.sources.map { Paths.get(it) }, target, spec.id, tracked::report)
        onCommitted(spec.sources.size)
        OpResult.Done(listOf(zip), emptyList())
    } catch (e: FsException) {
        tracked.failed(e.error)
    }
}

/**
 * [OpKind.EXTRACT] of [spec], as [FileOps.run] describes it: each of its sources, an archive, into a
 * new folder in its target folder ([ZipOps.extract]), in turn.
 */
internal suspend fun ZipOps.extractRun(
    spec: OperationSpec,
    onProgress: (OpProgress) -> Unit,
    onCommitted: (Int) -> Unit,
): OpResult {
    val target = Paths.get(requireNotNull(spec.target) { "${spec.kind} needs a target" })
    val charset = try {
        spec.options.zipCharset?.let { Charset.forName(it) }
    } catch (e: IllegalArgumentException) {
        // A name this runtime does not know (UnsupportedCharsetException) or that is none.
        return OpResult.Failed(FsError.UnsupportedEncoding, 0, 0)
    }
    val created = ArrayList<Path>()
    var renamed = 0
    for ((index, source) in spec.sources.withIndex()) {
        val tracked = Tracked(onProgress)
        try {
            val result = extract(Paths.get(source), target, charset, spec.id, tracked::report)
            created.add(result.folder)
            renamed += result.renamedDuplicates
        } catch (e: FsException) {
            return tracked.failed(e.error)
        }
        onCommitted(index + 1)
    }
    return OpResult.Done(created, emptyList(), renamedDuplicates = renamed)
}

/** [onProgress] of a run that writes or extracts one archive, keeping how many items it said it has. */
private class Tracked(private val onProgress: (OpProgress) -> Unit) {
    private var totalItems = 0

    fun report(progress: OpProgress) {
        totalItems = progress.totalItems
        onProgress(progress)
    }

    /**
     * The run's end at [error]: nothing of the archive is kept (one is written or extracted whole), out
     * of the items it counted; 0 of 0 when it was refused before it counted them.
     */
    fun failed(error: FsError) = OpResult.Failed(error, 0, totalItems)
}
