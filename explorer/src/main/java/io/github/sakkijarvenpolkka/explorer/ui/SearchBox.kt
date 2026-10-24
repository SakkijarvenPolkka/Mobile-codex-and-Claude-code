/*
 * Files — the search box (spec §6.1): what is typed in it searches the active tab's folder once the
 * typing stops, or at the keyboard's search key, never while the keyboard is still composing it.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTextBox
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import java.nio.file.Path

/** Whether the active tab of [this] can be searched: a folder or search results, on a mounted drive. */
internal val ExplorerUiState.canSearch: Boolean
    get() = (location is Location.Folder || location is Location.SearchResults) && unavailable == null

/** "Search <the folder>": what the search box of [ui] searches, by the name of the folder or of its drive. */
@Composable
internal fun searchHint(ui: ExplorerUiState): String {
    val internal = stringResource(R.string.volume_internal)
    val root = (ui.location as? Location.Folder)?.path ?: (ui.location as? Location.SearchResults)?.root
    val name = root?.let { folder ->
        ui.volumes.firstOrNull { it.root == folder }?.let { volumeLabel(it, internal) }
            ?: (folder.fileName ?: folder).toString()
    }
    return stringResource(R.string.search_hint, DisplayName.inSentence(name.orEmpty()))
}

/**
 * The search box of [ui]'s active tab, [searchHint] while empty. A change of its text calls [onSearch]
 * (the view model waits for the typing to stop), unless the keyboard is still composing it (a Korean
 * syllable being put together, say): then once it is composed. The keyboard's search key calls
 * [onSearchNow], composed or not. While it holds text, × empties it and calls [onCancel]. Each tab has its
 * own: it keeps what was typed in it, empties when the tab leaves its search results, and shows the words
 * of search results it comes to otherwise (Back, Forward, a search run again). [onFocusChange] tells when
 * it has the focus; with [autoFocus] it takes it when it first shows.
 */
@Composable
fun SearchBox(
    ui: ExplorerUiState,
    onSearch: (String) -> Unit,
    onSearchNow: (String) -> Unit,
    onCancel: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val tab = ui.tabs.activeId
    val shown = (ui.location as? Location.SearchResults)?.query
    var value by remember(tab) { mutableStateOf(textOf(shown)) }
    // The words of the search results last shown, and every text searched for from here since the tab left
    // search results: results for one of them came from typing, which goes on.
    var lastShown by remember(tab) { mutableStateOf(shown) }
    val typed = remember(tab) { HashSet<String>() }
    LaunchedEffect(tab, shown) {
        if (shown == lastShown) return@LaunchedEffect
        lastShown = shown
        if (shown == null) typed.clear()
        if (shown == null || shown !in typed) value = textOf(shown)
    }
    val reportFocus by rememberUpdatedState(onFocusChange)
    DisposableEffect(Unit) { onDispose { reportFocus(false) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FluentTextBox(
            value = value,
            onValueChange = { new ->
                // A new text, or the same one just composed.
                val changed = new.text != value.text || value.composition != null
                value = new
                if (changed && new.composition == null) {
                    typed += new.text
                    onSearch(new.text)
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { reportFocus(it.isFocused) }
                .testTag(Tags.SEARCH_BOX),
            placeholder = searchHint(ui),
            onSubmit = {
                typed += value.text
                onSearchNow(value.text)
                keyboard?.hide()
            },
            submitAction = ImeAction.Search,
        )
        if (value.text.isNotEmpty()) {
            FluentButton(
                text = null,
                onClick = {
                    value = TextFieldValue()
                    onCancel()
                },
                modifier = Modifier.testTag(Tags.SEARCH_CLEAR),
                icon = FluentIcons.Close,
                style = ButtonStyle.SUBTLE,
                contentDescription = stringResource(R.string.cancel),
            )
        }
    }
}

/** [query] in the box, the cursor at its end. */
private fun textOf(query: String?): TextFieldValue = TextFieldValue(query.orEmpty(), TextRange(query.orEmpty().length))

/**
 * Where [dir] is, as the folders from its drive down (`Internal storage › DCIM › Camera`), [internalLabel]
 * naming the primary volume; the path itself for a folder on none of [volumes].
 */
internal fun folderPath(dir: Path, volumes: List<Volume>, internalLabel: String): String {
    val volume = volumeAt(volumes, dir)
    val root = volume?.root ?: return DisplayName.of(dir.toString())
    val names = root.relativize(dir).map { it.toString() }.filter { it.isNotEmpty() }.map(DisplayName::of)
    return (listOf(volumeLabel(volume, internalLabel)) + names).joinToString(" › ")
}
