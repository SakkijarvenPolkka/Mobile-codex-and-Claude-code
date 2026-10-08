/*
 * Audacity Android port — foreground service that keeps a recording (and
 * playback) going while the app is in the background.
 *
 * The engine records and plays on its own native threads; without a
 * foreground service Android silences the microphone of a background app
 * and may kill the process. The service is started when the transport enters
 * "recording" or "playing" and stopped when it is stopped again
 * ([TransportForeground]). Android 14: foregroundServiceType
 * microphone|mediaPlayback (the microphone type only while recording and
 * only with RECORD_AUDIO granted). The notification has a Stop action
 * (transport.stop: a recording is committed like the desktop Stop button).
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.sakkijarvenpolkka.audacity.AudacityApp
import io.github.sakkijarvenpolkka.audacity.MainActivity
import io.github.sakkijarvenpolkka.audacity.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransportService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRANSPORT -> {
                // The notification's Stop: the "stopped" transport event then stops this service
                val app = application as? AudacityApp
                if (app == null) {
                    finish()
                } else {
                    app.appScope.launch(Dispatchers.Main) {
                        runCatching { app.engine.stop() }
                        if (app.engine.transportState.value.state == "stopped") {
                            // Normally the transport follower already sent the stop command;
                            // a late tap (the stream had just ended) started this service anew
                            if (active) stop(this@TransportService) else finish()
                        }
                    }
                }
            }
            // Commands arrive in order: a stop always follows the start it ends,
            // so startForeground() has been called for every startForegroundService()
            ACTION_STOP_SERVICE -> finish()
            else -> goForeground(intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false)
        }
        // Not restarted after the process died: the engine state is gone with it
        return START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** startForeground with the types the current state allows; a microphone
     *  type that is refused falls back to media playback (never throws, so
     *  a startForegroundService() is always answered). */
    private fun goForeground(recording: Boolean) {
        val notification = notification(this, recording)
        val mic = recording && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val playback = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        val micType = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (mic) playback or micType else playback)
            return
        } catch (e: RuntimeException) {
            // SecurityException (microphone type not allowed now) or
            // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "startForeground(${if (mic) "microphone" else "playback"}) refused: ${e.message}")
        }
        if (mic) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, playback)
                return
            } catch (e: RuntimeException) {
                Log.w(TAG, "startForeground(playback) refused: ${e.message}")
            }
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "AudacityTransport"
        const val CHANNEL_ID = "transport"
        const val NOTIFICATION_ID = 0x41756463
        const val ACTION_UPDATE = "io.github.sakkijarvenpolkka.audacity.action.TRANSPORT_UPDATE"
        const val ACTION_STOP_TRANSPORT = "io.github.sakkijarvenpolkka.audacity.action.TRANSPORT_STOP"
        const val ACTION_STOP_SERVICE = "io.github.sakkijarvenpolkka.audacity.action.SERVICE_STOP"
        const val EXTRA_RECORDING = "recording"

        /** True between a successful [start] and [stop]. */
        @Volatile
        var active: Boolean = false
            private set

        /** Starts (or updates) the service; false when Android refused it
         *  (e.g. the app is in the background on Android 12+). */
        fun start(context: Context, recording: Boolean): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransportService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_RECORDING, recording),
            )
            active = true
            true
        } catch (e: RuntimeException) {
            // IllegalStateException / ForegroundServiceStartNotAllowedException
            Log.w(TAG, "cannot start the transport service: ${e.message}")
            false
        }

        /**
         * Stops the service after a [start]. Sent as a command instead of
         * stopService(): stopping a service whose startForegroundService() has
         * not been answered by startForeground() yet crashes the app, and the
         * command is handled after the start command. While the service is in
         * the foreground the app may start services.
         */
        fun stop(context: Context) {
            if (!active) return
            active = false
            try {
                context.startService(Intent(context, TransportService::class.java).setAction(ACTION_STOP_SERVICE))
            } catch (e: RuntimeException) {
                Log.w(TAG, "cannot send the stop command: ${e.message}")
                try {
                    context.stopService(Intent(context, TransportService::class.java))
                } catch (e2: RuntimeException) {
                    Log.w(TAG, "cannot stop the transport service: ${e2.message}")
                }
            }
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun notification(context: Context, recording: Boolean): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, TransportService::class.java).setAction(ACTION_STOP_TRANSPORT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_transport)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(if (recording) R.string.notif_recording else R.string.notif_playing))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(if (recording) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_TRANSPORT)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.btn_stop), stop)
                .build()
        }
    }
}
