/*
 * Files — the single activity: the permission screen until the app has all files access,
 * then the shell. Recreated on configuration changes (no configChanges in the manifest).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.PermissionGate
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerShell
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerTheme
import io.github.sakkijarvenpolkka.explorer.ui.PermissionScreen
import io.github.sakkijarvenpolkka.explorer.ui.WidthClass
import io.github.sakkijarvenpolkka.explorer.ui.isDarkTheme
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {

    /** Whether the app has all files access, checked again whenever the activity comes back. */
    private var granted by mutableStateOf(false)

    /** Created on first use, which is after the access is granted: nothing reads volumes before. */
    private val model = viewModels<ExplorerViewModel> {
        viewModelFactory {
            initializer {
                val app = application as ExplorerApp
                val mime = MimeResolver()
                val deps = ExplorerDeps(
                    NioFsBackend, app.settings, app.volumes, app.volumes, mime, Dispatchers.IO, OpenWith(app, mime),
                )
                ExplorerViewModel(app, deps)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = PermissionGate.granted()
        val settings = (application as ExplorerApp).settings
        setContent {
            val global by settings.global.collectAsStateWithLifecycle()
            val dark = isDarkTheme(global.theme)
            DisposableEffect(dark) {
                // The system bar icons follow the app theme, which may differ from the system's.
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            ExplorerTheme(global) {
                if (granted) {
                    ExplorerShell(model.value, WidthClass.of(LocalConfiguration.current.screenWidthDp))
                } else {
                    PermissionScreen(onOpenSettings = ::openAllFilesAccessSettings)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted = PermissionGate.granted()
        // A view model created for this activity has just read everything; one that was already there reads again.
        if (granted && model.isInitialized()) model.value.onResume()
    }

    /** The first of the settings screens that grant all files access that this device has. */
    private fun openAllFilesAccessSettings() {
        for (intent in PermissionGate.settingsIntents(packageName)) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // This device lacks that screen; try the next one.
            }
        }
    }

    private companion object {
        /** The scrims `enableEdgeToEdge` puts behind three-button navigation by default. */
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
