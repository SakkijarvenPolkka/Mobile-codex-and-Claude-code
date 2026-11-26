'use strict';
/** Trio — application entry point. */

const path = require('node:path');
const { app, session, nativeTheme, Menu, dialog, shell } = require('electron');
const { configureProfile, profileFile } = require('./paths');
const { registerScheme, handleScheme, isInternalUrl } = require('./protocol');
const { installPermissionHandlers } = require('./permissions');
const { SettingsStore } = require('./settings');
const { HistoryDb } = require('./history-db');
const { BookmarkStore } = require('./bookmarks');
const { DownloadManager } = require('./downloads');
const { SessionStore } = require('./session-store');
const { WindowManager } = require('./windows');
const { AgentManager } = require('./agents/agent-manager');
const { installHandlers } = require('./ipc-handlers');
const { contentsOf, emit } = require('./ipc');
const i18n = require('./i18n');
const data = require('../core/data');

configureProfile();
registerScheme();
// Test hook: map made-up host names to the local mock server (see test/e2e).
if (process.env.TRIO_HOST_RULES) app.commandLine.appendSwitch('host-resolver-rules', process.env.TRIO_HOST_RULES);
app.setName('Trio');

// Present the engine as what it is — Chromium — without advertising the app or the embedding
// framework (sites otherwise see "Trio/0.1.0 … Electron/45.5.0").
const chromeVersion = process.versions.chrome.split('.')[0];
const platformToken =
  process.platform === 'win32' ? 'Windows NT 10.0; Win64; x64' : process.platform === 'darwin' ? 'Macintosh; Intel Mac OS X 10_15_7' : 'X11; Linux x86_64';
app.userAgentFallback = `Mozilla/5.0 (${platformToken}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeVersion}.0.0.0 Safari/537.36`;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.whenReady().then(start, (err) => {
    dialog.showErrorBox('Trio', String(err && err.stack ? err.stack : err));
    app.exit(1);
  });
}

async function start() {
  const settings = new SettingsStore(profileFile('settings.json'), data.searchEngines().engines.map((/** @type {any} */ e) => e.id));
  let history = null;
  try {
    history = new HistoryDb(profileFile('history.sqlite'));
  } catch (err) {
    console.error('History database unavailable:', err);
  }

  /** @type {import('./app-context').AppContext} */
  const ctx = /** @type {any} */ ({
    settings,
    history,
    bookmarks: new BookmarkStore(profileFile('bookmarks.json')),
    downloads: new DownloadManager(profileFile('downloads.json'), () => settings.get()),
    session: new SessionStore(profileFile('session.json')),
  });
  ctx.locale = () => i18n.resolveLocale(settings.get().general.language);
  ctx.theme = () => i18n.resolveTheme(settings.get().general.theme);
  ctx.t = (key, params) => i18n.t(ctx.locale(), key, params);
  ctx.boot = () => ({ locale: ctx.locale(), theme: ctx.theme(), strings: i18n.strings(ctx.locale()), platform: /** @type {any} */ (process.platform) });
  ctx.pagesChanged = (what) => {
    for (const wc of contentsOf('pages')) if (isInternalUrl(wc.getURL())) emit(wc, 'pages', 'changed', what);
  };
  let historyTimer = /** @type {NodeJS.Timeout | null} */ (null);
  ctx.historyChanged = () => {
    if (historyTimer) return;
    historyTimer = setTimeout(() => {
      historyTimer = null;
      ctx.pagesChanged('history');
    }, 500);
  };

  /** @param {Electron.Session} ses @param {boolean} isPrivate */
  const configureSession = (ses, isPrivate) => {
    handleScheme(ses);
    installPermissionHandlers(ses, {
      settings: isPrivate ? /** @type {any} */ ({ get: () => ({ ...settings.get(), permissions: {} }), setPermission: () => settings.get() }) : settings,
      prompt: (wc, origin, kind) => {
        const w = ctx.windows.list.find((x) => x.tabs.some((t) => t.wc === wc)) || ctx.windows.current();
        return w ? w.requestPermission(origin, kind) : Promise.resolve('deny');
      },
    });
    // Pages (including the providers' sign-in pages) follow the browser's UI language.
    ses.setUserAgent(app.userAgentFallback, ctx.locale() === 'ko' ? 'ko-KR,ko,en-US,en' : 'en-US,en');
    ctx.downloads.attach(ses);
  };

  ctx.windows = new WindowManager(ctx, { configureSession });
  ctx.agents = new AgentManager(ctx, session.defaultSession);
  configureSession(session.defaultSession, false);
  installHandlers(ctx);

  // macOS needs an application menu for standard editing shortcuts; elsewhere the browser's own
  // menus and before-input-event shortcuts are used.
  if (process.platform === 'darwin') {
    Menu.setApplicationMenu(Menu.buildFromTemplate([{ role: 'appMenu' }, { role: 'editMenu' }, { role: 'windowMenu' }]));
  } else {
    Menu.setApplicationMenu(null);
  }

  settings.on('change', (_s, key) => {
    if (key.startsWith('general.language')) {
      const langs = ctx.locale() === 'ko' ? 'ko-KR,ko,en-US,en' : 'en-US,en';
      session.defaultSession.setUserAgent(app.userAgentFallback, langs);
    }
    if (key.startsWith('general.language') || key.startsWith('general.theme')) {
      const boot = ctx.boot();
      for (const surface of /** @type {const} */ (['chrome', 'agent', 'dock', 'pages'])) for (const wc of contentsOf(surface)) emit(wc, surface, 'boot', boot);
    }
    if (key.startsWith('agents.dockVisible')) ctx.agents.syncDock();
    ctx.windows.refreshAll();
    ctx.agents.summaryChanged();
    ctx.pagesChanged('settings');
  });
  nativeTheme.on('updated', () => {
    if (settings.get().general.theme === 'system') {
      const boot = ctx.boot();
      for (const surface of /** @type {const} */ (['chrome', 'agent', 'dock', 'pages'])) for (const wc of contentsOf(surface)) emit(wc, surface, 'boot', boot);
    }
  });
  ctx.bookmarks.on('change', () => {
    ctx.windows.refreshAll();
    ctx.pagesChanged('bookmarks');
  });
  ctx.downloads.on('change', () => {
    ctx.windows.refreshAll();
    ctx.pagesChanged('downloads');
  });

  app.on('web-contents-created', (_e, wc) => {
    // Never let any web contents attach a <webview> or open the file system through drag-and-drop navigation.
    wc.on('will-attach-webview', (event) => event.preventDefault());
  });
  app.on('certificate-error', (event, _wc, _url, _error, _cert, callback) => {
    event.preventDefault();
    callback(false);
  });
  app.on('second-instance', (_e, argv) => {
    const w = ctx.windows.current() || ctx.windows.create({});
    const url = argv.find((a) => /^https?:\/\//.test(a));
    if (url) w.addTab(url);
    w.win.show();
    w.win.focus();
  });
  app.on('before-quit', () => {
    ctx.windows.saveSession();
    ctx.windows.quitting = true;
    for (const w of ctx.windows.list) for (const t of w.tabs) t.finishVisit();
    for (const s of [ctx.settings, ctx.bookmarks, ctx.downloads, ctx.session, ctx.agents]) {
      try {
        s.flush();
      } catch (err) {
        console.error(err);
      }
    }
  });
  app.on('window-all-closed', () => app.quit());

  await ctx.agents.init();
  const startUrls = process.argv.slice(app.isPackaged ? 1 : 2).filter((a) => /^https?:\/\//.test(a));
  ctx.windows.restore();
  const first = ctx.windows.current();
  if (first) for (const url of startUrls) first.addTab(url);
  ctx.agents.syncDock();
  void shell;
  void path;
}
