'use strict';
/**
 * A floating agent window: a frameless OS window owned by the browser window, with Trio's own UI
 * (header, quick actions, lock screen, preview dialog) and the provider's web app below it. The
 * provider view is attached only while the login gate allows it.
 */

const { BaseWindow, WebContentsView, screen, clipboard, Menu } = require('electron');
const { preloadFile } = require('../paths');
const { registerSurface, emit } = require('../ipc');
const { classifyProviderUrl, gateView } = require('../../core/login-state');
const { planHandoff } = require('../../core/handoff');
const data = require('../../core/data');
const shortcuts = require('../shortcuts');
const { popupOptions, guardPopup } = require('../popups');
const { isInternalUrl } = require('../protocol');

const DEFAULT_SIZE = { width: 440, height: 680 };
const MIN_SIZE = { width: 340, height: 420 };
const ISOLATED_WORLD = 1201;
let nextNotice = 1;

/**
 * @typedef {import('../../types/contract').AgentUiMode} AgentUiMode
 * @typedef {import('../../types/contract').PreviewPayload} PreviewPayload
 * @typedef {import('./agent-manager').AgentRecord} AgentRecord
 * @typedef {import('./agent-manager').AgentManager} AgentManager
 */

class AgentWindow {
  /**
   * @param {AgentManager} manager
   * @param {AgentRecord} record
   * @param {{ bounds: Electron.Rectangle | null, pinned: boolean }} layout
   */
  constructor(manager, record, layout) {
    this.manager = manager;
    this.record = record;
    this.provider = record.provider;
    this.app = manager.app;
    /** @type {'overlay' | null} */
    this.overlay = null;
    /** @type {PreviewPayload | null} */
    this.preview = null;
    this.historyOpen = false;
    this.pinned = layout.pinned;
    this.headerHeight = 76;
    /** @type {import('../../types/contract').Notice | null} */
    this.notice = null;
    /** URL the provider view should show while the gate allows it ('' = nothing loaded). */
    this.providerUrl = '';
    this.loading = false;
    this.closed = false;
    /** @type {NodeJS.Timeout | null} */
    this.drag = null;
    /** @type {NodeJS.Timeout | null} */
    this.pushTimer = null;

    const parent = this.app.windows.current();
    const bounds = layout.bounds && onScreen(layout.bounds) ? layout.bounds : this.defaultBounds(parent ? parent.win.getBounds() : null);
    this.win = new BaseWindow({
      ...bounds,
      minWidth: MIN_SIZE.width,
      minHeight: MIN_SIZE.height,
      frame: false,
      show: false,
      resizable: true,
      maximizable: false,
      fullscreenable: false,
      title: this.provider.name,
      backgroundColor: this.app.theme() === 'dark' ? '#1d1e22' : '#ffffff',
      ...(parent ? { parent: parent.win } : {}),
    });
    if (this.pinned) this.win.setAlwaysOnTop(true, 'floating');

    this.ui = new WebContentsView({
      webPreferences: { preload: preloadFile(), sandbox: true, contextIsolation: true, nodeIntegration: false, spellcheck: false },
    });
    registerSurface(this.ui.webContents, 'agent', this);
    this.ui.webContents.on('will-navigate', (e) => e.preventDefault());
    this.ui.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    this.ui.webContents.on('before-input-event', (e, input) => this.beforeInput(e, input));
    this.win.contentView.addChildView(this.ui);
    this.ui.webContents.loadURL(`trio://agent/?agent=${encodeURIComponent(this.provider.id)}`);

    this.providerView = this.createProviderView();
    this.providerAttached = false;

    this.win.on('resize', () => {
      this.layout();
      this.manager.saveLayout(this);
    });
    this.win.on('move', () => this.manager.saveLayout(this));
    this.win.on('closed', () => this.destroy());
    this.layout();
  }

  createProviderView() {
    const view = new WebContentsView({
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, webviewTag: false, safeDialogs: true, spellcheck: true },
    });
    const wc = view.webContents;
    wc.on('did-start-loading', () => {
      this.loading = true;
      this.pushState();
    });
    wc.on('did-stop-loading', () => {
      this.loading = false;
      // Give a script-driven logout page a moment to finish before the view is cleared.
      if (this.record.signingOut) setTimeout(() => this.manager.signOutFinished(this.record), 2000);
      this.pushState();
    });
    wc.on('did-navigate', (_e, url) => this.onNavigate(url));
    wc.on('did-navigate-in-page', (_e, url, isMainFrame) => {
      if (isMainFrame) this.onNavigate(url);
    });
    /** Navigations and redirects: never to internal pages; external sites go to a tab when signed in. */
    const guard = (/** @type {Electron.Event} */ event, /** @type {string} */ url) => {
      if (isInternalUrl(url) || /^(file|chrome|devtools):/.test(url)) {
        event.preventDefault();
        return;
      }
      if (this.record.state() === 'signedIn' && classifyProviderUrl(this.provider, url) === 'external') {
        event.preventDefault();
        this.manager.openInBrowser(url);
      }
    };
    wc.on('will-navigate', (event) => guard(event, event.url));
    wc.on('will-redirect', (event) => guard(event, event.url));
    wc.on('will-frame-navigate', (event) => {
      if (!event.isMainFrame && isInternalUrl(event.url)) event.preventDefault();
    });
    wc.setWindowOpenHandler((details) => {
      const state = this.record.state();
      if (state === 'signedOut' || isInternalUrl(details.url)) return { action: 'deny' };
      const kind = details.url === 'about:blank' ? 'blank' : classifyProviderUrl(this.provider, details.url);
      // Popups are for signing in: identity-provider pages, or a blank popup that a sign-in
      // flow fills in (only while signing in).
      if (kind === 'auth' || (state === 'signingIn' && kind !== 'app')) {
        return { action: 'allow', overrideBrowserWindowOptions: { ...popupOptions(), parent: /** @type {any} */ (this.win) } };
      }
      if (kind === 'app' && state === 'signedIn') this.loadProvider(details.url);
      else if (kind === 'external') this.manager.openInBrowser(details.url);
      return { action: 'deny' };
    });
    wc.on('did-create-window', (win) => guardPopup(win, (url) => this.manager.openInBrowser(url)));
    // The provider's page may close itself (window.close()); replace the view.
    wc.once('destroyed', () => {
      if (!this.closed && this.providerView === view) this.replaceProviderView();
    });
    wc.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
      if (isMainFrame && code !== -3 && url !== 'about:blank') this.showNotice('error', this.app.t('agent.notice.loadFailed', { error: desc || String(code) }));
    });
    wc.on('render-process-gone', () => this.showNotice('error', this.app.t('agent.notice.crashed')));
    wc.on('context-menu', (_e, p) => this.providerContextMenu(p));
    wc.on('before-input-event', (e, input) => this.beforeInput(e, input));
    return view;
  }

  /** Swaps in a fresh provider view after the old one's web contents went away. */
  replaceProviderView() {
    if (this.providerAttached) {
      try {
        this.win.contentView.removeChildView(this.providerView);
      } catch {
        /* already gone */
      }
      this.providerAttached = false;
    }
    this.providerView = this.createProviderView();
    this.providerUrl = '';
    this.loading = false;
    const state = this.record.state();
    if (state === 'signingIn') this.manager.cancelSignIn(this.provider.id);
    else this.stateChanged();
  }

  /** @param {Electron.Rectangle | null} parent */
  defaultBounds(parent) {
    const display = parent ? screen.getDisplayMatching(parent) : screen.getPrimaryDisplay();
    const area = display.workArea;
    const width = Math.min(DEFAULT_SIZE.width, area.width - 32);
    const height = Math.min(DEFAULT_SIZE.height, area.height - 64);
    const index = this.manager.order.indexOf(this.provider.id);
    const right = parent ? Math.min(parent.x + parent.width, area.x + area.width) : area.x + area.width;
    // Leave room for the dock, which sits at the right edge of the browser window.
    const x = Math.max(area.x + 16, right - width - 96 - index * 36);
    const y = Math.max(area.y + 16, (parent ? parent.y + 96 : area.y + 96) + index * 36);
    return { x, y: Math.min(y, area.y + area.height - height - 16), width, height };
  }

  // ---------------------------------------------------------------------------------------------
  // Gate and provider loading

  /** The mode the UI shows, derived from the login state and our own overlays. */
  mode() {
    const state = this.record.state();
    if (state === 'signedIn') {
      if (this.preview) return /** @type {AgentUiMode} */ ('preview');
      if (this.historyOpen) return /** @type {AgentUiMode} */ ('history');
      return /** @type {AgentUiMode} */ ('app');
    }
    return /** @type {AgentUiMode} */ (state === 'signingIn' ? 'signin' : 'lock');
  }

  /** Attaches or detaches the provider view to match the mode and the URL it is on. */
  applyGate() {
    if (this.closed || this.providerView.webContents.isDestroyed()) return;
    const mode = this.mode();
    const url = this.providerView.webContents.getURL();
    const kind = url && url !== 'about:blank' ? classifyProviderUrl(this.provider, url) : 'external';
    const allowed = (mode === 'app' || mode === 'signin') && gateView(this.record.state(), kind) === 'provider' && Boolean(url) && url !== 'about:blank';
    if (allowed && !this.providerAttached) {
      this.win.contentView.addChildView(this.providerView);
      this.providerAttached = true;
    } else if (!allowed && this.providerAttached) {
      this.win.contentView.removeChildView(this.providerView);
      this.providerAttached = false;
    }
    this.layout();
    this.pushState();
  }

  /** Called whenever the login state may have changed. */
  stateChanged() {
    if (this.closed || this.providerView.webContents.isDestroyed()) return;
    const state = this.record.state();
    const url = this.providerView.webContents.getURL();
    if (state === 'signedOut') {
      // While signing out the (detached, invisible) view keeps running the provider's logout page.
      if (url && url !== 'about:blank' && !this.record.signingOut) this.blankProvider();
      this.preview = null;
      this.historyOpen = false;
    } else if (state === 'signedIn') {
      const kind = url && url !== 'about:blank' ? classifyProviderUrl(this.provider, url) : 'external';
      if (kind !== 'app') this.loadProvider(this.provider.appUrl);
    }
    this.applyGate();
  }

  /** @param {string} url */
  loadProvider(url) {
    this.providerUrl = url;
    const wc = this.providerView.webContents;
    if (wc.isDestroyed()) return Promise.resolve();
    const done = wc.loadURL(url).catch(() => {});
    this.applyGate();
    return done;
  }

  blankProvider() {
    this.providerUrl = '';
    if (!this.providerView.webContents.isDestroyed()) this.providerView.webContents.loadURL('about:blank').catch(() => {});
  }

  /** @param {string} url */
  onNavigate(url) {
    if (url === 'about:blank') return this.applyGate();
    this.manager.onProviderNavigate(this.record, url);
    this.applyGate();
  }

  startSignIn() {
    this.preview = null;
    this.historyOpen = false;
    this.loadProvider(this.provider.loginUrl);
  }

  // ---------------------------------------------------------------------------------------------
  // Handoff

  /**
   * Puts text into the provider's composer (never sends it).
   * @param {string} text
   * @param {import('../../types/contract').HandoffTarget} target
   */
  async deliver(text, target) {
    this.show();
    const plan = planHandoff(this.provider, text, target);
    if (plan.method === 'clipboard') {
      clipboard.writeText(text);
      this.showNotice('warning', this.app.t('agent.notice.clipboard'));
      return;
    }
    this.record.busy = true;
    this.manager.summaryChanged();
    try {
      if (plan.method === 'url') {
        await this.loadProvider(plan.url);
        this.showNotice('info', this.app.t('agent.notice.inserted'));
        return;
      }
      if (plan.url) await this.loadProvider(plan.url);
      if (this.record.state() !== 'signedIn') {
        this.manager.setPending(this.record, { label: this.app.t('action.ask'), run: () => this.deliver(text, target) });
        return;
      }
      const wc = this.providerView.webContents;
      const composer = data.injectScript('find-composer.js');
      const selectors = JSON.stringify(this.provider.composerSelectors);
      const found = await wc.executeJavaScriptInIsolatedWorld(ISOLATED_WORLD, [{ code: `(${composer})("focus", ${selectors}, 15000)` }], true);
      if (!found || !found.found) {
        clipboard.writeText(text);
        this.showNotice('warning', this.app.t('agent.notice.noComposer'));
        return;
      }
      this.win.focus();
      wc.focus();
      await wc.insertText(text);
      const after = await wc.executeJavaScriptInIsolatedWorld(ISOLATED_WORLD, [{ code: `(${composer})("measure", ${selectors}, 0)` }]);
      const expected = text.replace(/\r/g, '').length;
      if (!after || !after.found || after.length - found.length < expected * 0.9) {
        clipboard.writeText(text);
        this.showNotice('warning', this.app.t('agent.notice.partial'));
      } else {
        this.showNotice('success', this.app.t('agent.notice.inserted'));
      }
    } catch {
      clipboard.writeText(text);
      this.showNotice('warning', this.app.t('agent.notice.noComposer'));
    } finally {
      this.record.busy = false;
      this.manager.summaryChanged();
    }
  }

  /** @param {PreviewPayload} payload */
  showPreview(payload) {
    this.preview = payload;
    this.historyOpen = false;
    this.show();
    this.applyGate();
    emit(this.ui.webContents, 'agent', 'preview', payload);
  }

  closePreview() {
    this.preview = null;
    emit(this.ui.webContents, 'agent', 'preview', null);
    this.applyGate();
  }

  openHistory() {
    this.preview = null;
    this.historyOpen = true;
    this.show();
    this.applyGate();
  }

  closeHistory() {
    this.historyOpen = false;
    this.applyGate();
  }

  // ---------------------------------------------------------------------------------------------
  // Window

  show() {
    if (this.closed) return;
    const parent = this.app.windows.current();
    if (parent && !parent.closed) this.win.setParentWindow(parent.win);
    if (!this.win.isVisible()) this.win.show();
    this.win.focus();
    this.manager.summaryChanged();
  }

  hide() {
    if (!this.closed && this.win.isVisible()) this.win.hide();
    this.manager.summaryChanged();
  }

  isOpen() {
    return !this.closed && this.win.isVisible();
  }

  /** @param {boolean} pinned */
  setPinned(pinned) {
    this.pinned = Boolean(pinned);
    this.win.setAlwaysOnTop(this.pinned, 'floating');
    this.manager.saveLayout(this);
    this.pushState();
  }

  /** @param {number} px */
  setHeaderHeight(px) {
    const h = Math.max(0, Math.min(400, Math.round(Number(px) || 0)));
    if (h !== this.headerHeight) {
      this.headerHeight = h;
      this.layout();
    }
  }

  layout() {
    if (this.closed) return;
    const { width, height } = this.win.getContentBounds();
    this.ui.setBounds({ x: 0, y: 0, width, height });
    if (this.providerAttached) this.providerView.setBounds({ x: 1, y: this.headerHeight, width: Math.max(0, width - 2), height: Math.max(0, height - this.headerHeight - 1) });
  }

  /** @param {'move' | 'resize'} kind */
  startDrag(kind) {
    this.endDrag();
    const start = screen.getCursorScreenPoint();
    const b = this.win.getBounds();
    const started = Date.now();
    this.drag = setInterval(() => {
      if (this.closed || Date.now() - started > 60000) return this.endDrag();
      const p = screen.getCursorScreenPoint();
      const dx = p.x - start.x;
      const dy = p.y - start.y;
      if (kind === 'move') this.win.setPosition(b.x + dx, b.y + dy);
      else this.win.setSize(Math.max(MIN_SIZE.width, b.width + dx), Math.max(MIN_SIZE.height, b.height + dy));
    }, 16);
  }

  endDrag() {
    if (this.drag) clearInterval(this.drag);
    this.drag = null;
  }

  /**
   * @param {'info' | 'success' | 'warning' | 'error'} kind
   * @param {string} text
   */
  showNotice(kind, text) {
    const id = nextNotice++;
    this.notice = { id, kind, text, actionLabel: null };
    this.pushState();
    setTimeout(() => {
      if (this.notice && this.notice.id === id) {
        this.notice = null;
        this.pushState();
      }
    }, 6000);
  }

  /** @param {number} id */
  dismissNotice(id) {
    if (this.notice && this.notice.id === id) {
      this.notice = null;
      this.pushState();
    }
  }

  /** @param {Electron.Event} event @param {Electron.Input} input */
  beforeInput(event, input) {
    const w = this.app.windows.current();
    const command = shortcuts.match(input);
    if (!w || !command) return;
    // Only agent and window-level commands make sense inside an agent window.
    if (/^(agent:|askAll|analyzeHistory|toggleDock|newTab|newWindow|newPrivateWindow|history|downloads|settings)/.test(command) && shortcuts.run(w, command)) event.preventDefault();
  }

  /** @param {Electron.ContextMenuParams} p */
  providerContextMenu(p) {
    const t = this.app.t;
    /** @type {Electron.MenuItemConstructorOptions[]} */
    const items = [];
    if (p.linkURL) items.push({ label: t('ctx.openLinkTab'), click: () => this.manager.openInBrowser(p.linkURL) }, { label: t('ctx.copyLink'), click: () => clipboard.writeText(p.linkURL) }, { type: 'separator' });
    if (p.isEditable) {
      items.push({ label: t('ctx.cut'), role: 'cut' }, { label: t('ctx.copy'), role: 'copy' }, { label: t('ctx.paste'), role: 'paste' }, { label: t('ctx.selectAll'), role: 'selectAll' });
    } else if (p.selectionText) items.push({ label: t('ctx.copy'), role: 'copy' });
    if (!items.length) return;
    const b = this.providerView.getBounds();
    Menu.buildFromTemplate(items).popup({ window: /** @type {any} */ (this.win), x: b.x + p.x, y: b.y + p.y });
  }

  /** @returns {import('../../types/contract').AgentWindowState} */
  state() {
    if (this.providerView.webContents.isDestroyed()) this.replaceProviderView();
    const w = this.app.windows.current();
    const tab = w ? w.active : null;
    const tabState = tab ? tab.state() : null;
    const url = this.providerView.webContents.getURL();
    let host = '';
    try {
      host = url && url !== 'about:blank' ? new URL(url).host : '';
    } catch {
      host = '';
    }
    return {
      agent: this.manager.summary(this.record),
      mode: this.mode(),
      host,
      loading: this.loading,
      pinned: this.pinned,
      lockReason: this.record.lockReason,
      pendingLabel: this.record.pending ? this.record.pending.label : null,
      notice: this.notice,
      context: {
        private: Boolean(w && w.private),
        pageAvailable: Boolean(tabState && !tabState.internal && !tabState.crashed && /^(https?|file):/.test(tabState.url)),
        searchQuery: tabState && tabState.search ? tabState.search.query : null,
      },
    };
  }

  pushState() {
    if (this.closed || this.pushTimer) return;
    this.pushTimer = setTimeout(() => {
      this.pushTimer = null;
      if (!this.closed) emit(this.ui.webContents, 'agent', 'state', this.state());
    }, 16);
  }

  destroy() {
    if (this.closed) return;
    this.closed = true;
    this.endDrag();
    if (this.pushTimer) clearTimeout(this.pushTimer);
    for (const view of [this.ui, this.providerView]) if (!view.webContents.isDestroyed()) view.webContents.close();
    this.manager.windowClosed(this);
  }

  close() {
    if (!this.closed) this.win.close();
  }
}

/** @param {Electron.Rectangle} b */
function onScreen(b) {
  return screen.getAllDisplays().some((d) => {
    const a = d.workArea;
    return b.x + 80 > a.x && b.x + 80 < a.x + a.width && b.y + 20 > a.y && b.y + 20 < a.y + a.height;
  });
}

module.exports = { AgentWindow, onScreen };
