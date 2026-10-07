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

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.AndroidViewModel
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
import io.github.sakkijarvenpolkka.audacity.files.LabelFiles
import io.github.sakkijarvenpolkka.audacity.files.LabelLine
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import io.github.sakkijarvenpolkka.audacity.menu.Disallowed
import io.github.sakkijarvenpolkka.audacity.menu.MenuHost
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

class AppViewModel(app: Application) : AndroidViewModel(app), MenuHost {

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

    // Purposes of activity-result launches in flight (kept here so they survive Activity recreation).
    var pendingOpenPurpose: OpenPurpose = OpenPurpose.OPEN
    var pendingCreatePurpose: CreatePurpose? = null
    var pendingRecordNewTrack: Boolean = false

    val projectsDir: File get() = File(context.filesDir, "Projects")

    init {
        viewModelScope.launch { startEngine() }
        viewModelScope.launch {
            engine.logs.collect { e ->
                logLines.add(e)
                if (logLines.size > 600) logLines.removeRange(0, logLines.size - 500)
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
        if (recoverable > 0) {
            val list = runCatching { engine.recoverableProjects() }.getOrDefault(emptyList())
            if (list.isNotEmpty()) open(AppDialog.Recovery(list)) else ensureProject()
        }
        if (pendingOpen.isNotEmpty()) {
            val uris = pendingOpen
            pendingOpen = emptyList()
            openUris(uris)
        }
    }

    /** Opens an empty project when none is open (after recovery was skipped). */
    suspend fun ensureProject() {
        if (!engine.snapshot.value.project.open) engine.newProject()
    }

    fun onResume() {
        if (!ready) return
        launchAction(quiet = true) { syncRecordPermission() }
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
            e is EngineException && e.code == ErrorCodes.CANCELLED -> message(UiText.Res(R.string.msg_cancelled))
            e is EngineException && e.code == ErrorCodes.AUDIO_BUSY -> message(UiText.Res(R.string.why_audio_busy))
            e is EngineException && e.code == ErrorCodes.STALE -> message(UiText.Res(R.string.msg_stale))
            e is EngineException -> message(UiText.Raw(e.message?.takeIf { it.isNotBlank() } ?: e.code))
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

    suspend fun askText(title: UiText, label: UiText, initial: String, allowEmpty: Boolean = false): String? {
        val d = AppDialog.TextInput(title, label, initial, allowEmpty = allowEmpty)
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
        val name = askText(UiText.Res(R.string.m_save_as_title), UiText.Res(R.string.project_name), initial) ?: return false
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

    /** Project manager: rename a project file that is not open (main file plus -wal/-shm). */
    suspend fun renameProjectFile(entry: ProjectFileEntry) {
        if (engine.snapshot.value.project.path == entry.path) {
            message(R.string.msg_rename_open_project)
            return
        }
        val name = askText(UiText.Res(R.string.pm_rename), UiText.Res(R.string.project_name), entry.name) ?: return
        val base = SafFiles.sanitizeBaseName(name.trim().removeSuffix(".aup3"))
        val src = File(entry.path)
        val dst = File(src.parentFile, "$base.aup3")
        if (dst.exists()) {
            message(R.string.msg_name_exists, dst.name)
            return
        }
        withContext(Dispatchers.IO) {
            if (!src.renameTo(dst)) throw IOException(src.name)
            for (suffix in listOf("-wal", "-shm")) {
                val aux = File(src.path + suffix)
                if (aux.exists()) aux.renameTo(File(dst.path + suffix))
            }
        }
        refreshRecent()
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

    fun recover(dialog: AppDialog.Recovery, paths: List<String>) = launchAction {
        val path = paths.firstOrNull() ?: return@launchAction
        dismiss(dialog)
        engine.recoverProject(path)
        show(AppScreen.EDITOR)
        if (paths.size > 1) message(R.string.msg_recover_one_at_a_time)
    }

    fun discardRecoverable(dialog: AppDialog.Recovery, paths: List<String>) = launchAction {
        if (paths.isEmpty()) return@launchAction
        val ok = askConfirm(UiText.Res(R.string.recovery_title), UiText.Res(R.string.recovery_discard_confirm), UiText.Res(R.string.btn_yes))
        if (!ok) return@launchAction
        engine.discardRecoverable(paths)
        val left = engine.recoverableProjects()
        dismiss(dialog)
        if (left.isNotEmpty()) open(AppDialog.Recovery(left)) else ensureProject()
    }

    fun skipRecovery(dialog: AppDialog.Recovery) = launchAction {
        dismiss(dialog)
        ensureProject()
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

    /** Result of the RECORD_AUDIO runtime permission request (API.md §6.6). */
    fun onRecordPermissionResult(granted: Boolean, newTrack: Boolean) = launchAction {
        engine.setRecordPermission(granted)
        if (granted) engine.record(newTrack) else message(R.string.msg_mic_denied)
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
        if (partial.effectsGroupBy != null) reloadEffects()
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
        if (ready) openUris(uris) else pendingOpen = uris
    }

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
    }

    fun onDocumentsPicked(purpose: OpenPurpose, uris: List<Uri>) {
        if (uris.isEmpty()) return
        when (purpose) {
            OpenPurpose.OPEN -> openUris(uris)
            OpenPurpose.IMPORT_AUDIO -> launchAction {
                val staged = stageForImport(uris) ?: return@launchAction
                importStaged(staged, newProject = false)
            }
            OpenPurpose.IMPORT_LABELS -> launchAction {
                val uri = uris.first()
                val text = SafFiles.readText(context.contentResolver, uri)
                val name = SafFiles.displayName(context.contentResolver, uri)?.substringBeforeLast('.') ?: "Labels"
                importLabels(name, LabelFiles.parse(text))
            }
        }
    }

    /** File ▸ Open: an .aup3 opens as the project; audio files import into a new (or the empty current) project. */
    fun openUris(uris: List<Uri>) = launchAction {
        val cr = context.contentResolver
        val named = uris.map { it to (SafFiles.displayName(cr, it) ?: "") }
        val project = named.firstOrNull { it.second.endsWith(".aup3", ignoreCase = true) }
        if (project != null) {
            openProjectUri(project.first, project.second)
            return@launchAction
        }
        val snap = engine.snapshot.value
        val intoCurrent = snap.project.open && snap.tracks.isEmpty()
        if (!intoCurrent && !confirmDiscard()) return@launchAction
        val staged = stageForImport(uris) ?: return@launchAction
        importStaged(staged, newProject = !intoCurrent)
        show(AppScreen.EDITOR)
    }

    /** Copies an .aup3 from SAF into `filesDir/Projects` and opens it (§5.4). */
    private suspend fun openProjectUri(uri: Uri, displayName: String) {
        val cr = context.contentResolver
        val isDb = withContext(Dispatchers.IO) {
            runCatching { cr.openInputStream(uri)?.use { SafFiles.isSqlite(it) } ?: false }.getOrDefault(false)
        }
        if (!isDb) {
            message(R.string.msg_not_a_project, displayName)
            return
        }
        if (!confirmDiscard()) return
        val base = SafFiles.sanitizeBaseName(displayName.removeSuffix(".aup3").removeSuffix(".AUP3"))
        val dest = SafFiles.uniqueFile(projectsDir.apply { mkdirs() }, base, "aup3")
        withLocalProgress(UiText.Res(R.string.progress_copying, listOf(displayName))) { report ->
            SafFiles.copyUriToFile(cr, uri, dest) { done, total -> if (total > 0) report(done.toDouble() / total) }
        }
        engine.openProject(dest.absolutePath)
        show(AppScreen.EDITOR)
        refreshRecent()
    }

    /** Copies picked documents to `cacheDir/import/<uuid>/<name>` (§5.2); null when cancelled. */
    private suspend fun stageForImport(uris: List<Uri>): List<File>? {
        val cr = context.contentResolver
        val staged = ArrayList<File>()
        try {
            val total = uris.sumOf { SafFiles.size(cr, it).coerceAtLeast(0) }
            val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (total > 0 && free < total + (64L shl 20)) {
                message(R.string.msg_no_space)
                return null
            }
            for ((i, uri) in uris.withIndex()) {
                val name = SafFiles.sanitizeFileName(SafFiles.displayName(cr, uri), cr.getType(uri))
                val dest = File(SafFiles.stagingDir(context.cacheDir, "import"), name)
                withLocalProgress(UiText.Res(R.string.progress_copying_n, listOf(name, i + 1, uris.size))) { report ->
                    SafFiles.copyUriToFile(cr, uri, dest) { done, size -> if (size > 0) report(done.toDouble() / size) }
                }
                staged += dest
            }
            return staged
        } catch (e: CancellationException) {
            staged.forEach { it.parentFile?.deleteRecursively() }
            throw e
        } catch (e: Exception) {
            staged.forEach { it.parentFile?.deleteRecursively() }
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
            withContext(Dispatchers.IO) { files.forEach { it.parentFile?.deleteRecursively() } }
        }
    }

    /** File ▸ Import ▸ Labels: a new label track named after the file (LabelMenus.cpp OnImportLabels). */
    private suspend fun importLabels(name: String, labels: List<LabelLine>) {
        if (labels.isEmpty()) {
            message(R.string.msg_no_labels_in_file)
            return
        }
        val snap = engine.snapshot.value
        val oldSel = snap.selection
        val oldTracks = snap.selectedTracks.map { it.id }
        val id = engine.addTrack("label")
        engine.renameTrack(id, name)
        engine.selectTracks(listOf(id), "set")
        for (l in labels) {
            engine.select(l.t0, l.t1)
            engine.addLabel(l.title)
        }
        engine.selectTracks(oldTracks, "set")
        engine.select(oldSel.t0, oldSel.t1)
        message(R.string.msg_labels_imported, labels.size)
    }

    /** Export dialog → pick the destination first (§5.3 step 1). */
    fun startExport(job: ExportJob, mime: String) {
        request(HostRequest.CreateDocument(CreatePurpose.ExportAudio(job), job.fileName, mime))
    }

    /** Result of ACTION_CREATE_DOCUMENT. */
    fun onDocumentCreated(purpose: CreatePurpose, uri: Uri?) {
        if (uri == null) return
        val cr = context.contentResolver
        when (purpose) {
            is CreatePurpose.ExportAudio -> launchAction {
                val job = purpose.job
                val dir = SafFiles.stagingDir(context.cacheDir, "export")
                val staged = File(dir, SafFiles.sanitizeBaseName(job.fileName))
                try {
                    engine.export(staged.absolutePath, job.formatKey, job.range, job.channels, job.rate, job.skipSilenceAtStart)
                    withLocalProgress(UiText.Res(R.string.progress_saving, listOf(job.fileName))) { report ->
                        SafFiles.copyFileToUri(cr, staged, uri) { done, total -> if (total > 0) report(done.toDouble() / total) }
                    }
                    uiPrefs.update { it.copy(lastExportFormat = job.formatKey, exportSkipSilence = job.skipSilenceAtStart) }
                    message(R.string.msg_exported, SafFiles.displayName(cr, uri) ?: job.fileName)
                } catch (e: Throwable) {
                    SafFiles.deleteDocument(cr, uri)
                    throw e
                } finally {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                }
            }
            CreatePurpose.BackupProject -> launchAction {
                val name = engine.snapshot.value.project.name.ifEmpty { "Untitled" }
                val dir = SafFiles.stagingDir(context.cacheDir, "export")
                val staged = File(dir, SafFiles.sanitizeBaseName(name) + ".aup3")
                try {
                    engine.saveProjectCopy(staged.absolutePath)
                    withLocalProgress(UiText.Res(R.string.progress_saving, listOf(staged.name))) { report ->
                        SafFiles.copyFileToUri(cr, staged, uri) { done, total -> if (total > 0) report(done.toDouble() / total) }
                    }
                    message(R.string.msg_exported, SafFiles.displayName(cr, uri) ?: staged.name)
                } catch (e: Throwable) {
                    SafFiles.deleteDocument(cr, uri)
                    throw e
                } finally {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                }
            }
            is CreatePurpose.ExportLabels -> launchAction {
                try {
                    SafFiles.writeTextToUri(cr, uri, purpose.text)
                    message(R.string.msg_exported, SafFiles.displayName(cr, uri) ?: "labels.txt")
                } catch (e: Throwable) {
                    SafFiles.deleteDocument(cr, uri)
                    throw e
                }
            }
        }
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
