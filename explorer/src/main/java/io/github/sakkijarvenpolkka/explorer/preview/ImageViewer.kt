/*
 * Files — the image viewer: the images of a folder side by side to swipe through, each decoded to
 * the size of the screen, zoomed by pinching (up to twice its own pixels) or double tapping (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import kotlinx.coroutines.CancellationException
import java.util.Locale
import kotlin.math.max

/** The test tags of [ImageViewer]. */
object ImageViewerTags {
    /** The pager the images are swiped in. */
    const val PAGER = "image_pager"

    /** The image shown on a page, described by its file name, with its [ImageZoom]. */
    const val IMAGE = "image"
}

/** How far the image on a page is zoomed in, as a multiple of its size when it fits the page (1). */
val ImageZoom = SemanticsPropertyKey<Float>("ImageZoom")
private var SemanticsPropertyReceiver.imageZoom by ImageZoom

/** The most an image is enlarged: two pixels of the screen for each pixel of the image. */
private const val MAX_SCALE = 2f

/** How much a double tap zooms in, from the image fitting the page. */
private const val DOUBLE_TAP_ZOOM = 2f

/** The types whose files may be animated, which are decoded as animations first. */
private val ANIMATABLE_TYPES = setOf("image/gif", "image/webp")

/**
 * The images [files] (the folder's images in its order), from the one at [startIndex], swiped
 * through sideways. Each is decoded by [decoder] at the size of the page, fitted to it (enlarged
 * at most twice its own pixels), and zoomed by pinching, up to twice its own pixels, or by a
 * double tap, which toggles between the fit and a zoom on the tapped point; zoomed, it pans with
 * one finger instead of swiping. GIF and WebP files that are animated play. An image that cannot
 * be decoded says so, with an "Open with" button when [onOpenWith] is given (for the image
 * shown). [onCurrentChange] hears of the image shown, first the one at [startIndex], so that a host
 * that draws its own top bar ([showTitle] false: no title row, nor window insets) knows it; the
 * title row has the name and a close button ([onClose]).
 */
@Composable
fun ImageViewer(
    files: List<FileEntry>,
    startIndex: Int,
    decoder: ImageDecoderPort,
    onClose: () -> Unit,
    showTitle: Boolean = true,
    onOpenWith: (() -> Unit)? = null,
    onCurrentChange: (FileEntry) -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, max(0, files.lastIndex))) { files.size }
    val current by rememberUpdatedState(onCurrentChange)
    LaunchedEffect(pager, files) {
        snapshotFlow { pager.currentPage }.collect { page -> files.getOrNull(page)?.let(current) }
    }
    // Only the page swiped to zooms, and the pager stays on it while it does.
    val zoom = remember(pager.settledPage, files) { ZoomState() }
    Column(
        Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background)
            .then(if (showTitle) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        val shown = files.getOrNull(pager.currentPage)
        if (showTitle && shown != null) TitleRow(shown.path, onClose)
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(ImageViewerTags.PAGER),
            userScrollEnabled = !zoom.zoomed,
            key = { files[it].path.toString() },
        ) { page ->
            ImagePage(files[page], decoder, zoom.takeIf { page == pager.settledPage }, onOpenWith)
        }
    }
}

/** The zoom of the image on the settled page: [zoom] times its fitted size, moved [offset] pixels from the centre. */
@Stable
private class ZoomState {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    val zoomed: Boolean get() = zoom > 1f
}

/** What a page shows while its image is decoded, once it is, and when it could not be. */
private sealed interface Page {
    data object Loading : Page

    /** [original] is the image's own size in pixels; [still] or [animation] is what is drawn. */
    data class Shown(val original: IntSize, val still: ImageBitmap?, val animation: AnimatedImageDrawable?) : Page

    data object Failed : Page
}

/**
 * The page of [entry]: decoded for the size of the page (again when that changes), drawn fitted,
 * zoomed with [zoom] (none: a page being swiped past, which only fits).
 */
@Composable
private fun ImagePage(entry: FileEntry, decoder: ImageDecoderPort, zoom: ZoomState?, onOpenWith: (() -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
        var page by remember(entry.path, decoder) { mutableStateOf<Page>(Page.Loading) }
        LaunchedEffect(entry.path, decoder, viewport) {
            if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
            page = try {
                decodePage(entry, decoder, viewport)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Page.Failed
            }
        }
        when (val shown = page) {
            Page.Loading -> Unit
            Page.Failed -> DecodeError(onOpenWith)
            is Page.Shown -> ZoomableImage(entry, shown, viewport, zoom)
        }
    }
}

/** Decodes [entry] for a page of [viewport] pixels: as an animation first when its type may be one. */
private suspend fun decodePage(entry: FileEntry, decoder: ImageDecoderPort, viewport: IntSize): Page.Shown {
    val original = decoder.sizeOf(entry.path)
    val mime = entry.mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    val (width, height) = viewport
    val animation = if (mime in ANIMATABLE_TYPES) decoder.decodeAnimation(entry.path, width, height) else null
    val still = if (animation == null) decoder.decode(entry.path, width, height) else null
    return Page.Shown(original, still, animation)
}

/**
 * The image of [shown] fitted to [viewport] (pixels of the screen per pixel of the image: what fits,
 * at most [MAX_SCALE]), zoomed by [zoom] from there up to [MAX_SCALE] pixels of the screen per pixel
 * of the image, and moved no further than keeps it over the page.
 */
@Composable
private fun ZoomableImage(entry: FileEntry, shown: Page.Shown, viewport: IntSize, zoom: ZoomState?) {
    val original = shown.original
    val fit = minOf(viewport.width.toFloat() / original.width, viewport.height.toFloat() / original.height, MAX_SCALE)
    val maxZoom = max(1f, MAX_SCALE / fit)
    val fitted = Offset(original.width * fit, original.height * fit)

    /** [offset] moved no further than keeps the image, zoomed [scale] times, over the page where it can. */
    fun bounded(offset: Offset, scale: Float): Offset {
        val x = max(0f, (fitted.x * scale - viewport.width) / 2)
        val y = max(0f, (fitted.y * scale - viewport.height) / 2)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        if (zoom == null) return@rememberTransformableState
        val scale = (zoom.zoom * zoomChange).coerceIn(1f, maxZoom)
        zoom.offset = bounded(zoom.offset * (scale / zoom.zoom) + panChange, scale)
        zoom.zoom = scale
    }
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val name = DisplayName.of(entry.name)
    val scale = zoom?.zoom ?: 1f
    Box(
        Modifier
            .fillMaxSize()
            .testTag(ImageViewerTags.IMAGE)
            .semantics {
                contentDescription = name
                role = Role.Image
                imageZoom = scale
            }
            .pointerInput(zoom, viewport, original) {
                if (zoom == null) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (zoom.zoomed) {
                            zoom.zoom = 1f
                            zoom.offset = Offset.Zero
                        } else {
                            // The point tapped stays under the finger, as far as the edges let it.
                            val target = minOf(DOUBLE_TAP_ZOOM, maxZoom)
                            val point = (tap - center - zoom.offset) / zoom.zoom
                            zoom.offset = bounded(tap - center - point * target, target)
                            zoom.zoom = target
                        }
                    },
                )
            }
            .transformable(transform, canPan = { zoom?.zoomed == true }, enabled = zoom != null),
        contentAlignment = Alignment.Center,
    ) {
        val size = with(LocalDensity.current) { Modifier.size(fitted.x.toDp(), fitted.y.toDp()) }
        val layer = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = zoom?.offset?.x ?: 0f
            translationY = zoom?.offset?.y ?: 0f
        }
        when {
            shown.animation != null -> Animation(shown.animation, size.then(layer))
            shown.still != null -> Image(shown.still, null, size.then(layer), contentScale = ContentScale.FillBounds)
        }
    }
}

/** Plays [drawable] in [modifier]'s bounds while it is shown. */
@Composable
private fun Animation(drawable: AnimatedImageDrawable, modifier: Modifier) {
    var frame by remember(drawable) { mutableIntStateOf(0) }
    // Held here: a drawable keeps its callback only weakly.
    val callback = remember(drawable) {
        val handler = Handler(Looper.getMainLooper())
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                frame++
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, who, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what, who)
            }
        }
    }
    DisposableEffect(drawable, callback) {
        drawable.callback = callback
        drawable.start()
        onDispose {
            drawable.stop()
            drawable.callback = null
        }
    }
    Canvas(modifier) {
        // Read so that each frame the drawable asks for draws again.
        frame
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

/** "The image can't be displayed", with an "Open with" button when [onOpenWith] is given. */
@Composable
private fun DecodeError(onOpenWith: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            stringResource(R.string.image_error),
            style = FluentTheme.type.body.copy(color = FluentTheme.colors.textSecondary, textAlign = TextAlign.Center),
        )
        if (onOpenWith != null) FluentButton(stringResource(R.string.cmd_open_with), onClick = onOpenWith)
    }
}
