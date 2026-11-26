'use strict';
/**
 * Real popup windows (window.open with features, used by sign-in flows that need their opener).
 * They are sandboxed, can never reach trio:// pages, open their own new windows as tabs, and
 * always show the page's origin in their title bar so a popup cannot pose as another site.
 */

const { isInternalUrl } = require('./protocol');

/** @param {Electron.Session} [session] @returns {Electron.BrowserWindowConstructorOptions} */
function popupOptions(session) {
  return {
    width: 520,
    height: 680,
    autoHideMenuBar: true,
    title: '',
    webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, webviewTag: false, ...(session ? { session } : {}) },
  };
}

/** @param {string} url */
function originLabel(url) {
  try {
    const u = new URL(url);
    if (u.protocol === 'about:') return 'about:blank';
    return u.protocol === 'https:' ? u.host : `${u.protocol}//${u.host}`;
  } catch {
    return url;
  }
}

/**
 * @param {Electron.BrowserWindow} win
 * @param {(url: string) => void} openTab where the popup's own new windows go
 */
function guardPopup(win, openTab) {
  const wc = win.webContents;
  const label = () => {
    if (win.isDestroyed() || wc.isDestroyed()) return;
    const title = wc.getTitle();
    const origin = originLabel(wc.getURL());
    win.setTitle(title && title !== wc.getURL() ? `${origin} — ${title}` : origin);
  };
  wc.on('will-navigate', (event, url) => {
    if (isInternalUrl(url)) event.preventDefault();
  });
  wc.on('will-redirect', (event) => {
    if (isInternalUrl(event.url)) event.preventDefault();
  });
  wc.on('will-frame-navigate', (event) => {
    if (isInternalUrl(event.url)) event.preventDefault();
  });
  wc.on('will-attach-webview', (event) => event.preventDefault());
  wc.setWindowOpenHandler((details) => {
    if (!isInternalUrl(details.url) && /^https?:/.test(details.url)) openTab(details.url);
    return { action: 'deny' };
  });
  wc.on('page-title-updated', (event) => {
    event.preventDefault();
    label();
  });
  wc.on('did-navigate', label);
  wc.on('did-navigate-in-page', label);
  label();
}

module.exports = { popupOptions, guardPopup, originLabel };
