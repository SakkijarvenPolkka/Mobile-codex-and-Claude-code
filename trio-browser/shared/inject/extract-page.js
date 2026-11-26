/*
 * Trio — page text extraction. Evaluated in the page (an isolated world on desktop) as an
 * expression; returns { title, url, lang, selection, text, truncated }.
 * Never reads form values, password fields, editable regions, hidden content, cookies or storage.
 */
(() => {
  const MAX = 200000;
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON', 'SVG', 'CANVAS', 'IFRAME', 'OBJECT', 'EMBED', 'VIDEO', 'AUDIO', 'NAV', 'FOOTER', 'ASIDE', 'FORM']);
  const BLOCK = /^(block|flex|grid|list-item|table|table-row|table-caption|flow-root)/;

  function hiddenOrPrivate(el) {
    if (SKIP.has(el.tagName)) return true;
    if (el.isContentEditable || el.hidden || el.getAttribute('aria-hidden') === 'true') return true;
    if (typeof el.checkVisibility === 'function' && !el.checkVisibility()) return true;
    return false;
  }

  function collect(root) {
    const out = [];
    let length = 0;
    let visited = 0;
    const walk = (node) => {
      if (length >= MAX || visited > 40000) return;
      visited++;
      if (node.nodeType === Node.TEXT_NODE) {
        const t = node.nodeValue.replace(/\s+/g, ' ');
        if (t.trim()) {
          out.push(t);
          length += t.length;
        }
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE || hiddenOrPrivate(node)) return;
      const block = node.tagName === 'BR' || BLOCK.test(getComputedStyle(node).display);
      if (block) out.push('\n');
      for (const child of node.childNodes) walk(child);
      if (block) out.push('\n');
    };
    walk(root);
    const text = out
      .join('')
      .split('\n')
      .map((l) => l.trim())
      .filter((l, i, a) => l || (i > 0 && a[i - 1]))
      .join('\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
    return { text: text.slice(0, MAX), truncated: text.length > MAX || length >= MAX };
  }

  function mainElement() {
    const candidates = [...document.querySelectorAll('article, main, [role="main"]')];
    let best = null;
    let bestLen = 0;
    for (const c of candidates) {
      const len = (c.textContent || '').length;
      if (len > bestLen) {
        best = c;
        bestLen = len;
      }
    }
    return best && bestLen > 500 ? best : document.body;
  }

  let selection = '';
  try {
    const s = window.getSelection();
    const anchor = s && s.anchorNode ? (s.anchorNode.nodeType === 1 ? s.anchorNode : s.anchorNode.parentElement) : null;
    const inEditable = anchor && (anchor.closest('input, textarea, [contenteditable]') || anchor.isContentEditable);
    if (s && !inEditable) selection = String(s).trim().slice(0, MAX);
  } catch (e) {
    selection = '';
  }
  const root = document.body ? mainElement() : null;
  const body = root ? collect(root) : { text: '', truncated: false };
  return {
    title: document.title || '',
    url: location.href,
    lang: document.documentElement.lang || '',
    selection,
    text: body.text,
    truncated: body.truncated,
  };
})()
