'use strict';
const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { launchTrio, go } = require('./helpers');

describe('browser basics', () => {
  /** @type {Awaited<ReturnType<typeof launchTrio>>} */
  let t;
  /** @type {import('playwright').Page} */
  let chrome;

  before(async () => {
    t = await launchTrio();
    chrome = await t.chrome();
    await chrome.getByTestId('tab').first().waitFor();
  });
  after(async () => {
    await t.close();
  });

  it('starts with one new-tab page in Korean', async () => {
    assert.equal(await chrome.getByTestId('tab').count(), 1);
    const newtab = await t.page((u) => u.startsWith('trio://newtab/'));
    await newtab.getByTestId('newtab-search').waitFor();
    assert.equal(await chrome.getByTestId('address-input').inputValue(), '');
    assert.match(await chrome.getByTestId('address-input').getAttribute('placeholder'), /검색어 또는 주소/);
    assert.equal(await newtab.getByTestId('agent-card').count(), 3);
    await t.shot('01-newtab');
  });

  it('navigates from the address bar and records history with the page title', async () => {
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'Test Article', 10000, 'title');
    assert.equal(await chrome.getByTestId('address-input').inputValue(), `${t.origin('news')}/page`);
    assert.equal(await chrome.getByTestId('security-icon').getAttribute('data-security'), 'insecure');
  });

  it('classifies text as a search with the default engine', async () => {
    await go(chrome, '서울 날씨');
    await t.until(async () => (await chrome.getByTestId('address-input').inputValue()).includes('/search?q='), 10000, 'search url');
    assert.match(await chrome.getByTestId('address-input').inputValue(), /search\.test:\d+\/search\?q=%EC%84%9C%EC%9A%B8%20%EB%82%A0%EC%94%A8/);
    // The results page is recognised, so the "Ask AI" chip appears.
    await chrome.getByTestId('ask-search').waitFor({ state: 'visible' });
  });

  it('goes back and forward', async () => {
    await chrome.getByTestId('nav-back').click();
    await t.until(async () => (await chrome.getByTestId('address-input').inputValue()).endsWith('/page'), 10000, 'back');
    await chrome.getByTestId('nav-forward').click();
    await t.until(async () => (await chrome.getByTestId('address-input').inputValue()).includes('/search?q='), 10000, 'forward');
  });

  it('suggests history, a search and the agents while typing', async () => {
    const input = chrome.getByTestId('address-input');
    await input.click();
    await input.fill('Test Art');
    const dropdown = chrome.getByTestId('omnibox-dropdown');
    await dropdown.waitFor({ state: 'visible' });
    await t.until(async () => (await chrome.getByTestId('suggestion').count()) >= 5, 5000, 'suggestions');
    const kinds = await chrome.getByTestId('suggestion').evaluateAll((rows) => rows.map((r) => /** @type {HTMLElement} */ (r).dataset.kind));
    assert.equal(kinds[0], 'search');
    assert.ok(kinds.includes('history'), kinds.join());
    assert.equal(kinds.filter((k) => k === 'agent').length, 3);
    await t.shot('02-omnibox');
    await input.press('Escape');
    await dropdown.waitFor({ state: 'hidden' });
    await input.press('Escape');
  });

  it('opens, switches and closes tabs', async () => {
    await chrome.getByTestId('new-tab').click();
    await t.until(async () => (await chrome.getByTestId('tab').count()) === 2, 5000, 'two tabs');
    const tabs = chrome.getByTestId('tab');
    assert.equal(await tabs.nth(1).getAttribute('aria-selected'), 'true');
    await tabs.nth(0).click();
    await t.until(async () => (await tabs.nth(0).getAttribute('aria-selected')) === 'true', 5000, 'switch');
    await tabs.nth(1).hover();
    await tabs.nth(1).getByTestId('tab-close').click();
    await t.until(async () => (await chrome.getByTestId('tab').count()) === 1, 5000, 'closed');
  });

  it('bookmarks the page with the star and lists it on the bookmarks page', async () => {
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'Test Article', 10000, 'title');
    await chrome.getByTestId('bookmark-star').click();
    await t.until(async () => (await chrome.getByTestId('bookmark-star').getAttribute('aria-pressed')) === 'true', 5000, 'starred');
    await go(chrome, 'trio://bookmarks');
    const page = await t.page((u) => u.startsWith('trio://bookmarks/'));
    await page.getByTestId('bookmark-row').first().waitFor();
    assert.match(await page.getByTestId('bookmark-row').first().innerText(), /Test Article/);
  });

  it('finds text in the page', async () => {
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'Test Article', 10000, 'title');
    await t.key(`${t.origin('news')}/page`, 'F', ['control']);
    await chrome.getByTestId('find-bar').waitFor({ state: 'visible' });
    await chrome.getByTestId('find-input').fill('Filler');
    await t.until(async () => /\d+\/20/.test((await chrome.getByTestId('find-count').textContent()) || ''), 5000, 'find count');
    await chrome.getByTestId('find-input').press('Escape');
    await chrome.getByTestId('find-bar').waitFor({ state: 'hidden' });
  });

  it('shows the history page with the visits and searches it', async () => {
    await go(chrome, 'trio://history');
    const page = await t.page((u) => u.startsWith('trio://history/'));
    await page.getByTestId('history-row').first().waitFor();
    const rows = await page.getByTestId('history-row').allInnerTexts();
    assert.ok(rows.some((r) => r.includes('Test Article')), rows.join('\n'));
    await page.getByTestId('history-search').fill('날씨');
    await t.until(async () => (await page.getByTestId('history-row').allInnerTexts()).every((r) => r.includes('날씨')), 5000, 'filtered');
    await t.shot('03-history');
  });

  it('opens an error page for unreachable sites and never navigates javascript: URLs', async () => {
    await go(chrome, 'http://nonexistent.invalid/');
    await t.page((u) => u.startsWith('trio://error/'));
    assert.equal(await chrome.getByTestId('address-input').inputValue(), 'http://nonexistent.invalid');
    await go(chrome, 'javascript:alert(1)');
    await t.until(async () => (await chrome.getByTestId('address-input').inputValue()).includes('search?q=javascript'), 10000, 'searched instead');
  });

  it('opens a private window without agents', async () => {
    await t.key('trio://chrome/', 'N', ['control', 'shift']);
    const priv = await t.until(async () => {
      const pages = t.app.windows().filter((w) => w.url().startsWith('trio://chrome/'));
      for (const p of pages) if (await p.getByTestId('private-badge').isVisible()) return p;
      return null;
    }, 10000, 'private window');
    assert.equal(await priv.getByTestId('agent-button').count(), 0);
    await priv.getByTestId('agents-off').waitFor({ state: 'visible' });
    await t.shot('04-private');
    const closed = await t.app.evaluate(({ BaseWindow, session }) => {
      const w = BaseWindow.getAllWindows().find((x) => x.contentView.children.some((v) => /** @type {any} */ (v).webContents && /** @type {any} */ (v).webContents.session !== session.defaultSession));
      if (w) w.close();
      return Boolean(w);
    });
    assert.ok(closed, 'private window found and closed');
  });

  it('downloads files into the profile download directory', async () => {
    const dir = path.join(t.profile, 'dl');
    fs.mkdirSync(dir, { recursive: true });
    // Point downloads at the temp directory through the settings page.
    await go(chrome, 'trio://settings');
    const page = await t.page((u) => u.startsWith('trio://settings/'));
    await page.getByTestId('setting-language').waitFor();
    await page.evaluate((d) => window.trio.pages?.setSetting('downloads.directory', d), dir);
    await go(chrome, `${t.origin('news')}/download`);
    await t.until(() => fs.existsSync(path.join(dir, 'report.txt')), 10000, 'downloaded file');
    assert.equal(fs.readFileSync(path.join(dir, 'report.txt'), 'utf8'), 'hello download');
    await chrome.getByTestId('downloads-button').waitFor({ state: 'visible' });
  });

  it('renders no missing strings and logs no errors', async () => {
    for (const p of t.app.windows().filter((w) => w.url().startsWith('trio://'))) {
      const text = await p.evaluate(() => document.body.innerText + [...document.querySelectorAll('[title],[placeholder],[aria-label]')].map((e) => e.getAttribute('title') + e.getAttribute('placeholder') + e.getAttribute('aria-label')).join(''));
      assert.ok(!text.includes('⟦'), `${p.url()} has a missing string: ${text.slice(text.indexOf('⟦'), text.indexOf('⟦') + 60)}`);
    }
    assert.deepEqual(t.errors, []);
  });
});
