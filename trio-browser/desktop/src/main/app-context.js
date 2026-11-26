'use strict';
/**
 * The services shared by windows, tabs and agents. Created once in main.js.
 * @typedef {{
 *   settings: import('./settings').SettingsStore,
 *   history: import('./history-db').HistoryDb | null,
 *   bookmarks: import('./bookmarks').BookmarkStore,
 *   downloads: import('./downloads').DownloadManager,
 *   session: import('./session-store').SessionStore,
 *   agents: import('./agents/agent-manager').AgentManager,
 *   windows: import('./windows').WindowManager,
 *   locale: () => import('../types/contract').Locale,
 *   theme: () => import('../types/contract').ThemeName,
 *   t: (key: string, params?: Record<string, string | number>) => string,
 *   boot: () => import('../types/contract').SurfaceBoot,
 *   historyChanged: () => void,
 *   pagesChanged: (what: 'history' | 'bookmarks' | 'downloads' | 'settings' | 'agents') => void,
 * }} AppContext
 */
module.exports = {};
