/*
 * Audacity Android port — the main window: menus, toolbars and the editor
 * arranged per breakpoint (ui-reference.md §9.2-9.4), plus the dialog host.
 * Phones get the editor's MobileEditBar (✂ Split, edit commands, quick
 * effects) at the bottom, within the thumb's reach; tablets get it as a
 * bottom row too. Short windows merge rows so the tracks keep their height.
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.github.sakkijarvenpolkka.audacity.editor.MobileEditBar
import io.github.sakkijarvenpolkka.audacity.editor.SelectionToolbar
import io.github.sakkijarvenpolkka.audacity.editor.TimeToolbar
import io.github.sakkijarvenpolkka.audacity.editor.TransportToolbar
import io.github.sakkijarvenpolkka.audacity.editor.rememberEditorState
import io.github.sakkijarvenpolkka.audacity.menu.ContextMenus
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ChoiceDialog
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
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.QuickEffectsSheet
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.RecoveryDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ResampleDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.SaveChangesDialog
import io.github.sakkijarvenpolkka.audacity.ui.dialogs.ShareDialog
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

/**
 * Phone portrait below this height (system bars excluded, the keyboard
 * not): the Edit and Selection toolbars share a row, so that the tracks keep
 * about half of a small phone's height next to the edit bar.
 */
const val COMPACT_PORTRAIT_HEIGHT_DP: Float = 640f

/** Test tag of the row holding the editor's [MobileEditBar]. */
const val EDIT_BAR_ROW_TAG: String = "main:editBar"

/** Test tag of the editor area (tracks). */
const val EDITOR_AREA_TAG: String = "main:editor"

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
    return MenuState(
        snapshot, effects, settings, prefs.showClipping, prefs.showRms, vm.editor?.followPlayhead ?: true, recent,
        stopAtTrackEnd = prefs.stopAtTrackEnd, snapEnabled = prefs.snapEnabled, snapToGrid = prefs.snapToGrid,
        splitTool = prefs.splitTool,
    )
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
    override fun onQuickEffects() = vm.open(AppDialog.QuickEffects)
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
    // Stop at track end, snapping and the split tool: the preferences are applied to the
    // editor here once, the View-menu toggles change both at once (MenuSpec.editorPrefs), and
    // the editor's own changes (long-press ✂, the split-tool banner) are saved back. No
    // preferences → editor collector: a stale value would undo a newer change in the editor.
    LaunchedEffect(editorState) {
        vm.uiPrefs.value.applyTo(editorState)
        snapshotFlow { Triple(editorState.stopAtTrackEnd, editorState.snapping, editorState.tool) }.collect {
            vm.uiPrefs.update { p -> p.withEditor(editorState) }
        }
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

    val pal = LocalAudacityColors.current
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        // The system bars (edge-to-edge) show the toolbar colour
        containerColor = pal.medium,
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                e.type == KeyEventType.KeyDown && vm.onShortcut(e.key, e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, menuState)
            },
    ) { padding ->
        // The breakpoint and the compact rows follow the window without the keyboard:
        // an open keyboard (typing a selection time) must not move the focused field
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val stableHeight = maxHeight - with(density) { (bars.getTop(this) + bars.getBottom(this)).toDp() }
            val stableWidth = maxWidth - with(density) {
                (bars.getLeft(this, direction) + bars.getRight(this, direction)).toDp()
            }
            val kind = layoutKind(stableWidth.value, stableHeight.value)
            val openMenu: () -> Unit = { menuSheet = true }
            Box(Modifier.padding(padding).fillMaxSize().background(pal.medium)) {
                when (kind) {
                    LayoutKind.TABLET -> TabletLayout(vm, editorState, callbacks, menuState, onItem)
                    LayoutKind.PHONE_LANDSCAPE -> LandscapeLayout(vm, editorState, callbacks, menuState, onItem, openMenu, stableWidth)
                    LayoutKind.PHONE_PORTRAIT -> PortraitLayout(
                        vm, editorState, callbacks, menuState, onItem, openMenu,
                        compact = stableHeight.value < COMPACT_PORTRAIT_HEIGHT_DP,
                    )
                }
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
    Box(modifier.testTag(EDITOR_AREA_TAG).onSizeChanged { vm.editorHeightDp = it.height / density }) {
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
        IconButton(onClick = { overflow = true }, modifier = Modifier.testTag("top:more")) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.btn_more))
        }
        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
            OVERFLOW_ITEMS.forEach { id ->
                if (id == null) {
                    HorizontalDivider()
                    return@forEach
                }
                val item = MenuSpec.find(id, menuState) ?: return@forEach
                val checked = item.checked?.invoke(menuState)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(OVERFLOW_LABELS[id] ?: R.string.m_projects),
                            color = if (item.enabled(menuState.flags)) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    trailingIcon = if (checked != null) ({ if (checked) Icon(Icons.Filled.Check, null) }) else null,
                    onClick = {
                        overflow = false
                        onItem(item)
                    },
                    modifier = Modifier
                        .testTag("overflow:$id")
                        .then(
                            if (checked != null) Modifier.semantics {
                                role = Role.Checkbox
                                toggleableState = ToggleableState(checked)
                            } else Modifier,
                        ),
                )
            }
        }
    }
}

/** The phone overflow menu: frequent File items, then the editor toggles (null = divider). */
private val OVERFLOW_ITEMS = listOf(
    "Projects", "Save", "Export", "ShareAudio", "ImportAudio", "Preferences", "About", null,
    "Snapping", "StopAtTrackEnd", "SplitTool",
)

private val OVERFLOW_LABELS = mapOf(
    "Projects" to R.string.m_projects, "Save" to R.string.m_save, "Export" to R.string.m_export_audio,
    "ShareAudio" to R.string.m_share_audio, "ImportAudio" to R.string.m_import_audio_long,
    "Preferences" to R.string.m_preferences, "About" to R.string.m_about,
    "Snapping" to R.string.m_snapping, "StopAtTrackEnd" to R.string.m_stop_at_track_end, "SplitTool" to R.string.m_split_tool,
)

/** The editor's mobile edit bar as a full-width bottom row (navigation-bar insets come from the Scaffold). */
@Composable
private fun EditBarRow(vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, modifier: Modifier = Modifier) {
    Box(modifier.background(LocalAudacityColors.current.medium).testTag(EDIT_BAR_ROW_TAG)) {
        MobileEditBar(vm.engine, editorState, callbacks, Modifier.fillMaxWidth())
    }
}

@Composable
private fun PortraitLayout(
    vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, menuState: MenuState,
    onItem: (MenuItem) -> Boolean, openMenu: () -> Unit, compact: Boolean,
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
        if (compact) {
            // One row: both toolbars scroll sideways
            Row(Modifier.fillMaxWidth().background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { EditToolbar(vm.engine, editorState) }
                SelectionToolbar(vm.engine, Modifier.weight(1f), editorState)
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { EditToolbar(vm.engine, editorState) }
            SelectionToolbar(vm.engine, Modifier.fillMaxWidth(), editorState)
        }
        TransportToolbar(vm.engine, callbacks, Modifier.fillMaxWidth())
        EditBarRow(vm, editorState, callbacks, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LandscapeLayout(
    vm: AppViewModel, editorState: EditorState, callbacks: EditorCallbacks, menuState: MenuState,
    onItem: (MenuItem) -> Boolean, openMenu: () -> Unit, width: Dp,
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
        // The edit bar and the selection share the bottom row (the height is short)
        Row(Modifier.fillMaxWidth().background(pal.medium), verticalAlignment = Alignment.CenterVertically) {
            EditBarRow(vm, editorState, callbacks, Modifier.weight(1f))
            SelectionToolbar(vm.engine, Modifier.widthIn(max = width * 0.45f), editorState)
        }
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
            SelectionToolbar(vm.engine, Modifier.weight(1f), editorState)
        }
        EditBarRow(vm, editorState, callbacks, Modifier.fillMaxWidth())
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
                is AppDialog.Choice -> ChoiceDialog(d, vm)
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
                AppDialog.QuickEffects -> QuickEffectsSheet(d, vm, menuState, onItem)
                AppDialog.EffectMenu -> {
                    val menu = MenuSpec.menus.first { it.id == "Effect" }
                    ContextSheet(menu.label.text(), menu.children, menuState, onItem) { vm.dismiss(d) }
                }
                AppDialog.Share -> ShareDialog(d, vm)
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
