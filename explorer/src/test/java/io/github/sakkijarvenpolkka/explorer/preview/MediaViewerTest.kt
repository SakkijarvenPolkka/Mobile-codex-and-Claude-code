// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.preview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.percentOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.sakkijarvenpolkka.explorer.ui.fluent.FluentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko")
class MediaViewerTest {

    @get:Rule
    val rule = createComposeRule()

    /** Plays nothing: records what it is told, and is made ready or failed by the test. */
    private class FakePlayer : MediaPort {
        val calls = mutableListOf<String>()
        var prepared: Pair<Path, Boolean>? = null
        var released = false
        override var positionMs = 0
        override var isPlaying = false
        private var onReady: ((Int) -> Unit)? = null
        private var onError: (() -> Unit)? = null

        override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) {
            prepared = file to video
            this.onReady = onReady
            this.onError = onError
        }

        override fun play() {
            calls += "play"
            isPlaying = true
        }

        override fun pause() {
            calls += "pause"
            isPlaying = false
        }

        override fun seekTo(ms: Int) {
            calls += "seek $ms"
            positionMs = ms
        }

        override fun release() {
            released = true
        }

        fun ready(durationMs: Int) = checkNotNull(onReady)(durationMs)

        fun fail() = checkNotNull(onError)()

        fun seeks(): List<Int> = calls.filter { it.startsWith("seek ") }.map { it.removePrefix("seek ").toInt() }
    }

    /** A lifecycle the test moves, resumed to begin with. */
    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val file = Paths.get("/storage/emulated/0/Music/song.mp3")

    private fun show(
        player: MediaPort,
        video: Boolean = false,
        lifecycle: LifecycleOwner = TestLifecycle(),
        onOpenWith: (() -> Unit)? = null,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                FluentTheme(dark = false, accent = null) {
                    MediaViewer(file, video, player, onClose = {}, onOpenWith = onOpenWith)
                }
            }
        }
    }

    @Test
    fun playPauseToggles() {
        val player = FakePlayer()
        show(player)
        rule.runOnIdle { assertEquals(file to false, player.prepared) }
        // Nothing to play until the file is ready.
        rule.onNodeWithContentDescription("재생").assertIsNotEnabled()

        rule.runOnIdle { player.ready(90_000) }
        rule.onNodeWithText("0:00").assertIsDisplayed()
        rule.onNodeWithText("1:30").assertIsDisplayed()

        rule.onNodeWithContentDescription("재생").performClick()
        rule.runOnIdle { assertTrue(player.isPlaying) }
        rule.onNodeWithContentDescription("일시정지").performClick()
        rule.runOnIdle {
            assertFalse(player.isPlaying)
            assertEquals(listOf("play", "pause"), player.calls)
        }
        rule.onNodeWithContentDescription("재생").performClick()

        // The player pauses by itself (the headphones were unplugged): the button follows it.
        rule.runOnIdle {
            player.positionMs = 61_000
            player.isPlaying = false
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithContentDescription("재생").assertIsDisplayed()
        rule.onNodeWithText("1:01").assertIsDisplayed()
    }

    @Test
    fun seekBarSeeks() {
        val player = FakePlayer()
        show(player)
        rule.runOnIdle { player.ready(100_000) }

        rule.onNodeWithTag(MediaViewerTags.SEEK_BAR).performTouchInput { click(percentOffset(0.25f, 0.5f)) }
        rule.runOnIdle {
            val seek = player.seeks().single()
            assertTrue("seek $seek", abs(seek - 25_000) <= 2_000)
        }

        // Accessibility services set the position too.
        rule.onNodeWithTag(MediaViewerTags.SEEK_BAR).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.runOnIdle { assertEquals(50_000, player.seeks().last()) }
        rule.onNodeWithText("0:50").assertIsDisplayed()
    }

    @Test
    fun stopPausesAndSeeksBackWhenPreparedAgain() {
        val player = FakePlayer()
        val lifecycle = TestLifecycle()
        show(player, video = true, lifecycle = lifecycle)
        rule.runOnIdle { player.ready(120_000) }
        rule.onNodeWithContentDescription("재생").performClick()

        rule.runOnIdle {
            player.positionMs = 42_000
            lifecycle.registry.currentState = Lifecycle.State.CREATED
        }
        rule.runOnIdle {
            assertFalse(player.isPlaying)
            assertEquals(listOf("play", "pause"), player.calls)
        }

        // Back from the background, the video is prepared anew, from its start.
        rule.runOnIdle {
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
            player.positionMs = 0
            player.ready(120_000)
        }
        rule.runOnIdle { assertEquals(listOf(42_000), player.seeks()) }
        rule.onNodeWithText("0:42").assertIsDisplayed()
        // It stays paused until asked to play.
        rule.onNodeWithContentDescription("재생").assertIsDisplayed()
        rule.runOnIdle { assertFalse(player.isPlaying) }
    }

    @Test
    fun videoControlsWaitForThePlayerAfterTheBackground() {
        val player = FakePlayer()
        val lifecycle = TestLifecycle()
        show(player, video = true, lifecycle = lifecycle)
        rule.runOnIdle { player.ready(120_000) }
        rule.onNodeWithContentDescription("재생").assertIsEnabled()

        // In the background the video's surface goes, and its player with it: nothing to play until it
        // is prepared again (pressed now, play would be put off, and the button would not show it).
        rule.runOnIdle {
            player.positionMs = 42_000
            lifecycle.registry.currentState = Lifecycle.State.CREATED
        }
        rule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        rule.onNodeWithContentDescription("재생").assertIsNotEnabled()
        rule.onNodeWithTag(MediaViewerTags.SEEK_BAR).assertIsNotEnabled()
        // The times stay as they were.
        rule.onNodeWithText("0:42").assertIsDisplayed()
        rule.onNodeWithText("2:00").assertIsDisplayed()

        rule.runOnIdle { player.ready(120_000) }
        rule.onNodeWithContentDescription("재생").assertIsEnabled()
        rule.onNodeWithContentDescription("재생").performClick()
        rule.runOnIdle { assertTrue(player.isPlaying) }
    }

    @Test
    fun audioStaysPlayableAfterTheBackground() {
        // An audio player is not made again: it stays ready.
        val player = FakePlayer()
        val lifecycle = TestLifecycle()
        show(player, video = false, lifecycle = lifecycle)
        rule.runOnIdle { player.ready(120_000) }

        rule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        rule.onNodeWithContentDescription("재생").assertIsEnabled()
    }

    @Test
    fun anotherKindOfFileStartsOver() {
        // The same player told the file is a video now prepares it anew: until then, nothing to play.
        val player = FakePlayer()
        var video by mutableStateOf(false)
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                MediaViewer(file, video, player, onClose = {})
            }
        }
        rule.runOnIdle { player.ready(60_000) }
        rule.onNodeWithContentDescription("재생").assertIsEnabled()

        video = true
        rule.runOnIdle { assertEquals(file to true, player.prepared) }
        rule.onNodeWithContentDescription("재생").assertIsNotEnabled()
        rule.runOnIdle { player.ready(60_000) }
        rule.onNodeWithContentDescription("재생").assertIsEnabled()
    }

    @Test
    fun errorShowsMediaErrorAndOpenWith() {
        val player = FakePlayer()
        var openedWith = 0
        show(player, onOpenWith = { openedWith++ })
        rule.runOnIdle { player.fail() }

        rule.onNodeWithText("재생할 수 없는 파일입니다").assertIsDisplayed()
        rule.onNodeWithContentDescription("재생").assertDoesNotExist()
        rule.onNodeWithText("연결 프로그램").performClick()
        rule.runOnIdle { assertEquals(1, openedWith) }
    }

    @Test
    fun leavingReleasesPlayer() {
        val player = FakePlayer()
        var shown by mutableStateOf(true)
        rule.setContent {
            FluentTheme(dark = false, accent = null) {
                if (shown) MediaViewer(file, video = false, player, onClose = {})
            }
        }
        rule.runOnIdle {
            player.ready(1_000)
            assertFalse(player.released)
            shown = false
        }
        rule.runOnIdle { assertTrue(player.released) }
    }
}
