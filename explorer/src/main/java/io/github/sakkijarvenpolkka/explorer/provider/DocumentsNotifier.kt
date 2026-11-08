/*
 * Files — telling the system file picker that what it shows of the app changed, so that it reads it
 * again (pick spec §3.1).
 *
 * SPDX-License-Identifier: MIT
 */
package io.github.sakkijarvenpolkka.explorer.provider

import android.content.Context
import android.net.Uri
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
     * The folders [paths] are in hold other items now: each that [ids] names, once (the picker is shown no other),
     * named once each. It reads the file system.
     */
    fun parentsOf(context: Context, ids: DocumentIds, paths: Collection<Path>) {
        children(context, paths.mapNotNullTo(LinkedHashSet()) { it.parent }.mapNotNull(ids::idOf))
    }

    /**
     * The top folder, which lists the favorites, holds other items now if one of [changed] (items renamed, moved,
     * deleted or made) is one of the [favorites] (where they are) or holds one: it no longer is there, or is again.
     */
    fun favoritesAmong(context: Context, changed: Collection<Path>, favorites: Collection<Path>) {
        if (changed.any { item -> favorites.any { it.startsWith(item) } }) children(context, listOf(ROOT_DOC_ID))
    }

    /**
     * Any document may be another now (a drive came or went): each folder the picker shows reads itself again, and one
     * on a drive that went says that it is not there.
     */
    fun allDocuments(context: Context) {
        context.contentResolver.notifyChange(Uri.parse("content://${authority(context)}/document"), null)
    }
}
