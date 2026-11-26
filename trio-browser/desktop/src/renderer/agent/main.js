// Agent window UI: header, quick actions, lock screen, sign-in bar, preview and history analysis.
// The provider's own page is drawn by the main process below #top while the login gate allows it.
import { h, q, debounce, setStyle } from '../common/dom.js';
import { icon } from '../common/icons.js';
import { applyBoot, applyStrings, t, formatNumber } from '../common/boot.js';
import { agentCircle, updateAgentCircle } from '../common/agent-circle.js';

/**
 * @typedef {import('../../types/contract').AgentWindowState} AgentWindowState
 * @typedef {import('../../types/contract').PreviewPayload} PreviewPayload
 * @typedef {import('../../types/contract').QuickActionDef} QuickActionDef
 * @typedef {import('../../types/contract').HistoryOptions} HistoryOptions
 */

const api = /** @type {import('../../types/contract').AgentApi} */ (window.trio.agent);

const el = {
  frame: q('#frame'),
  top: q('#top'),
  header: q('#header'),
  circle: q('#header-circle'),
  name: q('#agent-name'),
  stateText: q('#agent-state-text'),
  newChat: /** @type {HTMLButtonElement} */ (q('#agent-new-chat')),
  pin: /** @type {HTMLButtonElement} */ (q('#agent-pin')),
  openTab: /** @type {HTMLButtonElement} */ (q('#agent-open-tab')),
  menu: /** @type {HTMLButtonElement} */ (q('#agent-menu')),
  minimize: /** @type {HTMLButtonElement} */ (q('#agent-minimize')),
  close: /** @type {HTMLButtonElement} */ (q('#agent-close')),
  actions: q('#quick-actions'),
  questionForm: /** @type {HTMLFormElement} */ (q('#question-form')),
  questionInput: /** @type {HTMLInputElement} */ (q('#question-input')),
  signinBar: q('#signin-bar'),
  signinText: q('#signin-text'),
  privateNote: q('#private-note'),
  notice: q('#agent-notice'),
  noticeText: q('#agent-notice-text'),
  lock: q('#lock-screen'),
  lockCircle: q('#lock-circle'),
  lockTitle: q('#lock-title'),
  lockBody: q('#lock-body'),
  pending: q('#pending-label'),
  lockSignin: /** @type {HTMLButtonElement} */ (q('#lock-signin')),
  lockFoot: q('#lock-foot'),
  preview: q('#preview'),
  previewTitle: q('#preview-title'),
  previewSource: q('#preview-source'),
  previewText: /** @type {HTMLTextAreaElement} */ (q('#preview-text')),
  previewCount: q('#preview-count'),
  previewTarget: q('#preview-target'),
  previewSkip: /** @type {HTMLInputElement} */ (q('#preview-skip')),
  previewConfirm: /** @type {HTMLButtonElement} */ (q('#preview-confirm')),
  previewFoot: q('#preview-foot'),
  history: q('#history-analysis'),
  historyRange: q('#history-range'),
  historyKind: /** @type {HTMLSelectElement} */ (q('#history-kind')),
  historyQuestionField: q('#history-question-field'),
  historyQuestion: /** @type {HTMLInputElement} */ (q('#history-question')),
  historyQueries: /** @type {HTMLInputElement} */ (q('#history-include-queries')),
  historyTitles: /** @type {HTMLInputElement} */ (q('#history-include-titles')),
  historyUrls: /** @type {HTMLSelectElement} */ (q('#history-include-urls')),
  historyCounts: q('#history-counts'),
  historyText: /** @type {HTMLTextAreaElement} */ (q('#history-text')),
  historyChars: q('#history-chars'),
  historyToAll: /** @type {HTMLInputElement} */ (q('#history-to-all')),
  historySend: /** @type {HTMLButtonElement} */ (q('#history-send')),
  placeholder: q('#provider-placeholder'),
  grip: q('#resize-grip'),
};

/** @type {AgentWindowState | null} */
let state = null;
/** @type {QuickActionDef[]} */
let actions = [];
/** @type {PreviewPayload | null} */
let preview = null;
/** @type {PreviewPayload | null} */
let historyPreview = null;
/** @type {QuickActionDef | null} */
let asking = null;
let previewTarget = /** @type {'new' | 'current'} */ ('new');
/** @type {import('../../types/contract').HistoryRange} */
let historyRange = '7d';
let historySeq = 0;
let lastMode = '';

const RANGES = /** @type {const} */ (['today', '7d', '30d', '90d', 'all']);
const KINDS = /** @type {const} */ (['habits', 'topics', 'find', 'time', 'custom']);

// ------------------------------------------------------------------------------------------------
// Setup

function setupStatic() {
  el.newChat.replaceChildren(icon('new-chat', 16));
  el.openTab.replaceChildren(icon('external', 16));
  el.menu.replaceChildren(icon('more', 16));
  el.minimize.replaceChildren(icon('minimize', 16));
  el.close.replaceChildren(icon('close', 16));
  q('#signin-icon').replaceChildren(icon('lock', 16));
  q('#agent-notice-dismiss').replaceChildren(icon('close', 14));
  applyStrings();
  /** @type {[HTMLElement, string][]} */
  const titles = [
    [el.newChat, 'agent.newChat'],
    [el.openTab, 'agent.openInTab'],
    [el.menu, 'agent.menu'],
    [el.minimize, 'agent.minimize'],
    [el.close, 'common.close'],
    [q('#agent-notice-dismiss'), 'common.close'],
    [el.grip, 'agent.resize'],
  ];
  for (const [node, key] of titles) {
    node.title = t(key);
    node.setAttribute('aria-label', t(key));
  }
  el.historyRange.replaceChildren(
    ...RANGES.map((r) => {
      const b = h('button', { type: 'button', 'aria-pressed': String(r === historyRange), dataset: { range: r }, text: t(`history.range.${r}`) });
      b.addEventListener('click', () => {
        historyRange = r;
        for (const x of /** @type {HTMLElement[]} */ ([...el.historyRange.children])) x.setAttribute('aria-pressed', String(x.dataset.range === r));
        refreshHistory();
      });
      return b;
    }),
  );
  const kind = el.historyKind.value || 'habits';
  el.historyKind.replaceChildren(...KINDS.map((k) => h('option', { value: k, text: t(`agent.history.kinds.${k}`) })));
  el.historyKind.value = kind;
}

// ------------------------------------------------------------------------------------------------
// Rendering

/** @param {AgentWindowState} s */
function render(s) {
  state = s;
  const a = s.agent;
  setStyle(el.frame, '--agent-accent', a.accent);
  if (!el.circle.firstElementChild) el.circle.append(agentCircle(a, { size: 24 }));
  else updateAgentCircle(/** @type {HTMLElement} */ (el.circle.firstElementChild), a, { size: 24 });
  el.name.textContent = a.name;
  document.title = a.name;
  el.stateText.dataset.state = a.state;
  el.stateText.textContent = s.loading && s.mode === 'app' ? t('common.loading') : t(`agent.state.${a.state}`);

  const mode = s.mode;
  el.newChat.hidden = mode !== 'app';
  el.openTab.hidden = mode !== 'app';
  el.pin.setAttribute('aria-pressed', String(s.pinned));
  el.pin.replaceChildren(icon(s.pinned ? 'pin-filled' : 'pin', 16));
  el.pin.title = t(s.pinned ? 'agent.unpin' : 'agent.pin');
  el.pin.setAttribute('aria-label', el.pin.title);

  el.actions.hidden = mode !== 'app' || asking !== null;
  el.questionForm.hidden = mode !== 'app' || asking === null;
  el.signinBar.hidden = mode !== 'signin';
  el.lock.hidden = mode !== 'lock';
  el.preview.hidden = mode !== 'preview';
  el.history.hidden = mode !== 'history';
  el.placeholder.hidden = !(mode === 'app' || mode === 'signin');
  el.privateNote.hidden = !(mode === 'app' && s.context.private);
  el.privateNote.textContent = t('agent.privateNote');

  if (mode === 'app') renderActions(s);
  if (mode === 'signin') el.signinText.textContent = t('agent.signinBar', { host: s.host || '…' });
  if (mode === 'lock') renderLock(s);
  if (mode === 'history' && lastMode !== 'history') {
    el.historyToAll.checked = false;
    refreshHistory();
  }
  renderNotice(s);
  if (mode !== lastMode) {
    lastMode = mode;
    if (mode === 'preview') el.previewText.focus();
  }
  reportHeader();
}

/** @param {AgentWindowState} s */
function renderActions(s) {
  const key = JSON.stringify([actions.map((a) => a.id), s.context]);
  if (el.actions.dataset.render === key) return;
  el.actions.dataset.render = key;
  const pageBlocked = s.context.private || !s.context.pageAvailable;
  el.actions.replaceChildren(
    ...actions
      .filter((a) => a.id !== 'search_page' || s.context.searchQuery)
      .map((a) => {
        const isPage = a.id.endsWith('_page') && a.id !== 'search_page';
        const isSelection = a.id.endsWith('_selection');
        let label = t(a.labelKey);
        if (a.id === 'search_page' && s.context.searchQuery) {
          const qText = s.context.searchQuery.length > 24 ? s.context.searchQuery.slice(0, 23) + '…' : s.context.searchQuery;
          label = t('agent.searchChip', { query: qText });
        }
        const iconName = /** @type {Record<string, string>} */ ({ summarize_page: 'list', ask_page: 'chat', explain_selection: 'info', translate_selection: 'translate', search_page: 'search' })[a.id] || 'history';
        const chip = h('button', { class: 'chip', type: 'button', 'data-testid': 'quick-action', dataset: { actionId: a.id }, disabled: (isPage || isSelection) && pageBlocked }, icon(iconName, 14), h('span', { text: label }));
        chip.title = pageBlocked && (isPage || isSelection) ? t(s.context.private ? 'agent.privateNote' : 'agent.pageUnavailable') : label;
        chip.addEventListener('click', () => {
          if (a.id.startsWith('history_')) api.openHistoryAnalysis();
          else if (a.needsQuestion) {
            asking = a;
            el.questionInput.value = '';
            el.questionInput.placeholder = t('agent.question.placeholder', { name: s.agent.name });
            if (state) render(state);
            el.questionInput.focus();
          } else api.runAction(a.id);
        });
        return chip;
      }),
  );
}

/** @param {AgentWindowState} s */
function renderLock(s) {
  const a = s.agent;
  if (!el.lockCircle.firstElementChild) el.lockCircle.append(agentCircle(a, { size: 72 }));
  else updateAgentCircle(/** @type {HTMLElement} */ (el.lockCircle.firstElementChild), a, { size: 72 });
  el.lockTitle.textContent = t('agent.lock.title', { name: a.name });
  el.lockBody.textContent = t(`agent.lock.${s.lockReason}`, { name: a.name, vendor: a.vendor });
  el.pending.hidden = !s.pendingLabel;
  el.pending.textContent = s.pendingLabel ? t('agent.lock.pending', { label: s.pendingLabel }) : '';
  el.lockSignin.textContent = t('agent.lock.signIn', { name: a.name });
  el.lockFoot.textContent = t('agent.lock.foot', { vendor: a.vendor });
}

/** @param {AgentWindowState} s */
function renderNotice(s) {
  const n = s.notice;
  el.notice.hidden = !n || s.mode === 'lock';
  if (!n) return;
  el.notice.dataset.kind = n.kind;
  el.notice.dataset.id = String(n.id);
  q('#agent-notice-icon').replaceChildren(icon(n.kind === 'success' ? 'check' : n.kind === 'info' ? 'info' : 'warning', 16));
  el.noticeText.textContent = n.text;
}

function reportHeader() {
  api.setHeaderHeight(Math.round(el.top.getBoundingClientRect().bottom));
}

// ------------------------------------------------------------------------------------------------
// Preview

/** @param {PreviewPayload | null} p */
function showPreview(p) {
  preview = p;
  if (!p) return;
  const name = state ? state.agent.name : '';
  el.previewTitle.textContent = t('agent.preview.title', { name });
  el.previewSource.textContent = p.source;
  el.previewText.value = p.text;
  el.previewSkip.checked = false;
  previewTarget = 'new';
  for (const b of /** @type {HTMLElement[]} */ ([...el.previewTarget.children])) b.setAttribute('aria-pressed', String(b.dataset.target === 'new'));
  el.previewFoot.textContent = t('agent.preview.foot', { name });
  updatePreviewCount();
}

function updatePreviewCount() {
  const n = el.previewText.value.length;
  el.previewCount.textContent = t('agent.preview.count', { count: formatNumber(n) }) + (preview && preview.truncated ? ` · ${t('agent.preview.truncated')}` : '');
  el.previewConfirm.disabled = !el.previewText.value.trim();
}

el.previewText.addEventListener('input', updatePreviewCount);
for (const b of /** @type {HTMLButtonElement[]} */ ([...el.previewTarget.children])) {
  b.addEventListener('click', () => {
    previewTarget = b.dataset.target === 'current' ? 'current' : 'new';
    for (const x of /** @type {HTMLElement[]} */ ([...el.previewTarget.children])) x.setAttribute('aria-pressed', String(x === b));
  });
}
el.previewConfirm.addEventListener('click', () => {
  if (preview) api.confirmPreview(preview.id, el.previewText.value, { target: previewTarget, skipNextTime: el.previewSkip.checked });
});
q('#preview-cancel').addEventListener('click', () => {
  if (preview) api.cancelPreview(preview.id);
});

// ------------------------------------------------------------------------------------------------
// History analysis

/** @returns {HistoryOptions} */
function historyOptions() {
  return {
    range: historyRange,
    kind: /** @type {HistoryOptions['kind']} */ (el.historyKind.value || 'habits'),
    question: el.historyQuestion.value,
    include: { queries: el.historyQueries.checked, titles: el.historyTitles.checked, urls: el.historyUrls.value === 'paths' ? 'paths' : 'sites' },
  };
}

function refreshHistory() {
  const opts = historyOptions();
  el.historyQuestionField.hidden = !(opts.kind === 'find' || opts.kind === 'custom');
  const mine = ++historySeq;
  api.previewHistory(opts).then((p) => {
    if (mine !== historySeq) return;
    historyPreview = p;
    el.historyText.value = p.text;
    const c = p.counts || { visits: 0, sites: 0, searches: 0, excludedSensitive: 0 };
    el.historyCounts.textContent = t('agent.history.counts', { visits: formatNumber(c.visits), sites: formatNumber(c.sites), searches: formatNumber(c.searches), excluded: formatNumber(c.excludedSensitive) });
    updateHistoryChars();
  });
}
const refreshHistorySoon = debounce(refreshHistory, 150);

function updateHistoryChars() {
  const n = el.historyText.value.length;
  el.historyChars.textContent = t('agent.preview.count', { count: formatNumber(n) }) + (historyPreview && historyPreview.truncated ? ` · ${t('agent.preview.truncated')}` : '');
  const kind = el.historyKind.value;
  const needsQuestion = kind === 'find' || kind === 'custom';
  el.historySend.disabled = !el.historyText.value.trim() || (needsQuestion && !el.historyQuestion.value.trim());
}

el.historyKind.addEventListener('change', refreshHistory);
el.historyQuestion.addEventListener('input', () => {
  updateHistoryChars();
  refreshHistorySoon();
});
for (const c of [el.historyQueries, el.historyTitles, el.historyUrls]) c.addEventListener('change', refreshHistory);
el.historyText.addEventListener('input', updateHistoryChars);
el.historySend.addEventListener('click', () => {
  if (historyPreview) api.sendHistory(historyPreview.id, el.historyText.value, { toAll: el.historyToAll.checked });
});
q('#history-close').addEventListener('click', () => api.closeHistoryAnalysis());

// ------------------------------------------------------------------------------------------------
// Controls

el.questionForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const question = el.questionInput.value.trim();
  if (!asking || !question) return;
  const id = asking.id;
  asking = null;
  api.runAction(id, { question });
  if (state) render(state);
});
q('#question-cancel').addEventListener('click', () => {
  asking = null;
  if (state) render(state);
});
el.questionInput.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    asking = null;
    if (state) render(state);
  }
});
el.newChat.addEventListener('click', () => api.newChat());
el.pin.addEventListener('click', () => api.setPinned(!(state && state.pinned)));
el.openTab.addEventListener('click', () => api.openInTab());
el.menu.addEventListener('click', () => {
  const r = el.menu.getBoundingClientRect();
  api.showMenu({ x: r.left, y: r.bottom + 2 });
});
el.minimize.addEventListener('click', () => api.minimize());
el.close.addEventListener('click', () => api.close());
el.lockSignin.addEventListener('click', () => api.signIn());
q('#lock-signin-tab').addEventListener('click', () => api.signInInTab());
q('#signin-in-tab').addEventListener('click', () => api.signInInTab());
q('#signin-cancel').addEventListener('click', () => api.cancelSignIn());
q('#agent-notice-dismiss').addEventListener('click', () => api.dismissNotice(Number(el.notice.dataset.id)));
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && state && state.mode === 'preview' && preview) api.cancelPreview(preview.id);
  else if (e.key === 'Escape' && state && state.mode === 'history') api.closeHistoryAnalysis();
});

/** Moving and resizing the frameless window: the main process follows the cursor. */
/** @param {HTMLElement} handle @param {() => void} start */
function dragHandle(handle, start) {
  handle.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || /** @type {HTMLElement} */ (e.target).closest('button, input, select, textarea')) return;
    handle.setPointerCapture(e.pointerId);
    start();
    const end = () => {
      api.endDrag();
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
      handle.removeEventListener('lostpointercapture', end);
    };
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
    handle.addEventListener('lostpointercapture', end);
  });
}
dragHandle(el.header, () => api.startMove());
dragHandle(el.grip, () => api.startResize());

new ResizeObserver(reportHeader).observe(el.top);

// ------------------------------------------------------------------------------------------------
// Boot

async function main() {
  const boot = await api.init();
  applyBoot(boot);
  actions = boot.actions;
  setupStatic();
  render(boot.state);
  api.onState(render);
  api.onPreview((p) => {
    showPreview(p);
    if (state) render(state);
  });
  api.onBoot((b) => {
    applyBoot(b);
    setupStatic();
    delete el.actions.dataset.render;
    if (state) render(state);
    if (preview) showPreview(preview);
  });
}

main();
