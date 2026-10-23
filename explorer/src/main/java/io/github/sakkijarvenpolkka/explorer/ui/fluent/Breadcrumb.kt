/*
 * Files — the breadcrumb of the address bar: one button per path segment, a chevron after
 * each for its subfolders, and the empty space for typing a path.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** A path segment: what is shown and what identifies it to the caller. */
data class Crumb(val label: String, val key: Any)

/**
 * [crumbs] as `A › B › C ▾`. Tapping a segment calls [onCrumb]; the chevron after it calls
 * [onChildren] for that segment (a drop-down list named after it for accessibility
 * services); tapping the empty space calls [onEdit] (read as the whole path by accessibility
 * services). The row fills the width and, when the path is too long, scrolls with its end
 * (the current folder) in view.
 */
@Composable
fun Breadcrumb(crumbs: List<Crumb>, onCrumb: (Crumb) -> Unit, onChildren: (Crumb) -> Unit, onEdit: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.scrollTo(0) }
    val path = crumbs.joinToString(" › ") { it.label }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(interactionSource = null, indication = null, onClick = onEdit)
            .semantics { contentDescription = path },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.horizontalScroll(scroll, reverseScrolling = true), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { index, crumb ->
                key(crumb.key) {
                    FluentButton(text = crumb.label, onClick = { onCrumb(crumb) }, style = ButtonStyle.SUBTLE)
                    ButtonFrame(
                        onClick = { onChildren(crumb) },
                        modifier = Modifier,
                        style = ButtonStyle.SUBTLE,
                        enabled = true,
                        role = Role.DropdownList,
                        contentDescription = crumb.label,
                        iconOnly = true,
                    ) { content ->
                        val chevron = if (index == crumbs.lastIndex) FluentIcons.ChevronDown else FluentIcons.ChevronRight
                        FluentIcon(chevron, contentDescription = null, tint = content)
                    }
                }
            }
        }
    }
}
