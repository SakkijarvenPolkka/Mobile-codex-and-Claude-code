/*
 * Files — the address bar: navigation buttons, the breadcrumb of the current folder and the
 * path typed in its place.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Overlay
import io.github.sakkijarvenpolkka.explorer.ui.fluent.Breadcrumb
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.Crumb
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTextBox
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MenuFlyout
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import java.nio.file.Path
import java.nio.file.Paths

/**
 * ☰ (with [showMenuButton]), ←, →, ↑ and the breadcrumb of the active tab's folder. The first
 * segment is the volume's name; its real path shows only while typing ([Overlay.AddressEdit]).
 * A segment's chevron asks for its subfolders ([onSubfolders]), listed while
 * [Overlay.Subfolders] is open.
 */
@Composable
fun AddressBar(
    ui: ExplorerUiState,
    showMenuButton: Boolean,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onNavigate: (Location) -> Unit,
    onSubfolders: (Path) -> Unit,
    onEdit: () -> Unit,
    onSubmit: (String) -> Unit,
    onCloseOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab = ui.tabs.active
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showMenuButton) BarButton(FluentIcons.Menu, stringResource(R.string.nav_pane), Tags.NAV_MENU, true, onMenu)
        BarButton(FluentIcons.Back, stringResource(R.string.back), Tags.BACK, tab.back.isNotEmpty(), onBack)
        BarButton(
            FluentIcons.Forward, stringResource(R.string.forward), Tags.FORWARD, tab.forward.isNotEmpty(), onForward,
        )
        BarButton(FluentIcons.Up, stringResource(R.string.go_up), Tags.UP, ui.up != null, onUp)
        Box(Modifier.weight(1f).testTag(Tags.BREADCRUMB)) {
            if (ui.overlay == Overlay.AddressEdit) {
                AddressEditor((ui.location as? Location.Folder)?.path?.toString().orEmpty(), onSubmit, onCloseOverlay)
            } else {
                val internal = stringResource(R.string.volume_internal)
                Breadcrumb(
                    crumbs = crumbsOf(ui, internal),
                    onCrumb = { onNavigate(Location.Folder(it.key as Path)) },
                    onChildren = { onSubfolders(it.key as Path) },
                    onEdit = onEdit,
                )
            }
            val subfolders = ui.overlay as? Overlay.Subfolders
            MenuFlyout(
                expanded = subfolders != null,
                onDismiss = onCloseOverlay,
                items = subfolders?.children.orEmpty().map { child ->
                    val label = DisplayName.of(child.name)
                    CommandItem(Tags.crumbChild(child.name), label, FluentIcons.Folder, enabled = true) {
                        onNavigate(Location.Folder(child.path))
                    }
                },
            )
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    FluentButton(
        text = null,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        icon = icon,
        style = ButtonStyle.SUBTLE,
        enabled = enabled,
        contentDescription = label,
    )
}

/** The real path, all selected, focused at once; Done calls [onSubmit], losing focus [onCancel]. */
@Composable
private fun AddressEditor(path: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(path, TextRange(0, path.length))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    FluentTextBox(
        value = value,
        onValueChange = { value = it },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) onCancel()
                focused = it.isFocused
            }
            .testTag(Tags.ADDRESS_EDIT),
        onSubmit = { onSubmit(value.text) },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The segments of the active tab's folder, each keyed by its path: the volume (by name, also
 * while it is not mounted), then each folder below its root.
 */
private fun crumbsOf(ui: ExplorerUiState, internalLabel: String): List<Crumb> {
    val path = (ui.location as? Location.Folder)?.path ?: return emptyList()
    val gone = ui.unavailable
    val root: Path
    val label: String
    if (gone != null) {
        val depth = if (gone.rel.isEmpty()) 0 else Paths.get(gone.rel).nameCount
        root = (0 until depth).fold(path) { at, _ -> at.parent ?: at }
        val volume = ui.volumes.firstOrNull { it.uuid == gone.uuid }
        label = volume?.let { volumeLabel(it, internalLabel) } ?: DisplayName.of(gone.uuid)
    } else {
        val volume = volumeAt(ui.volumes, path) ?: return listOf(Crumb(DisplayName.of(path.toString()), path))
        root = volume.root ?: path
        label = volumeLabel(volume, internalLabel)
    }
    val crumbs = mutableListOf(Crumb(label, root))
    var at = root
    for (name in root.relativize(path)) {
        val text = name.toString()
        if (text.isEmpty()) continue
        at = at.resolve(text)
        crumbs += Crumb(DisplayName.of(text), at)
    }
    return crumbs
}
