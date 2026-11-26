'use strict';
/**
 * URL and host helpers shared by the address bar, history and privacy code. Pure functions.
 * The Android app implements the same rules in Kotlin (trio-browser/android/core); shared test
 * vectors in trio-browser/shared/test-vectors keep the two in step.
 */

const { domainToASCII } = require('node:url');

/**
 * @typedef {{ tlds: Set<string>, multiLevelSuffixes: Set<string> }} SuffixData
 */

/**
 * @param {{ tlds: string[], multiLevelSuffixes: string[] }} json contents of shared/site-suffixes.json
 * @returns {SuffixData}
 */
function loadSuffixData(json) {
  return { tlds: new Set(json.tlds), multiLevelSuffixes: new Set(json.multiLevelSuffixes) };
}

/**
 * Parses an absolute URL, returning null instead of throwing.
 * @param {string} text
 * @returns {URL | null}
 */
function parseUrl(text) {
  try {
    return new URL(text);
  } catch {
    return null;
  }
}

/**
 * The host of a URL, lower-case, without brackets for IPv6. '' when the URL has none.
 * @param {string} url
 */
function hostOf(url) {
  const u = parseUrl(url);
  if (!u) return '';
  return u.hostname.replace(/^\[|\]$/g, '').toLowerCase();
}

/** @param {string} host */
function isIpv4(host) {
  const parts = host.split('.');
  if (parts.length !== 4) return false;
  return parts.every((p) => /^\d{1,3}$/.test(p) && Number(p) <= 255);
}

/** @param {string} host without brackets */
function isIpv6(host) {
  return host.includes(':') && /^[0-9a-f:.]+$/i.test(host);
}

/**
 * Hosts that point at the user's own machine or local network.
 * @param {string} host lower-case, without brackets
 * @param {string[]} [localSuffixes] e.g. ["localhost", "local", "lan"]
 */
function isLocalHost(host, localSuffixes = ['localhost', 'local', 'internal', 'lan', 'home.arpa']) {
  if (!host) return false;
  if (localSuffixes.some((s) => host === s || host.endsWith('.' + s))) return true;
  if (isIpv4(host)) {
    const [a, b] = host.split('.').map(Number);
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  if (isIpv6(host)) {
    const h = host.toLowerCase();
    return h === '::1' || h === '::' || h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd');
  }
  return !host.includes('.');
}

/**
 * Converts a (possibly Unicode) host label sequence to ASCII. Returns '' when invalid.
 * @param {string} host
 */
function toAsciiHost(host) {
  return domainToASCII(host.toLowerCase());
}

/**
 * The registrable site used to group hosts: "news.naver.com" -> "naver.com",
 * "www.bbc.co.uk" -> "bbc.co.uk", "me.github.io" -> "me.github.io". IP addresses and single
 * labels are returned unchanged.
 * @param {string} host
 * @param {SuffixData} data
 */
function siteKey(host, data) {
  let h = host.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (!h || isIpv4(h) || isIpv6(h)) return h;
  const labels = h.split('.');
  if (labels.length <= 2) return h.replace(/^www\./, '');
  for (let i = 1; i < labels.length - 1; i++) {
    const suffix = labels.slice(i).join('.');
    if (data.multiLevelSuffixes.has(suffix)) return labels.slice(i - 1).join('.');
  }
  return labels.slice(-2).join('.');
}

/**
 * True when the last label of the host is a top-level domain from the IANA list.
 * @param {string} host Unicode or ASCII
 * @param {SuffixData} data
 */
function hasKnownTld(host, data) {
  const ascii = toAsciiHost(host.replace(/\.$/, ''));
  if (!ascii) return false;
  const tld = ascii.slice(ascii.lastIndexOf('.') + 1);
  return data.tlds.has(tld);
}

/**
 * The text shown in the address bar for a committed URL: unchanged except that a trailing slash
 * after a bare host is dropped ("https://example.com/" -> "https://example.com").
 * @param {string} url
 */
function displayUrl(url) {
  const u = parseUrl(url);
  if (!u) return url;
  if ((u.protocol === 'http:' || u.protocol === 'https:') && u.pathname === '/' && !u.search && !u.hash) {
    return `${u.protocol}//${u.host}`;
  }
  return url;
}

module.exports = { loadSuffixData, parseUrl, hostOf, isIpv4, isIpv6, isLocalHost, toAsciiHost, siteKey, hasKnownTld, displayUrl };
