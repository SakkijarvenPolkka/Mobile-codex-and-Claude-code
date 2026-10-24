// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A package uses only the packages below it (spec §1.2). */
class LayeringTest {

    @Test
    fun nothingBelowPreviewUsesIt() = assertUnused(PREVIEW)

    /** `ui/` and `ui/fluent/` are above `preview/`: what is below it uses neither (spec §1.2). */
    @Test
    fun nothingBelowPreviewUsesUi() = assertUnused(UI)

    /** The sources of the packages below `preview/` that mention [pkg] (any of its subpackages too): none. */
    private fun assertUnused(pkg: String) {
        val root = sourceRoot()
        val sources = BELOW_PREVIEW.flatMap { below -> File(root, below).walkTopDown().filter { it.extension == "kt" } }
        assertTrue("no sources under $root", sources.isNotEmpty())
        val users = sources.filter { pkg in it.readText() }.map { it.relativeTo(root).path }
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
        const val UI = "io.github.sakkijarvenpolkka.explorer.ui"

        /** The packages under `preview/`, in the order of spec §1.2. */
        val BELOW_PREVIEW = listOf("state", "ops", "data", "platform", "fs", "util")
    }
}
