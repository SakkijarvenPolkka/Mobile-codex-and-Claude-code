/*
 * Files — the shell: address bar, command bar, navigation pane, folder contents and status
 * bar, laid out for the window width; and the app theme from the settings.
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
 * display cutout; Back is handled only while the state has something for it to do.
 */
@Composable
fun ExplorerShell(vm: ExplorerViewModel, widthClass: WidthClass) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val expanded = widthClass == WidthClass.EXPANDED
    BackHandler(enabled = ui.backTarget != null, onBack = vm::systemBack)
    LaunchedEffect(expanded) { if (expanded) vm.setDrawerOpen(false) }
    val positions = rememberListPositions()
    Box(Modifier.fillMaxSize().background(FluentTheme.colors.background).testTag(Tags.SHELL)) {
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
            )
        }
        if (!expanded && ui.drawerOpen) {
            Drawer(ui, onNavigate = { vm.navigate(it); vm.setDrawerOpen(false) }, onClose = { vm.setDrawerOpen(false) })
        }
    }
    if (ui.overlay == Overlay.Options) OptionsDialog(ui.global, onChange = vm::setGlobal, onDismiss = vm::closeOverlay)
}

private val NAV_WIDTH = 240.dp

/** The message of the view model, then the restricted-folder warning. */
@Composable
private fun Banners(ui: ExplorerUiState, onDismiss: () -> Unit) {
    val message = ui.message
    if (message != null) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag(Tags.MESSAGE)) {
            val action = message.action?.let { (label, run) -> label.text() to run }
            InfoBar(message.severity, message.text.text(), action, onClose = onDismiss)
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
    val text = when (error) {
        FsError.NotFound -> R.string.error_not_found
        FsError.VolumeRemoved -> R.string.volume_unavailable
        else -> R.string.error_access_denied
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(Tags.LISTING_ERROR),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Note(stringResource(text), tag = null)
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
