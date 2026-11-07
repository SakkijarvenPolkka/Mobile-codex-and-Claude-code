/*
 * Files — the answer to another app that asked for files (pick spec §2.3): the files chosen, as content URIs it
 * may read, with their type.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.pick

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.github.sakkijarvenpolkka.explorer.platform.commonType
import java.nio.file.Path

/** The type of a file whose type is not known: bytes. */
private const val OCTET_STREAM = "application/octet-stream"

/**
 * The result for the app that asked for files, of the [paths] chosen (at least one): each as the content URI
 * [uriFor] makes of it, of the type [typeOf] tells (bytes when it tells none). One file is the result's data, with
 * its type. Several are its clip data, in their order, the first of them its data too, with the type that covers
 * them all ([commonType], as sharing has it). The app may read them, never write them.
 */
fun pickResultIntent(paths: List<Path>, uriFor: (Path) -> Uri, typeOf: (Path) -> String?): Intent {
    require(paths.isNotEmpty()) { "Nothing chosen" }
    val uris = paths.map(uriFor)
    val types = paths.map { typeOf(it) ?: OCTET_STREAM }
    val result = Intent()
    if (uris.size == 1) {
        result.setDataAndType(uris.single(), types.single())
    } else {
        val clip = ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        result.clipData = clip
        result.setDataAndType(uris.first(), commonType(types))
    }
    return result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
