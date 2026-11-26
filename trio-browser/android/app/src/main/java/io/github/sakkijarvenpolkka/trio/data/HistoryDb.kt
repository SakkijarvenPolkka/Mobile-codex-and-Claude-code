package io.github.sakkijarvenpolkka.trio.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.sakkijarvenpolkka.trio.core.Visit

data class HistoryEntry(val visitId: Long, val url: String, val title: String, val time: Long)

/**
 * Browsing history with the same schema as the desktop app (urls, visits). Text search uses LIKE
 * over a lower-cased title + URL column, which behaves the same on every SQLite build.
 */
class HistoryDb(context: Context, name: String? = "history.sqlite") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE urls (id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', visit_count INTEGER NOT NULL DEFAULT 0, typed_count INTEGER NOT NULL DEFAULT 0, last_visit INTEGER NOT NULL DEFAULT 0, search_text TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE visits (id INTEGER PRIMARY KEY, url_id INTEGER NOT NULL REFERENCES urls(id) ON DELETE CASCADE, time INTEGER NOT NULL, duration_ms INTEGER, transition TEXT NOT NULL, from_visit INTEGER)")
        db.execSQL("CREATE INDEX visits_time ON visits(time)")
        db.execSQL("CREATE INDEX visits_url ON visits(url_id)")
        db.execSQL("CREATE INDEX urls_last_visit ON urls(last_visit)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun searchText(title: String, url: String): String {
        val shown = runCatching { java.net.URLDecoder.decode(url.replace("+", "%2B"), "UTF-8") }.getOrDefault(url)
        return "$title\n$shown".lowercase()
    }

    fun addVisit(url: String, title: String, time: Long, transition: String, fromVisitId: Long?): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var urlId = db.rawQuery("SELECT id FROM urls WHERE url = ?", arrayOf(url)).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            if (urlId < 0) {
                urlId = db.insertOrThrow("urls", null, ContentValues().apply {
                    put("url", url)
                    put("title", title)
                    put("search_text", searchText(title, url))
                })
            } else if (title.isNotEmpty()) {
                db.update("urls", ContentValues().apply { put("title", title); put("search_text", searchText(title, url)) }, "id = ?", arrayOf(urlId.toString()))
            }
            if (transition != "reload" && transition != "redirect") {
                db.execSQL(
                    "UPDATE urls SET visit_count = visit_count + 1, typed_count = typed_count + ?, last_visit = MAX(last_visit, ?) WHERE id = ?",
                    arrayOf<Any>(if (transition == "typed") 1 else 0, time, urlId),
                )
            }
            val id = db.insertOrThrow("visits", null, ContentValues().apply {
                put("url_id", urlId)
                put("time", time)
                putNull("duration_ms")
                put("transition", transition)
                if (fromVisitId != null) put("from_visit", fromVisitId) else putNull("from_visit")
            })
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun setTitle(visitId: Long, title: String) {
        if (title.isEmpty()) return
        val db = writableDatabase
        val (urlId, url) = db.rawQuery("SELECT u.id, u.url FROM visits v JOIN urls u ON u.id = v.url_id WHERE v.id = ?", arrayOf(visitId.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else return
        }
        db.update("urls", ContentValues().apply { put("title", title); put("search_text", searchText(title, url)) }, "id = ?", arrayOf(urlId.toString()))
    }

    fun setDuration(visitId: Long, ms: Long) {
        writableDatabase.update("visits", ContentValues().apply { put("duration_ms", maxOf(0, ms)) }, "id = ?", arrayOf(visitId.toString()))
    }

    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun query(text: String, beforeTime: Long?, limit: Int): List<HistoryEntry> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        where.add("v.transition NOT IN ('redirect', 'reload')")
        for (t in text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)) {
            where.add("u.search_text LIKE ? ESCAPE '\\'")
            args.add("%${likeEscape(t)}%")
        }
        if (beforeTime != null) {
            where.add("v.time < ?")
            args.add(beforeTime.toString())
        }
        val sql = "SELECT v.id, u.url, u.title, v.time FROM visits v JOIN urls u ON u.id = v.url_id WHERE ${where.joinToString(" AND ")} ORDER BY v.time DESC, v.id DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))) }
        }
    }

    fun suggest(text: String, limit: Int): List<Pair<String, String>> {
        val terms = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)
        if (terms.isEmpty()) return emptyList()
        val where = terms.joinToString(" AND ") { "search_text LIKE ? ESCAPE '\\'" }
        val first = likeEscape(terms[0])
        val args = terms.map { "%${likeEscape(it)}%" } + listOf("https://$first%", "http://$first%", "https://www.$first%")
        val sql = "SELECT url, title FROM urls WHERE visit_count > 0 AND $where ORDER BY (url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\' OR url LIKE ? ESCAPE '\\') DESC, typed_count DESC, visit_count DESC, last_visit DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
    }

    fun allVisits(): List<Visit> = readableDatabase.rawQuery(
        "SELECT v.id, u.url, u.title, v.time, v.duration_ms, v.transition, v.from_visit FROM visits v JOIN urls u ON u.id = v.url_id ORDER BY v.time, v.id",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Visit(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getString(5), if (c.isNull(6)) null else c.getLong(6)))
            }
        }
    }

    fun deleteVisit(id: Long) {
        writableDatabase.delete("visits", "id = ?", arrayOf(id.toString()))
        cleanup()
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM visits")
        writableDatabase.execSQL("DELETE FROM urls")
    }

    private fun cleanup() {
        val db = writableDatabase
        db.execSQL("DELETE FROM urls WHERE id NOT IN (SELECT DISTINCT url_id FROM visits)")
        db.execSQL(
            "UPDATE urls SET visit_count = (SELECT COUNT(*) FROM visits v WHERE v.url_id = urls.id AND v.transition NOT IN ('redirect','reload')), " +
                "typed_count = (SELECT COUNT(*) FROM visits v WHERE v.url_id = urls.id AND v.transition = 'typed'), " +
                "last_visit = COALESCE((SELECT MAX(time) FROM visits v WHERE v.url_id = urls.id AND v.transition NOT IN ('redirect','reload')), 0)",
        )
    }
}
