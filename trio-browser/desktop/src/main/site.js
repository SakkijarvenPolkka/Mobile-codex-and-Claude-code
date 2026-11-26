'use strict';
/** Site keys for URLs, using the shared suffix data. */
const data = require('../core/data');
const { siteKey } = require('../core/url');

/** @param {string} url */
function siteKeyForUrl(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
    return siteKey(u.hostname, data.suffixData());
  } catch {
    return '';
  }
}

module.exports = { siteKeyForUrl };
