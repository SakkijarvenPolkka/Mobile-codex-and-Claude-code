/*
 * Files — one item of a folder, as read once from the file system.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.fs

import java.nio.file.Path

/** What an item is, read without following links. */
enum class EntryKind { FILE, DIR, SYMLINK, OTHER }

/**
 * A folder item. [size] is in bytes and 0 for anything but a [EntryKind.FILE]; [modified]
 * is in epoch milliseconds; [mime] is known for files only; [hidden] means the name starts
 * with a dot; [fileKey] identifies the file on its volume (inode), or is null if the
 * file system has none.
 */
data class FileEntry(
    val path: Path,
    val name: String,
    val kind: EntryKind,
    val size: Long,
    val modified: Long,
    val mime: String?,
    val hidden: Boolean,
    val fileKey: Any?,
)
