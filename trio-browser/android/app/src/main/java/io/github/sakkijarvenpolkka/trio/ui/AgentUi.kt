package io.github.sakkijarvenpolkka.trio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.agents.Agent
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import kotlin.math.roundToInt

/** The floating bubble: tap to show the three agents, drag to move. */
@Composable
fun AgentBubble(app: TrioApp, hub: AgentHub) {
    var expanded by remember { mutableStateOf(false) }
    var x by remember { mutableFloatStateOf(Float.NaN) }
    var y by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = with(density) { (maxWidth - 64.dp).toPx() }
        val maxY = with(density) { (maxHeight - 64.dp).toPx() }
        if (x.isNaN()) {
            x = maxX - with(density) { 8.dp.toPx() }
            y = maxY * 0.62f
        }
        Column(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        x = (x + drag.x).coerceIn(0f, maxX)
                        y = (y + drag.y).coerceIn(0f, maxY)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(bottom = 8.dp).shadow(6.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (a in hub.agents.filter { app.settings.agentEnabled(it.provider.id) }) {
                        Box(Modifier.clip(CircleShape).clickable { hub.toggle(a.provider.id); expanded = false }.testTag("bubble-agent-${a.provider.id}")) {
                            AgentCircle(a.provider.monogram, a.provider.accent, a.state, 44.dp, a.busy)
                        }
                    }
                }
            }
            Box(
                Modifier.size(56.dp).shadow(8.dp, CircleShape).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { expanded = !expanded }
                    .testTag("agent-bubble"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (expanded) Icons.close else Icons.sparkles, stringResource(R.string.dock), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** The floating agent panel: movable and resizable, with the login gate in front of the provider. */
@Composable
fun AgentPanel(app: TrioApp, hub: AgentHub, agent: Agent, browser: BrowserModel) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        var w by remember { mutableFloatStateOf(maxW * 0.94f) }
        var h by remember { mutableFloatStateOf(maxH * 0.62f) }
        var x by remember { mutableFloatStateOf(maxW * 0.03f) }
        var y by remember { mutableFloatStateOf(maxH * 0.34f) }
        val minW = with(density) { 260.dp.toPx() }
        val minH = with(density) { 300.dp.toPx() }
        Surface(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .testTag("agent-panel"),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(parseColor(agent.provider.accent)))
                    PanelHeader(app, hub, agent, Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            x = (x + drag.x).coerceIn(-w * 0.6f, maxW - w * 0.4f)
                            y = (y + drag.y).coerceIn(0f, maxH - with(density) { 48.dp.toPx() })
                        }
                    })
                    PanelBody(app, hub, agent, browser)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp).pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            w = (w + drag.x).coerceIn(minW, maxW)
                            h = (h + drag.y).coerceIn(minH, maxH)
                        }
                    }.testTag("panel-resize"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.grip, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

@Composable
private fun PanelHeader(app: TrioApp, hub: AgentHub, a: Agent, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(48.dp).padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AgentCircle(a.provider.monogram, a.provider.accent, a.state, 28.dp, a.busy)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(a.provider.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(stringResource(stateLabel(a.state)), fontSize = 11.sp, color = if (a.state == "signedIn") androidx.compose.ui.graphics.Color(0xFF1C8F4E) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("agent-state"))
        }
        if (a.state == "signedIn") {
            IconButton(onClick = { hub.newChat(a.provider.id) }) { Icon(Icons.newChat, stringResource(R.string.new_chat), Modifier.size(20.dp)) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.more, stringResource(R.string.menu), Modifier.size(20.dp)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                for (other in hub.agents.filter { it != a && app.settings.agentEnabled(it.provider.id) }) {
                    DropdownMenuItem(text = { Text(other.provider.name) }, leadingIcon = { AgentCircle(other.provider.monogram, other.provider.accent, other.state, 22.dp) }, onClick = { menu = false; hub.show(other.provider.id) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.analyze_history)) }, onClick = { menu = false; hub.openHistory(a.provider.id) })
                if (a.state == "signedIn") {
                    DropdownMenuItem(text = { Text(stringResource(R.string.open_in_tab)) }, onClick = { menu = false; LocalBrowserHolder.browser?.open(a.url.ifEmpty { a.provider.appUrl }, newTab = true) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sign_out)) }, onClick = { menu = false; hub.signOut(a.provider.id) })
                }
            }
        }
        IconButton(onClick = { hub.openId = null }, modifier = Modifier.testTag("panel-close")) { Icon(Icons.minimize, stringResource(R.string.minimize), Modifier.size(20.dp)) }
    }
}

/** Gives non-composable callbacks access to the browser (set by MainActivity). */
object LocalBrowserHolder {
    var browser: BrowserModel? = null
}

@Composable
private fun PanelBody(app: TrioApp, hub: AgentHub, a: Agent, browser: BrowserModel) {
    val state = a.state
    a.notice?.let { Text(it, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp).testTag("agent-notice")) }
    when {
        state == "signedIn" && hub.preview != null -> PreviewPanel(hub, a)
        state == "signedIn" && hub.historyOpen -> HistoryPanel(hub)
        state == "signedOut" -> LockScreen(hub, a)
        else -> {
            if (state == "signingIn") {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.signing_in_on, runCatching { android.net.Uri.parse(a.url).host.orEmpty() }.getOrDefault("")), fontSize = 12.sp, modifier = Modifier.weight(1f).testTag("signin-bar"))
                    TextButton(onClick = { hub.cancelSignIn(a.provider.id) }) { Text(stringResource(R.string.cancel)) }
                }
            } else QuickActions(app, hub, a, browser)
            Box(Modifier.fillMaxSize()) {
                val popup = a.popup
                when {
                    popup != null -> {
                        WebViewHost(popup, Modifier.fillMaxSize())
                        IconButton(onClick = { hub.closePopup(a) }, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.close, stringResource(R.string.close)) }
                    }
                    a.showProvider -> WebViewHost(hub.webViewFor(a), Modifier.fillMaxSize().testTag("provider-webview"))
                    else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
                }
            }
        }
    }
}

@Composable
private fun QuickActions(app: TrioApp, hub: AgentHub, a: Agent, browser: BrowserModel) {
    var asking by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    if (asking) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(question, { question = it }, Modifier.weight(1f).testTag("question-input"), placeholder = { Text(stringResource(R.string.question_hint, a.provider.name), fontSize = 13.sp) }, singleLine = true)
            TextButton(onClick = { if (question.isNotBlank()) { hub.runAction(a.provider.id, "ask_page", question); asking = false; question = "" } }) { Text(stringResource(R.string.insert)) }
            TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) }
        }
        return
    }
    val url = browser.active?.url.orEmpty()
    val pageOk = url.startsWith("http")
    val search = app.shared.searchData.extractSearch(url)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(onClick = { hub.runAction(a.provider.id, "summarize_page") }, label = { Text(stringResource(R.string.action_summarize_page), fontSize = 12.sp) }, enabled = pageOk, modifier = Modifier.testTag("action-summarize_page"))
        AssistChip(onClick = { asking = true }, label = { Text(stringResource(R.string.action_ask_page), fontSize = 12.sp) }, enabled = pageOk)
        AssistChip(onClick = { hub.runAction(a.provider.id, "explain_selection") }, label = { Text(stringResource(R.string.action_explain_selection), fontSize = 12.sp) }, enabled = pageOk)
        AssistChip(onClick = { hub.runAction(a.provider.id, "translate_selection") }, label = { Text(stringResource(R.string.action_translate_selection), fontSize = 12.sp) }, enabled = pageOk)
        if (search != null) AssistChip(onClick = { hub.runAction(a.provider.id, "search_page") }, label = { Text(stringResource(R.string.action_search_page), fontSize = 12.sp) })
        AssistChip(onClick = { hub.openHistory(a.provider.id) }, label = { Text(stringResource(R.string.action_history), fontSize = 12.sp) })
    }
}

@Composable
private fun LockScreen(hub: AgentHub, a: Agent) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("lock-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AgentCircle(a.provider.monogram, a.provider.accent, a.state, 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_title, a.provider.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (a.lockReason == "signInIncomplete") stringResource(R.string.lock_incomplete) else stringResource(R.string.lock_body, a.provider.name, a.provider.vendor),
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp,
        )
        a.pendingLabel?.let {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lock_pending, it), fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(10.dp))
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { hub.signIn(a.provider.id) }, modifier = Modifier.testTag("lock-signin")) { Text(stringResource(R.string.lock_sign_in, a.provider.name)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_foot, a.provider.vendor), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PreviewPanel(hub: AgentHub, a: Agent) {
    val p = hub.preview ?: return
    var text by remember(p.id) { mutableStateOf(p.text) }
    var target by remember(p.id) { mutableStateOf("new") }
    Column(Modifier.fillMaxSize().padding(14.dp).testTag("preview")) {
        Text(stringResource(R.string.preview_title, a.provider.name), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(p.source, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth().testTag("preview-text"), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_count, text.length.toString()) + if (p.truncated) " · " + stringResource(R.string.preview_truncated) else "", fontSize = 12.sp, modifier = Modifier.weight(1f))
            FilterChip(target == "new", { target = "new" }, { Text(stringResource(R.string.new_chat), fontSize = 12.sp) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { hub.preview = null }) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { hub.confirmPreview(text, target) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("preview-confirm")) { Text(stringResource(R.string.preview_confirm)) }
        }
        Text(stringResource(R.string.preview_foot, a.provider.name), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun HistoryPanel(hub: AgentHub) {
    val f = hub.historyForm
    var text by remember(hub.historyPreview?.id) { mutableStateOf(hub.historyPreview?.text.orEmpty()) }
    var toAll by remember { mutableStateOf(false) }
    LaunchedEffect(f) { hub.rebuildHistoryPreview() }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("history-panel")) {
        Text(stringResource(R.string.history_title), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((r, label) in listOf("today" to R.string.range_today, "7d" to R.string.range_7d, "30d" to R.string.range_30d, "90d" to R.string.range_90d, "all" to R.string.range_all)) {
                FilterChip(f.range == r, { hub.historyForm = f.copy(range = r) }, { Text(stringResource(label), fontSize = 12.sp) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, label) in listOf("habits" to R.string.kind_habits, "topics" to R.string.kind_topics, "find" to R.string.kind_find, "time" to R.string.kind_time, "custom" to R.string.kind_custom)) {
                FilterChip(f.kind == k, { hub.historyForm = f.copy(kind = k) }, { Text(stringResource(label), fontSize = 12.sp) })
            }
        }
        if (f.kind == "find" || f.kind == "custom") {
            OutlinedTextField(f.question, { hub.historyForm = f.copy(question = it) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(stringResource(R.string.action_ask)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(f.queries, { hub.historyForm = f.copy(queries = it) }); Text(stringResource(R.string.include_queries), fontSize = 12.sp)
            Checkbox(f.titles, { hub.historyForm = f.copy(titles = it) }); Text(stringResource(R.string.include_titles), fontSize = 12.sp)
            Checkbox(f.paths, { hub.historyForm = f.copy(paths = it) }); Text(stringResource(R.string.include_paths), fontSize = 12.sp)
        }
        hub.historyPreview?.counts?.let { c ->
            Text(stringResource(R.string.history_counts, c[0].toString(), c[1].toString(), c[2].toString(), c[3].toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("history-counts"))
        }
        OutlinedTextField(text, { text = it }, Modifier.weight(1f).fillMaxWidth().testTag("history-text"), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(toAll, { toAll = it }); Text(stringResource(R.string.to_all), fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { hub.historyOpen = false }) { Text(stringResource(R.string.close)) }
            Spacer(Modifier.width(8.dp))
            val needsQuestion = f.kind == "find" || f.kind == "custom"
            Button(onClick = { hub.sendHistory(text, toAll) }, enabled = text.isNotBlank() && (!needsQuestion || f.question.isNotBlank()), modifier = Modifier.testTag("history-send")) { Text(stringResource(R.string.preview_confirm)) }
        }
        Text(stringResource(R.string.history_foot), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}
