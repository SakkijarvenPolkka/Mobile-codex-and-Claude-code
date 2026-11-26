// Small DOM helpers; no framework.

/**
 * @typedef {Record<string, any>} Props
 * @typedef {Node | string | number | null | undefined | false} Child
 */

/**
 * Creates an element. Props: `class`, `dataset`, `on<Event>` listeners, `text`, boolean and
 * string attributes (aria-*, role, type, …), and `value` / `checked` / `disabled` properties.
 * @template {keyof HTMLElementTagNameMap} K
 * @param {K} tag
 * @param {Props | null} [props]
 * @param {...(Child | Child[])} children
 * @returns {HTMLElementTagNameMap[K]}
 */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') el.className = String(value);
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (key === 'text') el.textContent = String(value);
      else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
      else if (key === 'value' || key === 'checked' || key === 'disabled' || key === 'selected') /** @type {any} */ (el)[key] = value;
      else el.setAttribute(key, value === true ? '' : String(value));
    }
  }
  append(el, children);
  return el;
}

/** @param {Element} el @param {(Child | Child[])[]} children */
function append(el, children) {
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(typeof c === 'object' ? c : String(c));
  }
}

/**
 * @template {Element} [T=HTMLElement]
 * @param {string} selector
 * @param {ParentNode} [root]
 * @returns {T}
 */
export function q(selector, root = document) {
  const el = root.querySelector(selector);
  if (!el) throw new Error(`Missing element: ${selector}`);
  return /** @type {T} */ (el);
}

/** @param {Element} el */
export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
}

/**
 * Keyed list reconciliation: reuses elements by key, creates new ones, removes stale ones and
 * fixes the order without touching unchanged elements (so focus and scroll survive).
 * @template T
 * @param {Element} container
 * @param {T[]} items
 * @param {(item: T) => string} keyOf
 * @param {(item: T) => HTMLElement} create
 * @param {(el: HTMLElement, item: T) => void} update
 */
export function syncList(container, items, keyOf, create, update) {
  /** @type {Map<string, HTMLElement>} */
  const existing = new Map();
  for (const child of /** @type {HTMLElement[]} */ ([...container.children])) {
    const k = child.dataset.key;
    if (k !== undefined) existing.set(k, child);
  }
  /** @type {HTMLElement[]} */
  const next = [];
  for (const item of items) {
    const key = keyOf(item);
    let el = existing.get(key);
    if (el) existing.delete(key);
    else {
      el = create(item);
      el.dataset.key = key;
    }
    update(el, item);
    next.push(el);
  }
  for (const el of existing.values()) el.remove();
  let cursor = container.firstElementChild;
  for (const el of next) {
    if (el === cursor) {
      cursor = cursor.nextElementSibling;
    } else {
      container.insertBefore(el, cursor);
    }
  }
}

/**
 * @template {any[]} A
 * @param {(...args: A) => void} fn
 * @param {number} ms
 * @returns {((...args: A) => void) & { cancel: () => void }}
 */
export function debounce(fn, ms) {
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  let timer;
  const wrapped = (/** @type {A} */ ...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
  wrapped.cancel = () => clearTimeout(timer);
  return wrapped;
}

/**
 * Wraps an async function so that only the result of the latest call is delivered.
 * @template {any[]} A
 * @template R
 * @param {(...args: A) => Promise<R>} fn
 * @param {(result: R) => void} onResult
 * @returns {(...args: A) => void}
 */
export function latestOnly(fn, onResult) {
  let seq = 0;
  return (...args) => {
    const mine = ++seq;
    fn(...args).then(
      (r) => {
        if (mine === seq) onResult(r);
      },
      () => {},
    );
  };
}

/** Sets a CSS custom property or inline style property from script (allowed by the CSP). */
export function setStyle(/** @type {HTMLElement} */ el, /** @type {string} */ prop, /** @type {string} */ value) {
  el.style.setProperty(prop, value);
}
