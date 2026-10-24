/*
 * Files — the options dialog: theme, system accent colour, hidden items and file name extensions.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ContentDialog
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentToggleSwitch
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget

/**
 * The settings that hold everywhere, applied as they are changed ([onChange]). "Use system
 * accent color" is offered from Android 12, which has one.
 */
@Composable
fun OptionsDialog(
    global: GlobalSettings,
    onChange: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FluentTheme.colors
    ContentDialog(
        title = stringResource(R.string.cmd_options),
        onDismiss = onDismiss,
        primary = null,
        close = stringResource(R.string.close) to onDismiss,
    ) {
        BasicText(
            stringResource(R.string.theme_title),
            modifier = Modifier.semantics { heading() },
            style = FluentTheme.type.bodyStrong.copy(color = colors.text),
        )
        Column(Modifier.selectableGroup()) {
            for (choice in ThemeChoice.entries) {
                RadioRow(stringResource(themeLabel(choice)), global.theme == choice, Tags.theme(choice)) {
                    onChange { it.copy(theme = choice) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(Modifier.testTag(Tags.SYSTEM_ACCENT)) {
                FluentToggleSwitch(
                    global.systemAccent,
                    { on -> onChange { it.copy(systemAccent = on) } },
                    stringResource(R.string.system_accent),
                )
            }
        }
        Box(Modifier.testTag(Tags.OPTION_HIDDEN)) {
            FluentToggleSwitch(
                global.showHidden,
                { on -> onChange { it.copy(showHidden = on) } },
                stringResource(R.string.cmd_hidden),
            )
        }
        Box(Modifier.testTag(Tags.OPTION_EXTENSIONS)) {
            FluentToggleSwitch(
                global.showExtensions,
                { on -> onChange { it.copy(showExtensions = on) } },
                stringResource(R.string.cmd_extensions),
            )
        }
    }
}

private fun themeLabel(choice: ThemeChoice): Int = when (choice) {
    ThemeChoice.SYSTEM -> R.string.theme_system
    ThemeChoice.LIGHT -> R.string.theme_light
    ThemeChoice.DARK -> R.string.theme_dark
}

/** A radio button row: a ring, filled with the accent around a dot when [selected]. */
@Composable
internal fun RadioRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    val colors = FluentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .testTag(tag)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val radius = size.minDimension / 2f
            if (selected) {
                drawCircle(colors.accent, radius)
                drawCircle(colors.onAccent, radius * 0.4f)
            } else {
                val width = 1.dp.toPx()
                drawCircle(colors.textSecondary, radius - width / 2f, style = Stroke(width))
            }
        }
        BasicText(label, style = FluentTheme.type.body.copy(color = colors.text))
    }
}
