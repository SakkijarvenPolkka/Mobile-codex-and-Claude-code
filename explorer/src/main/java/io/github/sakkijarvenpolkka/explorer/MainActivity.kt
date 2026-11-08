/*
 * Files — the single activity: the permission screen until the app has all files access,
 * then the shell. Recreated on configuration changes (no configChanges in the manifest).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.PermissionGate
import io.github.sakkijarvenpolkka.explorer.platform.openAllFilesAccessSettings
import io.github.sakkijarvenpolkka.explorer.provider.DocumentsNotifier
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerShell
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerTheme
import io.github.sakkijarvenpolkka.explorer.ui.PermissionScreen
import io.github.sakkijarvenpolkka.explorer.ui.ThemedSystemBars
import io.github.sakkijarvenpolkka.explorer.ui.WidthClass
import io.github.sakkijarvenpolkka.explorer.ui.recheckAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Whether the app has all files access, checked again whenever the activity comes back. */
    private var granted by mutableStateOf(false)

    /** Created on first use, which is after the access is granted: nothing reads volumes before. */
    private val model = viewModels<ExplorerViewModel> {
        viewModelFactory {
            initializer {
                val app = application as ExplorerApp
                val mime = MimeResolver()
                // Files open in the built-in viewers, which the shell shows with the app's ExplorerApp.viewers.
                val deps = ExplorerDeps(
                    NioFsBackend, app.settings, app.volumes, app.volumes, mime, Dispatchers.IO, OpenWith(app, mime),
                    fileOps = app.fileOps, bin = app.bin, runner = app.runner,
                    opsNotice = app.opsNotice, clearOpsNotice = app::clearOpsNotice, thumbnails = app.thumbnails,
                    builtInViewers = true, openSaves = app.openSaves, foldersChanged = app::tellThePickerOf,
                )
                ExplorerViewModel(app, deps)
            }
        }
    }

    /** Asks for the notification permission; the answer changes nothing else. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = PermissionGate.granted()
        askForNotificationsOnFirstOperation()
        val app = application as ExplorerApp
        val settings = app.settings
        setContent {
            val global by settings.global.collectAsStateWithLifecycle()
            ThemedSystemBars(this, global.theme)
            ExplorerTheme(global) {
                if (granted) {
                    ExplorerShell(model.value, WidthClass.of(LocalConfiguration.current.screenWidthDp), app.viewers)
                } else {
                    PermissionScreen(onOpenSettings = { openAllFilesAccessSettings() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted = recheckAccess(model)
        // The access may have changed while the app was away, and the system file picker lists the app only with it.
        DocumentsNotifier.roots(this)
    }

    /**
     * Android 13 and later show the notification of the file operations only with the user's leave: it is
     * asked for once, when the first operation is submitted. The operations run whatever the answer.
     */
    private fun askForNotificationsOnFirstOperation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = application as ExplorerApp
        if (app.notificationsAsked) return
        lifecycleScope.launch {
            // Submitted by the user, in this activity: it is in the foreground.
            app.runner.state.first { it.running != null || it.queue.isNotEmpty() }
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (!app.notificationsAsked && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                app.notificationsAsked = true
                askNotifications.launch(permission)
            }
        }
    }
}
