// Error page for failed loads: trio://error/?url=…&code=…&desc=…
import { h, q } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t } from '../../common/boot.js';
import { bootPage, api } from '../../common/page.js';

const page = q('#page');
const params = new URLSearchParams(location.search);

function render() {
  const url = params.get('url') || '';
  let host = url;
  try {
    host = new URL(url).host || url;
  } catch {
    host = url;
  }
  const code = params.get('code') || '';
  const desc = params.get('desc') || '';
  const retry = h('button', { class: 'btn btn--primary', 'data-testid': 'error-retry', text: t('error.retry') });
  retry.addEventListener('click', () => api.retry());
  document.title = host;
  page.replaceChildren(
    h('div', { class: 'error' }, icon('warning', 40), h('h1', { text: t('error.title') }), h('p', { text: t(`error.hint.${hintKey(desc)}`, { host }) }), h('code', { text: `${desc || 'ERROR'} (${code})` }), retry),
  );
}

/** @param {string} desc */
function hintKey(desc) {
  if (/NAME_NOT_RESOLVED|ADDRESS_UNREACHABLE/.test(desc)) return 'dns';
  if (/INTERNET_DISCONNECTED|NETWORK_CHANGED/.test(desc)) return 'offline';
  if (/CONNECTION_REFUSED|CONNECTION_RESET|CONNECTION_CLOSED|TIMED_OUT/.test(desc)) return 'refused';
  if (/CERT|SSL/.test(desc)) return 'cert';
  return 'generic';
}

bootPage('error.title', render);
