/*
 * Files — the extension of a file name, the one rule every screen and the sort use.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

import java.util.Locale

/**
 * Where [name]'s extension starts: the index of its last dot, when that dot neither starts nor
 * ends the name (`.bashrc` and `a.` have no extension); -1 when there is none.
 */
fun extensionStart(name: String): Int {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.lastIndex) -1 else dot
}

/**
 * [name]'s extension in lower case whatever the default locale (`Report.PDF` → `pdf`,
 * `a.tar.gz` → `gz`), or "" when it has none (see [extensionStart]).
 */
fun extensionOf(name: String): String {
    val dot = extensionStart(name)
    return if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
}
