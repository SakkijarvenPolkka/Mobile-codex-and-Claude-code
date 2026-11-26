'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const dir = path.join(__dirname, '..', '..', 'src', 'renderer', 'common', 'i18n');
const ko = JSON.parse(fs.readFileSync(path.join(dir, 'ko.json'), 'utf8'));
const en = JSON.parse(fs.readFileSync(path.join(dir, 'en.json'), 'utf8'));

test('Korean and English strings have the same keys and placeholders', () => {
  assert.deepEqual(Object.keys(ko).sort(), Object.keys(en).sort());
  for (const key of Object.keys(ko)) {
    const ph = (/** @type {string} */ s) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
    assert.deepEqual(ph(ko[key]), ph(en[key]), key);
  }
});

test('no Korean particle with a consonant/vowel variant follows a placeholder', () => {
  for (const [key, s] of Object.entries(ko)) {
    assert.ok(!/\}(을|를|이|가|은|는|와|과|으로|\(을\)|\(이\)|\(은\)|\(와\)|\(으\))/.test(/** @type {string} */ (s)), `${key}: ${s}`);
  }
});

test('every string key referenced in code exists', () => {
  const src = path.join(__dirname, '..', '..', 'src');
  /** @type {string[]} */
  const files = [];
  const walk = (/** @type {string} */ d) => {
    for (const f of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, f.name);
      if (f.isDirectory()) walk(p);
      else if (/\.(js|html)$/.test(f.name)) files.push(p);
    }
  };
  walk(src);
  const keys = new Set();
  for (const f of files) {
    const text = fs.readFileSync(f, 'utf8');
    for (const m of text.matchAll(/\bt\('([a-z][\w.+-]*)'/g)) keys.add(m[1]);
    for (const m of text.matchAll(/data-i18n(?:-[a-z-]+)?="([\w.]+)"/g)) keys.add(m[1]);
    for (const m of text.matchAll(/labelKey: '([\w.]+)'/g)) keys.add(m[1]);
  }
  const missing = [...keys].filter((k) => !(k in ko));
  assert.deepEqual(missing, []);
});
