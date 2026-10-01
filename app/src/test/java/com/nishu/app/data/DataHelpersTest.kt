package com.nishu.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DataHelpersTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun ftsMatchPrefixesWordsAndStripsSyntax() {
        assertEquals("project* plan*", ftsMatch("  project   plan "))
        assertEquals("abc* OR*", ftsMatch("\"abc\" OR*-"))
        assertEquals("", ftsMatch("\"*-"))
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertEquals("%50\\%\\_x%", likePattern("50%_x"))
    }

    @Test
    fun timestampLabels() {
        val now = ms(2026, 10, 1, 18, 0)
        assertEquals("Just now", TimeLabels.timestamp(now - 10_000, now, utc))
        assertEquals("Today, 5:30 PM", TimeLabels.timestamp(ms(2026, 10, 1, 17, 30), now, utc))
        assertEquals("Yesterday", TimeLabels.timestamp(ms(2026, 9, 30, 23, 0), now, utc))
        assertEquals("3 days ago", TimeLabels.timestamp(ms(2026, 9, 28, 9, 0), now, utc))
        assertEquals("Sep 20", TimeLabels.timestamp(ms(2026, 9, 20, 9, 0), now, utc))
    }

    @Test
    fun durationLabels() {
        assertEquals("45 sec", TimeLabels.duration(45_000))
        assertEquals("12 min", TimeLabels.duration(12 * 60_000L))
        assertEquals("1 h 5 min", TimeLabels.duration(65 * 60_000L))
    }
}
