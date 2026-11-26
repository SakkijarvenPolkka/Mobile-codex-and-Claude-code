'use strict';
/**
 * On-device browsing-history analytics. Every number is defined here precisely so the Kotlin
 * port produces identical results; shared/test-vectors/history-stats.json pins them.
 *
 * Definitions
 * - Only http(s) visits are considered. Visits with transition "reload" or "redirect" are not
 *   counted as visits (but still mark a site as seen for new/returning).
 * - In range: rangeStart <= time <= now. rangeStart: "today" = local midnight of `now`;
 *   "7d" / "30d" / "90d" = now - N x 24 h; "all" = no lower bound.
 * - Excluded: a counted in-range visit whose host is in extraExcludedHosts, or (when
 *   excludeSensitive) whose URL has a sensitive category. Excluded visits appear only in
 *   `excluded`.
 * - Site = siteKey(host). Page = sanitizeUrl(url).
 * - Sessions: kept visits sorted by (time, id); a gap of more than sessionGapMinutes between
 *   consecutive visit times starts a new session. Session length = last.time +
 *   (last.durationMs or 0) - first.time.
 * - Rounding: Math.round on non-negative values (half up). Sorting ties are broken exactly as
 *   written next to each list, strings compared by UTF-16 code units.
 */

const { siteKey } = require('./url');
const { extractSearch } = require('./search');
const { sensitiveCategory, sanitizeUrl } = require('./privacy');
const { localParts, startOfLocalDay, localDate } = require('./time');

const DAY = 24 * 60 * 60 * 1000;

const STOPWORDS = new Set([
  'the', 'and', 'for', 'with', 'what', 'how', 'why', 'who', 'when', 'where', 'is', 'are', 'to', 'of', 'in', 'on', 'a', 'an', 'vs',
  'or', 'by', 'at', 'it', 'be', 'do', 'does', 'can', 'my', 'me', 'from',
  '방법', '하는', '하기', '그리고', '또는', '이란', '뜻', '무엇', '어떻게', '왜', '추천', '정리',
]);

/**
 * @typedef {import('../types/contract').Visit} Visit
 * @typedef {import('../types/contract').HistoryRange} HistoryRange
 * @typedef {{
 *   now: number, timeZone: string, range: HistoryRange, excludeSensitive: boolean,
 *   extraExcludedHosts: string[], sessionGapMinutes?: number
 * }} StatsOptions
 * @typedef {{
 *   suffixes: import('./url').SuffixData, rules: import('./privacy').PrivacyRules,
 *   searchData: import('./search').SearchData
 * }} StatsData
 */

/** @param {string} a @param {string} b */
function cmp(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * @param {HistoryRange} range
 * @param {number} now
 * @param {string} timeZone
 */
function rangeStart(range, now, timeZone) {
  switch (range) {
    case 'today':
      return startOfLocalDay(now, timeZone);
    case '7d':
      return now - 7 * DAY;
    case '30d':
      return now - 30 * DAY;
    case '90d':
      return now - 90 * DAY;
    default:
      return -Infinity;
  }
}

/** @param {string} query */
function normalizeQuery(query) {
  return query.replace(/\s+/g, ' ').trim().toLowerCase();
}

/** @param {string} query normalised */
function queryTerms(query) {
  return query
    .split(/[\s\p{P}\p{S}]+/u)
    .filter((t) => [...t].length >= 2 && !/^\d+$/.test(t) && !STOPWORDS.has(t));
}

/**
 * @param {Visit[]} visits all known visits (any time)
 * @param {StatsOptions} options
 * @param {StatsData} data
 */
function computeHistoryStats(visits, options, data) {
  const { now, timeZone, range } = options;
  const gap = (options.sessionGapMinutes ?? 30) * 60 * 1000;
  const start = rangeStart(range, now, timeZone);

  /** @type {Map<string, number>} */
  const firstSeen = new Map();
  /** @type {{ v: Visit, site: string, page: string, search: { engineId: string, query: string } | null }[]} */
  const kept = [];
  let excluded = 0;

  for (const v of visits) {
    let u;
    try {
      u = new URL(v.url);
    } catch {
      continue;
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') continue;
    const site = siteKey(u.hostname, data.suffixes);
    const seen = firstSeen.get(site);
    if (seen === undefined || v.time < seen) firstSeen.set(site, v.time);

    if (v.transition === 'reload' || v.transition === 'redirect') continue;
    if (v.time < start || v.time > now) continue;
    const userExcluded = sensitiveCategory(v.url, { ...data.rules, categories: {} }, options.extraExcludedHosts) === 'user';
    if (userExcluded || (options.excludeSensitive && sensitiveCategory(v.url, data.rules) !== null)) {
      excluded++;
      continue;
    }
    const page = /** @type {string} */ (sanitizeUrl(v.url, data.rules, data.searchData));
    const s = extractSearch(data.searchData, v.url);
    kept.push({ v, site, page, search: s ? { engineId: s.engineId, query: normalizeQuery(s.query) } : null });
  }

  kept.sort((a, b) => a.v.time - b.v.time || a.v.id - b.v.id);

  const hours = new Array(24).fill(0);
  const weekdays = new Array(7).fill(0);
  /** @type {Set<string>} */
  const days = new Set();
  /** @type {Map<string, { visits: number, durationMs: number }>} */
  const sites = new Map();
  /** @type {Map<string, { title: string, site: string, visits: number, durationMs: number, lastTime: number }>} */
  const pages = new Map();
  /** @type {Map<string, { count: number, lastTime: number }>} */
  const queries = new Map();
  /** @type {Map<string, number>} */
  const terms = new Map();
  let totalDuration = 0;
  let searches = 0;

  for (const { v, site, page, search } of kept) {
    const p = localParts(v.time, timeZone);
    hours[p.hour]++;
    weekdays[p.weekday]++;
    days.add(localDate(v.time, timeZone));
    const dur = v.durationMs && v.durationMs > 0 ? v.durationMs : 0;
    totalDuration += dur;

    const s = sites.get(site) || { visits: 0, durationMs: 0 };
    s.visits++;
    s.durationMs += dur;
    sites.set(site, s);

    const pg = pages.get(page) || { title: '', site, visits: 0, durationMs: 0, lastTime: 0 };
    pg.visits++;
    pg.durationMs += dur;
    if (v.time >= pg.lastTime) {
      pg.lastTime = v.time;
      if (v.title.trim()) pg.title = v.title.trim();
    } else if (!pg.title && v.title.trim()) pg.title = v.title.trim();
    pages.set(page, pg);

    if (search) {
      searches++;
      const q = queries.get(search.query) || { count: 0, lastTime: 0 };
      q.count++;
      q.lastTime = Math.max(q.lastTime, v.time);
      queries.set(search.query, q);
      for (const t of queryTerms(search.query)) terms.set(t, (terms.get(t) || 0) + 1);
    }
  }

  // Sessions
  let sessionCount = 0;
  let sessionTotal = 0;
  let sessionLongest = 0;
  for (let i = 0; i < kept.length; ) {
    let j = i;
    while (j + 1 < kept.length && kept[j + 1].v.time - kept[j].v.time <= gap) j++;
    const first = kept[i].v;
    const last = kept[j].v;
    const length = last.time + (last.durationMs && last.durationMs > 0 ? last.durationMs : 0) - first.time;
    sessionCount++;
    sessionTotal += length;
    sessionLongest = Math.max(sessionLongest, length);
    i = j + 1;
  }

  const siteList = [...sites.entries()].map(([site, s]) => ({ site, visits: s.visits, durationMs: s.durationMs }));
  const topSitesByVisits = [...siteList]
    .sort((a, b) => b.visits - a.visits || b.durationMs - a.durationMs || cmp(a.site, b.site))
    .slice(0, 10);
  const topSitesByTime = siteList
    .filter((s) => s.durationMs > 0)
    .sort((a, b) => b.durationMs - a.durationMs || b.visits - a.visits || cmp(a.site, b.site))
    .slice(0, 10);

  const pageList = [...pages.entries()].map(([url, p]) => ({ url, ...p }));
  const notablePages = [...pageList]
    .sort((a, b) => b.visits - a.visits || b.durationMs - a.durationMs || b.lastTime - a.lastTime || cmp(a.url, b.url))
    .slice(0, 15)
    .map((p) => ({ title: p.title, url: p.url, site: p.site, visits: p.visits, durationMs: p.durationMs }));
  const recentPages = [...pageList]
    .sort((a, b) => b.lastTime - a.lastTime || cmp(a.url, b.url))
    .slice(0, 40)
    .map((p) => ({ title: p.title, url: p.url, site: p.site, time: p.lastTime }));

  const topQueries = [...queries.entries()]
    .sort((a, b) => b[1].count - a[1].count || b[1].lastTime - a[1].lastTime || cmp(a[0], b[0]))
    .slice(0, 15)
    .map(([query, q]) => ({ query, count: q.count }));
  const topTerms = [...terms.entries()]
    .sort((a, b) => b[1] - a[1] || cmp(a[0], b[0]))
    .slice(0, 20)
    .map(([term, count]) => ({ term, count }));

  let newSites = /** @type {number | null} */ (null);
  let returningSites = /** @type {number | null} */ (null);
  if (Number.isFinite(start)) {
    newSites = 0;
    returningSites = 0;
    for (const site of sites.keys()) {
      if (/** @type {number} */ (firstSeen.get(site)) >= start) newSites++;
      else returningSites++;
    }
  }

  return {
    range,
    timeZone,
    from: Number.isFinite(start) ? start : kept.length ? kept[0].v.time : now,
    to: now,
    totals: {
      visits: kept.length,
      sites: sites.size,
      pages: pages.size,
      searches,
      activeDays: days.size,
      durationMs: totalDuration,
      excluded,
    },
    topSitesByVisits,
    topSitesByTime,
    hours,
    weekdays,
    sessions: {
      count: sessionCount,
      averageMinutes: sessionCount ? Math.round(sessionTotal / sessionCount / 60000) : 0,
      longestMinutes: Math.round(sessionLongest / 60000),
    },
    topQueries,
    topTerms,
    newSites,
    returningSites,
    notablePages,
    recentPages,
  };
}

module.exports = { computeHistoryStats, rangeStart, normalizeQuery, queryTerms };
