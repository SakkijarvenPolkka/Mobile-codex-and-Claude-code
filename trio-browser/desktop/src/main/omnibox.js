'use strict';
/** Address-bar suggestions. */

const data = require('../core/data');
const { classifyInput, agentKeywordMap } = require('../core/classify-input');
const { buildSearchUrl, engineById, fillTemplate, parseSuggestions } = require('../core/search');

/**
 * @typedef {import('../types/contract').Suggestion} Suggestion
 * @typedef {import('./browser-window').BrowserWin} BrowserWin
 */

/** @param {string} url */
function urlLabel(url) {
  return url.replace(/^https?:\/\//, '').replace(/\/$/, '');
}

/**
 * @param {BrowserWin} w
 * @param {string} text
 * @returns {Promise<Suggestion[]>}
 */
async function suggest(w, text) {
  const app = w.app;
  const t = app.t;
  const input = String(text || '').slice(0, 2000);
  const kind = classifyInput(input, { agentKeywords: agentKeywordMap(data.providers()), suffixes: data.suffixData() });
  if (kind.type === 'empty') return [];
  const settings = app.settings.get();
  const engine = engineById(data.searchEngines(), settings.search.engine);
  const agents = w.private ? [] : app.agents.summaries().filter((a) => a.enabled);
  /** @type {Suggestion[]} */
  const out = [];
  const base = { faviconUrl: null, agentId: null, locked: false };

  if (kind.type === 'url') {
    out.push({ ...base, kind: 'url', title: urlLabel(kind.url), subtitle: t('omnibox.open'), fill: kind.url, action: { type: 'navigate', url: kind.url } });
  } else if (kind.type === 'search') {
    out.push({ ...base, kind: 'search', title: kind.query, subtitle: t('omnibox.searchWith', { engine: engine.name }), fill: input.trim(), action: { type: 'search', query: kind.query } });
  } else if (kind.type === 'agent') {
    const target = kind.agentId;
    const summary = agents.find((a) => a.id === target);
    const name = target === 'all' ? t('omnibox.allAgents') : summary ? summary.name : target;
    out.push({
      ...base,
      kind: 'agent',
      title: kind.query || t('omnibox.openAgent', { name }),
      subtitle: w.private ? t('notice.privateNoAgents') : t('omnibox.askAgent', { name }),
      fill: input.trim(),
      action: { type: 'agent', agentId: target, query: kind.query },
      agentId: target,
      locked: target === 'all' ? false : Boolean(summary && summary.state !== 'signedIn'),
    });
    return out;
  } else if (kind.type === 'history') {
    out.push({ ...base, kind: 'history-search', title: kind.query || t('omnibox.openHistory'), subtitle: t('omnibox.searchHistory'), fill: input.trim(), action: { type: 'history', query: kind.query } });
  }

  const words = input.trim();
  const seen = new Set(out.flatMap((s) => (s.action.type === 'navigate' ? [s.action.url] : [])));
  if (kind.type !== 'history') {
    const bookmarks = app.bookmarks.search(words, 3);
    for (const b of bookmarks) {
      if (seen.has(b.url)) continue;
      seen.add(b.url);
      out.push({ ...base, kind: 'bookmark', title: b.title || urlLabel(b.url), subtitle: urlLabel(b.url), fill: b.url, action: { type: 'navigate', url: b.url }, faviconUrl: app.history ? app.history.faviconForUrl(b.url) : null });
    }
    if (app.history && !w.private) {
      for (const h of app.history.suggest(words, 6)) {
        if (seen.has(h.url) || out.length >= 8) continue;
        seen.add(h.url);
        out.push({ ...base, kind: 'history', title: h.title || urlLabel(h.url), subtitle: urlLabel(h.url), fill: h.url, action: { type: 'navigate', url: h.url }, faviconUrl: app.history.faviconForUrl(h.url) });
      }
    }
  }

  if (settings.search.suggestions && engine.suggestTemplate && kind.type === 'search' && !w.private) {
    try {
      const res = await w.session.fetch(fillTemplate(engine.suggestTemplate, words), { signal: AbortSignal.timeout(1500), credentials: 'omit' });
      const list = parseSuggestions(engine.suggestFormat, JSON.parse(await res.text()));
      for (const q of list.slice(0, 4)) {
        if (q === kind.query) continue;
        out.push({ ...base, kind: 'remote', title: q, subtitle: t('omnibox.searchWith', { engine: engine.name }), fill: q, action: { type: 'search', query: q } });
      }
    } catch {
      /* suggestions are optional */
    }
  }

  if (kind.type === 'search' || kind.type === 'url') {
    const question = kind.type === 'search' ? kind.query : words;
    for (const a of agents) {
      out.push({
        ...base,
        kind: 'agent',
        title: question,
        subtitle: a.state === 'signedIn' ? t('omnibox.askAgent', { name: a.name }) : t('omnibox.askAgentLocked', { name: a.name }),
        fill: `@${a.id} ${question}`,
        action: { type: 'agent', agentId: a.id, query: question },
        agentId: a.id,
        locked: a.state !== 'signedIn',
      });
    }
  }
  return out;
}

module.exports = { suggest, buildSearchUrl };
