/*
 * Files — what another app asks to pick (`ACTION_GET_CONTENT`): the types of file it takes, and whether one
 * file or several (pick spec §2.1).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.util.baseMimeType

/**
 * A request for files from another app: the [mimeTypes] it takes, as [baseMimeType] makes them ([of] does),
 * and whether it takes several files at once ([allowMultiple]).
 */
data class PickRequest(val mimeTypes: List<String>, val allowMultiple: Boolean) {

    /**
     * Whether a file of type [mime] (as [baseMimeType] makes it) may be picked. Any type takes every file;
     * a type with a wildcard subtype (`image/` and an asterisk) takes the files of its top-level type; any
     * other type, the files of that very type. A file whose type is not known (null) is taken by any type
     * alone.
     */
    fun accepts(mime: String?): Boolean {
        val type = baseMimeType(mime)
        return mimeTypes.any { wanted ->
            when {
                wanted == ANY_TYPE -> true
                type == null -> false
                wanted.endsWith(WILDCARD_SUBTYPE) -> type.startsWith(wanted.dropLast(1))
                else -> type == wanted
            }
        }
    }

    companion object {
        /** The most files one answer holds: more would not fit in a binder transaction. */
        const val MAX_RESULTS = 500

        private const val ANY_TYPE = "*/*"
        private const val WILDCARD_SUBTYPE = "/*"

        /**
         * The request of an intent with [type] and [extraMimeTypes] (`EXTRA_MIME_TYPES`): those types if it has
         * any, else [type], else any type, each as [baseMimeType] makes it (blank ones are none).
         * [allowMultiple] is its `EXTRA_ALLOW_MULTIPLE`.
         */
        fun of(type: String?, extraMimeTypes: Array<String>?, allowMultiple: Boolean): PickRequest {
            val extra = extraMimeTypes.orEmpty().mapNotNull { baseMimeType(it) }
            return PickRequest(extra.ifEmpty { listOf(baseMimeType(type) ?: ANY_TYPE) }, allowMultiple)
        }
    }
}
