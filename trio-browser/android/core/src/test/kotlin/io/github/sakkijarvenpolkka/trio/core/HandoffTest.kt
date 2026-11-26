package io.github.sakkijarvenpolkka.trio.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HandoffTest {
    private val sharedDir = File(System.getProperty("trio.shared") ?: "../../shared")
    private val data = SharedData { File(sharedDir, it).readText() }

    @Test
    fun handoffPlans() {
        val claude = data.provider("claude")
        assertEquals(LoginState.Handoff.Insert("https://claude.ai/new"), LoginState.planHandoff(claude, "hi", "new"))
        assertEquals(LoginState.Handoff.Clipboard, LoginState.planHandoff(claude, "x".repeat(100_001), "new"))
        val withPrefill = claude.copy(prefill = Prefill("https://x.test/?q={prompt}", 20, false))
        assertEquals(LoginState.Handoff.Url("https://x.test/?q=a%20b"), LoginState.planHandoff(withPrefill, "a b", "new"))
    }
}
