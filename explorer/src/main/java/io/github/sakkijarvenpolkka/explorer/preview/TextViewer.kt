/*
 * Files — the text viewer: numbered rows in a monospace font, the "first 1 MB" notice, and the
 * encoding the file was read with in a status bar whose menu reads it with another (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.DisplayLine
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MenuFlyout
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import java.io.IOException
import java.nio.file.Path

/** The test tags of [TextViewer]; the encoding menu's items are tagged with the `ENCODING_` ids. */
object TextViewerTags {
    const val LINES = "text_lines"
    const val ENCODING = "text_encoding"
    const val ENCODING_AUTO = "encoding_auto"
    const val ENCODING_UTF8 = "encoding_utf8"
    const val ENCODING_KOREAN = "encoding_korean"
    const val ENCODING_UTF16 = "encoding_utf16"
}

/**
 * [file] as text, read by [loader] (off the main thread, see [FsTextLoader]): a title row with
 * the name and a close button ([onClose]), the "first 1 MB" notice for a longer file, the rows
 * with their line numbers (a row continuing a long line has none) and a status bar with the
 * encoding and a "⋯" menu to read the file again as Auto, UTF-8, Korean (CP949) or UTF-16 (the
 * byte order detected from a byte order mark, else little-endian). A file that is not text, or
 * cannot be read, says so; choosing an encoding shows a binary file anyway.
 */
@Composable
fun TextViewer(file: Path, loader: TextLoader, onClose: () -> Unit) {
    var forced by rememberSaveable(file) { mutableStateOf<TextEncoding?>(null) }
    var detected by rememberSaveable(file) { mutableStateOf<TextEncoding?>(null) }
    var state by remember(file, loader) { mutableStateOf<ViewState>(ViewState.Loading) }
    LaunchedEffect(file, loader, forced) {
        state = try {
            val document = loader.load(file, forced)
            if (forced == null && document is TextDocument.Lines) detected = document.encoding
            ViewState.Shown(document)
        } catch (e: IOException) {
            ViewState.Failed
        }
    }
    val colors = FluentTheme.colors
    val lines = ((state as? ViewState.Shown)?.document as? TextDocument.Lines)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TitleRow(file, onClose)
        if (lines?.truncated == true) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                InfoBar(InfoSeverity.INFO, stringResource(R.string.text_truncated))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = state) {
                ViewState.Loading -> Unit
                ViewState.Failed -> Notice(stringResource(R.string.open_unsupported))
                is ViewState.Shown -> when (val document = shown.document) {
                    TextDocument.Binary -> Notice(stringResource(R.string.text_binary))
                    is TextDocument.Lines -> Rows(file, document.lines)
                }
            }
        }
        EncodingBar(
            encoding = lines?.encoding,
            forced = forced,
            onForce = { choice ->
                forced = if (choice == TextEncoding.UTF16LE && detected == TextEncoding.UTF16BE) TextEncoding.UTF16BE else choice
            },
        )
    }
}

/** What [TextViewer] shows while [TextLoader.load] runs, after it returned and after it failed. */
private sealed interface ViewState {
    data object Loading : ViewState
    data class Shown(val document: TextDocument) : ViewState
    data object Failed : ViewState
}

/** The title row of a viewer: the name of [file] and a close button ([onClose]). */
@Composable
internal fun TitleRow(file: Path, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            DisplayName.of(file.fileName?.toString() ?: file.toString()),
            modifier = Modifier.weight(1f),
            style = FluentTheme.type.bodyStrong.copy(color = FluentTheme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FluentButton(
            text = null,
            onClick = onClose,
            icon = FluentIcons.Close,
            style = ButtonStyle.SUBTLE,
            contentDescription = stringResource(R.string.close),
        )
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        BasicText(
            text,
            style = FluentTheme.type.body.copy(color = FluentTheme.colors.textSecondary, textAlign = TextAlign.Center),
        )
    }
}

/** The rows of [lines], each line number right-aligned in a gutter as wide as the last one. */
@Composable
private fun Rows(file: Path, lines: List<DisplayLine>) {
    val colors = FluentTheme.colors
    val textStyle = FluentTheme.type.mono.copy(color = colors.text)
    val numberStyle = FluentTheme.type.mono.copy(color = colors.textSecondary, textAlign = TextAlign.End)
    val gutter = gutterWidth(digits = (lines.lastOrNull()?.number ?: 1).toString().length, numberStyle)
    val listState = rememberSaveable(file, saver = LazyListState.Saver) { LazyListState() }
    LazyColumn(Modifier.fillMaxSize().testTag(TextViewerTags.LINES), state = listState) {
        items(lines) { line ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                BasicText(if (line.continuation) "" else line.number.toString(), Modifier.width(gutter), style = numberStyle)
                Spacer(Modifier.width(12.dp))
                BasicText(line.text, Modifier.weight(1f), style = textStyle)
            }
        }
    }
}

/** The width of [digits] digits in [style] (a monospace font, so any number of that many digits). */
@Composable
private fun gutterWidth(digits: Int, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(digits, style, measurer, density) {
        with(density) { measurer.measure("0".repeat(digits), style).size.width.toDp() }
    }
}

/**
 * The status bar: [encoding] (the shown document's, none while there is none) and the "⋯" menu,
 * where the choice in effect ([forced], Auto when null) is checked.
 */
@Composable
private fun EncodingBar(encoding: TextEncoding?, forced: TextEncoding?, onForce: (TextEncoding?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun item(id: String, label: String, choice: TextEncoding?, checked: Boolean) =
        CommandItem(id, label, if (checked) FluentIcons.Check else null, enabled = true) { onForce(choice) }
    val items = listOf(
        item(TextViewerTags.ENCODING_AUTO, stringResource(R.string.encoding_auto), null, forced == null),
        item(TextViewerTags.ENCODING_UTF8, stringResource(R.string.encoding_utf8), TextEncoding.UTF8, forced == TextEncoding.UTF8),
        item(
            TextViewerTags.ENCODING_KOREAN,
            stringResource(R.string.encoding_korean),
            TextEncoding.CP949,
            forced == TextEncoding.CP949,
        ),
        item(
            TextViewerTags.ENCODING_UTF16,
            stringResource(R.string.encoding_utf16),
            TextEncoding.UTF16LE,
            forced == TextEncoding.UTF16LE || forced == TextEncoding.UTF16BE,
        ),
    )
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            if (encoding != null) encodingLabel(encoding) else "",
            modifier = Modifier.weight(1f).testTag(TextViewerTags.ENCODING),
            style = FluentTheme.type.caption.copy(color = FluentTheme.colors.textSecondary),
            maxLines = 1,
        )
        Box {
            FluentButton(
                text = null,
                onClick = { expanded = true },
                icon = FluentIcons.More,
                style = ButtonStyle.SUBTLE,
                contentDescription = stringResource(R.string.more),
            )
            MenuFlyout(expanded, onDismiss = { expanded = false }, items = items)
        }
    }
}

@Composable
private fun encodingLabel(encoding: TextEncoding): String = stringResource(
    when (encoding) {
        TextEncoding.UTF8 -> R.string.encoding_utf8
        TextEncoding.CP949 -> R.string.encoding_korean
        TextEncoding.UTF16LE, TextEncoding.UTF16BE -> R.string.encoding_utf16
    },
)
