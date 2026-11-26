'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const data = require('../../src/core/data');
const { classifyProviderUrl, hasSession, deriveAgentState, gateView } = require('../../src/core/login-state');
const { planHandoff } = require('../../src/core/handoff');

const v = data.readShared('test-vectors/login-state.json');
const byId = Object.fromEntries(data.providers().map((p) => [p.id, p]));

for (const c of v.urls) test(`classifyProviderUrl ${c.provider} ${c.url}`, () => assert.equal(classifyProviderUrl(byId[c.provider], c.url), c.out));
for (const [i, c] of v.sessions.entries()) test(`hasSession #${i} ${c.provider}`, () => assert.equal(hasSession(byId[c.provider], c.cookies, v.now), c.out));
for (const c of v.states) test(`deriveAgentState ${JSON.stringify(c.in)}`, () => assert.equal(deriveAgentState(c.in), c.out));
for (const c of v.gate) test(`gateView ${c.state} ${c.kind}`, () => assert.equal(gateView(c.state, c.kind), c.out));

test('planHandoff inserts into a new chat by default', () => {
  assert.deepEqual(planHandoff(byId.claude, 'hi', 'new'), { method: 'insert', url: 'https://claude.ai/new' });
  assert.deepEqual(planHandoff(byId.gemini, 'hi', 'current'), { method: 'insert', url: null });
});
test('planHandoff uses a prefill URL only when it fits and does not auto-submit', () => {
  const p = { ...byId.claude, prefill: { template: 'https://x.test/?q={prompt}', maxEncodedChars: 20, autoSubmits: false } };
  assert.deepEqual(planHandoff(p, 'a b', 'new'), { method: 'url', url: 'https://x.test/?q=a%20b' });
  assert.equal(planHandoff(p, 'x'.repeat(21), 'new').method, 'insert');
  assert.equal(planHandoff({ ...p, prefill: { ...p.prefill, autoSubmits: true } }, 'a', 'new').method, 'insert');
  assert.equal(planHandoff(p, 'a', 'current').method, 'insert');
});
test('planHandoff falls back to the clipboard for huge text', () => {
  assert.deepEqual(planHandoff(byId.chatgpt, 'x'.repeat(100001), 'new'), { method: 'clipboard' });
});
