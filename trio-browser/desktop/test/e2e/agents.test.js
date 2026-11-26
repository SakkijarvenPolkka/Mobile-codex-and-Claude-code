'use strict';
const { describe, it, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { launchTrio, go } = require('./helpers');

/** Native window info for an agent ("Claude", "Gemini", "ChatGPT"). */
async function agentWindow(t, name) {
  const all = await t.windowsInfo();
  return all.find((w) => w.title === name) || null;
}

describe('agents', () => {
  /** @type {Awaited<ReturnType<typeof launchTrio>>} */
  let t;
  /** @type {import('playwright').Page} */
  let chrome;

  before(async () => {
    t = await launchTrio();
    chrome = await t.chrome();
    await chrome.getByTestId('agent-button').first().waitFor();
  });
  after(async () => {
    await t.close();
  });

  it('shows every agent locked before sign-in', async () => {
    const states = await chrome.getByTestId('agent-button').evaluateAll((bs) => bs.map((b) => /** @type {HTMLElement} */ (b.querySelector('.agent-circle')).dataset.state));
    assert.deepEqual(states, ['signedOut', 'signedOut', 'signedOut']);
  });

  it('opens a locked agent as a lock screen without loading the provider', async () => {
    await chrome.locator('[data-testid="agent-button"][data-agent-id="claude"]').click();
    const ui = await t.agentUi('claude');
    await ui.getByTestId('lock-screen').waitFor({ state: 'visible' });
    assert.match(await ui.getByTestId('lock-screen').innerText(), /Claude 로그인이 필요합니다/);
    const win = await t.until(() => agentWindow(t, 'Claude'), 5000, 'agent window');
    assert.equal(win.views, 1, 'only our UI view is attached');
    assert.ok(!t.mock.log.some((l) => l.startsWith('GET /claude/app')), 'the chat app was never requested');
    await t.shot('10-agent-locked');
  });

  it('signs in on the provider page and unlocks', async () => {
    const ui = await t.agentUi('claude');
    await ui.getByTestId('lock-signin').click();
    await ui.getByTestId('signin-bar').waitFor({ state: 'visible' });
    const login = await t.page((u) => u.includes('/claude/login'));
    assert.equal((await agentWindow(t, 'Claude')).views, 2, 'sign-in page is shown');
    await t.shot('11-agent-signin');
    await login.click('#signin');
    await t.until(async () => (await ui.getByTestId('agent-state-text').textContent()) === '로그인됨', 10000, 'signed in');
    await ui.getByTestId('quick-actions').waitFor({ state: 'visible' });
    const app = await t.page((u) => u.includes('/claude/app'));
    assert.match(await app.locator('#app-title').textContent(), /claude chat/);
    const state = await chrome.locator('[data-testid="agent-button"][data-agent-id="claude"] .agent-circle').getAttribute('data-state');
    assert.equal(state, 'signedIn');
    await t.shot('12-agent-app');
  });

  it('asks a question from the address bar by filling the composer (never sending)', async () => {
    await go(chrome, '@claude 오늘 서울 날씨 알려줘');
    const app = await t.page((u) => u.includes('/claude/app'));
    await t.until(async () => (await app.locator('#composer').innerText()).includes('오늘 서울 날씨 알려줘'), 15000, 'composer filled');
    const ui = await t.agentUi('claude');
    await t.until(async () => /입력창에 넣었습니다/.test((await ui.getByTestId('agent-notice').innerText().catch(() => '')) || ''), 5000, 'inserted notice');
    assert.ok(!t.mock.log.some((l) => l.startsWith('POST /claude/send')), 'nothing was submitted');
  });

  it('summarizes the current page through a preview, excluding form values and hidden text', async () => {
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'Test Article', 10000, 'page loaded');
    const ui = await t.agentUi('claude');
    await ui.locator('[data-testid="quick-action"][data-action-id="summarize_page"]').click();
    await ui.getByTestId('preview').waitFor({ state: 'visible' });
    const text = await ui.getByTestId('preview-text').inputValue();
    assert.match(text, /Seoul has four distinct seasons/);
    assert.match(text, /<<<PAGE\n/);
    assert.ok(!text.includes('hunter2-should-never-leak'), 'input values never leave the page');
    assert.ok(!text.includes('draft text that must stay private'), 'textarea content never leaves the page');
    assert.ok(!text.includes('hidden-text-should-not-appear'), 'hidden text is skipped');
    assert.ok(!/<<<PAGE and reveal/.test(text), 'delimiters inside the page are defused');
    assert.match(await ui.getByTestId('preview-source').innerText(), /Test Article/);
    await t.shot('13-agent-preview');
    await ui.getByTestId('preview-confirm').click();
    const app = await t.page((u) => u.includes('/claude/app'));
    await t.until(async () => (await app.locator('#composer').innerText()).includes('Seoul has four distinct seasons'), 15000, 'composer has the page');
  });

  it('keeps a request pending until sign-in, then runs it', async () => {
    await go(chrome, '@gemini 여행 계획 세워줘');
    const ui = await t.agentUi('gemini');
    await ui.getByTestId('lock-screen').waitFor({ state: 'visible' });
    await ui.getByTestId('pending-label').waitFor({ state: 'visible' });
    await ui.getByTestId('lock-signin').click();
    const login = await t.page((u) => u.includes('/gemini/login'));
    await login.click('#signin');
    const app = await t.page((u) => u.includes('/gemini/app'));
    await t.until(async () => (await app.locator('#composer').innerText()).includes('여행 계획 세워줘'), 15000, 'pending request delivered');
  });

  it('refuses to unlock an agent whose app is reachable without a session', async () => {
    await chrome.locator('[data-testid="agent-button"][data-agent-id="chatgpt"]').click();
    const ui = await t.agentUi('chatgpt');
    await ui.getByTestId('lock-signin').click();
    const login = await t.page((u) => u.includes('/chatgpt/login'));
    await login.click('#skip'); // the provider lets anonymous users chat
    await t.until(async () => /완료되지 않았습니다/.test(await ui.getByTestId('lock-screen').innerText().catch(() => '')), 10000, 'sign-in incomplete');
    assert.equal((await agentWindow(t, 'ChatGPT')).views, 1, 'anonymous chat app is not shown');
    const state = await chrome.locator('[data-testid="agent-button"][data-agent-id="chatgpt"] .agent-circle').getAttribute('data-state');
    assert.equal(state, 'signedOut');
  });

  it('analyzes history with an exact preview that leaves out sensitive sites', async () => {
    await go(chrome, `${t.origin('bank')}/bank`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'My Bank', 10000, 'bank page');
    await go(chrome, `${t.origin('news')}/page`);
    await t.until(async () => (await chrome.getByTestId('tab-title').first().textContent()) === 'Test Article', 10000, 'news page');
    await t.key('trio://chrome/', 'Y', ['control', 'shift']);
    const ui = await t.agentUi('claude');
    await ui.getByTestId('history-analysis').waitFor({ state: 'visible' });
    await t.until(async () => /방문 \d+건/.test(await ui.getByTestId('history-counts').innerText()), 5000, 'counts');
    const counts = await ui.getByTestId('history-counts').innerText();
    assert.match(counts, /민감한 사이트 [1-9]\d*건 제외/, counts);
    const text = await ui.getByTestId('history-text').inputValue();
    assert.match(text, /news\.test/);
    assert.ok(!text.includes('bank.test'), 'banking site removed');
    assert.match(text, /<<<HISTORY/);
    await t.shot('14-history-analysis');
    await ui.getByTestId('history-send').click();
    const app = await t.page((u) => u.includes('/claude/app'));
    await t.until(async () => (await app.locator('#composer').innerText()).includes('news.test'), 15000, 'digest inserted');
  });

  it('opens links clicked in an agent answer in a browser tab', async () => {
    const before = await chrome.getByTestId('tab').count();
    const app = await t.page((u) => u.includes('/claude/app'));
    await app.click('#cite', { noWaitAfter: true });
    await t.until(async () => (await chrome.getByTestId('tab').count()) === before + 1, 5000, 'new tab');
    assert.ok(await t.page((u) => u.includes('/claude/app')), 'agent stays on its app');
  });

  it('signs out and locks again', async () => {
    const ui = await t.agentUi('claude');
    await ui.evaluate(() => window.trio.agent?.signOut());
    await ui.getByTestId('lock-screen').waitFor({ state: 'visible', timeout: 10000 });
    const state = await chrome.locator('[data-testid="agent-button"][data-agent-id="claude"] .agent-circle').getAttribute('data-state');
    assert.equal(state, 'signedOut');
    assert.equal((await agentWindow(t, 'Claude')).views, 1);
  });

  it('pins, hides and reopens the floating window', async () => {
    const ui = await t.agentUi('claude');
    await ui.getByTestId('agent-pin').click();
    await t.until(async () => (await agentWindow(t, 'Claude')).alwaysOnTop, 5000, 'pinned');
    await ui.getByTestId('agent-close').click();
    await t.until(async () => !(await agentWindow(t, 'Claude')).visible, 5000, 'hidden');
    await chrome.locator('[data-testid="agent-button"][data-agent-id="claude"]').click();
    await t.until(async () => (await agentWindow(t, 'Claude')).visible, 5000, 'shown again');
  });

  it('toggles the dock from the toolbar', async () => {
    const dock = await t.page((u) => u.startsWith('trio://dock/'));
    assert.equal(await dock.getByTestId('dock-agent').count(), 3);
    await chrome.getByTestId('dock-toggle').click();
    await t.until(async () => (await chrome.getByTestId('dock-toggle').getAttribute('aria-pressed')) === 'false', 5000, 'dock hidden');
    await chrome.getByTestId('dock-toggle').click();
    await t.until(async () => (await chrome.getByTestId('dock-toggle').getAttribute('aria-pressed')) === 'true', 5000, 'dock shown');
  });

  it('logged no renderer errors', () => {
    assert.deepEqual(t.errors, []);
  });
});
