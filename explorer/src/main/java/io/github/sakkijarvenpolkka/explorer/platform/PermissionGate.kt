/*
 * Files — the all-files access the app needs before it shows anything else.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/** All files access (`MANAGE_EXTERNAL_STORAGE`). */
object PermissionGate {

    /** Whether the app has all files access now; checked again whenever the app comes back. */
    fun granted(): Boolean = Environment.isExternalStorageManager()

    /**
     * The settings screens that grant the access, best first: this app's own switch, the list
     * of all apps with the access, then this app's details. Try them in order until one starts
     * (a device may lack a screen: `ActivityNotFoundException`).
     */
    fun settingsIntents(packageName: String): List<Intent> {
        val app = Uri.fromParts("package", packageName, null)
        return listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        )
    }
}
