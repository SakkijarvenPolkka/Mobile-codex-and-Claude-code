// Browser window UI: tab strip, toolbar with the address bar, bookmarks bar and info bars.
import { h, q, syncList, debounce, setStyle } from '../common/dom.js';
import { icon } from '../common/icons.js';
import { applyBoot, applyStrings, t } from '../common/boot.js';
import { agentCircle, updateAgentCircle } from '../common/agent-circle.js';

/**
 * @typedef {import('../../types/contract').ChromeState} ChromeState
 * @typedef {import('../../types/contract').TabState} TabState
 * @typedef {import('../../types/contract').Suggestion} Suggestion
 */

const api = /** @type {import('../../types/contract').ChromeApi} */ (window.trio.chrome);

const el = {
  bars: q('#bars'),
  tabs: q('#tabs'),
  newTab: /** @type {HTMLButtonElement} */ (q('#new-tab')),
  spacer: q('#strip-spacer'),
  privateBadge: q('[data-testid="private-badge"]'),
  back: /** @type {HTMLButtonElement} */ (q('#nav-back')),
  forward: /** @type {HTMLButtonElement} */ (q('#nav-forward')),
  reload: /** @type {HTMLButtonElement} */ (q('#nav-reload')),
  omnibox: q('#omnibox'),
  security: q('#security-icon'),
  input: /** @type {HTMLInputElement} */ (q('#address-input')),
  zoom: q('#zoom-label'),
  askSearch: /** @type {HTMLButtonElement} */ (q('#ask-search')),
  star: /** @type {HTMLButtonElement} */ (q('#bookmark-star')),
  agents: q('#agents'),
  agentsOff: q('#agents-off'),
  dock: /** @type {HTMLButtonElement} */ (q('#dock-toggle')),
  downloads: /** @type {HTMLButtonElement} */ (q('#downloads-button')),
  menu: /** @type {HTMLButtonElement} */ (q('#app-menu')),
  bookmarks: q('#bookmarks-bar'),
  findBar: q('#find-bar'),
  findInput: /** @type {HTMLInputElement} */ (q('#find-input')),
  findCount: q('#find-count'),
  permissionBar: q('#permission-bar'),
  permissionText: q('#permission-text'),
  permissionRemember: /** @type {HTMLInputElement} */ (q('#permission-remember')),
  noticeBar: q('#notice-bar'),
  noticeText: q('#notice-text'),
  noticeAction: /** @type {HTMLButtonElement} */ (q('#notice-action')),
  dropdown: q('#omnibox-dropdown'),
};

/** @type {ChromeState | null} */
let state = null;
let editing = false; // the user has typed into the address bar since it was focused
/** @type {Suggestion[]} */
let suggestions = [];
let selected = 0;
let typedText = '';
let suggestSeq = 0;

// ------------------------------------------------------------------------------------------------
// Static setup

function setupIcons() {
  el.newTab.append(icon('plus'));
  el.back.append(icon('back'));
  el.forward.append(icon('forward'));
  el.menu.append(icon('more'));
  el.dock.append(icon('dock'));
  el.downloads.append(icon('download'));
  el.agentsOff.append(icon('agents-off'));
  q('#find-icon').append(icon('search', 16));
  q('#find-prev').append(icon('chevron-up', 16));
  q('#find-next').append(icon('chevron-down', 16));
  q('#find-close').append(icon('close', 16));
  q('#notice-dismiss').append(icon('close', 16));
  q('#permission-icon').append(icon('shield', 18));
  el.askSearch.append(icon('sparkles', 14), h('span', { text: t('chrome.askSearch') }));
}

function setupLabels() {
  applyStrings();
  /** @type {[HTMLElement, string][]} */
  const titles = [
    [el.newTab, 'chrome.newTab'],
    [el.back, 'chrome.back'],
    [el.forward, 'chrome.forward'],
    [el.menu, 'chrome.menu'],
    [el.dock, 'chrome.dock'],
    [el.downloads, 'chrome.downloads'],
    [q('#find-prev'), 'chrome.find.previous'],
    [q('#find-next'), 'chrome.find.next'],
    [q('#find-close'), 'chrome.find.close'],
    [q('#notice-dismiss'), 'common.close'],
    [el.agentsOff, 'chrome.agentsOff'],
    [el.askSearch, 'chrome.askSearchTitle'],
  ];
  for (const [node, key] of titles) {
    node.title = t(key);
    node.setAttribute('aria-label', t(key));
  }
  el.input.placeholder = t('chrome.placeholder');
  el.input.setAttribute('aria-label', t('chrome.addressLabel'));
  el.findInput.placeholder = t('chrome.find.placeholder');
  el.privateBadge.replaceChildren(icon('private', 16), h('span', { text: t('common.private') }));
  el.askSearch.replaceChildren(icon('sparkles', 14), h('span', { text: t('chrome.askSearch') }));
}

// ------------------------------------------------------------------------------------------------
// Rendering

/** @param {ChromeState} s */
function render(s) {
  state = s;
  const active = s.tabs.find((x) => x.id === s.activeTabId) || null;
  el.privateBadge.hidden = !s.private;
  renderTabs(s);
  renderToolbar(s, active);
  renderAgents(s);
  renderBookmarks(s);
  renderFind(s);
  renderPermission(s);
  renderNotice(s);
}

/** @param {ChromeState} s */
function renderTabs(s) {
  syncList(
    el.tabs,
    s.tabs,
    (tab) => String(tab.id),
    (tab) => createTab(tab),
    (node, tab) => updateTab(node, tab, tab.id === s.activeTabId),
  );
  const narrow = s.tabs.length * 56 > el.tabs.clientWidth && s.tabs.length > 1;
  for (const node of /** @type {HTMLElement[]} */ ([...el.tabs.children])) {
    if (narrow && node.getBoundingClientRect().width < 72) node.dataset.narrow = '';
    else delete node.dataset.narrow;
  }
  const activeNode = /** @type {HTMLElement | null} */ (el.tabs.querySelector('[aria-selected="true"]'));
  if (activeNode) activeNode.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

/** @param {TabState} tab */
function createTab(tab) {
  const node = h('div', { class: 'tab', role: 'tab', tabindex: '-1', dataset: { tabId: String(tab.id), testid: 'tab' } });
  node.setAttribute('data-testid', 'tab');
  const iconBox = h('span', { class: 'tab__icon' });
  const title = h('span', { class: 'tab__title', 'data-testid': 'tab-title' });
  const audio = h('button', { class: 'icon-btn tab__audio', 'data-testid': 'tab-audio', hidden: true });
  const close = h('button', { class: 'icon-btn tab__close', 'data-testid': 'tab-close', title: t('chrome.closeTab'), 'aria-label': t('chrome.closeTab') }, icon('close', 14));
  node.append(iconBox, title, audio, close);
  close.addEventListener('click', (e) => {
    e.stopPropagation();
    api.closeTab(Number(node.dataset.tabId));
  });
  audio.addEventListener('click', (e) => {
    e.stopPropagation();
    const t2 = state?.tabs.find((x) => x.id === Number(node.dataset.tabId));
    if (t2) api.setTabMuted(t2.id, !t2.muted);
  });
  node.addEventListener('auxclick', (e) => {
    if (e.button === 1) {
      e.preventDefault();
      api.closeTab(Number(node.dataset.tabId));
    }
  });
  node.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    api.showTabMenu(Number(node.dataset.tabId), { x: e.clientX, y: e.clientY });
  });
  node.addEventListener('pointerdown', (e) => startTabDrag(e, node));
  node.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') api.activateTab(Number(node.dataset.tabId));
  });
  return node;
}

/** @param {HTMLElement} node @param {TabState} tab @param {boolean} isActive */
function updateTab(node, tab, isActive) {
  node.setAttribute('aria-selected', String(isActive));
  node.tabIndex = isActive ? 0 : -1;
  const label = tab.title || t('chrome.untitled');
  const [iconBox, title, audio] = /** @type {HTMLElement[]} */ ([...node.children]);
  if (title.textContent !== label) title.textContent = label;
  node.title = tab.url && !tab.url.startsWith('trio://') ? `${label}\n${tab.displayUrl}` : label;
  const iconKey = tab.loading ? 'loading' : tab.crashed ? 'crashed' : tab.faviconUrl || (tab.internal ? 'internal' : 'globe');
  if (iconBox.dataset.icon !== iconKey) {
    iconBox.dataset.icon = iconKey;
    if (tab.loading) iconBox.replaceChildren(h('span', { class: 'spinner' }));
    else if (tab.crashed) iconBox.replaceChildren(icon('warning', 16));
    else if (tab.faviconUrl) iconBox.replaceChildren(h('img', { src: tab.faviconUrl, alt: '' }));
    else iconBox.replaceChildren(icon(tab.internal ? 'sparkles' : 'globe', 16));
  }
  if (tab.crashed) node.dataset.crashed = '';
  else delete node.dataset.crashed;
  const showAudio = tab.audible || tab.muted;
  audio.hidden = !showAudio;
  if (showAudio && audio.dataset.muted !== String(tab.muted)) {
    audio.dataset.muted = String(tab.muted);
    audio.replaceChildren(icon(tab.muted ? 'volume-muted' : 'volume', 14));
    audio.title = t(tab.muted ? 'chrome.unmute' : 'chrome.mute');
    audio.setAttribute('aria-label', audio.title);
  }
}

/** @param {ChromeState} s @param {TabState | null} active */
function renderToolbar(s, active) {
  el.back.disabled = !active || !active.canGoBack;
  el.forward.disabled = !active || !active.canGoForward;
  const loading = Boolean(active && active.loading);
  if (el.reload.dataset.mode !== String(loading)) {
    el.reload.dataset.mode = String(loading);
    el.reload.replaceChildren(icon(loading ? 'stop' : 'reload'));
    el.reload.title = t(loading ? 'chrome.stop' : 'chrome.reload');
    el.reload.setAttribute('aria-label', el.reload.title);
  }
  const security = active ? active.security : 'internal';
  if (el.security.dataset.security !== security) {
    el.security.dataset.security = security;
    const name = /** @type {Record<string, string>} */ ({ secure: 'lock', insecure: 'info', internal: 'sparkles', local: 'file', error: 'warning' })[security] || 'globe';
    el.security.replaceChildren(icon(name, 16));
    el.security.title = t(`chrome.security.${security}`);
  }
  if (!editing && document.activeElement !== el.input) el.input.value = active ? active.displayUrl : '';
  else if (!editing && active && document.activeElement === el.input && el.input.value !== active.displayUrl && !suggestions.length) {
    el.input.value = active.displayUrl;
    el.input.select();
  }
  el.zoom.hidden = !active || active.zoomPercent === 100;
  if (active) el.zoom.textContent = `${active.zoomPercent}%`;
  el.askSearch.hidden = !(active && active.search && !s.private && document.activeElement !== el.input);
  if (active && active.search) el.askSearch.title = t('chrome.askSearchTitle', { query: active.search.query });
  el.star.hidden = !active || active.internal;
  const starred = Boolean(active && active.bookmarked);
  if (el.star.dataset.on !== String(starred)) {
    el.star.dataset.on = String(starred);
    el.star.replaceChildren(icon(starred ? 'star-filled' : 'star', 18));
    el.star.setAttribute('aria-pressed', String(starred));
    el.star.title = t(starred ? 'chrome.bookmarkRemove' : 'chrome.bookmarkAdd');
    el.star.setAttribute('aria-label', el.star.title);
  }
  el.dock.setAttribute('aria-pressed', String(s.dockVisible));
  el.dock.hidden = s.private;
  const d = s.downloads;
  el.downloads.hidden = !(d.activeCount > 0 || d.unseen);
  if (d.progress !== null && d.activeCount > 0) {
    el.downloads.dataset.progress = '';
    setStyle(el.downloads, '--p', String(d.progress));
  } else delete el.downloads.dataset.progress;
  if (d.unseen) el.downloads.dataset.unseen = '';
  else delete el.downloads.dataset.unseen;
}

/** @param {ChromeState} s */
function renderAgents(s) {
  el.agentsOff.hidden = !s.private;
  el.agents.hidden = s.private;
  if (s.private) return;
  syncList(
    el.agents,
    s.agents.filter((a) => a.enabled),
    (a) => a.id,
    (a) => {
      const b = h('button', { class: 'agent-btn', 'data-testid': 'agent-button', dataset: { agentId: a.id } }, agentCircle(a, { size: 24 }));
      b.addEventListener('click', () => api.toggleAgent(a.id));
      return b;
    },
    (b, a) => {
      updateAgentCircle(/** @type {HTMLElement} */ (b.firstElementChild), a, { size: 24 });
      b.setAttribute('aria-pressed', String(a.open));
      const label = a.state === 'signedIn' ? t('chrome.agentOpen', { name: a.name }) : t('chrome.agentLocked', { name: a.name });
      b.title = label;
      b.setAttribute('aria-label', label);
    },
  );
}

/** @param {ChromeState} s */
function renderBookmarks(s) {
  el.bookmarks.hidden = !s.bookmarksBar.visible;
  if (!s.bookmarksBar.visible) return;
  syncList(
    el.bookmarks,
    s.bookmarksBar.items,
    (b) => b.id,
    (b) => {
      const node = h('button', { class: 'bookmark-item', 'data-testid': 'bookmark-item', dataset: { bookmarkId: b.id } });
      node.addEventListener('click', (e) => api.openBookmark(b.id, { newTab: e.ctrlKey || e.metaKey }));
      node.addEventListener('auxclick', (e) => {
        if (e.button === 1) api.openBookmark(b.id, { newTab: true });
      });
      node.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        api.showBookmarkMenu(b.id, { x: e.clientX, y: e.clientY });
      });
      return node;
    },
    (node, b) => {
      const key = `${b.title}|${b.faviconUrl || ''}`;
      if (node.dataset.render === key) return;
      node.dataset.render = key;
      node.title = `${b.title}\n${b.url}`;
      node.replaceChildren(b.faviconUrl ? h('img', { src: b.faviconUrl, alt: '' }) : icon('globe', 14), h('span', { text: b.title || b.url }));
    },
  );
  if (!s.bookmarksBar.items.length) {
    el.bookmarks.replaceChildren(h('span', { class: 'bookmark-item', text: t('chrome.bookmarksEmpty') }));
  }
}

/** @param {ChromeState} s */
function renderFind(s) {
  const open = s.find !== null;
  const wasOpen = !el.findBar.hidden;
  el.findBar.hidden = !open;
  if (!open) return;
  const f = /** @type {NonNullable<ChromeState['find']>} */ (s.find);
  if (!wasOpen) el.findInput.value = f.query;
  el.findCount.textContent = f.query ? (f.total ? `${f.current}/${f.total}` : t('chrome.find.none')) : '';
}

/** @param {ChromeState} s */
function renderPermission(s) {
  const p = s.permissionPrompt;
  el.permissionBar.hidden = !p;
  if (!p) return;
  el.permissionBar.dataset.id = String(p.id);
  el.permissionText.textContent = t('chrome.permission.text', { origin: p.origin.replace(/^https?:\/\//, ''), permission: t(`permission.${p.permission}`) });
}

/** @param {ChromeState} s */
function renderNotice(s) {
  const n = s.notice;
  el.noticeBar.hidden = !n;
  if (!n) return;
  el.noticeBar.dataset.id = String(n.id);
  el.noticeBar.dataset.kind = n.kind;
  const name = /** @type {Record<string, string>} */ ({ info: 'info', success: 'check', warning: 'warning', error: 'warning' })[n.kind];
  q('#notice-icon').replaceChildren(icon(name, 18));
  el.noticeText.textContent = n.text;
  el.noticeAction.hidden = !n.actionLabel;
  el.noticeAction.textContent = n.actionLabel || '';
}

// ------------------------------------------------------------------------------------------------
// Tab dragging

/** @param {PointerEvent} e @param {HTMLElement} node */
function startTabDrag(e, node) {
  if (e.button !== 0 || /** @type {HTMLElement} */ (e.target).closest('button')) return;
  const id = Number(node.dataset.tabId);
  const startX = e.clientX;
  let dragging = false;
  node.setPointerCapture(e.pointerId);
  /** @param {PointerEvent} ev */
  const move = (ev) => {
    const dx = ev.clientX - startX;
    if (!dragging && Math.abs(dx) > 5) {
      dragging = true;
      node.dataset.dragging = '';
    }
    if (dragging) node.style.transform = `translateX(${dx}px)`;
  };
  /** @param {PointerEvent} ev */
  const up = (ev) => {
    node.removeEventListener('pointermove', move);
    node.removeEventListener('pointerup', up);
    node.removeEventListener('pointercancel', up);
    if (!dragging) {
      api.activateTab(id);
      return;
    }
    node.style.transform = '';
    delete node.dataset.dragging;
    const others = /** @type {HTMLElement[]} */ ([...el.tabs.children]).filter((c) => c !== node);
    let index = others.length;
    for (let i = 0; i < others.length; i++) {
      const r = others[i].getBoundingClientRect();
      if (ev.clientX < r.left + r.width / 2) {
        index = i;
        break;
      }
    }
    api.moveTab(id, index);
  };
  node.addEventListener('pointermove', move);
  node.addEventListener('pointerup', up);
  node.addEventListener('pointercancel', up);
}

// ------------------------------------------------------------------------------------------------
// Address bar and suggestions

function openDropdown() {
  if (!el.dropdown.hidden) return;
  api.setOverlay(true);
  el.dropdown.hidden = false;
  el.omnibox.dataset.open = '';
  positionDropdown();
}

function closeDropdown() {
  suggestions = [];
  suggestSeq++;
  if (el.dropdown.hidden) return;
  el.dropdown.hidden = true;
  el.dropdown.replaceChildren();
  delete el.omnibox.dataset.open;
  el.input.removeAttribute('aria-activedescendant');
  api.setOverlay(false);
}

function positionDropdown() {
  const r = el.omnibox.getBoundingClientRect();
  setStyle(el.dropdown, 'left', `${r.left}px`);
  setStyle(el.dropdown, 'top', `${r.bottom - 1}px`);
  setStyle(el.dropdown, 'width', `${r.width}px`);
}

/** @param {Suggestion} s */
function suggestionIcon(s) {
  if (s.kind === 'agent' && s.agentId && s.agentId !== 'all') {
    const agent = state?.agents.find((a) => a.id === s.agentId);
    if (agent) return agentCircle(agent, { size: 24 });
  }
  if (s.faviconUrl) return h('img', { src: s.faviconUrl, alt: '' });
  const name = /** @type {Record<string, string>} */ ({ url: 'globe', search: 'search', remote: 'search', history: 'history', bookmark: 'star', agent: 'sparkles', 'history-search': 'history' })[s.kind] || 'globe';
  return icon(name, 18);
}

function renderSuggestions() {
  if (!suggestions.length) {
    closeDropdown();
    return;
  }
  openDropdown();
  el.dropdown.replaceChildren(
    ...suggestions.map((s, i) => {
      const row = h(
        'div',
        { class: 'suggestion', role: 'option', id: `sugg-${i}`, 'aria-selected': String(i === selected), 'data-testid': 'suggestion', dataset: { kind: s.kind, index: String(i) } },
        h('span', { class: 'suggestion__icon' }, suggestionIcon(s)),
        h('span', { class: 'suggestion__text' }, h('span', { class: 'suggestion__title', text: s.title }), h('span', { class: 'suggestion__subtitle', text: s.subtitle })),
        s.locked ? h('span', { class: 'suggestion__lock', title: t('chrome.agentLockedShort') }, icon('lock', 14)) : null,
      );
      row.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        accept(i, e.ctrlKey || e.metaKey);
      });
      row.addEventListener('pointermove', () => {
        if (selected !== i) {
          selected = i;
          updateSelection(false);
        }
      });
      return row;
    }),
  );
  el.input.setAttribute('aria-activedescendant', `sugg-${selected}`);
  positionDropdown();
}

/** @param {boolean} fill */
function updateSelection(fill) {
  for (const row of /** @type {HTMLElement[]} */ ([...el.dropdown.children])) row.setAttribute('aria-selected', String(Number(row.dataset.index) === selected));
  el.input.setAttribute('aria-activedescendant', `sugg-${selected}`);
  if (fill && suggestions[selected]) el.input.value = selected === 0 ? typedText : suggestions[selected].fill;
}

const requestSuggestions = debounce((/** @type {string} */ text) => {
  const mine = ++suggestSeq;
  api.suggest(text).then((list) => {
    if (mine !== suggestSeq || !editing) return;
    suggestions = list;
    selected = 0;
    renderSuggestions();
  });
}, 60);

/** @param {number} index @param {boolean} newTab */
function accept(index, newTab) {
  const s = suggestions[index];
  const text = el.input.value;
  editing = false;
  closeDropdown();
  el.input.blur();
  if (s && (index > 0 || s.fill === text || text === typedText)) api.acceptSuggestion(s, { newTab });
  else api.navigate(text, { newTab });
}

function restoreAddress() {
  const active = state?.tabs.find((x) => x.id === state?.activeTabId);
  el.input.value = active ? active.displayUrl : '';
}

el.input.addEventListener('focus', () => {
  el.omnibox.dataset.focused = '';
  el.input.select();
  if (state) renderToolbar(state, state.tabs.find((x) => x.id === state?.activeTabId) || null);
});
el.input.addEventListener('blur', () => {
  delete el.omnibox.dataset.focused;
  closeDropdown();
  if (editing) {
    editing = false;
    restoreAddress();
  }
  if (state) renderToolbar(state, state.tabs.find((x) => x.id === state?.activeTabId) || null);
});
el.input.addEventListener('input', () => {
  editing = true;
  typedText = el.input.value;
  if (!typedText.trim()) {
    closeDropdown();
    return;
  }
  requestSuggestions(typedText);
});
el.input.addEventListener('keydown', (e) => {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    if (!suggestions.length) return;
    e.preventDefault();
    selected = (selected + (e.key === 'ArrowDown' ? 1 : -1) + suggestions.length) % suggestions.length;
    updateSelection(true);
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (e.isComposing) return;
    if (!el.input.value.trim()) return;
    if (suggestions.length) accept(selected, e.altKey);
    else {
      editing = false;
      el.input.blur();
      api.navigate(el.input.value, { newTab: e.altKey });
    }
  } else if (e.key === 'Escape') {
    e.preventDefault();
    if (!el.dropdown.hidden) closeDropdown();
    else {
      editing = false;
      restoreAddress();
      el.input.blur();
    }
  }
});
document.addEventListener('pointerdown', (e) => {
  if (el.dropdown.hidden) return;
  const target = /** @type {Node} */ (e.target);
  if (!el.dropdown.contains(target) && !el.omnibox.contains(target)) {
    el.input.blur();
  }
});
window.addEventListener('resize', () => {
  if (!el.dropdown.hidden) positionDropdown();
  if (state) renderTabs(state);
});

// ------------------------------------------------------------------------------------------------
// Other controls

el.newTab.addEventListener('click', () => api.newTab());
el.spacer.addEventListener('dblclick', () => api.newTab());
el.back.addEventListener('click', () => api.back());
el.forward.addEventListener('click', () => api.forward());
el.reload.addEventListener('click', (e) => {
  if (el.reload.dataset.mode === 'true') api.stop();
  else api.reload({ hard: e.shiftKey });
});
el.star.addEventListener('click', () => api.toggleBookmark());
el.askSearch.addEventListener('click', () => api.askAboutSearch());
el.dock.addEventListener('click', () => api.toggleDock());
el.downloads.addEventListener('click', () => api.openDownloads());
el.menu.addEventListener('click', () => {
  const r = el.menu.getBoundingClientRect();
  api.showAppMenu({ x: r.right - 4, y: r.bottom + 2 });
});
el.tabs.addEventListener('wheel', (e) => {
  if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
    el.tabs.scrollLeft += e.deltaY;
    e.preventDefault();
  }
}, { passive: false });

el.findInput.addEventListener('input', () => api.find({ action: 'search', text: el.findInput.value }));
el.findInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    api.find({ action: e.shiftKey ? 'previous' : 'next' });
  } else if (e.key === 'Escape') {
    e.preventDefault();
    api.find({ action: 'close' });
  }
});
q('#find-prev').addEventListener('click', () => api.find({ action: 'previous' }));
q('#find-next').addEventListener('click', () => api.find({ action: 'next' }));
q('#find-close').addEventListener('click', () => api.find({ action: 'close' }));

q('#permission-allow').addEventListener('click', () => api.respondPermission(Number(el.permissionBar.dataset.id), 'allow', el.permissionRemember.checked));
q('#permission-deny').addEventListener('click', () => api.respondPermission(Number(el.permissionBar.dataset.id), 'deny', el.permissionRemember.checked));
el.noticeAction.addEventListener('click', () => api.dismissNotice(Number(el.noticeBar.dataset.id), true));
q('#notice-dismiss').addEventListener('click', () => api.dismissNotice(Number(el.noticeBar.dataset.id), false));

new ResizeObserver(() => api.setChromeHeight(el.bars.offsetHeight)).observe(el.bars);

// ------------------------------------------------------------------------------------------------
// Boot

async function main() {
  const boot = await api.init();
  applyBoot(boot, { private: boot.state.private });
  setupIcons();
  setupLabels();
  render(boot.state);
  api.setChromeHeight(el.bars.offsetHeight);
  api.onState(render);
  api.onBoot((b) => {
    applyBoot(b, { private: Boolean(state && state.private) });
    setupLabels();
    for (const node of /** @type {HTMLElement[]} */ ([...el.tabs.children, ...el.agents.children, ...el.bookmarks.children])) node.remove();
    for (const node of [el.reload, el.security, el.star]) delete node.dataset.mode, delete node.dataset.security, delete node.dataset.on;
    if (state) render(state);
  });
  api.onCommand((cmd) => {
    if (cmd.type === 'focus-address-bar') {
      el.input.focus();
      if (cmd.text !== undefined && cmd.text !== '') {
        el.input.value = cmd.text;
        editing = true;
        typedText = cmd.text;
        if (cmd.selectAll) el.input.select();
        else el.input.setSelectionRange(cmd.text.length, cmd.text.length);
        requestSuggestions(cmd.text);
      } else el.input.select();
    } else if (cmd.type === 'open-find') {
      el.findBar.hidden = false;
      el.findInput.focus();
      el.findInput.select();
    } else if (cmd.type === 'close-overlays') {
      closeDropdown();
    }
  });
}

main();
