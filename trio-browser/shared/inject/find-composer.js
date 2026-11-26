/*
 * Trio — composer helper. A function expression; the host calls it as
 *   (<this source>)(mode, selectors, timeoutMs)
 * mode "focus": waits for the first visible element matching one of the selectors, focuses it and
 *   puts the caret at the end. Resolves to { found, selector, length }.
 * mode "measure": resolves to { found, selector, length } without changing focus.
 * Only the composer is touched; nothing else on the page is read.
 */
(function trioComposer(mode, selectors, timeoutMs) {
  function visible(el) {
    if (typeof el.checkVisibility === 'function') return el.checkVisibility();
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }
  function find() {
    for (const sel of selectors) {
      let list;
      try {
        list = document.querySelectorAll(sel);
      } catch (e) {
        continue;
      }
      for (const el of list) {
        if (el.disabled || el.readOnly) continue;
        if (visible(el)) return { el, sel };
      }
    }
    return null;
  }
  function textLength(el) {
    return 'value' in el && typeof el.value === 'string' ? el.value.length : (el.innerText || '').replace(/\n$/, '').length;
  }
  function focusEnd(el) {
    el.focus();
    if ('value' in el && typeof el.setSelectionRange === 'function') {
      const n = el.value.length;
      el.setSelectionRange(n, n);
      return;
    }
    const range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(false);
    const s = window.getSelection();
    s.removeAllRanges();
    s.addRange(range);
  }
  return new Promise((resolve) => {
    const deadline = Date.now() + (mode === 'measure' ? 0 : timeoutMs);
    const tick = () => {
      const hit = find();
      if (hit) {
        if (mode === 'focus') focusEnd(hit.el);
        resolve({ found: true, selector: hit.sel, length: textLength(hit.el) });
        return;
      }
      if (Date.now() >= deadline) {
        resolve({ found: false, selector: null, length: 0 });
        return;
      }
      setTimeout(tick, 150);
    };
    tick();
  });
})
