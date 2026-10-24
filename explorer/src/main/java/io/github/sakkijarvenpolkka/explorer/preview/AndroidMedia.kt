/*
 * Files — the audio and video player of the viewer on the platform's MediaPlayer and VideoView.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.preview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Path

/**
 * [MediaPort] on the platform's players: a video plays in a [VideoView] given the file's Uri (and
 * drawn by [VideoSurface]), an audio file in a [MediaPlayer] given its file descriptor. Playing asks
 * for the audio focus ([AudioFocusRequest]) and pauses when it is lost or the headphones are unplugged
 * ([AudioManager.ACTION_AUDIO_BECOMING_NOISY]). [context] is the activity's, which the video view is
 * made with.
 */
class AndroidMediaPort(private val context: Context) : MediaPort {

    private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private var video = false
    private var videoView: VideoView? = null
    private var player: MediaPlayer? = null

    /** Whether [player] is prepared (a [VideoView] keeps track of its own player). */
    private var playerReady = false

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    private var listening = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    /** The video view, made the first time it is wanted; it asks for no audio focus of its own. */
    private fun videoView(): VideoView = videoView ?: VideoView(context).also {
        it.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        it.setAudioAttributes(attributes)
        videoView = it
    }

    override fun prepare(file: Path, video: Boolean, onReady: (durationMs: Int) -> Unit, onError: () -> Unit) {
        this.video = video
        if (video) {
            val view = videoView()
            view.setOnPreparedListener { onReady(it.duration) }
            view.setOnErrorListener { _, _, _ ->
                stopListening()
                onError()
                true
            }
            view.setOnCompletionListener { stopListening() }
            view.setVideoURI(Uri.fromFile(file.toFile()))
            return
        }
        val player = MediaPlayer()
        this.player = player
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            playerReady = true
            onReady(it.duration)
        }
        player.setOnErrorListener { _, _, _ ->
            playerReady = false
            stopListening()
            onError()
            true
        }
        player.setOnCompletionListener { stopListening() }
        try {
            FileInputStream(file.toFile()).use { player.setDataSource(it.fd) }
            player.prepareAsync()
        } catch (e: IOException) {
            onError()
        } catch (e: IllegalArgumentException) {
            onError()
        } catch (e: SecurityException) {
            onError()
        }
    }

    override fun play() {
        if (!video && !playerReady) return
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        if (!listening) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(context, noisy, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }
        if (video) videoView().start() else player?.start()
    }

    /**
     * Pauses the file if it plays: [MediaPlayer.pause] on a player prepared but never started puts
     * it in its error state ([VideoView.pause] checks the same).
     */
    override fun pause() {
        if (video) videoView?.pause() else if (isPlaying) player?.pause()
        stopListening()
    }

    override fun seekTo(ms: Int) {
        if (video) videoView?.seekTo(ms) else if (playerReady) player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    override val positionMs: Int
        get() = when {
            video -> videoView?.currentPosition ?: 0
            playerReady -> player?.currentPosition ?: 0
            else -> 0
        }

    override val isPlaying: Boolean
        get() = if (video) videoView?.isPlaying == true else playerReady && player?.isPlaying == true

    override fun release() {
        stopListening()
        videoView?.stopPlayback()
        player?.release()
        player = null
        playerReady = false
    }

    @Composable
    override fun VideoSurface(modifier: Modifier) {
        AndroidView(factory = { videoView() }, modifier)
    }

    /** Lets go of the audio focus and stops listening for the headphones. */
    private fun stopListening() {
        if (listening) {
            context.unregisterReceiver(noisy)
            listening = false
        }
        audioManager.abandonAudioFocusRequest(focus)
    }
}
