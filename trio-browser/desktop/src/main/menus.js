'use strict';
/** Native popup menus: app menu, tab menu, page context menu, bookmark, agent and dock menus. */

const { Menu, clipboard } = require('electron');
const data = require('../core/data');
const { buildSearchUrl, engineById } = require('../core/search');

/**
 * @typedef {import('./browser-window').BrowserWin} BrowserWin
 * @typedef {import('./tab').Tab} Tab
 * @typedef {Electron.MenuItemConstructorOptions} Item
 */

/** @param {string} s @param {number} n */
const shorten = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

/**
 * @param {Electron.BaseWindow} win
 * @param {Item[]} template
 * @param {{ x: number, y: number } | undefined} at window-relative point
 */
function popup(win, template, at) {
  const menu = Menu.buildFromTemplate(template);
  menu.popup(at ? { window: /** @type {any} */ (win), x: Math.round(at.x), y: Math.round(at.y) } : { window: /** @type {any} */ (win) });
}

/** @param {BrowserWin} w @param {{ x: number, y: number }} at */
function appMenu(w, at) {
  const app = w.app;
  const t = app.t;
  const s = app.settings.get();
  const agentItems = w.private
    ? [{ label: t('menu.agentsPrivate'), enabled: false }]
    : [
        ...app.agents.summaries().filter((a) => a.enabled).map((a) => ({
          label: a.state === 'signedIn' ? t('menu.openAgent', { name: a.name }) : t('menu.signInAgent', { name: a.name }),
          click: () => app.agents.toggle(a.id, w),
        })),
        { type: /** @type {const} */ ('separator') },
        { label: t('menu.askAll'), accelerator: 'CmdOrCtrl+Shift+A', registerAccelerator: false, click: () => w.focusAddressBar('@all ', false) },
        { label: t('menu.analyzeHistory'), accelerator: 'CmdOrCtrl+Shift+Y', registerAccelerator: false, click: () => app.agents.openHistoryAnalysis(undefined, w) },
        { label: app.agents.dockVisible() ? t('menu.hideDock') : t('menu.showDock'), click: () => app.agents.setDockVisible(!app.agents.dockVisible()) },
      ];
  const zoom = w.active ? Math.round(w.active.wc.getZoomFactor() * 100) : 100;
  /** @type {Item[]} */
  const template = [
    { label: t('menu.newTab'), accelerator: 'CmdOrCtrl+T', registerAccelerator: false, click: () => { w.addTab('trio://newtab/'); w.focusAddressBar(); } },
    { label: t('menu.newWindow'), accelerator: 'CmdOrCtrl+N', registerAccelerator: false, click: () => app.windows.create({}) },
    { label: t('menu.newPrivateWindow'), accelerator: 'CmdOrCtrl+Shift+N', registerAccelerator: false, click: () => app.windows.create({ private: true }) },
    { type: 'separator' },
    { label: t('menu.agents'), submenu: agentItems },
    { type: 'separator' },
    { label: t('menu.history'), accelerator: 'CmdOrCtrl+H', registerAccelerator: false, click: () => w.openUrl('trio://history/', { newTab: true }) },
    { label: t('menu.downloads'), accelerator: 'CmdOrCtrl+J', registerAccelerator: false, click: () => w.openUrl('trio://downloads/', { newTab: true }) },
    { label: t('menu.bookmarks'), click: () => w.openUrl('trio://bookmarks/', { newTab: true }) },
    { label: t('menu.showBookmarksBar'), type: 'checkbox', checked: s.general.showBookmarksBar, click: () => app.settings.set('general.showBookmarksBar', !s.general.showBookmarksBar) },
    { type: 'separator' },
    {
      label: t('menu.zoom', { percent: zoom }),
      submenu: [
        { label: t('menu.zoomIn'), accelerator: 'CmdOrCtrl+=', registerAccelerator: false, click: () => w.active?.zoomBy(1) },
        { label: t('menu.zoomOut'), accelerator: 'CmdOrCtrl+-', registerAccelerator: false, click: () => w.active?.zoomBy(-1) },
        { label: t('menu.zoomReset'), accelerator: 'CmdOrCtrl+0', registerAccelerator: false, click: () => w.active?.zoomBy(0) },
      ],
    },
    { label: t('menu.find'), accelerator: 'CmdOrCtrl+F', registerAccelerator: false, click: () => w.openFind() },
    { label: t('menu.print'), accelerator: 'CmdOrCtrl+P', registerAccelerator: false, enabled: Boolean(w.active), click: () => w.active?.wc.print() },
    { label: t('menu.fullscreen'), accelerator: 'F11', registerAccelerator: false, click: () => w.win.setFullScreen(!w.win.isFullScreen()) },
    { type: 'separator' },
    { label: t('menu.settings'), accelerator: 'CmdOrCtrl+,', registerAccelerator: false, click: () => w.openUrl('trio://settings/', { newTab: true }) },
    { label: t('menu.about'), click: () => w.openUrl('trio://about/', { newTab: true }) },
    { type: 'separator' },
    { label: t('menu.quit'), click: () => app.windows.quit() },
  ];
  popup(w.win, template, at);
}

/** @param {BrowserWin} w @param {number} tabId @param {{ x: number, y: number }} at */
function tabMenu(w, tabId, at) {
  const tab = w.tabs.find((x) => x.id === tabId);
  if (!tab) return;
  const t = w.app.t;
  const index = w.tabs.indexOf(tab);
  /** @type {Item[]} */
  const template = [
    { label: t('menu.newTabRight'), click: () => w.addTab('trio://newtab/', { index: index + 1 }) },
    { label: t('menu.reload'), click: () => { w.activate(tab); w.reload(); } },
    { label: t('menu.duplicate'), click: () => w.addTab(tab.url, { index: index + 1 }) },
    { label: tab.wc.isAudioMuted() ? t('menu.unmute') : t('menu.mute'), click: () => { tab.wc.setAudioMuted(!tab.wc.isAudioMuted()); w.pushState(); } },
    { type: 'separator' },
    { label: t('menu.closeTab'), click: () => w.closeTab(tab.id) },
    { label: t('menu.closeOthers'), enabled: w.tabs.length > 1, click: () => w.tabs.filter((x) => x !== tab).forEach((x) => w.closeTab(x.id)) },
    { label: t('menu.closeRight'), enabled: index < w.tabs.length - 1, click: () => w.tabs.slice(index + 1).forEach((x) => w.closeTab(x.id)) },
    { type: 'separator' },
    { label: t('menu.reopenClosed'), enabled: w.app.windows.hasClosed(), click: () => w.app.windows.reopenClosed(w) },
  ];
  popup(w.win, template, at);
}

/**
 * @param {BrowserWin} w
 * @param {Tab} tab
 * @param {Electron.ContextMenuParams} p
 */
function pageContextMenu(w, tab, p) {
  const app = w.app;
  const t = app.t;
  const wc = tab.wc;
  /** @type {Item[]} */
  const items = [];
  const sep = () => {
    if (items.length && items[items.length - 1].type !== 'separator') items.push({ type: 'separator' });
  };
  const agents = w.private ? [] : app.agents.summaries().filter((a) => a.enabled);

  if (p.linkURL) {
    const link = p.linkURL;
    items.push(
      { label: t('ctx.openLinkTab'), click: () => w.addTab(link, { background: true, index: w.tabs.indexOf(tab) + 1 }) },
      { label: t('ctx.openLinkWindow'), click: () => app.windows.create({ tabs: [{ url: link }] }) },
      { label: t('ctx.openLinkPrivate'), click: () => app.windows.create({ private: true, tabs: [{ url: link }] }) },
      { label: t('ctx.copyLink'), click: () => clipboard.writeText(link) },
    );
  }
  if (p.mediaType === 'image' && p.srcURL) {
    sep();
    const src = p.srcURL;
    items.push(
      { label: t('ctx.openImageTab'), click: () => w.addTab(src, { background: true, index: w.tabs.indexOf(tab) + 1 }) },
      { label: t('ctx.saveImage'), click: () => wc.downloadURL(src) },
      { label: t('ctx.copyImage'), click: () => wc.copyImageAt(p.x, p.y) },
      { label: t('ctx.copyImageAddress'), click: () => clipboard.writeText(src) },
    );
  }
  if (p.isEditable) {
    sep();
    const f = p.editFlags;
    items.push(
      { label: t('ctx.undo'), role: 'undo', enabled: f.canUndo },
      { label: t('ctx.redo'), role: 'redo', enabled: f.canRedo },
      { type: 'separator' },
      { label: t('ctx.cut'), role: 'cut', enabled: f.canCut },
      { label: t('ctx.copy'), role: 'copy', enabled: f.canCopy },
      { label: t('ctx.paste'), role: 'paste', enabled: f.canPaste },
      { label: t('ctx.selectAll'), role: 'selectAll', enabled: f.canSelectAll },
    );
  } else if (p.selectionText && p.selectionText.trim()) {
    sep();
    const sel = p.selectionText.trim();
    const engine = engineById(data.searchEngines(), app.settings.get().search.engine);
    items.push(
      { label: t('ctx.copy'), role: 'copy' },
      { label: t('ctx.searchFor', { engine: engine.name, text: shorten(sel.replace(/\s+/g, ' '), 24) }), click: () => w.addTab(buildSearchUrl(data.searchEngines(), engine.id, sel), { index: w.tabs.indexOf(tab) + 1, transition: 'search' }) },
    );
    if (agents.length && !tab.url.startsWith('trio:')) {
      items.push({
        label: t('ctx.askAgents'),
        submenu: agents.flatMap((a) => [
          { label: t('ctx.explainWith', { name: a.name }), click: () => app.agents.runAction(a.id, 'explain_selection', {}, w, sel) },
          { label: t('ctx.translateWith', { name: a.name }), click: () => app.agents.runAction(a.id, 'translate_selection', {}, w, sel) },
        ]),
      });
    }
  }
  if (!p.linkURL && !p.isEditable && !(p.selectionText && p.selectionText.trim()) && p.mediaType === 'none') {
    const nav = wc.navigationHistory;
    items.push(
      { label: t('ctx.back'), enabled: nav.canGoBack(), click: () => w.back() },
      { label: t('ctx.forward'), enabled: nav.canGoForward(), click: () => w.forward() },
      { label: t('ctx.reload'), click: () => w.reload() },
      { type: 'separator' },
      { label: t('ctx.print'), click: () => wc.print() },
    );
    if (agents.length && !tab.url.startsWith('trio:')) {
      items.push({ label: t('ctx.summarizeWith'), submenu: agents.map((a) => ({ label: a.name, click: () => app.agents.runAction(a.id, 'summarize_page', {}, w) })) });
    }
    if (/^https?:/.test(tab.url)) items.push({ label: t('ctx.viewSource'), click: () => w.addTab('view-source:' + tab.url, { index: w.tabs.indexOf(tab) + 1 }) });
  }
  sep();
  items.push({ label: t('ctx.inspect'), click: () => wc.inspectElement(p.x, p.y) });
  const b = tab.view.getBounds();
  popup(w.win, items, { x: b.x + p.x, y: b.y + p.y });
}

/** @param {BrowserWin} w @param {string} id @param {{ x: number, y: number }} at */
function bookmarkMenu(w, id, at) {
  const t = w.app.t;
  /** @type {Item[]} */
  const template = [
    { label: t('menu.open'), click: () => w.openBookmark(id) },
    { label: t('menu.openNewTab'), click: () => w.openBookmark(id, { newTab: true }) },
    { type: 'separator' },
    { label: t('menu.editBookmarks'), click: () => w.openUrl('trio://bookmarks/', { newTab: true }) },
    { label: t('menu.delete'), click: () => w.app.bookmarks.remove(id) },
  ];
  popup(w.win, template, at);
}

module.exports = { appMenu, tabMenu, pageContextMenu, bookmarkMenu, popup };
