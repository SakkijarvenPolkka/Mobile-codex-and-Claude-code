/*
 * Files — what the shell's view model keeps of the shell itself: what is open over it and the message shown.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

/**
 * What is open over the shell, the message and the location it is about ([messageAt], null
 * for one that stays until dismissed), a counter that moves whenever a folder's view changes,
 * the intent for the shell to start, and whether `Android/obb` is open to the app.
 */
internal data class ShellState(
    val overlay: Overlay? = null,
    val drawerOpen: Boolean = false,
    val message: UiMessage? = null,
    val messageAt: Location? = null,
    val viewRevision: Int = 0,
    val launch: PendingLaunch? = null,
    val obbAllowed: Boolean = false,
) {
    /** This state with the active tab at [location]: without a message about another place. */
    fun at(location: Location): ShellState =
        if (messageAt == null || messageAt == location) this else copy(message = null, messageAt = null)
}
