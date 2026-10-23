/*
 * Files — what the parts of the shell's view model (handing files over, the file operations) need
 * from it.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import java.nio.file.Path

/** The view model as its parts see it; main thread only. */
internal interface ShellHost {
    /** Where the active tab is. */
    fun here(): Location

    /**
     * Shows [message] about [at], where the active tab was when the user asked for what it
     * answers: not at all when the tab has left it since, and it goes when the tab leaves it.
     */
    fun showAt(at: Location, message: UiMessage)

    /** Shows [message] about no place: it stays until it is dismissed or replaced. */
    fun showUnbound(message: UiMessage)

    fun dismissMessage()

    /** Reads the active tab's folder again. */
    fun refresh()

    /** Reads again every tab at a location that is [affected]: the active one now, the others when shown. */
    fun refreshShowing(affected: (Location) -> Boolean)

    /** Selects exactly [paths] when the active tab is still at [at]. */
    fun selectAt(at: Location, paths: List<Path>)

    /** Asks the shell to start [launch] from the activity. */
    fun setLaunch(launch: PendingLaunch)
}

/** Shows [text] as an error about [at] ([ShellHost.showAt]). */
internal fun ShellHost.showError(at: Location, text: UiText) = showAt(at, UiMessage(text, InfoSeverity.ERROR))
