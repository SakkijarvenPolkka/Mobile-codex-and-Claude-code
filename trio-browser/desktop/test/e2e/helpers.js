'use strict';
/** Launching Trio under Playwright's Electron driver with a temporary profile and mock services. */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { _electron } = require('playwright');
const { startMockServer, mockProviders } = require('./mock-provider');

const ROOT = path.join(__dirname, '..', '..');
const SHARED = path.join(ROOT, '..', 'shared');
const SHOTS = path.join(__dirname, '__screenshots__');

/** @param {number} port */
function makeSharedDir(port) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trio-shared-'));
  fs.cpSync(SHARED, dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'providers.json'), JSON.stringify(mockProviders(port)));
  const engines = JSON.parse(fs.readFileSync(path.join(SHARED, 'search-engines.json'), 'utf8'));
  engines.engines.unshift({ id: 'mock', name: 'MockSearch', searchTemplate: `http://search.test:${port}/search?q={searchTerms}`, suggestTemplate: null, suggestFormat: null });
  engines.recognizers.unshift({ engineId: 'mock', name: 'MockSearch', host: '^search\\.test$', path: '^/search$', params: ['q'] });
  fs.writeFileSync(path.join(dir, 'search-engines.json'), JSON.stringify(engines));
  return dir;
}

/**
 * @param {{ settings?: object, locale?: 'ko' | 'en', profile?: string, mock?: Awaited<ReturnType<typeof startMockServer>> }} [opts]
 */
async function launchTrio(opts = {}) {
  const mock = opts.mock || (await startMockServer());
  const shared = makeSharedDir(mock.port);
  const profile = opts.profile || fs.mkdtempSync(path.join(os.tmpdir(), 'trio-profile-'));
  const settings = { general: { language: opts.locale || 'ko', startup: 'newtab' }, search: { engine: 'mock' }, ...(opts.settings || {}) };
  if (!fs.existsSync(path.join(profile, 'settings.json'))) fs.writeFileSync(path.join(profile, 'settings.json'), JSON.stringify(settings));
  const app = await _electron.launch({
    executablePath: path.join(ROOT, 'node_modules', 'electron', 'dist', 'electron'),
    args: ['--no-sandbox', ROOT],
    cwd: ROOT,
    env: { ...process.env, TRIO_PROFILE_DIR: profile, TRIO_SHARED_DIR: shared, TRIO_HOST_RULES: 'MAP *.test 127.0.0.1', ELECTRON_ENABLE_LOGGING: '0' },
  });
  /** @type {string[]} */
  const errors = [];
  const watch = (/** @type {import('playwright').Page} */ p) => {
    p.on('pageerror', (e) => errors.push(`${p.url()}: ${e.message}`));
    p.on('console', (m) => {
      if (m.type() === 'error' && p.url().startsWith('trio://')) errors.push(`${p.url()}: ${m.text()}`);
    });
  };
  app.windows().forEach(watch);
  app.on('window', watch);

  const t = {
    app,
    mock,
    profile,
    shared,
    errors,
    origin: (/** @type {string} */ host) => `http://${host}.test:${mock.port}`,
    /** Waits for a page (web contents) whose URL matches. */
    async page(/** @type {(url: string) => boolean} */ match, timeout = 10000) {
      const end = Date.now() + timeout;
      for (;;) {
        const p = app.windows().find((w) => {
          try {
            return match(w.url());
          } catch {
            return false;
          }
        });
        if (p) return p;
        if (Date.now() > end) throw new Error(`No page matched; open: ${app.windows().map((w) => w.url()).join(', ')}`);
        await new Promise((r) => setTimeout(r, 100));
      }
    },
    chrome() {
      return t.page((u) => u.startsWith('trio://chrome/'));
    },
    agentUi(/** @type {string} */ id) {
      return t.page((u) => u.startsWith(`trio://agent/?agent=${id}`));
    },
    /**
     * Sends a key press through Chromium's input pipeline (so before-input-event shortcuts fire),
     * to the first web contents whose URL starts with `urlPrefix`.
     * @param {string} urlPrefix
     * @param {string} keyCode Electron key code, e.g. "F", "Tab", "1"
     * @param {string[]} [modifiers] e.g. ["control", "shift"]
     */
    key(urlPrefix, keyCode, modifiers = []) {
      return app.evaluate(({ webContents }, a) => {
        const wc = webContents.getAllWebContents().find((w) => w.getURL().startsWith(a.urlPrefix));
        if (!wc) throw new Error('no web contents for ' + a.urlPrefix);
        wc.sendInputEvent({ type: 'keyDown', keyCode: a.keyCode, modifiers: a.modifiers });
        wc.sendInputEvent({ type: 'keyUp', keyCode: a.keyCode, modifiers: a.modifiers });
      }, { urlPrefix, keyCode, modifiers });
    },
    /** Info about every native window. */
    windowsInfo() {
      return app.evaluate(({ BaseWindow }) =>
        BaseWindow.getAllWindows().map((w) => ({ id: w.id, title: w.getTitle(), visible: w.isVisible(), views: w.contentView.children.length, bounds: w.getBounds(), alwaysOnTop: w.isAlwaysOnTop() })),
      );
    },
    /** Polls until fn returns a truthy value. */
    async until(/** @type {() => any} */ fn, timeout = 10000, label = 'condition') {
      const end = Date.now() + timeout;
      let last;
      for (;;) {
        try {
          last = await fn();
          if (last) return last;
        } catch (e) {
          last = e;
        }
        if (Date.now() > end) throw new Error(`Timed out waiting for ${label}; last: ${last instanceof Error ? last.message : JSON.stringify(last)}`);
        await new Promise((r) => setTimeout(r, 100));
      }
    },
    /** Screenshot of the whole virtual screen (all windows), for visual review. */
    async shot(/** @type {string} */ name) {
      fs.mkdirSync(SHOTS, { recursive: true });
      await new Promise((r) => setTimeout(r, 400)); // let every window repaint
      try {
        execFileSync('import', ['-window', 'root', path.join(SHOTS, `${name}.png`)], { stdio: 'ignore' });
      } catch {
        /* screenshots are best effort */
      }
    },
    async close() {
      await app.close().catch(() => {});
      if (!opts.mock) await mock.close();
    },
  };
  return t;
}

/** Types into the address bar and presses Enter. */
async function go(/** @type {import('playwright').Page} */ chrome, /** @type {string} */ text) {
  const input = chrome.getByTestId('address-input');
  await input.click();
  await input.fill(text);
  await chrome.waitForTimeout(150);
  await input.press('Enter');
}

module.exports = { launchTrio, go, SHOTS };
