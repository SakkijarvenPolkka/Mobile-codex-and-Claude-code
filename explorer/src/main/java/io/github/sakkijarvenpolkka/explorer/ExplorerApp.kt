/*
 * Files — Application: owns the process-wide services of the file manager, and does what the file
 * operations need around them: the foreground service, the media scans, and stopping those on a
 * volume that goes (and forgetting its thumbnails, and closing the files the viewers hold on it). It
 * tells the system file picker when the drives or the favorites it lists change, and the folders its
 * operations change.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.sakkijarvenpolkka.explorer.data.SettingsStore
import io.github.sakkijarvenpolkka.explorer.fs.FileOps
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.NameLabels
import io.github.sakkijarvenpolkka.explorer.fs.NioFsBackend
import io.github.sakkijarvenpolkka.explorer.fs.OpKind
import io.github.sakkijarvenpolkka.explorer.fs.OpResult
import io.github.sakkijarvenpolkka.explorer.fs.OpenSaves
import io.github.sakkijarvenpolkka.explorer.fs.OperationSpec
import io.github.sakkijarvenpolkka.explorer.fs.RecycleBin
import io.github.sakkijarvenpolkka.explorer.fs.WriteProtocol
import io.github.sakkijarvenpolkka.explorer.fs.ZipOps
import io.github.sakkijarvenpolkka.explorer.ops.FileOpsHost
import io.github.sakkijarvenpolkka.explorer.ops.FileOpsService
import io.github.sakkijarvenpolkka.explorer.ops.FinishedOp
import io.github.sakkijarvenpolkka.explorer.ops.OperationJournal
import io.github.sakkijarvenpolkka.explorer.ops.OperationRunner
import io.github.sakkijarvenpolkka.explorer.ops.OpsNotice
import io.github.sakkijarvenpolkka.explorer.ops.RunnerHost
import io.github.sakkijarvenpolkka.explorer.ops.RunnerState
import io.github.sakkijarvenpolkka.explorer.ops.ServiceStarter
import io.github.sakkijarvenpolkka.explorer.ops.scanTargets
import io.github.sakkijarvenpolkka.explorer.platform.AndroidMediaScan
import io.github.sakkijarvenpolkka.explorer.platform.AndroidThumbnailPort
import io.github.sakkijarvenpolkka.explorer.platform.MediaScan
import io.github.sakkijarvenpolkka.explorer.platform.ThumbnailPort
import io.github.sakkijarvenpolkka.explorer.platform.Thumbnails
import io.github.sakkijarvenpolkka.explorer.platform.VolumeEvent
import io.github.sakkijarvenpolkka.explorer.platform.VolumeRegistry
import io.github.sakkijarvenpolkka.explorer.preview.AndroidImageDecoder
import io.github.sakkijarvenpolkka.explorer.preview.AndroidMediaPort
import io.github.sakkijarvenpolkka.explorer.preview.AndroidPdfOpener
import io.github.sakkijarvenpolkka.explorer.preview.FsTextLoader
import io.github.sakkijarvenpolkka.explorer.preview.OpenFiles
import io.github.sakkijarvenpolkka.explorer.preview.ViewerDeps
import io.github.sakkijarvenpolkka.explorer.preview.trackedIn
import io.github.sakkijarvenpolkka.explorer.provider.DocumentIds
import io.github.sakkijarvenpolkka.explorer.provider.DocumentsNotifier
import io.github.sakkijarvenpolkka.explorer.provider.ProviderHost
import io.github.sakkijarvenpolkka.explorer.provider.ROOT_DOC_ID
import io.github.sakkijarvenpolkka.explorer.provider.favoritePaths
import io.github.sakkijarvenpolkka.explorer.provider.mountedRoots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

class ExplorerApp : Application(), RunnerHost, FileOpsHost, ProviderHost {

    /** Application-wide scope (work that outlives an activity). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The storage volumes, read on first use; [VolumeRegistry.start] follows their changes. */
    override val volumes: VolumeRegistry by lazy { VolumeRegistry(this) }

    private val settingsLazy = lazy { SettingsStore(this).also(::tellThePickerOfFavorites) }

    /**
     * Settings, favorites, recent files, folder views and the saved tabs, read on first use: not at every start of
     * the process, as they are read from a file. The system file picker hears of the favorites from then on.
     */
    override val settings: SettingsStore by settingsLazy

    /** Whether [settings] were read in this process (tests: not at its start). */
    internal val settingsRead: Boolean get() = settingsLazy.isInitialized()

    /** The Recycle Bin, one for the whole app: it knows which items are still going in. */
    override val bin: RecycleBin by lazy { RecycleBin(NioFsBackend, volumes, System::currentTimeMillis, ::newId) }

    /** The journal of the file operations, in `files/ops`. */
    override val journal: OperationJournal by lazy { OperationJournal(File(filesDir, "ops"), NioFsBackend) }

    /**
     * The saves other apps have in flight through the system file picker, for the whole app: the picker's calls on
     * those files wait for them, and neither the picker nor the app moves them away meanwhile.
     */
    override val openSaves = OpenSaves()

    /**
     * The file operations, journaled, with the Recycle Bin, ZIP archives and the names of the app's language; a move or
     * a deletion leaves alone what a save in flight uses ([openSaves]).
     */
    override val fileOps: FileOps by lazy {
        val labels = NameLabels(
            newFolder = getString(R.string.label_new_folder),
            newTextDocument = getString(R.string.label_new_text),
            copySuffix = getString(R.string.label_copy_suffix),
        )
        val zip = ZipOps(NioFsBackend, WriteProtocol(NioFsBackend, journal), labels, volumes)
        FileOps(NioFsBackend, volumes, labels, journal, ids = ::newId, bin = bin, zip = zip, inUse = openSaves::uses)
    }

    /** Runs the file operations, one at a time, outside any activity; the app hears of them ([RunnerHost]). */
    override val runner: OperationRunner by lazy {
        OperationRunner(appScope, fileOps, journal, Dispatchers.IO, host = this)
    }

    /** Tells the media store of what the operations made, on the volumes it indexes. */
    override val mediaScan: MediaScan by lazy {
        AndroidMediaScan(this, volumes) { root -> volumes.volumes.value.any { it.root == root && it.fuseBacked } }
    }

    /** How the thumbnails are made; a test puts its own before [thumbnails] is first used. */
    internal var thumbnailPort: ThumbnailPort = AndroidThumbnailPort()

    /** The thumbnails of the tiles and large icons views, for the whole app, in up to an eighth of the heap. */
    val thumbnails: Thumbnails by lazy {
        Thumbnails(thumbnailPort, Dispatchers.IO, (Runtime.getRuntime().maxMemory() / 8).toInt())
    }

    /** The files the viewers hold open, for the whole app: those on a volume that goes are closed with it. */
    val viewerFiles = OpenFiles()

    /**
     * What the built-in viewers read files with, for the whole app, keeping in [viewerFiles] what they hold
     * open. They hold no activity: each player gets the context of the shell that shows it.
     */
    val viewers: ViewerDeps by lazy {
        ViewerDeps(
            text = FsTextLoader(NioFsBackend, Dispatchers.IO),
            images = AndroidImageDecoder(Dispatchers.IO),
            pdf = AndroidPdfOpener(this, Dispatchers.IO),
            media = ::AndroidMediaPort,
            io = Dispatchers.IO,
        ).trackedIn(viewerFiles)
    }

    /** Whether the user was asked to allow notifications since the process started (main thread only). */
    internal var notificationsAsked = false

    /** How the foreground service is started; a test puts its own. */
    internal var serviceStarter = ServiceStarter(ContextCompat::startForegroundService)

    private val mutableOpsNotice = MutableStateFlow<OpsNotice?>(null)

    /**
     * What the file operations tell the user from outside any activity, kept until the shell has shown it
     * and the user dismissed it ([clearOpsNotice]): a shell that comes later still shows it.
     */
    val opsNotice: StateFlow<OpsNotice?> = mutableOpsNotice.asStateFlow()

    private val finishedOps = MutableSharedFlow<FinishedOp>(extraBufferCapacity = 64)

    /**
     * The operations as they end ([onFinished]), for another app's picker, which has no runner: it reads again what
     * they changed. One that ends while none listens is not kept.
     */
    val finished: SharedFlow<FinishedOp> = finishedOps.asSharedFlow()

    /** Guards [workWithoutService]. */
    private val serviceLock = Any()

    /**
     * The operations (by id) there were when the service last left them to the process (refused, or out
     * of time), while those go on without it; null while it runs them, or while there are none. One
     * submitted since starts it again ([restartServiceForNewWork]).
     */
    private var workWithoutService: Set<String>? = null

    /** Where each volume was last mounted, by uuid: one that ejects has no root any more. Main thread only. */
    private val lastRoots = HashMap<String, Path>()

    /**
     * Telling the system file picker, as the process starts, to read the root it lists the app by again ([onCreate]);
     * tests wait for it before they listen.
     */
    internal var rootsToldAtStart: Job? = null
        private set

    override fun onCreate() {
        super.onCreate()
        volumes.start()
        stopOperationsOnEject()
        restartServiceForNewWork()
        tellThePickerOfVolumes()
        // The access may have changed while no process ran: revoking it kills the app without a word, and the picker
        // keeps the root it read. It reads the roots again, at its next call at the latest. Nothing is read for that.
        rootsToldAtStart = appScope.launch(Dispatchers.IO) { DocumentsNotifier.roots(this@ExplorerApp) }
        // What a crash left behind is cleaned up, and the operations it interrupted are offered again.
        runner.loadInterrupted()
    }

    /** Whether the operation running uses [path] ([OperationSpec.uses]), for the picker's renames and deletes. */
    override fun busy(path: Path): Boolean = runner.state.value.running?.spec?.uses(path) == true

    /** Work came: it goes on in the foreground service ([startService]). */
    override fun onBusy() = startService()

    /** No work left: the service stops itself once it sees that, and no work goes on without it. */
    override fun onIdle() = synchronized(serviceLock) { workWithoutService = null }

    override fun onFinished(op: FinishedOp) {
        mediaScan.scan(scanTargets(op, volumes))
        tellThePickerOfFolders(op)
        // Never waits: it is called with the runner's lock held. A picker 64 behind misses what is past that.
        finishedOps.tryEmit(op)
    }

    /** The user is told to keep the app open: the operations go on in the process only. */
    override fun onForegroundRefused() {
        mutableOpsNotice.value = OpsNotice.ForegroundRefused
        leftWithoutService()
    }

    /**
     * The day's time for the service ran out: the work left, if any, goes on in the process only, slowly once
     * the screen is off, which the user is told (to open the app and retry what was stopped).
     */
    override fun onForegroundTimedOut() {
        if (workOf(runner.state.value).isNotEmpty()) mutableOpsNotice.value = OpsNotice.TimedOut
        leftWithoutService()
    }

    /** Forgets the notice of the file operations, once the user has dismissed it. */
    fun clearOpsNotice() {
        mutableOpsNotice.value = null
    }

    /**
     * Starts the foreground service, so that the work goes on with the app in the background. Refused
     * that, it goes on in the process all the same ([onForegroundRefused]). Called without [serviceLock]:
     * the start is a call to the system.
     */
    private fun startService() {
        try {
            serviceStarter.start(this, Intent(this, FileOpsService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app is not in the foreground.
            onForegroundRefused()
        }
    }

    /** The work there is now goes on without the service: the next operation submitted starts it again. */
    private fun leftWithoutService() = synchronized(serviceLock) {
        workWithoutService = workOf(runner.state.value).takeIf { it.isNotEmpty() }
    }

    /**
     * Starts the service again whenever an operation is submitted while the runner is busy without it,
     * which [onBusy] does not hear of: the user just did something, so the app is in the foreground now.
     * It wakes only when the work changes, not on each progress report. Whether to start is decided under
     * [serviceLock], where [onIdle] changes what it is compared with, and the work is then no longer
     * without the service (a refusal says so again), so that one submit starts the service once.
     */
    private fun restartServiceForNewWork() {
        appScope.launch(Dispatchers.Main.immediate) {
            runner.state.map(::workOf).distinctUntilChanged().collect {
                val start = synchronized(serviceLock) {
                    val before = workWithoutService ?: return@collect
                    workOf(runner.state.value).any { it !in before }.also { if (it) workWithoutService = null }
                }
                if (start) startService()
            }
        }
    }

    /** The ids of the operations [state] has queued or running. */
    private fun workOf(state: RunnerState): Set<String> =
        (state.queue.map { it.id } + listOfNotNull(state.running?.spec?.id)).toSet()

    /**
     * Stops the operations on a volume that goes as soon as it does ([OperationRunner.cancelTouching]):
     * vold kills the processes that still hold its files open a few seconds later. Its thumbnails go
     * too ([Thumbnails.evictUnder]): one that could not be made while it went is made again when it is
     * back. The files the viewers hold on it are closed ([OpenFiles.closeUnder]), as nothing else closes
     * them while the app is in the background. All run on the main thread, where the registry changes,
     * so a volume's root is noted before an event needs it.
     */
    private fun stopOperationsOnEject() {
        appScope.launch(Dispatchers.Main.immediate) {
            volumes.volumes.collect { list ->
                for (volume in list) volume.root?.let { lastRoots[volume.uuid] = it }
            }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            volumes.events.collect { event ->
                if (event is VolumeEvent.Ejecting) {
                    lastRoots[event.uuid]?.let { root ->
                        runner.cancelTouching(root, FsError.VolumeRemoved)
                        thumbnails.evictUnder(root)
                        viewerFiles.closeUnder(root)
                    }
                }
            }
        }
    }

    /**
     * Tells the system file picker whenever a volume comes or goes ([tellThePickerOfTheRoot]), and that every folder it
     * shows is to be read again (one on a drive that went says so then: the app's root stays), on the default
     * dispatcher. It listens from before [onCreate] returns, so that no event is missed.
     */
    private fun tellThePickerOfVolumes() {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            volumes.events.collect {
                tellThePickerOfTheRoot()
                DocumentsNotifier.allDocuments(this@ExplorerApp)
            }
        }
    }

    /**
     * Tells the system file picker whenever the favorites in [store] change ([tellThePickerOfTheRoot]), on the
     * default dispatcher. It listens from when [store] is made, before anything can change them.
     */
    private fun tellThePickerOfFavorites(store: SettingsStore) {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.favorites.drop(1).collect { tellThePickerOfTheRoot() }
        }
    }

    /**
     * Tells the system file picker that the folders [op] changed hold other items now: those its sources were in,
     * and those of what it made, that the picker is shown (pick spec §3.5); for one that stopped partway, also the
     * folder it was putting items in (a copy, a move or an extraction) or the one its archive was going to (a
     * compression); and the top folder when what it took away or made is a favorite or holds one. A picker open on
     * one does not go on showing what was there. Off the runner's lock, which [onFinished] is called with: naming a
     * folder reads the file system.
     */
    private fun tellThePickerOfFolders(op: FinishedOp) {
        val spec = op.spec
        val made = (op.result as? OpResult.Done)?.created.orEmpty()
        val sources = spec.sources.map { Paths.get(it) }
        val target = spec.target?.let { Paths.get(it) }.takeIf { op.result !is OpResult.Done }
        val into = target?.takeIf { spec.kind == OpKind.COPY || spec.kind == OpKind.MOVE || spec.kind == OpKind.EXTRACT }
        val archive = target?.takeIf { spec.kind == OpKind.COMPRESS }
        val gone = sources.takeIf { spec.kind in TAKES_AWAY }.orEmpty()
        tellThePicker(sources + made + listOfNotNull(archive), moved = gone + made, into = into)
    }

    /**
     * Tells the system file picker that the folders [paths] are in hold other items now, and its top folder too when
     * one of them is a favorite or holds one: what the app renamed or made itself, outside its operations (the old
     * name and the new). Off the main thread.
     */
    fun tellThePickerOf(paths: List<Path>) = tellThePicker(paths, moved = paths)

    /**
     * Tells the system file picker, off the main thread, that the folders [paths] are in hold other items now
     * ([DocumentsNotifier.parentsOf]), and the folder [into] itself; and its top folder too when one of [moved]
     * (renamed, moved, deleted or made) is a favorite or holds one ([DocumentsNotifier.favoritesAmong]).
     */
    private fun tellThePicker(paths: List<Path>, moved: List<Path>, into: Path? = null) {
        appScope.launch(Dispatchers.IO) {
            val mounted = volumes.volumes.value
            val ids = DocumentIds(NioFsBackend) { mountedRoots(mounted) }
            DocumentsNotifier.parentsOf(this@ExplorerApp, ids, paths)
            into?.let(ids::idOf)?.let { DocumentsNotifier.children(this@ExplorerApp, listOf(it)) }
            if (moved.isNotEmpty()) {
                val favorites = favoritePaths(settings.favorites.value, mounted)
                DocumentsNotifier.favoritesAmong(this@ExplorerApp, moved, favorites)
            }
        }
    }

    /**
     * Tells the system file picker that the root it lists the app by, and the favorites and the drives in it, are
     * to be read again (pick spec §3.1).
     */
    private fun tellThePickerOfTheRoot() {
        DocumentsNotifier.roots(this)
        DocumentsNotifier.children(this, listOf(ROOT_DOC_ID))
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        /** The operations whose sources are no longer where they were once done. */
        val TAKES_AWAY = setOf(OpKind.MOVE, OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT)
    }
}
