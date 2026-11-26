package io.github.sakkijarvenpolkka.trio.core

/** Mirrors desktop/src/core/history-digest.js (the text must match it exactly). */
object HistoryDigest {
    data class Include(val queries: Boolean, val titles: Boolean, val urls: String)
    data class Options(val locale: String, val include: Include, val kind: String, val charLimit: Int)
    data class Result(val text: String, val truncated: Boolean)

    fun formatInt(n: Long): String {
        val s = Math.round(Math.abs(n.toDouble())).toString()
        val out = s.replace(Regex("\\B(?=(\\d{3})+(?!\\d))"), ",")
        return if (n < 0) "-$out" else out
    }

    fun formatDuration(ms: Long, locale: String): String {
        val minutes = Math.round(ms.toDouble() / 60000)
        if (minutes < 1) return if (locale == "ko") "1분 미만" else "<1 min"
        val h = minutes / 60
        val m = minutes % 60
        return if (locale == "ko") {
            if (h > 0) (if (m > 0) "${h}시간 ${m}분" else "${h}시간") else "${m}분"
        } else {
            if (h > 0) (if (m > 0) "$h h $m min" else "$h h") else "$m min"
        }
    }

    private val RANGE_KO = mapOf("today" to "오늘", "7d" to "최근 7일", "30d" to "최근 30일", "90d" to "최근 90일", "all" to "전체 기간")
    private val RANGE_EN = mapOf("today" to "today", "7d" to "last 7 days", "30d" to "last 30 days", "90d" to "last 90 days", "all" to "all time")
    private val WEEK_KO = listOf("월", "화", "수", "목", "금", "토", "일")
    private val WEEK_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun render(stats: HistoryStats, o: Options): Result {
        val ko = o.locale == "ko"
        val tz = stats.timeZone
        val t = stats.totals
        val sections = ArrayList<List<String>>()
        val rangeLabel = (if (ko) RANGE_KO else RANGE_EN)[stats.range] ?: stats.range
        val a = LocalTime.date(stats.from, tz)
        val b = LocalTime.date(stats.to, tz)
        val period = if (ko) "기간: $a ~ $b ($rangeLabel, 시간대 $tz)" else "Period: $a to $b ($rangeLabel, time zone $tz)"
        val totals = if (ko) {
            "방문 ${formatInt(t.visits.toLong())}건 · 사이트 ${formatInt(t.sites.toLong())}곳 · 페이지 ${formatInt(t.pages.toLong())}개 · 검색 ${formatInt(t.searches.toLong())}건 · 활동한 날 ${formatInt(t.activeDays.toLong())}일" +
                (if (t.excluded > 0) " (민감한 사이트 방문 ${formatInt(t.excluded.toLong())}건 제외)" else "")
        } else {
            "${formatInt(t.visits.toLong())} visits · ${formatInt(t.sites.toLong())} sites · ${formatInt(t.pages.toLong())} pages · ${formatInt(t.searches.toLong())} searches · ${formatInt(t.activeDays.toLong())} active days" +
                (if (t.excluded > 0) " (${formatInt(t.excluded.toLong())} visits to sensitive sites removed)" else "")
        }
        sections.add(listOf(period, totals))
        val marker = if (ko) "… (글자 수 제한으로 나머지는 생략했습니다)" else "… (the rest was left out to stay within the length limit)"
        if (t.visits == 0) {
            sections.add(listOf(if (ko) "이 기간에는 분석할 방문 기록이 없습니다." else "There is no browsing history to analyze for this period."))
            return assemble(sections, o.charLimit, marker)
        }
        fun visitsTime(v: Int, d: String) = if (ko) "${formatInt(v.toLong())}회" + (if (d.isNotEmpty()) ", $d" else "") else "${formatInt(v.toLong())} ${if (v == 1) "visit" else "visits"}" + (if (d.isNotEmpty()) ", $d" else "")
        fun times(n: Int) = if (ko) "${formatInt(n.toLong())}회" else "${formatInt(n.toLong())}×"
        sections.add(listOf(if (ko) "자주 방문한 사이트 (방문 수 기준):" else "Most visited sites (by visits):") + stats.topSitesByVisits.mapIndexed { i, s ->
            "${i + 1}. ${s.site} — ${visitsTime(s.visits, if (s.durationMs > 0) formatDuration(s.durationMs, o.locale) else "")}"
        })
        if (stats.topSitesByTime.isNotEmpty()) {
            sections.add(listOf(if (ko) "오래 머문 사이트 (머문 시간 기준):" else "Sites with the most time spent:") + stats.topSitesByTime.mapIndexed { i, s -> "${i + 1}. ${s.site} — ${formatDuration(s.durationMs, o.locale)}" })
        }
        sections.add(listOf(if (ko) "시간대별 방문 수:" else "Visits by hour of day:", stats.hours.mapIndexed { h, n -> (if (ko) "${h}시" else "${h}h") + " $n" }.joinToString(" · ")))
        val week = if (ko) WEEK_KO else WEEK_EN
        sections.add(listOf(if (ko) "요일별 방문 수:" else "Visits by weekday:", stats.weekdays.mapIndexed { d, n -> "${week[d]} $n" }.joinToString(" · ")))
        val s = stats.sessions
        sections.add(listOf(
            if (ko) "브라우징 세션: ${formatInt(s.count.toLong())}회, 평균 ${formatDuration(s.averageMinutes * 60000, "ko")}, 가장 긴 세션 ${formatDuration(s.longestMinutes * 60000, "ko")}"
            else "Browsing sessions: ${formatInt(s.count.toLong())}, average ${formatDuration(s.averageMinutes * 60000, "en")}, longest ${formatDuration(s.longestMinutes * 60000, "en")}",
        ))
        if (o.include.queries && stats.topQueries.isNotEmpty()) {
            sections.add(listOf(if (ko) "자주 검색한 검색어:" else "Most frequent searches:") + stats.topQueries.map { "- ${it.first} (${times(it.second)})" })
            if (stats.topTerms.isNotEmpty()) sections.add(listOf(if (ko) "검색어에 자주 나온 단어:" else "Most frequent search words:", stats.topTerms.joinToString(", ") { "${it.first}(${it.second})" }))
        }
        if (stats.newSites != null && stats.returningSites != null) {
            sections.add(listOf(if (ko) "처음 방문한 사이트 ${formatInt(stats.newSites.toLong())}곳 · 다시 방문한 사이트 ${formatInt(stats.returningSites.toLong())}곳" else "${formatInt(stats.newSites.toLong())} new sites · ${formatInt(stats.returningSites.toLong())} returning sites"))
        }
        val untitled = if (ko) "(제목 없음)" else "(untitled)"
        fun label(title: String, url: String, site: String): String {
            val where = if (o.include.urls == "paths") url else site
            return if (o.include.titles) "${title.ifEmpty { untitled }} — $where" else where
        }
        val showPages = o.include.titles || o.include.urls == "paths"
        val notable = if (showPages) listOf(if (ko) "많이 본 페이지:" else "Most viewed pages:") + stats.notablePages.map { "- ${label(it.title, it.url, it.site)} (${times(it.visits)})" } else null
        val recent = if (showPages) listOf(if (ko) "최근 본 페이지:" else "Recently viewed pages:") + stats.recentPages.map { "- ${LocalTime.date(it.time, tz)} ${LocalTime.clock(it.time, tz)} ${label(it.title, it.url, it.site)}" } else null
        if (o.kind == "find") {
            recent?.let { sections.add(it) }
            notable?.let { sections.add(it) }
        } else notable?.let { sections.add(it) }
        return assemble(sections, o.charLimit, marker)
    }

    private fun assemble(sections: List<List<String>>, limit: Int, marker: String): Result {
        val full = sections.joinToString("\n\n") { it.joinToString("\n") }
        if (full.length <= limit) return Result(full, false)
        val budget = maxOf(0, limit - marker.length - 1)
        val out = StringBuilder()
        loop@ for (sec in sections) {
            for ((j, line) in sec.withIndex()) {
                val sep = if (out.isNotEmpty()) (if (j == 0) "\n\n" else "\n") else ""
                if (out.length + sep.length + line.length > budget) break@loop
                out.append(sep).append(line)
            }
        }
        return Result(if (out.isNotEmpty()) "$out\n$marker" else marker.take(limit), true)
    }
}
