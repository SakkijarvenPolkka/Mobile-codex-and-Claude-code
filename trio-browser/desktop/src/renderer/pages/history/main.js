// History page: day groups, search, deletion and "analyze with AI".
import { h, q, debounce } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t, formatTime, formatDay } from '../../common/boot.js';
import { bootPage, api, navLink } from '../../common/page.js';

const page = q('#page');
const PAGE_SIZE = 100;
/** @type {import('../../../types/contract').HistoryEntry[]} */
let entries = [];
let hasMore = false;
let text = new URLSearchParams(location.search).get('q') || '';
/** @type {Set<number>} */
const checked = new Set();
let seq = 0;
let isPrivate = false;

const search = /** @type {HTMLInputElement} */ (h('input', { class: 'input', type: 'search', 'data-testid': 'history-search', autocomplete: 'off', spellcheck: 'false' }));
const list = h('div', { class: 'history-list', 'data-testid': 'history-list' });
const deleteSelected = /** @type {HTMLButtonElement} */ (h('button', { class: 'btn btn--danger', 'data-testid': 'history-delete-selected', hidden: true }));
const analyze = /** @type {HTMLButtonElement} */ (h('button', { class: 'btn btn--primary', 'data-testid': 'history-analyze' }));
const clearBtn = /** @type {HTMLButtonElement} */ (h('button', { class: 'btn', 'data-testid': 'history-clear' }));
const title = h('h1', { class: 'page__title' });

async function load(reset = true) {
  const mine = ++seq;
  const before = reset || !entries.length ? null : entries[entries.length - 1].time;
  const res = await api.queryHistory({ text, beforeTime: before, limit: PAGE_SIZE });
  if (mine !== seq) return;
  entries = reset ? res.entries : [...entries, ...res.entries];
  hasMore = res.hasMore;
  if (reset) checked.clear();
  renderList();
}

function renderList() {
  const nodes = [];
  let day = '';
  for (const e of entries) {
    const d = formatDay(e.time);
    if (d !== day) {
      day = d;
      nodes.push(h('h2', { class: 'day-heading', text: d }));
    }
    const box = /** @type {HTMLInputElement} */ (h('input', { type: 'checkbox', checked: checked.has(e.visitId), 'aria-label': t('history.select') }));
    box.addEventListener('change', () => {
      if (box.checked) checked.add(e.visitId);
      else checked.delete(e.visitId);
      updateSelection();
    });
    const link = /** @type {HTMLAnchorElement} */ (h('a', { class: 'list-row__title', text: e.title || e.url }));
    navLink(link, e.url);
    const remove = h('button', { class: 'icon-btn icon-btn--sm', title: t('history.deleteOne'), 'aria-label': t('history.deleteOne') }, icon('trash', 16));
    remove.addEventListener('click', async () => {
      await api.deleteHistory({ visitIds: [e.visitId] });
      entries = entries.filter((x) => x.visitId !== e.visitId);
      renderList();
    });
    nodes.push(
      h(
        'div',
        { class: 'list-row', 'data-testid': 'history-row', dataset: { visitId: String(e.visitId) } },
        box,
        h('span', { class: 'list-row__time', text: formatTime(e.time) }),
        h('span', { class: 'list-row__icon' }, e.faviconUrl ? h('img', { src: e.faviconUrl, alt: '' }) : icon('globe', 16)),
        h('span', { class: 'list-row__main' }, link, h('span', { class: 'list-row__sub', text: e.host })),
        h('span', { class: 'list-row__actions' }, remove),
      ),
    );
  }
  if (!entries.length) nodes.push(h('div', { class: 'empty-state' }, icon('history', 32), h('span', { text: text ? t('history.noResults') : t('history.empty') })));
  if (hasMore) {
    const more = h('button', { class: 'btn', 'data-testid': 'history-more', text: t('history.more') });
    more.addEventListener('click', () => load(false));
    nodes.push(h('div', { class: 'load-more' }, more));
  }
  list.replaceChildren(...nodes);
  updateSelection();
}

function updateSelection() {
  deleteSelected.hidden = checked.size === 0;
  deleteSelected.textContent = t('history.deleteSelected', { count: checked.size });
}

deleteSelected.addEventListener('click', async () => {
  await api.deleteHistory({ visitIds: [...checked] });
  load(true);
});
analyze.addEventListener('click', () => api.analyzeHistory());
clearBtn.addEventListener('click', () => openClearDialog());
search.addEventListener('input', debounce(() => {
  text = search.value.trim();
  load(true);
}, 150));
window.addEventListener('scroll', () => {
  if (hasMore && window.innerHeight + window.scrollY > document.body.scrollHeight - 400) {
    hasMore = false;
    load(false);
  }
});

function openClearDialog() {
  const select = /** @type {HTMLSelectElement} */ (h('select', { class: 'select', 'data-testid': 'history-clear-range' }, ...['hour', 'day', 'week', 'month', 'all'].map((r) => h('option', { value: r, text: t(`clear.range.${r}`) }))));
  const confirm = h('button', { class: 'btn btn--danger', 'data-testid': 'history-clear-confirm', text: t('history.clearConfirm') });
  const cancel = h('button', { class: 'btn', text: t('common.cancel') });
  const backdrop = h('div', { class: 'modal-backdrop' }, h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, h('h2', { class: 'modal__title', text: t('history.clearTitle') }), h('div', { class: 'modal__body' }, h('p', { text: t('history.clearBody') }), select), h('div', { class: 'modal__buttons' }, cancel, confirm)));
  const close = () => backdrop.remove();
  cancel.addEventListener('click', close);
  backdrop.addEventListener('click', (e) => {
    if (e.target === backdrop) close();
  });
  confirm.addEventListener('click', async () => {
    await api.deleteHistory({ range: /** @type {any} */ (select.value) });
    close();
    load(true);
  });
  document.body.append(backdrop);
  select.focus();
}

/** @param {{ private: boolean }} ctx */
function render(ctx) {
  isPrivate = ctx.private;
  title.textContent = t('history.title');
  search.placeholder = t('history.searchPlaceholder');
  search.value = text;
  analyze.replaceChildren(icon('sparkles', 16), h('span', { text: t('history.analyze') }));
  analyze.hidden = isPrivate;
  clearBtn.textContent = t('history.clear');
  const note = h('p', { class: 'page__sub', text: t('history.privateNote'), hidden: !isPrivate });
  page.replaceChildren(h('div', { class: 'page__head' }, title, analyze, clearBtn), note, h('div', { class: 'toolbar-row' }, search, deleteSelected), h('section', { class: 'section' }, list));
  return load(true);
}

bootPage('history.title', render).then(() => {
  api.onChanged((what) => {
    if (what === 'history') load(true);
  });
});
