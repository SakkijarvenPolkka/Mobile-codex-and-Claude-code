'use strict';
/**
 * Decides which URLs are too sensitive to describe to an agent, and strips identifying parts
 * from the rest. Rules come from shared/privacy-rules.json (see its "_doc").
 */

const { isLocalHost } = require('./url');
const { matchSearch } = require('./search');

/**
 * @typedef {{ hostSuffixes: string[], hostKeywords: string[], hostPrefixes: string[], pathSegments: string[] }} CategoryRule
 * @typedef {{ categories: Record<string, CategoryRule>, localHostSuffixes: string[], tokenSegment: { minLength: number, hexMinLength: number } }} PrivacyRules
 */

/** @param {string} host @param {string} suffix */
function hostMatches(host, suffix) {
  return host === suffix || host.endsWith('.' + suffix);
}

/**
 * The sensitive category of a URL, or null when it may be described.
 * "internal" for non-web schemes, "local" for the local network, "user" for hosts the user
 * excluded, otherwise the first matching category of the rules.
 * @param {string} url
 * @param {PrivacyRules} rules
 * @param {string[]} [extraHosts]
 * @returns {string | null}
 */
function sensitiveCategory(url, rules, extraHosts = []) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return 'internal';
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return 'internal';
  const host = u.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  for (const h of extraHosts) {
    const clean = h.trim().toLowerCase().replace(/^\*\./, '').replace(/^www\./, '');
    if (clean && hostMatches(host, clean)) return 'user';
  }
  if (isLocalHost(host, rules.localHostSuffixes)) return 'local';
  const tokens = host.split(/[.-]/).filter(Boolean);
  const segments = u.pathname.toLowerCase().split('/').filter(Boolean).map(safeDecode);
  for (const [name, rule] of Object.entries(rules.categories)) {
    if (rule.hostSuffixes.some((s) => hostMatches(host, s))) return name;
    if (tokens.some((t) => rule.hostKeywords.includes(t) || rule.hostPrefixes.some((p) => t.startsWith(p)))) return name;
    if (segments.some((s) => rule.pathSegments.includes(s))) return name;
  }
  return null;
}

/** @param {string} s */
function safeDecode(s) {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

/**
 * @param {string} segment decoded path segment
 * @param {PrivacyRules['tokenSegment']} t
 */
function isTokenLike(segment, t) {
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(segment)) return true;
  if (segment.length >= t.hexMinLength && /^[0-9a-f]+$/i.test(segment) && /[a-f]/i.test(segment) && /\d/.test(segment)) return true;
  return segment.length >= t.minLength && /^[A-Za-z0-9_-]+$/.test(segment) && /\d/.test(segment) && /[A-Za-z]/.test(segment);
}

/**
 * Removes user info, fragments, query strings (except the query of a recognised search),
 * e-mail addresses and token-like path segments. Returns null for non-web URLs.
 * @param {string} url
 * @param {PrivacyRules} rules
 * @param {import('./search').SearchData} searchData
 * @returns {string | null}
 */
function sanitizeUrl(url, rules, searchData) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  const path = u.pathname
    .split('/')
    .map((seg) => {
      if (!seg) return seg;
      const d = safeDecode(seg);
      if (d.includes('@')) return ':email';
      return isTokenLike(d, rules.tokenSegment) ? ':id' : seg;
    })
    .join('/');
  const search = matchSearch(searchData, url);
  const query = search ? `?${search.param}=${encodeURIComponent(search.query)}` : '';
  return `${u.protocol}//${u.host.toLowerCase()}${path}${query}`;
}

module.exports = { sensitiveCategory, sanitizeUrl, isTokenLike };
