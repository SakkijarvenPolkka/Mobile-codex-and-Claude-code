/*
 * Audacity Android port — device-list injection (API.md §3.3
 * audio.setDevices): the native engine cannot enumerate Android audio
 * devices, so the app lists AudioManager.getDevices(GET_DEVICES_ALL) and
 * sends them when the engine is ready and whenever an AudioDeviceCallback
 * reports a change (USB/Bluetooth hot-plug). The engine applies a list at
 * once when no stream is open, else when the stream stops; Transport ▸
 * Rescan Audio Devices reads the result with audio.devices. It also tells
 * the engine once whether the UNPROCESSED microphone source is supported
 * (audio.setInputOptions; the engine then records with it instead of
 * VOICE_RECOGNITION / CAMCORDER).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.AudioDeviceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AudioDeviceMonitor(
    context: Context,
    private val engine: AudacityEngine,
    private val scope: CoroutineScope,
    /** AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED; null = ask the AudioManager (tests replace it). */
    private val unprocessedSupported: Boolean? = null,
    /** The current devices; default AudioManager.getDevices(GET_DEVICES_ALL) (tests replace it). */
    private val lister: (() -> List<AudioDeviceSpec>)? = null,
) {
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val sendMutex = Mutex()
    private var pending: Job? = null
    private var lastSent: List<AudioDeviceSpec>? = null
    private var registered = false
    private var inputOptionsSent = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleSend()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleSend()
    }

    /** Sends the current list and follows changes (main thread; idempotent). */
    fun start() {
        val am = audioManager ?: return
        if (registered) return
        registered = true
        // The callback reports every current device right after registering
        am.registerAudioDeviceCallback(callback, handler)
        scheduleSend()
    }

    fun stop() {
        if (!registered) return
        registered = false
        audioManager?.unregisterAudioDeviceCallback(callback)
        pending?.cancel()
    }

    /** Coalesces bursts of callbacks (a headset reports several routes at once). */
    private fun scheduleSend() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            send()
        }
    }

    private fun currentDevices(): List<AudioDeviceSpec>? {
        lister?.let { return it() }
        val am = audioManager ?: return null
        return try {
            am.getDevices(AudioManager.GET_DEVICES_ALL).mapNotNull { specOf(it) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot list the audio devices: $e")
            null
        }
    }

    private suspend fun send() = sendMutex.withLock {
        sendInputOptions()
        val devices = currentDevices() ?: return@withLock
        if (devices == lastSent) return@withLock
        try {
            engine.setAudioDevices(devices)
            lastSent = devices
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not ready yet or a fake engine without devices: the next change retries
            lastSent = null
        }
    }

    /** Once per process: the microphone sources the device supports (a device property). */
    private suspend fun sendInputOptions() {
        if (inputOptionsSent) return
        inputOptionsSent = true
        val supported = unprocessedSupported
            ?: (runCatching { audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) }
                .getOrNull() == "true")
        try {
            engine.invokeCommand("audio.setInputOptions", buildJsonObject { put("unprocessedSupported", supported) })
        } catch (e: CancellationException) {
            inputOptionsSent = false
            throw e
        } catch (e: Exception) {
            // A fake engine without the command: the engine keeps its defaults
            Log.i(TAG, "audio.setInputOptions: $e")
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L

        private const val TAG = "AudacityDevices"

        /** The device as `audio.setDevices` wants it; null for a device that
         *  cannot be described (its capabilities fall back to "any"). */
        fun specOf(d: AudioDeviceInfo): AudioDeviceSpec? = try {
            AudioDeviceSpec(
                id = d.id,
                name = d.productName?.toString().orEmpty(),
                type = d.type,
                isSource = d.isSource,
                isSink = d.isSink,
                // Empty = any (AudioDeviceInfo's convention, also the bridge's)
                channelCounts = runCatching { d.channelCounts.toList() }.getOrDefault(emptyList()),
                sampleRates = runCatching { d.sampleRates.toList() }.getOrDefault(emptyList()),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "skipping audio device: $e")
            null
        }
    }
}

