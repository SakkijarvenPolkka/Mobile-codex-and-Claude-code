'use strict';
/**
 * One browser tab: a sandboxed WebContentsView plus its navigation state, history recording and
 * dwell-time accounting. The owning BrowserWin passes callbacks for everything that needs the
 * window (opening tabs, menus, find results, state pushes).
 */

const { WebContentsView } = require('electron');
const { preloadFile } = require('./paths');
const { isInternalUrl, internalHost } = require('./protocol');
const { registerSurface } = require('./ipc');
const { popupOptions, guardPopup } = require('./popups');
const { displayUrl } = require('../core/url');
const { extractSearch } = require('../core/search');
const data = require('../core/data');

const MAX_DWELL_MS = 30 * 60 * 1000;
let nextTabId = 1;

/**
 * @typedef {import('../types/contract').VisitTransition} VisitTransition
 * @typedef {{
 *   app: import('./app-context').AppContext,
 *   win: import('./browser-window').BrowserWin,
 *   private: boolean,
 *   session: Electron.Session,
 *   onChange: (tab: Tab) => void,
 *   openTab: (url: string, opts: { background: boolean, opener: Tab }) => void,
 *   contextMenu: (tab: Tab, params: Electron.ContextMenuParams) => void,
 *   foundInPage: (tab: Tab, result: Electron.Result) => void,
 *   beforeInput: (event: Electron.Event, input: Electron.Input) => void,
 *   destroyed: (tab: Tab) => void,
 * }} TabHost
 */

class Tab {
  /**
   * @param {TabHost} host
   * @param {{ url?: string, title?: string, lazy?: boolean }} [initial]
   */
  constructor(host, initial = {}) {
    this.id = nextTabId++;
    this.host = host;
    this.app = host.app;
    this.view = new WebContentsView({
      webPreferences: {
        preload: preloadFile(),
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        webviewTag: false,
        session: host.session,
        safeDialogs: true,
        spellcheck: false,
      },
    });
    this.view.setBackgroundColor('#ffffff');
    this.wc = this.view.webContents;
    // Internal pages (trio://history and friends) talk to the browser through the "pages" API;
    // the IPC layer checks that the calling frame is really on such a page.
    registerSurface(this.wc, 'pages', { tab: this, win: host.win });
    this.title = initial.title || '';
    /** The committed URL (what the page really is). */
    this.url = initial.url || 'trio://newtab/';
    /** A navigation we started that has not committed yet (shown in the address bar meanwhile). */
    /** @type {string | null} */
    this.pendingUrl = null;
    /** The URL whose load failed, while the error page is shown. */
    /** @type {string | null} */
    this.failedUrl = null;
    this.destroyed = false;
    /** @type {string | null} */
    this.faviconUrl = null;
    this.loading = false;
    this.audible = false;
    this.crashed = false;
    this.failed = false;
    /** @type {VisitTransition | null} */
    this.pendingTransition = null;
    /** @type {number | null} */
    this.visitId = null;
    this.visitAccum = 0;
    /** @type {number | null} */
    this.activeSince = null;
    this.active = false;
    /** A restored background tab loads only when first shown. */
    this.lazyUrl = initial.lazy ? this.url : null;
    this.wire();
    if (!this.lazyUrl) this.load(this.url, null);
  }

  wire() {
    const wc = this.wc;
    wc.setWindowOpenHandler((details) => {
      const internal = isInternalUrl(details.url);
      if (internal && !isInternalUrl(wc.getURL())) return { action: 'deny' };
      if (details.disposition === 'new-window' && !internal && /^(https?:|about:blank)/.test(details.url)) {
        // Real popups (sign-in windows and the like) keep their opener; see popups.js for the
        // guards and the address shown in their title bar.
        return { action: 'allow', overrideBrowserWindowOptions: popupOptions(this.host.session) };
      }
      this.host.openTab(details.url, { background: details.disposition === 'background-tab', opener: this });
      return { action: 'deny' };
    });
    wc.on('did-create-window', (win) => guardPopup(win, (url) => this.host.openTab(url, { background: false, opener: this })));
    // Web content may never reach trio:// pages: not by navigation, redirect or subframe.
    wc.on('will-navigate', (event, url) => {
      if (isInternalUrl(url) && !isInternalUrl(wc.getURL())) event.preventDefault();
    });
    wc.on('will-redirect', (event) => {
      if (isInternalUrl(event.url)) event.preventDefault();
    });
    wc.on('will-frame-navigate', (event) => {
      if (!event.isMainFrame && isInternalUrl(event.url)) event.preventDefault();
    });
    // A page may close itself (window.close()); the web contents is then destroyed.
    wc.once('destroyed', () => {
      this.destroyed = true;
      this.host.destroyed(this);
    });
    wc.on('did-start-loading', () => {
      this.loading = true;
      this.changed();
    });
    wc.on('did-stop-loading', () => {
      this.loading = false;
      // A navigation that never committed (download, external protocol, aborted) is forgotten.
      this.clearPending();
      this.changed();
    });
    wc.on('did-navigate', (_e, url, httpCode) => {
      this.failed = internalHost(url) === 'error';
      if (!this.failed) this.failedUrl = null;
      this.crashed = false;
      this.pendingUrl = null;
      this.url = url;
      this.faviconUrl = this.app.history ? this.app.history.faviconForUrl(url) : null;
      if (!this.failed) this.recordVisit(url, httpCode);
      this.changed();
    });
    wc.on('did-navigate-in-page', (_e, url, isMainFrame) => {
      if (!isMainFrame) return;
      const before = this.url;
      this.url = url;
      if (before.split('#')[0] !== url.split('#')[0]) this.recordVisit(url, 200);
      this.changed();
    });
    wc.on('page-title-updated', (_e, title) => {
      this.title = title;
      if (this.visitId !== null && this.app.history) this.app.history.setTitle(this.visitId, title, this.url);
      this.changed();
    });
    wc.on('page-favicon-updated', (_e, favicons) => {
      const first = favicons.find((f) => /^(https?|data):/.test(f));
      if (first) this.fetchFavicon(first);
    });
    wc.on('did-fail-load', (_e, errorCode, errorDescription, validatedURL, isMainFrame) => {
      if (!isMainFrame) return;
      if (errorCode === -3) {
        // Aborted: the user stopped, a new navigation started, or it became a download.
        this.clearPending();
        this.changed();
        return;
      }
      if (isInternalUrl(validatedURL)) return;
      // The failed URL is kept here; the error page's own query string is only for its text.
      this.failedUrl = validatedURL;
      this.pendingTransition = null;
      const q = new URLSearchParams({ url: validatedURL, code: String(errorCode), desc: errorDescription });
      this.wc.loadURL(`trio://error/?${q}`).catch(() => {});
    });
    wc.on('render-process-gone', () => {
      this.crashed = true;
      this.loading = false;
      this.changed();
    });
    wc.on('audio-state-changed', (e) => {
      this.audible = e.audible;
      this.changed();
    });
    wc.on('context-menu', (_e, params) => this.host.contextMenu(this, params));
    wc.on('found-in-page', (_e, result) => this.host.foundInPage(this, result));
    wc.on('before-input-event', (event, input) => this.host.beforeInput(event, input));
    wc.on('zoom-changed', (_e, dir) => {
      this.zoomBy(dir === 'in' ? 1 : -1);
    });
  }

  changed() {
    if (!this.destroyed) this.host.onChange(this);
  }

  clearPending() {
    if (this.pendingUrl !== null || this.pendingTransition !== null) {
      this.pendingUrl = null;
      this.pendingTransition = null;
    }
  }

  /**
   * @param {string} url
   * @param {VisitTransition | null} transition
   */
  load(url, transition) {
    if (this.destroyed) return;
    this.lazyUrl = null;
    this.pendingTransition = transition;
    this.pendingUrl = url;
    this.wc.loadURL(url).catch(() => {
      /* failures surface through did-fail-load */
    });
    this.changed();
  }

  /** The URL to save in the session: what the tab shows, never an error page or a download. */
  sessionUrl() {
    if (this.lazyUrl) return this.lazyUrl;
    if (this.failed && this.failedUrl) return this.failedUrl;
    return this.url;
  }

  /** Loads a lazily restored tab. */
  ensureLoaded() {
    if (this.lazyUrl) this.load(this.lazyUrl, null);
  }

  /**
   * @param {string} url
   * @param {number} httpCode
   */
  recordVisit(url, httpCode) {
    this.finishVisit();
    const transition = this.pendingTransition || 'link';
    this.pendingTransition = null;
    const app = this.app;
    if (this.host.private || !app.history || !app.settings.get().privacy.saveHistory) return;
    if (!/^https?:/.test(url) || httpCode >= 400) return;
    const from = transition === 'link' ? this.visitId : null;
    this.visitId = app.history.addVisit({ url, title: this.wc.getTitle() === url ? '' : this.wc.getTitle(), time: Date.now(), transition, fromVisitId: from });
    this.visitAccum = 0;
    if (this.active) this.activeSince = Date.now();
    app.historyChanged();
  }

  /** Stores the dwell time of the current visit. */
  finishVisit() {
    if (this.visitId === null || !this.app.history) return;
    const now = Date.now();
    const total = this.visitAccum + (this.activeSince !== null ? now - this.activeSince : 0);
    this.app.history.setDuration(this.visitId, Math.min(total, MAX_DWELL_MS), this.url);
    this.visitId = null;
    this.visitAccum = 0;
    this.activeSince = this.active ? now : null;
  }

  /** @param {boolean} active */
  setActive(active) {
    if (active === this.active) return;
    const now = Date.now();
    if (!active && this.activeSince !== null) this.visitAccum += now - this.activeSince;
    this.activeSince = active ? now : null;
    this.active = active;
    if (active) this.ensureLoaded();
  }

  /** Stops counting dwell time (window blurred or minimised) without deactivating the tab. */
  pauseTimer() {
    if (this.activeSince !== null) this.visitAccum += Date.now() - this.activeSince;
    this.activeSince = null;
  }

  resumeTimer() {
    if (this.active && this.activeSince === null) this.activeSince = Date.now();
  }

  /** The history rows this tab pointed at were deleted. */
  forgetVisit() {
    this.visitId = null;
    this.visitAccum = 0;
  }

  /** @param {string} iconUrl */
  async fetchFavicon(iconUrl) {
    try {
      let dataUrl = iconUrl;
      if (!iconUrl.startsWith('data:')) {
        const res = await this.host.session.fetch(iconUrl, { credentials: 'omit' });
        const type = res.headers.get('content-type') || 'image/x-icon';
        if (!res.ok || !/^image\//.test(type)) return;
        const buf = Buffer.from(await res.arrayBuffer());
        if (buf.length > 100 * 1024) return;
        dataUrl = `data:${type.split(';')[0]};base64,${buf.toString('base64')}`;
      } else if (iconUrl.length > 140 * 1024) return;
      this.faviconUrl = dataUrl;
      if (!this.host.private && this.app.history) {
        try {
          this.app.history.setFavicon(new URL(this.url).host, dataUrl);
        } catch {
          /* not a URL with a host */
        }
      }
      this.changed();
    } catch {
      /* favicons are best effort */
    }
  }

  /** @param {number} step +1 in, -1 out, 0 reset */
  zoomBy(step) {
    const levels = [0.25, 0.33, 0.5, 0.67, 0.75, 0.8, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4, 5];
    const current = this.wc.getZoomFactor();
    let next = 1;
    if (step > 0) next = levels.find((l) => l > current + 0.001) || levels[levels.length - 1];
    else if (step < 0) next = [...levels].reverse().find((l) => l < current - 0.001) || levels[0];
    this.wc.setZoomFactor(next);
    this.changed();
  }

  /** @returns {import('../types/contract').TabState} */
  state() {
    const url = this.url;
    if (this.destroyed || this.wc.isDestroyed()) {
      return { id: this.id, title: this.title, url, displayUrl: displayUrl(url), faviconUrl: null, loading: false, canGoBack: false, canGoForward: false, audible: false, muted: false, crashed: true, security: 'error', zoomPercent: 100, bookmarked: false, search: null, internal: false };
    }
    const internal = isInternalUrl(url);
    const host = internalHost(url);
    const nav = this.wc.navigationHistory;
    const search = !internal ? extractSearch(data.searchEngines(), url) : null;
    let security = /** @type {import('../types/contract').Security} */ ('insecure');
    if (this.failed) security = 'error';
    else if (internal) security = 'internal';
    else if (url.startsWith('https:')) security = 'secure';
    else if (url.startsWith('file:') || /^http:\/\/(localhost|127\.|\[::1\])/.test(url)) security = 'local';
    let shownUrl = displayUrl(this.pendingUrl && this.loading ? this.pendingUrl : url);
    if (host === 'newtab' && !(this.pendingUrl && this.loading)) shownUrl = '';
    else if (host === 'error') shownUrl = displayUrl(this.failedUrl || '');
    return {
      id: this.id,
      title: this.title || (host === 'newtab' ? '' : shownUrl),
      url,
      displayUrl: shownUrl,
      faviconUrl: this.faviconUrl,
      loading: this.loading,
      canGoBack: nav.canGoBack(),
      canGoForward: nav.canGoForward(),
      audible: this.audible,
      muted: this.wc.isAudioMuted(),
      crashed: this.crashed,
      security,
      zoomPercent: Math.round(this.wc.getZoomFactor() * 100),
      bookmarked: Boolean(this.app.bookmarks.findByUrl(url)),
      search: search ? { engineId: search.engineId, engineName: search.name, query: search.query } : null,
      internal,
    };
  }

  destroy() {
    if (this.destroyed) return;
    this.finishVisit();
    this.destroyed = true;
    if (!this.wc.isDestroyed()) this.wc.close();
  }
}

module.exports = { Tab };
