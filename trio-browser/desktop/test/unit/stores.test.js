'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { SettingsStore } = require('../../src/main/settings');
const { HistoryDb } = require('../../src/main/history-db');
const { JsonStore } = require('../../src/main/json-store');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'trio-test-'));

test('settings: defaults, dotted set, validation and persistence', () => {
  const dir = tmp();
  const file = path.join(dir, 'settings.json');
  const s = new SettingsStore(file, ['google', 'naver']);
  assert.equal(s.get().search.engine, 'google');
  assert.equal(s.set('search.engine', 'naver').search.engine, 'naver');
  assert.equal(s.set('search.engine', 'nope').search.engine, 'google', 'invalid values fall back to the default');
  s.set('search.engine', 'naver');
  assert.equal(s.set('agents.pageCharLimit', 5).agents.pageCharLimit, 12000);
  assert.equal(s.set('agents.enabled.gemini', false).agents.enabled.gemini, false);
  assert.equal(s.set('__proto__.x', 1).search.engine, 'naver');
  assert.equal(s.set('nonexistent.key', 1).search.engine, 'naver');
  s.set('privacy.extraExcludedHosts', [' Example.COM ', 'bad host', 3]);
  assert.deepEqual(s.get().privacy.extraExcludedHosts, ['example.com']);
  s.setPermission('https://example.com', 'geolocation', 'allow');
  assert.deepEqual(s.get().permissions, { 'https://example.com': { geolocation: 'allow' } });
  s.setPermission('https://example.com', 'geolocation', null);
  assert.deepEqual(s.get().permissions, {});
  let changed = 0;
  s.on('change', () => changed++);
  s.set('general.theme', 'dark');
  assert.equal(changed, 1);
  s.flush();
  const again = new SettingsStore(file, ['google', 'naver']);
  assert.equal(again.get().search.engine, 'naver');
  assert.equal(again.get().general.theme, 'dark');
});

test('json store: corrupt file falls back to defaults and is kept aside', () => {
  const dir = tmp();
  const file = path.join(dir, 'x.json');
  fs.writeFileSync(file, '{not json');
  const store = new JsonStore(file, () => ({ a: 1 }), (raw) => raw);
  assert.deepEqual(store.get(), { a: 1 });
  assert.ok(fs.existsSync(file + '.corrupt'));
});

test('history: visits, titles, durations, search and deletion', () => {
  const db = new HistoryDb(':memory:');
  const t0 = Date.parse('2026-11-26T00:00:00Z');
  const a = db.addVisit({ url: 'https://news.naver.com/a', title: '크로뮴 브라우저 소식', time: t0, transition: 'typed', fromVisitId: null });
  const b = db.addVisit({ url: 'https://example.com/%EA%B2%BD%EB%A1%9C', title: 'Example', time: t0 + 1000, transition: 'link', fromVisitId: a });
  db.addVisit({ url: 'https://news.naver.com/a', title: '', time: t0 + 2000, transition: 'reload', fromVisitId: null });
  db.setTitle(b, 'Example Domain', 'https://example.com/%EA%B2%BD%EB%A1%9C');
  db.setTitle(b, 'Wrong page', 'https://other.example/');
  db.setDuration(a, 61234.4, 'https://news.naver.com/a');
  db.setDuration(b, 999, 'https://not-this-page.example/');

  assert.deepEqual(db.query({ text: '', beforeTime: null, limit: 10 }).entries.map((e) => e.visitId), [b, a], 'reloads are hidden');
  assert.deepEqual(db.query({ text: '브라우', beforeTime: null, limit: 10 }).entries.map((e) => e.visitId), [a], '2-character Korean substring');
  assert.deepEqual(db.query({ text: '경로', beforeTime: null, limit: 10 }).entries.map((e) => e.visitId), [b], 'decoded URL text');
  assert.deepEqual(db.query({ text: 'example domain', beforeTime: null, limit: 10 }).entries.map((e) => e.title), ['Example Domain']);
  assert.equal(db.query({ text: '100%', beforeTime: null, limit: 10 }).entries.length, 0, 'wildcards are literal');
  const page = db.query({ text: '', beforeTime: null, limit: 1 });
  assert.equal(page.hasMore, true);
  assert.deepEqual(db.query({ text: '', beforeTime: page.entries[0].time, limit: 5 }).entries.map((e) => e.visitId), [a]);

  assert.deepEqual(db.suggest('news', 5).map((r) => r.url), ['https://news.naver.com/a']);
  assert.deepEqual(db.suggest('exam dom', 5).map((r) => r.url), ['https://example.com/%EA%B2%BD%EB%A1%9C']);

  const all = db.allVisits();
  assert.equal(all.length, 3);
  assert.equal(all[0].durationMs, 61234);
  assert.equal(all[1].fromVisitId, a);
  assert.equal(all[1].durationMs, null, 'a visit is only updated for its own URL');

  db.setFavicon('news.naver.com', 'data:image/png;base64,AA==');
  assert.equal(db.query({ text: 'naver', beforeTime: null, limit: 5 }).entries[0].faviconUrl, 'data:image/png;base64,AA==');
  assert.deepEqual(db.topSites(t0 + 5000, 8).map((x) => x.url), ['https://example.com/', 'https://news.naver.com/'], 'one origin per site, ties by recency');

  db.deleteVisits([b]);
  assert.equal(db.suggest('example', 5).length, 0, 'URL removed with its last visit');
  db.deleteSince(t0);
  assert.equal(db.allVisits().length, 0);
  const fresh = db.addVisit({ url: 'https://new.example/', title: 'New', time: t0 + 9000, transition: 'link', fromVisitId: null });
  assert.ok(fresh > b, 'visit ids are never reused after deletion');
  db.close();
});
