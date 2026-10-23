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
