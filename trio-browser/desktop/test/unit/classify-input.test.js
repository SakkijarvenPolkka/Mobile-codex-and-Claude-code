'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const data = require('../../src/core/data');
const { classifyInput, agentKeywordMap } = require('../../src/core/classify-input');

const vectors = data.readShared('test-vectors/classify-input.json');
const options = { agentKeywords: agentKeywordMap(data.providers()), suffixes: data.suffixData() };

for (const c of vectors.cases) {
  test(`classifyInput(${JSON.stringify(c.in)})`, () => {
    assert.deepEqual(classifyInput(c.in, options), c.out);
  });
}
