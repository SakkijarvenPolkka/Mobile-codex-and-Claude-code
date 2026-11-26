'use strict';
/** Bookmarks (bookmarks.json): a flat, ordered list. */
const { EventEmitter } = require('node:events');
const crypto = require('node:crypto');
const { JsonStore, v } = require('./json-store');

/** @typedef {{ id: string, title: string, url: string, created: number }} Bookmark */

/** @param {string} url */
function validUrl(url) {
  try {
    const u = new URL(url);
    return ['http:', 'https:', 'file:', 'trio:'].includes(u.protocol) ? u.href : null;
  } catch {
    return null;
  }
}

class BookmarkStore extends EventEmitter {
  /** @param {string} file */
  constructor(file) {
    super();
    this.store = new JsonStore(
      file,
      () => /** @type {Bookmark[]} */ ([]),
      (raw) =>
        v
          .arr(raw)
          .filter((b) => b && typeof b.id === 'string' && typeof b.url === 'string' && validUrl(b.url))
          .map((b) => ({ id: b.id, title: v.str(b.title, ''), url: /** @type {string} */ (validUrl(b.url)), created: Number(b.created) || 0 })),
    );
  }

  /** @returns {Bookmark[]} */
  list() {
    return this.store.get();
  }

  /** @param {string} url */
  findByUrl(url) {
    return this.list().find((b) => b.url === url) || null;
  }

  /** @param {{ title: string, url: string }} b */
  add(b) {
    const url = validUrl(b.url);
    if (!url) throw new Error('Invalid bookmark URL');
    const existing = this.findByUrl(url);
    if (existing) return existing;
    const item = { id: crypto.randomUUID(), title: (b.title || url).slice(0, 500), url, created: Date.now() };
    this.store.set([...this.list(), item]);
    this.emit('change');
    return item;
  }

  /** @param {string} id @param {{ title?: string, url?: string }} patch */
  update(id, patch) {
    const url = patch.url !== undefined ? validUrl(patch.url) : undefined;
    if (url === null) throw new Error('Invalid bookmark URL');
    this.store.set(this.list().map((b) => (b.id === id ? { ...b, ...(patch.title !== undefined ? { title: patch.title.slice(0, 500) } : {}), ...(url ? { url } : {}) } : b)));
    this.emit('change');
  }

  /** @param {string} id */
  remove(id) {
    this.store.set(this.list().filter((b) => b.id !== id));
    this.emit('change');
  }

  /** @param {string} id @param {number} toIndex */
  move(id, toIndex) {
    const list = [...this.list()];
    const from = list.findIndex((b) => b.id === id);
    if (from < 0) return;
    const [item] = list.splice(from, 1);
    list.splice(Math.max(0, Math.min(list.length, toIndex)), 0, item);
    this.store.set(list);
    this.emit('change');
  }

  /** @param {string} text @param {number} limit */
  search(text, limit) {
    const terms = text.toLowerCase().split(/\s+/).filter(Boolean);
    if (!terms.length) return [];
    return this.list()
      .filter((b) => terms.every((t) => b.title.toLowerCase().includes(t) || b.url.toLowerCase().includes(t)))
      .slice(0, limit);
  }

  flush() {
    this.store.flush();
  }
}

module.exports = { BookmarkStore, validUrl };
