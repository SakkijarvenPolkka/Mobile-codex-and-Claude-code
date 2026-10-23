/*
 * Files — the shell: address bar, command bar, navigation pane, folder contents and status
 * bar, laid out for the window width, with the dialogs of the file operations; and the app theme
 * from the settings.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.GlobalSettings
import io.github.sakkijarvenpolkka.explorer.data.ThemeChoice
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.VolumeTraits
import io.github.sakkijarvenpolkka.explorer.ops.RunnerState
import io.github.sakkijarvenpolkka.explorer.platform.launch
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.ListingState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.Overlay
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentProgressBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.text

/** How wide the window is: [EXPANDED] from 840 dp, which pins the navigation pane. */
enum class WidthClass {
    COMPACT,
    EXPANDED,
    ;

    companion object {
        fun of(widthDp: Int): WidthClass = if (widthDp >= 840) EXPANDED else COMPACT
    }
}

/** Whether [choice] is dark, following the system for [ThemeChoice.SYSTEM]. */
@Composable
fun isDarkTheme(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

/** The Fluent theme of [global]: its light or dark choice, and the system accent colour when chosen (Android 12+). */
@Composable
fun ExplorerTheme(global: GlobalSettings, content: @Composable () -> Unit) {
    val accent = if (global.systemAccent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        colorResource(android.R.color.system_accent1_600)
    } else {
        null
    }
    FluentTheme(dark = isDarkTheme(global.theme), accent = accent, content = content)
}

/**
 * The file manager for [vm]: on [WidthClass.EXPANDED] the navigation pane is pinned at the
 * start, otherwise it is a drawer behind ☰. The bars keep clear of the system bars and the
 * display cutout; Back is handled only while the state has something for it to do. The
 * keyboard shortcuts ([shortcutFor]) are read here first, so the shell keeps the focus while
 * nothing is open over it; the intents the view model asks for are started from here, from
 * the activity. The file operations show from the runner's state ([OperationsDialogs]), which
 * outlives the activity.
 */
@Composable
fun ExplorerShell(vm: ExplorerViewModel, widthClass: WidthClass) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val runner by vm.runner.collectAsStateWithLifecycle()
    val expanded = widthClass == WidthClass.EXPANDED
    BackHandler(enabled = ui.backTarget != null, onBack = vm::systemBack)
    LaunchedEffect(expanded) { if (expanded) vm.setDrawerOpen(false) }
    val positions = remember(vm) { ListPositions(vm::scrollOf) }
    val context = LocalContext.current
    val pending = ui.launch
    LaunchedEffect(pending) { if (pending != null) vm.launched(pending, launch(context, pending)) }
    // Keys reach only what has the focus: the shell takes it back when what had it is gone.
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // The shell itself, not a button or row in it, has the focus.
    var focused by remember { mutableStateOf(false) }
    val nothingOpen = ui.overlay == null
    LaunchedEffect(nothingOpen) { if (nothingOpen && !hasFocus) focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background)
            .onPreviewKeyEvent { event ->
                // The address box is the shell's only text field.
                val action = shortcutFor(event, textFieldFocused = ui.overlay == Overlay.AddressEdit)
                action != null && vm.onShortcut(action, ui, shellFocused = focused)
            }
            .onFocusChanged {
                hasFocus = it.hasFocus
                focused = it.isFocused
            }
            .focusRequester(focus)
            .focusTarget()
            .testTag(Tags.SHELL),
    ) {
        val sides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        Column(Modifier.fillMaxSize().then(sides)) {
            // The tab strip goes above the address bar once tabs are shown.
            AddressBar(
                ui = ui,
                showMenuButton = !expanded,
                onMenu = { vm.setDrawerOpen(true) },
                onBack = vm::back,
                onForward = vm::forward,
                onUp = vm::up,
                onNavigate = vm::navigate,
                onSubfolders = vm::showSubfolders,
                onEdit = vm::editAddress,
                onSubmit = vm::typedPath,
                onCloseOverlay = vm::closeOverlay,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            )
            CommandsUi(ui, onCommand = vm::command, onSetView = vm::setView, onSetGlobal = vm::setGlobal)
            Row(Modifier.weight(1f)) {
                if (expanded) {
                    NavContent(ui.volumes, ui.location, vm::navigate, Modifier.width(NAV_WIDTH).fillMaxHeight())
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Banners(ui, onDismiss = vm::dismissMessage)
                    Contents(ui, widthClass, positions, vm, Modifier.weight(1f))
                }
            }
            StatusBar(
                ui,
                onSetMode = { mode -> vm.setView(ui.viewSetting.copy(mode = mode)) },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                running = runner.running,
                onShowProgress = vm::showProgress,
            )
        }
        if (!expanded && ui.drawerOpen) {
            Drawer(ui, onNavigate = { vm.navigate(it); vm.setDrawerOpen(false) }, onClose = { vm.setDrawerOpen(false) })
        }
    }
    when (val overlay = ui.overlay) {
        Overlay.Options -> OptionsDialog(ui.global, onChange = vm::setGlobal, onDismiss = vm::closeOverlay)
        is Overlay.Properties -> {
            val contents = remember(overlay) { vm.contentsOf(overlay.entries) }
            PropertiesDialog(overlay.entries, contents, onDismiss = vm::closeOverlay)
        }
        else -> Unit
    }
    OperationsDialogs(ui, runner, vm)
}

/**
 * The dialogs of the file operations, the later over the earlier: the conflict the running one waits
 * on, else its progress (unless it runs in the background); the failures of one that ended; the
 * rename dialog; and a question to answer, over the rename dialog it may come from.
 */
@Composable
private fun OperationsDialogs(ui: ExplorerUiState, runner: RunnerState, vm: ExplorerViewModel) {
    val running = runner.running
    val pending = runner.pending
    if (pending != null) {
        val progress = running?.progress
        // The items still to come, of which some may clash too.
        val remaining = progress?.let { (it.totalItems - it.doneItems - 1).coerceAtLeast(0) } ?: 0
        ConflictDialog(pending.conflict, pending.allowed, remaining, pending::answer) { vm.cancelOperation(pending.opId) }
    } else if (running != null && !ui.progressInBackground) {
        ProgressDialog(running, onBackground = vm::runInBackground) { vm.cancelOperation(running.spec.id) }
    }
    ui.failedOp?.let { op -> FailuresDialog(op, onRetry = { vm.retryFailed(op) }) { vm.dismissFailures(op) } }
    ui.renaming?.let { entry ->
        val traits = volumeAt(ui.volumes, entry.path)?.traits ?: VolumeTraits()
        RenameDialog(entry, traits, onCommit = { vm.commitRename(entry, it) }, onDismiss = vm::cancelRename)
    }
    ui.confirm?.let { ConfirmDialog(it, onAnswer = vm::answerConfirm) }
}

/**
 * Does what the shortcut [action] asks for in [ui]: false when it does nothing there (a command
 * not offered, nowhere to go), so that the key goes on to what has the focus. Enter opens the
 * one selected item only while the shell itself is [shellFocused]: a focused button, crumb or
 * row gets it first.
 */
private fun ExplorerViewModel.onShortcut(action: ShortcutAction, ui: ExplorerUiState, shellFocused: Boolean): Boolean {
    fun offered(c: Command): Boolean = (c in ui.commands).also { if (it) command(c) }
    fun can(possible: Boolean, act: () -> Unit): Boolean = possible.also { if (it) act() }
    return when (action) {
        ShortcutAction.COPY -> offered(Command.COPY)
        ShortcutAction.CUT -> offered(Command.CUT)
        ShortcutAction.PASTE -> offered(Command.PASTE)
        ShortcutAction.SELECT_ALL -> offered(Command.SELECT_ALL)
        ShortcutAction.DELETE -> offered(Command.DELETE)
        ShortcutAction.DELETE_PERMANENT -> offered(Command.DELETE_PERMANENT)
        ShortcutAction.RENAME -> offered(Command.RENAME)
        ShortcutAction.REFRESH -> offered(Command.REFRESH)
        ShortcutAction.OPEN -> shellFocused && ui.selected.size == 1 && offered(Command.OPEN)
        ShortcutAction.FOCUS_ADDRESS -> can(ui.location is Location.Folder) { editAddress() }
        ShortcutAction.BACK -> can(ui.backTarget != null) { systemBack() }
        ShortcutAction.FORWARD -> can(ui.tabs.active.forward.isNotEmpty()) { forward() }
        ShortcutAction.UP -> can(ui.up != null) { up() }
        // The tab strip and the preview pane come later.
        ShortcutAction.NEW_TAB, ShortcutAction.CLOSE_TAB, ShortcutAction.NEXT_TAB, ShortcutAction.PREVIEW_PANE -> false
    }
}

private val NAV_WIDTH = 240.dp

/** The message of the view model, then the restricted-folder warning. */
@Composable
private fun Banners(ui: ExplorerUiState, onDismiss: () -> Unit) {
    val message = ui.message
    if (message != null) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(Tags.MESSAGE)) {
            val action = message.action?.let { (label, run) -> label.text() to run }
            val text = listOfNotNull(message.text.text(), message.detail?.text()).joinToString("\n")
            InfoBar(message.severity, text, action, onClose = onDismiss)
        }
    }
    if (ui.restricted) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(Tags.RESTRICTED_BANNER)) {
            InfoBar(InfoSeverity.WARNING, stringResource(R.string.restricted_banner))
        }
    }
}

/** What the active tab's location shows: the unavailable drive, the listing error, or the list. */
@Composable
private fun Contents(
    ui: ExplorerUiState,
    widthClass: WidthClass,
    positions: ListPositions,
    vm: ExplorerViewModel,
    modifier: Modifier,
) {
    val listing = ui.listing
    Box(modifier.fillMaxWidth()) {
        when {
            ui.unavailable != null -> Note(stringResource(R.string.volume_unavailable), Tags.UNAVAILABLE)
            listing is ListingState.Error -> ListingError(listing.error, ui.up, onUp = vm::navigate)
            ui.location == Location.RecycleBin -> if (listing is ListingState.Loading) {
                LoadingBar()
            } else {
                RecycleBinView(ui, widthClass, onTap = vm::onTap, onLongPress = vm::onLongPress)
            }
            ui.location !is Location.Folder -> Unit
            listing is ListingState.Loading -> LoadingBar()
            else -> {
                FileList(
                    ui = ui,
                    widthClass = widthClass,
                    positions = positions,
                    onTap = vm::onTap,
                    onLongPress = vm::onLongPress,
                    onMore = vm::openContextMenu,
                    onDispatch = vm::dispatch,
                    onSort = { key -> vm.setView(ui.viewSetting.copy(sort = nextSort(ui.viewSetting.sort, key))) },
                    onScrolled = vm::onScrolled,
                    contextMenu = { EntryMenu(ui, onCommand = vm::command, onDismiss = vm::closeOverlay) },
                )
                if (listing is ListingState.Loaded && listing.refreshing) LoadingBar()
            }
        }
    }
}

@Composable
private fun LoadingBar() {
    FluentProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
}

/** Why the folder cannot be listed; for a folder that is gone, an Up button to [up] when there is one. */
@Composable
private fun ListingError(error: FsError, up: Location?, onUp: (Location) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(Tags.LISTING_ERROR),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Note(errorText(error).text(), tag = null)
        if (error == FsError.NotFound && up != null) FluentButton(stringResource(R.string.go_up), { onUp(up) })
    }
}

@Composable
private fun Note(text: String, tag: String?) {
    Box(
        Modifier.fillMaxWidth().padding(24.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val style = FluentTheme.type.body.copy(color = FluentTheme.colors.textSecondary, textAlign = TextAlign.Center)
        BasicText(text, style = style)
    }
}

/** The navigation pane over the shell on narrow windows; touching outside it closes it. */
@Composable
private fun Drawer(ui: ExplorerUiState, onNavigate: (Location) -> Unit, onClose: () -> Unit) {
    val title = stringResource(R.string.nav_pane)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(interactionSource = null, indication = null, onClick = onClose)
                .clearAndSetSemantics {},
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(DRAWER_WIDTH)
                .shadow(16.dp)
                .background(FluentTheme.colors.layer)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(DRAWER_INSETS))
                .semantics { paneTitle = title }
                .testTag(Tags.DRAWER),
        ) {
            NavContent(ui.volumes, ui.location, onNavigate = onNavigate, modifier = Modifier.fillMaxSize())
        }
    }
}

private val DRAWER_WIDTH = 280.dp
private val DRAWER_INSETS = WindowInsetsSides.Vertical + WindowInsetsSides.Start
private val SCRIM = Color(0x66000000)
