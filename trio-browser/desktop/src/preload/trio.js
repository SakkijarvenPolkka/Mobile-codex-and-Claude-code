// @ts-nocheck — kept dependency-free for the sandbox; checked by test/unit/surfaces.test.js.
'use strict';
/*
 * Trio preload, shared by every web contents. It exposes window.trio.<surface> only on Trio's own
 * pages (trio:// URLs); ordinary web pages get nothing. Sandboxed: no Node, only contextBridge and
 * ipcRenderer. Keep the lists identical to src/main/surfaces.js (checked by a unit test).
 */
const { contextBridge, ipcRenderer } = require('electron');

const SURFACES = {
  chrome: {
    methods: [
      'init', 'newTab', 'closeTab', 'activateTab', 'moveTab', 'setTabMuted', 'showTabMenu', 'navigate', 'acceptSuggestion', 'back',
      'forward', 'reload', 'stop', 'suggest', 'setOverlay', 'setChromeHeight', 'find', 'toggleBookmark', 'openBookmark',
      'showBookmarkMenu', 'respondPermission', 'dismissNotice', 'toggleAgent', 'toggleDock', 'askAboutSearch', 'showAppMenu',
      'openDownloads',
    ],
    events: ['state', 'command', 'boot'],
  },
  agent: {
    methods: [
      'init', 'signIn', 'cancelSignIn', 'signInInTab', 'signOut', 'runAction', 'confirmPreview', 'cancelPreview', 'openHistoryAnalysis',
      'previewHistory', 'sendHistory', 'closeHistoryAnalysis', 'newChat', 'reloadProvider', 'openInTab', 'setPinned', 'minimize',
      'close', 'showMenu', 'dismissNotice', 'setHeaderHeight', 'startMove', 'startResize', 'endDrag',
    ],
    events: ['state', 'preview', 'boot'],
  },
  dock: {
    methods: ['init', 'toggleAgent', 'showMenu', 'setSize', 'startMove', 'endDrag'],
    events: ['state', 'boot'],
  },
  pages: {
    methods: [
      'init', 'navigate', 'getNewTabData', 'toggleAgent', 'queryHistory', 'deleteHistory', 'analyzeHistory', 'getBookmarks',
      'addBookmark', 'updateBookmark', 'removeBookmark', 'moveBookmark', 'getDownloads', 'downloadAction', 'clearDownloads',
      'getSettings', 'setSetting', 'getSearchEngines', 'getAgents', 'clearBrowsingData', 'chooseDownloadDirectory', 'getAbout', 'retry',
    ],
    events: ['changed', 'boot'],
  },
};
const PAGE_HOSTS = ['newtab', 'history', 'bookmarks', 'downloads', 'settings', 'about', 'error'];

function surfaceFor(loc) {
  if (loc.protocol !== 'trio:') return null;
  if (loc.hostname === 'chrome' || loc.hostname === 'agent' || loc.hostname === 'dock') return loc.hostname;
  return PAGE_HOSTS.includes(loc.hostname) ? 'pages' : null;
}

const surface = surfaceFor(window.location);
if (surface) {
  const def = SURFACES[surface];
  const api = {};
  for (const m of def.methods) api[m] = (...args) => ipcRenderer.invoke(`trio:${surface}:${m}`, ...args);
  for (const e of def.events) {
    const name = 'on' + e[0].toUpperCase() + e.slice(1);
    api[name] = (listener) => {
      if (typeof listener !== 'function') return;
      ipcRenderer.on(`trio:${surface}:on:${e}`, (_event, payload) => listener(payload));
    };
  }
  contextBridge.exposeInMainWorld('trio', { [surface]: api });
}
