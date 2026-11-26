package io.github.sakkijarvenpolkka.trio

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Asking agents from the address bar, the way a user does it: taps and the keyboard's Go key. */
@RunWith(RobolectricTestRunner::class)
class AddressBarTest {
    val rule = createAndroidComposeRule<MainActivity>()

    /** A phone starts in touch mode with nothing focused; without this Robolectric focuses the address bar at launch. */
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() = InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }).around(rule)

    private fun address() = rule.onNodeWithTag("address-input")
    private fun addressText() = address().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Menu › Ask all agents puts "@all " in the address bar; what the user types must follow it, not replace it. */
    @Test
    fun askAllKeepsTheCommandWhileTyping() {
        rule.onNodeWithTag("menu-button").performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.ask_all)).performClick()
        rule.waitForIdle()
        assertEquals("@all ", addressText())

        address().performTextInput("compare the three")
        assertEquals("@all compare the three", addressText())

        address().performImeAction()
        rule.waitForIdle()
        assertTrue(rule.activity.hub.agents.all { it.pendingLabel != null })
    }

    /** With a page open, tapping the address bar selects the address, so "@claude …" replaces it instead of landing inside it. */
    @Test
    fun askFromTheAddressBarWhileAPageIsOpen() {
        address().performClick()
        address().performTextInput("first search")
        address().performImeAction()
        rule.waitForIdle()
        assertTrue(addressText().startsWith("https://www.google.com/search"))

        address().performClick()
        rule.waitForIdle()
        address().performTextInput("@claude summarize")
        assertEquals("@claude summarize", addressText())

        address().performImeAction()
        rule.waitForIdle()
        assertEquals("claude", rule.activity.hub.openId)
        assertNotNull(rule.activity.hub.agent("claude").pendingLabel)
    }

    /** An open agent panel floats over the page, but not over the address bar's suggestions: "Ask ChatGPT" stays tappable. */
    @Test
    fun suggestionsStayAboveAnOpenAgentPanel() {
        rule.runOnUiThread { rule.activity.hub.ask("claude", "hello") }
        rule.waitForIdle()
        assertEquals("claude", rule.activity.hub.openId)

        address().performClick()
        address().performTextInput("weather")
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.ask_agent, "ChatGPT")).performClick()
        rule.waitForIdle()
        assertEquals("chatgpt", rule.activity.hub.openId)
        assertNotNull(rule.activity.hub.agent("chatgpt").pendingLabel)
    }
}
