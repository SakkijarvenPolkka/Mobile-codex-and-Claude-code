'use strict';
/** The floating dock: a small frameless window with one button per agent. */

const { BaseWindow, WebContentsView, screen, Menu } = require('electron');
const { preloadFile } = require('../paths');
const { registerSurface, emit } = require('../ipc');
const { onScreen } = require('./agent-window');

class DockWindow {
  /**
   * @param {import('./agent-manager').AgentManager} manager
   * @param {{ x: number, y: number } | null} position
   */
  constructor(manager, position) {
    this.manager = manager;
    this.app = manager.app;
    this.closed = false;
    this.size = { width: 56, height: 200 };
    this.position = position;
    /** @type {NodeJS.Timeout | null} */
    this.drag = null;
    /** @type {NodeJS.Timeout | null} */
    this.pushTimer = null;
    this.win = new BaseWindow({
      width: this.size.width,
      height: this.size.height,
      frame: false,
      resizable: false,
      maximizable: false,
      minimizable: false,
      fullscreenable: false,
      skipTaskbar: true,
      show: false,
      title: 'Trio',
      transparent: false,
      backgroundColor: this.app.theme() === 'dark' ? '#26272c' : '#ffffff',
    });
    this.view = new WebContentsView({ webPreferences: { preload: preloadFile(), sandbox: true, contextIsolation: true, nodeIntegration: false, spellcheck: false } });
    registerSurface(this.view.webContents, 'dock', this);
    this.view.webContents.on('will-navigate', (e) => e.preventDefault());
    this.view.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    this.win.contentView.addChildView(this.view);
    this.view.setBounds({ x: 0, y: 0, ...this.size });
    this.view.webContents.loadURL('trio://dock/');
    this.ready = new Promise((resolve) => this.view.webContents.once('did-finish-load', () => resolve(undefined)));
    this.win.on('move', () => {
      const [x, y] = this.win.getPosition();
      this.position = { x, y };
      this.manager.saveDockPosition(this.position);
    });
    this.win.on('closed', () => this.destroy());
  }

  /** Shows the dock next to a browser window. @param {import('../browser-window').BrowserWin} parent */
  async attach(parent) {
    if (this.closed) return;
    this.win.setParentWindow(parent.win);
    if (!this.position || !onScreen({ ...this.position, ...this.size })) {
      const b = parent.win.getBounds();
      const area = screen.getDisplayMatching(b).workArea;
      this.position = {
        x: Math.min(b.x + b.width - this.size.width - 16, area.x + area.width - this.size.width - 8),
        y: Math.round(b.y + b.height / 2 - this.size.height / 2),
      };
    }
    this.win.setPosition(Math.round(this.position.x), Math.round(this.position.y));
    await this.ready;
    if (!this.closed && !this.win.isVisible()) this.win.showInactive();
    this.pushState();
  }

  hide() {
    if (!this.closed) this.win.hide();
  }

  /** @param {number} width @param {number} height */
  setSize(width, height) {
    const w = Math.max(40, Math.min(200, Math.round(width)));
    const h = Math.max(40, Math.min(600, Math.round(height)));
    this.size = { width: w, height: h };
    this.win.setContentSize(w, h);
    this.view.setBounds({ x: 0, y: 0, width: w, height: h });
  }

  startMove() {
    this.endDrag();
    const start = screen.getCursorScreenPoint();
    const [x, y] = this.win.getPosition();
    const started = Date.now();
    this.drag = setInterval(() => {
      if (this.closed || Date.now() - started > 60000) return this.endDrag();
      const p = screen.getCursorScreenPoint();
      this.win.setPosition(x + p.x - start.x, y + p.y - start.y);
    }, 16);
  }

  endDrag() {
    if (this.drag) clearInterval(this.drag);
    this.drag = null;
  }

  /** @param {{ x: number, y: number }} at */
  showMenu(at) {
    const t = this.app.t;
    const current = this.app.windows.current();
    Menu.buildFromTemplate([
      { label: t('menu.askAll'), click: () => current && current.focusAddressBar('@all ', false) },
      { label: t('menu.analyzeHistory'), click: () => this.manager.openHistoryAnalysis(undefined, current || undefined) },
      { type: 'separator' },
      { label: t('menu.hideDock'), click: () => this.manager.setDockVisible(false) },
      { label: t('menu.settings'), click: () => current && current.openUrl('trio://settings/', { newTab: true }) },
    ]).popup({ window: /** @type {any} */ (this.win), x: Math.round(at.x), y: Math.round(at.y) });
  }

  /** @returns {import('../../types/contract').DockState} */
  state() {
    const current = this.app.windows.current();
    return { agents: this.manager.summaries(), private: Boolean(current && current.private) };
  }

  pushState() {
    if (this.closed || this.pushTimer) return;
    this.pushTimer = setTimeout(() => {
      this.pushTimer = null;
      if (!this.closed) emit(this.view.webContents, 'dock', 'state', this.state());
    }, 16);
  }

  close() {
    if (!this.closed) this.win.close();
  }

  destroy() {
    if (this.closed) return;
    this.closed = true;
    this.endDrag();
    if (!this.view.webContents.isDestroyed()) this.view.webContents.close();
    if (this.manager.dock === this) this.manager.dock = null;
  }
}

module.exports = { DockWindow };
