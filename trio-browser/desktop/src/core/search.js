'use strict';
/**
 * Search engines: building search URLs, recognising search-results pages and parsing
 * suggestion responses. Data comes from shared/search-engines.json.
 */

/**
 * @typedef {{ id: string, name: string, searchTemplate: string, suggestTemplate: string | null, suggestFormat: 'opensearch' | 'naver' | null }} SearchEngine
 * @typedef {{ engineId: string, name: string, host: string, path: string, params: string[] }} Recognizer
 * @typedef {{ default: string, engines: SearchEngine[], recognizers: Recognizer[] }} SearchData
 */

/**
 * @param {SearchData} data
 * @param {string} id
 * @returns {SearchEngine}
 */
function engineById(data, id) {
  return data.engines.find((e) => e.id === id) || /** @type {SearchEngine} */ (data.engines.find((e) => e.id === data.default));
}

/**
 * @param {string} template with a {searchTerms} placeholder
 * @param {string} query
 */
function fillTemplate(template, query) {
  return template.replace('{searchTerms}', encodeURIComponent(query.trim()));
}

/**
 * @param {SearchData} data
 * @param {string} engineId
 * @param {string} query
 */
function buildSearchUrl(data, engineId, query) {
  return fillTemplate(engineById(data, engineId).searchTemplate, query);
}

/** @type {WeakMap<Recognizer, { host: RegExp, path: RegExp }>} */
const compiled = new WeakMap();

/**
 * Finds the recognizer, the query parameter and the query of a search-results URL.
 * @param {SearchData} data
 * @param {string} url
 * @returns {{ recognizer: Recognizer, param: string, query: string } | null}
 */
function matchSearch(data, url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  const host = u.hostname.toLowerCase();
  for (const r of data.recognizers) {
    let c = compiled.get(r);
    if (!c) {
      c = { host: new RegExp(r.host), path: new RegExp(r.path) };
      compiled.set(r, c);
    }
    if (!c.host.test(host) || !c.path.test(u.pathname)) continue;
    for (const p of r.params) {
      const v = u.searchParams.get(p);
      const query = v ? v.replace(/\s+/g, ' ').trim() : '';
      if (query) return { recognizer: r, param: p, query };
    }
  }
  return null;
}

/**
 * Recognises a search-results URL and returns the engine and the query.
 * @param {SearchData} data
 * @param {string} url
 * @returns {{ engineId: string, name: string, query: string } | null}
 */
function extractSearch(data, url) {
  const m = matchSearch(data, url);
  return m ? { engineId: m.recognizer.engineId, name: m.recognizer.name, query: m.query } : null;
}

/**
 * Parses a suggestion endpoint response into plain strings.
 * @param {'opensearch' | 'naver' | null} format
 * @param {unknown} json
 * @returns {string[]}
 */
function parseSuggestions(format, json) {
  /** @type {unknown[]} */
  let list = [];
  if (format === 'opensearch' && Array.isArray(json) && Array.isArray(json[1])) list = json[1];
  else if (format === 'naver' && json && typeof json === 'object') {
    const items = /** @type {any} */ (json).items;
    if (Array.isArray(items) && Array.isArray(items[0])) list = items[0].map((/** @type {any} */ x) => (Array.isArray(x) ? x[0] : x));
  }
  return list.filter((s) => typeof s === 'string' && s.trim()).map((s) => /** @type {string} */ (s).trim());
}

module.exports = { engineById, buildSearchUrl, matchSearch, extractSearch, parseSuggestions, fillTemplate };
