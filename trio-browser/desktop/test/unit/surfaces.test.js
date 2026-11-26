'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { SURFACES } = require('../../src/main/surfaces');

test('the preload exposes exactly the surface APIs the main process handles', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'preload', 'trio.js'), 'utf8');
  const literal = /const SURFACES = (\{[\s\S]*?\n\});/.exec(src);
  assert.ok(literal, 'SURFACES literal found in preload');
  const fromPreload = vm.runInNewContext(`(${literal[1]})`);
  assert.deepEqual(JSON.parse(JSON.stringify(fromPreload)), JSON.parse(JSON.stringify(SURFACES)));
});

test('every API method in the contract is wired', () => {
  const contract = fs.readFileSync(path.join(__dirname, '..', '..', 'src', 'types', 'contract.d.ts'), 'utf8');
  for (const [surface, iface] of [['chrome', 'ChromeApi'], ['agent', 'AgentApi'], ['dock', 'DockApi'], ['pages', 'PagesApi']]) {
    const body = new RegExp(`export interface ${iface} \\{([\\s\\S]*?)\\n\\}`).exec(contract);
    assert.ok(body, iface);
    const names = [...body[1].matchAll(/^\s{2}(\w+)\(/gm)].map((m) => m[1]);
    const methods = names.filter((n) => !/^on[A-Z]/.test(n));
    const events = names.filter((n) => /^on[A-Z]/.test(n)).map((n) => n[2].toLowerCase() + n.slice(3));
    assert.deepEqual([...methods].sort(), [...SURFACES[surface].methods].sort(), `${surface} methods`);
    assert.deepEqual([...events].sort(), [...SURFACES[surface].events].sort(), `${surface} events`);
  }
});
