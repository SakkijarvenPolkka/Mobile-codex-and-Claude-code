/*
 * Files — a Fluent content dialog: title, content and up to three buttons.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A modal dialog with [title], [content] (scrolls when long) and a row of buttons: [primary]
 * as the accent button (disabled unless [primaryEnabled]), then [secondary] and [close]; each
 * pair is a label and its action. The buttons only call their actions; the caller removes the
 * dialog. Back calls [onDismiss]; touching outside does not, so typed input is not lost by
 * accident. The dialog is as wide as its content and buttons need, from 280 to 548 dp (longer
 * text wraps); with [fullWidth] it is always as wide as it gets, for content that changes while
 * it is shown (a progress, a name being typed, a count), so the dialog does not resize with it. It
 * keeps clear of the system bars and of the keyboard.
 */
@Composable
fun ContentDialog(
    title: String,
    onDismiss: () -> Unit,
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
    close: Pair<String, () -> Unit>?,
    primaryEnabled: Boolean = true,
    fullWidth: Boolean = false,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The insets reach the content, which pads itself for the keyboard (imePadding).
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val colors = FluentTheme.colors
        val shape = FluentTheme.shapes.overlay
        Column(
            Modifier
                .systemBarsPadding()
                .imePadding()
                .padding(24.dp)
                .widthIn(min = 280.dp, max = 548.dp)
                .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max))
                .shadow(16.dp, shape)
                .background(colors.card, shape)
                .border(1.dp, colors.stroke, shape)
                .semantics { paneTitle = title },
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                BasicText(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = FluentTheme.type.subtitle.copy(color = colors.text),
                )
                Spacer(Modifier.height(12.dp))
                content()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .drawBehind { drawLine(colors.stroke, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                primary?.let { (label, action) ->
                    FluentButton(label, action, Modifier.weight(1f), style = ButtonStyle.ACCENT, enabled = primaryEnabled)
                }
                secondary?.let { (label, action) -> FluentButton(label, action, Modifier.weight(1f)) }
                close?.let { (label, action) -> FluentButton(label, action, Modifier.weight(1f)) }
            }
        }
    }
}
