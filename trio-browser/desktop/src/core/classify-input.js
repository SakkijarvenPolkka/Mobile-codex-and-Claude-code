'use strict';
/**
 * Decides what text typed into the address bar means: a URL to open, a web search, a question
 * for an agent, or a history search. Pure; mirrored in Kotlin (ClassifyInput.kt) and pinned by
 * shared/test-vectors/classify-input.json.
 */

const { toAsciiHost, isIpv4, hasKnownTld } = require('./url');

/**
 * @typedef {import('../types/contract').AgentId} AgentId
 * @typedef {{ type: 'empty' }
 *   | { type: 'url', url: string }
 *   | { type: 'search', query: string }
 *   | { type: 'agent', agentId: AgentId | 'all', query: string }
 *   | { type: 'history', query: string }} InputKind
 * @typedef {{ agentKeywords: Map<string, AgentId>, suffixes: import('./url').SuffixData }} ClassifyOptions
 */

const ALL_KEYWORDS = new Set(['all', '모두', '전체']);
const HISTORY_KEYWORDS = new Set(['history', '기록']);
/** about:/chrome: page names that map to internal pages. */
const INTERNAL_PAGES = new Set(['newtab', 'history', 'bookmarks', 'downloads', 'settings', 'about']);

/**
 * Builds the keyword table from provider configs.
 * @param {{ id: AgentId, keywords: string[] }[]} providers
 */
function agentKeywordMap(providers) {
  /** @type {Map<string, AgentId>} */
  const map = new Map();
  for (const p of providers) for (const k of p.keywords) map.set(k.toLowerCase(), p.id);
  return map;
}

/** @param {string} label ASCII label */
function isValidLabel(label) {
  return label.length > 0 && label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label);
}

/**
 * @param {string} text
 * @param {ClassifyOptions} options
 * @returns {InputKind}
 */
function classifyInput(text, options) {
  const input = text.trim();
  if (!input) return { type: 'empty' };

  if (input.startsWith('?')) {
    const query = input.slice(1).trim();
    return query ? { type: 'search', query } : { type: 'empty' };
  }

  if (input.startsWith('@')) {
    const m = /^@(\S+)(?:\s+([\s\S]*))?$/.exec(input);
    if (m) {
      const word = m[1].toLowerCase();
      const rest = (m[2] || '').trim();
      const agentId = options.agentKeywords.get(word);
      if (agentId) return { type: 'agent', agentId, query: rest };
      if (ALL_KEYWORDS.has(word)) return { type: 'agent', agentId: 'all', query: rest };
      if (HISTORY_KEYWORDS.has(word)) return { type: 'history', query: rest };
    }
    return { type: 'search', query: input };
  }

  const search = /** @type {InputKind} */ ({ type: 'search', query: input });

  // Windows drive paths and absolute POSIX paths open as files.
  if (/^[a-zA-Z]:[\\/]/.test(input) && !/\s/.test(input.slice(0, 3))) {
    return { type: 'url', url: new URL('file:///' + input.replace(/\\/g, '/')).href };
  }
  if (input.startsWith('/') && !input.startsWith('//')) {
    return { type: 'url', url: new URL('file://' + input).href };
  }

  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$/s.exec(input);
  if (scheme) {
    const name = scheme[1].toLowerCase();
    const rest = scheme[2];
    if (name === 'http' || name === 'https') return parsedOr(input, search);
    if (name === 'file') return parsedOr(input, search);
    if (name === 'trio') {
      const r = parsedOr(input, search);
      return r.type === 'url' ? { type: 'url', url: normalizeInternal(r.url) } : r;
    }
    if (name === 'mailto') return /\s/.test(rest) ? search : { type: 'url', url: input };
    if (name === 'about' || name === 'chrome') {
      const page = rest.replace(/^\/\//, '').replace(/\/$/, '').toLowerCase();
      if (name === 'about' && page === 'blank') return { type: 'url', url: 'about:blank' };
      if (INTERNAL_PAGES.has(page)) return { type: 'url', url: `trio://${page}/` };
      return search;
    }
    // Anything else with a scheme-like prefix is either host:port or not a URL at all.
    if (!/^\d+(?:[/?#]|$)/.test(rest)) return search;
  }

  if (/\s/.test(input)) return search;

  const hostPart = /^([^/?#]*)(.*)$/s.exec(input);
  if (!hostPart) return search;
  const authority = hostPart[1];
  const tail = hostPart[2];
  if (authority.includes('@')) return search;

  // Split host and port (IPv6 literals keep their brackets).
  let host = authority;
  let port = '';
  const v6 = /^(\[[0-9a-fA-F:.]+\])(?::(\d*))?$/.exec(authority);
  if (v6) {
    host = v6[1];
    port = v6[2] || '';
  } else {
    const hp = /^([^:]+)(?::(\d+))?$/.exec(authority);
    if (!hp) return search;
    host = hp[1];
    port = hp[2] || '';
  }
  if (port && (Number(port) < 1 || Number(port) > 65535)) return search;

  const lower = host.toLowerCase();
  if (v6 || lower === 'localhost' || isIpv4(lower)) return parsedOr(`http://${authority}${tail}`, search);
  if (/^[\d.]+$/.test(lower)) return search; // "3.14", "1.2.3", "256.1.1.1"

  const ascii = toAsciiHost(lower);
  if (!ascii) return search;
  const labels = ascii.split('.');
  if (!labels.every(isValidLabel)) return search;

  if (labels.length === 1) {
    return port ? parsedOr(`http://${authority}${tail}`, search) : search;
  }
  if (!hasKnownTld(ascii, options.suffixes)) return search;
  return parsedOr(`https://${authority}${tail}`, search);
}

/**
 * "trio://history" -> "trio://history/" (Chromium canonicalises the standard scheme the same way).
 * @param {string} url
 */
function normalizeInternal(url) {
  const m = /^trio:\/\/([^/?#]*)(.*)$/s.exec(url);
  if (!m) return url;
  const host = m[1].toLowerCase();
  const rest = m[2] && !m[2].startsWith('/') ? '/' + m[2] : m[2] || '/';
  return `trio://${host}${rest}`;
}

/**
 * @param {string} candidate
 * @param {InputKind} fallback
 * @returns {InputKind}
 */
function parsedOr(candidate, fallback) {
  try {
    const u = new URL(candidate);
    if ((u.protocol === 'http:' || u.protocol === 'https:') && !u.hostname) return fallback;
    return { type: 'url', url: u.href };
  } catch {
    return fallback;
  }
}

module.exports = { classifyInput, agentKeywordMap };
