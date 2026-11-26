// Bookmarks manager.
import { h, q } from '../../common/dom.js';
import { icon } from '../../common/icons.js';
import { t } from '../../common/boot.js';
import { bootPage, api, navLink } from '../../common/page.js';

const page = q('#page');
/** @type {string | null} */
let editing = null;

async function render() {
  const items = await api.getBookmarks();
  const titleInput = /** @type {HTMLInputElement} */ (h('input', { class: 'input', 'data-testid': 'bookmark-title', placeholder: t('bookmarks.titlePlaceholder') }));
  const urlInput = /** @type {HTMLInputElement} */ (h('input', { class: 'input', 'data-testid': 'bookmark-url', placeholder: t('bookmarks.urlPlaceholder'), spellcheck: 'false' }));
  const add = /** @type {HTMLButtonElement} */ (h('button', { class: 'btn btn--primary', 'data-testid': 'bookmark-add', type: 'submit', text: t('bookmarks.add') }));
  const error = h('p', { class: 'row__desc', hidden: true });
  const form = h('form', { class: 'add-form' }, titleInput, urlInput, add);
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    let url = urlInput.value.trim();
    if (!url) return;
    if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) url = 'https://' + url;
    try {
      await api.addBookmark({ title: titleInput.value.trim() || url, url });
      render();
    } catch {
      error.hidden = false;
      error.textContent = t('bookmarks.invalidUrl');
    }
  });

  const rows = items.map((b, i) => {
    if (editing === b.id) {
      const tIn = /** @type {HTMLInputElement} */ (h('input', { class: 'input', value: b.title, 'aria-label': t('bookmarks.titlePlaceholder') }));
      const uIn = /** @type {HTMLInputElement} */ (h('input', { class: 'input', value: b.url || '', spellcheck: 'false', 'aria-label': t('bookmarks.urlPlaceholder') }));
      const save = h('button', { class: 'btn btn--primary', text: t('common.save') });
      const cancel = h('button', { class: 'btn', text: t('common.cancel') });
      save.addEventListener('click', async () => {
        try {
          await api.updateBookmark(b.id, { title: tIn.value.trim(), url: uIn.value.trim() });
          editing = null;
          render();
        } catch {
          uIn.setAttribute('aria-invalid', 'true');
        }
      });
      cancel.addEventListener('click', () => {
        editing = null;
        render();
      });
      return h('div', { class: 'list-row', 'data-testid': 'bookmark-row', dataset: { bookmarkId: b.id } }, h('span', { class: 'edit-fields' }, tIn, uIn), h('span', { class: 'list-row__actions' }, cancel, save));
    }
    const link = /** @type {HTMLAnchorElement} */ (h('a', { class: 'list-row__title', text: b.title || b.url || '' }));
    navLink(link, b.url || '');
    /** @param {string} name @param {string} label @param {() => void} fn @param {boolean} [disabled] */
    const btn = (name, label, fn, disabled = false) => {
      const x = h('button', { class: 'icon-btn icon-btn--sm', title: label, 'aria-label': label, disabled }, icon(name, 16));
      x.addEventListener('click', fn);
      return x;
    };
    return h(
      'div',
      { class: 'list-row', 'data-testid': 'bookmark-row', dataset: { bookmarkId: b.id } },
      h('span', { class: 'list-row__icon' }, icon('star', 16)),
      h('span', { class: 'list-row__main' }, link, h('span', { class: 'list-row__sub', text: b.url || '' })),
      h(
        'span',
        { class: 'list-row__actions' },
        btn('chevron-up', t('bookmarks.moveUp'), async () => { await api.moveBookmark(b.id, i - 1); render(); }, i === 0),
        btn('chevron-down', t('bookmarks.moveDown'), async () => { await api.moveBookmark(b.id, i + 1); render(); }, i === items.length - 1),
        btn('edit', t('bookmarks.edit'), () => { editing = b.id; render(); }),
        btn('trash', t('bookmarks.delete'), async () => { await api.removeBookmark(b.id); render(); }),
      ),
    );
  });

  page.replaceChildren(
    h('div', { class: 'page__head' }, h('h1', { class: 'page__title', text: t('bookmarks.title') })),
    h('section', { class: 'section' }, h('h2', { class: 'section__title', text: t('bookmarks.addTitle') }), form, error),
    h('section', { class: 'section', 'data-testid': 'bookmark-list' }, ...(rows.length ? rows : [h('div', { class: 'empty-state' }, icon('star', 32), h('span', { text: t('bookmarks.empty') }))])),
  );
}

bootPage('bookmarks.title', render).then(() => {
  api.onChanged((what) => {
    if (what === 'bookmarks' && editing === null) render();
  });
});
