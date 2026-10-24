// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import org.robolectric.Shadows.shadowOf

/**
 * Delivers [intent] to every receiver registered in the process whose filter matches it (action and
 * data scheme). Robolectric's `sendBroadcast` prints each broadcast and its receivers to stderr; this
 * keeps the test output quiet. Unlike `Context.sendBroadcast`, it ignores receiver permissions and
 * export flags, and runs the receivers synchronously on the calling (main) thread instead of posting
 * them to the main looper; a test of either needs the real `sendBroadcast`.
 */
internal fun deliverBroadcast(context: Context, intent: Intent) {
    val app = context.applicationContext as Application
    shadowOf(app).registeredReceivers
        .filter { it.intentFilter.match(app.contentResolver, intent, false, "deliverBroadcast") >= 0 }
        .forEach { it.broadcastReceiver.onReceive(it.context, intent) }
}
