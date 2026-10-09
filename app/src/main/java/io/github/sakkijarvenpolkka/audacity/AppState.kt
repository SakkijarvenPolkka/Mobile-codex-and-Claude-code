/*
 * Audacity Android port — app-level UI state: dialogs, screens and requests
 * that only the Activity can fulfil (SAF pickers, runtime permissions).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import android.os.Bundle
import io.github.sakkijarvenpolkka.audacity.editor.ContextTarget
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
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

    /**
     * One-line text input; [result] gets null when cancelled. [maxBytes]
     * limits the UTF-8 length of the trimmed text (file names: the file
     * system allows 255 bytes, i.e. about 85 Korean characters).
     */
    class TextInput(
        val title: UiText,
        val label: UiText,
        val initial: String,
        val result: CompletableDeferred<String?> = CompletableDeferred(),
        val allowEmpty: Boolean = false,
        val maxBytes: Int = Int.MAX_VALUE,
    ) : AppDialog {
        /** [value] is longer than [maxBytes] (it is flagged, not cut: cutting would break a Korean IME's composition). */
        fun tooLong(value: String): Boolean = SafFiles.utf8Length(value.trim()) > maxBytes

        /** OK is enabled for [value]. */
        fun accepts(value: String): Boolean = (allowEmpty || value.isNotBlank()) && !tooLong(value)
    }

    /** Pick one of [options] (radio buttons); [result] gets the index, null when cancelled. */
    class Choice(
        val title: UiText,
        val options: List<UiText>,
        val initial: Int = 0,
        val result: CompletableDeferred<Int?> = CompletableDeferred(),
    ) : AppDialog

    /** Time input (hh:mm:ss.mmm); [result] gets null when cancelled. */
    class TimeInput(
        val title: UiText,
        val initial: Double,
        val result: CompletableDeferred<Double?> = CompletableDeferred(),
    ) : AppDialog

    /** "Save changes to %s?" — Yes / No / Cancel. */
    class SaveChanges(val projectName: String, val result: CompletableDeferred<SaveChoice> = CompletableDeferred()) : AppDialog

    /** Automatic crash recovery (AutoRecoveryDialog). [startup]: offered when
     *  the engine started; file flows wait until it is answered. */
    data class Recovery(val projects: List<ProjectFileEntry>, val startup: Boolean = false) : AppDialog

    data class Effect(val effectId: String) : AppDialog
    data object PlotSpectrum : AppDialog
    data object Contrast : AppDialog
    data class Export(val selectionOnly: Boolean = false) : AppDialog
    data object Tags : AppDialog
    data object History : AppDialog
    data object Resample : AppDialog
    data object DeviceInfo : AppDialog
    data object LabelEditor : AppDialog

    /** The edit bar's Effects button: one-tap effects and shortcuts to effect dialogs. */
    data object QuickEffects : AppDialog

    /** The whole Effect menu as a sheet ("All effects..." of [QuickEffects]). */
    data object EffectMenu : AppDialog

    /** File ▸ Share Audio...: quick format and range, then the Android share sheet. */
    data object Share : AppDialog

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
    /** Export Labels: `labels.export` writes [format] to a staging file that is copied to the document. */
    data class ExportLabels(val format: String, val fileName: String) : CreatePurpose
}

/**
 * [CreatePurpose] in a Bundle, so that a CREATE_DOCUMENT result delivered
 * after the process was killed (the picker was on top) still says what the
 * document was for (SavedStateHandle).
 */
internal object PurposeCodec {
    fun encode(p: CreatePurpose): Bundle = Bundle().apply {
        when (p) {
            is CreatePurpose.ExportAudio -> {
                putString("kind", "audio")
                putString("format", p.job.formatKey)
                putString("fileName", p.job.fileName)
                putString("range", p.job.range)
                putInt("channels", p.job.channels)
                putInt("rate", p.job.rate)
                putBoolean("skipSilence", p.job.skipSilenceAtStart)
            }
            CreatePurpose.BackupProject -> putString("kind", "backup")
            is CreatePurpose.ExportLabels -> {
                putString("kind", "labels")
                putString("format", p.format)
                putString("fileName", p.fileName)
            }
        }
    }

    fun decode(b: Bundle?): CreatePurpose? {
        b ?: return null
        return when (b.getString("kind")) {
            "audio" -> CreatePurpose.ExportAudio(
                ExportJob(
                    formatKey = b.getString("format") ?: return null,
                    fileName = b.getString("fileName") ?: return null,
                    range = b.getString("range") ?: return null,
                    channels = b.getInt("channels"),
                    rate = b.getInt("rate"),
                    skipSilenceAtStart = b.getBoolean("skipSilence"),
                ),
            )
            "backup" -> CreatePurpose.BackupProject
            "labels" -> CreatePurpose.ExportLabels(b.getString("format") ?: return null, b.getString("fileName") ?: return null)
            else -> null
        }
    }
}

/** Requests the Activity fulfils with activity-result launchers / intents. */
sealed interface HostRequest {
    data class OpenDocuments(val purpose: OpenPurpose, val mimeTypes: List<String>, val multiple: Boolean) : HostRequest
    data class CreateDocument(val purpose: CreatePurpose, val suggestedName: String, val mimeType: String) : HostRequest
    data class RecordPermission(val newTrack: Boolean) : HostRequest
    /** POST_NOTIFICATIONS (API 33+) for the recording/playback notification. */
    data object NotificationPermission : HostRequest
    data class OpenUrl(val url: String) : HostRequest
    /** ACTION_SEND of [file] (in `cacheDir/share`, served by the FileProvider) through the system share sheet. */
    data class ShareFile(val file: java.io.File, val mimeType: String) : HostRequest
    data object AppLanguageSettings : HostRequest
}

/** Progress of an app-side operation (SAF copy). */
data class LocalProgress(val title: UiText, val fraction: Double, val cancel: () -> Unit)
