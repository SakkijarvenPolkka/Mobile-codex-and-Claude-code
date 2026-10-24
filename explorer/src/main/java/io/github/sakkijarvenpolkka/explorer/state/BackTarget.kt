/*
 * Files — what the system Back gesture acts on.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

/** What Back closes or undoes, in priority order. */
enum class BackTarget { OVERLAY, SELECTION, DRAWER, HISTORY }

/**
 * What Back acts on: an open menu or dialog, then the selection, then the open drawer, then the
 * active tab's history. Null when there is nothing, so Back leaves the app (predictive back).
 */
fun backTarget(overlayOpen: Boolean, selectionActive: Boolean, drawerOpen: Boolean, canGoBack: Boolean): BackTarget? =
    when {
        overlayOpen -> BackTarget.OVERLAY
        selectionActive -> BackTarget.SELECTION
        drawerOpen -> BackTarget.DRAWER
        canGoBack -> BackTarget.HISTORY
        else -> null
    }

/** What Back acts on in [tab] with [shell] over it ([backTarget]): the viewer [viewed] counts as what is open. */
internal fun backOf(tab: TabState, shell: ShellState, viewed: Viewers.State): BackTarget? = backTarget(
    overlayOpen = shell.overlay != null || viewed.viewer != null,
    selectionActive = tab.selection.active,
    drawerOpen = shell.drawerOpen,
    canGoBack = tab.back.isNotEmpty(),
)
