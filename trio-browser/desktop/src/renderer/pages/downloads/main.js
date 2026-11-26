// Downloads page.
import { h, q } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t, formatBytes } from '../../common/boot.js';
import { bootPage, api } from '../../common/page.js';

const page = q('#page');

/** @param {import('../../../types/contract').DownloadItem} d */
function statusText(d) {
  const size = d.totalBytes ? `${formatBytes(d.receivedBytes)} / ${formatBytes(d.totalBytes)}` : formatBytes(d.receivedBytes);
  if (d.state === 'completed') return d.exists ? formatBytes(d.totalBytes || d.receivedBytes) : t('downloads.deleted');
  return `${t(`downloads.state.${d.state}`)} · ${size}`;
}

async function render() {
  const items = await api.getDownloads();
  /** @param {string} id @param {string} name @param {string} label @param {any} action */
  const btn = (id, name, label, action) => {
    const b = h('button', { class: 'icon-btn icon-btn--sm', title: label, 'aria-label': label }, icon(name, 16));
    b.addEventListener('click', async () => {
      await api.downloadAction(id, action);
      render();
    });
    return b;
  };
  const rows = items.map((d) => {
    let host = '';
    try {
      host = new URL(d.url).host;
    } catch {
      host = '';
    }
    const actions = [];
    if (d.state === 'completed' && d.exists) actions.push(btn(d.id, 'external', t('downloads.open'), 'open'), btn(d.id, 'folder', t('downloads.show'), 'show'));
    if (d.state === 'progressing') actions.push(btn(d.id, 'pause', t('downloads.pause'), 'pause'), btn(d.id, 'close', t('downloads.cancel'), 'cancel'));
    if (d.state === 'paused') actions.push(btn(d.id, 'play', t('downloads.resume'), 'resume'), btn(d.id, 'close', t('downloads.cancel'), 'cancel'));
    actions.push(btn(d.id, 'trash', t('downloads.remove'), 'remove'));
    const progress =
      d.state === 'progressing' || d.state === 'paused'
        ? (() => {
            const bar = h('span');
            bar.style.width = d.totalBytes ? `${Math.round((d.receivedBytes / d.totalBytes) * 100)}%` : '30%';
            return h('div', { class: 'progress download-progress' }, bar);
          })()
        : null;
    return h(
      'div',
      { class: 'list-row', 'data-testid': 'download-row', dataset: { downloadId: d.id, state: d.state } },
      h('span', { class: 'list-row__icon' }, icon('file', 18)),
      h('span', { class: 'list-row__main' }, h('span', { class: 'list-row__title', text: d.filename }), h('span', { class: 'list-row__sub', text: `${host} · ${statusText(d)}` }), progress),
      h('span', { class: 'list-row__actions' }, ...actions),
    );
  });
  const clear = h('button', { class: 'btn', 'data-testid': 'downloads-clear', text: t('downloads.clear'), disabled: !items.length });
  clear.addEventListener('click', async () => {
    await api.clearDownloads();
    render();
  });
  page.replaceChildren(
    h('div', { class: 'page__head' }, h('h1', { class: 'page__title', text: t('downloads.title') }), clear),
    h('section', { class: 'section' }, ...(rows.length ? rows : [h('div', { class: 'empty-state' }, icon('download', 32), h('span', { text: t('downloads.empty') }))])),
  );
}

bootPage('downloads.title', render).then(() => {
  api.onChanged((what) => {
    if (what === 'downloads') render();
  });
});
