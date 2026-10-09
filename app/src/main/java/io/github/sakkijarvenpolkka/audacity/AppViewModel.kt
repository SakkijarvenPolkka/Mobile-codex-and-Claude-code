/*
 * Audacity Android port — the app's view model: engine lifecycle (start,
 * crash recovery), dialogs, file flows (SAF import/export/open/save), record
 * permission and menu dispatch. Replaces the non-GUI parts of
 * ProjectManager / ProjectFileManager / AutoRecoveryDialog of Audacity
 * 3.7.9 src/ (the Audacity Team, GPL-2.0-or-later).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.EngineException
import io.github.sakkijarvenpolkka.audacity.engine.EngineStatus
import io.github.sakkijarvenpolkka.audacity.engine.StartConfigs
import io.github.sakkijarvenpolkka.audacity.engine.awaitStarted
import io.github.sakkijarvenpolkka.audacity.engine.model.AppInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.ErrorCodes
import io.github.sakkijarvenpolkka.audacity.engine.model.LogEvent
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import io.github.sakkijarvenpolkka.audacity.files.InsufficientSpaceException
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import io.github.sakkijarvenpolkka.audacity.files.SourceTooLargeException
import io.github.sakkijarvenpolkka.audacity.menu.Disallowed
import io.github.sakkijarvenpolkka.audacity.menu.MenuHost
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import io.github.sakkijarvenpolkka.audacity.share.ShareAudio
import io.github.sakkijarvenpolkka.audacity.share.ShareFormat
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import io.github.sakkijarvenpolkka.audacity.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext

class AppViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app), MenuHost {

    /** Without saved state (tests, previews). */
    constructor(app: Application) : this(app, SavedStateHandle())

    private val audacity = app as AudacityApp
    override val engine: AudacityEngine = audacity.engine
    override val uiPrefs: UiPrefs = audacity.prefs
    private val context: Context get() = getApplication()

    // ----- MenuHost state ------------------------------------------------------
    override var editor: EditorState? = null
    override var editorHeightDp: Float = 0f
    override var storedSelection: TimeRange? = null
    override var storedCursor: Double? = null

    // ----- UI state ------------------------------------------------------------
    /** Dialog stack; the last one is shown on top. */
    val dialogs = mutableStateListOf<AppDialog>()
    var screen by mutableStateOf(AppScreen.EDITOR)
        private set

    /** Engine log lines for Help ▸ Diagnostics ▸ Show Log. */
    val logLines = mutableStateListOf<LogEvent>()

    private val _localProgress = MutableStateFlow<LocalProgress?>(null)
    val localProgress: StateFlow<LocalProgress?> = _localProgress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _requests = Channel<HostRequest>(Channel.UNLIMITED)
    val requests: Flow<HostRequest> = _requests.receiveAsFlow()

    val effectList = MutableStateFlow<EffectList?>(null)
    val settings = MutableStateFlow<Settings?>(null)
    val recentProjects = MutableStateFlow<List<ProjectFileEntry>>(emptyList())
    val appInfo = MutableStateFlow<AppInfo?>(null)

    private var ready = false
    private var pendingOpen: List<Uri> = emptyList()

    /**
     * Open once the engine is ready and the start-up recovery prompt (if
     * any) is answered: documents from intents and activity results are
     * processed only then (before, the engine refuses commands, and a file
     * imported into a new project would be replaced by a recovery).
     */
    private val startupGate = MutableStateFlow(false)

    /** A Recover/Discard/Skip of the recovery dialog is running (its buttons are disabled). */
    var recoveryBusy by mutableStateOf(false)
        private set

    /**
     * Identifies requests launched by this process. Purposes of activity
     * results in flight are kept in [savedState]: the process may be killed
     * while a picker or the permission dialog is on top, and the result is
     * then delivered to a new process (and view model), where the project it
     * was for is gone. Process-wide, not per view model: when only the
     * Activity and its view model were destroyed, the project is still open
     * and the result is still valid.
     */
    private val requestToken: String get() = processToken

    val projectsDir: File get() = File(context.filesDir, "Projects")

    init {
        audacity.attachEngineServices(engine)
        viewModelScope.launch { startEngine() }
        viewModelScope.launch {
            engine.logs.collect { e ->
                logLines.add(e)
                if (logLines.size > 600) logLines.removeRange(0, logLines.size - 500)
            }
        }
        viewModelScope.launch {
            // Ask once for the notification of the recording/playback service (Android 13+)
            engine.transportState.collect { t ->
                if ((t.state == "recording" || t.state == "playing") && shouldAskNotifications()) {
                    markNotificationsAsked()
                    request(HostRequest.NotificationPermission)
                }
            }
        }
        viewModelScope.launch {
            engine.transportEvents.collect { t ->
                if ((t.reason == "error" || t.reason == "device") && t.message.isNotBlank()) message(UiText.Raw(t.message))
                else if (t.state == "stopped" && t.dropouts > 0) message(UiText.Res(R.string.msg_dropouts, listOf(t.dropouts)))
            }
        }
    }

    // =========================================================================
    // Engine lifecycle
    // =========================================================================

    private suspend fun startEngine() {
        try {
            engine.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            open(AppDialog.Info(UiText.Res(R.string.engine_failed_title), UiText.Raw(e.message ?: e.toString())))
            return
        }
        when (val st = engine.awaitStarted()) {
            is EngineStatus.Ready -> onReady(st.recoverableProjects)
            is EngineStatus.Failed -> open(AppDialog.Info(UiText.Res(R.string.engine_failed_title), UiText.Raw(st.message)))
            EngineStatus.Unavailable -> open(AppDialog.Info(UiText.Res(R.string.engine_failed_title), UiText.Res(R.string.engine_unavailable)))
            EngineStatus.Starting -> Unit
        }
    }

    private suspend fun onReady(recoverable: Int) {
        ready = true
        quietly { syncRecordPermission() }
        quietly { effectList.value = engine.effects() }
        quietly { settings.value = engine.getSettings() }
        quietly { appInfo.value = engine.appInfo() }
        refreshRecent()
        // [recoverable] is the count of the process's engine start: a later view
        // model (new Activity in a process kept alive) must not offer it again
        val offer = recoverable > 0 && shouldOfferRecovery()
        val list = if (offer) runCatching { engine.recoverableProjects() }.getOrDefault(emptyList()) else emptyList()
        if (list.isNotEmpty()) {
            open(AppDialog.Recovery(list, startup = true))
        } else {
            // The engine opens an empty project unless something is recoverable;
            // when that failed (e.g. storage full: self-check initialProject),
            // try again and say why
            try {
                ensureProject()
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                open(AppDialog.Info(UiText.Res(R.string.engine_failed_title), UiText.Raw(e.message?.takeIf { it.isNotBlank() } ?: e.code)))
            } catch (e: Exception) {
                reportError(e)
            }
        }
        // With the recovery prompt up, files wait for its answer (recoveryAnswered)
        if (list.isEmpty()) openStartupGate()
    }

    /** Recovery is offered once per engine (process), and never over an open project. */
    private fun shouldOfferRecovery(): Boolean =
        synchronized(answeredEngines) { engine !in answeredEngines } && !engine.snapshot.value.project.open

    /** The start-up recovery prompt was answered (Recover, Discard all, Skip). */
    private fun recoveryAnswered() {
        synchronized(answeredEngines) { answeredEngines += engine }
        openStartupGate()
    }

    private fun openStartupGate() {
        if (startupGate.value) return
        startupGate.value = true
        val uris = pendingOpen
        pendingOpen = emptyList()
        if (uris.isNotEmpty()) openUris(uris)
    }

    private suspend fun awaitStartup() {
        startupGate.first { it }
    }

    /** Opens an empty project when none is open (after recovery was skipped). */
    suspend fun ensureProject() {
        if (!engine.snapshot.value.project.open) engine.newProject()
    }

    fun onResume() {
        if (!ready) return
        launchAction(quiet = true) { syncRecordPermission() }
    }

    private fun shouldAskNotifications(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ASKED_NOTIFICATIONS, false)

    private fun markNotificationsAsked() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(K_ASKED_NOTIFICATIONS, true) }
    }

    private suspend fun syncRecordPermission() {
        val granted = StartConfigs.hasRecordPermission(context)
        if (engine.snapshot.value.has(CommandFlags.RECORD_PERMISSION) != granted) engine.setRecordPermission(granted)
    }

    suspend fun reloadEffects() {
        effectList.value = engine.effects()
    }

    suspend fun refreshRecent() {
        quietly { recentProjects.value = engine.listProjects() }
    }

    // =========================================================================
    // Actions, errors, messages
    // =========================================================================

    /** Runs [block] in the view-model scope, reporting engine and I/O errors. */
    fun launchAction(quiet: Boolean = false, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) reportError(e)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    fun reportError(e: Throwable) {
        when {
            e is CancellationException -> Unit   // a dialog/effect went away while waiting
            e is EngineException && e.code == ErrorCodes.CANCELLED -> message(UiText.Res(R.string.msg_cancelled))
            e is EngineException && e.code == ErrorCodes.AUDIO_BUSY -> message(UiText.Res(R.string.why_audio_busy))
            e is EngineException && e.code == ErrorCodes.STALE -> message(UiText.Res(R.string.msg_stale))
            e is EngineException -> message(UiText.Raw(e.message?.takeIf { it.isNotBlank() } ?: e.code))
            e is InsufficientSpaceException -> message(UiText.Res(R.string.msg_no_space_copy))
            e is SourceTooLargeException -> message(UiText.Res(R.string.msg_source_too_large, listOf(TimeCodec.formatBytes(e.limit))))
            e is IOException -> message(UiText.Res(R.string.msg_io_error, listOf(e.message ?: e.toString())))
            else -> message(UiText.Raw(e.message ?: e.toString()))
        }
    }

    override fun message(text: UiText) {
        _messages.tryEmit(text.resolve(context.resources))
    }

    fun message(@StringRes id: Int, vararg args: Any) = message(UiText.Res(id, args.toList()))

    fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    // =========================================================================
    // Dialogs and screens
    // =========================================================================

    override fun open(dialog: AppDialog) {
        dialogs.add(dialog)
    }

    /** Removes [dialog]; pending results are completed as "cancelled". */
    fun dismiss(dialog: AppDialog) {
        dialogs.remove(dialog)
        cancelResult(dialog)
    }

    private fun cancelResult(dialog: AppDialog) {
        when (dialog) {
            is AppDialog.TextInput -> dialog.result.complete(null)
            is AppDialog.TimeInput -> dialog.result.complete(null)
            is AppDialog.Choice -> dialog.result.complete(null)
            is AppDialog.Confirm -> dialog.result.complete(false)
            is AppDialog.SaveChanges -> dialog.result.complete(SaveChoice.CANCEL)
            else -> Unit
        }
    }

    override fun show(screen: AppScreen) {
        this.screen = screen
        if (screen == AppScreen.PROJECTS) launchAction(quiet = true) { refreshRecent() }
    }

    override fun request(request: HostRequest) {
        _requests.trySend(request)
    }

    suspend fun askText(title: UiText, label: UiText, initial: String, allowEmpty: Boolean = false, maxBytes: Int = Int.MAX_VALUE): String? {
        val d = AppDialog.TextInput(title, label, initial, allowEmpty = allowEmpty, maxBytes = maxBytes)
        open(d)
        return d.result.await()
    }

    suspend fun askConfirm(title: UiText, message: UiText, ok: UiText = UiText.Res(R.string.btn_yes)): Boolean {
        val d = AppDialog.Confirm(title, message, ok)
        open(d)
        return d.result.await()
    }

    override fun onCleared() {
        dialogs.toList().forEach { cancelResult(it) }
        super.onCleared()
    }

    // =========================================================================
    // Menus and keyboard
    // =========================================================================

    /** A menu item was picked: run it, or explain why it is disabled (ui-reference.md §9.5). */
    fun onMenuItem(item: MenuItem, state: MenuState) {
        val flags = engine.snapshot.value.flags
        if (!item.enabled(flags)) {
            val name = item.labelFor(state).resolve(context.resources).removeSuffix("...").removeSuffix("…")
            Disallowed.reason(item.required, flags, name, item.noiseReductionReason)?.let { message(it) }
            return
        }
        launchAction { item.action(this) }
    }

    /** Hardware keyboard shortcut; true when it was handled. */
    fun onShortcut(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean, state: MenuState): Boolean {
        if (dialogs.isNotEmpty() || screen != AppScreen.EDITOR) return false
        val item = MenuSpec.findByShortcut(key, ctrl, shift, alt, state) ?: return false
        onMenuItem(item, state)
        return true
    }

    // =========================================================================
    // MenuHost: project flows
    // =========================================================================

    /** Asks "Save changes to %s?" when the project is dirty; false = cancelled. */
    suspend fun confirmDiscard(): Boolean {
        val p = engine.snapshot.value.project
        if (!p.open || !p.dirty) return true
        val d = AppDialog.SaveChanges(p.name.ifEmpty { string(R.string.untitled) })
        open(d)
        return when (d.result.await()) {
            SaveChoice.SAVE -> saveInteractive()
            SaveChoice.DISCARD -> true
            SaveChoice.CANCEL -> false
        }
    }

    override suspend fun newProject() {
        if (!confirmDiscard()) return
        engine.newProject()
        show(AppScreen.EDITOR)
    }

    override suspend fun saveProject() {
        saveInteractive()
    }

    override suspend fun saveProjectAs() {
        saveAsInteractive()
    }

    /** File ▸ Save Project; a never-saved project goes through Save As. */
    private suspend fun saveInteractive(): Boolean {
        return try {
            engine.saveProject()
            message(R.string.msg_saved, engine.snapshot.value.project.name)
            refreshRecent()
            true
        } catch (e: EngineException) {
            if (e.code == ErrorCodes.NEEDS_PATH) saveAsInteractive() else throw e
        }
    }

    /** Save As: a name in app storage (`filesDir/Projects/<name>.aup3`). */
    private suspend fun saveAsInteractive(): Boolean {
        val p = engine.snapshot.value.project
        val initial = if (p.temporary || p.path == null) p.name.ifEmpty { string(R.string.untitled) } else p.name
        val name = askText(UiText.Res(R.string.m_save_as_title), UiText.Res(R.string.project_name), initial, maxBytes = SafFiles.MAX_STEM_BYTES)
            ?: return false
        val base = SafFiles.sanitizeBaseName(name.trim().removeSuffix(".aup3"))
        val dir = projectsDir.apply { mkdirs() }
        val file = File(dir, "$base.aup3")
        if (file.exists() && file.absolutePath != p.path) {
            val replace = askConfirm(
                UiText.Res(R.string.m_save_as_title), UiText.Res(R.string.msg_replace_file, listOf(file.name)),
                UiText.Res(R.string.btn_replace),
            )
            if (!replace) return false
        }
        engine.saveProjectAs(file.absolutePath)
        message(R.string.msg_saved, base)
        refreshRecent()
        return true
    }

    override suspend fun closeProject() {
        if (!confirmDiscard()) return
        engine.closeProject()
        show(AppScreen.PROJECTS)
    }

    override suspend fun openProjectFile(path: String) {
        val cur = engine.snapshot.value.project
        if (cur.open && cur.path == path) {
            show(AppScreen.EDITOR)
            return
        }
        if (!confirmDiscard()) return
        engine.openProject(path)
        show(AppScreen.EDITOR)
        refreshRecent()
    }

    /** Project manager: rename a project that is not open (project.rename moves the -wal/-shm files too). */
    suspend fun renameProjectFile(entry: ProjectFileEntry) {
        if (engine.snapshot.value.project.path == entry.path) {
            message(R.string.msg_rename_open_project)
            return
        }
        val name = askText(UiText.Res(R.string.pm_rename), UiText.Res(R.string.project_name), entry.name, maxBytes = SafFiles.MAX_STEM_BYTES)
            ?: return
        val base = SafFiles.sanitizeBaseName(name.trim().removeSuffix(".aup3"))
        if (base == entry.name) return
        if (recentProjects.value.any { it.name == base && it.path != entry.path }) {
            message(R.string.msg_name_exists, "$base.aup3")
            return
        }
        try {
            engine.renameProject(entry.path, base)
        } finally {
            refreshRecent()
        }
    }

    suspend fun deleteProjectFile(entry: ProjectFileEntry) {
        val ok = askConfirm(
            UiText.Res(R.string.pm_delete), UiText.Res(R.string.pm_delete_confirm, listOf(entry.name)),
            UiText.Res(R.string.btn_delete),
        )
        if (!ok) return
        engine.deleteProject(entry.path)
        refreshRecent()
    }

    // ----- crash recovery (AutoRecoveryDialog) ---------------------------------

    /**
     * Recover Selected. The recovered project replaces the open one, so this
     * asks "Save changes?" first like every other flow that replaces the
     * project (closing a never-saved project deletes its file). The dialog
     * stays up, its buttons disabled, until the recovery succeeded.
     */
    fun recover(dialog: AppDialog.Recovery, paths: List<String>) {
        val path = paths.firstOrNull() ?: return
        recoveryAction {
            if (!confirmDiscard()) return@recoveryAction
            engine.recoverProject(path)
            dismiss(dialog)
            if (dialog.startup) recoveryAnswered()
            show(AppScreen.EDITOR)
            if (paths.size > 1) message(R.string.msg_recover_one_at_a_time)
        }
    }

    fun discardRecoverable(dialog: AppDialog.Recovery, paths: List<String>) {
        if (paths.isEmpty()) return
        recoveryAction {
            val ok = askConfirm(UiText.Res(R.string.recovery_title), UiText.Res(R.string.recovery_discard_confirm), UiText.Res(R.string.btn_yes))
            if (!ok) return@recoveryAction
            engine.discardRecoverable(paths)
            val left = engine.recoverableProjects()
            dismiss(dialog)
            if (left.isNotEmpty()) open(dialog.copy(projects = left)) else finishRecovery(dialog)
        }
    }

    fun skipRecovery(dialog: AppDialog.Recovery) = recoveryAction {
        dismiss(dialog)
        finishRecovery(dialog)
    }

    private suspend fun finishRecovery(dialog: AppDialog.Recovery) {
        try {
            ensureProject()
        } finally {
            if (dialog.startup) recoveryAnswered()
        }
    }

    /** Runs one Recover/Discard/Skip at a time (double taps are ignored). */
    private fun recoveryAction(block: suspend () -> Unit) {
        if (recoveryBusy) return
        recoveryBusy = true
        launchAction {
            try {
                block()
            } finally {
                recoveryBusy = false
            }
        }
    }

    fun showRecovery() = launchAction {
        val list = engine.recoverableProjects()
        if (list.isEmpty()) message(R.string.msg_nothing_to_recover) else open(AppDialog.Recovery(list))
    }

    // =========================================================================
    // MenuHost: recording, effects, settings
    // =========================================================================

    override fun record(newTrack: Boolean) {
        if (StartConfigs.hasRecordPermission(context)) {
            launchAction {
                syncRecordPermission()
                engine.record(newTrack)
            }
        } else {
            request(HostRequest.RecordPermission(newTrack))
        }
    }

    /** The Activity is about to ask for RECORD_AUDIO for a recording ([newTrack]). */
    fun beginRecordPermission(newTrack: Boolean) {
        savedState[K_RECORD] = Bundle().apply {
            putBoolean("newTrack", newTrack)
            putString(K_TOKEN, requestToken)
        }
    }

    /**
     * Result of the RECORD_AUDIO runtime permission request (API.md §6.6).
     * Records only when the request came from this process: after a process
     * death the project it was for has changed (it is offered for recovery).
     */
    fun onRecordPermissionResult(granted: Boolean) {
        val req = savedState.remove<Bundle>(K_RECORD)?.takeIf { it.getString(K_TOKEN) == requestToken }
        launchAction {
            awaitStartup()
            engine.setRecordPermission(granted)
            if (!granted) message(R.string.msg_mic_denied) else if (req != null) engine.record(req.getBoolean("newTrack"))
        }
    }

    override suspend fun runEffect(effectId: String) {
        val info = effectList.value?.effects?.firstOrNull { it.id == effectId }
        if (info != null && !info.interactive && info.special == null) {
            val r = engine.applyEffect(effectId)
            r.message?.takeIf { it.isNotBlank() }?.let { open(AppDialog.Info(UiText.Raw(info.name), UiText.Raw(it))) }
        } else {
            open(AppDialog.Effect(effectId))
        }
    }

    override suspend fun updateSettings(partial: Settings) {
        settings.value = engine.setSettings(partial)
        // Effect names and menus are translated by the engine
        if (partial.effectsGroupBy != null || partial.language != null) reloadEffects()
        if (partial.language != null) quietly { appInfo.value = engine.appInfo() }
    }

    override fun clipboardText(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    // =========================================================================
    // Files: open / import / export (import-export-project.md §5)
    // =========================================================================

    /** ACTION_VIEW / ACTION_SEND intents with audio files or .aup3 projects. */
    fun handleIntent(intent: Intent?) {
        val uris = urisOf(intent ?: return)
        if (uris.isEmpty()) return
        // Before start-up is done, later intents add to the earlier ones
        if (startupGate.value) openUris(uris) else pendingOpen = (pendingOpen + uris).distinct()
    }

    /**
     * The documents of an intent; content Uris only. A file:// Uri (also in an
     * explicit intent, which bypasses the manifest filters) could name a
     * device such as /dev/zero, or a private file of this app.
     */
    @Suppress("DEPRECATION")
    private fun urisOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }

    // ----- activity results (purposes survive process death: savedState) -------

    /** The Activity is about to launch ACTION_OPEN_DOCUMENT for [purpose]. */
    fun beginOpenDocuments(purpose: OpenPurpose) {
        savedState[K_OPEN] = purpose.name
    }

    /** Result of ACTION_OPEN_DOCUMENT (one or several documents). */
    fun onDocumentsPicked(uris: List<Uri>) {
        val name = savedState.remove<String>(K_OPEN)
        onDocumentsPicked(OpenPurpose.entries.firstOrNull { it.name == name } ?: OpenPurpose.OPEN, uris)
    }

    fun onDocumentsPicked(purpose: OpenPurpose, uris: List<Uri>) {
        if (uris.isEmpty()) return
        when (purpose) {
            OpenPurpose.OPEN -> openUris(uris)
            OpenPurpose.IMPORT_AUDIO -> launchAction {
                awaitStartup()
                val staged = stageForImport(uris, SafFiles.describe(context.contentResolver, uris)) ?: return@launchAction
                importStaged(staged, newProject = false)
            }
            OpenPurpose.IMPORT_LABELS -> launchAction {
                awaitStartup()
                val one = uris.take(1)
                val staged = stageForImport(one, SafFiles.describe(context.contentResolver, one)) ?: return@launchAction
                importLabels(staged.single())
            }
        }
    }

    /** File ▸ Open: an .aup3 opens as the project; audio files import into a new (or the empty current) project. */
    fun openUris(uris: List<Uri>) = launchAction {
        awaitStartup()
        val docs = SafFiles.describe(context.contentResolver, uris)
        val project = docs.indexOfFirst { it.name.orEmpty().endsWith(".aup3", ignoreCase = true) }
        if (project >= 0) {
            openProjectUri(uris[project], docs[project])
            return@launchAction
        }
        val snap = engine.snapshot.value
        val intoCurrent = snap.project.open && snap.tracks.isEmpty()
        if (!intoCurrent && !confirmDiscard()) return@launchAction
        val staged = stageForImport(uris, docs) ?: return@launchAction
        importStaged(staged, newProject = !intoCurrent)
        show(AppScreen.EDITOR)
    }

    /** Copies an .aup3 from SAF into `filesDir/Projects` and opens it (§5.4). */
    private suspend fun openProjectUri(uri: Uri, doc: SafFiles.DocInfo) {
        val cr = context.contentResolver
        val displayName = doc.name.orEmpty()
        val isDb = withContext(Dispatchers.IO) {
            runCatching { cr.openInputStream(uri)?.use { SafFiles.isSqlite(it) } ?: false }.getOrDefault(false)
        }
        if (!isDb) {
            message(R.string.msg_not_a_project, displayName)
            return
        }
        if (!confirmDiscard()) return
        val dir = projectsDir.apply { mkdirs() }
        if (doc.size > SafFiles.writableBytes(dir)) {
            message(R.string.msg_no_space_copy)
            return
        }
        val base = SafFiles.sanitizeBaseName(displayName.removeSuffix(".aup3").removeSuffix(".AUP3"))
        val dest = SafFiles.uniqueFile(dir, base, "aup3")
        withLocalProgress(UiText.Res(R.string.progress_copying, listOf(displayName))) { report ->
            // Capped by the free space only (the reported size may be missing or wrong): a project
            // can be larger than an audio file of unknown size may be
            SafFiles.copyUriToFile(cr, uri, dest, unknownSizeLimit = Long.MAX_VALUE) { done, total ->
                if (total > 0) report(done.toDouble() / total)
            }
        }
        try {
            engine.openProject(dest.absolutePath)
        } catch (e: EngineException) {
            // A copy the engine refused (damaged, audio busy) is not left in the project list.
            // Not on cancellation: the engine may still be opening it.
            if (engine.snapshot.value.project.path != dest.absolutePath) SafFiles.deleteProjectFiles(dest)
            throw e
        }
        show(AppScreen.EDITOR)
        refreshRecent()
    }

    /** A fresh `cacheDir/<kind>/<uuid>`, once the start-up clean-up of old staging is done. */
    private suspend fun newStagingDir(kind: String): File {
        audacity.awaitStagingCleanup()
        return withContext(Dispatchers.IO) { SafFiles.stagingDir(context.cacheDir, kind) }
    }

    /** Copies picked documents to `cacheDir/import/<uuid>/<name>` (§5.2); null when there is no room. */
    private suspend fun stageForImport(uris: List<Uri>, docs: List<SafFiles.DocInfo>): List<File>? {
        val cr = context.contentResolver
        val dirs = ArrayList<File>()
        try {
            val total = docs.sumOf { it.size.coerceAtLeast(0) }
            if (total > SafFiles.writableBytes(context.cacheDir)) {
                message(R.string.msg_no_space)
                return null
            }
            val staged = ArrayList<File>()
            for ((i, uri) in uris.withIndex()) {
                val name = SafFiles.sanitizeFileName(docs[i].name, docs[i].mime)
                val dir = newStagingDir("import")
                dirs += dir
                val dest = File(dir, name)
                withLocalProgress(UiText.Res(R.string.progress_copying_n, listOf(name, i + 1, uris.size))) { report ->
                    // Capped by the free space (and 4 GiB without a size): a source of unknown size can be endless
                    SafFiles.copyUriToFile(cr, uri, dest) { done, size -> if (size > 0) report(done.toDouble() / size) }
                }
                staged += dest
            }
            return staged
        } catch (e: Throwable) {
            dirs.forEach { SafFiles.deleteStaging(it) }
            throw e
        }
    }

    private suspend fun importStaged(files: List<File>, newProject: Boolean) {
        try {
            val r = engine.importFiles(files.map { it.absolutePath }, newProject)
            if (r.messages.isNotEmpty()) {
                open(AppDialog.Info(UiText.Res(R.string.m_import), UiText.Raw(r.messages.joinToString("\n\n"))))
            }
        } finally {
            files.forEach { SafFiles.deleteStaging(it.parentFile) }
        }
    }

    /** File ▸ Import ▸ Labels (labels.import): a new label track named after the staged file. */
    private suspend fun importLabels(staged: File) {
        try {
            val id = engine.importLabels(staged.absolutePath)
            val n = engine.snapshot.value.track(id)?.labels?.size ?: 0
            if (n == 0) message(R.string.msg_no_labels_in_file) else message(R.string.msg_labels_imported, n)
        } finally {
            SafFiles.deleteStaging(staged.parentFile)
        }
    }

    /**
     * File ▸ Share Audio: exports the selection (or the project) in a quick
     * [format] to `cacheDir/share/<uuid>/<name>` and asks the Activity for the
     * share sheet. Earlier shared files are removed first (their share sheet
     * is done once the user is back here); the last one stays for the
     * receiver and goes at a later share or start-up (AudacityApp).
     */
    fun shareAudio(format: ShareFormat, selectionOnly: Boolean): Job = launchAction {
        uiPrefs.update { it.copy(lastShareFormat = format.name) }
        withContext(Dispatchers.IO) { ShareAudio.clearStaging(context.cacheDir) }
        val dir = newStagingDir(ShareAudio.STAGING)
        try {
            val name = engine.snapshot.value.project.name.ifEmpty { string(R.string.untitled) }
            val call = ShareAudio.export(engine, format, selectionOnly, dir, name)
            request(HostRequest.ShareFile(File(call.path), SafFiles.mimeForExtension(SafFiles.extensionOf(call.path))))
        } catch (e: Throwable) {
            SafFiles.deleteStaging(dir)
            throw e
        }
    }

    /** Export dialog → pick the destination first (§5.3 step 1). */
    fun startExport(job: ExportJob, mime: String) {
        request(HostRequest.CreateDocument(CreatePurpose.ExportAudio(job), job.fileName, mime))
    }

    /** The Activity is about to launch ACTION_CREATE_DOCUMENT for [purpose]. */
    fun beginCreateDocument(purpose: CreatePurpose) {
        savedState[K_CREATE] = PurposeCodec.encode(purpose).apply { putString(K_TOKEN, requestToken) }
    }

    /**
     * Result of ACTION_CREATE_DOCUMENT. When the request came from a process
     * that was killed while the picker was on top, the project and selection
     * it was for are gone: the empty document the picker created is removed
     * and the user is told to try again (nothing is written silently).
     */
    fun onDocumentCreated(uri: Uri?) {
        val saved = savedState.remove<Bundle>(K_CREATE)
        uri ?: return
        val purpose = PurposeCodec.decode(saved)
        if (purpose == null || saved?.getString(K_TOKEN) != requestToken) {
            launchAction {
                SafFiles.deleteDocument(context.contentResolver, uri)
                message(R.string.msg_save_interrupted)
            }
            return
        }
        onDocumentCreated(purpose, uri)
    }

    /** Writes the document [uri] created for [purpose]. */
    fun onDocumentCreated(purpose: CreatePurpose, uri: Uri?) {
        if (uri == null) return
        when (purpose) {
            is CreatePurpose.ExportAudio -> launchAction {
                val job = purpose.job
                val name = writeDocument(uri, SafFiles.sanitizeFileName(job.fileName, null), job.fileName, progress = true) { staged ->
                    engine.export(staged.absolutePath, job.formatKey, job.range, job.channels, job.rate, job.skipSilenceAtStart)
                }
                uiPrefs.update { it.copy(lastExportFormat = job.formatKey, exportSkipSilence = job.skipSilenceAtStart) }
                message(R.string.msg_exported, name)
            }
            CreatePurpose.BackupProject -> launchAction {
                val file = SafFiles.sanitizeBaseName(engine.snapshot.value.project.name.ifEmpty { "Untitled" }) + ".aup3"
                val name = writeDocument(uri, file, file, progress = true) { staged -> engine.saveProjectCopy(staged.absolutePath) }
                message(R.string.msg_exported, name)
            }
            is CreatePurpose.ExportLabels -> launchAction {
                // labels.export writes a staging file in the chosen format; then it is copied to the document
                val name = writeDocument(uri, SafFiles.sanitizeFileName(purpose.fileName, null), purpose.fileName, progress = false) { staged ->
                    engine.exportLabels(staged.absolutePath, purpose.format)
                }
                message(R.string.msg_exported, name)
            }
        }
    }

    /**
     * Has [produce] write `cacheDir/export/<uuid>/<stagedName>`, then copies it
     * to the created document [uri] (§5.3); returns the document's display
     * name. On failure or cancellation the document is removed; the staging
     * directory always is.
     */
    private suspend fun writeDocument(
        uri: Uri,
        stagedName: String,
        title: String,
        progress: Boolean,
        produce: suspend (File) -> Unit,
    ): String {
        val cr = context.contentResolver
        var dir: File? = null
        try {
            dir = newStagingDir("export")
            val staged = File(dir, stagedName)
            produce(staged)
            if (progress) {
                withLocalProgress(UiText.Res(R.string.progress_saving, listOf(title))) { report ->
                    SafFiles.copyFileToUri(cr, staged, uri) { done, total -> if (total > 0) report(done.toDouble() / total) }
                }
            } else {
                SafFiles.copyFileToUri(cr, staged, uri)
            }
        } catch (e: Throwable) {
            SafFiles.deleteDocument(cr, uri)
            throw e
        } finally {
            SafFiles.deleteStaging(dir)
        }
        return SafFiles.displayNameIo(cr, uri) ?: title
    }

    internal companion object {
        const val PREFS = "audacity_ui"
        const val K_ASKED_NOTIFICATIONS = "askedNotificationPermission"
        const val K_OPEN = "request.openPurpose"
        const val K_CREATE = "request.createPurpose"
        const val K_RECORD = "request.recordPermission"
        const val K_TOKEN = "token"

        /** See [requestToken]; replaced by tests to simulate a new process. */
        @VisibleForTesting
        var processToken: String = UUID.randomUUID().toString()

        /**
         * Engines whose start-up recovery prompt was answered. Process-wide:
         * the engine (and its start-up recoverable count) outlives view
         * models, and a new Activity must not offer the same projects again.
         */
        val answeredEngines: MutableSet<AudacityEngine> = Collections.newSetFromMap(WeakHashMap())
    }

    /** Runs [block] with a cancellable app-side progress dialog. */
    private suspend fun <T> withLocalProgress(title: UiText, block: suspend (report: (Double) -> Unit) -> T): T {
        val job = coroutineContext[Job]
        _localProgress.value = LocalProgress(title, -1.0) { job?.cancel() }
        try {
            return block { f -> _localProgress.value = _localProgress.value?.copy(fraction = f.coerceIn(0.0, 1.0)) }
        } finally {
            _localProgress.value = null
        }
    }
}
