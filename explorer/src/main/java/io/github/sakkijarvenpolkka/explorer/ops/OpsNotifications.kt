/*
 * Files — the notification of the file operations (spec §5): its channel, what it says of the
 * operation running, and its cancel action.
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.ops

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.sakkijarvenpolkka.explorer.R
import io.github.sakkijarvenpolkka.explorer.fs.OpKind

/** The steps of the progress bar when it goes by bytes. */
private const val PROGRESS_STEPS = 1000

/** Each pending intent holds the latest of what it is made with; no one else may change it. */
private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

/**
 * The one notification of the file operations, [ID] on the channel [CHANNEL], which [FileOpsService]
 * shows while it runs.
 */
object OpsNotifications {
    const val CHANNEL = "file_operations"
    const val ID = 1001

    /** What [FileOpsService] is started with to cancel the operation [EXTRA_OP_ID] names. */
    internal const val ACTION_CANCEL = "io.github.sakkijarvenpolkka.explorer.action.CANCEL_OPERATION"
    internal const val EXTRA_OP_ID = "io.github.sakkijarvenpolkka.explorer.extra.OP_ID"

    /** Makes [CHANNEL], or gives it the name of the app's language now: a quiet one, for work the user started. */
    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notif_channel))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * The notification for [state]: what the operation running (or else the next one) does, with how many
     * of its items are done, a progress bar and its cancel action; "Your decision is needed" over that
     * while it waits for an answer. Tapping it opens the app.
     */
    fun progress(context: Context, state: RunnerState): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val spec = state.running?.spec ?: state.queue.firstOrNull()
            ?: return builder.setContentTitle(context.getString(R.string.notif_channel)).build()
        val progress = state.running?.progress
        val doing = context.getString(
            doingOf(spec.kind),
            progress?.doneItems ?: 0,
            progress?.totalItems ?: spec.sources.size,
        )
        if (state.pending != null) {
            builder.setContentTitle(context.getString(R.string.notif_needs_decision)).setContentText(doing)
        } else {
            builder.setContentTitle(doing).setContentText(progress?.currentName)
        }
        when {
            progress == null -> builder.setProgress(0, 0, true)
            progress.totalBytes > 0 -> {
                val done = progress.doneBytes.coerceIn(0, progress.totalBytes) * PROGRESS_STEPS / progress.totalBytes
                builder.setProgress(PROGRESS_STEPS, done.toInt(), false)
            }
            progress.totalItems > 0 -> builder.setProgress(progress.totalItems, progress.doneItems, false)
            else -> builder.setProgress(0, 0, true)
        }
        return builder.addAction(0, context.getString(R.string.cancel), cancelIntent(context, spec.id)).build()
    }

    /** Starts [FileOpsService] to cancel the operation [opId] ([OperationRunner.cancel]). */
    fun cancelIntent(context: Context, opId: String): PendingIntent {
        val intent = Intent(context, FileOpsService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_OP_ID, opId)
            // One for each operation: a tap on one shown a moment before never cancels the next.
            .setIdentifier(opId)
        return PendingIntent.getService(context, 0, intent, PENDING_FLAGS)
    }

    /** Opens the app where it was, as its launcher icon does. */
    private fun openApp(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PENDING_FLAGS)
    }

    /** What an operation of [kind] is said to be doing, with how many of how many items. */
    @StringRes
    private fun doingOf(kind: OpKind): Int = when (kind) {
        OpKind.COPY -> R.string.notif_copying
        OpKind.MOVE, OpKind.RESTORE -> R.string.notif_moving
        OpKind.DELETE_TO_BIN, OpKind.DELETE_PERMANENT, OpKind.EMPTY_BIN -> R.string.notif_deleting
        OpKind.COMPRESS -> R.string.notif_compressing
        OpKind.EXTRACT -> R.string.notif_extracting
    }
}
