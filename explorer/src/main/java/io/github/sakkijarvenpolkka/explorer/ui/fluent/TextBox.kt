/*
 * Files — a Fluent text box.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A text box that fills the available width: 40 dp drawn inside a 48 dp touch target, a
 * thin border and a bottom line that turns into a 2 dp accent line while focused.
 * [placeholder] shows while it is empty. With [onSubmit] the keyboard's action key is
 * [submitAction] ("Done", or "Search" for a search box) and calls it. An [error] colours the
 * border, is shown below the box and is reported to accessibility services.
 */
@Composable
fun FluentTextBox(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    error: String? = null,
    submitAction: ImeAction = ImeAction.Done,
) {
    val colors = FluentTheme.colors
    val type = FluentTheme.type
    val shape = FluentTheme.shapes.control
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lineColor = when {
        error != null -> colors.danger
        focused -> colors.accent
        else -> colors.textDisabled
    }
    val lineWidth = if (focused || error != null) 2.dp else 1.dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (error != null) Modifier.semantics { error(error) } else Modifier),
        textStyle = type.body.copy(color = colors.text),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) submitAction else ImeAction.Default),
        keyboardActions = if (onSubmit != null) {
            KeyboardActions(onDone = { onSubmit() }, onSearch = { onSubmit() })
        } else {
            KeyboardActions.Default
        },
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.accent),
        decorationBox = { innerTextField ->
            Column {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(shape)
                        .background(colors.card)
                        .border(1.dp, if (error != null) colors.danger else colors.stroke, shape)
                        .drawWithContent {
                            drawContent()
                            val y = size.height - lineWidth.toPx() / 2f
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), lineWidth.toPx())
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (placeholder != null && value.text.isEmpty()) {
                        BasicText(
                            placeholder,
                            style = type.body.copy(color = colors.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (error != null) {
                    BasicText(
                        error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        style = type.caption.copy(color = colors.danger),
                    )
                }
            }
        },
    )
}
