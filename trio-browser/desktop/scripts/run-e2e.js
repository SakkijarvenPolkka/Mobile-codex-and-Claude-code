'use strict';
/**
 * Runs the end-to-end tests under a virtual display with a window manager (openbox), so child
 * windows, stacking and focus behave like on a desktop. Usage: node scripts/run-e2e.js [files…]
 */
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');

const root = path.join(__dirname, '..');
const files = process.argv.slice(2).length ? process.argv.slice(2) : fs.readdirSync(path.join(root, 'test', 'e2e')).filter((f) => f.endsWith('.test.js')).map((f) => path.join('test', 'e2e', f));
const inner = [
  'if command -v openbox >/dev/null; then openbox >/dev/null 2>&1 & for i in $(seq 1 50); do xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q "window id" && break; sleep 0.1; done; fi',
  `exec node --test --test-concurrency=1 --test-reporter=spec ${files.map((f) => JSON.stringify(f)).join(' ')}`,
].join('\n');
const hasDisplay = Boolean(process.env.DISPLAY) && !process.env.TRIO_FORCE_XVFB;
const result = hasDisplay
  ? spawnSync('node', ['--test', '--test-concurrency=1', '--test-reporter=spec', ...files], { cwd: root, stdio: 'inherit' })
  : spawnSync('xvfb-run', ['-a', '-s', '-screen 0 1600x1000x24', 'bash', '-c', inner], { cwd: root, stdio: 'inherit' });
process.exit(result.status === null ? 1 : result.status);
