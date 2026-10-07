/*
 * Audacity Android port — menu model.
 *
 * The 3.7.9 menus (src/menus, not compiled for Android) are a static
 * Kotlin tree keyed by the 3.7.9 CommandIDs (ui-reference.md §1). An item is
 * enabled when `(required and flags.inv()) == 0` with the engine's flag
 * bitset (API.md §4.2, port of CommonCommandFlags.cpp). Separators follow
 * `CommandManager::Populator::DoSeparator`: only between non-empty groups.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.menu

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppScreen
import io.github.sakkijarvenpolkka.audacity.HostRequest
import io.github.sakkijarvenpolkka.audacity.editor.EditorState
import io.github.sakkijarvenpolkka.audacity.engine.AudacityEngine
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.engine.model.EffectList
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.engine.model.Settings
import io.github.sakkijarvenpolkka.audacity.engine.model.Snapshot
import io.github.sakkijarvenpolkka.audacity.engine.model.TimeRange
import io.github.sakkijarvenpolkka.audacity.prefs.UiPrefs
import io.github.sakkijarvenpolkka.audacity.util.UiText

/** A keyboard shortcut of the 3.7.9 Linux "Standard" set. */
data class Shortcut(val key: Key, val keyName: String, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    /** Desktop notation, e.g. "Ctrl+Shift+Z". */
    val display: String
        get() = buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift) append("Shift+")
            append(keyName)
        }

    fun matches(key: Key, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
        this.key == key && this.ctrl == ctrl && this.shift == shift && this.alt == alt
}

/** Everything a menu action may use. Implemented by the AppViewModel (and by test hosts). */
interface MenuHost {
    val engine: AudacityEngine
    /** The editor's view state (zoom/scroll/track heights); null before the editor is shown. */
    val editor: EditorState?
    val uiPrefs: UiPrefs
    /** Height of the editor area in dp (Fit to Height). */
    val editorHeightDp: Float
    var storedSelection: TimeRange?
    var storedCursor: Double?

    fun open(dialog: AppDialog)
    fun show(screen: AppScreen)
    fun request(request: HostRequest)
    fun message(text: UiText)

    /** File ▸ New (asks to save changes first). */
    suspend fun newProject()
    /** File ▸ Save Project (falls back to Save As for a never-saved project). */
    suspend fun saveProject()
    suspend fun saveProjectAs()
    /** File ▸ Close Project (asks to save changes first). */
    suspend fun closeProject()
    /** Record (R) / Record New Track (Shift+R): handles the microphone permission. */
    fun record(newTrack: Boolean)
    /** An effect/generator/analyzer/tool picked in a menu: dialog or direct apply. */
    suspend fun runEffect(effectId: String)
    /** Applies a partial engine settings update and refreshes the cached settings. */
    suspend fun updateSettings(partial: Settings)
    /** Text of the system clipboard (Paste Text to New Label). */
    fun clipboardText(): String?
    /** Opens a recent project from app storage (asks to save changes first). */
    suspend fun openProjectFile(path: String)
}

/** Inputs that menu labels, checks and dynamic sections depend on. */
data class MenuState(
    val snapshot: Snapshot,
    val effects: EffectList? = null,
    val settings: Settings? = null,
    val showClipping: Boolean = false,
    val showRms: Boolean = false,
    val followPlayhead: Boolean = true,
    val recentProjects: List<ProjectFileEntry> = emptyList(),
) {
    val flags: Long get() = snapshot.flags
}

sealed interface MenuNode

/** A command. [id] is the 3.7.9 CommandID (unique per menu path, e.g. "Pause" twice). */
class MenuItem(
    val id: String,
    val label: UiText,
    val required: Long = 0L,
    val shortcut: Shortcut? = null,
    /** Check mark / radio state; null for plain commands. */
    val checked: ((MenuState) -> Boolean)? = null,
    /** Label that depends on the state (Undo %s, Repeat %s); null = [label]. */
    val dynamicLabel: ((MenuState) -> UiText?)? = null,
    /** Noise Reduction has its own "select audio" explanation (CommonCommandFlags.cpp). */
    val noiseReductionReason: Boolean = false,
    val action: suspend (MenuHost) -> Unit,
) : MenuNode {
    fun enabled(flags: Long): Boolean = CommandFlags.enabled(required, flags)
    fun labelFor(state: MenuState): UiText = dynamicLabel?.invoke(state) ?: label
    override fun toString(): String = "MenuItem($id)"
}

class SubMenu(val id: String, val label: UiText, val children: List<MenuNode>) : MenuNode {
    override fun toString(): String = "SubMenu($id)"
}

/** Separator (section boundary). */
data object Separator : MenuNode

/** Items computed from the state (effect lists, recent files). */
class DynamicItems(val id: String, val build: (MenuState) -> List<MenuNode>) : MenuNode

/** A top-level menu (File … Help). */
class TopMenu(val id: String, @StringRes val labelRes: Int, val children: List<MenuNode>) {
    val label: UiText get() = UiText.Res(labelRes)
}

/** Expands [DynamicItems] and normalises separators (no leading, trailing or doubled ones). */
fun List<MenuNode>.expand(state: MenuState): List<MenuNode> {
    val flat = ArrayList<MenuNode>()
    for (n in this) {
        when (n) {
            is DynamicItems -> flat += n.build(state).expand(state)
            is SubMenu -> {
                val kids = n.children.expand(state)
                if (kids.any { it !is Separator }) flat += SubMenu(n.id, n.label, kids)
            }
            else -> flat += n
        }
    }
    val out = ArrayList<MenuNode>(flat.size)
    for (n in flat) {
        if (n is Separator && (out.isEmpty() || out.last() is Separator)) continue
        out += n
    }
    while (out.isNotEmpty() && out.last() is Separator) out.removeAt(out.lastIndex)
    return out
}

/** Depth-first walk over items (dynamic nodes expanded with [state]). */
fun List<MenuNode>.allItems(state: MenuState): List<MenuItem> {
    val out = ArrayList<MenuItem>()
    fun walk(nodes: List<MenuNode>) {
        for (n in nodes) when (n) {
            is MenuItem -> out += n
            is SubMenu -> walk(n.children)
            is DynamicItems -> walk(n.build(state))
            Separator -> Unit
        }
    }
    walk(this)
    return out
}
