'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const data = require('../../src/core/data');
const { computeHistoryStats, rangeStart, queryTerms } = require('../../src/core/history-stats');

const statsData = { suffixes: data.suffixData(), rules: data.privacyRules(), searchData: data.searchEngines() };
const small = data.readShared('test-vectors/history-small.json');

test('hand-checked scenario', () => {
  const s = computeHistoryStats(small.visits, small.options, statsData);
  for (const [key, expected] of Object.entries(small.expected)) assert.deepEqual(/** @type {any} */ (s)[key], expected, key);
});

test('sensitive sites are kept when exclusion is off', () => {
  const s = computeHistoryStats(small.visits, { ...small.options, excludeSensitive: false }, statsData);
  assert.equal(s.totals.excluded, 0);
  assert.equal(s.totals.visits, 9);
  assert.ok(s.topSitesByVisits.some((x) => x.site === 'kbstar.com'));
});

test('user-excluded hosts are removed even when exclusion is off', () => {
  const s = computeHistoryStats(small.visits, { ...small.options, excludeSensitive: false, extraExcludedHosts: ['github.com'] }, statsData);
  assert.equal(s.totals.excluded, 2);
  assert.ok(!s.topSitesByVisits.some((x) => x.site === 'github.com'));
});

test('today starts at local midnight', () => {
  const now = Date.parse('2026-11-26T12:00:00+09:00');
  assert.equal(rangeStart('today', now, 'Asia/Seoul'), Date.parse('2026-11-26T00:00:00+09:00'));
  // New York switched from EDT to EST on 2026-11-01; midnight that day is still EDT.
  const ny = Date.parse('2026-11-01T15:00:00-05:00');
  assert.equal(rangeStart('today', ny, 'America/New_York'), Date.parse('2026-11-01T00:00:00-04:00'));
  assert.equal(rangeStart('all', now, 'Asia/Seoul'), -Infinity);
});

test('today range keeps only visits since midnight', () => {
  const s = computeHistoryStats(small.visits, { ...small.options, range: 'today' }, statsData);
  assert.equal(s.totals.visits, 6);
  assert.equal(s.totals.activeDays, 1);
});

test('all range has no new/returning split', () => {
  const s = computeHistoryStats(small.visits, { ...small.options, range: 'all' }, statsData);
  assert.equal(s.newSites, null);
  assert.equal(s.totals.visits, 9);
  assert.equal(s.from, small.visits.find((/** @type {any} */ v) => v.id === 9).time);
});

test('empty history', () => {
  const s = computeHistoryStats([], small.options, statsData);
  assert.equal(s.totals.visits, 0);
  assert.deepEqual(s.sessions, { count: 0, averageMinutes: 0, longestMinutes: 0 });
});

test('query terms drop short, numeric and stop words', () => {
  assert.deepEqual(queryTerms('how to use a 3d printer 2026 방법'), ['use', '3d', 'printer']);
  assert.deepEqual(queryTerms('c++ vs rust: 비교!'), ['rust', '비교']);
});
