/*
 * Files — the preview pane of a wide window: the one file selected, small, in its viewer (spec §2.2).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme

/**
 * [entry] in its viewer ([previewKindFor]) inside the pane, with no title row nor window insets: the
 * shell around it has those. Without an entry the pane asks for a file to be selected; a file no viewer
 * shows says so. [onOpenWith] is the "Open with" of a file its viewer cannot show after all. Each file
 * gets a viewer of its own, so that nothing of the last one (its zoom, its player) stays.
 */
@Composable
fun PreviewPane(
    entry: FileEntry?,
    deps: ViewerDeps,
    modifier: Modifier = Modifier,
    onOpenWith: ((FileEntry) -> Unit)? = null,
) {
    val colors = FluentTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .background(colors.layer)
            .drawBehind {
                // A line on the side that faces the list.
                val x = if (rtl) size.width else 0f
                drawLine(colors.stroke, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .testTag(ViewerTags.PANE),
    ) {
        if (entry == null) {
            ViewerNotice(stringResource(R.string.preview_none), onOpenWith = null)
        } else {
            key(entry.path) {
                val open = onOpenWith?.let { open -> { open(entry) } }
                ViewerContent(entry, listOf(entry), deps, onClose = {}, onOpenWith = open)
            }
        }
    }
}
