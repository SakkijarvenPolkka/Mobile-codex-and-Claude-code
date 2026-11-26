'use strict';
/**
 * Browsing history in SQLite (node:sqlite, built into Electron's Node). Schema shared with the
 * Android app. Text search uses LIKE over a lower-cased title + URL column so that short Korean
 * substrings match the same way on every SQLite build.
 */

const { siteKeyForUrl } = require('./site');

/** @returns {typeof import('node:sqlite')} */
function loadSqlite() {
  const original = process.emitWarning;
  // node:sqlite is marked experimental; its one-time warning is noise for users.
  process.emitWarning = /** @type {any} */ (
    (/** @type {any} */ warning, /** @type {any[]} */ ...rest) => {
      const text = typeof warning === 'string' ? warning : warning && warning.message;
      if (typeof text === 'string' && text.includes('SQLite')) return;
      return /** @type {any} */ (original).call(process, warning, ...rest);
    }
  );
  try {
    return require('node:sqlite');
  } finally {
    process.emitWarning = original;
  }
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS urls (
  id INTEGER PRIMARY KEY,
  url TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL DEFAULT '',
  visit_count INTEGER NOT NULL DEFAULT 0,
  typed_count INTEGER NOT NULL DEFAULT 0,
  last_visit INTEGER NOT NULL DEFAULT 0,
  search_text TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS visits (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  url_id INTEGER NOT NULL REFERENCES urls(id) ON DELETE CASCADE,
  time INTEGER NOT NULL,
  duration_ms INTEGER,
  transition TEXT NOT NULL,
  from_visit INTEGER
);
CREATE TABLE IF NOT EXISTS favicons (
  host TEXT PRIMARY KEY,
  data_url TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS visits_time ON visits(time);
CREATE INDEX IF NOT EXISTS visits_url ON visits(url_id);
CREATE INDEX IF NOT EXISTS urls_last_visit ON urls(last_visit);
`;

/** @param {string} title @param {string} url */
function searchText(title, url) {
  let shown = url;
  try {
    shown = decodeURI(url);
  } catch {
    /* keep the encoded form */
  }
  return `${title}\n${shown}`.toLowerCase();
}

/** Escapes LIKE wildcards. @param {string} s */
function likeEscape(s) {
  return s.replace(/[\\%_]/g, (c) => '\\' + c);
}

/**
 * @typedef {import('../types/contract').Visit} Visit
 * @typedef {import('../types/contract').VisitTransition} VisitTransition
 * @typedef {import('../types/contract').HistoryEntry} HistoryEntry
 */

class HistoryDb {
  /** @param {string} file path, or ':memory:' */
  constructor(file) {
    const { DatabaseSync } = loadSqlite();
    this.db = new DatabaseSync(file);
    this.db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA synchronous = NORMAL;');
    this.db.exec(SCHEMA);
    this.db.exec('PRAGMA user_version = 1');
    const db = this.db;
    this.stmt = {
      findUrl: db.prepare('SELECT id FROM urls WHERE url = ?'),
      insertUrl: db.prepare('INSERT INTO urls (url, title, search_text) VALUES (?, ?, ?)'),
      bumpUrl: db.prepare('UPDATE urls SET visit_count = visit_count + 1, typed_count = typed_count + ?, last_visit = MAX(last_visit, ?) WHERE id = ?'),
      insertVisit: db.prepare('INSERT INTO visits (url_id, time, duration_ms, transition, from_visit) VALUES (?, ?, NULL, ?, ?)'),
      setTitle: db.prepare('UPDATE urls SET title = ?, search_text = ? WHERE id = ?'),
      visitUrl: db.prepare('SELECT u.url AS url FROM visits v JOIN urls u ON u.id = v.url_id WHERE v.id = ?'),
      urlOfVisit: db.prepare('SELECT u.id AS id, u.url AS url FROM visits v JOIN urls u ON u.id = v.url_id WHERE v.id = ?'),
      setDuration: db.prepare('UPDATE visits SET duration_ms = ? WHERE id = ?'),
      setFavicon: db.prepare('INSERT INTO favicons (host, data_url) VALUES (?, ?) ON CONFLICT(host) DO UPDATE SET data_url = excluded.data_url'),
      getFavicon: db.prepare('SELECT data_url FROM favicons WHERE host = ?'),
    };
  }

  /**
   * Records a visit and returns its id.
   * @param {{ url: string, title: string, time: number, transition: VisitTransition, fromVisitId: number | null }} visit
   */
  addVisit(visit) {
    this.db.exec('BEGIN');
    try {
      const row = /** @type {{ id: number } | undefined} */ (this.stmt.findUrl.get(visit.url));
      const urlId = row ? row.id : Number(this.stmt.insertUrl.run(visit.url, visit.title, searchText(visit.title, visit.url)).lastInsertRowid);
      const counted = visit.transition !== 'reload' && visit.transition !== 'redirect';
      if (counted) this.stmt.bumpUrl.run(visit.transition === 'typed' ? 1 : 0, visit.time, urlId);
      const id = Number(this.stmt.insertVisit.run(urlId, visit.time, visit.transition, visit.fromVisitId).lastInsertRowid);
      if (visit.title && row) this.stmt.setTitle.run(visit.title, searchText(visit.title, visit.url), urlId);
      this.db.exec('COMMIT');
      return id;
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
  }

  /**
   * Updates the title of a visit's URL; ignored unless the visit still belongs to `url`
   * (a deleted visit's id must never touch another page).
   * @param {number} visitId @param {string} title @param {string} url
   */
  setTitle(visitId, title, url) {
    const row = /** @type {{ id: number, url: string } | undefined} */ (this.stmt.urlOfVisit.get(visitId));
    if (row && title && row.url === url) this.stmt.setTitle.run(title, searchText(title, row.url), row.id);
  }

  /** @param {number} visitId @param {number} ms @param {string} url */
  setDuration(visitId, ms, url) {
    const row = /** @type {{ url: string } | undefined} */ (this.stmt.visitUrl.get(visitId));
    if (row && row.url === url) this.stmt.setDuration.run(Math.max(0, Math.round(ms)), visitId);
  }

  /** @param {string} host @param {string} dataUrl */
  setFavicon(host, dataUrl) {
    this.stmt.setFavicon.run(host, dataUrl);
  }

  /** @param {string} host @returns {string | null} */
  favicon(host) {
    const row = /** @type {{ data_url: string } | undefined} */ (this.stmt.getFavicon.get(host));
    return row ? row.data_url : null;
  }

  /** @param {string} url */
  faviconForUrl(url) {
    try {
      return this.favicon(new URL(url).host);
    } catch {
      return null;
    }
  }

  /**
   * History page rows, newest first.
   * @param {{ text: string, beforeTime: number | null, limit: number }} q
   * @returns {{ entries: HistoryEntry[], hasMore: boolean }}
   */
  query(q) {
    const limit = Math.max(1, Math.min(500, q.limit));
    const terms = q.text.toLowerCase().split(/\s+/).filter(Boolean).slice(0, 8);
    const where = ["v.transition NOT IN ('redirect', 'reload')"];
    /** @type {(string | number)[]} */
    const params = [];
    for (const t of terms) {
      where.push("u.search_text LIKE ? ESCAPE '\\'");
      params.push(`%${likeEscape(t)}%`);
    }
    if (q.beforeTime !== null) {
      where.push('v.time < ?');
      params.push(q.beforeTime);
    }
    const rows = /** @type {{ id: number, url: string, title: string, time: number }[]} */ (
      this.db
        .prepare(`SELECT v.id AS id, u.url AS url, u.title AS title, v.time AS time FROM visits v JOIN urls u ON u.id = v.url_id WHERE ${where.join(' AND ')} ORDER BY v.time DESC, v.id DESC LIMIT ?`)
        .all(...params, limit + 1)
    );
    const entries = rows.slice(0, limit).map((r) => {
      let host = '';
      try {
        host = new URL(r.url).host;
      } catch {
        /* keep empty */
      }
      return { visitId: r.id, url: r.url, title: r.title, time: r.time, host, faviconUrl: host ? this.favicon(host) : null };
    });
    return { entries, hasMore: rows.length > limit };
  }

  /**
   * Address-bar suggestions: URLs whose title or address contains every typed word, ranked by
   * prefix match on the address, typed count, visit count and recency.
   * @param {string} text
   * @param {number} limit
   * @returns {{ url: string, title: string }[]}
   */
  suggest(text, limit) {
    const terms = text.toLowerCase().split(/\s+/).filter(Boolean).slice(0, 6);
    if (!terms.length) return [];
    const first = likeEscape(terms[0]);
    const where = terms.map(() => "search_text LIKE ? ESCAPE '\\'");
    const params = terms.map((t) => `%${likeEscape(t)}%`);
    const rows = /** @type {{ url: string, title: string }[]} */ (
      this.db
        .prepare(
          `SELECT url, title FROM urls WHERE visit_count > 0 AND ${where.join(' AND ')}
           ORDER BY (url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\') DESC,
                    typed_count DESC, visit_count DESC, last_visit DESC LIMIT ?`,
        )
        .all(...params, `https://${first}%`, `http://${first}%`, `https://www.${first}%`, limit)
    );
    return rows;
  }

  /**
   * Most visited sites of the last 30 days for the new-tab page, one URL per site.
   * @param {number} now
   * @param {number} limit
   * @returns {{ url: string, title: string, host: string, faviconUrl: string | null }[]}
   */
  topSites(now, limit) {
    const rows = /** @type {{ url: string, title: string, n: number }[]} */ (
      this.db
        .prepare(
          `SELECT u.url AS url, u.title AS title, COUNT(*) AS n FROM visits v JOIN urls u ON u.id = v.url_id
           WHERE v.time >= ? AND v.transition NOT IN ('redirect', 'reload', 'search') AND u.url LIKE 'http%'
           GROUP BY u.id ORDER BY n DESC, MAX(v.time) DESC LIMIT 200`,
        )
        .all(now - 30 * 24 * 3600 * 1000)
    );
    /** @type {Map<string, { url: string, title: string, host: string, faviconUrl: string | null }>} */
    const bySite = new Map();
    for (const r of rows) {
      const site = siteKeyForUrl(r.url);
      if (!site || bySite.has(site)) continue;
      let origin = r.url;
      let host = '';
      try {
        const u = new URL(r.url);
        origin = u.origin + '/';
        host = u.host;
      } catch {
        continue;
      }
      bySite.set(site, { url: origin, title: r.title || host, host, faviconUrl: this.favicon(host) });
      if (bySite.size >= limit) break;
    }
    return [...bySite.values()];
  }

  /**
   * Every visit (optionally since a time) in the shape the statistics expect.
   * @param {number} [since]
   * @returns {Visit[]}
   */
  allVisits(since = 0) {
    return /** @type {any[]} */ (
      this.db
        .prepare(
          `SELECT v.id AS id, u.url AS url, u.title AS title, v.time AS time, v.duration_ms AS durationMs, v.transition AS transition, v.from_visit AS fromVisitId
           FROM visits v JOIN urls u ON u.id = v.url_id WHERE v.time >= ? ORDER BY v.time, v.id`,
        )
        .all(since)
    );
  }

  /** @param {number[]} ids */
  deleteVisits(ids) {
    if (!ids.length) return;
    this.db.exec('BEGIN');
    try {
      const del = this.db.prepare('DELETE FROM visits WHERE id = ?');
      for (const id of ids) del.run(id);
      this.cleanupUrls();
      this.db.exec('COMMIT');
    } catch (err) {
      this.db.exec('ROLLBACK');
      throw err;
    }
  }

  /** @param {number} from inclusive epoch ms */
  deleteSince(from) {
    this.db.prepare('DELETE FROM visits WHERE time >= ?').run(from);
    this.cleanupUrls();
  }

  deleteAll() {
    this.db.exec('DELETE FROM visits; DELETE FROM urls; DELETE FROM favicons;');
  }

  /** Recomputes URL counters after deletions and drops URLs with no visits left. */
  cleanupUrls() {
    this.db.exec(`
      DELETE FROM urls WHERE id NOT IN (SELECT DISTINCT url_id FROM visits);
      UPDATE urls SET
        visit_count = (SELECT COUNT(*) FROM visits v WHERE v.url_id = urls.id AND v.transition NOT IN ('redirect', 'reload')),
        typed_count = (SELECT COUNT(*) FROM visits v WHERE v.url_id = urls.id AND v.transition = 'typed'),
        last_visit = COALESCE((SELECT MAX(time) FROM visits v WHERE v.url_id = urls.id AND v.transition NOT IN ('redirect', 'reload')), 0);
    `);
  }

  close() {
    this.db.close();
  }
}

module.exports = { HistoryDb, searchText, likeEscape };
