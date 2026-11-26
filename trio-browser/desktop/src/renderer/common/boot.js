// Surface boot: applies theme, locale and platform, and provides t() for UI strings.

/** @type {Record<string, string>} */
let strings = {};
/** @type {'ko' | 'en'} */
let locale = 'ko';

/**
 * @param {import('../../types/contract').SurfaceBoot} boot
 * @param {{ private?: boolean }} [opts]
 */
export function applyBoot(boot, opts = {}) {
  strings = boot.strings;
  locale = boot.locale;
  const root = document.documentElement;
  root.dataset.theme = boot.theme;
  root.dataset.platform = boot.platform;
  root.lang = boot.locale;
  if (opts.private) root.dataset.private = '';
  else delete root.dataset.private;
}

export const currentLocale = () => locale;

/**
 * @param {string} key
 * @param {Record<string, string | number>} [params]
 */
export function t(key, params = {}) {
  const s = strings[key];
  if (s === undefined) return `⟦${key}⟧`;
  return s.replace(/\{(\w+)\}/g, (_, k) => (k in params ? String(params[k]) : `{${k}}`));
}

/** Fills data-i18n, data-i18n-title, data-i18n-placeholder and data-i18n-aria-label. @param {ParentNode} [root] */
export function applyStrings(root = document) {
  for (const el of /** @type {NodeListOf<HTMLElement>} */ (root.querySelectorAll('[data-i18n]'))) el.textContent = t(/** @type {string} */ (el.dataset.i18n));
  for (const [attr, data] of [['title', 'i18nTitle'], ['placeholder', 'i18nPlaceholder'], ['aria-label', 'i18nAriaLabel']]) {
    for (const el of /** @type {NodeListOf<HTMLElement>} */ (root.querySelectorAll(`[data-${data.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())}]`))) {
      el.setAttribute(attr, t(/** @type {string} */ (el.dataset[data])));
    }
  }
}

/** @param {number} n */
export function formatNumber(n) {
  return new Intl.NumberFormat(locale === 'ko' ? 'ko-KR' : 'en-US').format(n);
}

/** @param {number} bytes */
export function formatBytes(bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${new Intl.NumberFormat(locale === 'ko' ? 'ko-KR' : 'en-US', { maximumFractionDigits: v < 10 && i ? 1 : 0 }).format(v)} ${units[i]}`;
}

/** @param {number} time */
export function formatTime(time) {
  return new Intl.DateTimeFormat(locale === 'ko' ? 'ko-KR' : 'en-US', { hour: '2-digit', minute: '2-digit', hour12: false }).format(time);
}

/** @param {number} time */
export function formatDay(time) {
  const d = new Date(time);
  const today = new Date();
  const start = (/** @type {Date} */ x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((start(today) - start(d)) / 86400000);
  if (diff === 0) return t('common.today');
  if (diff === 1) return t('common.yesterday');
  return new Intl.DateTimeFormat(locale === 'ko' ? 'ko-KR' : 'en-US', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(d);
}
