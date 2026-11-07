// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.pick

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.platform.MimeResolver
import io.github.sakkijarvenpolkka.explorer.platform.OpenWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.nio.file.Paths

/** What another app gets back for the files picked for it (pick spec §2.3): content URIs it may only read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickResultTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val mime = MimeResolver()
    private val openWith = OpenWith(context, mime)

    private fun picked(name: String): Path = Paths.get("/storage/emulated/0/DCIM/$name")

    /** The result for the files [names] of the folder above, through the app's FileProvider. */
    private fun result(vararg names: String): Intent =
        pickResultIntent(names.map(::picked), openWith::uriFor) { mime.mimeOf(it.fileName.toString()) }

    @Test
    fun singleResultHasDataTypeAndReadGrant() {
        val intent = result("a.jpg")

        val uri = checkNotNull(intent.data)
        assertEquals("content", uri.scheme)
        assertEquals("io.github.sakkijarvenpolkka.explorer.files", uri.authority)
        assertEquals(openWith.uriFor(picked("a.jpg")), uri)
        assertEquals("image/jpeg", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // One file is the data alone.
        assertNull(intent.clipData)
    }

    @Test
    fun multipleResultUsesClipData() {
        val names = listOf("a.jpg", "b.png", "c.jpg")
        val intent = result(*names.toTypedArray())

        val uris = names.map { openWith.uriFor(picked(it)) }
        val clip = checkNotNull(intent.clipData)
        assertEquals(3, clip.itemCount)
        assertEquals(uris, (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertEquals(uris.first(), intent.data)
        // The type that covers them all, as sharing has it.
        assertEquals("image/*", intent.type)
        assertEquals("image/jpeg", result("a.jpg", "c.jpg").type)
        assertEquals("*/*", result("a.jpg", "report.pdf").type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun unknownTypeIsOctetStream() {
        assertEquals("application/octet-stream", result("data.unknownext").type)
        assertEquals("application/octet-stream", result("README").type)
        // Several of no known type are bytes too; with a picture, of any type.
        assertEquals("application/octet-stream", result("data.unknownext", "README").type)
        assertEquals("*/*", result("data.unknownext", "a.jpg").type)
    }
}
