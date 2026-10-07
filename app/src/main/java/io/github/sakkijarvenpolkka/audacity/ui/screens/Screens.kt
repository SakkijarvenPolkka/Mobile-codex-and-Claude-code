/*
 * Audacity Android port — full-screen pages: project manager, About and the
 * engine log.
 *
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package io.github.sakkijarvenpolkka.audacity.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sakkijarvenpolkka.audacity.AppScreen
import io.github.sakkijarvenpolkka.audacity.AppViewModel
import io.github.sakkijarvenpolkka.audacity.BuildConfig
import io.github.sakkijarvenpolkka.audacity.HostRequest
import io.github.sakkijarvenpolkka.audacity.OpenPurpose
import io.github.sakkijarvenpolkka.audacity.R
import io.github.sakkijarvenpolkka.audacity.engine.model.ProjectFileEntry
import io.github.sakkijarvenpolkka.audacity.util.TimeCodec
import java.text.DateFormat
import java.util.Date

/** A page with a top app bar and a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.btn_back)) }
                },
                actions = actions,
            )
        },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Project manager
// ---------------------------------------------------------------------------

@Composable
fun ProjectsScreen(vm: AppViewModel) {
    val projects by vm.recentProjects.collectAsState()
    val snapshot by vm.engine.snapshot.collectAsState()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ScreenScaffold(
        title = stringResource(R.string.pm_title),
        onBack = { vm.show(AppScreen.EDITOR) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { vm.launchAction { vm.newProject() } }, modifier = Modifier.testTag("pm:new")) {
                    Icon(Icons.Filled.Add, null)
                    Text(stringResource(R.string.pm_new))
                }
                OutlinedButton(onClick = {
                    vm.request(HostRequest.OpenDocuments(OpenPurpose.OPEN, listOf("*/*"), multiple = true))
                }) { Text(stringResource(R.string.pm_open_device)) }
                TextButton(onClick = { vm.showRecovery() }) { Text(stringResource(R.string.pm_recover)) }
            }
            HorizontalDivider()
            if (projects.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.pm_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(projects.sortedByDescending { it.modifiedMs }, key = { it.path }) { p ->
                        ProjectRow(p, isOpen = snapshot.project.path == p.path, fmt = fmt, vm = vm)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectFileEntry, isOpen: Boolean, fmt: DateFormat, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.launchAction { vm.openProjectFile(p.path) } }
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.name.ifEmpty { p.path.substringAfterLast('/') } + if (isOpen) "  " + stringResource(R.string.pm_open_now) else "",
                style = MaterialTheme.typography.titleMedium, fontWeight = if (isOpen) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(fmt.format(Date(p.modifiedMs)) + " · " + TimeCodec.formatBytes(p.sizeBytes), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.btn_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.pm_open)) }, onClick = {
                    menu = false
                    vm.launchAction { vm.openProjectFile(p.path) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.pm_rename)) }, enabled = !isOpen, onClick = {
                    menu = false
                    vm.launchAction { vm.renameProjectFile(p) }
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.pm_delete)) }, enabled = !isOpen, onClick = {
                    menu = false
                    vm.launchAction { vm.deleteProjectFile(p) }
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

/** Third-party components linked into the engine (license notices in About). */
private val THIRD_PARTY = listOf(
    "wxWidgets (base)" to "wxWindows Library Licence 3.1",
    "libsndfile" to "LGPL-2.1-or-later",
    "libogg, libvorbis" to "BSD-3-Clause",
    "FLAC" to "BSD-3-Clause (libFLAC)",
    "Opus, opusfile" to "BSD-3-Clause",
    "mpg123" to "LGPL-2.1",
    "LAME" to "LGPL-2.0-or-later",
    "WavPack" to "BSD-3-Clause",
    "TwoLAME" to "LGPL-2.1-or-later",
    "libsoxr" to "LGPL-2.1-or-later",
    "SoundTouch" to "LGPL-2.1",
    "SBSMS" to "GPL-2.0-or-later",
    "SQLite" to "Public domain",
    "PortAudio" to "MIT-style licence",
    "Nyquist" to "BSD-style licence (Roger B. Dannenberg)",
    "nlohmann/json" to "MIT",
    "RapidJSON" to "MIT",
    "Expat" to "MIT",
    "pffft" to "BSD-like (FFTPACK licence)",
    "portsmf" to "MIT-style licence",
)

@Composable
fun AboutScreen(vm: AppViewModel) {
    val info by vm.appInfo.collectAsState()
    ScreenScaffold(title = stringResource(R.string.about_title), onBack = { vm.show(AppScreen.EDITOR) }) { padding ->
        SelectionContainer {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .widthIn(max = 720.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME, info?.audacityVersion?.ifEmpty { null } ?: "3.7.9"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (vm.engine.isFake) {
                    Text(stringResource(R.string.about_fake_engine), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                info?.let { i ->
                    Text(
                        "engine ${i.engineVersion} · wxWidgets ${i.wxVersion} · SQLite ${i.sqliteVersion} · ${i.abi}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Para(stringResource(R.string.about_disclaimer), bold = true)
                Para(stringResource(R.string.about_audacity))
                Heading(stringResource(R.string.about_credits_title))
                Para(stringResource(R.string.about_credits))
                Heading(stringResource(R.string.about_license_title))
                Para(stringResource(R.string.about_license))
                Para("This program is free software; you can redistribute it and/or modify it under the terms of the GNU " +
                    "General Public License as published by the Free Software Foundation; either version 2 of the License, " +
                    "or (at your option) any later version. This program is distributed in the hope that it will be useful, " +
                    "but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A " +
                    "PARTICULAR PURPOSE. See the GNU General Public License for more details: https://www.gnu.org/licenses/")
                TextButton(onClick = { vm.request(HostRequest.OpenUrl("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")) }) {
                    Text("GNU GPL v2")
                }
                TextButton(onClick = { vm.request(HostRequest.OpenUrl("https://www.gnu.org/licenses/gpl-3.0.html")) }) {
                    Text("GNU GPL v3")
                }
                Heading(stringResource(R.string.about_third_party))
                THIRD_PARTY.forEach { (name, license) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(license, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Para(stringResource(R.string.about_source))
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ColumnScope.Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Para(text: String, bold: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.padding(top = 8.dp),
    )
}

// ---------------------------------------------------------------------------
// Log
// ---------------------------------------------------------------------------

@Composable
fun LogScreen(vm: AppViewModel) {
    ScreenScaffold(
        title = stringResource(R.string.m_show_log).removeSuffix("..."),
        onBack = { vm.show(AppScreen.EDITOR) },
        actions = { TextButton(onClick = { vm.logLines.clear() }) { Text(stringResource(R.string.log_clear)) } },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 8.dp)) {
            items(vm.logLines.toList()) { e ->
                Text(
                    "${e.level.take(1).uppercase()}: ${e.message}",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = when (e.level) {
                        "error" -> MaterialTheme.colorScheme.error
                        "warning" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
