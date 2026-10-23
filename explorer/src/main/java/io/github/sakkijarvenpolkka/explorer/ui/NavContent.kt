/*
 * Files — the places of the navigation pane: the drives of This PC and the Recycle Bin.
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
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavItem
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavPane
import io.github.sakkijarvenpolkka.explorer.ui.fluent.NavSection
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import java.nio.file.Path

/**
 * The navigation pane: every volume under This PC, an unmounted one dimmed with "The drive
 * isn't available", then the Recycle Bin. The volume of the current folder, or the Recycle Bin,
 * is selected; choosing a volume goes to its root.
 */
@Composable
fun NavContent(
    volumes: List<Volume>,
    location: Location,
    onNavigate: (Location) -> Unit,
    modifier: Modifier = Modifier,
) {
    val internal = stringResource(R.string.volume_internal)
    val unavailable = stringResource(R.string.volume_unavailable)
    val drives = volumes.map { volume ->
        NavItem(
            key = volume.uuid,
            label = volumeLabel(volume, internal),
            icon = volumeIcon(volume),
            enabled = volume.root != null,
            sublabel = if (volume.root == null) unavailable else null,
        )
    }
    // Keyed by its location, which no volume's uuid is.
    val bin = NavItem(Location.RecycleBin, stringResource(R.string.nav_recycle_bin), FluentIcons.RecycleBin)
    val current = when (location) {
        is Location.Folder -> volumeAt(volumes, location.path)?.uuid
        Location.RecycleBin -> Location.RecycleBin
        else -> null
    }
    NavPane(
        sections = listOf(NavSection(stringResource(R.string.nav_this_pc), drives), NavSection(null, listOf(bin))),
        selectedKey = current,
        onSelect = { item ->
            if (item.key == Location.RecycleBin) return@NavPane onNavigate(Location.RecycleBin)
            volumes.firstOrNull { it.uuid == item.key }?.root?.let { onNavigate(Location.Folder(it)) }
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
