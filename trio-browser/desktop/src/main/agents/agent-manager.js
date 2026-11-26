'use strict';
/**
 * The three built-in agents: their login state (from the profile's cookies and the URLs their
 * windows reach), their floating windows, the dock, and every way context reaches them.
 * Invariant: an agent's chat app is shown, and text is handed to it, only in the signedIn state.
 */

const path = require('node:path');
const { app: electronApp } = require('electron');
const data = require('../../core/data');
const { hasSession, sessionDomains, classifyProviderUrl, deriveAgentState } = require('../../core/login-state');
const { buildPrompt } = require('../../core/prompts');
const { JsonStore, v } = require('../json-store');
const { AgentWindow } = require('./agent-window');
const { DockWindow } = require('./dock-window');
const { extractPage } = require('./page-context');
const { buildHistoryPreview } = require('./history-analysis');

/**
 * @typedef {import('../../types/contract').AgentId} AgentId
 * @typedef {import('../../types/contract').ActionId} ActionId
 * @typedef {import('../../types/contract').ProviderConfig} ProviderConfig
 * @typedef {import('../app-context').AppContext} AppContext
 * @typedef {{ label: string, run: () => void }} Pending
 * @typedef {{
 *   provider: ProviderConfig,
 *   sessionPresent: boolean,
 *   staleSession: boolean,
 *   signingIn: boolean,
 *   lockReason: 'signedOut' | 'signInIncomplete' | 'signInRefused',
 *   pending: Pending | null,
 *   signingOut: boolean,
 *   window: AgentWindow | null,
 *   busy: boolean,
 *   state: () => import('../../types/contract').AgentLoginState,
 * }} AgentRecord
 */

const PAGE_ACTIONS = new Set(['summarize_page', 'ask_page']);
const SELECTION_ACTIONS = new Set(['explain_selection', 'translate_selection']);
let nextPreviewId = 1;

class AgentManager {
  /**
   * @param {AppContext} app
   * @param {Electron.Session} session the browsing profile the agents share
   */
  constructor(app, session) {
    this.app = app;
    this.session = session;
    /** @type {Map<AgentId, AgentRecord>} */
    this.records = new Map();
    /** @type {AgentId[]} */
    this.order = [];
    for (const provider of data.providers()) {
      /** @type {AgentRecord} */
      const record = {
        provider,
        sessionPresent: false,
        staleSession: false,
        signingIn: false,
        lockReason: 'signedOut',
        pending: null,
        signingOut: false,
        window: null,
        busy: false,
        state: () => deriveAgentState(record),
      };
      this.records.set(provider.id, record);
      this.order.push(provider.id);
    }
    /** @type {DockWindow | null} */
    this.dock = null;
    /** @type {AgentId} */
    this.lastUsed = this.order[0];
    /** @type {Map<AgentId, NodeJS.Timeout>} */
    this.cookieTimers = new Map();
    this.layoutStore = new JsonStore(
      path.join(electronApp.getPath('userData'), 'agents.json'),
      () => ({ windows: /** @type {Record<string, { bounds: Electron.Rectangle | null, pinned: boolean }>} */ ({}), dock: /** @type {{ x: number, y: number } | null} */ (null) }),
      (raw) => {
        const r = v.obj(raw);
        /** @type {Record<string, { bounds: Electron.Rectangle | null, pinned: boolean }>} */
        const windows = {};
        for (const [id, w] of Object.entries(v.obj(r.windows))) {
          const b = v.obj(v.obj(w).bounds);
          const ok = ['x', 'y', 'width', 'height'].every((k) => Number.isFinite(b[k]));
          windows[id] = { bounds: ok ? { x: b.x, y: b.y, width: b.width, height: b.height } : null, pinned: v.bool(v.obj(w).pinned, false) };
        }
        const d = v.obj(r.dock);
        return { windows, dock: Number.isFinite(d.x) && Number.isFinite(d.y) ? { x: d.x, y: d.y } : null };
      },
      { delayMs: 800 },
    );
  }

  async init() {
    await Promise.all([...this.records.values()].map((r) => this.refreshSession(r)));
    this.session.cookies.on('changed', (_e, cookie) => {
      for (const r of this.records.values()) {
        if (sessionDomains(r.provider).some((d) => cookie.domain && (cookie.domain.replace(/^\./, '') === d || cookie.domain.endsWith('.' + d)))) this.scheduleRefresh(r);
      }
    });
  }

  /** @param {AgentRecord} r */
  scheduleRefresh(r) {
    const id = r.provider.id;
    const existing = this.cookieTimers.get(id);
    if (existing) clearTimeout(existing);
    this.cookieTimers.set(
      id,
      setTimeout(() => {
        this.cookieTimers.delete(id);
        r.staleSession = false;
        this.refreshSession(r);
      }, 250),
    );
  }

  /** Re-reads the provider's cookies and applies the resulting state. @param {AgentRecord} r */
  async refreshSession(r) {
    const before = r.state();
    const domains = sessionDomains(r.provider);
    const lists = await Promise.all(domains.map((domain) => this.session.cookies.get({ domain }).catch(() => [])));
    const cookies = lists.flat().map((c) => ({ domain: c.domain || '', name: c.name, value: c.value, expirationDate: c.expirationDate }));
    r.sessionPresent = hasSession(r.provider, cookies, Date.now());
    if (r.sessionPresent && !r.staleSession) r.signingIn = false;
    this.afterStateChange(r, before);
  }

  /**
   * @param {AgentRecord} r
   * @param {import('../../types/contract').AgentLoginState} before
   */
  afterStateChange(r, before) {
    const now = r.state();
    if (now === 'signedOut' && before !== 'signedOut' && r.lockReason === 'signInIncomplete') {
      /* keep the reason shown by the lock screen */
    } else if (now !== 'signedOut') r.lockReason = 'signedOut';
    if (r.window) r.window.stateChanged();
    if (now === 'signedIn' && before !== 'signedIn' && r.pending) {
      const p = r.pending;
      r.pending = null;
      setTimeout(() => p.run(), 0);
    }
    this.summaryChanged();
  }

  /**
   * Called for every main-frame navigation of an agent's provider view.
   * @param {AgentRecord} r
   * @param {string} url
   */
  async onProviderNavigate(r, url) {
    const kind = classifyProviderUrl(r.provider, url);
    const before = r.state();
    if (before === 'signedIn' && kind === 'auth') {
      r.staleSession = true;
      if (r.signingOut) {
        // The user signed out: lock instead of showing the provider's sign-in page.
        r.signingOut = false;
        r.signingIn = false;
      } else {
        // The app sent us to a sign-in page: the session is not valid (or the user is switching accounts).
        r.signingIn = true;
      }
      return this.afterStateChange(r, before);
    }
    if (before === 'signingIn' && kind === 'app') {
      await this.refreshSession(r);
      if (r.state() !== 'signedIn') {
        await new Promise((res) => setTimeout(res, 1500));
        r.staleSession = false;
        await this.refreshSession(r);
      }
      if (r.state() !== 'signedIn') {
        const was = r.state();
        r.signingIn = false;
        r.lockReason = 'signInIncomplete';
        if (r.window) r.window.blankProvider();
        this.afterStateChange(r, was);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Summaries and windows

  /** @param {AgentRecord} r @returns {import('../../types/contract').AgentSummary} */
  summary(r) {
    const p = r.provider;
    return {
      id: p.id,
      name: p.name,
      vendor: p.vendor,
      monogram: p.monogram,
      accent: p.accent,
      enabled: this.app.settings.get().agents.enabled[p.id] !== false,
      state: r.state(),
      open: Boolean(r.window && r.window.isOpen()),
      busy: r.busy || Boolean(r.window && r.window.loading && r.window.isOpen()),
    };
  }

  summaries() {
    return this.order.map((id) => this.summary(/** @type {AgentRecord} */ (this.records.get(id))));
  }

  summaryChanged() {
    this.app.windows.refreshAll();
    if (this.dock) this.dock.pushState();
    for (const r of this.records.values()) r.window?.pushState();
    this.app.pagesChanged('agents');
  }

  contextChanged() {
    for (const r of this.records.values()) r.window?.pushState();
    this.syncDock();
    if (this.dock) this.dock.pushState();
  }

  windowsChanged() {
    const current = this.app.windows.current();
    if (!current) {
      for (const r of this.records.values()) r.window?.close();
      this.dock?.close();
      return;
    }
    for (const r of this.records.values()) if (r.window && r.window.isOpen()) r.window.win.setParentWindow(current.win);
    this.syncDock();
  }

  /** @param {AgentId} id */
  record(id) {
    const r = this.records.get(id);
    if (!r) throw new Error(`Unknown agent ${id}`);
    return r;
  }

  /** @param {AgentRecord} r */
  ensureWindow(r) {
    if (!r.window || r.window.closed) {
      const saved = this.layoutStore.get().windows[r.provider.id];
      r.window = new AgentWindow(this, r, { bounds: saved ? saved.bounds : null, pinned: saved ? saved.pinned : false });
      if (r.state() === 'signedIn') r.window.loadProvider(r.provider.appUrl);
      else if (r.state() === 'signingIn') r.window.startSignIn();
    }
    return r.window;
  }

  /** @param {AgentWindow} w */
  windowClosed(w) {
    const r = w.record;
    if (r.window === w) r.window = null;
    r.signingIn = false;
    r.pending = null;
    this.summaryChanged();
  }

  /** @param {AgentWindow} w */
  saveLayout(w) {
    if (w.closed) return;
    const s = this.layoutStore.get();
    this.layoutStore.set({ ...s, windows: { ...s.windows, [w.provider.id]: { bounds: w.win.getBounds(), pinned: w.pinned } } });
  }

  /**
   * Opens (or hides) an agent window.
   * @param {AgentId} id
   * @param {import('../browser-window').BrowserWin} [from]
   */
  toggle(id, from) {
    const r = this.record(id);
    if (from && from.private) return;
    if (r.window && r.window.isOpen()) {
      r.window.hide();
      return;
    }
    this.open(id);
  }

  /** @param {AgentId} id */
  open(id) {
    const r = this.record(id);
    this.lastUsed = id;
    const w = this.ensureWindow(r);
    w.show();
    return w;
  }

  // ---------------------------------------------------------------------------------------------
  // Sign-in

  /** @param {AgentId} id */
  signIn(id) {
    const r = this.record(id);
    const before = r.state();
    r.signingIn = true;
    r.lockReason = 'signedOut';
    const w = this.ensureWindow(r);
    w.show();
    w.startSignIn();
    this.afterStateChange(r, before);
  }

  /** @param {AgentId} id */
  cancelSignIn(id) {
    const r = this.record(id);
    const before = r.state();
    r.signingIn = false;
    r.pending = null;
    r.window?.blankProvider();
    this.afterStateChange(r, before);
  }

  /** @param {AgentId} id */
  signInInTab(id) {
    const r = this.record(id);
    const w = this.app.windows.current() || this.app.windows.create({});
    if (w.private) return;
    w.addTab(r.provider.loginUrl);
    r.window?.showNotice('info', this.app.t('agent.notice.signInTab', { name: r.provider.name }));
  }

  /** @param {AgentId} id */
  signOut(id) {
    const r = this.record(id);
    const w = this.ensureWindow(r);
    if (r.provider.logoutUrl) {
      r.signingOut = true;
      w.loadProvider(r.provider.logoutUrl);
    }
    else w.showNotice('info', this.app.t('agent.notice.signOutInApp', { name: r.provider.name, vendor: r.provider.vendor }));
  }

  /**
   * Remembers a request to run once the agent is signed in, and shows its lock screen.
   * @param {AgentRecord} r
   * @param {Pending} pending
   */
  setPending(r, pending) {
    r.pending = pending;
    const w = this.ensureWindow(r);
    w.show();
    w.pushState();
  }

  // ---------------------------------------------------------------------------------------------
  // Context handoff

  /**
   * Hands text to an agent: directly when signed in, after sign-in otherwise.
   * @param {AgentId} id
   * @param {string} text
   * @param {import('../../types/contract').HandoffTarget} target
   * @param {string} label shown on the lock screen while waiting
   */
  handoff(id, text, target, label) {
    const r = this.record(id);
    this.lastUsed = id;
    if (r.state() !== 'signedIn') {
      this.setPending(r, { label, run: () => this.handoff(id, text, target, label) });
      return;
    }
    this.ensureWindow(r).deliver(text, target);
  }

  /** @param {import('../../types/contract').AgentTarget} target */
  targets(target) {
    const enabled = this.summaries().filter((a) => a.enabled).map((a) => a.id);
    return target === 'all' ? enabled : enabled.includes(target) ? [target] : [];
  }

  /**
   * A question typed by the user (address bar, "ask all").
   * @param {import('../../types/contract').AgentTarget} target
   * @param {string} query
   * @param {import('../browser-window').BrowserWin} from
   */
  ask(target, query, from) {
    if (from.private) return;
    const ids = this.targets(target);
    if (target === 'all') this.tile(ids);
    for (const id of ids) {
      if (!query) {
        this.open(id);
        continue;
      }
      const prompt = buildPrompt(data.prompts(), 'ask', { question: query }, { locale: this.promptLocale(), charLimit: 100000 });
      this.handoff(id, prompt.text, 'new', this.app.t('action.ask'));
    }
  }

  promptLocale() {
    const p = this.app.settings.get().agents.promptLanguage;
    return p === 'ui' ? this.app.locale() : p;
  }

  /**
   * Runs a quick action against the active tab of the given (or current) browser window.
   * @param {AgentId} id
   * @param {ActionId} actionId
   * @param {{ question?: string }} options
   * @param {import('../browser-window').BrowserWin} [from]
   * @param {string} [selectionOverride] selection text from a context menu
   */
  async runAction(id, actionId, options = {}, from, selectionOverride) {
    const r = this.record(id);
    const t = this.app.t;
    const w = from || this.app.windows.current();
    this.lastUsed = id;
    const agentWin = this.ensureWindow(r);
    if (actionId.startsWith('history_')) return this.openHistoryAnalysis(id, w || undefined);
    if (!w || w.private) {
      agentWin.show();
      agentWin.showNotice('warning', t('notice.privateNoAgents'));
      return;
    }
    const settings = this.app.settings.get().agents;
    const locale = this.promptLocale();
    const tab = w.active;
    const tabState = tab ? tab.state() : null;
    /** @type {import('../../core/prompts').PromptInput} */
    let input = { question: options.question || '' };
    let source = '';
    let preview = false;
    let origin = /** @type {'typed' | 'context'} */ ('typed');

    if (actionId === 'search_page') {
      const q = tabState && tabState.search ? tabState.search.query : '';
      if (!q) {
        agentWin.show();
        agentWin.showNotice('warning', t('agent.notice.noSearch'));
        return;
      }
      input = { query: q };
      source = t('agent.source.search', { query: q, engine: tabState && tabState.search ? tabState.search.engineName : '' });
      preview = settings.preview.search;
      origin = 'context';
    } else if (PAGE_ACTIONS.has(actionId) || SELECTION_ACTIONS.has(actionId)) {
      if (!tab || !tabState || tabState.internal || !/^(https?|file):/.test(tabState.url)) {
        agentWin.show();
        agentWin.showNotice('warning', t('agent.notice.pageUnavailable'));
        return;
      }
      const page = await extractPage(tab.wc, data.injectScript('extract-page.js'));
      if (!page) {
        agentWin.show();
        agentWin.showNotice('warning', t('agent.notice.pageUnavailable'));
        return;
      }
      let host = '';
      try {
        host = new URL(page.url).host;
      } catch {
        host = '';
      }
      if (SELECTION_ACTIONS.has(actionId)) {
        const sel = (selectionOverride || page.selection || '').trim();
        if (!sel) {
          agentWin.show();
          agentWin.showNotice('warning', t('agent.notice.noSelection'));
          return;
        }
        input = { ...input, title: page.title, url: page.url, content: sel };
        source = t('agent.source.selection', { title: page.title || host, host });
        preview = settings.preview.selection;
      } else {
        if (!page.text.trim()) {
          agentWin.show();
          agentWin.showNotice('warning', t('agent.notice.pageEmpty'));
          return;
        }
        input = { ...input, title: page.title, url: page.url, content: page.text };
        source = t('agent.source.page', { title: page.title || host, host });
        preview = settings.preview.page;
      }
      origin = 'context';
    }

    const prompt = buildPrompt(data.prompts(), actionId, input, { locale, charLimit: settings.pageCharLimit });
    const label = t(`action.${actionId}`);
    const go = () => {
      if (preview && origin === 'context') {
        this.ensureWindow(r).showPreview({ id: nextPreviewId++, actionId, source, text: prompt.text, truncated: prompt.truncated, counts: null, historyOptions: null });
      } else {
        this.ensureWindow(r).deliver(prompt.text, 'new');
      }
    };
    if (r.state() !== 'signedIn') this.setPending(r, { label, run: go });
    else go();
  }

  /**
   * @param {AgentWindow} w
   * @param {number} id
   * @param {string} text
   * @param {{ target: import('../../types/contract').HandoffTarget, skipNextTime: boolean }} options
   */
  confirmPreview(w, id, text, options) {
    const p = w.preview;
    if (!p || p.id !== id) return;
    if (options.skipNextTime) {
      const key = SELECTION_ACTIONS.has(p.actionId) ? 'selection' : p.actionId === 'search_page' ? 'search' : 'page';
      this.app.settings.set(`agents.preview.${key}`, false);
    }
    w.closePreview();
    const body = String(text || '').trim();
    if (body) w.deliver(body, options.target === 'current' ? 'current' : 'new');
  }

  // ---------------------------------------------------------------------------------------------
  // History analysis

  /**
   * @param {AgentId} [id]
   * @param {import('../browser-window').BrowserWin} [from]
   */
  openHistoryAnalysis(id, from) {
    if (from && from.private) return;
    const agentId = id || this.preferredAgent();
    const r = this.record(agentId);
    this.lastUsed = agentId;
    const open = () => this.ensureWindow(r).openHistory();
    if (r.state() !== 'signedIn') this.setPending(r, { label: this.app.t('action.history'), run: open });
    else open();
  }

  preferredAgent() {
    const last = this.records.get(this.lastUsed);
    if (last && last.state() === 'signedIn') return this.lastUsed;
    const signed = this.order.find((id) => this.record(id).state() === 'signedIn' && this.app.settings.get().agents.enabled[id]);
    return signed || this.lastUsed;
  }

  /** @param {import('../../types/contract').HistoryOptions} options */
  previewHistory(options) {
    return buildHistoryPreview(this.app, options, nextPreviewId++, this.promptLocale());
  }

  /**
   * @param {AgentWindow} w
   * @param {string} text
   * @param {boolean} toAll
   */
  sendHistory(w, text, toAll) {
    const body = String(text || '').trim();
    if (!body) return;
    w.closeHistory();
    const ids = toAll ? this.targets('all').filter((id) => this.record(id).state() === 'signedIn') : [w.provider.id];
    if (toAll) this.tile(ids);
    for (const id of ids) this.handoff(id, body, 'new', this.app.t('action.history'));
  }

  // ---------------------------------------------------------------------------------------------
  // Layout helpers, dock, links

  /** Places several agent windows side by side for comparing answers. @param {AgentId[]} ids */
  tile(ids) {
    if (ids.length < 2) return;
    const { screen } = require('electron');
    const current = this.app.windows.current();
    const display = current ? screen.getDisplayMatching(current.win.getBounds()) : screen.getPrimaryDisplay();
    const a = display.workArea;
    const gap = 12;
    const width = Math.max(340, Math.floor((a.width - gap * (ids.length + 1)) / ids.length));
    const height = Math.min(760, a.height - 2 * gap);
    ids.forEach((id, i) => {
      const w = this.ensureWindow(this.record(id));
      w.win.setBounds({ x: a.x + gap + i * (width + gap), y: a.y + Math.max(gap, Math.floor((a.height - height) / 2)), width, height });
      w.show();
    });
  }

  /** @param {string} url */
  openInBrowser(url) {
    const w = this.app.windows.current() || this.app.windows.create({});
    w.addTab(url);
    w.win.focus();
  }

  dockVisible() {
    return this.app.settings.get().agents.dockVisible;
  }

  /** @param {boolean} visible */
  setDockVisible(visible) {
    this.app.settings.set('agents.dockVisible', Boolean(visible));
    this.syncDock();
    this.app.windows.refreshAll();
  }

  syncDock() {
    const current = this.app.windows.current();
    const want = this.dockVisible() && current && !current.private;
    if (want && current) {
      if (!this.dock || this.dock.closed) this.dock = new DockWindow(this, this.layoutStore.get().dock);
      this.dock.attach(current);
    } else if (this.dock && !this.dock.closed) {
      this.dock.hide();
    }
  }

  /** @param {{ x: number, y: number }} position */
  saveDockPosition(position) {
    this.layoutStore.set({ ...this.layoutStore.get(), dock: position });
  }

  flush() {
    this.layoutStore.flush();
  }
}

module.exports = { AgentManager };
