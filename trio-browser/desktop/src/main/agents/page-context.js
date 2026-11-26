'use strict';
/** Reads the visible text of a tab (in an isolated world, so page scripts cannot interfere). */

const WORLD = 1202;

/**
 * @typedef {{ title: string, url: string, lang: string, selection: string, text: string, truncated: boolean }} PageContext
 */

/**
 * @param {Electron.WebContents} wc
 * @param {string} script contents of shared/inject/extract-page.js
 * @returns {Promise<PageContext | null>}
 */
async function extractPage(wc, script) {
  if (wc.isDestroyed() || wc.isCrashed()) return null;
  try {
    const result = await Promise.race([
      wc.executeJavaScriptInIsolatedWorld(WORLD, [{ code: script }]),
      new Promise((resolve) => setTimeout(() => resolve(null), 5000)),
    ]);
    if (!result || typeof result !== 'object') return null;
    const r = /** @type {any} */ (result);
    return {
      title: String(r.title || '').slice(0, 500),
      url: String(r.url || ''),
      lang: String(r.lang || ''),
      selection: String(r.selection || ''),
      text: String(r.text || ''),
      truncated: Boolean(r.truncated),
    };
  } catch {
    return null;
  }
}

module.exports = { extractPage };
