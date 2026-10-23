/*
 * Files — the screen shown instead of everything else until the app has all files access.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcon
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme

/** Why the app needs all files access, and a button ([onOpenSettings]) to the screen that grants it. */
@Composable
fun PermissionScreen(onOpenSettings: () -> Unit) {
    val colors = FluentTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag(Tags.PERMISSION_SCREEN),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FluentIcon(FluentIcons.Folder, contentDescription = null, Modifier.size(48.dp), tint = FolderTint)
            BasicText(
                stringResource(R.string.permission_title),
                modifier = Modifier.semantics { heading() },
                style = FluentTheme.type.subtitle.copy(color = colors.text),
            )
            BasicText(
                stringResource(R.string.permission_body),
                style = FluentTheme.type.body.copy(color = colors.textSecondary),
            )
            FluentButton(
                stringResource(R.string.permission_button),
                onOpenSettings,
                modifier = Modifier.testTag(Tags.PERMISSION_BUTTON),
                style = ButtonStyle.ACCENT,
            )
        }
    }
}
