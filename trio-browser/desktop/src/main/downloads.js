'use strict';
/** Downloads: tracks DownloadItems, keeps a persisted list (downloads.json) and exposes actions. */
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { app, shell } = require('electron');
const { JsonStore, v } = require('./json-store');

/** @typedef {import('../types/contract').DownloadItem} DownloadItem */

const STATES = ['progressing', 'paused', 'completed', 'cancelled', 'interrupted'];

/** Picks "name (1).ext" style names that do not exist yet. @param {string} dir @param {string} name */
function uniquePath(dir, name) {
  const safe = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^\.+/, '_').slice(0, 200) || 'download';
  const ext = path.extname(safe);
  const stem = safe.slice(0, safe.length - ext.length);
  let candidate = path.join(dir, safe);
  for (let i = 1; fs.existsSync(candidate); i++) candidate = path.join(dir, `${stem} (${i})${ext}`);
  return candidate;
}

class DownloadManager extends EventEmitter {
  /**
   * @param {string} file
   * @param {() => import('../types/contract').Settings} settings
   */
  constructor(file, settings) {
    super();
    this.settings = settings;
    /** @type {Map<string, Electron.DownloadItem>} */
    this.live = new Map();
    /** @type {Set<string>} */
    this.unseen = new Set();
    /** Downloads from private windows: memory only, listed only in private windows. */
    /** @type {Map<string, { session: Electron.Session, item: DownloadItem }>} */
    this.privateItems = new Map();
    this.store = new JsonStore(
      file,
      () => /** @type {DownloadItem[]} */ ([]),
      (raw) =>
        v
          .arr(raw)
          .filter((d) => d && typeof d.id === 'string' && typeof d.path === 'string')
          .map((d) => ({
            id: d.id,
            url: v.str(d.url, ''),
            filename: v.str(d.filename, path.basename(d.path)),
            path: d.path,
            state: /** @type {DownloadItem['state']} */ (d.state === 'progressing' || d.state === 'paused' ? 'interrupted' : v.oneOf(d.state, STATES, 'interrupted')),
            receivedBytes: Number(d.receivedBytes) || 0,
            totalBytes: Number(d.totalBytes) || 0,
            startTime: Number(d.startTime) || 0,
            exists: false,
          }))
          .slice(0, 500),
    );
  }

  /** @param {Electron.Session} ses @param {boolean} [isPrivate] */
  attach(ses, isPrivate = false) {
    ses.on('will-download', (_event, item) => this.track(item, isPrivate ? ses : null));
  }

  /**
   * @param {Electron.DownloadItem} item
   * @param {Electron.Session | null} privateSession
   */
  track(item, privateSession) {
    const id = crypto.randomUUID();
    const s = this.settings().downloads;
    if (!s.askWhere) {
      const dir = s.directory && fs.existsSync(s.directory) ? s.directory : app.getPath('downloads');
      item.setSavePath(uniquePath(dir, item.getFilename()));
    }
    this.live.set(id, item);
    const entry = () => this.snapshot(id, item);
    if (privateSession) {
      const update = () => {
        this.privateItems.set(id, { session: privateSession, item: entry() });
        this.emit('change');
      };
      update();
      item.on('updated', update);
      item.once('done', () => {
        this.live.delete(id);
        update();
      });
      return;
    }
    this.upsert(entry());
    item.on('updated', () => this.upsert(entry()));
    item.once('done', (_e, state) => {
      this.live.delete(id);
      if (state === 'completed') this.unseen.add(id);
      this.upsert(entry());
    });
  }

  /** @param {string} id @param {Electron.DownloadItem} item @returns {DownloadItem} */
  snapshot(id, item) {
    const raw = item.getState();
    const state = raw === 'progressing' && item.isPaused() ? 'paused' : /** @type {DownloadItem['state']} */ (raw);
    const savePath = item.getSavePath();
    return {
      id,
      url: item.getURL(),
      filename: savePath ? path.basename(savePath) : item.getFilename(),
      path: savePath,
      state,
      receivedBytes: item.getReceivedBytes(),
      totalBytes: item.getTotalBytes(),
      startTime: Math.round(item.getStartTime() * 1000),
      exists: state === 'completed',
    };
  }

  /** @param {DownloadItem} d */
  upsert(d) {
    const list = this.store.get().filter((x) => x.id !== d.id);
    this.store.set([d, ...list]);
    this.emit('change');
  }

  /**
   * @param {Electron.Session | null} [privateSession] a private window's session: list its downloads instead
   * @returns {DownloadItem[]} newest first, with `exists` checked for finished files
   */
  list(privateSession = null) {
    const items = privateSession
      ? [...this.privateItems.values()].filter((p) => p.session === privateSession).map((p) => p.item).reverse()
      : this.store.get();
    return items.map((d) => (d.state === 'completed' ? { ...d, exists: fs.existsSync(d.path) } : d));
  }

  /** Forgets a closed private window's downloads. @param {Electron.Session} session */
  dropPrivate(session) {
    for (const [id, p] of this.privateItems) if (p.session === session) this.privateItems.delete(id);
  }

  summary() {
    let received = 0;
    let total = 0;
    let unknown = false;
    for (const item of this.live.values()) {
      received += item.getReceivedBytes();
      const t = item.getTotalBytes();
      if (t > 0) total += t;
      else unknown = true;
    }
    return { activeCount: this.live.size, progress: this.live.size && !unknown && total ? Math.min(1, received / total) : null, unseen: this.unseen.size > 0 };
  }

  markSeen() {
    if (this.unseen.size) {
      this.unseen.clear();
      this.emit('change');
    }
  }

  /**
   * @param {string} id
   * @param {'open' | 'show' | 'cancel' | 'pause' | 'resume' | 'remove'} action
   */
  async act(id, action) {
    const d = this.store.get().find((x) => x.id === id) || this.privateItems.get(id)?.item;
    const item = this.live.get(id);
    switch (action) {
      case 'open':
        if (d && d.state === 'completed' && fs.existsSync(d.path)) await shell.openPath(d.path);
        break;
      case 'show':
        if (d && fs.existsSync(d.path)) shell.showItemInFolder(d.path);
        break;
      case 'cancel':
        item?.cancel();
        break;
      case 'pause':
        item?.pause();
        break;
      case 'resume':
        if (item?.canResume()) item.resume();
        break;
      case 'remove':
        item?.cancel();
        this.privateItems.delete(id);
        this.store.set(this.store.get().filter((x) => x.id !== id));
        this.unseen.delete(id);
        this.emit('change');
        break;
    }
  }

  clearFinished() {
    this.store.set(this.store.get().filter((d) => this.live.has(d.id)));
    this.unseen.clear();
    this.emit('change');
  }

  flush() {
    this.store.flush();
  }
}

module.exports = { DownloadManager, uniquePath };
