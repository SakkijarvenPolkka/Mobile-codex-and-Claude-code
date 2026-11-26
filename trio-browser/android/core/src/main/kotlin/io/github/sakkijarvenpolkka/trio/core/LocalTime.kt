package io.github.sakkijarvenpolkka.trio.core

import java.time.Instant
import java.time.ZoneId

/** Local-time helpers keyed by an IANA zone name (desktop: src/core/time.js). */
object LocalTime {
    data class Parts(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val weekday: Int)

    fun parts(time: Long, zone: String): Parts {
        val z = Instant.ofEpochMilli(time).atZone(ZoneId.of(zone))
        return Parts(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.dayOfWeek.value - 1)
    }

    fun startOfLocalDay(time: Long, zone: String): Long {
        val zid = ZoneId.of(zone)
        return Instant.ofEpochMilli(time).atZone(zid).toLocalDate().atStartOfDay(zid).toInstant().toEpochMilli()
    }

    fun date(time: Long, zone: String): String {
        val p = parts(time, zone)
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", p.year, p.month, p.day)
    }

    fun clock(time: Long, zone: String): String {
        val p = parts(time, zone)
        return String.format(java.util.Locale.ROOT, "%02d:%02d", p.hour, p.minute)
    }
}
