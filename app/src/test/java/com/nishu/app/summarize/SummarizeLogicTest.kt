package com.nishu.app.summarize

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizeLogicTest {
    /** Roman-script Hindi tokenizes worse than English; model that as 1.5 tokens per word. */
    private val tokens = { s: String -> (s.split(Regex("\\s+")).count { it.isNotEmpty() } * 1.5).toInt() }

    private fun transcript(minutes: Int): List<String> =
        List(minutes * 12) { i -> "segment $i " + "word ".repeat(18).trim() } // ~12 segments/minute

    @Test
    fun everyChunkFitsAndConsecutiveChunksOverlap() {
        val segs = transcript(15)
        val hardCap = 480
        val chunks = Chunker(tokens).chunk(segs, hardCap, 420)
        assertTrue(chunks.size > 5)
        chunks.forEach { assertTrue("chunk of ${tokens(it)} tokens exceeds $hardCap", tokens(it) <= hardCap) }
        // Overlap: the last segment of a chunk reappears at the start of the next.
        chunks.zipWithNext().forEach { (a, b) ->
            val lastSeg = Regex("segment \\d+").findAll(a).last().value
            assertTrue("no overlap between chunks", b.contains(lastSeg))
        }
        // Every segment is covered.
        val joined = chunks.joinToString(" ")
        segs.forEach { assertTrue(joined.contains(Regex("segment ${Regex("\\d+").find(it)!!.value} ").pattern.removeSuffix(" ")) ) }
    }

    @Test
    fun oversizedSingleSegmentIsSplit() {
        val huge = "alpha ".repeat(2000).trim()
        val chunks = Chunker(tokens).chunk(listOf(huge), 480, 420)
        assertTrue(chunks.size > 5)
        chunks.forEach { assertTrue(tokens(it) <= 480) }
    }

    @Test
    fun emptyInputGivesNoChunks() {
        assertTrue(Chunker(tokens).chunk(emptyList(), 480, 420).isEmpty())
        assertTrue(Chunker(tokens).chunk(listOf("   "), 480, 420).isEmpty())
    }

    @Test
    fun repetitionGuardCatchesSentenceLoops() {
        val loop = "The meeting is about the budget plan. " + "We should really finish the budget today. ".repeat(4)
        assertTrue(RepetitionGuard.isLooping(loop))
        val cleaned = RepetitionGuard.clean(loop)
        assertTrue(cleaned.length < loop.length)
        assertTrue(cleaned.contains("meeting"))
        assertFalse(RepetitionGuard.isLooping("First point here. Second point there. Third thing entirely different."))
    }

    @Test
    fun repetitionGuardCatchesNgramLoops() {
        val text = "ok so " + "send the deck now ".repeat(6)
        assertTrue(RepetitionGuard.isLooping(text))
    }

    @Test
    fun salvageRepairsTruncatedJson() {
        val cases = listOf(
            "{\"tasks\":[{\"text\":\"Send deck\",\"due\":\"Mon\"},{\"text\":\"Book",
            "{\"tasks\":[{\"text\":\"Send deck\"},{\"te",
            "{\"tasks\":[{\"text\":\"a\"}],\"decisions\":[\"go\",",
            "{\"tasks\":[],\"decisions\":[\"x\"",
            "{\"tasks\":[{\"text\":\"a\",\"owner\":",
        )
        for (c in cases) {
            val fixed = JsonSalvage.repair(c)
            assertNotNull("could not repair: $c", fixed)
            JSONObject(fixed!!) // must parse
        }
        assertNull(JsonSalvage.repair("no json here"))
        assertEquals("{\"a\":1}", JsonSalvage.repair("noise {\"a\":1} trailing"))
    }

    @Test
    fun heuristicsFindTasksAndDecisionsInHinglishAndEnglish() {
        val e = HeuristicExtractor.extract(
            listOf(
                "toh kal wale project ke liye presentation bana leni hai, aur client ko Monday tak bhejna hai.",
                "We decided to go with the cheaper vendor. I need to send the invoice tomorrow.",
                "ok chalo thik hai",
            ),
        )
        assertTrue(e.tasks.any { it.text.contains("bhejna hai") && it.dueHint == "Monday" })
        assertTrue(e.tasks.any { it.text.contains("send the invoice") && it.dueHint == "Tomorrow" })
        assertTrue(e.decisions.any { it.text.contains("cheaper vendor") })
        assertTrue(HeuristicExtractor.extract(listOf("ok chalo thik hai")).isEmpty)
    }
}
