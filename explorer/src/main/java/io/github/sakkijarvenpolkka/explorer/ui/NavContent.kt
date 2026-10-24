/*
 * Files — the places of the navigation pane: Home, the favorites, the drives of This PC and the Recycle Bin.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.platform.Volume
import io.github.sakkijarvenpolkka.explorer.state.Location
import io.github.sakkijarvenpolkka.explorer.state.PinnedFolder
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavPane
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavSection
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import java.nio.file.Path

/**
 * The navigation pane: Home; the [favorites] under Favorites (when there are any); every volume under
 * This PC; then the Recycle Bin. A favorite or volume that is not mounted is dimmed with "The drive isn't
 * available". Home, the favorite the current folder is, else that folder's volume, or the Recycle Bin is
 * selected; choosing a volume goes to its root.
 */
@Composable
fun NavContent(
    volumes: List<Volume>,
    favorites: List<PinnedFolder>,
    location: Location,
    onNavigate: (Location) -> Unit,
    modifier: Modifier = Modifier,
) {
    val internal = stringResource(R.string.volume_internal)
    val unavailable = stringResource(R.string.volume_unavailable)
    // Keyed by their locations or by themselves, which no volume's uuid is.
    val home = NavItem(Location.Home, stringResource(R.string.nav_home), FluentIcons.Home)
    val pinned = favorites.map { folder ->
        NavItem(
            key = folder,
            label = folderLabel(folder.path, folder.favorite.label, volumes),
            icon = FluentIcons.Folder,
            enabled = folder.available,
            sublabel = if (folder.available) null else unavailable,
        )
    }
    val drives = volumes.map { volume ->
        NavItem(
            key = volume.uuid,
            label = volumeLabel(volume, internal),
            icon = volumeIcon(volume),
            enabled = volume.root != null,
            sublabel = if (volume.root == null) unavailable else null,
        )
    }
    val bin = NavItem(Location.RecycleBin, stringResource(R.string.nav_recycle_bin), FluentIcons.RecycleBin)
    val current: Any? = when (location) {
        is Location.Folder -> favorites.firstOrNull { it.available && it.path == location.path }
            ?: volumeAt(volumes, location.path)?.uuid
        Location.Home, Location.RecycleBin -> location
        is Location.SearchResults -> null
    }
    val sections = listOfNotNull(
        NavSection(null, listOf(home)),
        NavSection(stringResource(R.string.nav_favorites), pinned).takeIf { pinned.isNotEmpty() },
        NavSection(stringResource(R.string.nav_this_pc), drives),
        NavSection(null, listOf(bin)),
    )
    NavPane(
        sections = sections,
        selectedKey = current,
        onSelect = { item ->
            when (val key = item.key) {
                is Location -> onNavigate(key)
                is PinnedFolder -> onNavigate(Location.Folder(key.path))
                else -> volumes.firstOrNull { it.uuid == key }?.root?.let { onNavigate(Location.Folder(it)) }
            }
        },
        modifier = modifier.testTag(Tags.NAV_PANE),
    )
}

/** The name of [volume]: [internalLabel] for the primary volume, the system's label otherwise. */
internal fun volumeLabel(volume: Volume, internalLabel: String): String =
    if (volume.isPrimary) internalLabel else DisplayName.of(volume.label)

/** A drive for internal storage, a USB stick for a volume outside `/storage`, else an SD card for removable media. */
internal fun volumeIcon(volume: Volume): ImageVector = when {
    volume.isPrimary -> FluentIcons.Drive
    !volume.fuseBacked -> FluentIcons.Usb
    volume.isRemovable -> FluentIcons.SdCard
    else -> FluentIcons.Drive
}

/** The mounted volume whose root is the longest prefix of [path]. */
internal fun volumeAt(volumes: List<Volume>, path: Path): Volume? = volumes
    .filter { volume -> volume.root?.let { path.startsWith(it) } == true }
    .maxByOrNull { it.root?.nameCount ?: 0 }
