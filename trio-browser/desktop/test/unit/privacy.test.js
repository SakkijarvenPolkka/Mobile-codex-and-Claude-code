'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const data = require('../../src/core/data');
const { sensitiveCategory, sanitizeUrl } = require('../../src/core/privacy');

const v = data.readShared('test-vectors/privacy.json');
const rules = data.privacyRules();
const engines = data.searchEngines();

for (const c of v.category) test(`sensitiveCategory ${c.url}`, () => assert.equal(sensitiveCategory(c.url, rules, c.extraHosts || []), c.out));
for (const c of v.sanitize) test(`sanitizeUrl ${c.url}`, () => assert.equal(sanitizeUrl(c.url, rules, engines), c.out));
