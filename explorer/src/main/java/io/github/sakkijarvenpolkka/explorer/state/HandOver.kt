/*
 * Files — handing files to other apps (viewing, "Open with", sharing), prepared off the main
 * thread for the shell to start from the activity.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.state

import android.app.Application
import android.content.Intent
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.toFsError
import io.github.sakkijarvenpolkka.explorer.platform.APK_MIME
import io.github.sakkijarvenpolkka.explorer.platform.OpenResult
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import io.github.sakkijarvenpolkka.explorer.ui.errorText
import io.github.sakkijarvenpolkka.explorer.ui.fluent.InfoSeverity
import io.github.sakkijarvenpolkka.explorer.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** An [intent] for the shell to start, asked for while the active tab was [at]. */
internal class PendingLaunch(val intent: Intent, val at: Location)

/**
 * Hands files to other apps for the view model [shell], with the intents [ExplorerDeps.openWith]
 * makes, in [scope]; nothing is handed over without it.
 */
internal class HandOver(
    private val app: Application,
    private val deps: ExplorerDeps,
    private val scope: CoroutineScope,
    private val shell: ShellHost,
) {
    /** Opens the file [entry] in another app; an APK needs the user to let this app install apps first. */
    fun open(entry: FileEntry) = handOver(listOf(entry.path)) { openWith ->
        if (entry.mime == APK_MIME && openWith.needsInstallPermission()) {
            Prepared.Refused(OpenResult.NeedsInstallPermission)
        } else {
            Prepared.Launch(openWith.viewIntent(entry.path))
        }
    }

    /** "Open with": the system chooser for the file [entry], which asks every time. */
    fun openWith(entry: FileEntry) {
        if (entry.kind != EntryKind.FILE) return
        val title = app.getString(R.string.open_with_title)
        handOver(listOf(entry.path)) { Prepared.Launch(it.chooserIntent(entry.path, title)) }
    }

    /** Shares the files [paths] through the system share sheet. */
    fun share(paths: List<Path>) {
        if (paths.isEmpty()) return
        handOver(paths) { Prepared.Launch(Intent.createChooser(it.shareIntent(paths), null)) }
    }

    /**
     * Hands [files] to another app with the intent [prepare] makes, off the main thread, for the
     * shell to start ([ExplorerUiState.launch]). A file gone since its folder was read shows "not
     * found" and the folder is read again; one the FileProvider does not serve cannot be opened.
     */
    private fun handOver(files: List<Path>, prepare: (OpenWith) -> Prepared) {
        val openWith = deps.openWith ?: return
        val at = shell.here()
        scope.launch {
            val prepared = try {
                withContext(deps.io) {
                    if (files.all(deps.fs::exists)) prepare(openWith) else Prepared.Refused(OpenResult.NotFound)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.toFsError()
                if (error == FsError.Unsupported) Prepared.Refused(OpenResult.Unsupported) else Prepared.Failed(error)
            }
            when (prepared) {
                is Prepared.Launch -> shell.setLaunch(PendingLaunch(prepared.intent, at))
                is Prepared.Refused -> onOpenResult(prepared.result, at)
                is Prepared.Failed -> shell.showError(at, errorText(prepared.error))
            }
        }
    }

    /** What [result] of handing a file to another app, asked for at [at], tells the user. */
    fun onOpenResult(result: OpenResult, at: Location) {
        when (result) {
            OpenResult.Started -> Unit
            OpenResult.NoApp -> shell.showError(at, UiText.res(R.string.open_no_app))
            OpenResult.Unsupported -> shell.showError(at, UiText.res(R.string.open_unsupported))
            OpenResult.NotFound -> {
                shell.showError(at, UiText.res(R.string.error_not_found))
                if (shell.here() == at) shell.refresh()
            }
            OpenResult.NeedsInstallPermission -> {
                val openWith = deps.openWith ?: return
                val allow = UiText.res(R.string.permission_button) to {
                    shell.dismissMessage()
                    shell.setLaunch(PendingLaunch(openWith.installPermissionIntent(), at))
                }
                shell.showAt(at, UiMessage(UiText.res(R.string.install_permission), InfoSeverity.WARNING, allow))
            }
        }
    }

    /** What handing files to another app comes to before anything is started. */
    private sealed interface Prepared {
        class Launch(val intent: Intent) : Prepared
        class Refused(val result: OpenResult) : Prepared
        class Failed(val error: FsError) : Prepared
    }
}
