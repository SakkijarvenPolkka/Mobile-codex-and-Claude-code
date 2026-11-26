'use strict';
/**
 * How a prompt reaches an agent: through the provider's URL prefill when it has one that fits
 * and does not auto-submit, otherwise by inserting it into the composer, and as a last resort
 * through the clipboard.
 */

/** Longest text the browser inserts in one go; longer text goes through the clipboard. */
const MAX_INSERT_CHARS = 100000;

/**
 * @param {import('../types/contract').ProviderConfig} provider
 * @param {string} text
 * @param {import('../types/contract').HandoffTarget} target
 * @returns {import('../types/contract').HandoffPlan}
 */
function planHandoff(provider, text, target) {
  if (text.length > MAX_INSERT_CHARS) return { method: 'clipboard' };
  const p = provider.prefill;
  if (p && !p.autoSubmits && target === 'new' && text) {
    const encoded = encodeURIComponent(text);
    if (encoded.length <= p.maxEncodedChars) return { method: 'url', url: p.template.replace('{prompt}', encoded) };
  }
  return { method: 'insert', url: target === 'new' ? provider.appUrl : null };
}

module.exports = { planHandoff, MAX_INSERT_CHARS };
