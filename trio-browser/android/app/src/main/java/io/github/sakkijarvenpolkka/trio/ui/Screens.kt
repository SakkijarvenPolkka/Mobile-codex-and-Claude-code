package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.webkit.CookieManager
import android.webkit.WebStorage
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import java.text.DateFormat
import java.util.Date

@Composable
fun TabsScreen(browser: BrowserModel, onClose: () -> Unit) {
    FullScreenPage(stringResource(R.string.tabs), onClose, actions = {
        IconButton(onClick = { browser.newTab(null); onClose() }) { Icon(Icons.plus, stringResource(R.string.new_tab)) }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("tab-list")) {
            items(browser.tabs.toList(), key = { it.id }) { tab ->
                val index = browser.tabs.indexOf(tab)
                Row(
                    Modifier.fillMaxWidth().clickable { browser.activate(index); onClose() }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Favicon(tab.favicon)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tab.title.ifEmpty { stringResource(R.string.new_tab) }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (index == browser.activeIndex) FontWeight.Bold else FontWeight.Normal)
                        Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { browser.closeTab(tab) }) { Icon(Icons.close, stringResource(R.string.close_tab)) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun HistoryScreen(app: TrioApp, browser: BrowserModel, hub: AgentHub, initialQuery: String, onClose: () -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(query, version) { runCatching { app.history.query(query, null, 300) }.getOrDefault(emptyList()) }
    FullScreenPage(stringResource(R.string.history), onClose, actions = {
        TextButton(onClick = { onClose(); hub.openHistory() }) { Text(stringResource(R.string.history_analyze)) }
        TextButton(onClick = { app.history.deleteAll(); version++ }) { Text(stringResource(R.string.history_clear)) }
    }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("history-search"), singleLine = true, placeholder = { Text(stringResource(R.string.history_search)) })
        if (entries.isEmpty()) Text(stringResource(R.string.history_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val dayFormat = DateFormat.getDateInstance(DateFormat.FULL)
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        LazyColumn(Modifier.fillMaxSize().testTag("history-list")) {
            var lastDay = ""
            for (e in entries) {
                val day = dayFormat.format(Date(e.time))
                if (day != lastDay) {
                    lastDay = day
                    item(key = "d$day${e.visitId}") { Text(day, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item(key = e.visitId) {
                    Row(Modifier.fillMaxWidth().clickable { browser.open(e.url); onClose() }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(timeFormat.format(Date(e.time)), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifEmpty { e.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { app.history.deleteVisit(e.visitId); version++ }) { Icon(Icons.close, stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksScreen(app: TrioApp, browser: BrowserModel, onClose: () -> Unit) {
    FullScreenPage(stringResource(R.string.bookmarks), onClose) {
        if (app.bookmarks.items.isEmpty()) Text(stringResource(R.string.bookmarks_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(app.bookmarks.items.toList(), key = { it.id }) { b ->
                Row(Modifier.fillMaxWidth().clickable { browser.open(b.url, transition = "bookmark"); onClose() }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.star, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { app.bookmarks.remove(b.id) }) { Icon(Icons.close, stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, desc: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (desc != null) Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

@Composable
fun SettingsScreen(app: TrioApp, hub: AgentHub, onAbout: () -> Unit, onClose: () -> Unit) {
    val s = app.settings
    FullScreenPage(stringResource(R.string.settings), onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.search_engine), Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), fontWeight = FontWeight.Bold)
            for (e in app.shared.searchData.engines) {
                Row(Modifier.fillMaxWidth().clickable { s.searchEngine = e.id }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.searchEngine == e.id, { s.searchEngine = e.id })
                    Text(e.name)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.agents), Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold)
            for (a in hub.agents) {
                SettingRow(stringResource(R.string.agent_enabled, a.provider.name), stringResource(stateLabel(a.state))) {
                    Switch(s.agentEnabled(a.provider.id), { s.setAgentEnabled(a.provider.id, it) })
                }
            }
            SettingRow(stringResource(R.string.dock)) { Switch(s.bubbleVisible, { s.bubbleVisible = it }) }
            SettingRow(stringResource(R.string.preview_page_setting)) { Switch(s.previewPage, { s.previewPage = it }) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingRow(stringResource(R.string.save_history)) { Switch(s.saveHistory, { s.saveHistory = it }) }
            SettingRow(stringResource(R.string.exclude_sensitive), stringResource(R.string.exclude_sensitive_desc)) { Switch(s.excludeSensitive, { s.excludeSensitive = it }) }
            SettingRow(stringResource(R.string.clear_cookies), stringResource(R.string.clear_cookies_desc)) {
                OutlinedButton(onClick = {
                    CookieManager.getInstance().removeAllCookies { hub.refreshSessions() }
                    WebStorage.getInstance().deleteAllData()
                }) { Text(stringResource(R.string.delete)) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onAbout, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.about)) }
        }
    }
}

@Composable
fun AboutScreen(onClose: () -> Unit) {
    FullScreenPage(stringResource(R.string.about), onClose) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trio 0.1.0", style = MaterialTheme.typography.titleLarge)
            Text("Engine: Android System WebView (Chromium)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {}
            Button(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    }
}
