package io.github.sakkijarvenpolkka.trio.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Runs the shared test vectors and golden outputs (the same ones the desktop tests use) on the
 * JVM. The app's on-device tests run the same checks on the Android runtime.
 */
class VectorsTest {
    private val sharedDir = File(System.getProperty("trio.shared") ?: "../../shared")
    private val checks = CoreChecks(SharedData { File(sharedDir, it).readText() }) { File(sharedDir, "test-vectors/$it").readText() }

    private fun pass(failures: List<String>) = assertEquals(failures.joinToString("\n"), 0, failures.size)

    @Test fun classifyInput() = pass(checks.classifyInput())

    @Test fun search() = pass(checks.search())

    @Test fun privacy() = pass(checks.privacy())

    @Test fun loginState() = pass(checks.loginState())

    @Test fun smallHistoryScenario() = pass(checks.smallHistory())

    @Test fun goldenStatistics() = pass(checks.goldenStats())

    @Test fun goldenDigests() = pass(checks.goldenDigests())

    @Test fun goldenPrompts() = pass(checks.goldenPrompts())
}
