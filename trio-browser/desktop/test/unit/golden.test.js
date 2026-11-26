'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');

test('golden vectors match the reference implementation', () => {
  const out = execFileSync(process.execPath, [path.join(__dirname, '..', '..', 'scripts', 'make-golden.js'), '--check'], { encoding: 'utf8' });
  assert.match(out, /up to date/);
});
