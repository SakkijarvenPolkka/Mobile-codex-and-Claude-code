'use strict';
/**
 * The renderer surfaces and the API each may call. The preload (src/preload/trio.js) has its own
 * copy of these lists because sandboxed preloads cannot load other files; a unit test keeps the
 * two in sync.
 */

/** @type {Record<'chrome' | 'agent' | 'dock' | 'pages', { methods: string[], events: string[] }>} */
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

/** Push channel for an event: event("chrome", "state") -> "trio:chrome:on:state". */
const eventChannel = (/** @type {string} */ surface, /** @type {string} */ name) => `trio:${surface}:on:${name}`;
const methodChannel = (/** @type {string} */ surface, /** @type {string} */ name) => `trio:${surface}:${name}`;

module.exports = { SURFACES, eventChannel, methodChannel };
