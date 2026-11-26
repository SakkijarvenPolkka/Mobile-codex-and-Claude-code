'use strict';
/**
 * Turns history statistics into the plain-text digest the user previews and hands to an agent.
 * Deterministic, locale-aware (ko / en), and bounded by a character limit.
 */

const { localDate, localTime } = require('./time');

/**
 * @typedef {ReturnType<typeof import('./history-stats').computeHistoryStats>} HistoryStats
 * @typedef {import('../types/contract').HistoryOptions} HistoryOptions
 * @typedef {{ locale: 'ko' | 'en', include: HistoryOptions['include'], kind: HistoryOptions['kind'], charLimit: number }} DigestOptions
 */

/** 1234567 -> "1,234,567" @param {number} n */
function formatInt(n) {
  const s = String(Math.round(Math.abs(n)));
  const out = s.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return n < 0 ? '-' + out : out;
}

/**
 * @param {number} ms
 * @param {'ko' | 'en'} locale
 */
function formatDuration(ms, locale) {
  const minutes = Math.round(ms / 60000);
  if (minutes < 1) return locale === 'ko' ? '1분 미만' : '<1 min';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (locale === 'ko') return h ? (m ? `${h}시간 ${m}분` : `${h}시간`) : `${m}분`;
  return h ? (m ? `${h} h ${m} min` : `${h} h`) : `${m} min`;
}

const T = {
  ko: {
    range: { today: '오늘', '7d': '최근 7일', '30d': '최근 30일', '90d': '최근 90일', all: '전체 기간' },
    period: (/** @type {string} */ a, /** @type {string} */ b, /** @type {string} */ r, /** @type {string} */ tz) => `기간: ${a} ~ ${b} (${r}, 시간대 ${tz})`,
    totals: (/** @type {any} */ t) =>
      `방문 ${formatInt(t.visits)}건 · 사이트 ${formatInt(t.sites)}곳 · 페이지 ${formatInt(t.pages)}개 · 검색 ${formatInt(t.searches)}건 · 활동한 날 ${formatInt(t.activeDays)}일` +
      (t.excluded ? ` (민감한 사이트 방문 ${formatInt(t.excluded)}건 제외)` : ''),
    empty: '이 기간에는 분석할 방문 기록이 없습니다.',
    topVisits: '자주 방문한 사이트 (방문 수 기준):',
    topTime: '오래 머문 사이트 (머문 시간 기준):',
    visitsTime: (/** @type {number} */ v, /** @type {string} */ d) => `${formatInt(v)}회` + (d ? `, ${d}` : ''),
    hours: '시간대별 방문 수:',
    hourLabel: (/** @type {number} */ h) => `${h}시`,
    weekdays: '요일별 방문 수:',
    weekdayNames: ['월', '화', '수', '목', '금', '토', '일'],
    sessions: (/** @type {any} */ s) => `브라우징 세션: ${formatInt(s.count)}회, 평균 ${formatDuration(s.averageMinutes * 60000, 'ko')}, 가장 긴 세션 ${formatDuration(s.longestMinutes * 60000, 'ko')}`,
    queries: '자주 검색한 검색어:',
    times: (/** @type {number} */ n) => `${formatInt(n)}회`,
    terms: '검색어에 자주 나온 단어:',
    newReturning: (/** @type {number} */ n, /** @type {number} */ r) => `처음 방문한 사이트 ${formatInt(n)}곳 · 다시 방문한 사이트 ${formatInt(r)}곳`,
    notable: '많이 본 페이지:',
    recent: '최근 본 페이지:',
    untitled: '(제목 없음)',
    truncated: '… (글자 수 제한으로 나머지는 생략했습니다)',
  },
  en: {
    range: { today: 'today', '7d': 'last 7 days', '30d': 'last 30 days', '90d': 'last 90 days', all: 'all time' },
    period: (/** @type {string} */ a, /** @type {string} */ b, /** @type {string} */ r, /** @type {string} */ tz) => `Period: ${a} to ${b} (${r}, time zone ${tz})`,
    totals: (/** @type {any} */ t) =>
      `${formatInt(t.visits)} visits · ${formatInt(t.sites)} sites · ${formatInt(t.pages)} pages · ${formatInt(t.searches)} searches · ${formatInt(t.activeDays)} active days` +
      (t.excluded ? ` (${formatInt(t.excluded)} visits to sensitive sites removed)` : ''),
    empty: 'There is no browsing history to analyze for this period.',
    topVisits: 'Most visited sites (by visits):',
    topTime: 'Sites with the most time spent:',
    visitsTime: (/** @type {number} */ v, /** @type {string} */ d) => `${formatInt(v)} ${v === 1 ? 'visit' : 'visits'}` + (d ? `, ${d}` : ''),
    hours: 'Visits by hour of day:',
    hourLabel: (/** @type {number} */ h) => `${h}h`,
    weekdays: 'Visits by weekday:',
    weekdayNames: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
    sessions: (/** @type {any} */ s) => `Browsing sessions: ${formatInt(s.count)}, average ${formatDuration(s.averageMinutes * 60000, 'en')}, longest ${formatDuration(s.longestMinutes * 60000, 'en')}`,
    queries: 'Most frequent searches:',
    times: (/** @type {number} */ n) => `${formatInt(n)}×`,
    terms: 'Most frequent search words:',
    newReturning: (/** @type {number} */ n, /** @type {number} */ r) => `${formatInt(n)} new sites · ${formatInt(r)} returning sites`,
    notable: 'Most viewed pages:',
    recent: 'Recently viewed pages:',
    untitled: '(untitled)',
    truncated: '… (the rest was left out to stay within the length limit)',
  },
};

/**
 * @param {HistoryStats} stats
 * @param {DigestOptions} options
 * @returns {{ text: string, truncated: boolean }}
 */
function renderHistoryDigest(stats, options) {
  const L = T[options.locale];
  const { include } = options;
  const tz = stats.timeZone;
  /** @type {string[][]} */
  const sections = [];

  sections.push([
    L.period(localDate(stats.from, tz), localDate(stats.to, tz), L.range[stats.range], tz),
    L.totals(stats.totals),
  ]);

  if (stats.totals.visits === 0) {
    sections.push([L.empty]);
    return assemble(sections, options.charLimit, L.truncated);
  }

  sections.push([L.topVisits, ...stats.topSitesByVisits.map((s, i) => `${i + 1}. ${s.site} — ${L.visitsTime(s.visits, s.durationMs ? formatDuration(s.durationMs, options.locale) : '')}`)]);
  if (stats.topSitesByTime.length) {
    sections.push([L.topTime, ...stats.topSitesByTime.map((s, i) => `${i + 1}. ${s.site} — ${formatDuration(s.durationMs, options.locale)}`)]);
  }
  sections.push([L.hours, stats.hours.map((n, h) => `${L.hourLabel(h)} ${n}`).join(' · ')]);
  sections.push([L.weekdays, stats.weekdays.map((n, d) => `${L.weekdayNames[d]} ${n}`).join(' · ')]);
  sections.push([L.sessions(stats.sessions)]);

  if (include.queries && stats.topQueries.length) {
    sections.push([L.queries, ...stats.topQueries.map((q) => `- ${q.query} (${L.times(q.count)})`)]);
    if (stats.topTerms.length) sections.push([L.terms, stats.topTerms.map((t) => `${t.term}(${t.count})`).join(', ')]);
  }
  if (stats.newSites !== null && stats.returningSites !== null) sections.push([L.newReturning(stats.newSites, stats.returningSites)]);

  /** @param {{ title: string, url: string, site: string }} p */
  const pageLabel = (p) => {
    const where = include.urls === 'paths' ? p.url : p.site;
    return include.titles ? `${p.title || L.untitled} — ${where}` : where;
  };
  const showPages = include.titles || include.urls === 'paths';
  const notable = showPages ? [L.notable, ...stats.notablePages.map((p) => `- ${pageLabel(p)} (${L.times(p.visits)})`)] : null;
  const recent = showPages
    ? [L.recent, ...stats.recentPages.map((p) => `- ${localDate(p.time, tz)} ${localTime(p.time, tz)} ${pageLabel(p)}`)]
    : null;
  if (options.kind === 'find') {
    if (recent) sections.push(recent);
    if (notable) sections.push(notable);
  } else if (notable) sections.push(notable);

  return assemble(sections, options.charLimit, L.truncated);
}

/**
 * Joins sections with blank lines, stopping before the limit.
 * @param {string[][]} sections
 * @param {number} limit
 * @param {string} marker
 */
function assemble(sections, limit, marker) {
  const full = sections.map((s) => s.join('\n')).join('\n\n');
  if (full.length <= limit) return { text: full, truncated: false };
  const budget = Math.max(0, limit - marker.length - 1);
  let out = '';
  outer: for (let i = 0; i < sections.length; i++) {
    for (let j = 0; j < sections[i].length; j++) {
      const sep = out ? (j === 0 ? '\n\n' : '\n') : '';
      const next = sep + sections[i][j];
      if (out.length + next.length > budget) break outer;
      out += next;
    }
  }
  return { text: out ? `${out}\n${marker}` : marker.slice(0, limit), truncated: true };
}

module.exports = { renderHistoryDigest, formatInt, formatDuration };
