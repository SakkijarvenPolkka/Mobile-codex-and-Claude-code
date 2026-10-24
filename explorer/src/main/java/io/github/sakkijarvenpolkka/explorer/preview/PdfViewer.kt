/*
 * Files — the PDF viewer: the pages of a document one under another, each rendered as wide as the
 * view, zoomed by pinching (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The test tags of [PdfViewer]. */
object PdfViewerTags {
    /** The list of the pages. */
    const val PAGES = "pdf_pages"

    /** A page of the document. */
    const val PAGE = "pdf_page"
}

/** The proportions (width / height) of a page until its size is known: A4 portrait. */
private const val PLACEHOLDER_RATIO = 210f / 297f

/**
 * The tallest a page is shown, in pixels: Compose cannot measure a box 65,535 pixels tall or more
 * once it is over 8190 pixels wide (nor 262,143 when narrower). A taller page is shown this tall,
 * drawn narrower in the middle; it is rendered no more than 4096 pixels tall anyway.
 */
private const val MAX_PAGE_HEIGHT_PX = 60_000

/** The most the pages are enlarged by pinching. */
private const val MAX_ZOOM = 3f

/**
 * [file] opened by [opener] (on [io]: it blocks), its pages one under another in a list, each
 * rendered as wide as the list ([pdfRenderWidth]) and as tall as its proportions make it (A4 until
 * its size is known, 60,000 pixels at most); two fingers zoom the list (up to three times) and move
 * it. A password protected document, or one that cannot be read, says so, with an "Open with"
 * button when [onOpenWith] is given. The document is closed when the viewer leaves. The title row
 * has the name and a close button ([onClose]); a host that draws its own top bar sets [showTitle]
 * false (no title row, nor window insets).
 */
@Composable
fun PdfViewer(
    file: Path,
    opener: PdfOpener,
    onClose: () -> Unit,
    showTitle: Boolean = true,
    onOpenWith: (() -> Unit)? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    var state by remember(file, opener) { mutableStateOf<PdfState>(PdfState.Opening) }
    LaunchedEffect(file, opener) {
        // Set as soon as it is open, so that it is closed even when the viewer left meanwhile.
        var opened: PdfPort? = null
        try {
            val pdf = withContext(io) { opener.open(file).also { opened = it } }
            state = PdfState.Open(pdf)
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val encrypted = (e as? FsException)?.error == FsError.Encrypted
            state = PdfState.Failed(if (encrypted) R.string.pdf_encrypted else R.string.pdf_corrupt)
        } finally {
            opened?.close()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background)
            .then(if (showTitle) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        if (showTitle) TitleRow(file, onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val shown = state) {
                PdfState.Opening -> Unit
                is PdfState.Failed -> ViewerNotice(stringResource(shown.message), onOpenWith)
                is PdfState.Open -> Pages(file, shown.pdf)
            }
        }
    }
}

/** What [PdfViewer] shows while the document opens, once it is open, and when it could not be. */
private sealed interface PdfState {
    data object Opening : PdfState
    data class Open(val pdf: PdfPort) : PdfState

    /** [message] is the string resource that says why. */
    data class Failed(val message: Int) : PdfState
}

/** The pages of [pdf] in a list as wide as the view, zoomed by pinching. */
@Composable
private fun Pages(file: Path, pdf: PdfPort) {
    val list = rememberSaveable(file, saver = LazyListState.Saver) { LazyListState() }
    val zoom = remember(pdf) { PagesZoom() }
    // The sizes measured, so that a page scrolled back to has its height at once.
    val sizes = remember(pdf) { mutableStateMapOf<Int, IntSize>() }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().pinchToZoom(zoom)) {
        val width = constraints.maxWidth
        LazyColumn(
            Modifier
                .fillMaxSize()
                .testTag(PdfViewerTags.PAGES)
                .graphicsLayer {
                    scaleX = zoom.zoom
                    scaleY = zoom.zoom
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                },
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pdf.pageCount) { index -> Page(pdf, index, width, sizes) }
        }
    }
}

/**
 * Page [index] of [pdf], [widthPx] wide: white in the proportions of its size (in [sizes], measured
 * there first; no taller than [MAX_PAGE_HEIGHT_PX]), with the page drawn in its own proportions once
 * it is rendered.
 */
@Composable
private fun Page(pdf: PdfPort, index: Int, widthPx: Int, sizes: SnapshotStateMap<Int, IntSize>) {
    var bitmap by remember(pdf, index, widthPx) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(pdf, index) { mutableStateOf(false) }
    LaunchedEffect(pdf, index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        try {
            val size = sizes[index] ?: pdf.pageSize(index).also { sizes[index] = it }
            bitmap = pdf.render(index, pdfRenderWidth(size.width, size.height, widthPx)).asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val size = sizes[index]
    val ratio = if (size != null && size.width > 0 && size.height > 0) {
        (size.width.toFloat() / size.height).coerceAtLeast(widthPx.toFloat() / MAX_PAGE_HEIGHT_PX)
    } else {
        PLACEHOLDER_RATIO
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White).testTag(PdfViewerTags.PAGE),
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        when {
            shown != null -> Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            failed -> BasicText(
                stringResource(R.string.pdf_corrupt),
                Modifier.padding(16.dp),
                style = FluentTheme.type.body.copy(color = Color.DarkGray, textAlign = TextAlign.Center),
            )
        }
    }
}

/** How far the pages are zoomed in ([zoom] times their width, at least 1) and moved ([offset] pixels from the centre). */
@Stable
private class PagesZoom {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    /**
     * Zooms by [change] about [centroid] and moves by [pan] (pixels of a [viewport]-sized view),
     * keeping the pages over the view.
     */
    fun transform(centroid: Offset, pan: Offset, change: Float, viewport: IntSize) {
        val target = (zoom * change).coerceIn(1f, MAX_ZOOM)
        val factor = target / zoom
        // The point under the fingers stays under them.
        val fromCentre = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        val moved = fromCentre * (1 - factor) + offset * factor + pan
        val x = viewport.width * (target - 1) / 2
        val y = viewport.height * (target - 1) / 2
        offset = Offset(moved.x.coerceIn(-x, x), moved.y.coerceIn(-y, y))
        zoom = target
    }
}

/**
 * Two fingers zoom and move [zoom], and then scroll nothing (they are seen before the list sees
 * them); one finger scrolls the list as before.
 */
private fun Modifier.pinchToZoom(zoom: PagesZoom): Modifier = pointerInput(zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom(), size)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
