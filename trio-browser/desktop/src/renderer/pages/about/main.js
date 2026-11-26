// About page.
import { h, q } from '../../common/dom.js';
import { t } from '../../common/boot.js';
import { bootPage, api } from '../../common/page.js';

const page = q('#page');

async function render() {
  const a = await api.getAbout();
  page.replaceChildren(
    h('div', { class: 'page__head' }, h('h1', { class: 'page__title', text: t('about.title') })),
    h(
      'section',
      { class: 'section about', 'data-testid': 'about' },
      h('h2', { class: 'section__title', text: `${a.appName} ${a.appVersion}` }),
      h('dl', null, h('dt', { text: 'Chromium' }), h('dd', { text: a.chromium }), h('dt', { text: 'Electron' }), h('dd', { text: a.electron }), h('dt', { text: 'Node.js' }), h('dd', { text: a.node }), h('dt', { text: t('about.platform') }), h('dd', { text: a.platform })),
      h('p', { text: t('about.notice') }),
    ),
  );
}

bootPage('about.title', render);
