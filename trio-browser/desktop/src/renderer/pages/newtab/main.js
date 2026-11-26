// New-tab page: search box, agent cards and frequently visited sites.
import { h, q } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t } from '../../common/boot.js';
import { agentCircle } from '../../common/agent-circle.js';
import { bootPage, api, navLink } from '../../common/page.js';

const page = q('#page');

/** @param {{ private: boolean }} ctx */
async function render(ctx) {
  const data = await api.getNewTabData();
  const input = /** @type {HTMLInputElement} */ (h('input', { type: 'text', 'data-testid': 'newtab-search', placeholder: t('chrome.placeholder'), autocomplete: 'off', spellcheck: 'false', 'aria-label': t('chrome.addressLabel') }));
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing && input.value.trim()) api.navigate(input.value, { newTab: e.altKey });
  });
  const dots = h('span', { class: 'wordmark__dots', 'aria-hidden': 'true' }, ...['#D97757', '#4C8DF6', '#10A37F'].map((c) => {
    const d = h('span');
    d.style.background = c;
    return d;
  }));
  /** @type {(Node | null)[]} */
  const parts = [
    h('div', { class: 'wordmark' }, h('span', { text: 'Trio' }), dots),
    h('label', { class: 'search' }, icon('search'), input),
    h('p', { class: 'hint', text: ctx.private ? t('newtab.hintPrivate', { engine: data.searchEngineName }) : t('newtab.hint', { engine: data.searchEngineName }) }),
  ];
  if (ctx.private) {
    parts.push(h('section', { class: 'private-intro card' }, icon('private', 32), h('h2', { text: t('newtab.private.title') }), h('p', { text: t('newtab.private.body') })));
  } else {
    if (data.agents.length) {
      parts.push(
        h(
          'div',
          { class: 'agents' },
          ...data.agents.map((a) => {
            const card = h(
              'button',
              { class: 'agent-card', 'data-testid': 'agent-card', dataset: { agentId: a.id } },
              agentCircle(a, { size: 40 }),
              h('span', { class: 'agent-card__text' }, h('span', { class: 'agent-card__name', text: a.name }), h('span', { class: 'agent-card__state', text: t(`agent.state.${a.state}`) })),
              h('span', { class: 'agent-card__action', text: a.state === 'signedIn' ? t('common.open') : t('common.signIn') }),
            );
            card.addEventListener('click', () => api.toggleAgent(a.id));
            return card;
          }),
        ),
      );
    }
    if (data.topSites.length) {
      parts.push(
        h(
          'nav',
          { class: 'sites', 'aria-label': t('newtab.topSites') },
          ...data.topSites.map((s) => {
            const a = /** @type {HTMLAnchorElement} */ (h('a', { class: 'site', 'data-testid': 'top-site', title: s.url }, h('span', { class: 'site__icon' }, s.faviconUrl ? h('img', { src: s.faviconUrl, alt: '' }) : icon('globe')), h('span', { class: 'site__title', text: s.title || s.host })));
            navLink(a, s.url);
            return a;
          }),
        ),
      );
    }
  }
  page.replaceChildren(...parts.filter(Boolean).map((n) => /** @type {Node} */ (n)));
  input.focus();
}

bootPage('newtab.title', render).then(() => {
  api.onChanged((what) => {
    if (what === 'agents') render({ private: false });
  });
});
