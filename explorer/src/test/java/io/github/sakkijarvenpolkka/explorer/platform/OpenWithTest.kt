// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.FsError
import io.github.sakkijarvenpolkka.explorer.fs.FsException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.nio.file.Paths

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenWithTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val openWith = OpenWith(context, MimeResolver())

    private fun sd(name: String): Path = Paths.get("/storage/1A2B-3C4D/$name")

    @Test
    fun uriForStorageAndUsbPathsWorks() {
        val sd = openWith.uriFor(sd("x.txt"))
        assertEquals("content", sd.scheme)
        assertEquals("io.github.sakkijarvenpolkka.explorer.files", sd.authority)
        assertEquals("/storage/1A2B-3C4D/x.txt", sd.path)

        val usb = openWith.uriFor(Paths.get("/mnt/media_rw/1A2B-3C4D/y"))
        assertEquals("content", usb.scheme)
        assertEquals("io.github.sakkijarvenpolkka.explorer.files", usb.authority)
        assertEquals("/usb/1A2B-3C4D/y", usb.path)
    }

    @Test
    fun uriForAppPrivateFileFails() {
        val private = context.filesDir.toPath().resolve("x")
        val e = assertThrows(FsException::class.java) { openWith.uriFor(private) }
        assertEquals(FsError.Unsupported, e.error)
    }

    @Test
    fun viewIntentGrantsReadOnly() {
        val file = sd("report.pdf")
        val intent = openWith.viewIntent(file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(openWith.uriFor(file), intent.data)
        assertEquals("application/pdf", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun unknownTypeIsOfferedToEveryApp() {
        assertEquals("*/*", openWith.viewIntent(sd("data.unknownext")).type)
        assertEquals("*/*", openWith.viewIntent(sd("README")).type)
    }

    @Test
    fun chooserWrapsTheViewIntent() {
        val file = sd("song.mp3")
        val chooser = openWith.chooserIntent(file, "Open with")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Open with", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(openWith.uriFor(file), target.data)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun shareIntentPicksCommonMime() {
        val jpgs = openWith.shareIntent(listOf(sd("a.jpg"), sd("b.jpg")))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, jpgs.action)
        assertEquals("image/jpeg", jpgs.type)
        val streams = jpgs.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals(listOf(openWith.uriFor(sd("a.jpg")), openWith.uriFor(sd("b.jpg"))), streams)
        assertEquals(2, jpgs.clipData?.itemCount)
        assertTrue(jpgs.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, jpgs.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertEquals("image/*", openWith.shareIntent(listOf(sd("a.jpg"), sd("b.png"))).type)
        assertEquals("*/*", openWith.shareIntent(listOf(sd("a.jpg"), sd("c.pdf"))).type)
        assertEquals("*/*", openWith.shareIntent(listOf(sd("a.jpg"), sd("d.unknownext"))).type)

        val one = openWith.shareIntent(listOf(sd("c.pdf")))
        assertEquals(Intent.ACTION_SEND, one.action)
        assertEquals("application/pdf", one.type)
        assertEquals(openWith.uriFor(sd("c.pdf")), one.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(one.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun launchWithoutHandlerReturnsNoApp() {
        // Started from the activity, so that the app it opens comes back to this one.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(context).checkActivities(true)
        val intent = openWith.viewIntent(sd("x.unknownext"))
        assertEquals(OpenResult.NoApp, launch(activity, intent))
    }

    @Test
    fun apkNeedsInstallPermissionWhenNotAllowed() {
        val packages = shadowOf(context.packageManager)
        packages.setCanRequestPackageInstalls(false)
        assertTrue(openWith.needsInstallPermission())
        val settings = openWith.installPermissionIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals(Uri.parse("package:io.github.sakkijarvenpolkka.explorer"), settings.data)

        packages.setCanRequestPackageInstalls(true)
        assertFalse(openWith.needsInstallPermission())
    }
}
