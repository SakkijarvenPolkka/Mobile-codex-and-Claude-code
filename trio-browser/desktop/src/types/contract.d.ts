/**
 * Trio desktop — the single source of truth for everything that crosses a boundary:
 *   - domain data shared by the main process, the pure core and the renderers;
 *   - the `window.trio.*` APIs that each preload exposes to its renderer surface;
 *   - the state snapshots the main process pushes to each surface.
 *
 * Conventions
 *   - Every API method `m` of surface `s` maps to the IPC channel `trio:<s>:<m>` (ipcRenderer.invoke).
 *   - Every `onX` subscription maps to the push channel `trio:<s>:on:<x>` (webContents.send),
 *     e.g. ChromeApi.onState -> `trio:chrome:on:state`.
 *   - Methods that return `void` here still travel over invoke; the renderer ignores the result.
 *   - Renderers hold no authoritative state: they render the latest snapshot and send intents.
 *   - This file contains types only. It is checked with `tsc --noEmit`; nothing is emitted.
 */

// ---------------------------------------------------------------------------------------------
// Basics
// ---------------------------------------------------------------------------------------------

export type Locale = 'ko' | 'en';
export type ThemeName = 'light' | 'dark';
export type Platform = 'linux' | 'win32' | 'darwin';

/** Flat map of UI strings for the active locale: key -> text with `{name}` placeholders. */
export type Strings = Record<string, string>;

export interface Point {
  x: number;
  y: number;
}

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** What every surface receives from its `init()` call in addition to its own state. */
export interface SurfaceBoot {
  locale: Locale;
  theme: ThemeName;
  strings: Strings;
  platform: Platform;
}

// ---------------------------------------------------------------------------------------------
// Agents (shared data: trio-browser/shared/providers.json)
// ---------------------------------------------------------------------------------------------

export type AgentId = 'claude' | 'gemini' | 'chatgpt';
export type AgentTarget = AgentId | 'all';

/**
 * signedOut  no valid session; the provider's chat app must never be visible.
 * signingIn  the user asked to sign in; only authentication pages are visible.
 * signedIn   session cookie rule holds and the app surface loaded without bouncing to sign-in.
 */
export type AgentLoginState = 'signedOut' | 'signingIn' | 'signedIn';

export interface CookieRule {
  /** Cookie domain without a leading dot, matched exactly or as a parent domain. */
  domain: string;
  /** Exact cookie name. Exactly one of `name` / `namePrefix` is set. */
  name?: string;
  /** Cookie name prefix (for chunked cookies such as `token.0`, `token.1`). */
  namePrefix?: string;
}

export interface ProviderConfig {
  id: AgentId;
  name: string;
  vendor: string;
  /** One or two characters drawn inside the agent's round button (never a logo). */
  monogram: string;
  /** CSS colour used for the agent's accent ring. */
  accent: string;
  /** Address-bar keywords without the leading '@', lower-case, Latin and Korean. */
  keywords: string[];
  /** New-chat URL of the provider's web app. */
  appUrl: string;
  loginUrl: string;
  logoutUrl: string | null;
  /** Hosts whose pages are the chat app (unless the path matches authPathPrefixes). */
  appHosts: string[];
  /** Hosts that only serve authentication pages. */
  authHosts: string[];
  /** Path prefixes on app hosts that are authentication pages, e.g. "/login". */
  authPathPrefixes: string[];
  /** Signed-in when every `allOf` rule matches and (if present) at least one `anyOf` rule matches. */
  sessionCookies: { anyOf?: CookieRule[]; allOf?: CookieRule[] };
  /** URL prefill, or null when the provider has none. `{prompt}` is replaced by the encoded prompt. */
  prefill: { template: string; maxEncodedChars: number; autoSubmits: boolean } | null;
  /** Ordered CSS selectors for the chat composer. */
  composerSelectors: string[];
}

/** What UIs need to draw an agent button. */
export interface AgentSummary {
  id: AgentId;
  name: string;
  vendor: string;
  monogram: string;
  accent: string;
  /** User setting; disabled agents are not shown in the dock or toolbar. */
  enabled: boolean;
  state: AgentLoginState;
  /** Its floating window is currently visible. */
  open: boolean;
  /** A page load or a handoff is in progress. */
  busy: boolean;
}

export type ActionId =
  | 'ask' // typed question, nothing attached
  | 'search' // typed query, "search the web and answer"
  | 'search_page' // query of the results page in the active tab (optionally with its text)
  | 'summarize_page'
  | 'ask_page'
  | 'explain_selection'
  | 'translate_selection'
  | 'history_habits'
  | 'history_topics'
  | 'history_find'
  | 'history_time'
  | 'history_custom';

/** `typed`: exactly what the user typed. `context`: contains page, selection, search or history text. */
export type HandoffOrigin = 'typed' | 'context';
/** Where the text goes: a fresh chat or the chat currently open in the agent window. */
export type HandoffTarget = 'new' | 'current';

export type HandoffPlan =
  | { method: 'url'; url: string }
  | { method: 'insert'; url: string | null } // url null = keep the current chat
  | { method: 'clipboard' };

// ---------------------------------------------------------------------------------------------
// History, search, privacy (shared core)
// ---------------------------------------------------------------------------------------------

export type VisitTransition =
  | 'link'
  | 'typed'
  | 'search' // generated from the address bar as a web search
  | 'bookmark'
  | 'form'
  | 'reload'
  | 'redirect'
  | 'other';

export interface Visit {
  id: number;
  url: string;
  title: string;
  /** Milliseconds since the Unix epoch (UTC). */
  time: number;
  /** Foreground time spent on the page, or null when unknown. */
  durationMs: number | null;
  transition: VisitTransition;
  fromVisitId: number | null;
}

export interface SearchMatch {
  engineId: string;
  query: string;
}

export type HistoryRange = 'today' | '7d' | '30d' | '90d' | 'all';
export type HistoryAnalysisKind = 'habits' | 'topics' | 'find' | 'time' | 'custom';

export interface HistoryOptions {
  range: HistoryRange;
  kind: HistoryAnalysisKind;
  /** The user's own question; required for `find` and `custom`, ignored otherwise. */
  question: string;
  include: {
    queries: boolean;
    titles: boolean;
    /** `sites`: site names only. `paths`: sanitised URLs without query strings. */
    urls: 'sites' | 'paths';
  };
}

/** A row of the history page. */
export interface HistoryEntry {
  visitId: number;
  url: string;
  title: string;
  time: number;
  host: string;
  faviconUrl: string | null;
}

// ---------------------------------------------------------------------------------------------
// Browser window ("chrome") surface
// ---------------------------------------------------------------------------------------------

/** secure: https. insecure: http. internal: trio:// page. local: file / loopback. error: failed load. */
export type Security = 'secure' | 'insecure' | 'internal' | 'local' | 'error';

export interface TabState {
  id: number;
  title: string;
  /** The committed URL. */
  url: string;
  /** Text for the address bar when it is not being edited ('' on the new-tab page). */
  displayUrl: string;
  faviconUrl: string | null;
  loading: boolean;
  canGoBack: boolean;
  canGoForward: boolean;
  audible: boolean;
  muted: boolean;
  crashed: boolean;
  security: Security;
  /** 100 = default zoom. */
  zoomPercent: number;
  bookmarked: boolean;
  /** Set when the committed URL is a recognised search-results page. */
  search: { engineId: string; engineName: string; query: string } | null;
  /** True for trio:// pages (page context is unavailable to agents). */
  internal: boolean;
}

export interface BookmarkItem {
  id: string;
  title: string;
  url: string;
  faviconUrl: string | null;
}

export type PermissionKind =
  | 'camera'
  | 'microphone'
  | 'camera+microphone'
  | 'geolocation'
  | 'notifications'
  | 'clipboard-read'
  | 'screen'
  | 'midi'
  | 'other';

export interface PermissionPrompt {
  id: number;
  /** Requesting origin, e.g. "https://example.com". */
  origin: string;
  permission: PermissionKind;
}

export interface FindState {
  query: string;
  /** 1-based index of the active match, 0 when there is none. */
  current: number;
  total: number;
}

export interface Notice {
  id: number;
  kind: 'info' | 'success' | 'warning' | 'error';
  text: string;
  /** Optional single action button. */
  actionLabel: string | null;
}

export interface ChromeState {
  windowId: number;
  /** Private window: no history, no agents. */
  private: boolean;
  tabs: TabState[];
  activeTabId: number | null;
  agents: AgentSummary[];
  dockVisible: boolean;
  bookmarksBar: { visible: boolean; items: BookmarkItem[] };
  downloads: {
    activeCount: number;
    /** 0..1 over all active downloads, null when unknown or idle. */
    progress: number | null;
    /** A download finished since the downloads page was last opened. */
    unseen: boolean;
  };
  permissionPrompt: PermissionPrompt | null;
  /** Non-null while the find bar is open. */
  find: FindState | null;
  notice: Notice | null;
  fullscreen: boolean;
}

export type SuggestionAction =
  | { type: 'navigate'; url: string }
  | { type: 'search'; query: string }
  | { type: 'agent'; agentId: AgentTarget; query: string }
  | { type: 'history'; query: string };

export interface Suggestion {
  kind: 'url' | 'search' | 'history' | 'bookmark' | 'remote' | 'agent' | 'history-search';
  /** Primary line. */
  title: string;
  /** Secondary line: a URL, "Google 검색", "Claude에게 묻기", ... */
  subtitle: string;
  /** Text placed in the address bar while this row is highlighted. */
  fill: string;
  action: SuggestionAction;
  faviconUrl: string | null;
  /** Agent rows only. */
  agentId: AgentTarget | null;
  /** Agent rows only: the agent is signed out. */
  locked: boolean;
}

/** One-shot instructions from the main process to the chrome UI. */
export type ChromeUiCommand =
  | { type: 'focus-address-bar'; text?: string; selectAll?: boolean }
  | { type: 'open-find' }
  | { type: 'close-overlays' };

export interface ChromeApi {
  init(): Promise<SurfaceBoot & { state: ChromeState }>;
  onState(listener: (state: ChromeState) => void): void;
  onCommand(listener: (command: ChromeUiCommand) => void): void;
  onBoot(listener: (boot: SurfaceBoot) => void): void; // locale or theme changed

  // tabs
  newTab(options?: { url?: string }): void;
  closeTab(tabId: number): void;
  activateTab(tabId: number): void;
  moveTab(tabId: number, toIndex: number): void;
  setTabMuted(tabId: number, muted: boolean): void;
  showTabMenu(tabId: number, at: Point): void;

  // navigation
  /** Classifies the typed text and acts on it (navigate, search, ask an agent, search history). */
  navigate(input: string, options?: { newTab?: boolean }): void;
  acceptSuggestion(suggestion: Suggestion, options?: { newTab?: boolean }): void;
  back(): void;
  forward(): void;
  reload(options?: { hard?: boolean }): void;
  stop(): void;

  // address bar
  suggest(text: string): Promise<Suggestion[]>;
  /** The chrome view must cover the page (dropdown open) or only its own bars. */
  setOverlay(open: boolean): void;
  /** Height in CSS pixels of the bars the chrome UI currently shows. */
  setChromeHeight(px: number): void;

  // page tools
  find(request: { action: 'search' | 'next' | 'previous' | 'close'; text?: string }): void;
  toggleBookmark(): void;
  openBookmark(id: string, options?: { newTab?: boolean }): void;
  showBookmarkMenu(id: string, at: Point): void;
  respondPermission(id: number, decision: 'allow' | 'deny', remember: boolean): void;
  dismissNotice(id: number, runAction: boolean): void;

  // agents
  toggleAgent(agentId: AgentId): void;
  toggleDock(): void;
  /** "이 검색을 AI로": hand the active tab's search query to an agent (default: last used). */
  askAboutSearch(agentId?: AgentId): void;

  // menus and pages
  showAppMenu(at: Point): void;
  openDownloads(): void;
}

// ---------------------------------------------------------------------------------------------
// Agent window surface
// ---------------------------------------------------------------------------------------------

/**
 * lock     lock screen (signed out)
 * signin   provider authentication pages visible below a slim bar
 * app      provider chat app visible below the header and quick actions
 * preview  our preview / consent dialog covers the window
 * history  history-analysis options plus live preview cover the window
 */
export type AgentUiMode = 'lock' | 'signin' | 'app' | 'preview' | 'history';

export interface QuickActionDef {
  id: ActionId;
  /** Strings key of the chip label. */
  labelKey: string;
  /** Needs a question typed by the user before it runs. */
  needsQuestion: boolean;
}

export interface AgentWindowState {
  agent: AgentSummary;
  mode: AgentUiMode;
  /** Host currently shown in the provider view ('' when none). */
  host: string;
  loading: boolean;
  /** Always on top of every application. */
  pinned: boolean;
  /** Why the lock screen is shown. */
  lockReason: 'signedOut' | 'signInIncomplete' | 'signInRefused';
  /** A request that will run automatically after sign-in, described for the lock screen. */
  pendingLabel: string | null;
  notice: Notice | null;
  /** The browser window this agent takes context from is private, or has no usable page. */
  context: { private: boolean; pageAvailable: boolean; searchQuery: string | null };
}

export interface PreviewPayload {
  id: number;
  actionId: ActionId;
  /** "페이지: <title> — example.com", "방문 기록: 최근 7일", ... */
  source: string;
  text: string;
  truncated: boolean;
  /** History previews only. */
  counts: { visits: number; sites: number; searches: number; excludedSensitive: number } | null;
  /** History previews only: the options this preview was built from. */
  historyOptions: HistoryOptions | null;
}

export interface AgentApi {
  init(): Promise<SurfaceBoot & { state: AgentWindowState; actions: QuickActionDef[] }>;
  onState(listener: (state: AgentWindowState) => void): void;
  /** A preview to show (mode becomes `preview`), or null when it was withdrawn. */
  onPreview(listener: (preview: PreviewPayload | null) => void): void;
  onBoot(listener: (boot: SurfaceBoot) => void): void;

  // gate
  signIn(): void;
  cancelSignIn(): void;
  /** Open the provider's sign-in page in a normal browser tab instead. */
  signInInTab(): void;
  signOut(): void;

  // actions
  runAction(actionId: ActionId, options?: { question?: string }): void;
  confirmPreview(id: number, text: string, options: { target: HandoffTarget; skipNextTime: boolean }): void;
  cancelPreview(id: number): void;
  openHistoryAnalysis(): void;
  /** Rebuild the history preview for the given options (mode stays `history`). */
  previewHistory(options: HistoryOptions): Promise<PreviewPayload>;
  /** Insert the (possibly edited) history text into this agent, or into every signed-in agent. */
  sendHistory(id: number, text: string, options: { toAll: boolean }): void;
  closeHistoryAnalysis(): void;

  // window
  newChat(): void;
  reloadProvider(): void;
  openInTab(): void;
  setPinned(pinned: boolean): void;
  minimize(): void;
  close(): void;
  showMenu(at: Point): void;
  dismissNotice(id: number): void;
  /** Height in CSS pixels that our own UI occupies above the provider view in `signin` / `app` mode. */
  setHeaderHeight(px: number): void;
  /** Start moving or resizing the window with the pointer; the main process follows the cursor until endDrag(). */
  startMove(): void;
  startResize(): void;
  endDrag(): void;
}

// ---------------------------------------------------------------------------------------------
// Dock surface
// ---------------------------------------------------------------------------------------------

export interface DockState {
  agents: AgentSummary[];
  /** The browser window the dock belongs to is private. */
  private: boolean;
}

export interface DockApi {
  init(): Promise<SurfaceBoot & { state: DockState }>;
  onState(listener: (state: DockState) => void): void;
  onBoot(listener: (boot: SurfaceBoot) => void): void;
  toggleAgent(agentId: AgentId): void;
  showMenu(at: Point): void;
  /** The dock UI reports the size it needs so the window can fit it exactly. */
  setSize(width: number, height: number): void;
  startMove(): void;
  endDrag(): void;
}

// ---------------------------------------------------------------------------------------------
// Internal pages surface (trio://newtab, history, bookmarks, downloads, settings, about, error)
// ---------------------------------------------------------------------------------------------

export type PageId = 'newtab' | 'history' | 'bookmarks' | 'downloads' | 'settings' | 'about' | 'error';

export interface TopSite {
  title: string;
  url: string;
  host: string;
  faviconUrl: string | null;
}

export interface BookmarkNode {
  id: string;
  title: string;
  /** Absent for folders. */
  url?: string;
  /** Present for folders. */
  children?: BookmarkNode[];
}

export interface DownloadItem {
  id: string;
  url: string;
  filename: string;
  /** Absolute path on disk. */
  path: string;
  state: 'progressing' | 'paused' | 'completed' | 'cancelled' | 'interrupted';
  receivedBytes: number;
  /** 0 when unknown. */
  totalBytes: number;
  startTime: number;
  /** File still exists on disk (completed downloads). */
  exists: boolean;
}

export interface Settings {
  general: {
    language: 'system' | Locale;
    theme: 'system' | ThemeName;
    startup: 'newtab' | 'restore';
    showBookmarksBar: boolean;
  };
  search: {
    /** Id from shared/search-engines.json. */
    engine: string;
    /** Send keystrokes to the search engine for suggestions. */
    suggestions: boolean;
  };
  privacy: {
    saveHistory: boolean;
    /** Drop sensitive sites from anything handed to an agent. */
    excludeSensitive: boolean;
    /** Extra hosts the user never wants in a history digest. */
    extraExcludedHosts: string[];
  };
  downloads: {
    askWhere: boolean;
    /** null = the operating system's downloads folder. */
    directory: string | null;
  };
  agents: {
    enabled: Record<AgentId, boolean>;
    dockVisible: boolean;
    /** Show our preview dialog before inserting. History is always previewed. */
    preview: { page: boolean; selection: boolean; search: boolean };
    pageCharLimit: number;
    historyCharLimit: number;
    promptLanguage: 'ui' | Locale;
  };
  /** origin -> permission -> decision */
  permissions: Record<string, Partial<Record<PermissionKind, 'allow' | 'deny'>>>;
}

export interface ClearDataRequest {
  history: boolean;
  cookies: boolean;
  cache: boolean;
  range: 'hour' | 'day' | 'week' | 'month' | 'all';
}

export interface AboutInfo {
  appName: string;
  appVersion: string;
  electron: string;
  chromium: string;
  node: string;
  platform: string;
}

export interface PagesApi {
  init(): Promise<SurfaceBoot & { page: PageId; private: boolean }>;
  /** Something the open page lists may have changed. */
  onChanged(listener: (what: 'history' | 'bookmarks' | 'downloads' | 'settings' | 'agents') => void): void;
  onBoot(listener: (boot: SurfaceBoot) => void): void;
  /** Same as typing into the address bar. */
  navigate(input: string, options?: { newTab?: boolean }): void;

  // new tab
  getNewTabData(): Promise<{ topSites: TopSite[]; agents: AgentSummary[]; searchEngineName: string }>;
  toggleAgent(agentId: AgentId): void;

  // history
  queryHistory(query: { text: string; beforeTime: number | null; limit: number }): Promise<{
    entries: HistoryEntry[];
    hasMore: boolean;
  }>;
  deleteHistory(selection: { visitIds: number[] } | { range: ClearDataRequest['range'] }): Promise<void>;
  analyzeHistory(agentId?: AgentId): void;

  // bookmarks
  getBookmarks(): Promise<BookmarkNode[]>;
  addBookmark(bookmark: { title: string; url: string }): Promise<void>;
  updateBookmark(id: string, patch: { title?: string; url?: string }): Promise<void>;
  removeBookmark(id: string): Promise<void>;
  moveBookmark(id: string, toIndex: number): Promise<void>;

  // downloads
  getDownloads(): Promise<DownloadItem[]>;
  downloadAction(id: string, action: 'open' | 'show' | 'cancel' | 'pause' | 'resume' | 'remove'): Promise<void>;
  clearDownloads(): Promise<void>;

  // settings
  getSettings(): Promise<Settings>;
  /** `path` is a dotted path such as "search.engine"; the new, validated settings are returned. */
  setSetting(path: string, value: unknown): Promise<Settings>;
  getSearchEngines(): Promise<{ id: string; name: string }[]>;
  getAgents(): Promise<AgentSummary[]>;
  clearBrowsingData(request: ClearDataRequest): Promise<void>;
  chooseDownloadDirectory(): Promise<Settings>;

  // about / error
  getAbout(): Promise<AboutInfo>;
  /** Error page: retry the failed URL. */
  retry(): void;
}

// ---------------------------------------------------------------------------------------------
// Globals exposed by the preloads
// ---------------------------------------------------------------------------------------------

declare global {
  interface Window {
    trio: {
      chrome?: ChromeApi;
      agent?: AgentApi;
      dock?: DockApi;
      pages?: PagesApi;
    };
  }
}
