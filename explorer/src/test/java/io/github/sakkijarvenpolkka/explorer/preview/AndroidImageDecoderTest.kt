// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** The animations of [AndroidImageDecoder], decoded by the platform's own decoder (native graphics). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImageDecoderTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** How many times a file was read into memory whole. */
    private val reads = AtomicInteger()

    /**
     * The decoder, its files read whole through a counter. The platform's decoder here refuses a file
     * source ("Only supported on Android"), so the header is read from the file's bytes instead.
     */
    private val decoder = AndroidImageDecoder(
        Dispatchers.Unconfined,
        fileSource = { ImageDecoder.createSource(ByteBuffer.wrap(Files.readAllBytes(it))) },
        readBytes = {
            reads.incrementAndGet()
            Files.readAllBytes(it)
        },
    )

    /** A file [name] holding the bytes [hex] spells out. */
    private fun gif(name: String, hex: String): Path {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Files.write(folder.root.toPath().resolve(name), bytes)
    }

    /** The files this process holds open, by where their descriptors lead. */
    private fun openFiles(): List<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
        runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrNull()
    }

    @Test
    fun animationHoldsNoFileOpen() {
        assumeTrue("Needs /proc to list the open files", File("/proc/self/fd").isDirectory)
        val file = gif("dance.gif", ANIMATED_GIF)

        val animation = runBlocking { decoder.decodeAnimation(file, 100, 100) }

        assertNotNull(animation)
        assertEquals(2, animation!!.intrinsicWidth)
        assertEquals(1, reads.get())
        // Played from memory: the volume can go while it is shown.
        assertEquals(emptyList<String>(), openFiles().filter { it.endsWith("dance.gif") })
    }

    @Test
    fun stillGifHasNoAnimation() {
        assertNull(runBlocking { decoder.decodeAnimation(gif("still.gif", STILL_GIF), 100, 100) })
    }

    @Test
    fun stillGifIsNotReadIntoMemory() {
        // Its header says it is still: decode() reads it from the file.
        assertNull(runBlocking { decoder.decodeAnimation(gif("still.gif", STILL_GIF), 100, 100) })
        assertEquals(0, reads.get())
    }

    @Test
    fun animationTooLargeToReadIsShownStill() {
        val file = folder.root.toPath().resolve("huge.gif")
        RandomAccessFile(file.toFile(), "rw").use { it.setLength(32L * 1024 * 1024 + 1) }

        assertNull(runBlocking { decoder.decodeAnimation(file, 100, 100) })
        assertEquals(0, reads.get())
    }

    private companion object {
        /** A 2 × 2 GIF of two frames, looping. */
        const val ANIMATED_GIF = "47494638396102000200810000ff000000000000000000000021ff0b4e4554534341504532" +
            "2e30030100000021f904000a0000002c00000000020002000008060001080410100021f904010a0001002c000000000200" +
            "0200810000ff0000000000000000000806000108041010003b"

        /** A 2 × 2 GIF of one frame. */
        const val STILL_GIF = "47494638376102000200810000ff00000000000000000000002c00000000020002000008060001" +
            "08041010003b"
    }
}
