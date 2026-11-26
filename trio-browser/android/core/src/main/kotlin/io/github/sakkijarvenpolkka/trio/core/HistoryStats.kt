package io.github.sakkijarvenpolkka.trio.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One recorded visit (same fields as the desktop Visit type). */
data class Visit(
    val id: Long,
    val url: String,
    val title: String,
    val time: Long,
    val durationMs: Long?,
    val transition: String,
    val fromVisitId: Long?,
)

data class StatsOptions(
    val now: Long,
    val timeZone: String,
    val range: String,
    val excludeSensitive: Boolean,
    val extraExcludedHosts: List<String>,
    val sessionGapMinutes: Int = 30,
)

data class SiteStat(val site: String, val visits: Int, val durationMs: Long)
data class PageStat(val title: String, val url: String, val site: String, val visits: Int, val durationMs: Long)
data class RecentPage(val title: String, val url: String, val site: String, val time: Long)
data class Totals(val visits: Int, val sites: Int, val pages: Int, val searches: Int, val activeDays: Int, val durationMs: Long, val excluded: Int)
data class Sessions(val count: Int, val averageMinutes: Long, val longestMinutes: Long)

data class HistoryStats(
    val range: String,
    val timeZone: String,
    val from: Long,
    val to: Long,
    val totals: Totals,
    val topSitesByVisits: List<SiteStat>,
    val topSitesByTime: List<SiteStat>,
    val hours: List<Int>,
    val weekdays: List<Int>,
    val sessions: Sessions,
    val topQueries: List<Pair<String, Int>>,
    val topTerms: List<Pair<String, Int>>,
    val newSites: Int?,
    val returningSites: Int?,
    val notablePages: List<PageStat>,
    val recentPages: List<RecentPage>,
) {
    /** The same JSON shape the desktop implementation produces (for golden comparisons). */
    fun toJson(): JsonObject {
        fun n(v: Number?) = if (v == null) JsonNull else JsonPrimitive(v)
        fun site(s: SiteStat) = JsonObject(mapOf("site" to JsonPrimitive(s.site), "visits" to n(s.visits), "durationMs" to n(s.durationMs)))
        return JsonObject(
            mapOf(
                "range" to JsonPrimitive(range),
                "timeZone" to JsonPrimitive(timeZone),
                "from" to n(from),
                "to" to n(to),
                "totals" to JsonObject(
                    mapOf(
                        "visits" to n(totals.visits), "sites" to n(totals.sites), "pages" to n(totals.pages), "searches" to n(totals.searches),
                        "activeDays" to n(totals.activeDays), "durationMs" to n(totals.durationMs), "excluded" to n(totals.excluded),
                    ),
                ),
                "topSitesByVisits" to JsonArray(topSitesByVisits.map(::site)),
                "topSitesByTime" to JsonArray(topSitesByTime.map(::site)),
                "hours" to JsonArray(hours.map { n(it) }),
                "weekdays" to JsonArray(weekdays.map { n(it) }),
                "sessions" to JsonObject(mapOf("count" to n(sessions.count), "averageMinutes" to n(sessions.averageMinutes), "longestMinutes" to n(sessions.longestMinutes))),
                "topQueries" to JsonArray(topQueries.map { JsonObject(mapOf("query" to JsonPrimitive(it.first), "count" to n(it.second))) }),
                "topTerms" to JsonArray(topTerms.map { JsonObject(mapOf("term" to JsonPrimitive(it.first), "count" to n(it.second))) }),
                "newSites" to n(newSites),
                "returningSites" to n(returningSites),
                "notablePages" to JsonArray(notablePages.map { JsonObject(mapOf("title" to JsonPrimitive(it.title), "url" to JsonPrimitive(it.url), "site" to JsonPrimitive(it.site), "visits" to n(it.visits), "durationMs" to n(it.durationMs))) }),
                "recentPages" to JsonArray(recentPages.map { JsonObject(mapOf("title" to JsonPrimitive(it.title), "url" to JsonPrimitive(it.url), "site" to JsonPrimitive(it.site), "time" to n(it.time))) }),
            ),
        )
    }
}

/** Mirrors desktop/src/core/history-stats.js; see the definitions documented there. */
object HistoryStatsCalc {
    private const val DAY = 24L * 60 * 60 * 1000
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "what", "how", "why", "who", "when", "where", "is", "are", "to", "of", "in", "on", "a", "an", "vs",
        "or", "by", "at", "it", "be", "do", "does", "can", "my", "me", "from",
        "방법", "하는", "하기", "그리고", "또는", "이란", "뜻", "무엇", "어떻게", "왜", "추천", "정리",
    )
    private val SPLIT = Regex("[$SPACE\\p{P}\\p{S}]+")
    private val WS = Regex("[$SPACE]+")
    private val DIGITS = Regex("^[0-9]+$")

    fun rangeStart(range: String, now: Long, zone: String): Long? = when (range) {
        "today" -> LocalTime.startOfLocalDay(now, zone)
        "7d" -> now - 7 * DAY
        "30d" -> now - 30 * DAY
        "90d" -> now - 90 * DAY
        else -> null
    }

    fun normalizeQuery(q: String): String = q.replace(WS, " ").trim().lowercase()

    fun queryTerms(q: String): List<String> = q.split(SPLIT).filter { t -> t.isNotEmpty() && t.codePointCount(0, t.length) >= 2 && !DIGITS.matches(t) && t !in STOPWORDS }

    private class Kept(val v: Visit, val site: String, val page: String, val query: String?)

    fun compute(visits: List<Visit>, o: StatsOptions, data: SharedData): HistoryStats {
        val gap = o.sessionGapMinutes * 60_000L
        val start = rangeStart(o.range, o.now, o.timeZone)
        val firstSeen = HashMap<String, Long>()
        val kept = ArrayList<Kept>()
        var excluded = 0
        val userOnly = PrivacyRules(emptyList(), data.privacyRules.localHostSuffixes, data.privacyRules.tokenMinLength, data.privacyRules.hexMinLength)
        for (v in visits) {
            val u = WebUrl.parse(v.url) ?: continue
            val site = Hosts.siteKey(u.hostname, data.suffixes)
            val seen = firstSeen[site]
            if (seen == null || v.time < seen) firstSeen[site] = v.time
            if (v.transition == "reload" || v.transition == "redirect") continue
            if ((start != null && v.time < start) || v.time > o.now) continue
            val userExcluded = Privacy.sensitiveCategory(v.url, userOnly, o.extraExcludedHosts) == "user"
            if (userExcluded || (o.excludeSensitive && Privacy.sensitiveCategory(v.url, data.privacyRules) != null)) {
                excluded++
                continue
            }
            val page = Privacy.sanitizeUrl(v.url, data.privacyRules, data.searchData) ?: continue
            val s = data.searchData.extractSearch(v.url)
            kept.add(Kept(v, site, page, s?.let { normalizeQuery(it.query) }))
        }
        kept.sortWith(compareBy<Kept> { it.v.time }.thenBy { it.v.id })

        val hours = IntArray(24)
        val weekdays = IntArray(7)
        val days = HashSet<String>()
        class SiteAcc(var visits: Int = 0, var duration: Long = 0)
        class PageAcc(val site: String, var title: String = "", var visits: Int = 0, var duration: Long = 0, var lastTime: Long = 0)
        class QueryAcc(var count: Int = 0, var lastTime: Long = 0)
        val sites = LinkedHashMap<String, SiteAcc>()
        val pages = LinkedHashMap<String, PageAcc>()
        val queries = LinkedHashMap<String, QueryAcc>()
        val terms = LinkedHashMap<String, Int>()
        var totalDuration = 0L
        var searches = 0
        for (k in kept) {
            val p = LocalTime.parts(k.v.time, o.timeZone)
            hours[p.hour]++
            weekdays[p.weekday]++
            days.add(LocalTime.date(k.v.time, o.timeZone))
            val dur = k.v.durationMs?.takeIf { it > 0 } ?: 0L
            totalDuration += dur
            sites.getOrPut(k.site) { SiteAcc() }.apply { this.visits++; this.duration += dur }
            val pg = pages.getOrPut(k.page) { PageAcc(k.site) }
            pg.visits++
            pg.duration += dur
            val title = k.v.title.trim()
            if (k.v.time >= pg.lastTime) {
                pg.lastTime = k.v.time
                if (title.isNotEmpty()) pg.title = title
            } else if (pg.title.isEmpty() && title.isNotEmpty()) pg.title = title
            if (k.query != null) {
                searches++
                val q = queries.getOrPut(k.query) { QueryAcc() }
                q.count++
                q.lastTime = maxOf(q.lastTime, k.v.time)
                for (t in queryTerms(k.query)) terms[t] = (terms[t] ?: 0) + 1
            }
        }

        var sessionCount = 0
        var sessionTotal = 0L
        var sessionLongest = 0L
        var i = 0
        while (i < kept.size) {
            var j = i
            while (j + 1 < kept.size && kept[j + 1].v.time - kept[j].v.time <= gap) j++
            val last = kept[j].v
            val length = last.time + (last.durationMs?.takeIf { it > 0 } ?: 0L) - kept[i].v.time
            sessionCount++
            sessionTotal += length
            sessionLongest = maxOf(sessionLongest, length)
            i = j + 1
        }

        val siteList = sites.map { (s, a) -> SiteStat(s, a.visits, a.duration) }
        val byVisits = siteList.sortedWith(compareByDescending<SiteStat> { it.visits }.thenByDescending { it.durationMs }.thenBy { it.site }).take(10)
        val byTime = siteList.filter { it.durationMs > 0 }.sortedWith(compareByDescending<SiteStat> { it.durationMs }.thenByDescending { it.visits }.thenBy { it.site }).take(10)
        val pageList = pages.map { (url, a) -> url to a }
        val notable = pageList.sortedWith(
            compareByDescending<Pair<String, PageAcc>> { it.second.visits }.thenByDescending { it.second.duration }.thenByDescending { it.second.lastTime }.thenBy { it.first },
        ).take(15).map { (url, a) -> PageStat(a.title, url, a.site, a.visits, a.duration) }
        val recent = pageList.sortedWith(compareByDescending<Pair<String, PageAcc>> { it.second.lastTime }.thenBy { it.first })
            .take(40).map { (url, a) -> RecentPage(a.title, url, a.site, a.lastTime) }
        val topQueries = queries.entries.sortedWith(compareByDescending<Map.Entry<String, QueryAcc>> { it.value.count }.thenByDescending { it.value.lastTime }.thenBy { it.key })
            .take(15).map { it.key to it.value.count }
        val topTerms = terms.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(20).map { it.key to it.value }

        var newSites: Int? = null
        var returning: Int? = null
        if (start != null) {
            newSites = sites.keys.count { (firstSeen[it] ?: 0) >= start }
            returning = sites.size - newSites
        }
        return HistoryStats(
            range = o.range,
            timeZone = o.timeZone,
            from = start ?: (kept.firstOrNull()?.v?.time ?: o.now),
            to = o.now,
            totals = Totals(kept.size, sites.size, pages.size, searches, days.size, totalDuration, excluded),
            topSitesByVisits = byVisits,
            topSitesByTime = byTime,
            hours = hours.toList(),
            weekdays = weekdays.toList(),
            sessions = Sessions(
                sessionCount,
                if (sessionCount > 0) Math.round(sessionTotal.toDouble() / sessionCount / 60000) else 0,
                Math.round(sessionLongest.toDouble() / 60000),
            ),
            topQueries = topQueries,
            topTerms = topTerms,
            newSites = newSites,
            returningSites = returning,
            notablePages = notable,
            recentPages = recent,
        )
    }

    /** Parses a visit from JSON (test vectors). */
    fun visitFromJson(e: JsonElement): Visit {
        val o = e.obj()
        fun long(k: String): Long? = (o[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()?.toLong()
        return Visit(long("id") ?: 0, o["url"].strOr(""), o["title"].strOr(""), long("time") ?: 0, long("durationMs"), o["transition"].strOr("link"), long("fromVisitId"))
    }
}
