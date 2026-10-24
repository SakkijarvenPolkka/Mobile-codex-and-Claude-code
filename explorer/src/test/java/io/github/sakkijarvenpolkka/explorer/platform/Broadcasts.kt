// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import org.robolectric.Shadows.shadowOf

/**
 * Delivers [intent] to every receiver registered in the process whose filter matches it (action and
 * data scheme), as `Context.sendBroadcast` would. Robolectric's `sendBroadcast` prints each broadcast
 * and its receivers to stderr; this keeps the test output quiet. Main thread; the receivers run at once
 * rather than on the next turn of the main looper.
 */
internal fun deliverBroadcast(context: Context, intent: Intent) {
    val app = context.applicationContext as Application
    shadowOf(app).registeredReceivers
        .filter { it.intentFilter.match(app.contentResolver, intent, false, "deliverBroadcast") >= 0 }
        .forEach { it.broadcastReceiver.onReceive(it.context, intent) }
}
