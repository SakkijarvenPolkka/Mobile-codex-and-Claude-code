'use strict';
/** All browser windows: creation, focus tracking, session save/restore and closed tabs. */

const { app: electronApp, session: electronSession } = require('electron');
const { BrowserWin } = require('./browser-window');
const { handleScheme } = require('./protocol');
const { installPermissionHandlers } = require('./permissions');

/** @typedef {import('./app-context').AppContext} AppContext */

let privateCounter = 0;

class WindowManager {
  /**
   * @param {AppContext} app
   * @param {{ configureSession: (ses: Electron.Session, isPrivate: boolean) => void }} deps
   */
  constructor(app, deps) {
    this.app = app;
    this.deps = deps;
    /** @type {BrowserWin[]} */
    this.list = [];
    /** @type {BrowserWin | null} */
    this.last = null;
    /** @type {{ url: string, title: string }[]} */
    this.closedTabs = [];
    this.quitting = false;
  }

  /** @param {import('./browser-window').WindowOptions} options */
  create(options = {}) {
    let ses = electronSession.defaultSession;
    if (options.private) {
      ses = electronSession.fromPartition(`trio-private-${++privateCounter}`, { cache: false });
      this.deps.configureSession(ses, true);
    }
    const w = new BrowserWin(this.app, ses, options);
    this.list.push(w);
    this.last = w;
    this.app.agents.windowsChanged();
    return w;
  }

  /** @param {BrowserWin} w */
  focused(w) {
    this.last = w;
    this.app.agents.contextChanged();
  }

  /** The window agents take their context from: the last focused one still open. */
  current() {
    if (this.last && !this.last.closed) return this.last;
    return this.list[this.list.length - 1] || null;
  }

  /** @param {BrowserWin} w */
  closed(w) {
    this.list = this.list.filter((x) => x !== w);
    if (this.last === w) this.last = this.list[this.list.length - 1] || null;
    if (!this.quitting) this.saveSession();
    this.app.agents.windowsChanged();
    if (!this.list.length && !this.quitting) electronApp.quit();
  }

  /** @param {string} url @param {string} title */
  rememberClosed(url, title) {
    this.closedTabs.push({ url, title });
    if (this.closedTabs.length > 25) this.closedTabs.shift();
  }

  hasClosed() {
    return this.closedTabs.length > 0;
  }

  /** @param {BrowserWin} w */
  reopenClosed(w) {
    const t = this.closedTabs.pop();
    if (t) w.addTab(t.url);
  }

  sessionChanged() {
    if (!this.quitting) this.saveSession();
  }

  saveSession() {
    const windows = this.list
      .filter((w) => !w.private && !w.closed)
      .map((w) => ({
        bounds: w.win.isMaximized() || w.win.isFullScreen() ? w.win.getNormalBounds() : w.win.getBounds(),
        maximized: w.win.isMaximized(),
        tabs: w.tabs.map((t) => ({ url: t.lazyUrl || t.url, title: t.title })),
        active: w.active ? Math.max(0, w.tabs.indexOf(w.active)) : 0,
      }));
    if (windows.length) this.app.session.set({ windows });
  }

  /** Opens the windows of the previous session, or one new window. */
  restore() {
    const saved = this.app.session.get().windows;
    if (this.app.settings.get().general.startup === 'restore' && saved.length) {
      for (const s of saved) this.create({ bounds: s.bounds, maximized: s.maximized, tabs: s.tabs, active: s.active });
    } else {
      this.create({});
    }
  }

  /** Pushes fresh state to every window (after settings, agents or bookmarks change). */
  refreshAll() {
    for (const w of this.list) w.pushState();
  }

  quit() {
    this.saveSession();
    this.quitting = true;
    electronApp.quit();
  }
}

module.exports = { WindowManager, handleScheme, installPermissionHandlers };
