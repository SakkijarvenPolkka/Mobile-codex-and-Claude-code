// SPDX-License-Identifier: GPL-2.0-or-later
package io.github.sakkijarvenpolkka.audacity

import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ApplicationProvider
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectInfo
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectMenus
import io.github.sakkijarvenpolkka.audacity.engine.model.HistoryState
import io.github.sakkijarvenpolkka.audacity.engine.model.LastEffect
import io.github.sakkijarvenpolkka.audacity.engine.model.MenuSection
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.menu.Disallowed
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuSpec
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.menu.Separator
import io.github.sakkijarvenpolkka.audacity.menu.SubMenu
import io.github.sakkijarvenpolkka.audacity.menu.expand
import io.github.sakkijarvenpolkka.audacity.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenuSpecTest {
    private val NB = CommandFlags.NB
    private val TS = CommandFlags.TS
    private val WS = CommandFlags.WS
    private val TE = CommandFlags.TE
    private val CC = CommandFlags.CC
    private val UA = CommandFlags.UA
    private val PO = CommandFlags.PROJECT_OPEN

    private val res get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources
    private fun find(id: String, st: MenuState = MenuState(Snapshot.EMPTY)): MenuItem =
        assertNotNullAndGet(MenuSpec.find(id, st), id)

    private fun assertNotNullAndGet(item: MenuItem?, id: String): MenuItem {
        assertNotNull("menu item $id", item)
        return item!!
    }

    @Test
    fun topLevelOrderIsAudacity379() {
        assertEquals(
            listOf("File", "Edit", "Select", "View", "Transport", "Tracks", "Generate", "Effect", "Analyze", "Tools", "Help"),
            MenuSpec.menus.map { it.id },
        )
    }

    @Test
    fun enableLogicFollowsRequiredFlags() {
        val cut = find("Cut")
        assertEquals(NB or CC, cut.required)
        assertFalse(cut.enabled(NB))
        assertFalse(cut.enabled(CC or CommandFlags.BUSY))
        assertTrue(cut.enabled(NB or CC or TS))

        val undo = find("Undo")
        assertTrue(undo.enabled(NB or UA))
        assertFalse(undo.enabled(NB))

        assertEquals(CommandFlags.JC, find("Join").required)
        assertFalse(find("SelectAll").enabled(0L))
        assertTrue(find("SelectAll").enabled(TE))
        assertTrue(find("About").enabled(0L))
        assertEquals(NB or CommandFlags.WE, find("Export").required)
        assertEquals(NB or TS or WS or CommandFlags.LAST_EFF, find("RepeatLastEffect").required)
        // Save needs an open project (Android flag) besides AudioIONotBusy.
        assertFalse(find("Save").enabled(NB))
        assertTrue(find("Save").enabled(NB or PO))
    }

    @Test
    fun linuxStandardShortcuts() {
        val st = MenuState(Snapshot.EMPTY)
        fun id(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
            MenuSpec.findByShortcut(key, ctrl, shift, alt, st)?.id
        assertEquals("Undo", id(Key.Z, ctrl = true))
        assertEquals("Redo", id(Key.Z, ctrl = true, shift = true))
        assertEquals("DefaultPlayStop", id(Key.Spacebar))
        assertEquals("OncePlayStop", id(Key.Spacebar, shift = true))
        assertEquals("Record1stChoice", id(Key.R))
        assertEquals("Record2ndChoice", id(Key.R, shift = true))
        assertEquals("RepeatLastEffect", id(Key.R, ctrl = true))
        assertEquals("ZoomIn", id(Key.One, ctrl = true))
        assertEquals("Preferences", id(Key.P, ctrl = true))
        assertEquals("AddLabelPlaying", id(Key.M, ctrl = true))
        assertEquals("CursProjectStart", id(Key.MoveHome))
        assertEquals("DeleteKey", id(Key.Backspace))
        // Full-set-only (†) keys are not bound in the Standard set.
        assertNull(id(Key.A, ctrl = true, shift = true))   // Select None
        assertNull(id(Key.N, ctrl = true, shift = true))   // New Mono Track
        assertEquals("Ctrl+Shift+E", find("Export").shortcut?.display)
        assertEquals("Ctrl+Alt+X", find("SplitCut").shortcut?.display)
    }

    @Test
    fun noShortcutIsBoundTwice() {
        val items = MenuSpec.allItems(MenuState(Snapshot.EMPTY)).filter { it.shortcut != null }
        val clashes = items.groupBy { it.shortcut!!.display }.filterValues { v -> v.map { it.id }.distinct().size > 1 }
        assertTrue(clashes.mapValues { e -> e.value.map { it.id } }.toString(), clashes.isEmpty())
    }

    @Test
    fun dynamicUndoRedoAndRepeatLabels() {
        val snap = Snapshot(history = HistoryState(canUndo = true, undo = "Cut"), lastEffect = LastEffect("id", "Amplify"))
        val st = MenuState(snap)
        assertEquals(UiText.Res(R.string.m_undo_fmt, listOf("Cut")), find("Undo").labelFor(st))
        assertEquals("Undo Cut", find("Undo").labelFor(st).resolve(res))
        assertEquals(UiText.Res(R.string.m_redo), find("Redo").labelFor(st))
        assertEquals("Repeat Amplify", find("RepeatLastEffect").labelFor(st).resolve(res))
        assertEquals("Repeat Last Generator", find("RepeatLastGenerator").labelFor(st).resolve(res))
    }

    @Test
    @Config(qualifiers = "ko")
    fun koreanLabelsComeFromAudacityTranslation() {
        assertEquals("되돌리기", find("Undo").label.resolve(res))
        assertEquals("파일", UiText.Res(R.string.menu_file).resolve(res))
        assertEquals("Cut 되돌리기", find("Undo").labelFor(MenuState(Snapshot(history = HistoryState(canUndo = true, undo = "Cut")))).resolve(res))
    }

    @Test
    fun effectMenusAreBuiltFromEngineSections() {
        val list = EffectList(
            effects = listOf(
                EffectInfo("amp", "Amplify", "process"),
                EffectInfo("fadein", "Fade In", "process", interactive = false),
                EffectInfo("tone", "Tone", "generate"),
                EffectInfo("nr", "Noise Reduction", "process", special = "noiseReduction"),
                EffectInfo("beat", "Beat Finder", "analyze", family = "Nyquist"),
            ),
            menus = EffectMenus(
                generate = listOf(MenuSection(null, listOf("tone"))),
                effect = listOf(
                    MenuSection("Volume and Compression", listOf("amp")),
                    MenuSection("Fading", listOf("fadein", "missing-id")),
                    MenuSection("Noise Removal and Repair", listOf("nr")),
                    MenuSection("Some Publisher", listOf("missing-only")),
                ),
                analyze = listOf(MenuSection(null, listOf("beat"))),
            ),
        )
        val st = MenuState(Snapshot.EMPTY, effects = list)
        val effect = MenuSpec.menus.first { it.id == "Effect" }.children.expand(st)
        val subs = effect.filterIsInstance<SubMenu>()
        assertEquals(listOf("Volume and Compression", "Fading", "Noise Removal and Repair"), subs.map { it.label.resolve(res) })
        val amp = subs[0].children.single() as MenuItem
        assertEquals("Amplify...", amp.label.resolve(res))
        assertEquals(NB or TS or WS, amp.required)
        assertEquals("Fade In", (subs[1].children.single() as MenuItem).label.resolve(res))
        assertTrue((subs[2].children.single() as MenuItem).noiseReductionReason)

        val gen = MenuSpec.menus.first { it.id == "Generate" }.children.expand(st).filterIsInstance<MenuItem>()
        val tone = gen.first { it.id == "tone" }
        assertEquals(NB or PO, tone.required)
        val analyze = MenuSpec.menus.first { it.id == "Analyze" }.children.expand(st).filterIsInstance<MenuItem>().map { it.id }
        assertEquals(listOf("RepeatLastAnalyzer", "ContrastAnalyser", "PlotSpectrum", "beat"), analyze)
    }

    @Test
    fun separatorsAreNormalised() {
        val a = MenuItem("a", UiText.Raw("a")) { }
        val b = MenuItem("b", UiText.Raw("b")) { }
        val out = listOf(Separator, a, Separator, Separator, SubMenu("empty", UiText.Raw("e"), listOf(Separator)), b, Separator)
            .expand(MenuState(Snapshot.EMPTY))
        assertEquals(listOf(a, Separator, b), out)
    }

    @Test
    fun disallowedMessagesFollowCommonCommandFlags() {
        val req = NB or TS or WS
        assertEquals(UiText.Res(R.string.why_audio_busy), Disallowed.reason(req, TS or WS or CommandFlags.BUSY, "Amplify"))
        val sel = Disallowed.reason(req, NB, "Amplify")!!.resolve(res)
        assertEquals("Select the audio for Amplify to use (for example, Ctrl + A to Select All) then try again.", sel)
        val nr = Disallowed.reason(req, NB, "Noise Reduction", noiseReduction = true)!!.resolve(res)
        assertTrue(nr, nr.startsWith("Select the audio for Noise Reduction to use.") && nr.contains("use Noise Reduction to get your 'noise profile'"))
        assertEquals(UiText.Res(R.string.why_wave_selected), Disallowed.reason(req, NB or TS, "Amplify"))
        assertEquals(
            "\"Remove Tracks\" requires one or more tracks to be selected.",
            Disallowed.reason(NB or CommandFlags.AS, NB, "Remove Tracks")!!.resolve(res),
        )
        assertEquals(UiText.Res(R.string.why_nothing_to_undo), Disallowed.reason(NB or UA, NB, "Undo"))
        assertNull(Disallowed.reason(NB, NB or TS, "x"))
    }
}
