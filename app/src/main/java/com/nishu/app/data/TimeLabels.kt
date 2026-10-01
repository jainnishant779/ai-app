package com.nishu.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object TimeLabels {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val monthDay = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun timestamp(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone))
        return when {
            days <= 0 && nowMs - epochMs < 60_000 -> "Just now"
            days <= 0 -> "Today, ${clock.format(t)}"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> monthDay.format(t)
        }
    }

    /** Coarser label for memory cards: "Today", "2 days ago". */
    fun relativeDay(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone))
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> monthDay.format(t)
        }
    }

    fun duration(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "$sec sec"
            sec < 3600 -> "${(sec + 30) / 60} min"
            else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        }
    }

    fun defaultTitle(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Recording, " + DateTimeFormatter.ofPattern("MMM d h:mm a", Locale.US).format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
