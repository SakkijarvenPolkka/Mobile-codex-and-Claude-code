/*
 * Files — USB drives that Android mounts for itself outside `/storage`, which this process can read only
 * when it started with all files access, and starting the app again.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.content.Intent
import android.os.Process
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.VolumeLookup
import io.github.sakkijarvenpolkka.explorer.util.UiText
import java.nio.file.Path
import java.nio.file.Paths

/** Where Android mounts a USB drive for itself, outside `/storage`. */
private val MEDIA_RW: Path = Paths.get("/mnt/media_rw")

/**
 * Whether [folder] is on a volume outside `/storage` (a USB drive at `/mnt/media_rw/<uuid>`), which this
 * process cannot read when it started before it had all files access.
 */
internal fun VolumeLookup.isOnRawMount(folder: Path): Boolean = volumeOf(folder)?.root?.startsWith(MEDIA_RW) == true

/** The offer to start [app] again ([restartApp]), to read a USB drive this process may not ([isOnRawMount]). */
internal fun restartOffer(app: Application): UiMessage {
    val restart = UiText.res(R.string.restart) to { restartApp(app) }
    return UiMessage(UiText.res(R.string.usb_restart), MessageSeverity.WARNING, restart)
}

/** Starts [app] again in a new process, which has all files access from its start. */
internal fun restartApp(app: Application) {
    val component = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component ?: return
    app.startActivity(Intent.makeRestartActivityTask(component))
    Process.killProcess(Process.myPid())
}
