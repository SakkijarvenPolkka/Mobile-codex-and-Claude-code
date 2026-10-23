/*
 * Files — reading a folder into entries and sorting them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import io.github.sakkijarvenpolkka.explorer.util.extensionOf
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path

enum class SortKey { NAME, MODIFIED, TYPE, SIZE }

/** How a folder is sorted; folders always come first, whatever the direction. */
@Serializable
data class SortSpec(val key: SortKey = SortKey.NAME, val ascending: Boolean = true)

data class ListOptions(val showHidden: Boolean = false, val sort: SortSpec = SortSpec())

/** Folder listing. [mime] maps a file name to its MIME type. */
object Listing {

    /** The entry for [path], from one attributes read; links are not followed. */
    fun entry(fs: FsBackend, path: Path, mime: (String) -> String?): FileEntry {
        val attributes = fs.attributes(path)
        val name = path.fileName?.toString() ?: path.toString()
        val kind = when {
            attributes.isSymbolicLink -> EntryKind.SYMLINK
            attributes.isDirectory -> EntryKind.DIR
            attributes.isRegularFile -> EntryKind.FILE
            else -> EntryKind.OTHER
        }
        val isFile = kind == EntryKind.FILE
        return FileEntry(
            path = path,
            name = name,
            kind = kind,
            size = if (isFile) attributes.size() else 0L,
            modified = attributes.lastModifiedTime().toMillis(),
            mime = if (isFile) mime(name) else null,
            hidden = HiddenNames.isHidden(name),
            fileKey = attributes.fileKey(),
        )
    }

    /**
     * The sorted entries of [dir]. Internal names are always left out, hidden ones unless
     * [ListOptions.showHidden]. A child that cannot be read (gone, denied) is skipped; a
     * failure to read [dir] itself is thrown.
     */
    fun list(fs: FsBackend, dir: Path, options: ListOptions, mime: (String) -> String?): List<FileEntry> {
        val entries = ArrayList<FileEntry>()
        for (child in fs.list(dir)) {
            val name = child.fileName.toString()
            if (HiddenNames.isInternal(name) || (!options.showHidden && HiddenNames.isHidden(name))) continue
            try {
                entries += entry(fs, child, mime)
            } catch (e: IOException) {
                // Gone since the folder was read, or unreadable: left out.
            }
        }
        return sort(entries, options.sort)
    }

    /** [entries] sorted by [spec], folders first; ties on the key are broken by name. */
    fun sort(entries: List<FileEntry>, spec: SortSpec): List<FileEntry> {
        val byType = spec.key == SortKey.TYPE
        val keyed = entries.map { entry ->
            Keyed(entry, NameComparator.key(entry.name), if (byType) typeKey(entry) else "")
        }
        val byName = Comparator<Keyed> { a, b -> NameComparator.compare(a.name, a.entry.name, b.name, b.entry.name) }
        val byKey = when (spec.key) {
            SortKey.NAME -> byName
            SortKey.MODIFIED -> compareBy<Keyed> { it.entry.modified }.then(byName)
            SortKey.TYPE -> Comparator<Keyed> { a, b -> NameComparator.compareKeys(a.type, b.type) }.then(byName)
            SortKey.SIZE -> compareBy<Keyed> { it.entry.size }.then(byName)
        }
        val foldersFirst = compareBy<Keyed> { it.entry.kind != EntryKind.DIR }
            .then(if (spec.ascending) byKey else byKey.reversed())
        return keyed.sortedWith(foldersFirst).map { it.entry }
    }

    /** An entry with its sort keys computed once. */
    private class Keyed(val entry: FileEntry, val name: String, val type: String)

    /** The folded extension ([extensionOf]); none for folders. */
    private fun typeKey(entry: FileEntry): String =
        if (entry.kind == EntryKind.DIR) "" else NameComparator.key(extensionOf(entry.name))
}
