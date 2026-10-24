/*
 * Files — the audio and video player: the video (or an audio icon) over Fluent controls to play,
 * pause and seek, with the time played and the length; it pauses when the app goes to the
 * background and comes back where it was (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcon
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentProgressBar
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import kotlinx.coroutines.delay
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

/** The test tags of [MediaViewer]. */
object MediaViewerTags {
    /** The seek bar: touched or dragged to move in the file. */
    const val SEEK_BAR = "media_seek_bar"
}

/** How often the time played is read while the file plays, in milliseconds. */
private const val TICK_MS = 250L

/**
 * [file] (a [video] or an audio file) played by [port]: the video, or an audio icon, over the
 * controls: play/pause, the time played, a seek bar to touch or drag, and the length. They work once
 * [port] has prepared the file; it plays when asked to. When the app goes to the background it
 * pauses and keeps the position, which a file prepared anew (a video whose surface came back, the
 * viewer made again after a configuration change) goes back to; a video's controls wait for that,
 * as its player went with its surface. A file that cannot be played says so, with an "Open with"
 * button when [onOpenWith] is given. [port] is released when the viewer leaves, and one port feeds
 * one viewer (and so one [MediaPort.VideoSurface]). The title row has the name and a close button
 * ([onClose]); a host that draws its own top bar sets [showTitle] false (no title row, nor window
 * insets).
 */
@Composable
fun MediaViewer(
    file: Path,
    video: Boolean,
    port: MediaPort,
    onClose: () -> Unit,
    showTitle: Boolean = true,
    onOpenWith: (() -> Unit)? = null,
) {
    // Made anew whenever the effect below prepares the file anew: it has the same keys.
    var state by remember(file, video, port) { mutableStateOf<MediaState>(MediaState.Preparing) }
    var playing by remember(file, video, port) { mutableStateOf(false) }
    var position by remember(file, video, port) { mutableIntStateOf(0) }
    // Where it was when the app went to the background.
    var resumeAt by rememberSaveable(file) { mutableStateOf<Int?>(null) }
    DisposableEffect(file, video, port) {
        port.prepare(
            file,
            video,
            onReady = { duration ->
                state = MediaState.Ready(duration)
                resumeAt?.let {
                    port.seekTo(it)
                    position = it
                    resumeAt = null
                }
            },
            onError = {
                state = MediaState.Failed
                playing = false
            },
        )
        onDispose { port.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val ready = state as? MediaState.Ready ?: return@LifecycleEventEffect
        port.pause()
        playing = false
        position = port.positionMs
        resumeAt = position
        // A video's player goes with its surface, and is prepared anew when the app comes back.
        if (video) state = MediaState.Reopening(ready.durationMs)
    }
    // While it plays, the time played moves on, and it may stop by itself (at the end, or for the
    // audio focus or the headphones).
    LaunchedEffect(port, playing) {
        while (playing) {
            delay(TICK_MS)
            position = port.positionMs
            playing = port.isPlaying
        }
    }
    val colors = FluentTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .then(if (showTitle) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier),
    ) {
        if (showTitle) TitleRow(file, onClose)
        val shown = state
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                shown == MediaState.Failed -> ViewerNotice(stringResource(R.string.media_error), onOpenWith)
                video -> port.VideoSurface(Modifier.fillMaxSize())
                else -> FluentIcon(FluentIcons.Audio, contentDescription = null, Modifier.size(96.dp), tint = colors.textSecondary)
            }
        }
        if (shown != MediaState.Failed) {
            val length = when (shown) {
                is MediaState.Ready -> shown.durationMs
                is MediaState.Reopening -> shown.durationMs
                else -> null
            }
            Controls(
                durationMs = length,
                enabled = shown is MediaState.Ready,
                playing = playing,
                positionMs = position,
                onPlayPause = {
                    if (playing) port.pause() else port.play()
                    // Playing may be refused (another app holds the audio focus).
                    playing = port.isPlaying
                    position = port.positionMs
                },
                onSeek = { ms ->
                    port.seekTo(ms)
                    position = ms
                },
            )
        }
    }
}

/**
 * What [MediaViewer] shows while the file is prepared, once it is ready to play, while a video that was
 * ready is prepared again after the app was in the background, and when it cannot be played.
 */
private sealed interface MediaState {
    data object Preparing : MediaState
    data class Ready(val durationMs: Int) : MediaState
    data class Reopening(val durationMs: Int) : MediaState
    data object Failed : MediaState
}

/**
 * Play/pause, the time played ([positionMs]), the seek bar and the length ([durationMs], null while
 * the file is prepared); all but the times work only while [enabled].
 */
@Composable
private fun Controls(
    durationMs: Int?,
    enabled: Boolean,
    playing: Boolean,
    positionMs: Int,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    val timeStyle = FluentTheme.type.caption.copy(color = FluentTheme.colors.textSecondary)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluentButton(
            text = null,
            onClick = onPlayPause,
            icon = if (playing) FluentIcons.Pause else FluentIcons.Play,
            style = ButtonStyle.SUBTLE,
            enabled = enabled,
            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
        )
        BasicText(playbackTime(positionMs), style = timeStyle)
        SeekBar(positionMs, durationMs ?: 0, enabled, onSeek, Modifier.weight(1f))
        BasicText(playbackTime(durationMs ?: 0), style = timeStyle)
    }
}

/**
 * [positionMs] of [durationMs] as a progress bar that a touch or a drag (or an accessibility
 * service) moves: [onSeek] hears the time picked, once the drag ends. Disabled without a length,
 * and unless [usable].
 */
@Composable
private fun SeekBar(positionMs: Int, durationMs: Int, usable: Boolean, onSeek: (Int) -> Unit, modifier: Modifier) {
    val enabled = usable && durationMs > 0
    // Where a drag has got to, a share of the length.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: if (enabled) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val seek by rememberUpdatedState { share: Float -> onSeek((share.coerceIn(0f, 1f) * durationMs).roundToInt()) }

    /** The share of the length at [x] pixels of a bar [width] pixels wide, from its start edge. */
    fun shareAt(x: Float, width: Int): Float = (x / width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .testTag(MediaViewerTags.SEEK_BAR)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) {
                    setProgress { share ->
                        seek(share)
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, rtl) {
                if (enabled) detectTapGestures { seek(shareAt(it.x, size.width)) }
            }
            .pointerInput(enabled, rtl) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragged = shareAt(it.x, size.width) },
                    onDragEnd = {
                        dragged?.let(seek)
                        dragged = null
                    },
                    onDragCancel = { dragged = null },
                ) { change, _ ->
                    change.consume()
                    dragged = shareAt(change.position.x, size.width)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        FluentProgressBar(shown, Modifier.fillMaxWidth())
    }
}

/** [ms] as `m:ss`, or `h:mm:ss` from an hour on. */
private fun playbackTime(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val hours = seconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
