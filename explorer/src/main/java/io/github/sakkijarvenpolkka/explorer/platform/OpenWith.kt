/*
 * Files — handing files to other apps: viewing, "Open with", sharing and installing APKs,
 * through the app's FileProvider.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import java.nio.file.Path

/** The MIME type of Android packages. */
const val APK_MIME = "application/vnd.android.package-archive"

/** The type that any app taking files at all accepts. */
private const val ANY_TYPE = "*/*"

/**
 * Intents that hand files to other apps as content URIs of the app's FileProvider (authority
 * `<package>.files`), which serves only `/storage` and `/mnt/media_rw`, never the app's own
 * data, and only for reading. Nothing is resolved before it is started: package visibility
 * hides most apps from this one, so [launch] reports when no app takes an intent.
 */
class OpenWith(private val context: Context, private val mime: MimeResolver) {

    private val authority = "${context.packageName}.files"

    /**
     * ACTION_VIEW of [file] with its MIME type (any type when it has none), for a tap: the
     * system lets the user choose "Always".
     */
    fun viewIntent(file: Path): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(file), typeOf(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** The system chooser titled [title] over [viewIntent], for "Open with", which asks every time. */
    fun chooserIntent(file: Path, title: String): Intent = Intent.createChooser(viewIntent(file), title)

    /**
     * ACTION_SEND of one file or ACTION_SEND_MULTIPLE of several, typed with the MIME type that
     * covers them all ([commonType]); the URIs are also the clip data, which carries the read grant.
     */
    fun shareIntent(files: List<Path>): Intent {
        require(files.isNotEmpty()) { "Nothing to share" }
        val uris = files.map(::uriFor)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri(null, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.type = commonType(files.map(::typeOf))
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The content URI of [file]; a file the provider does not serve fails with [FsError.Unsupported]. */
    fun uriFor(file: Path): Uri = try {
        FileProvider.getUriForFile(context, authority, file.toFile())
    } catch (e: IllegalArgumentException) {
        throw FsException(FsError.Unsupported, e)
    }

    /** Whether the user has yet to allow this app to install apps ("Install unknown apps"). */
    fun needsInstallPermission(): Boolean = !context.packageManager.canRequestPackageInstalls()

    /** The settings screen where the user allows this app to install apps. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private fun typeOf(file: Path): String = mime.mimeOf(file.fileName?.toString().orEmpty()) ?: ANY_TYPE
}

/**
 * The MIME type that covers all of [types]: their type when they all have the same; the
 * top-level type they share with a wildcard subtype (JPEG and PNG images: `image/` and an
 * asterisk); else any type.
 */
private fun commonType(types: List<String>): String {
    val distinct = types.distinct()
    distinct.singleOrNull()?.let { return it }
    if (ANY_TYPE in distinct) return ANY_TYPE
    val top = distinct.map { it.substringBefore('/') }.distinct().singleOrNull() ?: return ANY_TYPE
    return "$top/*"
}

/** How handing a file to another app ended. */
sealed interface OpenResult {
    data object Started : OpenResult

    /** No app takes the intent. */
    data object NoApp : OpenResult

    /** The file is not one the FileProvider serves. */
    data object Unsupported : OpenResult

    /** An APK, which needs the user to allow this app to install apps first. */
    data object NeedsInstallPermission : OpenResult

    /** The file is gone. */
    data object NotFound : OpenResult
}

/**
 * Starts [intent] from [context]: [OpenResult.NoApp] when no app takes it, or the one that would
 * refuses this app (an activity it does not export, a permission this app lacks).
 */
fun launch(context: Context, intent: Intent): OpenResult = try {
    context.startActivity(intent)
    OpenResult.Started
} catch (e: ActivityNotFoundException) {
    OpenResult.NoApp
} catch (e: SecurityException) {
    OpenResult.NoApp
}
