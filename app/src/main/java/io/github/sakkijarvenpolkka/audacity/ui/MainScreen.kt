/*
 * Audacity Android port — the main window: menus, toolbars and the editor
 * arranged per breakpoint (ui-reference.md §9.2-9.4), plus the dialog host.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppScreen
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.HostRequest
import io.github.sakkijarvenpolkka.audacity.OpenPurpose
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.editor.ContextTarget
import io.github.sakkijarvenpolkka.audacity.editor.EditToolbar
import io.github.sakkijarvenpolkka.audacity.editor.EditorCallbacks
import io.github.sakkijarvenpolkka.audacity.editor.EditorScreen
import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.editor.LocalAudacityColors
import io.github.sakkijarvenpolkka.audacity.editor.MeterToolbar
import io.github.sakkijarvenpolkka.audacity.editor.SelectionToolbar
import io.github.sakkijarvenpolkka.audacity.editor.TimeToolbar
import io.github.sakkijarvenpolkka.audacity.editor.TransportToolbar
import io.github.sakkijarvenpolkka.audacity.editor.rememberEditorState
import io.github.sakkijarvenpolkka.audacity.menu.ContextMenus
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ConfirmDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ContrastDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.DeviceInfoDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.EffectDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.EngineDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.EngineProgressDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ExportDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.HistoryDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.InfoDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.LabelEditorDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.LocalProgressDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.PlotSpectrumDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.RecoveryDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ResampleDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.SaveChangesDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.TagsDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.TextInputDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.TimeInputDialog
import io.github.sakkijarvenpolkka.audacity.ui.screens.AboutScreen
import io.github.sakkijarvenpolkka.audacity.ui.screens.LogScreen
import io.github.sakkijarvenpolkka.audacity.ui.screens.PreferencesScreen
import io.github.sakkijarvenpolkka.audacity.ui.screens.ProjectsScreen
import io.github.sakkijarvenpolkka.audacity.util.UiText
import io.github.sakkijarvenpolkka.audacity.util.text

/** Breakpoints of ui-reference.md §9. */
enum class LayoutKind { PHONE_PORTRAIT, PHONE_LANDSCAPE, TABLET }

fun layoutKind(widthDp: Float, heightDp: Float): LayoutKind = when {
    widthDp >= 840f -> LayoutKind.TABLET
    widthDp > heightDp && widthDp >= 560f -> LayoutKind.PHONE_LANDSCAPE
    else -> LayoutKind.PHONE_PORTRAIT
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val holder = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            AppScreen.EDITOR -> holder.SaveableStateProvider("editor") { EditorShell(vm, snackbar) }
            AppScreen.PROJECTS -> ProjectsScreen(vm)
            AppScreen.PREFERENCES -> PreferencesScreen(vm)
            AppScreen.ABOUT -> AboutScreen(vm)
            AppScreen.LOG -> LogScreen(vm)
        }
        if (vm.screen != AppScreen.EDITOR) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
    DialogHost(vm)
}

/** Menu state from the engine snapshot and app state. */
@Composable
fun rememberMenuState(vm: AppViewModel): MenuState {
    val snapshot by vm.engine.snapshot.collectAsState()
    val effects by vm.effectList.collectAsState()
    val settings by vm.settings.collectAsState()
    val prefs by vm.uiPrefs.state.collectAsState()
    val recent by vm.recentProjects.collectAsState()
    return MenuState(snapshot, effects, settings, prefs.showClipping, prefs.showRms, vm.editor?.followPlayhead ?: true, recent)
}

private class Callbacks(private val vm: AppViewModel) : EditorCallbacks {
    override fun onTrackMenu(trackId: Long) = vm.open(AppDialog.TrackMenu(trackId))
    override fun onContextMenu(target: ContextTarget) = vm.open(AppDialog.Context(target))
    override fun onRecord(newTrack: Boolean) = vm.record(newTrack)
    override fun onEditLabel(trackId: Long, index: Int) {
        vm.launchAction { ContextMenus.editLabelText(vm, trackId, index) }
    }
    override fun onMessage(text: String) = vm.message(UiText.Raw(text))
    override fun onRenameClip(trackId: Long, clipIndex: Int, generation: Long) {
        vm.launchAction {
            val clip = vm.engine.snapshot.value.track(trackId)?.clips?.getOrNull(clipIndex)
            val name = vm.askText(UiText.Res(R.string.m_rename_clip), UiText.Res(R.string.clip_name), clip?.name ?: "") ?: return@launchAction
            vm.engine.renameClip(trackId, clipIndex, generation, name)
        }
    }
}

@Composable
private fun EditorShell(vm: AppViewModel, snackbar: SnackbarHostState) {
    val editorState = rememberEditorState()
    val prefs by vm.uiPrefs.state.collectAsState()
    SideEffect { vm.editor = editorState }
    LaunchedEffect(editorState, prefs.showClipping, prefs.showRms) {
        editorState.showClipping = prefs.showClipping
        editorState.showRms = prefs.showRms
    }
    val callbacks = remember(vm) { Callbacks(vm) }
    val menuState = rememberMenuState(vm)
    val focus = remember { FocusRequester() }
    var menuSheet by remember { mutableStateOf(false) }
    var menuTab by remember { mutableIntStateOf(0) }
    val onItem: (MenuItem) -> Boolean = { item ->
        vm.onMenuItem(item, menuState)
        item.enabled(menuState.flags)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                e.type == KeyEventType.KeyDown && vm.onShortcut(e.key, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, menuState)
            },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(LocalAudacityColors.current.medium)) {
            val kind = layoutKind(maxWidth.value, maxHeight.value)
            val openMenu: () -> Unit = { menuSheet = true }
            when (kind) {
                LayoutKind.TABLET -> TabletLayout(vm, editorState, callbacks, menuState, onItem)
                LayoutKind.PHONE_LANDSCAPE -> LandscapeLayout(vm, editorState, callbacks, menuState, onItem, openMenu)
                LayoutKind.PHONE_PORTRAIT -> PortraitLayout(vm, editorState, callbacks, menuState, onItem, openMenu)
            }
        }
    }
    if (menuSheet) {
        MenuSheet(MenuSpec.menus, menuState, menuTab, { menuTab = it }, onItem) { menuSheet = false }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun EditorArea(vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, modifier: Modifier) {
    val density = LocalDensity.current.density
    val snapshot by vm.engine.snapshot.collectAsState()
    Box(modifier.onSizeChanged { vm.editorHeightDp = it.height / density }) {
        EditorScreen(vm.engine, editorState, callbacks, Modifier.fillMaxSize())
        if (!snapshot.project.open) NoProjectPanel(vm, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun NoProjectPanel(vm: AppViewModel, modifier: Modifier) {
    Surface(modifier.padding(24.dp).widthIn(max = 420.dp), shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.no_project), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.launchAction { vm.newProject() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pm_new)) }
            OutlinedButton(onClick = { vm.show(AppScreen.PROJECTS) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.m_projects)) }
            OutlinedButton(onClick = {
                vm.request(HostRequest.OpenDocuments(OpenPurpose.OPEN, listOf("*/*"), multiple = true))
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.m_open)) }
        }
    }
}

@Composable
private fun projectTitle(vm: AppViewModel): String {
    val snapshot by vm.engine.snapshot.collectAsState()
    val p = snapshot.project
    if (!p.open) return stringResource(R.string.app_name)
    return p.name.ifEmpty { stringResource(R.string.untitled) } + if (p.dirty) "*" else ""
}

/** Undo / Redo buttons and the overflow menu of the phone top bar. */
@Composable
private fun TopBarActions(vm: AppViewModel, menuState: MenuState, onItem: (MenuItem) -> Boolean) {
    val undo = remember { MenuSpec.find("Undo") }
    val redo = remember { MenuSpec.find("Redo") }
    undo?.let { u ->
        IconButton(onClick = { onItem(u) }, enabled = true, modifier = Modifier.testTag("top:undo")) {
            Icon(AppIcons.Undo, stringResource(R.string.m_undo),
                tint = if (u.enabled(menuState.flags)) LocalAudacityColors.current.glyph else LocalAudacityColors.current.glyphDisabled)
        }
    }
    redo?.let { r ->
        IconButton(onClick = { onItem(r) }, modifier = Modifier.testTag("top:redo")) {
            Icon(AppIcons.Redo, stringResource(R.string.m_redo),
                tint = if (r.enabled(menuState.flags)) LocalAudacityColors.current.glyph else LocalAudacityColors.current.glyphDisabled)
        }
    }
    var overflow by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { overflow = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.btn_more)) }
        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
            listOf("Projects", "Save", "Export", "ImportAudio", "Preferences", "About").forEach { id ->
                val item = MenuSpec.find(id, menuState) ?: return@forEach
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(OVERFLOW_LABELS[id] ?: R.string.m_projects),
                            color = if (item.enabled(menuState.flags)) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    onClick = {
                        overflow = false
                        onItem(item)
                    },
                )
            }
        }
    }
}

private val OVERFLOW_LABELS = mapOf(
    "Projects" to R.string.m_projects, "Save" to R.string.m_save, "Export" to R.string.m_export_audio,
    "ImportAudio" to R.string.m_import_audio_long, "Preferences" to R.string.m_preferences, "About" to R.string.m_about,
)

@Composable
private fun PortraitLayout(
    vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, menuState: MenuState,
    onItem: (MenuItem) -> Boolean, openMenu: () -> Unit,
) {
    val pal = LocalAudacityColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openMenu, modifier = Modifier.testTag("top:menu")) { Icon(Icons.Filled.Menu, stringResource(R.string.menu_open)) }
            Text(
                projectTitle(vm), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = pal.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            )
            TopBarActions(vm, menuState, onItem)
        }
        Row(Modifier.fillMaxWidth().background(pal.medium).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TimeToolbar(vm.engine)
            Spacer(Modifier.width(6.dp))
            MeterToolbar(vm.engine, Modifier.weight(1f))
        }
        EditorArea(vm, editorState, callbacks, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { EditToolbar(vm.engine, editorState) }
        SelectionToolbar(vm.engine, Modifier.fillMaxWidth())
        TransportToolbar(vm.engine, callbacks, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LandscapeLayout(
    vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, menuState: MenuState,
    onItem: (MenuItem) -> Boolean, openMenu: () -> Unit,
) {
    val pal = LocalAudacityColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openMenu, modifier = Modifier.testTag("top:menu")) { Icon(Icons.Filled.Menu, stringResource(R.string.menu_open)) }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TransportToolbar(vm.engine, callbacks)
                EditToolbar(vm.engine, editorState)
                Spacer(Modifier.width(4.dp))
                TimeToolbar(vm.engine)
                Spacer(Modifier.width(4.dp))
                MeterToolbar(vm.engine, Modifier.width(240.dp))
            }
            TopBarActions(vm, menuState, onItem)
        }
        EditorArea(vm, editorState, callbacks, Modifier.weight(1f).fillMaxWidth())
        SelectionToolbar(vm.engine, Modifier.fillMaxWidth())
    }
}

@Composable
private fun TabletLayout(
    vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, menuState: MenuState,
    onItem: (MenuItem) -> Boolean,
) {
    val pal = LocalAudacityColors.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
            MenuBar(MenuSpec.menus, menuState, onItem, Modifier.weight(1f))
            Text(
                projectTitle(vm), Modifier.padding(horizontal = 12.dp).widthIn(max = 280.dp), color = pal.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().background(pal.medium).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportToolbar(vm.engine, callbacks)
            Spacer(Modifier.width(8.dp))
            EditToolbar(vm.engine, editorState)
            Spacer(Modifier.width(8.dp))
            MeterToolbar(vm.engine, Modifier.width(360.dp))
        }
        EditorArea(vm, editorState, callbacks, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
            TimeToolbar(vm.engine, Modifier.padding(4.dp))
            SelectionToolbar(vm.engine, Modifier.weight(1f))
        }
    }
}

/** Shows the dialog stack, engine dialogs and progress. */
@Composable
fun DialogHost(vm: AppViewModel) {
    val menuState = rememberMenuState(vm)
    val onItem: (MenuItem) -> Boolean = { item ->
        vm.onMenuItem(item, menuState)
        item.enabled(menuState.flags)
    }
    for (d in vm.dialogs.toList()) {
        key(d) {
            when (d) {
                is AppDialog.Info -> InfoDialog(d, vm)
                is AppDialog.Confirm -> ConfirmDialog(d, vm)
                is AppDialog.TextInput -> TextInputDialog(d, vm)
                is AppDialog.TimeInput -> TimeInputDialog(d, vm)
                is AppDialog.SaveChanges -> SaveChangesDialog(d, vm)
                is AppDialog.Recovery -> RecoveryDialog(d, vm)
                is AppDialog.Effect -> EffectDialog(d, vm)
                AppDialog.PlotSpectrum -> PlotSpectrumDialog(d, vm)
                AppDialog.Contrast -> ContrastDialog(d, vm)
                is AppDialog.Export -> ExportDialog(d, vm)
                AppDialog.Tags -> TagsDialog(d, vm)
                AppDialog.History -> HistoryDialog(d, vm)
                AppDialog.Resample -> ResampleDialog(d, vm)
                AppDialog.DeviceInfo -> DeviceInfoDialog(d, vm)
                AppDialog.LabelEditor -> LabelEditorDialog(d, vm)
                is AppDialog.Context -> ContextSheet(
                    ContextMenus.title(d.target, menuState.snapshot).text(),
                    ContextMenus.forTarget(d.target, menuState.snapshot, { vm.editor?.isSpectrogram(it) == true }, { id, on -> vm.editor?.setSpectrogram(id, on) }),
                    menuState, onItem,
                ) { vm.dismiss(d) }
                is AppDialog.TrackMenu -> ContextSheet(
                    menuState.snapshot.track(d.trackId)?.name ?: "",
                    ContextMenus.trackMenu(d.trackId, menuState.snapshot, { vm.editor?.isSpectrogram(it) == true }, { id, on -> vm.editor?.setSpectrogram(id, on) }),
                    menuState, onItem,
                ) { vm.dismiss(d) }
            }
        }
    }
    val pending by vm.engine.pendingDialogs.collectAsState()
    pending.firstOrNull()?.let { e -> key(e.id) { EngineDialog(e, vm) } }
    val progress by vm.engine.progress.collectAsState()
    progress.values.minByOrNull { it.id }?.let { p -> EngineProgressDialog(p, vm) }
    val local by vm.localProgress.collectAsState()
    local?.let { LocalProgressDialog(it) }
}
