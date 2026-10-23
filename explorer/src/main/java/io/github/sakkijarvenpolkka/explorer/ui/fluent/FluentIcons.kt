/*
 * Files — icons drawn for this app on a 24 × 24 grid in the outline style of Fluent icons,
 * the composable that draws them, and the icon of a folder item.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ui.fluent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.explorer.fs.EntryKind
import io.github.sakkijarvenpolkka.explorer.fs.FileEntry
import io.github.sakkijarvenpolkka.explorer.util.extensionOf
import java.util.Locale

/**
 * Draws [icon] in [tint] (20 dp unless [modifier] sizes it). A [contentDescription] makes it
 * an image for accessibility services; null marks it decorative.
 */
@Composable
fun FluentIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = FluentTheme.colors.text,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(20.dp),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * The app's icons, drawn in one colour (black, tinted when drawn) on a 24 × 24 viewport with
 * Fluent proportions: mostly 1.5 unit outlines with round caps and corners.
 */
object FluentIcons {

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroke {
                moveTo(3f, 7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 5f)
                horizontalLineTo(9.2f)
                lineTo(11.2f, 7f)
                horizontalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 21f, 9f)
                verticalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 19f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f)
                close()
                moveTo(3f, 9f)
                horizontalLineTo(9.2f)
                lineTo(11.2f, 7f)
            }
        }
    }

    val FolderOpen: ImageVector by lazy {
        icon("FolderOpen") {
            stroke {
                moveTo(3f, 17f)
                verticalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 5f)
                horizontalLineTo(9.2f)
                lineTo(11.2f, 7f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 9f)
                verticalLineTo(10.5f)
                moveTo(3f, 17f)
                lineTo(5.4f, 11.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 7f, 10.5f)
                horizontalLineTo(20.4f)
                arcTo(1f, 1f, 0f, false, true, 21.3f, 11.9f)
                lineTo(19f, 17.8f)
                arcTo(2f, 2f, 0f, false, true, 17.2f, 19f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 3f, 17f)
                close()
            }
        }
    }

    val File: ImageVector by lazy { icon("File") { stroke { page() } } }

    val Image: ImageVector by lazy {
        icon("Image") {
            stroke {
                roundRect(3f, 4f, 21f, 20f, 3f)
                circle(15.5f, 8.75f, 1.5f)
                moveTo(3.5f, 17f)
                lineTo(8.6f, 12f)
                quadTo(9.4f, 11.3f, 10.2f, 12f)
                lineTo(17.5f, 19.5f)
                moveTo(13.6f, 15.3f)
                lineTo(15.2f, 13.7f)
                quadTo(16f, 13f, 16.8f, 13.7f)
                lineTo(20.5f, 17.2f)
            }
        }
    }

    val Video: ImageVector by lazy {
        icon("Video") {
            stroke {
                roundRect(2.5f, 6f, 15.5f, 18f, 2.5f)
                moveTo(15.5f, 10.2f)
                lineTo(19.95f, 7.55f)
                curveTo(20.6f, 7.15f, 21.5f, 7.6f, 21.5f, 8.4f)
                verticalLineTo(15.6f)
                curveTo(21.5f, 16.4f, 20.6f, 16.85f, 19.95f, 16.45f)
                lineTo(15.5f, 13.8f)
            }
        }
    }

    val Audio: ImageVector by lazy {
        icon("Audio") {
            stroke {
                circle(7.5f, 17.5f, 2.5f)
                circle(16.5f, 15.5f, 2.5f)
                moveTo(10f, 17.5f)
                verticalLineTo(6f)
                lineTo(19f, 4f)
                verticalLineTo(15.5f)
                moveTo(10f, 9f)
                lineTo(19f, 7f)
            }
        }
    }

    val Text: ImageVector by lazy {
        icon("Text") {
            stroke {
                page()
                moveTo(8.5f, 12.5f)
                horizontalLineTo(15.5f)
                moveTo(8.5f, 15.5f)
                horizontalLineTo(15.5f)
                moveTo(8.5f, 18f)
                horizontalLineTo(12.5f)
            }
        }
    }

    val Pdf: ImageVector by lazy {
        icon("Pdf") {
            stroke { page() }
            stroke(width = 1f) {
                // P
                moveTo(6.75f, 18f)
                verticalLineTo(13f)
                horizontalLineTo(7.75f)
                arcTo(1f, 1.25f, 0f, false, true, 7.75f, 15.5f)
                horizontalLineTo(6.75f)
                // D
                moveTo(11f, 13f)
                verticalLineTo(18f)
                horizontalLineTo(11.4f)
                arcTo(1.8f, 2.5f, 0f, false, false, 11.4f, 13f)
                close()
                // F
                moveTo(15.45f, 18f)
                verticalLineTo(13f)
                horizontalLineTo(17.25f)
                moveTo(15.45f, 15.3f)
                horizontalLineTo(16.85f)
            }
        }
    }

    val Zip: ImageVector by lazy {
        icon("Zip") {
            stroke {
                page()
                roundRect(8.5f, 10.5f, 11.5f, 15f, 1f)
                moveTo(10f, 12.25f)
                verticalLineTo(13.25f)
            }
            fill {
                rect(9f, 3.75f, 10f, 5.25f)
                rect(10f, 5.25f, 11f, 6.75f)
                rect(9f, 6.75f, 10f, 8.25f)
                rect(10f, 8.25f, 11f, 9.75f)
            }
        }
    }

    /** An app package: a box. */
    val Apk: ImageVector by lazy {
        icon("Apk") {
            stroke {
                moveTo(12f, 3f)
                lineTo(20f, 7.5f)
                verticalLineTo(16.5f)
                lineTo(12f, 21f)
                lineTo(4f, 16.5f)
                verticalLineTo(7.5f)
                close()
                moveTo(4f, 7.5f)
                lineTo(12f, 12f)
                lineTo(20f, 7.5f)
                moveTo(12f, 12f)
                verticalLineTo(21f)
            }
        }
    }

    val Code: ImageVector by lazy {
        icon("Code") {
            stroke {
                page()
                moveTo(9.5f, 12f)
                lineTo(7.5f, 14.75f)
                lineTo(9.5f, 17.5f)
                moveTo(14.5f, 12f)
                lineTo(16.5f, 14.75f)
                lineTo(14.5f, 17.5f)
                moveTo(12.8f, 11.5f)
                lineTo(11.2f, 18f)
            }
        }
    }

    val Drive: ImageVector by lazy {
        icon("Drive") {
            stroke {
                roundRect(3f, 6.5f, 21f, 17.5f, 2.5f)
                moveTo(3f, 13f)
                horizontalLineTo(21f)
            }
            fill { circle(17.5f, 15.25f, 1f) }
        }
    }

    val SdCard: ImageVector by lazy {
        icon("SdCard") {
            stroke {
                moveTo(9f, 3f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, true, 19f, 5f)
                verticalLineTo(19f)
                arcTo(2f, 2f, 0f, false, true, 17f, 21f)
                horizontalLineTo(7f)
                arcTo(2f, 2f, 0f, false, true, 5f, 19f)
                verticalLineTo(7f)
                close()
                moveTo(10f, 6.5f)
                verticalLineTo(9f)
                moveTo(13f, 6.5f)
                verticalLineTo(9f)
                moveTo(16f, 6.5f)
                verticalLineTo(9f)
            }
        }
    }

    val Usb: ImageVector by lazy {
        icon("Usb") {
            stroke {
                roundRect(8.5f, 2.75f, 15.5f, 8f, 1f)
                roundRect(6.5f, 8f, 17.5f, 21f, 2f)
            }
            fill {
                rect(10.25f, 4.75f, 11.25f, 6f)
                rect(12.75f, 4.75f, 13.75f, 6f)
            }
        }
    }

    /** An open basket with circling arrows in it (unlike [Delete], which has a lid). */
    val RecycleBin: ImageVector by lazy {
        icon("RecycleBin") {
            stroke {
                moveTo(4.5f, 6.5f)
                arcTo(7.5f, 2f, 0f, false, true, 19.5f, 6.5f)
                arcTo(7.5f, 2f, 0f, false, true, 4.5f, 6.5f)
                close()
                moveTo(4.5f, 6.5f)
                lineTo(6.6f, 19f)
                quadTo(6.85f, 21f, 8.8f, 21f)
                horizontalLineTo(15.2f)
                quadTo(17.15f, 21f, 17.4f, 19f)
                lineTo(19.5f, 6.5f)
                moveTo(9.28f, 13.23f)
                arcTo(3f, 3f, 0f, false, true, 14.3f, 12.57f)
                moveTo(14.44f, 10.98f)
                lineTo(14.3f, 12.57f)
                lineTo(12.71f, 12.43f)
                moveTo(14.72f, 15.77f)
                arcTo(3f, 3f, 0f, false, true, 9.7f, 16.43f)
                moveTo(9.56f, 18.02f)
                lineTo(9.7f, 16.43f)
                lineTo(11.29f, 16.57f)
            }
        }
    }

    val Home: ImageVector by lazy {
        icon("Home") {
            stroke {
                moveTo(5f, 9.3f)
                verticalLineTo(19f)
                arcTo(2f, 2f, 0f, false, false, 7f, 21f)
                horizontalLineTo(9.5f)
                verticalLineTo(15.5f)
                arcTo(1f, 1f, 0f, false, true, 10.5f, 14.5f)
                horizontalLineTo(13.5f)
                arcTo(1f, 1f, 0f, false, true, 14.5f, 15.5f)
                verticalLineTo(21f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, false, 19f, 19f)
                verticalLineTo(9.3f)
                moveTo(3f, 10.8f)
                lineTo(11.1f, 4f)
                arcTo(1.4f, 1.4f, 0f, false, true, 12.9f, 4f)
                lineTo(21f, 10.8f)
            }
        }
    }

    val Star: ImageVector by lazy {
        icon("Star") {
            stroke {
                moveTo(12f, 3f)
                lineTo(14.44f, 8.84f)
                lineTo(20.75f, 9.36f)
                lineTo(15.95f, 13.48f)
                lineTo(17.41f, 19.64f)
                lineTo(12f, 16.35f)
                lineTo(6.59f, 19.64f)
                lineTo(8.05f, 13.48f)
                lineTo(3.25f, 9.36f)
                lineTo(9.56f, 8.84f)
                close()
            }
        }
    }

    val Back: ImageVector by lazy {
        icon("Back") {
            stroke {
                moveTo(19.5f, 12f)
                horizontalLineTo(4.5f)
                moveTo(10.5f, 5.75f)
                lineTo(4.25f, 12f)
                lineTo(10.5f, 18.25f)
            }
        }
    }

    val Forward: ImageVector by lazy {
        icon("Forward") {
            stroke {
                moveTo(4.5f, 12f)
                horizontalLineTo(19.5f)
                moveTo(13.5f, 5.75f)
                lineTo(19.75f, 12f)
                lineTo(13.5f, 18.25f)
            }
        }
    }

    val Up: ImageVector by lazy {
        icon("Up") {
            stroke {
                moveTo(12f, 19.5f)
                verticalLineTo(4.5f)
                moveTo(5.75f, 10.5f)
                lineTo(12f, 4.25f)
                lineTo(18.25f, 10.5f)
            }
        }
    }

    val Menu: ImageVector by lazy {
        icon("Menu") {
            stroke {
                moveTo(3.5f, 6.5f)
                horizontalLineTo(20.5f)
                moveTo(3.5f, 12f)
                horizontalLineTo(20.5f)
                moveTo(3.5f, 17.5f)
                horizontalLineTo(20.5f)
            }
        }
    }

    val More: ImageVector by lazy {
        icon("More") {
            fill {
                circle(5.5f, 12f, 1.5f)
                circle(12f, 12f, 1.5f)
                circle(18.5f, 12f, 1.5f)
            }
        }
    }

    val Add: ImageVector by lazy {
        icon("Add") {
            stroke {
                moveTo(12f, 4.5f)
                verticalLineTo(19.5f)
                moveTo(4.5f, 12f)
                horizontalLineTo(19.5f)
            }
        }
    }

    val Cut: ImageVector by lazy {
        icon("Cut") {
            stroke {
                circle(6.75f, 17.25f, 2.75f)
                circle(17.25f, 17.25f, 2.75f)
                moveTo(8.6f, 15.2f)
                lineTo(16.5f, 3.75f)
                moveTo(15.4f, 15.2f)
                lineTo(7.5f, 3.75f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon("Copy") {
            stroke {
                roundRect(8.5f, 7.5f, 19f, 21f, 2f)
                moveTo(5f, 16.5f)
                verticalLineTo(5f)
                arcTo(2f, 2f, 0f, false, true, 7f, 3f)
                horizontalLineTo(15f)
            }
        }
    }

    val Paste: ImageVector by lazy {
        icon("Paste") {
            stroke {
                moveTo(9f, 4.5f)
                horizontalLineTo(7f)
                arcTo(2f, 2f, 0f, false, false, 5f, 6.5f)
                verticalLineTo(19f)
                arcTo(2f, 2f, 0f, false, false, 7f, 21f)
                horizontalLineTo(17f)
                arcTo(2f, 2f, 0f, false, false, 19f, 19f)
                verticalLineTo(6.5f)
                arcTo(2f, 2f, 0f, false, false, 17f, 4.5f)
                horizontalLineTo(15f)
                roundRect(9f, 3f, 15f, 6f, 1f)
            }
        }
    }

    /** A text field with the text cursor at its end. */
    val Rename: ImageVector by lazy {
        icon("Rename") {
            stroke {
                moveTo(15f, 7f)
                horizontalLineTo(5f)
                arcTo(2f, 2f, 0f, false, false, 3f, 9f)
                verticalLineTo(15f)
                arcTo(2f, 2f, 0f, false, false, 5f, 17f)
                horizontalLineTo(15f)
                moveTo(18.5f, 4f)
                verticalLineTo(20f)
                moveTo(16.5f, 4f)
                horizontalLineTo(20.5f)
                moveTo(16.5f, 20f)
                horizontalLineTo(20.5f)
            }
        }
    }

    /** An arrow leaving a box. */
    val Share: ImageVector by lazy {
        icon("Share") {
            stroke {
                moveTo(10f, 5f)
                horizontalLineTo(7f)
                arcTo(3f, 3f, 0f, false, false, 4f, 8f)
                verticalLineTo(17f)
                arcTo(3f, 3f, 0f, false, false, 7f, 20f)
                horizontalLineTo(16f)
                arcTo(3f, 3f, 0f, false, false, 19f, 17f)
                verticalLineTo(14f)
                moveTo(14.5f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(9.5f)
                moveTo(20f, 4f)
                lineTo(11f, 13f)
            }
        }
    }

    /** A bin with a lid. */
    val Delete: ImageVector by lazy {
        icon("Delete") {
            stroke {
                moveTo(4f, 6.5f)
                horizontalLineTo(20f)
                moveTo(9.5f, 6.5f)
                verticalLineTo(5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 11f, 3.5f)
                horizontalLineTo(13f)
                arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 5f)
                verticalLineTo(6.5f)
                moveTo(5.75f, 6.5f)
                lineTo(6.8f, 19.2f)
                quadTo(6.95f, 21f, 8.8f, 21f)
                horizontalLineTo(15.2f)
                quadTo(17.05f, 21f, 17.2f, 19.2f)
                lineTo(18.25f, 6.5f)
                moveTo(10f, 10.5f)
                verticalLineTo(17f)
                moveTo(14f, 10.5f)
                verticalLineTo(17f)
            }
        }
    }

    val Sort: ImageVector by lazy {
        icon("Sort") {
            stroke {
                moveTo(7.5f, 19.5f)
                verticalLineTo(4.5f)
                moveTo(4f, 8f)
                lineTo(7.5f, 4.5f)
                lineTo(11f, 8f)
                moveTo(16.5f, 4.5f)
                verticalLineTo(19.5f)
                moveTo(13f, 16f)
                lineTo(16.5f, 19.5f)
                lineTo(20f, 16f)
            }
        }
    }

    val View: ImageVector by lazy {
        icon("View") {
            stroke {
                roundRect(4f, 4f, 10.5f, 10.5f, 1.5f)
                roundRect(13.5f, 4f, 20f, 10.5f, 1.5f)
                roundRect(4f, 13.5f, 10.5f, 20f, 1.5f)
                roundRect(13.5f, 13.5f, 20f, 20f, 1.5f)
            }
        }
    }

    /** A clockwise arrow. */
    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            stroke {
                moveTo(19.34f, 13.56f)
                arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f)
                lineTo(18.7f, 8.1f)
                moveTo(18.7f, 3.6f)
                verticalLineTo(8.1f)
                horizontalLineTo(14.2f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            stroke {
                circle(10f, 10f, 6f)
                moveTo(14.5f, 14.5f)
                lineTo(20f, 20f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroke {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            stroke {
                moveTo(4.5f, 12.5f)
                lineTo(9.5f, 17.5f)
                lineTo(19.5f, 6.5f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            stroke {
                moveTo(9f, 5.5f)
                lineTo(15.5f, 12f)
                lineTo(9f, 18.5f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            stroke {
                moveTo(5.5f, 9f)
                lineTo(12f, 15.5f)
                lineTo(18.5f, 9f)
            }
        }
    }

    val Info: ImageVector by lazy {
        icon("Info") {
            stroke {
                circle(12f, 12f, 8.5f)
                moveTo(12f, 11f)
                verticalLineTo(16.5f)
            }
            fill { circle(12f, 8f, 1.1f) }
        }
    }

    val Warning: ImageVector by lazy {
        icon("Warning") {
            stroke {
                moveTo(10.3f, 4.4f)
                arcTo(2f, 2f, 0f, false, true, 13.7f, 4.4f)
                lineTo(20.8f, 16.9f)
                arcTo(2f, 2f, 0f, false, true, 19.1f, 19.9f)
                horizontalLineTo(4.9f)
                arcTo(2f, 2f, 0f, false, true, 3.2f, 16.9f)
                close()
                moveTo(12f, 9.5f)
                verticalLineTo(13.5f)
            }
            fill { circle(12f, 16.5f, 1.1f) }
        }
    }

    val Play: ImageVector by lazy {
        icon("Play") {
            solid {
                moveTo(7.5f, 4.5f)
                lineTo(19f, 12f)
                lineTo(7.5f, 19.5f)
                close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            fill {
                roundRect(6f, 4.5f, 10f, 19.5f, 1.5f)
                roundRect(14f, 4.5f, 18f, 19.5f, 1.5f)
            }
        }
    }

    /** An arrow turning back (restore from the Recycle Bin). */
    val Restore: ImageVector by lazy {
        icon("Restore") {
            stroke {
                moveTo(4.5f, 9.5f)
                horizontalLineTo(14.5f)
                arcTo(5.5f, 5.5f, 0f, false, true, 14.5f, 20.5f)
                horizontalLineTo(10.5f)
                moveTo(8.5f, 5.5f)
                lineTo(4.5f, 9.5f)
                lineTo(8.5f, 13.5f)
            }
        }
    }
}

/**
 * The icon of a folder item: a folder for [EntryKind.DIR]; for a file, by MIME type, then by
 * extension (case-insensitive), then any `text/` type as text; [FluentIcons.File] otherwise,
 * also for links and special files, whose targets are not read.
 */
fun iconFor(entry: FileEntry): ImageVector = when (entry.kind) {
    EntryKind.DIR -> FluentIcons.Folder
    EntryKind.FILE -> {
        val mime = entry.mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        iconForMime(mime)
            ?: iconForExtension(extensionOf(entry.name))
            ?: if (mime?.startsWith("text/") == true) FluentIcons.Text else FluentIcons.File
    }
    EntryKind.SYMLINK, EntryKind.OTHER -> FluentIcons.File
}

private fun iconForMime(mime: String?): ImageVector? = when {
    mime == null -> null
    mime.startsWith("image/") -> FluentIcons.Image
    mime.startsWith("video/") -> FluentIcons.Video
    mime.startsWith("audio/") -> FluentIcons.Audio
    mime == "application/pdf" -> FluentIcons.Pdf
    mime == "application/vnd.android.package-archive" -> FluentIcons.Apk
    mime in ARCHIVE_MIMES -> FluentIcons.Zip
    mime in CODE_MIMES -> FluentIcons.Code
    else -> null
}

private fun iconForExtension(extension: String): ImageVector? = when (extension) {
    "" -> null
    in IMAGE_EXTENSIONS -> FluentIcons.Image
    in VIDEO_EXTENSIONS -> FluentIcons.Video
    in AUDIO_EXTENSIONS -> FluentIcons.Audio
    "pdf" -> FluentIcons.Pdf
    in ARCHIVE_EXTENSIONS -> FluentIcons.Zip
    in APK_EXTENSIONS -> FluentIcons.Apk
    in CODE_EXTENSIONS -> FluentIcons.Code
    in TEXT_EXTENSIONS -> FluentIcons.Text
    else -> null
}

private val ARCHIVE_MIMES = setOf(
    "application/zip", "application/x-zip-compressed", "application/x-7z-compressed",
    "application/x-rar-compressed", "application/vnd.rar", "application/x-tar", "application/gzip",
    "application/x-gzip", "application/x-bzip2", "application/x-xz", "application/zstd",
)
private val CODE_MIMES = setOf(
    "application/json", "application/xml", "text/xml", "text/html", "text/css",
    "application/javascript", "text/javascript", "application/x-sh",
)
private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "ico", "dng",
)
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "3gp", "3g2", "mov", "avi", "wmv", "flv", "ts", "mpg", "mpeg",
)
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mid", "midi", "amr", "aiff",
)
private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst")
private val APK_EXTENSIONS = setOf("apk", "apks", "xapk")
private val CODE_EXTENSIONS = setOf(
    "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "py", "js", "mjs", "ts", "tsx", "jsx",
    "json", "xml", "html", "htm", "css", "scss", "sh", "bat", "ps1", "go", "rs", "swift", "rb", "php",
    "sql", "yaml", "yml", "toml", "gradle",
)
private val TEXT_EXTENSIONS = setOf("txt", "log", "md", "csv", "tsv", "ini", "conf", "cfg", "srt", "nfo")

private val Ink = SolidColor(Color.Black)

private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "Fluent.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

/** An outline path: [width] unit strokes with round caps and joins. */
private inline fun ImageVector.Builder.stroke(width: Float = 1.5f, block: PathBuilder.() -> Unit) {
    path(
        stroke = Ink,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

private inline fun ImageVector.Builder.fill(block: PathBuilder.() -> Unit) {
    path(fill = Ink, pathBuilder = block)
}

/** A filled path with a round-joined outline, which rounds its corners. */
private inline fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
    path(
        fill = Ink,
        stroke = Ink,
        strokeLineWidth = 1.5f,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}

/** A sheet of paper with its top right corner folded, the base of the document icons. */
private fun PathBuilder.page() {
    moveTo(13f, 3f)
    horizontalLineTo(7f)
    arcTo(2f, 2f, 0f, false, false, 5f, 5f)
    verticalLineTo(19f)
    arcTo(2f, 2f, 0f, false, false, 7f, 21f)
    horizontalLineTo(17f)
    arcTo(2f, 2f, 0f, false, false, 19f, 19f)
    verticalLineTo(9f)
    close()
    moveTo(13f, 3f)
    verticalLineTo(7f)
    arcTo(2f, 2f, 0f, false, false, 15f, 9f)
    horizontalLineTo(19f)
}

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    moveTo(left + radius, top)
    horizontalLineTo(right - radius)
    arcTo(radius, radius, 0f, false, true, right, top + radius)
    verticalLineTo(bottom - radius)
    arcTo(radius, radius, 0f, false, true, right - radius, bottom)
    horizontalLineTo(left + radius)
    arcTo(radius, radius, 0f, false, true, left, bottom - radius)
    verticalLineTo(top + radius)
    arcTo(radius, radius, 0f, false, true, left + radius, top)
    close()
}

private fun PathBuilder.rect(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top)
    horizontalLineTo(right)
    verticalLineTo(bottom)
    horizontalLineTo(left)
    close()
}

private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX - radius, centerY)
    arcTo(radius, radius, 0f, false, true, centerX + radius, centerY)
    arcTo(radius, radius, 0f, false, true, centerX - radius, centerY)
    close()
}
