'use strict';
/**
 * Pure rules for the agent login gate: which provider URLs are the chat app and which are
 * sign-in pages, whether the profile holds a provider session, and what the agent window may show.
 */

/**
 * @typedef {import('../types/contract').ProviderConfig} ProviderConfig
 * @typedef {import('../types/contract').CookieRule} CookieRule
 * @typedef {import('../types/contract').AgentLoginState} AgentLoginState
 * @typedef {{ domain: string, name: string, value: string, expirationDate?: number }} CookieLike
 *   expirationDate in seconds since the epoch (Electron and Chromium convention); absent = session cookie
 */

/** @param {string} host @param {string[]} hosts */
function hostIn(host, hosts) {
  return hosts.some((h) => host === h || host.endsWith('.' + h));
}

/**
 * app      a page of the provider's chat app
 * auth     a sign-in / sign-out / identity-provider page
 * external anything else (citations, help pages, other sites)
 * @param {ProviderConfig} provider
 * @param {string} url
 * @returns {'app' | 'auth' | 'external'}
 */
function classifyProviderUrl(provider, url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return 'external';
  }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return 'external';
  const host = u.hostname.toLowerCase();
  if (hostIn(host, provider.appHosts)) {
    const path = u.pathname.toLowerCase();
    return provider.authPathPrefixes.some((p) => path === p.replace(/\/$/, '') || path.startsWith(p)) ? 'auth' : 'app';
  }
  if (hostIn(host, provider.authHosts)) return 'auth';
  return 'external';
}

/**
 * @param {CookieLike} cookie
 * @param {CookieRule} rule
 */
function cookieMatches(cookie, rule) {
  const domain = cookie.domain.replace(/^\./, '').toLowerCase();
  if (domain !== rule.domain && !domain.endsWith('.' + rule.domain)) return false;
  if (rule.name !== undefined) return cookie.name === rule.name;
  return rule.namePrefix !== undefined && cookie.name.startsWith(rule.namePrefix);
}

/**
 * True when the cookies satisfy the provider's session rule.
 * @param {ProviderConfig} provider
 * @param {CookieLike[]} cookies
 * @param {number} nowMs
 */
function hasSession(provider, cookies, nowMs) {
  const live = cookies.filter((c) => c.value && (c.expirationDate === undefined || c.expirationDate * 1000 > nowMs));
  const { anyOf, allOf } = provider.sessionCookies;
  if (allOf && !allOf.every((rule) => live.some((c) => cookieMatches(c, rule)))) return false;
  if (anyOf && anyOf.length && !anyOf.some((rule) => live.some((c) => cookieMatches(c, rule)))) return false;
  return Boolean((allOf && allOf.length) || (anyOf && anyOf.length));
}

/**
 * The domains whose cookies decide the session, for filtering cookie-change events.
 * @param {ProviderConfig} provider
 */
function sessionDomains(provider) {
  const rules = [...(provider.sessionCookies.anyOf || []), ...(provider.sessionCookies.allOf || [])];
  return [...new Set(rules.map((r) => r.domain))];
}

/**
 * The agent's login state.
 * - sessionPresent: hasSession() is true.
 * - staleSession: the app bounced to a sign-in page although the cookie was present; stays true
 *   until the provider's cookies change.
 * - signingIn: the user started the sign-in flow and has not finished or cancelled it.
 * @param {{ sessionPresent: boolean, staleSession: boolean, signingIn: boolean }} s
 * @returns {AgentLoginState}
 */
function deriveAgentState(s) {
  if (s.sessionPresent && !s.staleSession) return 'signedIn';
  return s.signingIn ? 'signingIn' : 'signedOut';
}

/**
 * What the agent window may show for a URL the provider view is on.
 * - signedIn: everything (external links are opened in tabs by the caller).
 * - signingIn: only authentication pages; reaching the app without a session hides it.
 * - signedOut: nothing from the provider.
 * @param {AgentLoginState} state
 * @param {'app' | 'auth' | 'external'} urlKind
 * @returns {'provider' | 'lock'}
 */
function gateView(state, urlKind) {
  if (state === 'signedIn') return 'provider';
  if (state === 'signingIn') return urlKind === 'auth' ? 'provider' : 'lock';
  return 'lock';
}

module.exports = { classifyProviderUrl, cookieMatches, hasSession, sessionDomains, deriveAgentState, gateView };
