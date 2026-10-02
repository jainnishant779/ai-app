package com.nishu.app.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTurnsTest {
    private fun t(s: Long, e: Long, spk: Int) = SpeakerTurn(s, e, spk)

    @Test
    fun speakersAreNumberedByFirstAppearance() {
        val r = SpeakerTurns.renumber(listOf(t(5_000, 8_000, 7), t(0, 4_000, 3), t(9_000, 12_000, 7)))
        assertEquals(listOf(0, 1, 1), r.map { it.speaker })
        assertEquals(listOf(0L, 5_000L, 9_000L), r.map { it.startMs })
    }

    @Test
    fun noTurnsMeansNoSpeaker() {
        val p = SpeakerTurns.split(0, 5_000, emptyList())
        assertEquals(listOf(SpeakerPiece(0, 5_000, null)), p)
    }

    @Test
    fun oneSpeakerStaysOnePiece() {
        val p = SpeakerTurns.split(1_000, 6_000, listOf(t(0, 9_000, 0)))
        assertEquals(listOf(SpeakerPiece(1_000, 6_000, 0)), p)
    }

    @Test
    fun aSpeakerChangeSplitsTheSegmentAndCoversEverySample() {
        val p = SpeakerTurns.split(0, 10_000, listOf(t(0, 4_000, 0), t(4_500, 10_000, 1)))
        assertEquals(2, p.size)
        assertEquals(0, p[0].speaker); assertEquals(1, p[1].speaker)
        assertEquals(0L, p.first().startMs); assertEquals(10_000L, p.last().endMs)
        assertTrue("no gap between pieces", p[0].endMs == p[1].startMs)
    }

    @Test
    fun aTinyInterjectionIsAbsorbedNotLabelled() {
        // 0.4 s of speaker 1 inside speaker 0's long turn is too short to read; it must not flicker the label.
        val p = SpeakerTurns.split(0, 10_000, listOf(t(0, 4_800, 0), t(4_800, 5_200, 1), t(5_200, 10_000, 0)))
        assertEquals(listOf(SpeakerPiece(0, 10_000, 0)), p)
    }

    @Test
    fun overlappingTurnsDoNotDoubleCount() {
        val p = SpeakerTurns.split(0, 8_000, listOf(t(0, 5_000, 0), t(4_000, 8_000, 1)))
        assertEquals(2, p.size)
        assertEquals(p[0].endMs, p[1].startMs)
        assertEquals(8_000L, p.last().endMs)
    }

    @Test
    fun turnsOutsideTheSegmentAreIgnored() {
        val p = SpeakerTurns.split(20_000, 25_000, listOf(t(0, 10_000, 0)))
        assertNull(p.single().speaker)
    }

    @Test
    fun longSpeechIsChunkedWithoutLosingOrDuplicatingAnySample() {
        val max = 28 * 16_000
        for (total in listOf(1, max, max + 1, 2 * max, 3 * max + 7, 60 * 16_000)) {
            val parts = SpeechChunks.split(total, max)
            assertTrue("each part within the limit for $total", parts.all { it.last - it.first + 1 <= max })
            assertEquals("covers every sample exactly once for $total", total, parts.sumOf { it.last - it.first + 1 })
            assertEquals(0, parts.first().first)
            assertTrue(parts.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
        }
        assertTrue(SpeechChunks.split(0, max).isEmpty())
    }

    @Test
    fun labelsAreHumanNumbered() {
        assertEquals("S1", SpeakerTurns.label(0)); assertEquals("S3", SpeakerTurns.label(2)); assertNull(SpeakerTurns.label(null))
    }
}

class SpeechBatcherTest {
    private fun floats(ms: Int) = FloatArray(ms * 16)

    @Test
    fun neighbouringSegmentsOfOneSpeakerShareACall() {
        val b = SpeechBatcher(maxSamples = 25 * 16_000)
        assertNull(b.add(0, 2_000, 0, floats(2_000)))
        assertNull(b.add(3_000, 5_000, 0, floats(2_000)))
        val batch = b.flush()!!
        assertEquals(0L, batch.startMs)
        assertEquals(5_000L, batch.endMs)
        assertEquals(floats(4_200).size, batch.samples.size) // both segments plus a 200 ms silence
    }

    @Test
    fun aSpeakerChangeStartsANewCall() {
        val b = SpeechBatcher(maxSamples = 25 * 16_000)
        b.add(0, 2_000, 0, floats(2_000))
        val closed = b.add(2_100, 4_000, 1, floats(1_900))!!
        assertEquals(0, closed.speaker)
        assertEquals(1, b.flush()!!.speaker)
    }

    @Test
    fun aLongPauseStartsANewCall() {
        val b = SpeechBatcher(maxSamples = 25 * 16_000, maxGapMs = 2_000)
        b.add(0, 2_000, null, floats(2_000))
        assertEquals(2_000L, b.add(10_000, 12_000, null, floats(2_000))!!.endMs)
    }

    @Test
    fun aCallNeverExceedsTheLimit() {
        val b = SpeechBatcher(maxSamples = 10 * 16_000)
        b.add(0, 6_000, null, floats(6_000))
        val closed = b.add(6_100, 12_100, null, floats(6_000))!!
        assertEquals(6_000L, closed.endMs)
        assertTrue(b.flush()!!.samples.size <= 10 * 16_000)
    }

    @Test
    fun emptyBatcherFlushesNothing() {
        assertNull(SpeechBatcher(16_000).flush())
    }
}
