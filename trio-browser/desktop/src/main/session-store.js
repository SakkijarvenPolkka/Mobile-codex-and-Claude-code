'use strict';
/** Open windows and tabs (session.json), for restoring the previous session at startup. */
const { JsonStore, v } = require('./json-store');

/**
 * @typedef {{ url: string, title: string }} SavedTab
 * @typedef {{ bounds: { x: number, y: number, width: number, height: number } | null, maximized: boolean, tabs: SavedTab[], active: number }} SavedWindow
 * @typedef {{ windows: SavedWindow[] }} SavedSession
 */

/** @param {any} b */
function bounds(b) {
  const o = v.obj(b);
  const ok = ['x', 'y', 'width', 'height'].every((k) => Number.isFinite(o[k]));
  return ok && o.width >= 200 && o.height >= 200 ? { x: Math.round(o.x), y: Math.round(o.y), width: Math.round(o.width), height: Math.round(o.height) } : null;
}

class SessionStore {
  /** @param {string} file */
  constructor(file) {
    this.store = new JsonStore(
      file,
      () => /** @type {SavedSession} */ ({ windows: [] }),
      (raw) => ({
        windows: v
          .arr(v.obj(raw).windows)
          .map((w) => {
            const tabs = v
              .arr(v.obj(w).tabs)
              .filter((t) => t && typeof t.url === 'string' && /^(https?|file|trio):/.test(t.url))
              .map((t) => ({ url: t.url, title: v.str(t.title, '') }))
              .slice(0, 200);
            return { bounds: bounds(w.bounds), maximized: v.bool(w.maximized, false), tabs, active: v.int(w.active, 0, Math.max(0, tabs.length - 1), 0) };
          })
          .filter((w) => w.tabs.length)
          .slice(0, 20),
      }),
      { delayMs: 1000 },
    );
  }

  /** @returns {SavedSession} */
  get() {
    return this.store.get();
  }

  /** @param {SavedSession} s */
  set(s) {
    this.store.set(s);
  }

  flush() {
    this.store.flush();
  }
}

module.exports = { SessionStore };
