// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer.fs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

class RestrictedPathsTest {

    private val root: Path = Paths.get("/storage/emulated/0")

    private fun under(relative: String): Path = root.resolve(relative)

    @Test
    fun androidDataAndObbAreRestrictedIgnoringCase() {
        assertTrue(isRestrictedAppDataPath(root, under("Android/data")))
        assertTrue(isRestrictedAppDataPath(root, under("android/DATA/com.x")))
        assertTrue(isRestrictedAppDataPath(root, under("Android/obb/y")))
        assertTrue(isRestrictedAppDataPath(root, under("Android/media/../data")))
    }

    @Test
    fun androidItselfAndItsOtherFoldersAreNot() {
        assertFalse(isRestrictedAppDataPath(root, under("Android")))
        assertFalse(isRestrictedAppDataPath(root, under("Android/media")))
        assertFalse(isRestrictedAppDataPath(root, root))
        assertFalse(isRestrictedAppDataPath(root, under("Download/Android/data")))
        assertFalse(isRestrictedAppDataPath(root, under("Android/database")))
        assertFalse(isRestrictedAppDataPath(root, Paths.get("/storage/1A2B-3C4D/Android/data")))
    }

    @Test
    fun obbIsAllowedWhenInstallsArePermitted() {
        assertFalse(isRestrictedAppDataPath(root, under("Android/obb"), obbAllowed = true))
        assertFalse(isRestrictedAppDataPath(root, under("Android/obb/y"), obbAllowed = true))
        assertTrue(isRestrictedAppDataPath(root, under("Android/data/com.x"), obbAllowed = true))
    }
}
