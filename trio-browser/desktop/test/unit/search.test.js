'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const data = require('../../src/core/data');
const { buildSearchUrl, extractSearch, parseSuggestions } = require('../../src/core/search');

const v = data.readShared('test-vectors/search.json');
const engines = data.searchEngines();

for (const c of v.build) test(`buildSearchUrl ${c.engineId} ${JSON.stringify(c.query)}`, () => assert.equal(buildSearchUrl(engines, c.engineId, c.query), c.out));
for (const c of v.extract) test(`extractSearch ${c.url}`, () => assert.deepEqual(extractSearch(engines, c.url), c.out));
for (const c of v.suggestions) test(`parseSuggestions ${c.format}`, () => assert.deepEqual(parseSuggestions(c.format, c.json), c.out));
