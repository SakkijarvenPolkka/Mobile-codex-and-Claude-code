'use strict';
// Regression tests for the defects found in the desktop security review.
const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { launchTrio, go } = require('./helpers');

describe('security and robustness', () => {
  /** @type {Awaited<ReturnType<typeof launchTrio>>} */
  let t;
  /** @type {import('playwright').Page} */
  let chrome;
  const titles = async () => chrome.getByTestId('tab-title').allTextContents();

  before(async () => {
    t = await launchTrio();
    chrome = await t.chrome();
    await chrome.getByTestId('tab').first().waitFor();
  });
  after(async () => {
    await t.close();
  });

  it('removes a tab whose page closes itself and keeps the window working', async () => {
    await go(chrome, `${t.origin('news')}/opener`);
    const opener = await t.page((u) => u.endsWith('/opener'));
    await opener.click('#open');
    const closing = await t.page((u) => u.endsWith('/selfclose'));
    await t.until(async () => (await titles()).includes('Self closing'), 5000, 'second tab');
    await closing.click('#close', { noWaitAfter: true }).catch(() => {});
    await t.until(async () => !(await titles()).includes('Self closing'), 5000, 'tab removed');
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await titles()).includes('Test Article'), 10000, 'window still works');
  });

  it('never lets a redirect land on an internal page', async () => {
    await go(chrome, `${t.origin('news')}/redir?to=trio://settings/`);
    await new Promise((r) => setTimeout(r, 1500));
    assert.ok(!t.app.windows().some((w) => w.url().startsWith('trio://settings')), 'settings page not opened');
    const urls = await t.app.evaluate(({ webContents }) => webContents.getAllWebContents().map((w) => w.getURL()));
    assert.ok(!urls.some((u) => u.startsWith('trio://settings')), urls.join(','));
  });

  it('keeps query strings, fragments and line breaks in titles out of page prompts', async () => {
    await go(chrome, `${t.origin('news')}/page?reset_token=SECRET-RESET-123&email=me%40example.com#access_token=OAUTH-XYZ`);
    await t.until(async () => (await titles()).includes('Test Article'), 10000, 'page');
    await chrome.locator('[data-testid="agent-button"][data-agent-id="claude"]').click();
    const ui = await t.agentUi('claude');
    await ui.getByTestId('lock-signin').click();
    const login = await t.page((u) => u.includes('/claude/login'));
    await login.click('#signin');
    await ui.getByTestId('quick-actions').waitFor({ state: 'visible' });
    await ui.locator('[data-testid="quick-action"][data-action-id="summarize_page"]').click();
    await ui.getByTestId('preview').waitFor({ state: 'visible' });
    const text = await ui.getByTestId('preview-text').inputValue();
    for (const secret of ['SECRET-RESET-123', 'me@example.com', 'me%40example.com', 'OAUTH-XYZ', 'reset_token']) assert.ok(!text.includes(secret), secret);
    assert.match(text, /news\.test:\d+\/page/);
    await ui.getByTestId('preview-cancel').click();
  });

  it('extracts only visible text', async () => {
    await go(chrome, `${t.origin('news')}/hidden`);
    await t.until(async () => (await titles()).includes('Hidden text'), 10000, 'page');
    const ui = await t.agentUi('claude');
    await ui.locator('[data-testid="quick-action"][data-action-id="summarize_page"]').click();
    await ui.getByTestId('preview').waitFor({ state: 'visible' });
    const text = await ui.getByTestId('preview-text').inputValue();
    assert.match(text, /VISIBLE-TEXT/);
    for (const hidden of ['VISHIDDEN', 'OPACITY', 'OFFSCREEN', 'FONTZERO', 'CLIPPED', 'DISPLAYNONE', 'PASSWORD-VALUE']) assert.ok(!text.includes(hidden), hidden);
    await ui.getByTestId('preview-cancel').click();
  });

  it('does not loop when a stale session cookie meets a sign-in page that sets other cookies', async () => {
    await t.app.evaluate(async ({ session }, origin) => {
      await session.defaultSession.cookies.set({ url: origin, name: 'mock_gemini', value: 'expired' });
    }, t.origin('gemini'));
    await new Promise((r) => setTimeout(r, 600));
    const before = t.mock.log.length;
    await chrome.locator('[data-testid="agent-button"][data-agent-id="gemini"]').click();
    const ui = await t.agentUi('gemini');
    await new Promise((r) => setTimeout(r, 4000));
    const loads = t.mock.log.slice(before).filter((l) => /^GET \/gemini\/(app|login)/.test(l));
    assert.ok(loads.length <= 4, `provider pages loaded ${loads.length} times: ${loads.join(' ')}`);
    assert.equal(await ui.getByTestId('agent-state-text').textContent(), '로그인 중…');
  });

  it('keeps the committed URL when a navigation turns into a download', async () => {
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await titles()).includes('Test Article'), 10000, 'page');
    await go(chrome, `${t.origin('news')}/download`);
    await t.until(async () => (await chrome.getByTestId('downloads-button').isVisible()), 10000, 'download started');
    await t.until(async () => (await chrome.getByTestId('address-input').inputValue()).endsWith('/page'), 5000, 'address back to the page');
  });

  it('keeps private-window downloads out of the saved download list', async () => {
    await t.key('trio://chrome/', 'N', ['control', 'shift']);
    const priv = await t.until(async () => {
      for (const p of t.app.windows().filter((w) => w.url().startsWith('trio://chrome/'))) if (await p.getByTestId('private-badge').isVisible()) return p;
      return null;
    }, 10000, 'private window');
    const input = priv.getByTestId('address-input');
    await input.click();
    await input.fill(`${t.origin('secret')}/download`);
    await input.press('Enter');
    await new Promise((r) => setTimeout(r, 2500));
    await t.app.evaluate(({ app }) => app.emit('before-quit'));
    const saved = fs.existsSync(path.join(t.profile, 'downloads.json')) ? fs.readFileSync(path.join(t.profile, 'downloads.json'), 'utf8') : '[]';
    assert.ok(!saved.includes('secret.test'), saved);
  });

  it('logged no renderer errors', () => {
    assert.deepEqual(t.errors, []);
  });
});
