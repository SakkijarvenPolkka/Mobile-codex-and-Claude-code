/*
 * Files — which files the built-in viewers show, what they need, and the full screen viewer with its
 * top bar: the name, Share, Open with, Properties and Close (spec §7).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.fs.isTextCandidate
import io.github.sakkijarvenpolkka.explorer.ui.fluent.ButtonStyle
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentButton
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentIcons
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import io.github.sakkijarvenpolkka.explorer.util.DisplayName
import io.github.sakkijarvenpolkka.explorer.util.baseMimeType
import kotlinx.coroutines.CoroutineDispatcher

/** The test tags of [ViewerScreen] and [PreviewPane]. */
object ViewerTags {
    /** The full screen viewer. */
    const val SCREEN = "viewer"

    /** The preview pane. */
    const val PANE = "preview_pane"
}

/** Which built-in viewer shows a file ([previewKindFor]). */
sealed interface PreviewKind {
    data object Image : PreviewKind
    data object Text : PreviewKind
    data object Audio : PreviewKind
    data object Video : PreviewKind
    data object Pdf : PreviewKind
}

/** The pictures [ImageViewer] shows: those `ImageDecoder` decodes (Android's type table calls BMP `image/x-ms-bmp`). */
private val IMAGE_TYPES = setOf(
    "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp", "image/x-ms-bmp",
    "image/avif",
)

/**
 * The viewer that shows [entry], a file, by its MIME type (parameters and case ignored), in this order:
 * the pictures `ImageDecoder` decodes, any video, any audio, PDF, then text ([isTextCandidate], by type
 * or extension), so that a `.ts` video (`video/mp2t`) is not read as TypeScript. Null for a folder, a
 * link, and any other file, which another app opens.
 */
fun previewKindFor(entry: FileEntry): PreviewKind? {
    if (entry.kind != EntryKind.FILE) return null
    val mime = baseMimeType(entry.mime)
    return when {
        mime in IMAGE_TYPES -> PreviewKind.Image
        mime?.startsWith("video/") == true -> PreviewKind.Video
        mime?.startsWith("audio/") == true -> PreviewKind.Audio
        mime == "application/pdf" -> PreviewKind.Pdf
        isTextCandidate(entry.name, entry.mime) -> PreviewKind.Text
        else -> null
    }
}

/** [files] when [entry] is among them, else [entry] alone: what a viewer opened on [entry] swipes through. */
internal fun siblingsOrSelf(entry: FileEntry, files: List<FileEntry>): List<FileEntry> =
    files.takeIf { list -> list.any { it.path == entry.path } } ?: listOf(entry)

/** [entry] to show full screen, with the files to swipe to from it ([siblings], [entry] among them). */
data class ViewerRequest(val entry: FileEntry, val siblings: List<FileEntry>)

/**
 * What the viewers read files with: [text] files, [images], [pdf] documents, and a new player for each
 * audio or video file shown ([media], given the activity's context, which a video view is made with).
 * [io] is where the calls that block run.
 */
data class ViewerDeps(
    val text: TextLoader,
    val images: ImageDecoderPort,
    val pdf: PdfOpener,
    val media: (Context) -> MediaPort,
    val io: CoroutineDispatcher,
)

/**
 * The file of [req] full screen, in the viewer its kind has ([previewKindFor]); a picture swipes to the
 * other pictures of [ViewerRequest.siblings]. The top bar has the name of the file shown, then Share
 * ([onShare]), Open with ([onOpenWith]) and Properties ([onProperties]) for it, and Close ([onClose]);
 * the viewer under it draws no title of its own. Clear of the system bars and the display cutout.
 */
@Composable
fun ViewerScreen(
    req: ViewerRequest,
    deps: ViewerDeps,
    onClose: () -> Unit,
    onShare: (FileEntry) -> Unit,
    onOpenWith: (FileEntry) -> Unit,
    onProperties: (FileEntry) -> Unit,
) {
    // The picture swiped to, else the file opened.
    var current by remember(req) { mutableStateOf(req.entry) }
    Column(
        Modifier
            .fillMaxSize()
            .background(FluentTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(ViewerTags.SCREEN),
    ) {
        TopBar(current, onShare, onOpenWith, onProperties, onClose)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ViewerContent(
                entry = req.entry,
                siblings = req.siblings,
                deps = deps,
                onClose = onClose,
                onOpenWith = { onOpenWith(current) },
                onCurrentChange = { current = it },
            )
        }
    }
}

/** The name of [entry], then the buttons that act on it, and Close. */
@Composable
private fun TopBar(
    entry: FileEntry,
    onShare: (FileEntry) -> Unit,
    onOpenWith: (FileEntry) -> Unit,
    onProperties: (FileEntry) -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            DisplayName.of(entry.name),
            modifier = Modifier.weight(1f),
            style = FluentTheme.type.bodyStrong.copy(color = FluentTheme.colors.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BarButton(FluentIcons.Share, stringResource(R.string.cmd_share)) { onShare(entry) }
        FluentButton(stringResource(R.string.cmd_open_with), { onOpenWith(entry) }, style = ButtonStyle.SUBTLE)
        BarButton(FluentIcons.Info, stringResource(R.string.cmd_properties)) { onProperties(entry) }
        BarButton(FluentIcons.Close, stringResource(R.string.close), onClose)
    }
}

/** A button of the top bar showing [icon], read as [label]. */
@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FluentButton(null, onClick, icon = icon, style = ButtonStyle.SUBTLE, contentDescription = label)
}

/**
 * [entry] in the viewer of its kind, with no title row nor window insets (its host draws those): a
 * picture among [siblings] to swipe through (telling [onCurrentChange] which one shows), a text file, a
 * PDF, or an audio or video file on a player of its own from [ViewerDeps.media]. A file no viewer shows
 * says so. [onOpenWith] is the "Open with" of a file a viewer cannot show after all.
 */
@Composable
internal fun ViewerContent(
    entry: FileEntry,
    siblings: List<FileEntry>,
    deps: ViewerDeps,
    onClose: () -> Unit,
    onOpenWith: (() -> Unit)?,
    onCurrentChange: (FileEntry) -> Unit = {},
) {
    when (val kind = previewKindFor(entry)) {
        PreviewKind.Image -> {
            val files = siblingsOrSelf(entry, siblings)
            val start = files.indexOfFirst { it.path == entry.path }
            ImageViewer(files, start, deps.images, onClose, showTitle = false, onOpenWith, onCurrentChange)
        }
        PreviewKind.Text -> TextViewer(entry.path, deps.text, onClose, showTitle = false, onOpenWith)
        PreviewKind.Pdf -> PdfViewer(entry.path, deps.pdf, onClose, showTitle = false, onOpenWith, deps.io)
        PreviewKind.Audio, PreviewKind.Video -> {
            val context = LocalContext.current
            val port = remember(entry.path, deps, context) { deps.media(context) }
            MediaViewer(entry.path, kind == PreviewKind.Video, port, onClose, showTitle = false, onOpenWith)
        }
        null -> ViewerNotice(stringResource(R.string.preview_unavailable), onOpenWith)
    }
}
