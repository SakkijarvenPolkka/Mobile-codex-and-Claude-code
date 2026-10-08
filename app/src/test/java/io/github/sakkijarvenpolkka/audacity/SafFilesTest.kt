// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import io.github.sakkijarvenpolkka.audacity.files.InsufficientSpaceException
import io.github.sakkijarvenpolkka.audacity.files.SafFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** File names, capped copies and clean-up of the SAF helpers (review F4, F8, F11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun assertWellFormed(s: String) {
        for (i in s.indices) {
            if (Character.isHighSurrogate(s[i])) assertTrue("split surrogate pair in $s", i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
            if (Character.isLowSurrogate(s[i])) assertTrue("split surrogate pair in $s", i > 0 && Character.isHighSurrogate(s[i - 1]))
        }
    }

    @Test
    fun longKoreanAndEmojiNamesFitInAFileName() {
        val korean = "가".repeat(90)                    // 270 bytes
        val base = SafFiles.sanitizeBaseName(korean)
        assertTrue(bytes(base) <= SafFiles.MAX_STEM_BYTES)
        // SQLite's companions of a project fit in NAME_MAX
        assertTrue(bytes("$base.aup3-journal") <= 255)
        // A real file can be created with that name and its -wal companion
        File(tmp.root, "$base.aup3").writeText("x")
        File(tmp.root, "$base.aup3-wal").writeText("x")

        val emoji = "🎵".repeat(70)          // 4 bytes each
        val e = SafFiles.sanitizeBaseName(emoji)
        assertWellFormed(e)
        assertTrue(bytes(e) <= SafFiles.MAX_STEM_BYTES)
        assertEquals(0, bytes(e) % 4)

        assertEquals("Short name", SafFiles.sanitizeBaseName("Short name"))
        assertEquals("a_b", SafFiles.sanitizeBaseName("a/b"))
    }

    @Test
    fun fileNamesKeepTheirExtensionWhenShortened() {
        val name = SafFiles.sanitizeFileName("노래".repeat(60) + ".flac", null)
        assertTrue(name, name.endsWith(".flac"))
        assertTrue(bytes(name) <= SafFiles.MAX_STEM_BYTES)
        assertWellFormed(name)
        // A missing extension comes from the MIME type
        assertEquals("song.mp3", SafFiles.sanitizeFileName("song", "audio/mpeg"))
        assertEquals("audio.wav", SafFiles.sanitizeFileName(null, "audio/wav"))
        assertEquals(10, bytes(SafFiles.truncateUtf8("가나다라", 10).let { it + "a" }))
        assertEquals(SafFiles.utf8Length("가a🎵"), bytes("가a🎵"))
    }

    @Test
    fun anEndlessStreamStopsAtTheLimit() = runBlocking {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val out = ByteArrayOutputStream()
        try {
            SafFiles.pump(endless, out, -1, 3L shl 20) { _, _ -> }
            fail("no limit")
        } catch (_: InsufficientSpaceException) {
        }
        assertTrue(out.size() <= 3 shl 20)
        // A stream within the limit is copied whole
        val small = ByteArrayOutputStream()
        SafFiles.pump(ByteArray(1000).inputStream(), small, 1000, 1000) { _, _ -> }
        assertEquals(1000, small.size())
    }

    @Test
    fun stagingIsDeletedByACancelledCoroutine() = runBlocking {
        val dir = File(tmp.root, "export/uuid").apply { mkdirs() }
        File(dir, "song.wav").writeBytes(ByteArray(1 shl 16))
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                SafFiles.deleteStaging(dir)
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertFalse(dir.exists())
    }
}
