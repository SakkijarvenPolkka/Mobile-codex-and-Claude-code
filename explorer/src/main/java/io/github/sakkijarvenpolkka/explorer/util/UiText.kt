/*
 * Files — text that is either a string resource or a literal (file names,
 * system messages).
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

    fun resolve(res: Resources): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) res.getString(id)
        else res.getString(id, *args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray())
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(text: String): UiText = Raw(text)
    }
}

@Composable
fun UiText.text(): String = resolve(LocalResources.current)
