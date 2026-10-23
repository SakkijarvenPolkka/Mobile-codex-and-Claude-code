/*
 * Files — counting what a selection holds, for its properties.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path

/** What a selection holds as counted so far: files, folders and the files' size in bytes. */
data class ContentCount(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0)

/**
 * Counts [entries] and everything under their folders, emitting the total so far at once, again before
 * each folder below them is read, and when each selected folder is done. A selected file counts itself;
 * a selected folder counts itself only among several, since the properties of one folder show what it
 * contains. The count is a [walk]: links are neither followed nor counted, the app's internal names are
 * left out, and so is what cannot be read (whatever the backend throws). The walk stops when its
 * collector is cancelled.
 */
fun countContents(fs: FsBackend, entries: List<FileEntry>): Flow<ContentCount> = flow {
    var files = 0
    var folders = 0
    var bytes = 0L
    val selectedFolders = ArrayList<Path>()
    for (entry in entries) {
        when (entry.kind) {
            EntryKind.FILE -> {
                files++
                bytes += entry.size
            }
            EntryKind.DIR -> {
                if (entries.size > 1) folders++
                selectedFolders.add(entry.path)
            }
            EntryKind.SYMLINK, EntryKind.OTHER -> Unit
        }
    }
    emit(ContentCount(files, folders, bytes))
    for (folder in selectedFolders) {
        walk(fs, folder) { item ->
            when (item.entry.kind) {
                EntryKind.FILE -> {
                    files++
                    bytes += item.entry.size
                }
                EntryKind.DIR -> {
                    folders++
                    emit(ContentCount(files, folders, bytes))
                }
                EntryKind.SYMLINK, EntryKind.OTHER -> Unit
            }
            true
        }
        emit(ContentCount(files, folders, bytes))
    }
}
