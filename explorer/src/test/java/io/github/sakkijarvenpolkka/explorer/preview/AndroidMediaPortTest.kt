// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.InvalidStateBehavior
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidMediaPortTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The shadows of the players made, in order. */
    private val players = mutableListOf<ShadowMediaPlayer>()

    private var durationMs: Int? = null
    private var errors = 0

    @Before
    fun emulatePlayers() {
        // Every file is a minute long and ready at once.
        ShadowMediaPlayer.setMediaInfoProvider { MediaInfo(60_000, 0) }
        // A call the real player refuses in its state puts it in its error state and calls its error listener.
        ShadowMediaPlayer.setCreateListener { _, shadow ->
            shadow.invalidStateBehavior = InvalidStateBehavior.EMULATE
            players += shadow
        }
    }

    /** Runs what the player posted to the main thread. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** An [AndroidMediaPort] with an audio file prepared and ready. */
    private fun preparedAudio(): AndroidMediaPort {
        val port = AndroidMediaPort(context)
        port.prepare(folder.newFile("song.mp3").toPath(), video = false, onReady = { durationMs = it }, onError = { errors++ })
        idle()
        assertEquals(60_000, durationMs)
        return port
    }

    @Test
    fun pauseBeforePlayingIsNoError() {
        val port = preparedAudio()

        // As when the app goes to the background before the file was played.
        port.pause()
        idle()

        assertEquals(0, errors)
        assertEquals(ShadowMediaPlayer.State.PREPARED, players.single().state)
        port.release()
    }

    @Test
    fun pauseWhilePlayingPauses() {
        val port = preparedAudio()
        port.play()
        assertTrue(port.isPlaying)

        port.pause()
        idle()

        assertFalse(port.isPlaying)
        assertEquals(ShadowMediaPlayer.State.PAUSED, players.single().state)
        assertEquals(0, errors)
        port.release()
    }
}
