/*
 * Files — the files the viewers hold open, let go of when their volume goes (spec §6.3).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The files the viewers hold open: the PDF documents and the players of [ViewerDeps.trackedIn]. A
 * viewer lets go of its file when it leaves the screen, which does not happen while the app is in the
 * background (nothing is drawn then), and vold kills a process that still holds files open on a volume
 * a few seconds after the volume starts to go. So when one goes, [closeUnder] lets go of those on it at
 * once. Thread safe.
 */
class OpenFiles {
    /** What holds a file open, and the file. */
    private val open = HashMap<Closeable, Path>()

    /** [holder] holds [file] open until it is closed. */
    internal fun opened(file: Path, holder: Closeable) {
        synchronized(open) { open[holder] = file }
    }

    /** [holder] let go of its file. */
    internal fun closed(holder: Closeable) {
        synchronized(open) { open.remove(holder) }
    }

    /**
     * Closes what holds a file under [root] open, on the thread that calls it: the main thread, where
     * players are released. A viewer still composed can read its file no more; the view model closes
     * the viewers of a volume that goes anyway.
     */
    fun closeUnder(root: Path) {
        val going = synchronized(open) { open.filterValues { it.startsWith(root) }.keys.toList() }
        going.forEach(Closeable::close)
    }
}

/**
 * These parts, with the documents of [ViewerDeps.pdf] and the players of [ViewerDeps.media] kept in
 * [files] while they hold a file open, for [OpenFiles.closeUnder].
 */
fun ViewerDeps.trackedIn(files: OpenFiles): ViewerDeps {
    val opener = pdf
    val players = media
    return copy(
        pdf = PdfOpener { file -> TrackedPdf(opener.open(file), file, files) },
        media = { context -> TrackedPlayer(players(context), files) },
    )
}

/** [pdf], open on [file], kept in [files] until it is closed: once, by its viewer or by [OpenFiles.closeUnder]. */
private class TrackedPdf(private val pdf: PdfPort, file: Path, private val files: OpenFiles) : PdfPort by pdf {
    private val closed = AtomicBoolean(false)

    init {
        files.opened(file, this)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            files.closed(this)
            pdf.close()
        }
    }
}

/**
 * [port], kept in [files] from when it prepares a file until it is released: once, by its viewer or by
 * [OpenFiles.closeUnder] (which closes it). Main thread only, as players are.
 */
private class TrackedPlayer(private val port: MediaPort, private val files: OpenFiles) : MediaPort by port, Closeable {
    private var released = false

    override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) {
        files.opened(file, this)
        port.prepare(file, video, onReady, onError)
    }

    override fun release() {
        if (released) return
        released = true
        files.closed(this)
        port.release()
    }

    override fun close() = release()

    @Composable
    override fun VideoSurface(modifier: Modifier) = port.VideoSurface(modifier)
}
