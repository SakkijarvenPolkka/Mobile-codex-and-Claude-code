// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The changes the system file picker hears of from the app (its provider's root and folders), heard as the picker
 * hears them, by observing the provider's authority, from when this is made.
 */
internal class PickerChanges(context: Context) : ContentObserver(null) {

    private val heard = CopyOnWriteArrayList<Uri>()

    init {
        context.contentResolver.registerContentObserver(Uri.parse("content://$AUTHORITY"), true, this)
    }

    /** What was heard since the last [take], in order. */
    fun take(): List<Uri> = heard.toList().also { heard.removeAll(it.toSet()) }

    /** What was heard since the last [take], in order, once that is at least [count] changes (told from any thread). */
    fun take(count: Int): List<Uri> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (heard.size < count) {
            check(System.nanoTime() < deadline) { "Heard only ${heard.toList()}" }
            Thread.sleep(10)
        }
        return take()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (uri != null) heard += uri
    }

    companion object {
        const val AUTHORITY = "io.github.sakkijarvenpolkka.explorer.documents"

        /** The provider's root changed. */
        val ROOTS: Uri = DocumentsContract.buildRootsUri(AUTHORITY)

        /** The folder [documentId] holds other items. */
        fun childrenOf(documentId: String): Uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId)
    }
}
