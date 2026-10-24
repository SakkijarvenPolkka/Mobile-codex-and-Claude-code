/*
 * Files — MIME types as the app compares them.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

import java.util.Locale

/**
 * [mime] as the app compares types: without its parameters (`; charset=utf-8`), trimmed, and in lower
 * case whatever the default locale (`TEXT/HTML; charset=utf-8` → `text/html`). Null for null and for a
 * type that is blank once its parameters are gone.
 */
fun baseMimeType(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
