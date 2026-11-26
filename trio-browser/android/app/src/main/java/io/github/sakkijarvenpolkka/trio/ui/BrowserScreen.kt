package io.github.sakkijarvenpolkka.trio.ui

import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sakkijarvenpolkka.trio.R
import io.github.sakkijarvenpolkka.trio.TrioApp
import io.github.sakkijarvenpolkka.trio.agents.AgentHub
import io.github.sakkijarvenpolkka.trio.browser.BrowserModel
import io.github.sakkijarvenpolkka.trio.core.ClassifyInput
import io.github.sakkijarvenpolkka.trio.core.Hosts
import io.github.sakkijarvenpolkka.trio.core.InputKind

enum class Screen { Tabs, History, Bookmarks, Settings, About }

/** Hosts a WebView that lives outside composition (tabs and agents keep theirs). */
@Composable
fun WebViewHost(webView: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx) },
        update = { container ->
            if (container.getChildAt(0) !== webView) {
                container.removeAllViews()
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        modifier = modifier,
    )
}

/** The address bar's text and focus, shared with the suggestions that float over the panel. */
internal class AddressState {
    var value by mutableStateOf(TextFieldValue(""))
    var focused by mutableStateOf(false)

    /** The text to suggest for, while the bar is being edited. */
    val suggesting: String? get() = value.text.takeIf { focused && it.isNotBlank() }
}

@Composable
fun BrowserScreen(app: TrioApp, browser: BrowserModel, hub: AgentHub) {
    var screen by remember { mutableStateOf<Screen?>(null) }
    var historyQuery by remember { mutableStateOf("") }
    var addressCommand by remember { mutableStateOf<String?>(null) }
    val address = remember { AddressState() }
    var addressHeight by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    androidx.compose.runtime.DisposableEffect(browser) {
        browser.onShowHistory = { q -> historyQuery = q; screen = Screen.History }
        onDispose { browser.onShowHistory = null }
    }
    // Opening an agent panel (a New Tab card, the menu, the bubble, "Ask …") ends address-bar editing.
    LaunchedEffect(hub.openId) {
        if (hub.openId != null) focus.clearFocus()
    }

    BackHandler(enabled = address.focused || screen != null || hub.openId != null || browser.active?.canGoBack == true || browser.fullscreenView != null) {
        when {
            browser.fullscreenView != null -> browser.exitFullscreen()
            screen != null -> screen = null
            address.focused -> focus.clearFocus()
            hub.openId != null -> hub.openId = null
            else -> browser.back()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            AddressBar(app, browser, hub, address, addressCommand, onCommandHandled = { addressCommand = null }, modifier = Modifier.onSizeChanged { addressHeight = it.height })
            if (browser.findQuery != null) FindBar(browser)
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                val tab = browser.active
                if (tab != null) {
                    WebViewHost(tab.webView, Modifier.fillMaxSize().testTag("tab-webview"))
                    tab.error?.let { err -> ErrorPage(tab.url, err) { browser.reload() } }
                    if (tab.url.isEmpty() && tab.error == null) NewTabPage(app, hub) { browser.navigate(it) }
                }
            }
            BottomBar(
                app, browser, hub,
                openScreen = { screen = it },
                askAll = { addressCommand = "@all " },
            )
        }

        if (app.settings.bubbleVisible) AgentBubble(app, hub)
        hub.open?.let { AgentPanel(app, hub, it, browser) }

        // The suggestions, with their "Ask …" rows, float over the page and over the agent bubble and panel.
        val suggesting = address.suggesting
        if (suggesting != null && screen == null && browser.fullscreenView == null) {
            Surface(Modifier.padding(top = with(density) { addressHeight.toDp() }).fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                SuggestionList(app, hub, suggesting) { action -> focus.clearFocus(); action() }
            }
        }

        when (screen) {
            Screen.Tabs -> TabsScreen(browser) { screen = null }
            Screen.History -> HistoryScreen(app, browser, hub, historyQuery) { screen = null }
            Screen.Bookmarks -> BookmarksScreen(app, browser) { screen = null }
            Screen.Settings -> SettingsScreen(app, hub, onAbout = { screen = Screen.About }) { screen = null }
            Screen.About -> AboutScreen { screen = null }
            null -> Unit
        }

        browser.fullscreenView?.let { view ->
            AndroidView(factory = { FrameLayout(it).apply { addView(view) } }, modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
        }
    }
}

private data class Suggestion(val title: String, val subtitle: String, val agentId: String?, val locked: Boolean, val action: () -> Unit)

@Composable
private fun AddressBar(
    app: TrioApp,
    browser: BrowserModel,
    hub: AgentHub,
    address: AddressState,
    command: String?,
    onCommandHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tab = browser.active
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val selection = remember { AddressSelection() }
    val shown = tab?.url?.let { if (it.isEmpty() || it == "about:blank") "" else Hosts.displayUrl(it) } ?: ""
    androidx.compose.runtime.LaunchedEffect(shown, address.focused) {
        if (!address.focused && address.value.text != shown) address.value = TextFieldValue(shown)
    }
    androidx.compose.runtime.LaunchedEffect(command) {
        if (command != null) {
            address.value = TextFieldValue(command, TextRange(command.length))
            selection.commandApplied(willFocus = !address.focused)
            if (!address.focused) requester.requestFocus()
            onCommandHandled()
        }
    }
    val onFocus = { isFocused: Boolean ->
        val gained = isFocused && !address.focused
        address.focused = isFocused
        address.value = selection.onFocusChanged(isFocused, gained, address.value)
    }
    val onValueChange = { new: TextFieldValue -> address.value = selection.onValueChange(address.value, new) }
    val submit = { text: String ->
        focus.clearFocus()
        browser.navigate(text)
    }
    val focused = address.focused
    val value = address.value

    Surface(modifier, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(21.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (tab?.url?.startsWith("https://") == true) Icons.lock else Icons.search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty()) Text(stringResource(R.string.address_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { if (address.value.text.isNotBlank()) submit(address.value.text) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(requester).testTag("address-input").onFocusChanged { onFocus(it.isFocused) },
                        )
                    }
                    if (tab != null && !focused) {
                        IconButton(onClick = { if (tab.loading) browser.stop() else browser.reload() }, modifier = Modifier.size(32.dp)) {
                            Icon(if (tab.loading) Icons.close else Icons.reload, stringResource(if (tab.loading) R.string.stop else R.string.reload), Modifier.size(18.dp))
                        }
                    }
                }
                val search = tab?.url?.let { app.shared.searchData.extractSearch(it) }
                if (search != null && !focused) {
                    IconButton(onClick = { hub.runAction(hub.lastUsed, "search_page") }, modifier = Modifier.testTag("ask-search")) {
                        Icon(Icons.sparkles, stringResource(R.string.action_search_page), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (tab != null && tab.loading) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun SuggestionList(app: TrioApp, hub: AgentHub, text: String, run: (() -> Unit) -> Unit) {
    val shared = app.shared
    val browserNav = LocalBrowser.current
    val kind = ClassifyInput.classify(text, shared.agentKeywords, shared.suffixes)
    val engine = shared.searchData.engine(app.settings.searchEngine)
    val rows = ArrayList<Suggestion>()
    when (kind) {
        is InputKind.Url -> rows.add(Suggestion(kind.url, stringResource(R.string.open_url), null, false) { browserNav?.navigate(text) })
        is InputKind.Search -> rows.add(Suggestion(kind.query, stringResource(R.string.search_with, engine.name), null, false) { browserNav?.navigate(text) })
        is InputKind.Agent -> rows.add(Suggestion(kind.query.ifEmpty { kind.agentId }, stringResource(R.string.ask_agent, if (kind.agentId == "all") stringResource(R.string.agents) else hub.agent(kind.agentId).provider.name), kind.agentId.takeIf { it != "all" }, false) { browserNav?.navigate(text) })
        is InputKind.History -> rows.add(Suggestion(kind.query, stringResource(R.string.history), null, false) { browserNav?.navigate(text) })
        InputKind.Empty -> Unit
    }
    if (kind is InputKind.Search || kind is InputKind.Url) {
        for (b in app.bookmarks.search(text, 3)) rows.add(Suggestion(b.title, b.url, null, false) { browserNav?.open(b.url, transition = "bookmark") })
        for ((url, title) in runCatching { app.history.suggest(text, 5) }.getOrDefault(emptyList())) {
            if (rows.none { it.subtitle == url }) rows.add(Suggestion(title.ifEmpty { url }, url, null, false) { browserNav?.open(url, transition = "typed") })
        }
        val question = (kind as? InputKind.Search)?.query ?: text
        for (a in hub.agents.filter { app.settings.agentEnabled(it.provider.id) }) {
            rows.add(Suggestion(question, stringResource(R.string.ask_agent, a.provider.name), a.provider.id, a.state != "signedIn") { hub.ask(a.provider.id, question) })
        }
    }
    // As tall as the rows (the "Ask …" rows come last), scrolling past the cap or the space above the keyboard.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("suggestions")) {
        items(rows) { s ->
            Row(Modifier.fillMaxWidth().clickable { run(s.action) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.agentId != null) {
                    val a = hub.agent(s.agentId)
                    AgentCircle(a.provider.monogram, a.provider.accent, a.state, 26.dp)
                } else Icon(Icons.search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    Text(s.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = if (s.agentId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.locked) Icon(Icons.lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The browser model for composables deep in the tree. */
val LocalBrowser = androidx.compose.runtime.staticCompositionLocalOf<BrowserModel?> { null }

@Composable
private fun FindBar(browser: BrowserModel) {
    var text by remember { mutableStateOf(browser.findQuery.orEmpty()) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = text,
            onValueChange = { text = it; browser.find(it) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f).testTag("find-input"),
        )
        val (cur, total) = browser.findResult
        Text(if (text.isEmpty()) "" else if (total > 0) "$cur/$total" else stringResource(R.string.find_none), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { browser.findNext(false) }) { Icon(Icons.chevronUp, null) }
        IconButton(onClick = { browser.findNext(true) }) { Icon(Icons.chevronDown, null) }
        IconButton(onClick = { browser.closeFind() }) { Icon(Icons.close, stringResource(R.string.close)) }
    }
}

@Composable
private fun BottomBar(app: TrioApp, browser: BrowserModel, hub: AgentHub, openScreen: (Screen) -> Unit, askAll: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tab = browser.active
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { browser.back() }, enabled = tab?.canGoBack == true) { Icon(Icons.back, stringResource(R.string.back)) }
            IconButton(onClick = { browser.forward() }, enabled = tab?.canGoForward == true) { Icon(Icons.forward, stringResource(R.string.forward)) }
            IconButton(onClick = { browser.newTab(null) }) { Icon(Icons.plus, stringResource(R.string.new_tab)) }
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).border(1.6.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp)).clickable { openScreen(Screen.Tabs) }.testTag("tabs-button"),
                contentAlignment = Alignment.Center,
            ) { Text(browser.tabs.size.toString(), fontSize = 13.sp, style = MaterialTheme.typography.labelLarge) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-button")) { Icon(Icons.more, stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val bookmarked = tab?.url?.let { app.bookmarks.find(it) } != null
                    DropdownMenuItem(text = { Text(stringResource(if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark)) }, onClick = {
                        menu = false
                        tab?.takeIf { it.url.startsWith("http") }?.let { app.bookmarks.toggle(it.title, it.url) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.find_in_page)) }, onClick = { menu = false; browser.find("") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.ask_all)) }, onClick = { menu = false; askAll() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.analyze_history)) }, onClick = { menu = false; hub.openHistory() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.history)) }, onClick = { menu = false; openScreen(Screen.History) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks)) }, onClick = { menu = false; openScreen(Screen.Bookmarks) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; openScreen(Screen.Settings) })
                }
            }
        }
    }
}

@Composable
private fun ErrorPage(url: String, error: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.error_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(url, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = retry) { Text(stringResource(R.string.error_retry)) }
    }
}

@Composable
private fun NewTabPage(app: TrioApp, hub: AgentHub, navigate: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Trio", fontSize = 40.sp, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (a in hub.agents.filter { app.settings.agentEnabled(it.provider.id) }) {
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable { hub.show(a.provider.id) }.padding(14.dp).testTag("agent-card-${a.provider.id}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AgentCircle(a.provider.monogram, a.provider.accent, a.state, 44.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(a.provider.name, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(stateLabel(a.state)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        val top = remember { runCatching { app.history.suggest("http", 6) }.getOrDefault(emptyList()) }
        for ((url, title) in top.take(4)) {
            Text(title.ifEmpty { url }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { navigate(url) }.padding(6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun stateLabel(state: String): Int = when (state) {
    "signedIn" -> R.string.state_signed_in
    "signingIn" -> R.string.state_signing_in
    else -> R.string.state_signed_out
}

@Composable
fun Favicon(bitmap: android.graphics.Bitmap?) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)))
    else Icon(Icons.search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FullScreenPage(title: String, onClose: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.back, stringResource(R.string.back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                actions()
            }
            content()
        }
    }
}
