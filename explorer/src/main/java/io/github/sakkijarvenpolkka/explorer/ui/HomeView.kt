/*
 * Files — Home (spec §2.4): quick access, the drives and how full they are, the files opened last and the
 * size of the Recycle Bin.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import android.content.res.Resources
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.data.Favorite
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.Command
import io.github.sakkijarvenpolkka.explorer.state.DEFAULT_FOLDERS
import io.github.sakkijarvenpolkka.explorer.state.DriveUsage
import io.github.sakkijarvenpolkka.explorer.state.ExplorerUiState
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.PinnedFolder
import io.github.sakkijarvenpolkka.explorer.state.QuickItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.CommandItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcon
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentProgressBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MenuFlyout
import io.github.sakkijarvenpolkka.explorer.ui.fluent.MinTouchTarget
import io.github.sakkijarvenpolkka.explorer.ui.fluent.rowHighlight
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.Formats
import java.nio.file.Path

/**
 * Home of [ui] ([ExplorerUiState.home]), a progress bar until it is first read: quick access (the
 * favorites, then the default folders there are), the drives, each with a bar of how full it is, the
 * recent files and, while it holds anything, the Recycle Bin's size; a section with nothing in it is left
 * out. A folder, a drive or the Recycle Bin opens with [onNavigate] (a favorite whose drive is out is
 * dimmed), a recent file with [onOpen]. A long press on a quick access folder opens its menu: Pin to
 * Favorites ([onPin]) for a default folder; Unpin ([onUnpin]), Move up and Move down ([onMoveFavorite],
 * from and to its place in [ExplorerUiState.favorites]) for a favorite.
 */
@Composable
fun HomeView(
    ui: ExplorerUiState,
    onNavigate: (Location) -> Unit,
    onOpen: (FileEntry) -> Unit,
    onPin: (Path) -> Unit,
    onUnpin: (Favorite) -> Unit,
    onMoveFavorite: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = ui.home
    val korean = LocalConfiguration.current.locales[0].language == "ko"
    val res = LocalResources.current
    if (home == null) {
        Box(modifier.fillMaxSize().testTag(Tags.HOME)) {
            FluentProgressBar(progress = null, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag(Tags.HOME)) {
        if (home.quickAccess.isNotEmpty()) {
            item(key = "quick") { Heading(stringResource(R.string.home_quick_access)) }
            items(home.quickAccess, key = { "quick:${it.path}" }) { item ->
                QuickRow(item, ui, onNavigate) { quickMenu(item, ui.favorites, res, onPin, onUnpin, onMoveFavorite) }
            }
        }
        if (home.drives.isNotEmpty()) {
            item(key = "drives") { Heading(stringResource(R.string.home_drives)) }
            items(home.drives, key = { "drive:${it.volume.uuid}" }) { drive ->
                DriveRow(drive, korean) { drive.volume.root?.let { onNavigate(Location.Folder(it)) } }
            }
        }
        if (home.recents.isNotEmpty()) {
            item(key = "recent") { Heading(stringResource(R.string.home_recent)) }
            items(home.recents, key = { "recent:${it.path}" }) { entry ->
                RecentRow(entry, ui.volumes) { onOpen(entry) }
            }
        }
        if (home.binSize > 0) {
            item(key = "bin") {
                HomeRow(onClick = { onNavigate(Location.RecycleBin) }, modifier = Modifier.testTag(Tags.HOME_BIN)) {
                    FluentIcon(FluentIcons.RecycleBin, contentDescription = null)
                    Label(stringResource(R.string.bin_size, Formats.size(home.binSize, korean)), Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * What a folder of quick access or of Favorites at [path] is called: a default folder of the primary
 * volume (one of [volumes]) by its name in the app's language, any other by [label].
 */
@Composable
internal fun folderLabel(path: Path, label: String, volumes: List<Volume>): String {
    val primary = volumes.firstOrNull { it.isPrimary }?.root
    val name = path.fileName?.toString()
    val own = DEFAULT_FOLDERS.firstOrNull { it.first == name }?.second
    return if (own != null && primary != null && path.parent == primary) stringResource(own) else DisplayName.of(label)
}

/** A section's heading. */
@Composable
private fun Heading(text: String) {
    BasicText(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = FluentTheme.type.bodyStrong.copy(color = FluentTheme.colors.text),
    )
}

/**
 * A folder of quick access, tagged by its name: dimmed, and opening nothing, while its drive is not mounted
 * (one of [ExplorerUiState.volumes]); a pinned one marked with a star. A long press opens [menu].
 */
@Composable
private fun QuickRow(
    item: QuickItem,
    ui: ExplorerUiState,
    onNavigate: (Location) -> Unit,
    menu: () -> List<CommandItem>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val available = volumeAt(ui.volumes, item.path) != null
    val colors = FluentTheme.colors
    val content = if (available) colors.text else colors.textDisabled
    Box {
        HomeRow(
            onClick = { if (available) onNavigate(Location.Folder(item.path)) },
            onLongClick = { menuOpen = true },
            modifier = Modifier
                .semantics { if (!available) disabled() }
                .testTag(Tags.quickItem((item.path.fileName ?: item.path).toString())),
        ) {
            FluentIcon(FluentIcons.Folder, contentDescription = null, tint = if (available) FolderTint else content)
            Column(Modifier.weight(1f)) {
                Label(folderLabel(item.path, item.label, ui.volumes), color = content)
                if (!available) Secondary(stringResource(R.string.volume_unavailable))
            }
            if (item.pinned) FluentIcon(FluentIcons.Star, stringResource(R.string.nav_favorites), tint = content)
        }
        MenuFlyout(expanded = menuOpen, onDismiss = { menuOpen = false }, items = if (menuOpen) menu() else emptyList())
    }
}

/**
 * The menu of [item]: Pin to Favorites for a default folder; for a favorite, Unpin, Move up and Move
 * down, by its place in [favorites] (each disabled when there is nowhere to go).
 */
private fun quickMenu(
    item: QuickItem,
    favorites: List<PinnedFolder>,
    res: Resources,
    onPin: (Path) -> Unit,
    onUnpin: (Favorite) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
): List<CommandItem> {
    if (!item.pinned) {
        return listOf(
            CommandItem(Tags.contextCommand(Command.PIN), res.getString(R.string.cmd_pin), FluentIcons.Star, true) {
                onPin(item.path)
            },
        )
    }
    val at = favorites.indexOfFirst { it.path == item.path }
    val favorite = favorites.getOrNull(at)?.favorite
    val unpin = res.getString(R.string.cmd_unpin)
    val up = res.getString(R.string.favorite_move_up)
    val down = res.getString(R.string.favorite_move_down)
    return listOf(
        CommandItem(Tags.contextCommand(Command.UNPIN), unpin, FluentIcons.Star, enabled = favorite != null) {
            favorite?.let(onUnpin)
        },
        CommandItem(Tags.FAVORITE_UP, up, null, enabled = at > 0) { onMove(at, at - 1) },
        CommandItem(Tags.FAVORITE_DOWN, down, null, enabled = at in 0 until favorites.lastIndex) { onMove(at, at + 1) },
    )
}

/** A drive, tagged by its uuid: its name, a bar filled as far as it is used, and "N free of M". */
@Composable
private fun DriveRow(drive: DriveUsage, korean: Boolean, onClick: () -> Unit) {
    val used = (drive.total - drive.free).coerceIn(0, drive.total).toFloat() / drive.total
    HomeRow(onClick = onClick, modifier = Modifier.testTag(Tags.drive(drive.volume.uuid))) {
        FluentIcon(volumeIcon(drive.volume), contentDescription = null)
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(volumeLabel(drive.volume, stringResource(R.string.volume_internal)))
            FluentProgressBar(progress = used, modifier = Modifier.fillMaxWidth())
            val free = Formats.size(drive.free, korean)
            Secondary(stringResource(R.string.drive_free, free, Formats.size(drive.total, korean)))
        }
    }
}

/** A recent file, tagged by its name, with the folder it is in (a volume's root by the volume's name). */
@Composable
private fun RecentRow(entry: FileEntry, volumes: List<Volume>, onClick: () -> Unit) {
    val folder = entry.path.parent
    val volume = folder?.let { volumeAt(volumes, it) }?.takeIf { it.root == folder }
    val where = volume?.let { volumeLabel(it, stringResource(R.string.volume_internal)) }
        ?: DisplayName.of((folder?.fileName ?: folder ?: entry.path).toString())
    HomeRow(onClick = onClick, modifier = Modifier.testTag(Tags.recent(entry.name))) {
        EntryIcon(entry, Modifier)
        Column(Modifier.weight(1f)) {
            Name(DisplayName.of(entry.name))
            Secondary(where)
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, color: Color = FluentTheme.colors.text) {
    BasicText(
        text,
        modifier = modifier,
        style = FluentTheme.type.body.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A full-width row of Home, at least 48 dp high, that [onClick] opens and, given [onLongClick], long-presses;
 * pressing shows a darker fill instead of a ripple.
 */
@Composable
private fun HomeRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .rowHighlight(selected = false, pressed = pressed, hovered = hovered, colors = colors)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
