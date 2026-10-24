/*
 * Files — where a path typed in the address bar (or a link's) leads.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.fs.FsBackend
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

/** Where a path leads ([resolvePath]): a folder or a file (by their real paths), outside storage, or nowhere. */
internal sealed interface Resolved {
    data class Folder(val path: Path) : Resolved
    data class File(val path: Path) : Resolved
    data object Outside : Resolved
    data class Failed(val error: FsError) : Resolved
}

/**
 * Where [text] leads, against [base] when it is relative (not found without one): made canonical by
 * [fs] (`..`, links, `/sdcard`), outside storage when on no volume of [lookup]. It blocks, and throws
 * what the backend throws.
 */
internal fun resolvePath(fs: FsBackend, lookup: VolumeLookup, text: String, base: Path?): Resolved {
    val path = try {
        Paths.get(text)
    } catch (e: InvalidPathException) {
        return Resolved.Failed(FsError.NotFound)
    }
    val absolute = if (path.isAbsolute) path else base?.resolve(path) ?: return Resolved.Failed(FsError.NotFound)
    val real = fs.realPath(absolute)
    if (lookup.volumeOf(real) == null) return Resolved.Outside
    return if (fs.attributes(real).isDirectory) Resolved.Folder(real) else Resolved.File(real)
}
