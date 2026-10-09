/*
 * Audacity Android port — the quick effects sheet of the mobile edit bar
 * (EditorCallbacks.onQuickEffects): large tiles for one-tap effects and for
 * the dialogs of the effects used most on a phone (effects/QuickEffects.kt).
 *
 * A tile that needs a time selection is dimmed without one; tapping it says
 * what is missing in the sheet itself (the snackbar would be hidden behind
 * the sheet's scrim), with 3.7.9's TellUserWhyDisallowed wording.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.dialogs

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppDialog
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.effects.QuickEffect
import io.github.sakkijarvenpolkka.audacity.effects.QuickEffects
import io.github.sakkijarvenpolkka.audacity.engine.model.CommandFlags
import io.github.sakkijarvenpolkka.audacity.menu.Disallowed
import io.github.sakkijarvenpolkka.audacity.menu.MenuItem
import io.github.sakkijarvenpolkka.audacity.menu.MenuState
import io.github.sakkijarvenpolkka.audacity.ui.SectionHeader
import io.github.sakkijarvenpolkka.audacity.util.UiText
import io.github.sakkijarvenpolkka.audacity.util.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEffectsSheet(d: AppDialog, vm: AppViewModel, state: MenuState, onItem: (MenuItem) -> Boolean) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val oneTap = remember(state.effects) { QuickEffects.oneTap(state.effects) }
    val dialogs = remember(state.effects) { QuickEffects.dialogs(state.effects) }
    var why by remember { mutableStateOf<UiText?>(null) }
    val flags = state.flags
    val noSelection = (flags and CommandFlags.TS) == 0L

    fun tap(q: QuickEffect, label: String) {
        val item = q.item()
        val reason = Disallowed.reason(item.required, flags, label, q.noiseReduction)
        if (reason != null) {
            why = reason
            return
        }
        why = null
        if (onItem(item)) vm.dismiss(d)
    }

    ModalBottomSheet(onDismissRequest = { vm.dismiss(d) }, sheetState = sheetState, modifier = Modifier.testTag("quickEffects")) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(stringResource(R.string.qfx_title), style = MaterialTheme.typography.titleMedium)
            val hint = why?.text() ?: if (noSelection) stringResource(R.string.qfx_select_first) else null
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (why != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("quickEffects:hint"),
                )
            }
            if (oneTap.isNotEmpty()) {
                SectionHeader(stringResource(R.string.qfx_one_tap))
                TileRow(oneTap, flags) { q, label -> tap(q, label) }
            }
            if (dialogs.isNotEmpty()) {
                SectionHeader(stringResource(R.string.qfx_with_settings))
                TileRow(dialogs, flags) { q, label -> tap(q, label) }
            }
            TextButton(
                onClick = {
                    vm.dismiss(d)
                    vm.open(AppDialog.EffectMenu)
                },
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).testTag("quickEffects:all"),
            ) { Text(stringResource(R.string.qfx_all_effects)) }
        }
    }
}

@Composable
private fun TileRow(entries: List<QuickEffect>, flags: Long, onTap: (QuickEffect, String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (q in entries) {
            val label = q.label.text()
            Tile(label, enabled = CommandFlags.enabled(q.required, flags), tag = "quick:${q.key}") { onTap(q, label) }
        }
    }
}

/** A ≥ 56 dp tall tile; a disabled one stays tappable (it explains why). */
@Composable
private fun Tile(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val unavailable = stringResource(R.string.qfx_unavailable)
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 104.dp, max = 220.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) stateDescription = unavailable }
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 2)
    }
}
