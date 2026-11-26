'use strict';
/** Builds the history-analysis preview: statistics -> digest -> prompt, all on this device. */

const data = require('../../core/data');
const { computeHistoryStats } = require('../../core/history-stats');
const { renderHistoryDigest } = require('../../core/history-digest');
const { buildPrompt } = require('../../core/prompts');

const RANGES = ['today', '7d', '30d', '90d', 'all'];
const KINDS = ['habits', 'topics', 'find', 'time', 'custom'];

/** @param {any} raw @returns {import('../../types/contract').HistoryOptions} */
function cleanOptions(raw) {
  const o = raw && typeof raw === 'object' ? raw : {};
  const inc = o.include && typeof o.include === 'object' ? o.include : {};
  return {
    range: RANGES.includes(o.range) ? o.range : '7d',
    kind: KINDS.includes(o.kind) ? o.kind : 'habits',
    question: typeof o.question === 'string' ? o.question.slice(0, 2000) : '',
    include: { queries: inc.queries !== false, titles: inc.titles !== false, urls: inc.urls === 'paths' ? 'paths' : 'sites' },
  };
}

/**
 * @param {import('../app-context').AppContext} app
 * @param {any} rawOptions
 * @param {number} id
 * @param {import('../../types/contract').Locale} locale
 * @returns {import('../../types/contract').PreviewPayload}
 */
function buildHistoryPreview(app, rawOptions, id, locale) {
  const options = cleanOptions(rawOptions);
  const settings = app.settings.get();
  const visits = app.history ? app.history.allVisits() : [];
  const stats = computeHistoryStats(
    visits,
    {
      now: Date.now(),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
      range: options.range,
      excludeSensitive: settings.privacy.excludeSensitive,
      extraExcludedHosts: settings.privacy.extraExcludedHosts,
    },
    { suffixes: data.suffixData(), rules: data.privacyRules(), searchData: data.searchEngines() },
  );
  const digest = renderHistoryDigest(stats, { locale, include: options.include, kind: options.kind, charLimit: settings.agents.historyCharLimit });
  const prompt = buildPrompt(
    data.prompts(),
    /** @type {any} */ (`history_${options.kind}`),
    { question: options.question, content: digest.text, historyKind: options.kind },
    { locale, charLimit: settings.agents.historyCharLimit + 2000 },
  );
  return {
    id,
    actionId: /** @type {any} */ (`history_${options.kind}`),
    source: app.t('agent.source.history', { range: app.t(`history.range.${options.range}`) }),
    text: prompt.text,
    truncated: digest.truncated || prompt.truncated,
    counts: { visits: stats.totals.visits, sites: stats.totals.sites, searches: stats.totals.searches, excludedSensitive: stats.totals.excluded },
    historyOptions: options,
  };
}

module.exports = { buildHistoryPreview, cleanOptions };
