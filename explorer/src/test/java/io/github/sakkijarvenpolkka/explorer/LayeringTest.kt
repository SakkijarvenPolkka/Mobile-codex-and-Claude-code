// SPDX-License-Identifier: MIT
package io.github.sakkijarvenpolkka.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A package uses only the packages below it (spec §1.2). */
class LayeringTest {

    @Test
    fun nothingBelowPreviewUsesIt() = assertUnused(PREVIEW, by = BELOW_PREVIEW)

    /** `ui/` and `ui/fluent/` are above `preview/`: what is below it uses neither (spec §1.2). */
    @Test
    fun nothingBelowPreviewUsesUi() = assertUnused(UI, by = BELOW_PREVIEW)

    /** The system picker's provider uses none of `state/`, `ui/`, `preview/` and `pick/` (pick spec §1). */
    @Test
    fun providerUsesNoUpperLayer() {
        for (pkg in listOf(STATE, UI, PREVIEW, PICK)) assertUnused(pkg, by = listOf("provider"))
    }

    /** `state/` uses neither `pick/` nor `provider/`: the pick request it needs is its own (pick spec §1). */
    @Test
    fun stateUsesNoPickOrProvider() {
        for (pkg in listOf(PICK, PROVIDER)) assertUnused(pkg, by = listOf("state"))
    }

    /** The sources of the packages [by] that mention [pkg] (any of its subpackages too): none. */
    private fun assertUnused(pkg: String, by: List<String>) {
        val root = sourceRoot()
        val sources = by.flatMap { user -> File(root, user).walkTopDown().filter { it.extension == "kt" } }
        assertTrue("no sources of $by under $root", sources.isNotEmpty())
        val users = sources.filter { pkg in it.readText() }.map { it.relativeTo(root).path }
        assertEquals("users of $pkg", emptyList<String>(), users)
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
        const val STATE = "io.github.sakkijarvenpolkka.explorer.state"
        const val PICK = "io.github.sakkijarvenpolkka.explorer.pick"
        const val PROVIDER = "io.github.sakkijarvenpolkka.explorer.provider"

        /** The packages under `preview/`, in the order of spec §1.2. */
        val BELOW_PREVIEW = listOf("state", "ops", "data", "platform", "fs", "util")
    }
}
