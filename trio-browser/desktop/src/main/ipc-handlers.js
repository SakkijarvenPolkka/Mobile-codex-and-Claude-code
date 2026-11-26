'use strict';
/**
 * Implements every surface API (see contract.d.ts) on top of the window, agent and store
 * modules. Arguments come from renderer code and are validated here before use.
 */

const { app: electronApp, dialog, session: electronSession } = require('electron');
const { handleSurface } = require('./ipc');
const omnibox = require('./omnibox');
const menus = require('./menus');
const data = require('../core/data');
const { engineById } = require('../core/search');
const { internalHost } = require('./protocol');

/** @param {unknown} x */
const str = (x, max = 10000) => (typeof x === 'string' ? x.slice(0, max) : '');
/** @param {unknown} x */
const int = (x) => (Number.isInteger(x) ? /** @type {number} */ (x) : -1);
/** @param {unknown} p @returns {{ x: number, y: number } | undefined} */
const point = (p) => {
  const o = /** @type {any} */ (p);
  return o && Number.isFinite(o.x) && Number.isFinite(o.y) ? { x: o.x, y: o.y } : undefined;
};
const AGENTS = ['claude', 'gemini', 'chatgpt'];
/** @param {unknown} id @returns {import('../types/contract').AgentId} */
function agentId(id) {
  if (typeof id !== 'string' || !AGENTS.includes(id)) throw new Error('Unknown agent');
  return /** @type {any} */ (id);
}

/**
 * @param {import('./app-context').AppContext} app
 */
function installHandlers(app) {
  /** @typedef {import('./browser-window').BrowserWin} W */
  handleSurface('chrome', {
    init: (/** @type {W} */ w) => ({ ...app.boot(), state: w.state() }),
    newTab: (/** @type {W} */ w, opts) => {
      const url = opts && typeof opts.url === 'string' ? opts.url : 'trio://newtab/';
      w.addTab(url.startsWith('trio://') || /^https?:/.test(url) ? url : 'trio://newtab/');
      if (url === 'trio://newtab/') w.focusAddressBar();
    },
    closeTab: (/** @type {W} */ w, id) => w.closeTab(int(id)),
    activateTab: (/** @type {W} */ w, id) => {
      const tab = w.tabs.find((t) => t.id === int(id));
      if (tab) w.activate(tab);
    },
    moveTab: (/** @type {W} */ w, id, to) => w.moveTab(int(id), int(to)),
    setTabMuted: (/** @type {W} */ w, id, muted) => {
      const tab = w.tabs.find((t) => t.id === int(id));
      if (tab) {
        tab.wc.setAudioMuted(Boolean(muted));
        w.pushState();
      }
    },
    showTabMenu: (/** @type {W} */ w, id, at) => menus.tabMenu(w, int(id), point(at) || { x: 0, y: 0 }),
    navigate: (/** @type {W} */ w, input, opts) => w.navigate(str(input), { newTab: Boolean(opts && opts.newTab) }),
    acceptSuggestion: (/** @type {W} */ w, s, opts) => {
      const a = s && s.action;
      if (!a || typeof a !== 'object') return;
      /** @type {import('../types/contract').Suggestion} */
      let clean;
      if (a.type === 'navigate' && typeof a.url === 'string') {
        // Re-classify so a crafted suggestion cannot open javascript: or data: URLs.
        return w.navigate(a.url, { newTab: Boolean(opts && opts.newTab) });
      } else if (a.type === 'search') clean = { ...s, action: { type: 'search', query: str(a.query) } };
      else if (a.type === 'agent' && (a.agentId === 'all' || AGENTS.includes(a.agentId))) clean = { ...s, action: { type: 'agent', agentId: a.agentId, query: str(a.query) } };
      else if (a.type === 'history') clean = { ...s, action: { type: 'history', query: str(a.query) } };
      else return;
      w.acceptSuggestion(clean, { newTab: Boolean(opts && opts.newTab) });
    },
    back: (/** @type {W} */ w) => w.back(),
    forward: (/** @type {W} */ w) => w.forward(),
    reload: (/** @type {W} */ w, opts) => w.reload({ hard: Boolean(opts && opts.hard) }),
    stop: (/** @type {W} */ w) => w.stop(),
    suggest: (/** @type {W} */ w, text) => omnibox.suggest(w, str(text, 2000)),
    setOverlay: (/** @type {W} */ w, open) => w.setOverlay(Boolean(open)),
    setChromeHeight: (/** @type {W} */ w, px) => w.setChromeHeight(Number(px)),
    find: (/** @type {W} */ w, req) => {
      const action = req && ['search', 'next', 'previous', 'close'].includes(req.action) ? req.action : null;
      if (action) w.find({ action, text: str(req.text, 1000) });
    },
    toggleBookmark: (/** @type {W} */ w) => w.toggleBookmark(),
    openBookmark: (/** @type {W} */ w, id, opts) => w.openBookmark(str(id), { newTab: Boolean(opts && opts.newTab) }),
    showBookmarkMenu: (/** @type {W} */ w, id, at) => menus.bookmarkMenu(w, str(id), point(at) || { x: 0, y: 0 }),
    respondPermission: (/** @type {W} */ w, id, decision, remember) => w.respondPermission(int(id), decision === 'allow' ? 'allow' : 'deny', Boolean(remember)),
    dismissNotice: (/** @type {W} */ w, id, run) => w.dismissNotice(int(id), Boolean(run)),
    toggleAgent: (/** @type {W} */ w, id) => app.agents.toggle(agentId(id), w),
    toggleDock: (/** @type {W} */ w) => {
      if (!w.private) app.agents.setDockVisible(!app.agents.dockVisible());
    },
    askAboutSearch: (/** @type {W} */ w, id) => {
      if (w.private) return;
      app.agents.runAction(id ? agentId(id) : app.agents.preferredAgent(), 'search_page', {}, w);
    },
    showAppMenu: (/** @type {W} */ w, at) => menus.appMenu(w, point(at) || { x: 0, y: 0 }),
    openDownloads: (/** @type {W} */ w) => {
      app.downloads.markSeen();
      w.openUrl('trio://downloads/', { newTab: true });
    },
  });

  /** @typedef {import('./agents/agent-window').AgentWindow} A */
  const quickActions = [
    { id: 'summarize_page', labelKey: 'action.summarize_page', needsQuestion: false },
    { id: 'ask_page', labelKey: 'action.ask_page', needsQuestion: true },
    { id: 'explain_selection', labelKey: 'action.explain_selection', needsQuestion: false },
    { id: 'translate_selection', labelKey: 'action.translate_selection', needsQuestion: false },
    { id: 'search_page', labelKey: 'action.search_page', needsQuestion: false },
    { id: 'history_habits', labelKey: 'action.history', needsQuestion: false },
  ];
  const ACTION_IDS = new Set(['ask', 'search', 'search_page', 'summarize_page', 'ask_page', 'explain_selection', 'translate_selection', 'history_habits', 'history_topics', 'history_find', 'history_time', 'history_custom']);
  handleSurface('agent', {
    init: (/** @type {A} */ a) => ({ ...app.boot(), state: a.state(), actions: quickActions }),
    signIn: (/** @type {A} */ a) => app.agents.signIn(a.provider.id),
    cancelSignIn: (/** @type {A} */ a) => app.agents.cancelSignIn(a.provider.id),
    signInInTab: (/** @type {A} */ a) => app.agents.signInInTab(a.provider.id),
    signOut: (/** @type {A} */ a) => app.agents.signOut(a.provider.id),
    runAction: (/** @type {A} */ a, actionId, opts) => {
      if (typeof actionId !== 'string' || !ACTION_IDS.has(actionId)) return;
      app.agents.runAction(a.provider.id, /** @type {any} */ (actionId), { question: str(opts && opts.question, 4000) });
    },
    confirmPreview: (/** @type {A} */ a, id, text, opts) =>
      app.agents.confirmPreview(a, int(id), str(text, 200000), { target: opts && opts.target === 'current' ? 'current' : 'new', skipNextTime: Boolean(opts && opts.skipNextTime) }),
    cancelPreview: (/** @type {A} */ a) => a.closePreview(),
    openHistoryAnalysis: (/** @type {A} */ a) => app.agents.openHistoryAnalysis(a.provider.id),
    previewHistory: (/** @type {A} */ _a, options) => app.agents.previewHistory(options),
    sendHistory: (/** @type {A} */ a, _id, text, opts) => app.agents.sendHistory(a, str(text, 200000), Boolean(opts && opts.toAll)),
    closeHistoryAnalysis: (/** @type {A} */ a) => a.closeHistory(),
    newChat: (/** @type {A} */ a) => {
      if (a.record.state() === 'signedIn') a.loadProvider(a.provider.appUrl);
    },
    reloadProvider: (/** @type {A} */ a) => {
      if (a.record.state() !== 'signedOut') a.providerView.webContents.reload();
    },
    openInTab: (/** @type {A} */ a) => {
      const url = a.providerView.webContents.getURL();
      app.agents.openInBrowser(url && url !== 'about:blank' ? url : a.provider.appUrl);
    },
    setPinned: (/** @type {A} */ a, pinned) => a.setPinned(Boolean(pinned)),
    minimize: (/** @type {A} */ a) => a.hide(),
    close: (/** @type {A} */ a) => a.hide(),
    showMenu: (/** @type {A} */ a, at) => agentMenu(app, a, point(at) || { x: 0, y: 0 }),
    dismissNotice: (/** @type {A} */ a, id) => a.dismissNotice(int(id)),
    setHeaderHeight: (/** @type {A} */ a, px) => a.setHeaderHeight(Number(px)),
    startMove: (/** @type {A} */ a) => a.startDrag('move'),
    startResize: (/** @type {A} */ a) => a.startDrag('resize'),
    endDrag: (/** @type {A} */ a) => a.endDrag(),
  });

  /** @typedef {import('./agents/dock-window').DockWindow} D */
  handleSurface('dock', {
    init: (/** @type {D} */ d) => ({ ...app.boot(), state: d.state() }),
    toggleAgent: (/** @type {D} */ _d, id) => app.agents.toggle(agentId(id), app.windows.current() || undefined),
    showMenu: (/** @type {D} */ d, at) => d.showMenu(point(at) || { x: 0, y: 0 }),
    setSize: (/** @type {D} */ d, w, h) => d.setSize(Number(w), Number(h)),
    startMove: (/** @type {D} */ d) => d.startMove(),
    endDrag: (/** @type {D} */ d) => d.endDrag(),
  });

  /** The tab (and window) an internal page is shown in. @param {import('./tab').Tab & { win: W }} p */
  handleSurface('pages', {
    init: (/** @type {any} */ p) => ({ ...app.boot(), page: internalHost(p.tab.wc.getURL()) || 'newtab', private: p.win.private }),
    navigate: (/** @type {any} */ p, input, opts) => {
      if (opts && opts.newTab) p.win.navigate(str(input), { newTab: true });
      else {
        p.win.activate(p.tab);
        p.win.navigate(str(input));
      }
    },
    getNewTabData: (/** @type {any} */ p) => ({
      topSites: p.win.private || !app.history ? [] : app.history.topSites(Date.now(), 8),
      agents: p.win.private ? [] : app.agents.summaries().filter((a) => a.enabled),
      searchEngineName: engineById(data.searchEngines(), app.settings.get().search.engine).name,
    }),
    toggleAgent: (/** @type {any} */ p, id) => app.agents.toggle(agentId(id), p.win),
    queryHistory: (/** @type {any} */ _p, q) =>
      app.history ? app.history.query({ text: str(q && q.text, 500), beforeTime: q && Number.isFinite(q.beforeTime) ? q.beforeTime : null, limit: Number.isInteger(q && q.limit) ? q.limit : 100 }) : { entries: [], hasMore: false },
    deleteHistory: (/** @type {any} */ _p, sel) => {
      if (!app.history || !sel) return;
      if (Array.isArray(sel.visitIds)) app.history.deleteVisits(sel.visitIds.filter(Number.isInteger).slice(0, 10000));
      else if (typeof sel.range === 'string') clearHistoryRange(app, sel.range);
      app.historyChanged();
    },
    analyzeHistory: (/** @type {any} */ p, id) => app.agents.openHistoryAnalysis(id ? agentId(id) : undefined, p.win),
    getBookmarks: () => app.bookmarks.list().map((b) => ({ id: b.id, title: b.title, url: b.url })),
    addBookmark: (_p, b) => {
      app.bookmarks.add({ title: str(b && b.title, 500), url: str(b && b.url, 5000) });
    },
    updateBookmark: (_p, id, patch) => {
      app.bookmarks.update(str(id), { ...(patch && typeof patch.title === 'string' ? { title: patch.title } : {}), ...(patch && typeof patch.url === 'string' ? { url: patch.url } : {}) });
    },
    removeBookmark: (_p, id) => app.bookmarks.remove(str(id)),
    moveBookmark: (_p, id, to) => app.bookmarks.move(str(id), int(to)),
    getDownloads: () => {
      app.downloads.markSeen();
      return app.downloads.list();
    },
    downloadAction: (_p, id, action) => {
      if (['open', 'show', 'cancel', 'pause', 'resume', 'remove'].includes(action)) return app.downloads.act(str(id), action);
    },
    clearDownloads: () => app.downloads.clearFinished(),
    getSettings: () => app.settings.get(),
    setSetting: (_p, key, value) => app.settings.set(str(key, 200), value),
    getSearchEngines: () => data.searchEngines().engines.map((/** @type {any} */ e) => ({ id: e.id, name: e.name })),
    getAgents: () => app.agents.summaries(),
    clearBrowsingData: async (/** @type {any} */ p, req) => {
      if (!req || typeof req !== 'object') return;
      const range = ['hour', 'day', 'week', 'month', 'all'].includes(req.range) ? req.range : 'hour';
      if (req.history && app.history) {
        clearHistoryRange(app, range);
        app.historyChanged();
      }
      const ses = p.win.session;
      if (req.cache) await ses.clearCache();
      if (req.cookies) await ses.clearStorageData({ storages: ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'cachestorage', 'websql', 'filesystem', 'shadercache'] });
    },
    chooseDownloadDirectory: async (/** @type {any} */ p) => {
      const res = await dialog.showOpenDialog(/** @type {any} */ (p.win.win), { properties: ['openDirectory', 'createDirectory'] });
      if (!res.canceled && res.filePaths[0]) return app.settings.set('downloads.directory', res.filePaths[0]);
      return app.settings.get();
    },
    getAbout: () => ({
      appName: 'Trio',
      appVersion: electronApp.getVersion(),
      electron: process.versions.electron,
      chromium: process.versions.chrome,
      node: process.versions.node,
      platform: `${process.platform} ${process.arch}`,
    }),
    retry: (/** @type {any} */ p) => {
      try {
        const url = new URL(p.tab.wc.getURL()).searchParams.get('url');
        if (url && /^(https?|file):/.test(url)) p.tab.load(url, 'reload');
      } catch {
        /* nothing to retry */
      }
    },
  });
  void electronSession;
}

/** @param {import('./app-context').AppContext} app @param {string} range */
function clearHistoryRange(app, range) {
  if (!app.history) return;
  const H = 3600 * 1000;
  const spans = /** @type {Record<string, number>} */ ({ hour: H, day: 24 * H, week: 7 * 24 * H, month: 30 * 24 * H });
  if (range === 'all') app.history.deleteAll();
  else if (spans[range]) app.history.deleteSince(Date.now() - spans[range]);
}

/**
 * @param {import('./app-context').AppContext} app
 * @param {import('./agents/agent-window').AgentWindow} a
 * @param {{ x: number, y: number }} at
 */
function agentMenu(app, a, at) {
  const t = app.t;
  const signedIn = a.record.state() === 'signedIn';
  /** @type {Electron.MenuItemConstructorOptions[]} */
  const items = [
    { label: t('agent.menu.newChat'), enabled: signedIn, click: () => a.loadProvider(a.provider.appUrl) },
    { label: t('agent.menu.reload'), enabled: a.record.state() !== 'signedOut', click: () => a.providerView.webContents.reload() },
    { label: t('agent.menu.openInTab'), click: () => app.agents.openInBrowser(signedIn ? a.providerView.webContents.getURL() || a.provider.appUrl : a.provider.appUrl) },
    { type: 'separator' },
    { label: t('menu.analyzeHistory'), click: () => app.agents.openHistoryAnalysis(a.provider.id) },
    { label: a.pinned ? t('agent.menu.unpin') : t('agent.menu.pin'), click: () => a.setPinned(!a.pinned) },
    { type: 'separator' },
    signedIn
      ? { label: t('agent.menu.signOut', { name: a.provider.name }), click: () => app.agents.signOut(a.provider.id) }
      : { label: t('agent.menu.signIn', { name: a.provider.name }), click: () => app.agents.signIn(a.provider.id) },
    { label: t('agent.menu.close'), click: () => a.hide() },
  ];
  menus.popup(a.win, items, at);
}

module.exports = { installHandlers };
