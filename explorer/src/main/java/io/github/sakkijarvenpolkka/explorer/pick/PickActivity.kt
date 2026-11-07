/*
 * Files — picking files for another app (`ACTION_GET_CONTENT`, pick spec §2): the permission screen until the
 * app has all files access, then the shell picking the files asked for, which answers with them or with nothing.
 * Recreated on configuration changes (no configChanges in the manifest).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.pick

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.sakkijarvenpolkka.explorer.ExplorerApp
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.platform.PermissionGate
import io.github.sakkijarvenpolkka.explorer.platform.openAllFilesAccessSettings
import io.github.sakkijarvenpolkka.explorer.state.ExplorerDeps
import io.github.sakkijarvenpolkka.explorer.state.ExplorerViewModel
import io.github.sakkijarvenpolkka.explorer.state.PickOutcome
import io.github.sakkijarvenpolkka.explorer.state.PickRequest
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerShell
import io.github.sakkijarvenpolkka.explorer.ui.ExplorerTheme
import io.github.sakkijarvenpolkka.explorer.ui.PermissionScreen
import io.github.sakkijarvenpolkka.explorer.ui.ThemedSystemBars
import io.github.sakkijarvenpolkka.explorer.ui.WidthClass
import io.github.sakkijarvenpolkka.explorer.ui.recheckAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.nio.file.Path

/**
 * Picks files for the app that started it with `ACTION_GET_CONTENT`, of the types it takes, one or several as it
 * asks ([PickRequest.of]), in the shell of its own view model, which changes no file. It answers `RESULT_OK` with
 * the files chosen, for that app to read ([pickResultIntent]), or `RESULT_CANCELED`.
 */
class PickActivity : ComponentActivity() {

    /** Whether the app has all files access, checked again whenever the activity comes back. */
    private var granted by mutableStateOf(false)

    private val mime = MimeResolver()

    private val openWith by lazy { OpenWith(application, mime) }

    /**
     * The content URI the app that asked gets for a file chosen ([OpenWith.uriFor]); a test puts its own, as the
     * FileProvider serves only the storage volumes.
     */
    internal var uriFor: (Path) -> Uri = { openWith.uriFor(it) }

    /**
     * Created on first use, which is after the access is granted: nothing reads volumes before. Read only, it has no
     * file operations, nor their runner, nor the Recycle Bin: the app's own operations show nothing over the app that
     * asked. Files are chosen, never opened, so there are no viewers either.
     */
    private val model = viewModels<ExplorerViewModel> {
        viewModelFactory {
            initializer {
                val app = application as ExplorerApp
                val request = PickRequest.of(
                    intent.type,
                    intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
                    intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                )
                val deps = ExplorerDeps(
                    NioFsBackend, app.settings, app.volumes, app.volumes, mime, Dispatchers.IO, openWith,
                    opsNotice = app.opsNotice, clearOpsNotice = app::clearOpsNotice, thumbnails = app.thumbnails,
                    pick = request,
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
            ThemedSystemBars(this, global.theme)
            ExplorerTheme(global) {
                if (granted) {
                    val vm = model.value
                    ExplorerShell(vm, WidthClass.of(LocalConfiguration.current.screenWidthDp))
                    LaunchedEffect(vm) { answer(vm.ui.mapNotNull { it.pick?.outcome }.first()) }
                } else {
                    PermissionScreen(onOpenSettings = { openAllFilesAccessSettings() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted = recheckAccess(model)
    }

    /** Answers the app that asked with how picking ended: the files chosen, or nothing. */
    private fun answer(outcome: PickOutcome) {
        when (outcome) {
            is PickOutcome.Chosen -> {
                val result = pickResultIntent(outcome.paths, uriFor) { mime.mimeOf(it.fileName.toString()) }
                setResult(RESULT_OK, result)
            }
            PickOutcome.Cancelled -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
