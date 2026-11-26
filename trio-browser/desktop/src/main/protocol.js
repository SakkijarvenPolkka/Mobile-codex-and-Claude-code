'use strict';
/**
 * The trio:// scheme for the browser's own UI and internal pages. Files are served from
 * src/renderer with a strict Content-Security-Policy; paths cannot escape the renderer directory.
 */
const fs = require('node:fs');
const path = require('node:path');
const { protocol } = require('electron');
const { rendererDir } = require('./paths');

const SCHEME = 'trio';
const SURFACE_HOSTS = new Set(['chrome', 'agent', 'dock']);
const PAGE_HOSTS = new Set(['newtab', 'history', 'bookmarks', 'downloads', 'settings', 'about', 'error']);
const CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function registerScheme() {
  protocol.registerSchemesAsPrivileged([
    { scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: false, corsEnabled: false, stream: false, codeCache: true } },
  ]);
}

/**
 * Maps a trio:// URL to a file inside the renderer directory, or null.
 * @param {string} url
 */
function resolveFile(url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return null;
  }
  const host = u.hostname;
  let base;
  if (SURFACE_HOSTS.has(host)) base = path.join(rendererDir(), host);
  else if (PAGE_HOSTS.has(host)) base = path.join(rendererDir(), 'pages', host);
  else return null;
  let rel = decodeURIComponent(u.pathname);
  const common = /^\/(?:.*\/)?common\/(.*)$/.exec(rel);
  if (common) {
    base = path.join(rendererDir(), 'common');
    rel = '/' + common[1];
  }
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(base, rel));
  if (!file.startsWith(base + path.sep)) return null;
  return file;
}

/** @param {Electron.Session} ses */
function handleScheme(ses) {
  if (ses.protocol.isProtocolHandled(SCHEME)) return;
  ses.protocol.handle(SCHEME, async (request) => {
    const file = resolveFile(request.url);
    if (!file) return new Response('Not found', { status: 404, headers: { 'content-type': 'text/plain' } });
    try {
      const body = await fs.promises.readFile(file);
      const type = /** @type {Record<string, string>} */ (MIME)[path.extname(file)] || 'application/octet-stream';
      /** @type {Record<string, string>} */
      const headers = { 'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' };
      if (type.startsWith('text/html')) headers['content-security-policy'] = CSP;
      return new Response(body, { status: 200, headers });
    } catch {
      return new Response('Not found', { status: 404, headers: { 'content-type': 'text/plain' } });
    }
  });
}

/** @param {string} url */
const isInternalUrl = (url) => url.startsWith(SCHEME + '://');
/** @param {string} url @returns {string} host of a trio:// URL or '' */
function internalHost(url) {
  try {
    const u = new URL(url);
    return u.protocol === SCHEME + ':' ? u.hostname : '';
  } catch {
    return '';
  }
}

module.exports = { SCHEME, PAGE_HOSTS, SURFACE_HOSTS, CSP, registerScheme, handleScheme, resolveFile, isInternalUrl, internalHost };
