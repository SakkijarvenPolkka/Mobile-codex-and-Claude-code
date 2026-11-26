/*
 * Trio — page text extraction. Evaluated in the page (an isolated world on desktop) as an
 * expression; returns { title, url, lang, selection, text, truncated }.
 * Never reads form values, password fields, editable regions, hidden content, cookies or storage.
 */
(() => {
  const MAX = 200000;
  const SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON', 'SVG', 'CANVAS', 'IFRAME', 'OBJECT', 'EMBED', 'VIDEO', 'AUDIO', 'NAV', 'FOOTER', 'ASIDE', 'FORM']);
  const BLOCK = /^(block|flex|grid|list-item|table|table-row|table-caption|flow-root)/;

  const docWidth = Math.max(document.documentElement.scrollWidth, window.innerWidth);

  /** Text the user cannot see (or cannot see as text) is never extracted. */
  function hiddenOrPrivate(el, style) {
    if (SKIP.has(el.tagName)) return true;
    if (el.isContentEditable || el.hidden || el.getAttribute('aria-hidden') === 'true') return true;
    if (
      typeof el.checkVisibility === 'function' &&
      !el.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true, opacityProperty: true, visibilityProperty: true, contentVisibilityAuto: true })
    ) {
      return true;
    }
    if (style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) return true;
    if (parseFloat(style.fontSize) === 0) return true;
    if (style.clipPath === 'inset(50%)' || /rect\(0(px)?,? 0(px)?,? 0(px)?,? 0(px)?\)/.test(style.clip)) return true;
    const r = el.getBoundingClientRect();
    if (style.display !== 'contents') {
      if (r.width <= 1 && r.height <= 1) return true;
      const left = r.left + window.scrollX;
      const top = r.top + window.scrollY;
      if (left + r.width <= 0 || top + r.height <= 0 || left >= docWidth) return true;
    }
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
      if (node.nodeType !== Node.ELEMENT_NODE) return;
      if (node.tagName === 'BR') {
        out.push('\n');
        return;
      }
      const style = getComputedStyle(node);
      if (hiddenOrPrivate(node, style)) return;
      const block = BLOCK.test(style.display);
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
  // On Android this runs in the page's own world, where a named element (<img name="title">) or a
  // redefined getter can make document.title anything: read the real getter, and return strings only.
  const str = (v) => (typeof v === 'string' ? v : '');
  let title = '';
  try {
    const desc = Object.getOwnPropertyDescriptor(Document.prototype, 'title');
    title = str(desc && desc.get ? desc.get.call(document) : document.title);
  } catch (e) {
    title = '';
  }
  return {
    title,
    url: String(location.href),
    lang: str(document.documentElement && document.documentElement.lang),
    selection: str(selection),
    text: str(body.text),
    truncated: !!body.truncated,
  };
})()
