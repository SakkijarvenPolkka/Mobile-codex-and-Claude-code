// Shared boot for internal pages.
import { applyBoot, applyStrings, t } from './boot.js';

const api = /** @type {import('../../types/contract').PagesApi} */ (window.trio.pages);

/**
 * Boots an internal page: applies theme and strings, sets the title, and re-renders when the
 * language or theme changes.
 * @param {string} titleKey
 * @param {(ctx: { private: boolean }) => void | Promise<void>} render
 */
export async function bootPage(titleKey, render) {
  const boot = await api.init();
  applyBoot(boot, { private: boot.private });
  applyStrings();
  document.title = t(titleKey);
  await render({ private: boot.private });
  api.onBoot((b) => {
    applyBoot(b, { private: boot.private });
    applyStrings();
    document.title = t(titleKey);
    render({ private: boot.private });
  });
  return boot;
}

export { api };

/** A link that navigates through the browser (internal pages cannot open web pages directly). */
export function navLink(/** @type {HTMLAnchorElement} */ a, /** @type {string} */ url) {
  a.href = url;
  a.addEventListener('click', (e) => {
    e.preventDefault();
    api.navigate(url, { newTab: e.ctrlKey || e.metaKey || e.button === 1 });
  });
  a.addEventListener('auxclick', (e) => {
    if (e.button === 1) {
      e.preventDefault();
      api.navigate(url, { newTab: true });
    }
  });
}
