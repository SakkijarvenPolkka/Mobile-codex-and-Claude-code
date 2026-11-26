'use strict';
/**
 * IPC dispatch with sender checks. A handler runs only when the call comes from the top frame of
 * a web contents registered for that surface and the frame is on the surface's trio:// origin.
 */
const { ipcMain } = require('electron');
const { SURFACES, methodChannel, eventChannel } = require('./surfaces');
const { PAGE_HOSTS } = require('./protocol');

/**
 * @typedef {'chrome' | 'agent' | 'dock' | 'pages'} SurfaceName
 * @typedef {(ctx: any, ...args: any[]) => any} Handler
 */

/** @type {Map<number, { surface: SurfaceName, ctx: any }>} */
const registry = new Map();

/**
 * Associates a web contents with a surface and a context object passed to handlers
 * (the browser window, agent window, …). Pages are registered per tab.
 * @param {Electron.WebContents} wc
 * @param {SurfaceName} surface
 * @param {any} ctx
 */
function registerSurface(wc, surface, ctx) {
  registry.set(wc.id, { surface, ctx });
  wc.once('destroyed', () => registry.delete(wc.id));
}

/** @param {Electron.WebContents} wc */
function unregisterSurface(wc) {
  registry.delete(wc.id);
}

/** @param {SurfaceName} surface @param {string} url */
function originAllowed(surface, url) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return false;
  }
  if (u.protocol !== 'trio:') return false;
  if (surface === 'pages') return PAGE_HOSTS.has(u.hostname);
  return u.hostname === surface;
}

/**
 * Installs handlers for one surface. `handlers` maps method names to functions receiving the
 * registered context first.
 * @param {SurfaceName} surface
 * @param {Record<string, Handler>} handlers
 */
function handleSurface(surface, handlers) {
  for (const method of SURFACES[surface].methods) {
    const fn = handlers[method];
    if (typeof fn !== 'function') throw new Error(`No handler for ${surface}.${method}`);
    ipcMain.handle(methodChannel(surface, method), async (event, ...args) => {
      const entry = registry.get(event.sender.id);
      const frame = event.senderFrame;
      if (!entry || entry.surface !== surface || !frame || frame !== event.sender.mainFrame || !originAllowed(surface, frame.url)) {
        throw new Error('Rejected IPC call');
      }
      return fn(entry.ctx, ...args);
    });
  }
}

/**
 * Sends an event to a surface's web contents.
 * @param {Electron.WebContents} wc
 * @param {SurfaceName} surface
 * @param {string} name
 * @param {unknown} payload
 */
function emit(wc, surface, name, payload) {
  if (!wc.isDestroyed()) wc.send(eventChannel(surface, name), payload);
}

/** All registered web contents for a surface (for broadcasts). */
function contentsOf(/** @type {SurfaceName} */ surface) {
  const { webContents } = require('electron');
  return [...registry.entries()].flatMap(([id, e]) => {
    const wc = e.surface === surface ? webContents.fromId(id) : undefined;
    return wc && !wc.isDestroyed() ? [wc] : [];
  });
}

module.exports = { registerSurface, unregisterSurface, handleSurface, emit, contentsOf, originAllowed };
