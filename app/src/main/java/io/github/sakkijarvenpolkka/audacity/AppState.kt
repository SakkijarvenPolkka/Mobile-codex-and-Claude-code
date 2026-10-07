/*
 * Audacity Android port — app-level UI state: dialogs, screens and requests
 * that only the Activity can fulfil (SAF pickers, runtime permissions).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import io.github.sakkijarvenpolkka.audacity.editor.ContextTarget
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.coroutines.CompletableDeferred

/** Full-screen destinations. */
enum class AppScreen { EDITOR, PROJECTS, PREFERENCES, ABOUT, LOG }

/** Answer of the "Save changes?" prompt. */
enum class SaveChoice { SAVE, DISCARD, CANCEL }

/** Dialogs and sheets shown above the current screen (a stack; the last one is on top). */
sealed interface AppDialog {
    /** Informational message (OK). */
    data class Info(val title: UiText, val message: UiText) : AppDialog

    /** Yes/No style confirmation; [result] gets true for [ok]. */
    class Confirm(
        val title: UiText,
        val message: UiText,
        val ok: UiText,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    ) : AppDialog

    /** One-line text input; [result] gets null when cancelled. */
    class TextInput(
        val title: UiText,
        val label: UiText,
        val initial: String,
        val result: CompletableDeferred<String?> = CompletableDeferred(),
        val allowEmpty: Boolean = false,
    ) : AppDialog

    /** Time input (hh:mm:ss.mmm); [result] gets null when cancelled. */
    class TimeInput(
        val title: UiText,
        val initial: Double,
        val result: CompletableDeferred<Double?> = CompletableDeferred(),
    ) : AppDialog

    /** "Save changes to %s?" — Yes / No / Cancel. */
    class SaveChanges(val projectName: String, val result: CompletableDeferred<SaveChoice> = CompletableDeferred()) : AppDialog

    /** Automatic crash recovery (AutoRecoveryDialog). */
    data class Recovery(val projects: List<ProjectFileEntry>) : AppDialog

    data class Effect(val effectId: String) : AppDialog
    data object PlotSpectrum : AppDialog
    data object Contrast : AppDialog
    data class Export(val selectionOnly: Boolean = false) : AppDialog
    data object Tags : AppDialog
    data object History : AppDialog
    data object Resample : AppDialog
    data object DeviceInfo : AppDialog
    data object LabelEditor : AppDialog

    /** Context menu for a long-press target of the editor. */
    data class Context(val target: ContextTarget) : AppDialog

    /** The track control panel menu (⋯). */
    data class TrackMenu(val trackId: Long) : AppDialog
}

/** Where a document picked with ACTION_OPEN_DOCUMENT goes. */
enum class OpenPurpose { OPEN, IMPORT_AUDIO, IMPORT_LABELS }

/** Export job collected by the export dialog (export.run arguments). */
data class ExportJob(
    val formatKey: String,
    val fileName: String,
    val range: String,
    val channels: Int,
    val rate: Int,
    val skipSilenceAtStart: Boolean,
)

/** What to write into a document created with ACTION_CREATE_DOCUMENT. */
sealed interface CreatePurpose {
    data class ExportAudio(val job: ExportJob) : CreatePurpose
    data object BackupProject : CreatePurpose
    data class ExportLabels(val text: String) : CreatePurpose
}

/** Requests the Activity fulfils with activity-result launchers / intents. */
sealed interface HostRequest {
    data class OpenDocuments(val purpose: OpenPurpose, val mimeTypes: List<String>, val multiple: Boolean) : HostRequest
    data class CreateDocument(val purpose: CreatePurpose, val suggestedName: String, val mimeType: String) : HostRequest
    data class RecordPermission(val newTrack: Boolean) : HostRequest
    data class OpenUrl(val url: String) : HostRequest
    data object AppLanguageSettings : HostRequest
}

/** Progress of an app-side operation (SAF copy). */
data class LocalProgress(val title: UiText, val fraction: Double, val cancel: () -> Unit)
