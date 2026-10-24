// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A package uses only the packages below it (spec §1.2). */
class LayeringTest {

    @Test
    fun nothingBelowPreviewUsesIt() {
        val root = sourceRoot()
        val sources = BELOW_PREVIEW.flatMap { pkg -> File(root, pkg).walkTopDown().filter { it.extension == "kt" } }
        assertTrue("no sources under $root", sources.isNotEmpty())
        val users = sources.filter { PREVIEW in it.readText() }.map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), users)
    }

    /** `src/main/java/.../explorer` of this module (Gradle runs unit tests in the module directory). */
    private fun sourceRoot(): File {
        val path = "src/main/java/io/github/sakkijarvenpolkka/explorer"
        val candidates = listOf(File(path), File("explorer", path))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("$path not found (looked at ${candidates.map { it.absolutePath }})")
    }

    private companion object {
        const val PREVIEW = "io.github.sakkijarvenpolkka.explorer.preview"

        /** The packages under `preview/`, in the order of spec §1.2. */
        val BELOW_PREVIEW = listOf("state", "ops", "data", "platform", "fs", "util")
    }
}
