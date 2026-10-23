// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MimeResolverTest {

    private val mime = MimeResolver()

    @Test
    fun platformTypesByLastExtensionIgnoringCase() {
        assertEquals("image/jpeg", mime.mimeOf("a.JPG"))
        assertEquals("application/pdf", mime.mimeOf("report.final.pdf"))
    }

    @Test
    fun supplementaryTable() {
        assertEquals("application/x-subrip", mime.mimeOf("x.srt"))
        assertEquals("application/vnd.android.package-archive", mime.mimeOf("y.apk"))
        assertEquals("application/x-sami", mime.mimeOf("movie.smi"))
        assertEquals("text/vtt", mime.mimeOf("movie.vtt"))
        assertEquals("text/markdown", mime.mimeOf("README.md"))
        assertEquals("text/x-kotlin", mime.mimeOf("Main.kt"))
        assertEquals("image/heic", mime.mimeOf("photo.HEIC"))
        assertEquals("image/avif", mime.mimeOf("photo.avif"))
    }

    @Test
    fun unknownOrMissingExtensionIsNull() {
        assertNull(mime.mimeOf("z.unknownext"))
        assertNull(mime.mimeOf("README"))
        assertNull(mime.mimeOf(".srt"))
        assertNull(mime.mimeOf("trailing."))
    }
}
