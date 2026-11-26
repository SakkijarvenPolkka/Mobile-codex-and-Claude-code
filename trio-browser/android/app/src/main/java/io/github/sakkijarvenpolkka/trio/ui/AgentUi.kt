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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.agents.Agent
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Where the floating panel goes: the size and place the user chose, fitted into the space there is now
 * (rotation, folding, split screen and the keyboard all change it). Never an empty range, so no
 * window size can make it throw. The panel may stick out sideways, but stays inside vertically so its
 * header and the chat box above the keyboard stay reachable.
 */
internal data class PanelBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun fit(maxW: Float, maxH: Float, minW: Float, minH: Float): PanelBox {
        val mw = maxOf(maxW, 0f)
        val mh = maxOf(maxH, 0f)
        val fw = w.coerceIn(minOf(minW, mw), mw)
        val fh = h.coerceIn(minOf(minH, mh), mh)
        return PanelBox(x.coerceIn(-fw * 0.6f, mw - fw * 0.4f), y.coerceIn(0f, mh - fh), fw, fh)
    }

    /** Dragged by its header. */
    fun moved(dx: Float, dy: Float, maxW: Float, maxH: Float, minW: Float, minH: Float): PanelBox =
        copy(x = x + dx, y = y + dy).fit(maxW, maxH, minW, minH)

    /** Resized by its corner grip; the top stays where it is. */
    fun resized(dw: Float, dh: Float, maxW: Float, maxH: Float, minW: Float, minH: Float): PanelBox {
        val f = fit(maxW, maxH, minW, minH)
        val room = maxOf(maxH, 0f) - f.y
        return f.copy(w = f.w + dw, h = (f.h + dh).coerceIn(minOf(minH, room), room)).fit(maxW, maxH, minW, minH)
    }
}

/**
 * The panel the user has (or has not yet) chosen: each part they never set follows the current space,
 * so a panel opened while the keyboard is up is full size once it goes down.
 */
internal fun panelFor(x: Float?, y: Float?, w: Float?, h: Float?, maxW: Float, maxH: Float): PanelBox =
    PanelBox(x ?: maxW * 0.03f, y ?: maxH * 0.34f, w ?: maxW * 0.94f, h ?: maxH * 0.62f)

/** What a panel window's address row says, as desktop's originLabel: the host (with the scheme unless https), else about:blank. */
internal data class PopupLabel(val text: String, val secure: Boolean)

internal fun popupLabel(url: String): PopupLabel {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host.orEmpty()
    if ((scheme != "http" && scheme != "https") || host.isEmpty()) return PopupLabel("about:blank", false)
    val hostPort = if (uri!!.port != -1) "$host:${uri.port}" else host
    return if (scheme == "https") PopupLabel(hostPort, true) else PopupLabel("http://$hostPort", false)
}

/** The bubble's place, kept inside [maxX] × [maxY] (both may be 0 in a tiny window). */
internal fun fitBubble(x: Float, y: Float, maxX: Float, maxY: Float): Pair<Float, Float> =
    x.coerceIn(0f, maxOf(maxX, 0f)) to y.coerceIn(0f, maxOf(maxY, 0f))

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
        // Read by the drag handler, which outlives this composition's bounds.
        val bounds by rememberUpdatedState(maxX to maxY)
        Column(
            Modifier.offset { fitBubble(x, y, maxX, maxY).let { (fx, fy) -> IntOffset(fx.roundToInt(), fy.roundToInt()) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val (bx, by) = bounds
                        val (fx, fy) = fitBubble(x, y, bx, by)
                        val (nx, ny) = fitBubble(fx + drag.x, fy + drag.y, bx, by)
                        x = nx
                        y = ny
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
        val minW = with(density) { 260.dp.toPx() }
        val minH = with(density) { 300.dp.toPx() }
        // What the user chose (null: not chosen, follows the space); shown fitted into the space there is now.
        var cx by remember { mutableStateOf<Float?>(null) }
        var cy by remember { mutableStateOf<Float?>(null) }
        var cw by remember { mutableStateOf<Float?>(null) }
        var ch by remember { mutableStateOf<Float?>(null) }
        val shown = panelFor(cx, cy, cw, ch, maxW, maxH).fit(maxW, maxH, minW, minH)
        // Read by the drag handlers, which outlive this composition's bounds.
        val bounds by rememberUpdatedState(floatArrayOf(maxW, maxH, minW, minH))
        Surface(
            Modifier.offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) }
                .size(with(density) { shown.w.toDp() }, with(density) { shown.h.toDp() })
                .testTag("agent-panel"),
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(parseColor(agent.provider.accent)))
                    PanelHeader(app, hub, agent, browser, Modifier.pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val (bw, bh, mw, mh) = bounds
                            // Moving sets the place only; the size stays what it was (or keeps following the space).
                            val m = panelFor(cx, cy, cw, ch, bw, bh).fit(bw, bh, mw, mh).moved(drag.x, drag.y, bw, bh, mw, mh)
                            cx = m.x
                            cy = m.y
                        }
                    })
                    PanelBody(app, hub, agent, browser)
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(28.dp).pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val (bw, bh, mw, mh) = bounds
                            val r = panelFor(cx, cy, cw, ch, bw, bh).fit(bw, bh, mw, mh).resized(drag.x, drag.y, bw, bh, mw, mh)
                            cw = r.w
                            ch = r.h
                        }
                    }.testTag("panel-resize"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.grip, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

@Composable
private fun PanelHeader(app: TrioApp, hub: AgentHub, a: Agent, browser: BrowserModel, modifier: Modifier) {
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
                    DropdownMenuItem(text = { Text(stringResource(R.string.open_in_tab)) }, onClick = { menu = false; browser.open(a.url.ifEmpty { a.provider.appUrl }, newTab = true) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sign_out)) }, onClick = { menu = false; hub.signOut(a.provider.id) })
                }
            }
        }
        IconButton(onClick = { hub.openId = null }, modifier = Modifier.testTag("panel-close")) { Icon(Icons.minimize, stringResource(R.string.minimize), Modifier.size(20.dp)) }
    }
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
                    popup != null -> Column(Modifier.fillMaxSize()) {
                        // A window opened by the provider: its address is always shown, so it cannot pose as another site.
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val label = popupLabel(a.popupUrl)
                            Icon(if (label.secure) Icons.lock else Icons.search, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            // The end of a host (its registrable domain) is the part that must stay visible.
                            Text(label.text, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f).testTag("popup-host"))
                            IconButton(onClick = { hub.closePopup(a) }, modifier = Modifier.testTag("popup-close")) { Icon(Icons.close, stringResource(R.string.close)) }
                        }
                        WebViewHost(popup, Modifier.fillMaxSize())
                    }
                    a.showProvider -> WebViewHost(hub.webViewFor(a), Modifier.fillMaxSize().testTag("provider-webview"))
                    a.stopped -> Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(24.dp).testTag("agent-stopped"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.agent_stopped, a.provider.name), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { hub.reload(a.provider.id) }) { Text(stringResource(R.string.error_retry)) }
                    }
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
    // The text shown was built for this form (Send waits until it is).
    val upToDate = hub.historyPreviewForm == f
    LaunchedEffect(f) {
        // Each letter typed into the question changes the form: rebuild once typing pauses. Other changes
        // (range, kind, what to include) rebuild at once. Both off the main thread.
        val typing = hub.historyPreviewForm?.copy(question = f.question) == f
        if (typing) delay(300)
        hub.refreshHistoryPreview()
    }
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
            Button(onClick = { hub.sendHistory(text, toAll) }, enabled = upToDate && text.isNotBlank() && (!needsQuestion || f.question.isNotBlank()), modifier = Modifier.testTag("history-send")) { Text(stringResource(R.string.preview_confirm)) }
        }
        Text(stringResource(R.string.history_foot), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}
