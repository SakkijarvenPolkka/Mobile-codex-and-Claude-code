// Settings page. Every control writes one dotted settings path and re-renders from the result.
import { h, q, debounce } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t } from '../../common/boot.js';
import { agentCircle } from '../../common/agent-circle.js';
import { bootPage, api, navLink } from '../../common/page.js';

const page = q('#page');
/** @type {import('../../../types/contract').Settings | null} */
let settings = null;

/**
 * @param {string} nameKey
 * @param {string | null} descKey
 * @param {Node} control
 * @param {Record<string, string | number>} [params]
 */
function row(nameKey, descKey, control, params = {}) {
  return h('div', { class: 'row' }, h('div', { class: 'row__label' }, h('div', { class: 'row__name', text: t(nameKey, params) }), descKey ? h('div', { class: 'row__desc', text: t(descKey, params) }) : null), h('div', { class: 'row__control' }, control));
}

/** @param {string} path @param {unknown} value */
async function set(path, value) {
  settings = await api.setSetting(path, value);
}

/** @param {string} path @param {boolean} value @param {string} testid */
function toggle(path, value, testid) {
  const box = /** @type {HTMLInputElement} */ (h('input', { type: 'checkbox', class: 'switch', checked: value, 'data-testid': testid }));
  box.addEventListener('change', () => set(path, box.checked));
  return box;
}

/**
 * @param {string} path
 * @param {string} value
 * @param {[string, string][]} options value, label
 * @param {string} testid
 */
function select(path, value, options, testid) {
  const s = /** @type {HTMLSelectElement} */ (h('select', { class: 'select', 'data-testid': testid }, ...options.map(([v, label]) => h('option', { value: v, text: label, selected: v === value }))));
  s.addEventListener('change', () => set(path, s.value));
  return s;
}

/** @param {string} path @param {number} value @param {string} testid */
function number(path, value, testid) {
  const input = /** @type {HTMLInputElement} */ (h('input', { class: 'input', type: 'number', min: '1000', max: '100000', step: '1000', value: String(value), 'data-testid': testid }));
  input.addEventListener('change', async () => {
    await set(path, Number(input.value));
    if (settings) input.value = String(path.endsWith('pageCharLimit') ? settings.agents.pageCharLimit : settings.agents.historyCharLimit);
  });
  return input;
}

async function render() {
  settings = await api.getSettings();
  const s = settings;
  const engines = await api.getSearchEngines();
  const agents = await api.getAgents();

  const hosts = /** @type {HTMLTextAreaElement} */ (h('textarea', { class: 'textarea hosts', 'data-testid': 'setting-excluded-hosts', spellcheck: 'false' }));
  hosts.value = s.privacy.extraExcludedHosts.join('\n');
  hosts.addEventListener('input', debounce(() => set('privacy.extraExcludedHosts', hosts.value.split(/\s+/).filter(Boolean)), 400));

  const clearBtn = h('button', { class: 'btn', 'data-testid': 'clear-data', text: t('settings.clearData') });
  clearBtn.addEventListener('click', openClearDialog);

  const dirLabel = h('span', { class: 'row__desc', text: s.downloads.directory || t('settings.downloadsDefault') });
  const dirBtn = h('button', { class: 'btn', text: t('settings.change') });
  dirBtn.addEventListener('click', async () => {
    settings = await api.chooseDownloadDirectory();
    render();
  });

  const permRows = Object.entries(s.permissions).flatMap(([origin, perms]) =>
    Object.entries(perms).map(([kind, decision]) => {
      const reset = h('button', { class: 'btn btn--ghost', text: t('settings.permissions.reset') });
      reset.addEventListener('click', async () => {
        const next = structuredClone(s.permissions);
        delete (/** @type {any} */ (next[origin]))[kind];
        if (!Object.keys(next[origin] || {}).length) delete next[origin];
        await set('permissions', next);
        render();
      });
      return h('tr', null, h('td', { text: origin.replace(/^https?:\/\//, '') }), h('td', { text: t(`permission.${kind}`) }), h('td', { text: t(`settings.permissions.${decision}`) }), h('td', null, reset));
    }),
  );

  const about = /** @type {HTMLAnchorElement} */ (h('a', { class: 'btn', text: t('settings.aboutLink') }));
  navLink(about, 'trio://about/');

  page.replaceChildren(
    h('div', { class: 'page__head' }, h('h1', { class: 'page__title', text: t('settings.title') })),
    h(
      'section',
      { class: 'section' },
      h('h2', { class: 'section__title', text: t('settings.general') }),
      row('settings.language', null, select('general.language', s.general.language, [['system', t('settings.language.system')], ['ko', '한국어'], ['en', 'English']], 'setting-language')),
      row('settings.theme', null, select('general.theme', s.general.theme, [['system', t('settings.theme.system')], ['light', t('settings.theme.light')], ['dark', t('settings.theme.dark')]], 'setting-theme')),
      row('settings.startup', null, select('general.startup', s.general.startup, [['restore', t('settings.startup.restore')], ['newtab', t('settings.startup.newtab')]], 'setting-startup')),
      row('settings.bookmarksBar', null, toggle('general.showBookmarksBar', s.general.showBookmarksBar, 'setting-bookmarks-bar')),
    ),
    h(
      'section',
      { class: 'section' },
      h('h2', { class: 'section__title', text: t('settings.search') }),
      row('settings.searchEngine', null, select('search.engine', s.search.engine, engines.map((e) => [e.id, e.name]), 'setting-search-engine')),
      row('settings.suggestions', 'settings.suggestionsDesc', toggle('search.suggestions', s.search.suggestions, 'setting-suggestions')),
    ),
    h(
      'section',
      { class: 'section' },
      h('h2', { class: 'section__title', text: t('settings.agents') }),
      ...agents.map((a) =>
        h(
          'div',
          { class: 'row' },
          h('div', { class: 'row__label agent-row-name' }, agentCircle(a, { size: 32 }), h('div', null, h('div', { class: 'row__name', text: a.name }), h('div', { class: 'row__desc', text: `${a.vendor} · ${t(`agent.state.${a.state}`)}` }))),
          h('div', { class: 'row__control' }, toggle(`agents.enabled.${a.id}`, s.agents.enabled[a.id], `setting-agent-${a.id}`)),
        ),
      ),
      row('settings.dock', null, toggle('agents.dockVisible', s.agents.dockVisible, 'setting-dock')),
      row('settings.previewPage', 'settings.previewNote', toggle('agents.preview.page', s.agents.preview.page, 'setting-preview-page')),
      row('settings.previewSelection', null, toggle('agents.preview.selection', s.agents.preview.selection, 'setting-preview-selection')),
      row('settings.previewSearch', null, toggle('agents.preview.search', s.agents.preview.search, 'setting-preview-search')),
      row('settings.pageLimit', 'settings.limitDesc', number('agents.pageCharLimit', s.agents.pageCharLimit, 'setting-page-limit')),
      row('settings.historyLimit', 'settings.limitDesc', number('agents.historyCharLimit', s.agents.historyCharLimit, 'setting-history-limit')),
      row('settings.promptLanguage', null, select('agents.promptLanguage', s.agents.promptLanguage, [['ui', t('settings.promptLanguage.ui')], ['ko', '한국어'], ['en', 'English']], 'setting-prompt-language')),
    ),
    h(
      'section',
      { class: 'section' },
      h('h2', { class: 'section__title', text: t('settings.privacy') }),
      row('settings.saveHistory', null, toggle('privacy.saveHistory', s.privacy.saveHistory, 'setting-save-history')),
      row('settings.excludeSensitive', 'settings.excludeSensitiveDesc', toggle('privacy.excludeSensitive', s.privacy.excludeSensitive, 'setting-exclude-sensitive')),
      row('settings.excludedHosts', 'settings.excludedHostsDesc', hosts),
      row('settings.clearDataRow', null, clearBtn),
    ),
    h(
      'section',
      { class: 'section' },
      h('h2', { class: 'section__title', text: t('settings.downloads') }),
      h('div', { class: 'row' }, h('div', { class: 'row__label' }, h('div', { class: 'row__name', text: t('settings.downloadsDir') }), dirLabel), h('div', { class: 'row__control' }, dirBtn)),
      row('settings.askWhere', null, toggle('downloads.askWhere', s.downloads.askWhere, 'setting-ask-where')),
    ),
    h(
      'section',
      { class: 'section', 'data-testid': 'permissions' },
      h('h2', { class: 'section__title', text: t('settings.permissions') }),
      permRows.length ? h('table', { class: 'table' }, h('thead', null, h('tr', null, h('th', { text: t('settings.permissions.site') }), h('th', { text: t('settings.permissions.kind') }), h('th', { text: t('settings.permissions.decision') }), h('th'))), h('tbody', null, ...permRows)) : h('p', { class: 'perm-empty', text: t('settings.permissions.empty') }),
    ),
    h('section', { class: 'section' }, h('h2', { class: 'section__title', text: t('settings.about') }), h('div', { class: 'row' }, h('div', { class: 'row__label' }, h('div', { class: 'row__desc', text: t('about.notice') })), h('div', { class: 'row__control' }, about))),
  );
}

function openClearDialog() {
  const range = /** @type {HTMLSelectElement} */ (h('select', { class: 'select' }, ...['hour', 'day', 'week', 'month', 'all'].map((r) => h('option', { value: r, text: t(`clear.range.${r}`) }))));
  /** @param {string} key @param {boolean} checked */
  const check = (key, checked) => {
    const box = /** @type {HTMLInputElement} */ (h('input', { type: 'checkbox', checked }));
    return { box, label: h('label', { class: 'check' }, box, h('span', { text: t(key) })) };
  };
  const hist = check('settings.clear.history', true);
  const cookies = check('settings.clear.cookies', false);
  const cache = check('settings.clear.cache', true);
  const confirm = h('button', { class: 'btn btn--danger', 'data-testid': 'clear-data-confirm', text: t('settings.clear.confirm') });
  const cancel = h('button', { class: 'btn', text: t('common.cancel') });
  const backdrop = h('div', { class: 'modal-backdrop' }, h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, h('h2', { class: 'modal__title', text: t('settings.clearData') }), h('div', { class: 'modal__body' }, range, hist.label, cookies.label, h('p', { class: 'row__desc', text: t('settings.clear.cookiesNote') }), cache.label), h('div', { class: 'modal__buttons' }, cancel, confirm)));
  const close = () => backdrop.remove();
  cancel.addEventListener('click', close);
  confirm.addEventListener('click', async () => {
    await api.clearBrowsingData({ history: hist.box.checked, cookies: cookies.box.checked, cache: cache.box.checked, range: /** @type {any} */ (range.value) });
    close();
  });
  document.body.append(backdrop);
  range.focus();
}

bootPage('settings.title', render).then(() => {
  api.onChanged((what) => {
    if ((what === 'settings' || what === 'agents') && !document.querySelector('.modal-backdrop') && !(document.activeElement instanceof HTMLTextAreaElement) && !(document.activeElement instanceof HTMLInputElement && document.activeElement.type === 'number')) render();
  });
});
void icon;
