'use strict';
/** UI strings for the main process (menus, notices) and for the renderers' boot data. */
const fs = require('node:fs');
const path = require('node:path');
const { app, nativeTheme } = require('electron');

/** @typedef {import('../types/contract').Locale} Locale */

/** @type {Record<string, Record<string, string>>} */
const cache = {};

/** @param {Locale} locale @returns {Record<string, string>} */
function strings(locale) {
  if (!cache[locale]) {
    cache[locale] = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'renderer', 'common', 'i18n', `${locale}.json`), 'utf8'));
  }
  return cache[locale];
}

/** @param {'system' | Locale} setting @returns {Locale} */
function resolveLocale(setting) {
  if (setting === 'ko' || setting === 'en') return setting;
  const sys = (app.getPreferredSystemLanguages()[0] || app.getLocale() || 'en').toLowerCase();
  return sys.startsWith('ko') ? 'ko' : 'en';
}

/** @param {'system' | 'light' | 'dark'} setting @returns {'light' | 'dark'} */
function resolveTheme(setting) {
  if (setting === 'light' || setting === 'dark') return setting;
  return nativeTheme.shouldUseDarkColors ? 'dark' : 'light';
}

/**
 * @param {Locale} locale
 * @param {string} key
 * @param {Record<string, string | number>} [params]
 */
function t(locale, key, params = {}) {
  const s = strings(locale)[key];
  if (s === undefined) return `⟦${key}⟧`;
  return s.replace(/\{(\w+)\}/g, (_, k) => (k in params ? String(params[k]) : `{${k}}`));
}

module.exports = { strings, resolveLocale, resolveTheme, t };
