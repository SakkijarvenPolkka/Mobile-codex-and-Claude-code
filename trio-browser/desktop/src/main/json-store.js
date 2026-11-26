'use strict';
/**
 * A JSON file with defaults, validation on load and atomic, debounced writes
 * (temp file + rename). A missing or corrupt file falls back to the defaults; a corrupt file is
 * kept next to it as <name>.corrupt for inspection.
 */

const fs = require('node:fs');
const path = require('node:path');

/** @template T */
class JsonStore {
  /**
   * @param {string} file
   * @param {() => T} defaults
   * @param {(raw: any, defaults: T) => T} validate returns a clean value built from raw
   * @param {{ delayMs?: number }} [options]
   */
  constructor(file, defaults, validate, options = {}) {
    this.file = file;
    this.delayMs = options.delayMs ?? 300;
    /** @type {NodeJS.Timeout | null} */
    this.timer = null;
    /** @type {T} */
    this.value = this.load(defaults, validate);
  }

  /**
   * @param {() => T} defaults
   * @param {(raw: any, defaults: T) => T} validate
   * @returns {T}
   */
  load(defaults, validate) {
    let raw;
    try {
      raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (err) {
      const e = /** @type {NodeJS.ErrnoException} */ (err);
      if (e.code !== 'ENOENT') {
        try {
          fs.renameSync(this.file, this.file + '.corrupt');
        } catch {
          /* the file may already be gone */
        }
      }
      return defaults();
    }
    try {
      return validate(raw, defaults());
    } catch {
      return defaults();
    }
  }

  /** @returns {T} */
  get() {
    return this.value;
  }

  /** @param {T} value */
  set(value) {
    this.value = value;
    this.scheduleSave();
  }

  scheduleSave() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.flush(), this.delayMs);
  }

  /** Writes now if a save is pending (also call on quit). */
  flush() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.value, null, 1));
    fs.renameSync(tmp, this.file);
  }
}

/** Value helpers for validators. */
const v = {
  /** @param {any} x @param {boolean} d */
  bool: (x, d) => (typeof x === 'boolean' ? x : d),
  /** @param {any} x @param {string} d */
  str: (x, d) => (typeof x === 'string' ? x : d),
  /** @template {string} E @param {any} x @param {readonly E[]} allowed @param {E} d @returns {E} */
  oneOf: (x, allowed, d) => (allowed.includes(x) ? x : d),
  /** @param {any} x @param {number} min @param {number} max @param {number} d */
  int: (x, min, max, d) => (Number.isInteger(x) && x >= min && x <= max ? x : d),
  /** @param {any} x @returns {any[]} */
  arr: (x) => (Array.isArray(x) ? x : []),
  /** @param {any} x @returns {Record<string, any>} */
  obj: (x) => (x && typeof x === 'object' && !Array.isArray(x) ? x : {}),
};

module.exports = { JsonStore, v };
