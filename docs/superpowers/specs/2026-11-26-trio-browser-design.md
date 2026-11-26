# Trio Browser — Design

Status: v1 (research results in Appendix A; items marked ⟨R⟩ are backed by it).
Date: 2026-11-26. Working name: **Trio** (트리오). The name lives in a handful of clearly listed places (§11) and can be changed later.

## 요약 (Korean summary)

- **무엇을**: 크로뮴 엔진 위에 만든 자체 브라우저. Claude · Gemini · ChatGPT 세 에이전트가 기본 탑재되어 브라우저 위를 **떠다니는 별도 창**으로 뜬다.
- **로그인 게이트**: 각 에이전트는 해당 서비스의 **공식 로그인 페이지에서 사용자가 직접 로그인**해야 잠금이 풀린다. 브라우저는 비밀번호를 보거나 저장하지 않고, 로그인 여부만 세션(쿠키·주소)으로 판단한다.
- **검색·기록 분석**: 주소창에서 바로 에이전트에게 묻기(`@claude 질문`), 현재 페이지·선택 영역·검색 결과를 에이전트에 넘기기, 그리고 기기 안에서 계산한 방문 기록 요약(자주 가는 사이트, 시간대, 검색어 등)을 **미리보기로 확인한 뒤** 에이전트에 넘겨 분석하게 하기.
- **플랫폼**: 데스크톱(Electron = 크로뮴 내장, Windows/macOS/Linux)을 먼저, 이어서 같은 설계로 안드로이드(크로뮴 기반 시스템 WebView + Kotlin/Compose).
- **하지 않는 것**: 에이전트의 답변을 읽어 가거나(스크래핑), 에이전트가 브라우저를 대신 조작하게 하거나, 계정 정보를 다루는 일.

## 1. Intent and why

The user asked for (their words, translated): *"a self-made browser based on Chromium with Claude, Gemini and ChatGPT built in as agents"*, where *"the agents can only be used after logging in"*, and *"float around as a separate UI so they can be used for search and history analysis"*. Asked which device it is for, the answer was **both** desktop and Android.

Reading of the why: one browser where all three assistants are always one click away without tab-juggling, each tied to the user's own account, and able to work with what the browser knows (the page in front of the user, their searches, their browsing history).

## 2. Goals, non-goals, anti-goals

### Goals

1. A real, usable browser on a Chromium engine: tabs, address bar with suggestions, navigation, history, bookmarks, downloads, find-in-page, zoom, private browsing, settings, session restore, Korean and English UI.
2. Three built-in agents (Claude, Gemini, ChatGPT), each shown in its own **floating** surface that hovers over the browser and can be moved, resized, minimised and summoned from a floating dock.
3. **Login gate**: an agent is locked until the user is signed in to that provider. Sign-in happens on the provider's own page. Every agent entry point respects the lock.
4. **Search with agents**: ask an agent straight from the address bar; hand a web search (query, results page) to an agent; ask all signed-in agents at once.
5. **History analysis with agents**: a local history store plus on-device analytics; the user previews exactly what will be shared and then hands it to an agent with an analysis question.
6. **Page context**: summarise page, explain / translate selection, ask about page.
7. The same behaviour on desktop and Android, enforced by shared data files and shared test vectors.
8. Everything that can be verified in the build environment is verified by automated tests; what cannot be verified (real sign-in, real devices, Windows/macOS) is stated plainly.

### Non-goals (this version)

- Forking and compiling Chromium itself; browser extensions support; sync across devices; a password manager or autofill; ad blocking.
- Agents that act on the browser autonomously (clicking, form filling, opening tabs on their own).
- API-key based chat inside the browser (no API keys; the official web apps are the agents).
- iOS.

### Anti-goals (would make it a failure even if it "works")

- Reading, scraping or storing the assistants' answers or the user's chats.
- Seeing, storing or transmitting passwords, session tokens or cookies anywhere outside the engine's own cookie store.
- Sending page content or history to a provider without the user having seen what is sent, or auto-submitting such content.
- Evading a provider's security checks (spoofing automation or integrity signals, patching provider scripts).
- Shipping provider logos or anything that suggests affiliation with Anthropic, Google or OpenAI.
- An agent being usable while signed out through any entry point.

## 3. Constraints

- Built and verified in a Linux x86-64 container (4 cores, 15 GB RAM, no display, no KVM). Desktop is exercised under Xvfb with Playwright's Electron driver. Android can be compiled and unit-tested (JVM / Robolectric); real WebView behaviour and real devices are out of reach unless the software-emulator probe succeeds ⟨R⟩.
- No accounts are available for testing: sign-in and prefill against the real providers are verified only up to the public, signed-out pages. A local mock provider stands in for end-to-end tests.
- The repository already contains an unrelated Android project (Audacity port) at its root. The browser lives in its own top-level directory and must not disturb it.
- Provider web apps change without notice: every provider-specific string (URLs, cookie names, selectors, prefill parameters) lives in one data file, and every provider-specific step has a generic fallback.

## 4. Approaches considered

### 4.1 Engine and shell

| Approach | Verdict |
|---|---|
| Fork Chromium (like Brave, Comet, Atlas) | Rejected: a checkout plus build needs >100 GB and many hours per platform, cannot be done or verified here, and would leave the user with something they cannot rebuild. |
| Ship stock Chromium with a bundled extension | Rejected: it is an extension, not an own browser; floating windows, the login gate and history handling would all be constrained by extension APIs; `--load-extension` is gone from branded Chrome. |
| **Electron** (Chromium + Node) on desktop | **Chosen.** Real Chromium engine, full control of the window chrome, runs and is testable here, one codebase for Windows/macOS/Linux. |
| **Android System WebView** on Android | **Chosen.** The only genuinely Chromium engine available to an app without compiling Chromium ⟨R⟩. |

### 4.2 How the agents are integrated

| Approach | Verdict |
|---|---|
| Provider APIs with API keys (tool-calling agents) | Rejected for this version: it is not "login", costs the user per token, and is a different product. The handoff design leaves room to add it later. |
| Reuse first-party OAuth / subscription tokens in our own client | Rejected: against the providers' terms and an anti-goal. |
| **Official web apps in floating surfaces, signed in by the user** | **Chosen.** Matches "must log in to use", uses the user's existing plans, and the browser only pre-fills the composer the way Firefox, Opera and Vivaldi do ⟨R⟩. |

### 4.3 Floating UI

| Approach | Verdict |
|---|---|
| Docked side panel (what most AI browsers do) | Rejected: the user asked for a separate floating UI. |
| Floating panels drawn inside the browser window | Kept for Android (an in-app draggable panel is the natural form on a phone). Rejected on desktop because the panel could not leave the browser window. |
| **Real floating OS windows on desktop** (one per agent, plus a floating dock) | **Chosen for desktop.** They can sit anywhere on any monitor, stay above the browser, and can be pinned above all apps. |

### 4.4 Sessions

Agents **share the main browsing profile** (one cookie jar). Signing in to a provider in a normal tab also unlocks its agent and the other way round; this is how Opera/Edge/Firefox side panels behave and it is the only model Android's WebView supports everywhere. Isolated per-agent profiles were rejected (double sign-in, and not available on older WebViews). Private windows/tabs use a separate throw-away profile and never talk to agents.

### 4.5 Storage

History lives in SQLite on both platforms (desktop: Node's built-in `node:sqlite` in the Electron main process ⟨R⟩; Android: `SQLiteOpenHelper`) with one shared schema. Text search uses `LIKE` over a lower-cased `title + url` column instead of FTS: it behaves identically on both SQLite builds and finds 2-character Korean substrings, which FTS5 trigram cannot ⟨R⟩. Settings, bookmarks, session and downloads are JSON files on desktop and DataStore/JSON on Android.

## 5. Product behaviour (both platforms)

### 5.1 Agents and the login gate

Three agents ship enabled: `claude` (Claude, Anthropic), `gemini` (Gemini, Google), `chatgpt` (ChatGPT, OpenAI). Everything provider-specific comes from `shared/providers.json` (§6.1).

Each agent is always in exactly one state:

| State | Meaning | What the agent surface shows |
|---|---|---|
| `signedOut` | no valid session | **Lock screen**: provider name, "로그인이 필요합니다", a sign-in button, a "일반 탭에서 로그인" fallback, and a line saying the browser never sees the password. The provider site is not loaded at all. |
| `signingIn` | the user pressed sign-in | The provider's own sign-in pages (and any identity-provider pages or popups they open). A slim bar above shows the host and a cancel button. |
| `signedIn` | session present and the app loaded | The provider's chat app, plus the quick-action bar. |

Rules:

- **Session detection** uses only (a) presence of the provider's session cookie(s) in the profile's cookie store and (b) the URL the agent surface ends up on. `signedIn` requires the cookie rule to hold **and** the surface not having been redirected to an authentication URL when the app URL was loaded.
- **The gate**: while the state is not `signedIn`, the provider's *chat app* is never shown, even if the site allows anonymous chatting. During `signingIn` the surface is visible only while its URL is not an app URL; if it reaches the app without a session, the surface is hidden again and the lock screen explains that sign-in did not complete.
- Every entry point (dock, toolbar buttons, address-bar `@agent`, quick actions, context menus, "ask all", history analysis) checks the state. A locked agent shows a lock badge; choosing it opens its lock screen and remembers the pending request, which runs automatically once sign-in completes.
- **Sign-out** ("로그아웃" in the agent menu) loads the provider's own sign-out URL when one is known ⟨R⟩, otherwise opens the provider's account page; it never deletes unrelated cookies silently.
- Links that leave the provider (citations in answers) open in a normal browser tab, not inside the agent surface.
- If a provider refuses sign-in inside the app, the lock screen offers "일반 탭에서 로그인" (same profile, so the agent unlocks as soon as it succeeds) and says so honestly. No evasion.

### 5.2 Floating UI

- **Dock**: a small floating pill with one round button per agent (neutral monogram, accent ring, state badge: lock / signed-in dot / busy). Click toggles that agent's surface; the dock can be dragged anywhere, remembers its place, and can be hidden (a toolbar button and a shortcut bring it back). Its menu offers "모두에게 묻기", "기록 분석", "설정".
- **Agent surface**: header (drag handle, agent name, state, new chat, pin / always-on-top on desktop, open in tab, menu, minimise to dock, close), a row of quick-action chips, then the provider area. It is movable and resizable, remembers size and position per agent, and never opens off-screen.
- Desktop: each agent surface and the dock are **separate frameless OS windows** owned by the browser window (they stay above it; "pin" keeps one above every app). Android: a draggable, resizable **in-app panel** and a draggable bubble; one panel visible at a time, with agent switching in its header.
- Private windows/tabs: the dock does not offer context actions; a notice explains that private pages are never handed to agents.

### 5.3 Context handoff (how text reaches an agent)

A handoff has an **origin**:

- `typed` — the text is exactly what the user typed (address bar, "ask all" box).
- `context` — the text contains page content, a selection, search results or history.

Plan (pure function `planHandoff`, §6.3):

1. Open the provider's new-chat URL (or keep the current chat when the user chose "현재 대화에 이어서"), wait for the composer (ordered selector list, bounded wait), focus it and insert the text through the engine's own text-insertion path (desktop: `webContents.insertText`, verified for textarea, contenteditable, ProseMirror, Tiptap and Quill up to 60,000 characters ⟨R⟩; Android: `document.execCommand('insertText')` in the page).
2. If the composer cannot be found or the text did not arrive: copy the prompt to the clipboard and tell the user to paste it.

URL prefill (`?q=`) is **not** used in this version: Firefox stopped using it for Claude and ChatGPT because long prompts plus cookies exceed Cloudflare's header limit (HTTP 431), and Gemini has no public prefill parameter ⟨R⟩. `providers.json` keeps a `prefill` field (null for all three) so it can be enabled per provider later.

Nothing is ever auto-submitted: the user sees the text in the composer and presses send (a hint says so). `origin` still decides whether the preview step runs.

The browser never reads the provider page beyond locating and focusing the composer and checking that the inserted text arrived.

### 5.4 Quick actions

| Id | Label (ko / en) | Input | Preview before insert |
|---|---|---|---|
| `summarize_page` | 페이지 요약 / Summarize page | extracted page text | per setting (default on) |
| `ask_page` | 페이지에 질문 / Ask about page | user question + page text | per setting |
| `explain_selection` | 선택 영역 설명 / Explain selection | selected text | per setting (default off) |
| `translate_selection` | 선택 영역 번역 / Translate selection | selected text (or page) | per setting |
| `search` | AI 검색 / Search with AI | typed query | none (typed) |
| `search_page` | 이 검색을 AI로 / Ask AI about this search | query (+ results page text if ticked) | per setting |
| `history_*` | 기록 분석 / Analyze history | history digest | **always** |
| `ask_all` | 모두에게 묻기 / Ask all | typed question | none (typed) |

The preview is our own dialog inside the agent surface: source line (page title and host, or history range), character count, the exact text in an editable box, "넣기" and "취소". It is the consent step.

Page extraction (`shared/inject/extract-page.js`, same file on both platforms) returns title, URL, language, selection and readable text. It never reads form control values, password fields, hidden elements, cookies or storage, and is capped in size.

### 5.5 Search integration

- Address bar input is classified (pure `classifyInput`): URL, web search, agent question, history search.
- Agent keywords: `@claude`, `@gemini`, `@chatgpt` (alias `@gpt`), `@all`, Korean aliases `@클로드`, `@제미나이`, `@챗지피티` (alias `@지피티`), `@모두`; `@history` / `@기록` searches local history; a leading `?` forces a web search.
- The suggestion list always contains, after the default action: matching history and bookmarks, optional search-engine suggestions (setting, **off by default** — it sends keystrokes to the engine), and one "…에게 묻기" row per agent (locked agents show the lock and lead to sign-in).
- On a recognised search-results page the dock and agent header offer "이 검색을 AI로" with the extracted query.
- Default search engines: Google, Naver, Daum, Bing, DuckDuckGo ⟨R⟩ (default Google; changeable).

### 5.6 History and history analysis

- Recorded for normal (non-private) http/https navigations: URL, title, time, transition type, referring visit, dwell time, recognised search query. Internal pages, private browsing and failed loads are not recorded. A setting turns recording off.
- History page: grouped by day, text search, delete entry / range / everything.
- **Analysis** (pure `computeHistoryStats`, §6.3): totals, top sites by visits and by time, hour-of-day and weekday patterns, sessions, searches (top queries and terms), new versus returning sites, a bounded list of notable pages.
- **Privacy filter** before anything is rendered into a prompt: sensitive categories are dropped by default (banking and payment, health, adult, authentication / SSO, webmail, local network, files, internal pages — list in `shared/privacy-rules.json` ⟨R⟩, user-extendable); URLs lose query strings and fragments except recognised search queries; credentials and token-like path segments are removed.
- **Flow**: choose range (today / 7 / 30 / 90 days / all), analysis type (habits, topics, find a page, time report, own question), what to include (search queries, page titles, addresses: sites only or full paths) → live preview with counts ("1,204 visits, 37 excluded as sensitive, 6,412 characters") → choose agent(s) → insert. Always previewed, never auto-submitted.

### 5.7 Privacy and safety rules (binding)

1. Nothing derived from pages, selections, searches or history leaves the device except by an explicit user action that names the target agent; history always passes the preview.
2. Extracted page text is untrusted. Prompts wrap it in a delimited block preceded by a fixed line telling the model to treat it as data and ignore instructions inside it; the delimiter is escaped inside the content.
3. Never included in any prompt: cookies, storage, form values, password fields, private-browsing data, full URLs with query strings (except recognised search queries).
4. Web content (tabs and provider pages) runs sandboxed with no access to app APIs. Internal pages are a separate privileged origin; every privileged call is checked against the caller's origin.
5. Page-initiated navigation to internal pages, `javascript:` typed into the address bar, and external-protocol launches without confirmation are blocked.
6. Permissions (camera, microphone, location, notifications, clipboard read) are denied until the user allows them per site.
7. The browser identifies itself with an accurate Chromium-family User-Agent for its engine and does not advertise the embedding framework; it does not alter automation or integrity signals ⟨R⟩.
8. Provider names appear as plain text with neutral monograms; an "about" notice states that Trio is not affiliated with Anthropic, Google or OpenAI.

### 5.8 Browser basics

Tabs (new, close, reopen closed, duplicate, reorder, audio indicator and mute), address bar with security indicator, back / forward / reload / stop, new-tab page (search box, top sites, agent shortcuts), bookmarks (star, bar, manager), history, downloads (list, open, show in folder, cancel), find-in-page, zoom, print, fullscreen, private window, page / link / image / selection context menus (including "…에게 묻기"), popups (sign-in popups work), error pages, permissions prompt, session restore, settings, keyboard shortcuts following Chrome conventions, light / dark / system theme, Korean / English UI (follows the system, switchable).

## 6. Shared core

Directory `trio-browser/shared/` is consumed by both platforms (desktop reads it at runtime; Android packs it into assets).

### 6.1 Data files

- `providers.json` — per agent: `id`, `name`, `vendor`, `monogram`, `accent`, `keywords[]`, `appUrl` (new chat), `loginUrl`, `logoutUrl?`, `appHosts[]`, `authHosts[]`, `authPathPrefixes[]` (paths on app hosts that are authentication pages), `sessionCookies` (`anyOf` / `allOf` of `{domain, name | namePrefix}`), `prefill` (`template`, `maxEncodedChars`, `autoSubmits`), `composerSelectors[]` ⟨R⟩.
- `search-engines.json` — selectable engines (`id`, `name`, `searchTemplate`, `suggestTemplate?`) and recognition rules for query extraction across more engines ⟨R⟩.
- `privacy-rules.json` — sensitive host / URL patterns by category, token-segment heuristics ⟨R⟩.
- `site-suffixes.json` — IANA TLD list (dated) plus second-level and hosting suffixes used for URL classification and for grouping hosts into sites.
- `prompts.ko.json`, `prompts.en.json` — prompt templates per action.
- `inject/extract-page.js` (page text), `inject/find-composer.js` (locate, focus and measure the composer).

### 6.2 Test vectors

`shared/test-vectors/*.json`: `classify-input`, `search-engines`, `privacy`, `history-stats`, `history-digest`, `prompts`, `login-state`, `handoff`. Both implementations must pass all of them. In addition the desktop (reference) implementation generates **golden files** from a seeded corpus of generated inputs; the Android implementation must reproduce them exactly.

### 6.3 Pure functions (same names and semantics in JS and Kotlin)

| Function | Result |
|---|---|
| `classifyInput(text)` | `empty` \| `url(url)` \| `search(query)` \| `agent(agentId \| all, query)` \| `history(query)` |
| `buildSearchUrl(engineId, query)` / `extractSearch(url)` | URL / `{engineId, query}` or none |
| `siteKey(host)` | registrable-site key used for grouping |
| `sanitizeUrl(url)` / `sensitiveCategory(url)` | cleaned URL or none / category or none |
| `computeHistoryStats(visits, options)` | stats object (definitions pinned by vectors ⟨R⟩) |
| `renderHistoryDigest(stats, options)` | `{text, truncated}` |
| `buildPrompt(actionId, input, options)` | `{text, truncated}` |
| `classifyProviderUrl(provider, url)` | `app` \| `auth` \| `external` |
| `hasSession(provider, cookies)` | boolean |
| `deriveAgentState(inputs)` / `gateView(state, urlKind)` | state / `lock` \| `provider` |
| `planHandoff(provider, text, origin, target)` | `{method: url \| insert \| clipboard, url?}` |

## 7. Desktop architecture (Electron)

### 7.1 Stack and layout

Electron (latest stable ⟨R⟩). Plain JavaScript: CommonJS in the main process and preloads, ES modules in renderers. Types by JSDoc checked with `tsc --noEmit`. Unit tests with `node:test`; end-to-end tests with Playwright's Electron driver under Xvfb. No runtime npm dependencies.

```
trio-browser/
  shared/                     data files, injected scripts, test vectors (§6)
  desktop/
    package.json
    src/core/                 pure logic (§6.3), no Electron imports
    src/main/                 app lifecycle, windows, tabs, agents, stores, IPC, internal scheme
    src/preload/              one self-contained file per surface (sandbox-compatible)
    src/renderer/chrome/      browser window UI (tab strip, toolbar, address bar, bars)
    src/renderer/agent/       agent window UI (header, lock screen, preview dialog)
    src/renderer/dock/        dock UI
    src/renderer/pages/       newtab, history, bookmarks, downloads, settings, about
    src/renderer/common/      design tokens, icons, i18n, small DOM helpers
    test/unit/  test/e2e/  test/fixtures/ (static pages, mock provider server)
  android/                    (§8)
```

### 7.2 Windows and views

- **Browser window**: `BaseWindow` whose content holds, bottom to top: the active tab's `WebContentsView`, then the **chrome view** (`WebContentsView` with our UI). The chrome view normally covers only the top strip; while the address-bar dropdown is open it expands over the page with a transparent background ⟨R⟩ and shrinks back afterwards. Find bar, permission prompt and download notice are rows that push the page down, so they need no overlay. Menus are native (`Menu.popup`).
- **Tabs**: one `WebContentsView` per tab in the profile session; restored background tabs load lazily.
- **Agent window** (one per agent, created lazily): frameless, resizable child window of the browser window that last had focus; its own UI page fills the window and the provider `WebContentsView` sits below the header while the gate allows it. The lock screen, sign-in bar and preview dialog are states of that UI page.
- **Dock window**: small frameless child window.
- **Private window**: a browser window on an in-memory session; no history, no agents.

### 7.3 Internal scheme

`trio://` is a privileged standard scheme served by `protocol.handle` from `src/renderer`. Hosts: `chrome`, `agent`, `dock` (only ever loaded in their own dedicated web contents) and `newtab`, `history`, `bookmarks`, `downloads`, `settings`, `about` (loadable in tabs). Strict CSP (`default-src 'self'`; no inline script). Web pages cannot navigate to or frame `trio://`.

### 7.4 IPC

`ipcRenderer.invoke` for requests, `webContents.send` for state pushes. One contract module lists every channel with the surfaces allowed to call it and a payload validator; the dispatcher rejects calls whose sender is not the top frame of an allowed `trio://` origin in a web contents registered for that surface. The chrome UI is a pure function of a state snapshot pushed by the main process.

### 7.5 Storage (per profile directory)

`history.sqlite` (schema ⟨R⟩), `settings.json`, `bookmarks.json`, `session.json`, `downloads.json`, `agents.json` (window bounds, dock position, pins). JSON files are written atomically (temp file + rename) and validated on load with defaults for anything missing or malformed.

### 7.6 Security configuration

Tabs and provider views: `sandbox: true`, `contextIsolation: true`, `nodeIntegration: false`, `webviewTag: false`, no remote module; the tab preload exposes an API only when the document's origin is an internal page. `setWindowOpenHandler` routes new windows to tabs (real popups only for sign-in flows); navigation guards as in §5.7; permission request and check handlers backed by per-site settings; certificate errors are never bypassed.

### 7.7 Tests

- Unit: every `src/core` module against the shared vectors; stores (settings, history, bookmarks, session) against temporary directories.
- End-to-end (mock provider on loopback hosts, fixture pages, temporary profile): startup and navigation; address-bar classification and suggestions; tabs and session restore; history record / search / delete; bookmarks; downloads; find and zoom; popups; permissions; private window; **agent lock → sign-in → unlock → sign-out**; quick actions with preview; `@agent` from the address bar with a locked and an unlocked agent; history analysis including sensitive-site exclusion; ask all; dock and agent window show / hide / move / resize / persistence.
- Manual smoke script: load the three real sign-in pages in agent windows and save screenshots (signed-out only).

### 7.8 Packaging

`electron-builder`: Linux AppImage and tar.gz built and smoke-started here; Windows and macOS targets configured, built where the toolchain allows ⟨R⟩ and otherwise documented.

## 8. Android architecture

Standalone Gradle project in `trio-browser/android/` (its own wrapper and version catalog, independent from the Audacity project at the repository root).

- `:core` — pure Kotlin/JVM library implementing §6.3, tested against the shared vectors and the golden files. No Android dependencies.
- `:app` — Kotlin + Jetpack Compose, single activity.
  - One `WebView` per tab, kept in a retained holder; `WebViewClient` / `WebChromeClient` wired for navigation state, history recording, titles, favicons, progress, popups, file chooser, permissions, fullscreen video, downloads, SSL errors and renderer crashes ⟨R⟩.
  - UI: address bar with suggestions, tab switcher, menu, bottom navigation, new-tab page, history / bookmarks / downloads / settings screens.
  - **Floating UI**: a draggable bubble (dock) that expands to the three agents, and a draggable, resizable in-app panel hosting the provider `WebView`, with the same header, quick actions, lock screen and preview dialog as desktop.
  - Login gate through `CookieManager` and URL classification (same pure functions); handoff through URL prefill or composer insertion ⟨R⟩ with the clipboard fallback.
  - History in SQLite with the shared schema; settings in DataStore.
  - User-Agent without the WebView markers and without the `X-Requested-With` header where the platform allows ⟨R⟩.
  - Private tabs only where the WebView multi-profile API is available ⟨R⟩; otherwise the feature is hidden rather than faked.
- Tests: JVM unit tests for `:core`; Robolectric tests for view-models, stores and Compose screens; an emulator smoke test only if the software-emulation probe proves workable ⟨R⟩.
- Output: debug-signed release APKs (arm64-v8a and universal).

## 9. Verification matrix

| Area | Desktop | Android |
|---|---|---|
| Pure logic (§6.3) | unit tests on shared vectors | same vectors + golden parity |
| Stores | unit tests | Robolectric |
| Browser UI flows | Playwright-Electron under Xvfb | Robolectric / Compose tests; emulator smoke if possible ⟨R⟩ |
| Agent gate, handoff, preview | end-to-end against the mock provider | view-model tests against fakes; emulator smoke if possible |
| Real provider sign-in and prefill | **not verifiable** (no accounts); signed-out pages smoke-loaded | **not verifiable** |
| Windows / macOS builds | **not verifiable** here | n/a |

## 10. Open risks

1. A provider may refuse sign-in inside an embedded Chromium (Google most likely) ⟨R⟩ — mitigated by the "sign in in a normal tab" path and honest messaging; not by evasion.
2. Provider DOM or URL changes break prefill — mitigated by the data file, ordered fallbacks and the clipboard fallback.
3. Cookie-based detection may misjudge (expired session with a cookie present) — the URL signal corrects it after the first load.
4. Floating child windows behave differently per OS / window system (Wayland positioning, macOS child-window movement) ⟨R⟩.
5. Android behaviour on real devices is unverified here.

## 11. Where the name lives

`trio-browser/` directory, `desktop/package.json` (`name`, `productName`, `appId`), the `trio://` scheme constant, UI strings (`common/i18n/*.json`, Android `strings.xml`), Android `applicationId` / namespace `io.github.sakkijarvenpolkka.trio`.

## Appendix A — Research facts (2026-11-26)

Gathered by research agents (live checks in this container); confidence noted where it matters.

**Electron (desktop)** — verified by spikes here:
- `electron@45.5.0` (Chromium 156.0.8078.109, Node 24.22.3); installs in ~4 s; runs under `xvfb-run` with `--no-sandbox` as root.
- `node:sqlite` works in the Electron main process (`DatabaseSync`, SQLite 3.54.1, FTS5 with unicode61 and trigram). Plain Node prints an `ExperimentalWarning`.
- `BaseWindow` + `WebContentsView` with a `persist:` partition works; a `WebContentsView` above another with `setBackgroundColor('#00000000')` (set again after load) is transparent where its page is transparent, and it receives all clicks inside its bounds, including transparent areas.
- `webContents.insertText` inserted 5,000 and 60,000 characters of mixed Korean/English multi-line text exactly into textarea, contenteditable, ProseMirror 1.44, Tiptap 3.35 and Quill 2.0 (60k in ~0.5 s); clipboard paste collapses blank lines in ProseMirror; a synthetic `paste` event does nothing.
- `session.cookies` sees HttpOnly cookies and emits `changed`.
- Playwright 1.63.0 `_electron.launch({ args: ['--no-sandbox', dir] })` drives it under Xvfb.
- `electron-builder 26.18` builds `--linux dir` here; the default user agent contains `<AppName>/<version>` and `Electron/45.5.0`, which the app removes (`app.userAgentFallback`) so sites see a normal Chrome 156 user agent.

**Providers** — from live HEAD/page loads (signed out) and Firefox's source (`browser/components/genai`):
- Claude: app `https://claude.ai/new`; signed out it redirects to `/logout?involuntary=1…` then `/login?…`; `frame-ancestors 'self'` (no iframes, fine as a top-level view). Session cookie `sessionKey` on `claude.ai` (named in claude.ai's own scripts together with `lastActiveOrg`; medium-high confidence).
- ChatGPT: app `https://chatgpt.com/`; signed-out users can chat anonymously on the same URL, so the gate relies on the session cookie `__Secure-next-auth.session-token` (possibly chunked `.0`, `.1`) on `chatgpt.com` (medium confidence); sign-in at `https://chatgpt.com/auth/login` → `auth.openai.com`. Behind Cloudflare; long `?q=` URLs hit HTTP 431.
- Gemini: app `https://gemini.google.com/app`; sign-in on `accounts.google.com` (`ServiceLogin?continue=…`); Firefox passes prompts through a private `X-Firefox-Gemini` header that is not available to others. Google session cookies `SID` / `__Secure-1PSID` on `google.com` (high confidence that one exists when signed in).
- Composer selectors used by Firefox: `#prompt-textarea, [contenteditable], [role="textbox"]`; ChatGPT signed out shows `textarea[name="prompt"]`.
- Firefox no longer uses `?q=` for ChatGPT and Claude (bug 2038353) and inserts into the composer instead.

**Embedded sign-in**: Google blocks sign-in from browsers it considers embedded ("This browser or app may not be secure"); reports from Electron apps in 2025–2026 are mixed and depend on the user agent and other signals. Trio presents an accurate Chrome user agent, does not touch automation or integrity signals, and offers "sign in in a normal tab" plus honest messaging when a provider refuses.

**Android** — verified by a spike here: Android SDK in `/opt/android-sdk` (platform 36/37, build-tools 36/37); a Compose + WebView app builds with AGP 9.5.3, Kotlin 2.4.21, Compose BOM 2026.11.01, androidx.webkit 1.18.0, Robolectric 4.18.1; an API 30 x86_64 AOSP image boots in the software emulator (no KVM) in ~456 s (`recon_api30` AVD).
