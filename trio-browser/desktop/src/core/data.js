'use strict';
/**
 * Loads the shared data files (trio-browser/shared) once. The directory is found next to the
 * desktop app in development and under the app's resources when packaged.
 */

const fs = require('node:fs');
const path = require('node:path');
const { loadSuffixData } = require('./url');

/** @returns {string} */
function sharedDir() {
  if (process.env.TRIO_SHARED_DIR) return process.env.TRIO_SHARED_DIR;
  const candidates = [path.join(__dirname, '..', '..', '..', 'shared')];
  const resources = /** @type {any} */ (process).resourcesPath;
  if (typeof resources === 'string') candidates.unshift(path.join(resources, 'shared'));
  for (const c of candidates) if (fs.existsSync(path.join(c, 'providers.json'))) return c;
  throw new Error('Trio shared data directory not found');
}

/** @type {Map<string, any>} */
const cache = new Map();

/**
 * @param {string} name file name inside the shared directory
 * @returns {any}
 */
function readShared(name) {
  if (!cache.has(name)) cache.set(name, JSON.parse(fs.readFileSync(path.join(sharedDir(), name), 'utf8')));
  return cache.get(name);
}

/** @returns {import('../types/contract').ProviderConfig[]} */
function providers() {
  const file = process.env.TRIO_PROVIDERS_FILE;
  if (file) {
    const key = 'override:' + file;
    if (!cache.has(key)) cache.set(key, JSON.parse(fs.readFileSync(file, 'utf8')));
    return cache.get(key).providers;
  }
  return readShared('providers.json').providers;
}

let suffixes = /** @type {import('./url').SuffixData | null} */ (null);
function suffixData() {
  if (!suffixes) suffixes = loadSuffixData(readShared('site-suffixes.json'));
  return suffixes;
}

module.exports = {
  sharedDir,
  readShared,
  providers,
  suffixData,
  searchEngines: () => readShared('search-engines.json'),
  privacyRules: () => readShared('privacy-rules.json'),
  prompts: () => readShared('prompts.json'),
  injectScript: (/** @type {string} */ name) => fs.readFileSync(path.join(sharedDir(), 'inject', name), 'utf8'),
};
