/*
 * Files — telling the system file picker that what it shows of the app changed, so that it reads it
 * again (pick spec §3.1).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.Context
import android.provider.DocumentsContract
import java.nio.file.Path

/** Tells the picker of changes to the provider's root and to the folders it lists. */
object DocumentsNotifier {

    /** The provider's authority: `<package>.documents`, as the manifest declares it. */
    fun authority(context: Context): String = context.packageName + ".documents"

    /** The root changed: it comes and goes with the app's access, and lists the favorites and the drives. */
    fun roots(context: Context) {
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
    }

    /** The folders [docIds] (document ids) hold other items now. */
    fun children(context: Context, docIds: Collection<String>) {
        val authority = authority(context)
        for (id in docIds) {
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority, id), null)
        }
    }

    /**
     * The folders [paths] are in hold other items now: each that [ids] names, once (the picker is shown no other).
     * It reads the file system.
     */
    fun parentsOf(context: Context, ids: DocumentIds, paths: Collection<Path>) {
        children(context, paths.mapNotNullTo(LinkedHashSet()) { path -> path.parent?.let(ids::idOf) })
    }
}
