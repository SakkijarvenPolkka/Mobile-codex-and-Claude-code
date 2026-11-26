# Trio desktop — UI specification

Companion to `2026-11-26-trio-browser-design.md`. It pins what each renderer surface shows and does.
The data each surface receives and the calls it can make are defined in
`trio-browser/desktop/src/types/contract.d.ts` (**the contract**); names below refer to it.

## 1. Rules for every surface

- Files live under `trio-browser/desktop/src/renderer/<surface>/` and are served unchanged (no bundler):
  `trio://chrome/`, `trio://agent/`, `trio://dock/` and one host per internal page (`trio://newtab/`, `trio://history/`, …).
  In every host the path `/common/…` maps to `src/renderer/common/…`. A surface's own files are referenced relatively (`./main.js`). Shared files are referenced from HTML as `/common/<file>` and **imported from JavaScript by the relative path that also resolves on disk** (`../common/dom.js` from `chrome/`, `../../common/dom.js` from `pages/<id>/`): the type checker follows the disk path, and at run time `..` above the URL root is clamped, so `trio://history/../../common/dom.js` is `trio://history/common/dom.js`.
- Plain ES modules (`<script type="module" src="./main.js">`), no framework, no runtime dependencies.
- Content-Security-Policy is `default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'`:
  no inline `<script>`, no inline `style=""` attributes, no `eval`. Setting `element.style.x` from script is fine.
- Boot: `const boot = await window.trio.<surface>.init()`, apply `boot.theme` / `boot.locale` to `<html data-theme lang>`, keep `boot.strings`, render `boot.state`, then subscribe with `onState` (and `onBoot` for theme or language changes). The UI is a pure function of the last state; it sends intents and waits for the next state instead of mutating its own copy.
- All visible text comes from `boot.strings` via `t(key, params)`. Korean strings are phrased so that no particle with a consonant/vowel variant (을/를, 이/가, 은/는, 와/과, 으로/로) directly follows a placeholder. There is one string file per locale for the whole app, `src/renderer/common/i18n/ko.json` and `en.json` (identical keys, dotted names grouped by surface: `chrome.*`, `agent.*`, …); the main process uses the same files for native menus. Korean is the primary copy; English must read naturally.
- Favicons arrive as `data:` URLs or `null` (show the globe icon). Never load remote images.
- Every interactive element is a real `<button>`, `<a>` or form control with an accessible name, visible focus ring, and a `title` tooltip where the control is icon-only. Honour `prefers-reduced-motion`.
- Elements named below with a test id carry `data-testid="<id>"`; lists also carry the stated `data-*` key. End-to-end tests depend on these exact names.
- Light and dark themes via CSS custom properties from `/common/tokens.css`; private browser windows add `data-private` on `<html>` and use the private palette.

### Visual direction

Calm, compact, modern. Neutral greys, one brand accent (indigo-violet), three agent accents taken from `AgentSummary.accent`. System UI font stack with Korean fallbacks (`system-ui, -apple-system, "Segoe UI", "Pretendard", "Apple SD Gothic Neo", "Malgun Gothic", "Noto Sans KR", sans-serif`); 13px base in window chrome, 14px in pages. Radii: 8px controls, 12px cards, fully rounded address bar and chips. Icons: one original set of 20px line icons (1.6px stroke, round caps) in `/common/icons.js`. No provider logos anywhere: an agent is drawn as a circle with its monogram text, an accent ring, and a state badge.

Agent state badge (same everywhere an agent button appears): `signedOut` → small lock; `signingIn` → small spinner; `signedIn` → small green dot; `busy` → the ring pulses.

## 2. Browser window (`trio://chrome/`, `ChromeApi`, `ChromeState`)

The chrome view normally covers only its own bars; while the address-bar dropdown is open it covers the whole window with a transparent `<body>`, so everything outside the bars and the dropdown must be transparent and a pointer press there closes the dropdown. Report the bars' total height with `setChromeHeight` whenever it changes (use a `ResizeObserver`), and call `setOverlay(true|false)` when the dropdown opens or closes.

Bars, top to bottom:

1. **Tab strip** (38px) — test id `tab-strip`.
   - One `tab` per `TabState` with `data-tab-id`, `aria-selected` on the active one: favicon (spinner while `loading`, warning icon when `crashed`), title (`tab-title`), audio button when `audible || muted` (`tab-audio`, toggles `setTabMuted`), close button (`tab-close`).
   - Click activates; middle-click closes; context menu calls `showTabMenu(tabId, point)`; dragging a tab along the strip reorders it (`moveTab`).
   - `new-tab` button after the last tab. Double-click on empty strip space opens a new tab.
   - Tabs share the width (max 220px, min 56px); below the minimum the strip scrolls horizontally and the mouse wheel scrolls it.
   - Private window: a `private-badge` ("사생활 보호") at the left edge.
2. **Toolbar** (46px) — test id `toolbar`.
   - `nav-back`, `nav-forward` (disabled per `canGoBack` / `canGoForward`), `nav-reload` which becomes stop while `loading`.
   - **Address bar** `omnibox`: security icon (`security-icon`, by `TabState.security`, tooltip explains it), text input `address-input`, then inside the bar on the right: zoom label when `zoomPercent !== 100` (`zoom-label`), `ask-search` chip ("AI로 검색") when the active tab has `search`, the window is not private and the input is not focused (calls `askAboutSearch()`), and the bookmark star `bookmark-star` (filled when `bookmarked`, hidden on internal pages).
   - Agent buttons: one `agent-button` (`data-agent-id`, `aria-pressed` = `open`) per enabled agent; click calls `toggleAgent`. Tooltip: "{name} 열기" or "{name} — 로그인 필요". In a private window they are replaced by one disabled `agents-off` icon whose tooltip says agents do not receive private pages.
   - `dock-toggle` (`aria-pressed` = `dockVisible`), `downloads-button` (only when `downloads.activeCount > 0 || downloads.unseen`; shows a progress ring; dot when `unseen`), `app-menu` (calls `showAppMenu` with the point under the button).
3. **Bookmarks bar** (30px, only when `bookmarksBar.visible`) — `bookmarks-bar` with `bookmark-item` (`data-bookmark-id`) buttons: favicon + title (max 160px, ellipsis). Click opens, Ctrl/Cmd-click or middle-click opens in a new tab, context menu calls `showBookmarkMenu`. Overflowing items are clipped.
4. **Find bar** (only while `find !== null`) — `find-bar`: input `find-input`, count `find-count` ("3/12", or "결과 없음"), `find-prev`, `find-next`, `find-close`. Typing calls `find({action:'search', text})`; Enter next, Shift+Enter previous, Escape closes. `ChromeUiCommand` `open-find` focuses and selects the input.
5. **Permission bar** (only while `permissionPrompt !== null`) — `permission-bar`: "{origin}에서 {permission} 사용 권한을 요청합니다", checkbox `permission-remember` ("이 사이트에 대해 기억", checked by default), `permission-allow`, `permission-deny`.
6. **Notice bar** (only while `notice !== null`) — `notice-bar`: text, optional action button `notice-action`, `notice-dismiss`.

### Address bar behaviour

- Not focused: shows the active tab's `displayUrl` (scheme and `www.` visually de-emphasised). Empty with placeholder "검색어 또는 주소 입력 · @claude @gemini @chatgpt" on the new-tab page.
- Focus selects everything. Typing calls `suggest(text)` (debounced ~60ms; ignore out-of-order answers) and shows the dropdown `omnibox-dropdown` with one `suggestion` row per result (`data-kind`, `data-index`): icon by kind, title, subtitle; agent rows show the agent's monogram circle and a lock when `locked`.
- The first row is the default action and is highlighted initially. Up/Down move the highlight and put the row's `fill` text into the input; Enter runs the highlighted row with `acceptSuggestion` (or `navigate(text)` when the dropdown is closed or the text no longer matches); Alt+Enter opens in a new tab; Escape closes the dropdown, a second Escape restores `displayUrl` and blurs. Clicking a row accepts it. Blur closes the dropdown.
- `ChromeUiCommand` `focus-address-bar` focuses the input, optionally replacing its text (used for "모두에게 묻기", which prefills `@all `).
- Switching tabs or receiving a new `displayUrl` while the input is not being edited updates the text; text being edited is never overwritten.

## 3. Agent window (`trio://agent/?agent=<id>`, `AgentApi`, `AgentWindowState`)

A frameless floating window. Our page fills the window; in `signin` and `app` mode the provider's page is drawn by the main process over everything below our header, so report the header's height with `setHeaderHeight` whenever it changes. The header is the drag handle (`-webkit-app-region: drag`; controls inside it are `no-drag`). The window has a 1px border and the agent's accent as a thin top line.

**Header** (40px, all modes) — `agent-header`: monogram circle, name, state text (`agent-state-text`: "로그인됨" / "로그인 필요" / "로그인 중…" / "불러오는 중…"), then buttons: `agent-new-chat` (app mode only), `agent-pin` (`aria-pressed` = `pinned`, "항상 위에 고정"), `agent-open-tab` ("탭에서 열기", app mode only), `agent-menu` (`showMenu`), `agent-minimize`, `agent-close`.

**Mode `app`** — below the header:
- Quick-action row `quick-actions` (36px, chips scroll horizontally): one `quick-action` chip (`data-action-id`) per `QuickActionDef` in the order given. Chips for page actions are disabled when `context.private` or `!context.pageAvailable`; the `search_page` chip appears only when `context.searchQuery` is set and reads "‘{query}’ AI로 검색" (query shortened to 24 characters). A chip whose action `needsQuestion` swaps the row for an inline form `question-form`: input `question-input` (placeholder "{name}에게 물어볼 내용"), `question-send`, `question-cancel`; Enter sends, Escape cancels.
- When `context.private`: a one-line note `private-note` "사생활 보호 창의 페이지는 에이전트에 전달되지 않습니다".
- Notice row `agent-notice` when `notice !== null` (text + dismiss).

**Mode `signin`** — below the header a slim bar `signin-bar`: lock icon, "{host}에서 로그인 중", `signin-in-tab` ("일반 탭에서 로그인"), `signin-cancel` ("취소").

**Mode `lock`** — the whole window below the header is the lock screen `lock-screen`:
- large monogram circle with a lock badge;
- title: "{name} 로그인이 필요합니다";
- body by `lockReason`: `signedOut` → "{vendor} 계정으로 로그인하면 {name} 에이전트를 사용할 수 있습니다."; `signInIncomplete` → "로그인이 완료되지 않았습니다. 다시 시도해 주세요."; `signInRefused` → "이 창에서는 로그인이 거부되었습니다. ‘일반 탭에서 로그인’을 사용해 보세요.";
- `pending-label` when `pendingLabel` is set: "로그인하면 ‘{label}’ 작업을 이어서 실행합니다.";
- primary button `lock-signin` "{name} 로그인"; secondary `lock-signin-tab` "일반 탭에서 로그인";
- footnote: "로그인은 {vendor}의 공식 페이지에서 이루어지며, Trio는 비밀번호를 보거나 저장하지 않습니다."

**Mode `preview`** — the window below the header is the dialog `preview` (driven by the last `onPreview` payload):
- title "{name}에게 보낼 내용"; source line `preview-source`; character count `preview-count` ("6,412자", plus "· 길어서 일부만 포함됨" when `truncated`);
- editable textarea `preview-text` filling the available height;
- radio group `preview-target`: "새 대화" (default) / "현재 대화에 이어서";
- checkbox `preview-skip` "이 작업은 다음부터 미리보기 없이 넣기";
- buttons `preview-confirm` "입력창에 넣기" (disabled when the text is empty) and `preview-cancel` "취소"; Escape cancels;
- footnote: "입력창에 넣은 뒤 직접 전송해야 {name}에게 전달됩니다."

**Mode `history`** — the window below the header is `history-analysis`:
- range segmented control `history-range` (오늘 / 7일 / 30일 / 90일 / 전체; default 7일);
- analysis type select `history-kind` (브라우징 습관 / 관심 주제 / 예전에 본 페이지 찾기 / 시간 사용 리포트 / 직접 질문);
- question input `history-question` (only for `find` and `custom`; required);
- include toggles: `history-include-queries` "검색어", `history-include-titles` "페이지 제목", and `history-include-urls` (사이트 이름만 / 경로까지);
- counts line `history-counts`: "방문 {visits}건 · 사이트 {sites}곳 · 검색 {searches}건 · 민감한 사이트 {excluded}건 제외";
- editable preview textarea `history-text` with its character count;
- checkbox `history-to-all` "로그인된 모든 에이전트에 넣기";
- buttons `history-send` "입력창에 넣기" and `history-close` "닫기".
- Any option change calls `previewHistory(options)` (debounced ~150ms, ignore out-of-order answers) and replaces the textarea content and counts.

## 4. Dock (`trio://dock/`, `DockApi`, `DockState`)

A small frameless window sized by `setSize` to exactly fit: a vertical pill with a 12px grip area on top (drag region, six-dot grip), one `dock-agent` button (`data-agent-id`, 40px circle, `aria-pressed` = `open`) per enabled agent with 8px gaps, and a `dock-menu` button at the bottom (`showMenu`). Tooltips as for the toolbar agent buttons. No text labels.

## 5. Internal pages (`PagesApi`)

All pages share a header-less centred layout (max width 880px), respond to `onChanged` by reloading their lists, and work in both themes.

- **`newtab`** — wordmark "Trio"; search box `newtab-search` (placeholder as the address bar; Enter calls `navigate`); three agent cards `agent-card` (`data-agent-id`): monogram, name, state text, button "열기" or "로그인" (both call `toggleAgent`); top-sites grid of up to 8 `top-site` tiles (favicon, title; plain links navigated with `navigate`). In a private window the cards are replaced by a short explanation of private browsing ("방문 기록이 저장되지 않으며 에이전트에 페이지가 전달되지 않습니다").
- **`history`** — search input `history-search`; buttons `history-analyze` ("AI로 분석", calls `analyzeHistory()`), `history-clear` ("기록 삭제…", opens a small in-page dialog with the range choices and a confirm button `history-clear-confirm`); the list `history-list` grouped under date headings (오늘 / 어제 / "2026년 11월 24일 화요일"), rows `history-row` (`data-visit-id`): checkbox, time (HH:MM), favicon, title (link, `navigate`), host; `history-delete-selected` appears when rows are ticked; more rows load when scrolled near the end (`beforeTime` paging, 100 per page); empty state text.
- **`bookmarks`** — add form (`bookmark-title`, `bookmark-url`, `bookmark-add`); list of `bookmark-row` (`data-bookmark-id`) with inline rename / edit URL, move up / down, delete; empty state.
- **`downloads`** — list of `download-row` (`data-download-id`): file name, source host, state text or progress bar with size, actions by state (열기, 폴더에서 보기, 일시중지 / 계속, 취소, 목록에서 지우기); `downloads-clear` ("목록 지우기"); "파일이 삭제됨" when `exists` is false; empty state.
- **`settings`** — sections with controls bound to `Settings` paths through `setSetting`:
  일반 (언어 `setting-language`, 테마 `setting-theme`, 시작 시 `setting-startup`, 북마크바 표시), 검색 (검색 엔진 `setting-search-engine`, 검색어 추천 with a note that keystrokes are sent to the search engine), 개인정보 (방문 기록 저장, 민감한 사이트 제외, 추가로 제외할 사이트 — one host per line, "인터넷 사용 기록 삭제…" with a `ClearDataRequest` dialog), 에이전트 (per agent: enabled toggle and state; 독 표시; 미리보기 toggles for page / selection / search with a fixed note that history is always previewed; 글자 수 한도 for page and history; 프롬프트 언어), 다운로드 (저장 위치 with `chooseDownloadDirectory`, 매번 위치 묻기), 사이트 권한 (table origin × permission with a reset button per row; empty state), 정보 (link to `trio://about`).
- **`about`** — app name and version, Electron / Chromium / Node versions, platform, and the notice: "Trio는 독립적으로 만든 브라우저이며 Anthropic, Google, OpenAI와 제휴하거나 승인을 받은 제품이 아닙니다. Claude, Gemini, ChatGPT는 각 회사의 상표입니다."
- **`error`** — reads `url`, `code`, `description` from its query string: title "페이지를 열 수 없습니다", the host, the error code, a `error-retry` button (`retry()`).

## 6. Tests

Renderer code is exercised through the real app: Playwright's Electron driver starts Trio with a temporary profile and a mock provider server, and the tests drive the surfaces by their test ids. Screenshots of every surface in light and dark are written to `trio-browser/desktop/test/e2e/__screenshots__/` (git-ignored) for visual review.
