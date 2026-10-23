/*
 * Files — text that is either a string resource, a literal (file names,
 * system messages) or a size in bytes.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.util

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    /** [bytes] as the app shows sizes ([Formats.size]), in the language of the resources it is resolved with. */
    data class Size(val bytes: Long) : UiText

    fun resolve(res: Resources): String = when (this) {
        is Raw -> text
        is Size -> Formats.size(bytes, korean = res.configuration.locales[0].language == "ko")
        is Res -> if (args.isEmpty()) res.getString(id)
        else res.getString(id, *args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(text: String): UiText = Raw(text)
        fun size(bytes: Long): UiText = Size(bytes)
    }
}

@Composable
fun UiText.text(): String = resolve(LocalResources.current)
