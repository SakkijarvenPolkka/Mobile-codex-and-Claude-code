'use strict';
/** User settings (settings.json) with validation and change notification. */

const { EventEmitter } = require('node:events');
const { JsonStore, v } = require('./json-store');

/** @typedef {import('../types/contract').Settings} Settings */

const AGENT_IDS = /** @type {const} */ (['claude', 'gemini', 'chatgpt']);
const PERMISSION_KINDS = ['camera', 'microphone', 'camera+microphone', 'geolocation', 'notifications', 'clipboard-read', 'screen', 'midi', 'other'];

/** @returns {Settings} */
function defaults() {
  return {
    general: { language: 'system', theme: 'system', startup: 'restore', showBookmarksBar: false },
    search: { engine: 'google', suggestions: false },
    privacy: { saveHistory: true, excludeSensitive: true, extraExcludedHosts: [] },
    downloads: { askWhere: false, directory: null },
    agents: {
      enabled: { claude: true, gemini: true, chatgpt: true },
      dockVisible: true,
      preview: { page: true, selection: false, search: false },
      pageCharLimit: 12000,
      historyCharLimit: 8000,
      promptLanguage: 'ui',
    },
    permissions: {},
  };
}

/**
 * @param {any} raw
 * @param {Settings} d
 * @param {string[]} engineIds
 * @returns {Settings}
 */
function validate(raw, d, engineIds) {
  const r = v.obj(raw);
  const g = v.obj(r.general);
  const s = v.obj(r.search);
  const p = v.obj(r.privacy);
  const dl = v.obj(r.downloads);
  const a = v.obj(r.agents);
  const en = v.obj(a.enabled);
  const pv = v.obj(a.preview);
  /** @type {Settings['permissions']} */
  const permissions = {};
  for (const [origin, perms] of Object.entries(v.obj(r.permissions))) {
    if (!/^https?:\/\/[^/]+$/.test(origin)) continue;
    /** @type {Record<string, 'allow' | 'deny'>} */
    const clean = {};
    for (const [kind, decision] of Object.entries(v.obj(perms))) {
      if (PERMISSION_KINDS.includes(kind) && (decision === 'allow' || decision === 'deny')) clean[kind] = decision;
    }
    if (Object.keys(clean).length) permissions[origin] = clean;
  }
  return {
    general: {
      language: v.oneOf(g.language, ['system', 'ko', 'en'], d.general.language),
      theme: v.oneOf(g.theme, ['system', 'light', 'dark'], d.general.theme),
      startup: v.oneOf(g.startup, ['newtab', 'restore'], d.general.startup),
      showBookmarksBar: v.bool(g.showBookmarksBar, d.general.showBookmarksBar),
    },
    search: {
      engine: engineIds.includes(s.engine) ? s.engine : d.search.engine,
      suggestions: v.bool(s.suggestions, d.search.suggestions),
    },
    privacy: {
      saveHistory: v.bool(p.saveHistory, d.privacy.saveHistory),
      excludeSensitive: v.bool(p.excludeSensitive, d.privacy.excludeSensitive),
      extraExcludedHosts: v
        .arr(p.extraExcludedHosts)
        .filter((h) => typeof h === 'string')
        .map((h) => h.trim().toLowerCase())
        .filter((h) => /^[a-z0-9.*-]+$/.test(h) && h.length <= 253)
        .slice(0, 500),
    },
    downloads: {
      askWhere: v.bool(dl.askWhere, d.downloads.askWhere),
      directory: typeof dl.directory === 'string' && dl.directory ? dl.directory : null,
    },
    agents: {
      enabled: {
        claude: v.bool(en.claude, d.agents.enabled.claude),
        gemini: v.bool(en.gemini, d.agents.enabled.gemini),
        chatgpt: v.bool(en.chatgpt, d.agents.enabled.chatgpt),
      },
      dockVisible: v.bool(a.dockVisible, d.agents.dockVisible),
      preview: {
        page: v.bool(pv.page, d.agents.preview.page),
        selection: v.bool(pv.selection, d.agents.preview.selection),
        search: v.bool(pv.search, d.agents.preview.search),
      },
      pageCharLimit: v.int(a.pageCharLimit, 1000, 100000, d.agents.pageCharLimit),
      historyCharLimit: v.int(a.historyCharLimit, 1000, 100000, d.agents.historyCharLimit),
      promptLanguage: v.oneOf(a.promptLanguage, ['ui', 'ko', 'en'], d.agents.promptLanguage),
    },
    permissions,
  };
}

class SettingsStore extends EventEmitter {
  /**
   * @param {string} file
   * @param {string[]} engineIds
   */
  constructor(file, engineIds) {
    super();
    this.engineIds = engineIds;
    this.store = new JsonStore(file, defaults, (raw, d) => validate(raw, d, engineIds));
  }

  /** @returns {Settings} */
  get() {
    return this.store.get();
  }

  /**
   * Sets one value by dotted path ("search.engine"). The whole object is re-validated, so an
   * invalid value is ignored. Returns the new settings.
   * @param {string} dotted
   * @param {unknown} value
   */
  set(dotted, value) {
    const keys = dotted.split('.');
    if (!keys.length || keys.some((k) => !k || k === '__proto__' || k === 'constructor' || k === 'prototype')) return this.get();
    const next = structuredClone(this.get());
    /** @type {any} */
    let node = next;
    for (const k of keys.slice(0, -1)) {
      if (!node || typeof node !== 'object' || !(k in node)) return this.get();
      node = node[k];
    }
    const last = /** @type {string} */ (keys.at(-1));
    if (!node || typeof node !== 'object' || (!(last in node) && keys[0] !== 'permissions')) return this.get();
    node[last] = value;
    const clean = validate(next, defaults(), this.engineIds);
    this.store.set(clean);
    this.emit('change', clean, dotted);
    return clean;
  }

  /**
   * @param {string} origin
   * @param {string} kind
   * @param {'allow' | 'deny' | null} decision null removes it
   */
  setPermission(origin, kind, decision) {
    const perms = structuredClone(this.get().permissions);
    const entry = perms[origin] || {};
    if (decision) /** @type {any} */ (entry)[kind] = decision;
    else delete (/** @type {any} */ (entry)[kind]);
    if (Object.keys(entry).length) perms[origin] = entry;
    else delete perms[origin];
    return this.set('permissions', perms);
  }

  flush() {
    this.store.flush();
  }
}

module.exports = { SettingsStore, defaults, validate, AGENT_IDS };
