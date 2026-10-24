// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.explorer.fs.TextEncoding
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.nio.file.Paths

/** What the viewers hold open, and what closes it when its volume goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A document that counts how often it is closed. */
    private class Document : PdfPort {
        var closes = 0
        override val pageCount: Int = 1
        override suspend fun pageSize(index: Int): IntSize = IntSize(100, 100)
        override suspend fun render(index: Int, widthPx: Int): Bitmap = error("Not drawn here")
        override fun close() {
            closes++
        }
    }

    /** A player that counts how often it is released. */
    private class Player : MediaPort {
        var releases = 0
        override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Int) = Unit
        override val positionMs: Int = 0
        override val isPlaying: Boolean = false
        override fun release() {
            releases++
        }
    }

    private val documents = HashMap<Path, Document>()
    private val players = ArrayList<Player>()
    private val files = OpenFiles()

    /** Viewer parts on the fakes above, whose documents and players [files] keeps. */
    private val deps = ViewerDeps(
        text = object : TextLoader {
            override suspend fun load(file: Path, forced: TextEncoding?): TextDocument = TextDocument.Binary
        },
        images = object : ImageDecoderPort {
            override suspend fun decode(file: Path, maxWidth: Int, maxHeight: Int): ImageBitmap =
                error("Not drawn here")
            override suspend fun sizeOf(file: Path): IntSize = IntSize(1, 1)
        },
        pdf = PdfOpener { file -> documents.getOrPut(file) { Document() } },
        media = { Player().also(players::add) },
        io = Dispatchers.Unconfined,
    ).trackedIn(files)

    private fun player(file: Path): MediaPort = deps.media(context).apply { prepare(file, video = false, {}, {}) }

    @Test
    fun closeUnderClosesWhatIsOpenOnThatVolumeOnly() {
        val onCard = deps.pdf.open(CARD.resolve("a.pdf"))
        deps.pdf.open(PHONE.resolve("b.pdf"))
        // Its name starts as the card's does, but it is another volume.
        deps.pdf.open(Paths.get("/storage/1A2B-3C4D-2/c.pdf"))
        val song = player(CARD.resolve("Music/song.mp3"))
        player(PHONE.resolve("clip.mp4"))

        files.closeUnder(CARD)

        assertEquals(1, documents.getValue(CARD.resolve("a.pdf")).closes)
        assertEquals(0, documents.getValue(PHONE.resolve("b.pdf")).closes)
        assertEquals(0, documents.getValue(Paths.get("/storage/1A2B-3C4D-2/c.pdf")).closes)
        assertEquals(listOf(1, 0), players.map { it.releases })

        // Its viewer leaving later closes it no second time.
        onCard.close()
        song.release()
        files.closeUnder(CARD)
        assertEquals(1, documents.getValue(CARD.resolve("a.pdf")).closes)
        assertEquals(listOf(1, 0), players.map { it.releases })
    }

    @Test
    fun whatTheViewersClosedIsForgotten() {
        deps.pdf.open(CARD.resolve("a.pdf")).close()
        player(CARD.resolve("song.mp3")).release()

        files.closeUnder(CARD)

        assertEquals(1, documents.getValue(CARD.resolve("a.pdf")).closes)
        assertEquals(listOf(1), players.map { it.releases })
    }

    private companion object {
        val CARD: Path = Paths.get("/storage/1A2B-3C4D")
        val PHONE: Path = Paths.get("/storage/emulated/0")
    }
}
