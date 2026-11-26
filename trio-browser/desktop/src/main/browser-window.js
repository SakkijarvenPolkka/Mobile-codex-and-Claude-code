'use strict';
/**
 * A browser window: a BaseWindow holding the chrome UI view (tab strip, toolbar, bars) above the
 * active tab's view. The chrome UI is a pure function of state() pushed from here.
 */

const { BaseWindow, WebContentsView, clipboard } = require('electron');
const { Tab } = require('./tab');
const { preloadFile } = require('./paths');
const { registerSurface, emit } = require('./ipc');
const { classifyInput, agentKeywordMap } = require('../core/classify-input');
const { buildSearchUrl } = require('../core/search');
const data = require('../core/data');
const shortcuts = require('./shortcuts');
const menus = require('./menus');

let nextWindowId = 1;
let nextPromptId = 1;
let nextNoticeId = 1;

/**
 * @typedef {import('../types/contract').ChromeState} ChromeState
 * @typedef {import('./app-context').AppContext} AppContext
 * @typedef {{ private?: boolean, bounds?: Electron.Rectangle | null, maximized?: boolean, tabs?: { url: string, title?: string }[], active?: number }} WindowOptions
 */

const DEFAULT_CHROME_HEIGHT = 84;

class BrowserWin {
  /**
   * @param {AppContext} app
   * @param {Electron.Session} session
   * @param {WindowOptions} options
   */
  constructor(app, session, options = {}) {
    this.id = nextWindowId++;
    this.app = app;
    this.session = session;
    this.private = Boolean(options.private);
    /** @type {Tab[]} */
    this.tabs = [];
    /** @type {Tab | null} */
    this.active = null;
    this.chromeHeight = DEFAULT_CHROME_HEIGHT;
    this.overlay = false;
    /** @type {import('../types/contract').FindState | null} */
    this.findState = null;
    /** @type {import('../types/contract').Notice & { action?: () => void } | null} */
    this.notice = null;
    /** @type {{ id: number, origin: string, permission: import('../types/contract').PermissionKind, resolve: (d: 'allow' | 'deny') => void, remember: boolean }[]} */
    this.prompts = [];
    this.htmlFullscreen = false;
    this.closed = false;
    /** @type {NodeJS.Timeout | null} */
    this.pushTimer = null;

    const b = options.bounds;
    this.win = new BaseWindow({
      width: b ? b.width : 1280,
      height: b ? b.height : 840,
      ...(b ? { x: b.x, y: b.y } : {}),
      minWidth: 480,
      minHeight: 360,
      show: false,
      title: 'Trio',
      backgroundColor: app.theme() === 'dark' ? '#1d1e22' : '#f3f4f7',
    });
    if (options.maximized) this.win.maximize();

    this.chromeView = new WebContentsView({
      webPreferences: { preload: preloadFile(), sandbox: true, contextIsolation: true, nodeIntegration: false, session: this.session, spellcheck: false },
    });
    this.chromeView.setBackgroundColor('#00000000');
    registerSurface(this.chromeView.webContents, 'chrome', this);
    this.chromeView.webContents.on('before-input-event', (e, input) => this.beforeInput(e, input));
    this.chromeView.webContents.on('will-navigate', (e) => e.preventDefault());
    this.chromeView.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    this.win.contentView.addChildView(this.chromeView);
    this.chromeView.webContents.loadURL('trio://chrome/');
    this.chromeView.webContents.once('did-finish-load', () => {
      if (!this.closed) this.win.show();
    });

    this.win.on('resize', () => this.layout());
    this.win.on('focus', () => app.windows.focused(this));
    this.win.on('enter-full-screen', () => this.pushState());
    this.win.on('leave-full-screen', () => {
      this.htmlFullscreen = false;
      this.layout();
      this.pushState();
    });
    this.win.on('closed', () => this.destroy());

    const tabs = options.tabs && options.tabs.length ? options.tabs : [{ url: 'trio://newtab/' }];
    const activeIndex = Math.min(options.active || 0, tabs.length - 1);
    tabs.forEach((t, i) => this.addTab(t.url, { background: i !== activeIndex, lazy: i !== activeIndex, title: t.title }));
    this.layout();
  }

  // ---------------------------------------------------------------------------------------------
  // Tabs

  /**
   * @param {string} url
   * @param {{ background?: boolean, index?: number, lazy?: boolean, title?: string, transition?: import('../types/contract').VisitTransition | null }} [opts]
   */
  addTab(url, opts = {}) {
    const tab = new Tab(
      {
        app: this.app,
        win: this,
        private: this.private,
        session: this.session,
        onChange: (t) => {
          if (t === this.active) this.updateTitle();
          this.pushState();
          this.app.windows.sessionChanged();
        },
        openTab: (u, o) => this.addTab(u, { background: o.background, index: this.tabs.indexOf(o.opener) + 1 }),
        contextMenu: (t, params) => menus.pageContextMenu(this, t, params),
        foundInPage: (t, result) => this.onFound(t, result),
        beforeInput: (e, input) => this.beforeInput(e, input),
      },
      { url, lazy: opts.lazy, title: opts.title },
    );
    if (opts.transition && !opts.lazy) tab.pendingTransition = opts.transition;
    tab.wc.on('enter-html-full-screen', () => {
      this.htmlFullscreen = true;
      this.win.setFullScreen(true);
      this.layout();
    });
    tab.wc.on('leave-html-full-screen', () => {
      this.htmlFullscreen = false;
      this.win.setFullScreen(false);
      this.layout();
    });
    const index = opts.index !== undefined && opts.index >= 0 ? Math.min(opts.index, this.tabs.length) : this.tabs.length;
    this.tabs.splice(index, 0, tab);
    if (!opts.background || !this.active) this.activate(tab);
    this.pushState();
    this.app.windows.sessionChanged();
    return tab;
  }

  /** @param {Tab} tab */
  activate(tab) {
    if (this.active === tab) return;
    const prev = this.active;
    if (prev) {
      prev.setActive(false);
      this.win.contentView.removeChildView(prev.view);
    }
    this.active = tab;
    this.findState = null;
    tab.setActive(true);
    this.win.contentView.addChildView(tab.view, 0);
    this.layout();
    this.updateTitle();
    if (this.win.isFocused()) tab.wc.focus();
    this.pushState();
    this.app.agents.contextChanged();
  }

  /** @param {number} tabId */
  closeTab(tabId) {
    const tab = this.tabs.find((t) => t.id === tabId);
    if (!tab) return;
    const index = this.tabs.indexOf(tab);
    if (!this.private && !tab.url.startsWith('trio://newtab')) this.app.windows.rememberClosed(tab.url, tab.title);
    this.tabs.splice(index, 1);
    if (this.active === tab) {
      this.win.contentView.removeChildView(tab.view);
      this.active = null;
      const next = this.tabs[Math.min(index, this.tabs.length - 1)];
      if (next) this.activate(next);
    }
    tab.destroy();
    if (!this.tabs.length) {
      this.win.close();
      return;
    }
    this.pushState();
    this.app.windows.sessionChanged();
  }

  /** @param {number} tabId @param {number} toIndex */
  moveTab(tabId, toIndex) {
    const from = this.tabs.findIndex((t) => t.id === tabId);
    if (from < 0) return;
    const [tab] = this.tabs.splice(from, 1);
    this.tabs.splice(Math.max(0, Math.min(this.tabs.length, toIndex)), 0, tab);
    this.pushState();
    this.app.windows.sessionChanged();
  }

  /** @param {number} delta */
  cycleTab(delta) {
    if (!this.active || this.tabs.length < 2) return;
    const i = this.tabs.indexOf(this.active);
    this.activate(this.tabs[(i + delta + this.tabs.length) % this.tabs.length]);
  }

  /** @param {number} n 1-based; 9 = last */
  selectTabNumber(n) {
    const tab = n === 9 ? this.tabs[this.tabs.length - 1] : this.tabs[n - 1];
    if (tab) this.activate(tab);
  }

  // ---------------------------------------------------------------------------------------------
  // Navigation

  /**
   * Acts on text typed into the address bar (or the new-tab search box).
   * @param {string} input
   * @param {{ newTab?: boolean }} [opts]
   */
  navigate(input, opts = {}) {
    const kind = classifyInput(input, { agentKeywords: agentKeywordMap(data.providers()), suffixes: data.suffixData() });
    switch (kind.type) {
      case 'empty':
        return;
      case 'url':
        return this.openUrl(kind.url, { newTab: opts.newTab, transition: 'typed' });
      case 'search':
        return this.search(kind.query, opts);
      case 'agent':
        return this.askAgent(kind.agentId, kind.query);
      case 'history':
        return this.openUrl(`trio://history/${kind.query ? '?q=' + encodeURIComponent(kind.query) : ''}`, { newTab: opts.newTab });
    }
  }

  /** @param {string} query @param {{ newTab?: boolean }} [opts] */
  search(query, opts = {}) {
    const url = buildSearchUrl(data.searchEngines(), this.app.settings.get().search.engine, query);
    this.openUrl(url, { newTab: opts.newTab, transition: 'search' });
  }

  /**
   * @param {import('../types/contract').AgentTarget} agentId
   * @param {string} query
   */
  askAgent(agentId, query) {
    if (this.private) {
      this.showNotice('warning', this.app.t('notice.privateNoAgents'));
      return;
    }
    this.app.agents.ask(agentId, query, this);
  }

  /**
   * @param {string} url
   * @param {{ newTab?: boolean, transition?: import('../types/contract').VisitTransition | null, background?: boolean }} [opts]
   */
  openUrl(url, opts = {}) {
    if (opts.newTab || !this.active) {
      this.addTab(url, { transition: opts.transition || null, background: opts.background, index: this.active ? this.tabs.indexOf(this.active) + 1 : undefined });
    } else {
      this.active.load(url, opts.transition || null);
      this.active.wc.focus();
    }
  }

  /** @param {import('../types/contract').Suggestion} s @param {{ newTab?: boolean }} [opts] */
  acceptSuggestion(s, opts = {}) {
    const a = s.action;
    if (a.type === 'navigate') this.openUrl(a.url, { newTab: opts.newTab, transition: s.kind === 'bookmark' ? 'bookmark' : 'typed' });
    else if (a.type === 'search') this.search(a.query, opts);
    else if (a.type === 'agent') this.askAgent(a.agentId, a.query);
    else if (a.type === 'history') this.openUrl(`trio://history/${a.query ? '?q=' + encodeURIComponent(a.query) : ''}`, opts);
  }

  back() {
    const nav = this.active?.wc.navigationHistory;
    if (nav && nav.canGoBack()) nav.goBack();
  }

  forward() {
    const nav = this.active?.wc.navigationHistory;
    if (nav && nav.canGoForward()) nav.goForward();
  }

  /** @param {{ hard?: boolean }} [opts] */
  reload(opts = {}) {
    const tab = this.active;
    if (!tab) return;
    if (tab.crashed || tab.failed) {
      const url = tab.failed ? new URL(tab.url).searchParams.get('url') || tab.url : tab.url;
      tab.load(url, 'reload');
      return;
    }
    tab.pendingTransition = 'reload';
    if (opts.hard) tab.wc.reloadIgnoringCache();
    else tab.wc.reload();
  }

  stop() {
    this.active?.wc.stop();
  }

  // ---------------------------------------------------------------------------------------------
  // Page tools

  /** @param {{ action: 'search' | 'next' | 'previous' | 'close', text?: string }} req */
  find(req) {
    const wc = this.active?.wc;
    if (!wc) return;
    if (req.action === 'close') {
      wc.stopFindInPage('keepSelection');
      this.findState = null;
    } else if (req.action === 'search') {
      const text = req.text || '';
      this.findState = { query: text, current: 0, total: 0 };
      if (text) wc.findInPage(text);
      else wc.stopFindInPage('clearSelection');
    } else if (this.findState && this.findState.query) {
      wc.findInPage(this.findState.query, { forward: req.action === 'next', findNext: true });
    }
    this.pushState();
  }

  openFind() {
    if (!this.findState) this.findState = { query: '', current: 0, total: 0 };
    this.pushState();
    this.command({ type: 'open-find' });
  }

  /** @param {Tab} tab @param {Electron.Result} result */
  onFound(tab, result) {
    if (tab !== this.active || !this.findState) return;
    if (result.finalUpdate || result.matches !== undefined) {
      this.findState = { query: this.findState.query, current: result.activeMatchOrdinal || 0, total: result.matches || 0 };
      this.pushState();
    }
  }

  toggleBookmark() {
    const tab = this.active;
    if (!tab || tab.url.startsWith('trio://')) return;
    const existing = this.app.bookmarks.findByUrl(tab.url);
    if (existing) {
      this.app.bookmarks.remove(existing.id);
      this.showNotice('info', this.app.t('notice.bookmarkRemoved'));
    } else {
      this.app.bookmarks.add({ title: tab.title || tab.url, url: tab.url });
      this.showNotice('success', this.app.t('notice.bookmarkAdded'));
    }
  }

  /** @param {string} id @param {{ newTab?: boolean }} [opts] */
  openBookmark(id, opts = {}) {
    const b = this.app.bookmarks.list().find((x) => x.id === id);
    if (b) this.openUrl(b.url, { newTab: opts.newTab, transition: 'bookmark' });
  }

  // ---------------------------------------------------------------------------------------------
  // Permission prompts and notices

  /**
   * @param {string} origin
   * @param {import('../types/contract').PermissionKind} permission
   * @returns {Promise<'allow' | 'deny'>}
   */
  requestPermission(origin, permission) {
    return new Promise((resolve) => {
      this.prompts.push({ id: nextPromptId++, origin, permission, resolve, remember: !this.private });
      this.pushState();
    });
  }

  /** @param {number} id @param {'allow' | 'deny'} decision @param {boolean} remember */
  respondPermission(id, decision, remember) {
    const i = this.prompts.findIndex((p) => p.id === id);
    if (i < 0) return;
    const [p] = this.prompts.splice(i, 1);
    if (remember && !this.private) this.app.settings.setPermission(p.origin, p.permission, decision);
    p.resolve(decision);
    // Answer queued prompts that the remembered decision now covers.
    if (remember && !this.private) {
      for (const q of [...this.prompts]) {
        if (q.origin === p.origin && q.permission === p.permission) this.respondPermission(q.id, decision, false);
      }
    }
    this.pushState();
  }

  /**
   * @param {'info' | 'success' | 'warning' | 'error'} kind
   * @param {string} text
   * @param {{ label: string, run: () => void }} [action]
   */
  showNotice(kind, text, action) {
    this.notice = { id: nextNoticeId++, kind, text, actionLabel: action ? action.label : null, action: action ? action.run : undefined };
    const id = this.notice.id;
    this.pushState();
    if (!action) {
      setTimeout(() => {
        if (this.notice && this.notice.id === id) this.dismissNotice(id, false);
      }, 4000);
    }
  }

  /** @param {number} id @param {boolean} runAction */
  dismissNotice(id, runAction) {
    if (!this.notice || this.notice.id !== id) return;
    const action = this.notice.action;
    this.notice = null;
    this.pushState();
    if (runAction && action) action();
  }

  // ---------------------------------------------------------------------------------------------
  // Layout and state

  /** @param {boolean} open */
  setOverlay(open) {
    this.overlay = Boolean(open);
    this.layout();
  }

  /** @param {number} px */
  setChromeHeight(px) {
    const h = Math.max(0, Math.min(400, Math.round(Number(px) || 0)));
    if (h !== this.chromeHeight) {
      this.chromeHeight = h;
      this.layout();
    }
  }

  layout() {
    if (this.closed) return;
    const { width, height } = this.win.getContentBounds();
    const top = this.htmlFullscreen ? 0 : this.chromeHeight;
    if (this.active) this.active.view.setBounds({ x: 0, y: top, width, height: Math.max(0, height - top) });
    this.chromeView.setVisible(!this.htmlFullscreen);
    this.chromeView.setBounds(this.overlay ? { x: 0, y: 0, width, height } : { x: 0, y: 0, width, height: this.chromeHeight });
  }

  updateTitle() {
    const t = this.active ? this.active.state().title : '';
    this.win.setTitle(t ? `${t} - Trio` : 'Trio');
  }

  /** @returns {ChromeState} */
  state() {
    const settings = this.app.settings.get();
    const prompt = this.prompts[0];
    return {
      windowId: this.id,
      private: this.private,
      tabs: this.tabs.map((t) => t.state()),
      activeTabId: this.active ? this.active.id : null,
      agents: this.app.agents.summaries(),
      dockVisible: this.app.agents.dockVisible(),
      bookmarksBar: {
        visible: settings.general.showBookmarksBar,
        items: settings.general.showBookmarksBar
          ? this.app.bookmarks.list().slice(0, 60).map((b) => ({ id: b.id, title: b.title, url: b.url, faviconUrl: this.app.history ? this.app.history.faviconForUrl(b.url) : null }))
          : [],
      },
      downloads: this.app.downloads.summary(),
      permissionPrompt: prompt ? { id: prompt.id, origin: prompt.origin, permission: prompt.permission } : null,
      find: this.findState,
      notice: this.notice ? { id: this.notice.id, kind: this.notice.kind, text: this.notice.text, actionLabel: this.notice.actionLabel } : null,
      fullscreen: this.win.isFullScreen(),
    };
  }

  pushState() {
    if (this.closed || this.pushTimer) return;
    this.pushTimer = setTimeout(() => {
      this.pushTimer = null;
      if (!this.closed) emit(this.chromeView.webContents, 'chrome', 'state', this.state());
    }, 16);
  }

  /** @param {import('../types/contract').ChromeUiCommand} cmd */
  command(cmd) {
    if (!this.closed) emit(this.chromeView.webContents, 'chrome', 'command', cmd);
  }

  focusAddressBar(text = '', selectAll = true) {
    this.chromeView.webContents.focus();
    this.command({ type: 'focus-address-bar', text, selectAll });
  }

  /** @param {Electron.Event} event @param {Electron.Input} input */
  beforeInput(event, input) {
    const command = shortcuts.match(input);
    if (command && shortcuts.run(this, command)) event.preventDefault();
  }

  copyUrl() {
    if (this.active) clipboard.writeText(this.active.url);
  }

  destroy() {
    if (this.closed) return;
    this.closed = true;
    if (this.pushTimer) clearTimeout(this.pushTimer);
    for (const p of this.prompts) p.resolve('deny');
    this.prompts = [];
    for (const t of this.tabs) t.destroy();
    this.tabs = [];
    if (!this.chromeView.webContents.isDestroyed()) this.chromeView.webContents.close();
    this.app.windows.closed(this);
  }
}

module.exports = { BrowserWin };
