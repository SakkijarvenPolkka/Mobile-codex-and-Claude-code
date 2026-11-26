'use strict';
/**
 * Builds the text the browser puts into an agent's composer. Page, selection and history text is
 * untrusted: it is wrapped in a delimited block after a fixed notice, and any delimiter-like runs
 * inside it are defused. Templates come from shared/prompts.json.
 */

/**
 * @typedef {import('../types/contract').ActionId} ActionId
 * @typedef {'ko' | 'en'} Locale
 * @typedef {{ question?: string, query?: string, title?: string, url?: string, content?: string, historyKind?: import('../types/contract').HistoryAnalysisKind }} PromptInput
 * @typedef {{ locale: Locale, charLimit: number }} PromptOptions
 */

/** @param {string} template @param {Record<string, string>} values */
function fill(template, values) {
  return template.replace(/\{(\w+)\}/g, (_, k) => (k in values ? values[k] : ''));
}

/**
 * Normalises untrusted text: unifies line ends, trims trailing spaces, collapses runs of blank
 * lines and defuses "<<<" / ">>>" so the content cannot close its own block.
 * @param {string} text
 */
function cleanContent(text) {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t ]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/<{3,}/g, '<<')
    .replace(/>{3,}/g, '>>')
    .trim();
}

/** Cuts a string to at most `max` UTF-16 units without splitting a surrogate pair. */
function cut(/** @type {string} */ s, /** @type {number} */ max) {
  if (s.length <= max) return s;
  let end = Math.max(0, max);
  const code = s.charCodeAt(end - 1);
  if (code >= 0xd800 && code <= 0xdbff) end--;
  return s.slice(0, end);
}

/** True when the text is mostly Hangul. @param {string} text */
function isMostlyKorean(text) {
  const hangul = (text.match(/[ㄱ-ㆎ가-힣]/g) || []).length;
  const latin = (text.match(/[A-Za-z]/g) || []).length;
  return hangul > latin;
}

/**
 * @param {any} prompts contents of shared/prompts.json
 * @param {ActionId} actionId
 * @param {PromptInput} input
 * @param {PromptOptions} options
 * @returns {{ text: string, truncated: boolean }}
 */
function buildPrompt(prompts, actionId, input, options) {
  const { locale } = options;
  const isHistory = actionId.startsWith('history_');
  const key = isHistory ? 'history' : actionId;
  const template = prompts.actions[key]?.[locale];
  if (!template) throw new Error(`Unknown prompt action: ${actionId}`);

  const tag = isHistory ? 'HISTORY' : actionId === 'explain_selection' || actionId === 'translate_selection' ? 'TEXT' : 'PAGE';
  const untrustedNotice = fill(prompts.untrustedNotice[locale], { tag });
  const question = (input.question || '').trim();

  /** @type {Record<string, string>} */
  const values = {
    question,
    query: (input.query || '').trim(),
    title: (input.title || '').trim(),
    url: input.url || '',
    untrustedNotice,
    pageBlock: '',
    targetLanguage: '',
    task: '',
  };

  let content = cleanContent(input.content || '');
  if (actionId === 'translate_selection') {
    // Korean text is translated into English, anything else into Korean.
    values.targetLanguage = prompts.languages[locale][isMostlyKorean(content) ? 'en' : 'ko'];
  }
  if (isHistory) {
    const kind = input.historyKind || /** @type {any} */ (actionId.slice('history_'.length));
    values.task = fill(prompts.historyTasks[kind][locale], { question });
  }

  /** @param {string} c */
  const render = (c) => {
    if (actionId === 'search_page') {
      const v = { ...values, pageBlock: c ? `\n\n${untrustedNotice}\n\n<<<PAGE\n${c}\nPAGE>>>` : '' };
      return fill(template, v);
    }
    return fill(template, { ...values, content: c });
  };

  let text = render(content);
  let truncated = false;
  if (text.length > options.charLimit && content) {
    const overhead = render('').length;
    const marker = locale === 'ko' ? '\n…(길이 제한으로 뒷부분 생략)' : '\n…(rest omitted to fit the length limit)';
    const room = Math.max(0, options.charLimit - overhead - marker.length);
    content = cut(content, room).replace(/\s+$/, '') + marker;
    text = render(content);
    truncated = true;
  }
  return { text, truncated };
}

module.exports = { buildPrompt, cleanContent, isMostlyKorean };
