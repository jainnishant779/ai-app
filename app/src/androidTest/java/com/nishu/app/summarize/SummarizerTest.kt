package com.nishu.app.summarize

import androidx.test.platform.app.InstrumentationRegistry
import com.nishu.app.data.db.ConversationEntity
import com.nishu.app.data.db.NishuDatabase
import com.nishu.app.data.db.TranscriptSegmentEntity
import com.nishu.app.llm.ChatMessage
import com.nishu.app.llm.FinishReason
import com.nishu.app.llm.GenerationResult
import com.nishu.app.llm.LLMEngine
import com.nishu.app.llm.PromptTemplate
import com.nishu.app.llm.Qwen3PromptTemplate
import com.nishu.app.llm.SamplerProfile
import com.nishu.app.llm.ToolCallParser
import com.nishu.app.llm.WarmResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Scripted engine: decides each reply from the prompt, so every degradation path is reachable without a model. */
class FakeEngine(private val script: (prompt: String, sampler: SamplerProfile) -> String) : LLMEngine {
    override val template: PromptTemplate = Qwen3PromptTemplate("system")
    override val parser = ToolCallParser()
    override val contextTokens = 1024
    override val systemPrefixTokens = 299
    val prompts = mutableListOf<String>()
    override suspend fun warmPrefix() = WarmResult(true, 0, 299)
    override suspend fun generate(messages: List<ChatMessage>, sampler: SamplerProfile, maxTokens: Int, onPiece: ((String) -> Unit)?): GenerationResult {
        val prompt = messages.last().content
        prompts += prompt
        val text = script(prompt, sampler)
        onPiece?.invoke(text)
        return GenerationResult(text, text.split(' ').size, FinishReason.STOP, 1, 10.0)
    }
    override suspend fun generateWithToolSwitch(messages: List<ChatMessage>, toolGrammar: String, maxTokens: Int, onPiece: ((String) -> Unit)?) =
        generate(messages, SamplerProfile.Greedy, maxTokens, onPiece)
    override fun tokenCount(text: String) = (text.split(Regex("\\s+")).count { it.isNotEmpty() } * 1.5).toInt()
    override fun cancel() {}
    override fun rssBytes() = 0L
    override fun close() {}
}

class SummarizerTest {
    private lateinit var db: NishuDatabase

    @Before fun setUp() {
        db = NishuDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() = db.close()

    private val grammar = "root ::= \"{}\""

    private suspend fun conversation(vararg segments: String): Long {
        val id = db.conversations().insert(ConversationEntity(title = "t", createdAt = 0, status = "TRANSCRIBED"))
        segments.forEachIndexed { i, s ->
            db.transcripts().insert(TranscriptSegmentEntity(conversationId = id, startMs = i * 1000L, endMs = i * 1000L + 900, text = s))
        }
        return id
    }

    private fun longTranscript(n: Int) = Array(n) { i -> "segment$i " + "word ".repeat(20).trim() }

    private suspend fun run(engine: FakeEngine, id: Long, maxChunks: Int = 12) =
        MapReduceSummarizer(db, engine, grammar, "test", maxChunks).run(id)

    private val goodJson = "{\"tasks\":[{\"text\":\"Send the deck\",\"due\":\"Mon\"}],\"decisions\":[\"Use vendor A\"]}"

    private fun happy(extract: String = goodJson) = FakeEngine { p, _ ->
        when {
            p.startsWith("Summarize this conversation") -> "- point one\n- point two"
            p.startsWith("Summarize these notes") -> "- final one\n- final two"
            else -> extract
        }
    }

    @Test fun happyPathStoresSummaryAndModelJsonItems() = runBlocking {
        val id = conversation("we met today", "ok")
        run(happy(), id)
        assertEquals("DONE", db.conversations().get(id)!!.status)
        // One chunk means no reduce step: the map output is the summary.
        assertEquals("- point one\n- point two", db.summaries().observe(id).first()!!.bulletsText)
        val tasks = db.tasks().observe(id).first()
        assertEquals(listOf("MODEL_JSON"), tasks.map { it.extractionConfidence })
        assertEquals("Mon", tasks.single().dueHint)
        assertEquals("Use vendor A", db.decisions().observe(id).first().single().text)
    }

    @Test fun multipleChunksAreReducedIntoOneSummary() = runBlocking {
        val id = conversation(*longTranscript(40))
        val engine = happy()
        run(engine, id)
        assertTrue(engine.prompts.count { it.startsWith("Summarize this conversation") } >= 2)
        assertEquals(1, engine.prompts.count { it.startsWith("Summarize these notes") })
        assertEquals("- final one\n- final two", db.summaries().observe(id).first()!!.bulletsText)
    }

    @Test fun truncatedJsonIsSalvagedAndMarkedSo() = runBlocking {
        val id = conversation("we met today")
        run(happy("{\"tasks\":[{\"text\":\"Send the deck\"},{\"text\":\"Book"), id)
        val tasks = db.tasks().observe(id).first()
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.all { it.extractionConfidence == "MODEL_SALVAGED" })
    }

    @Test fun garbageFallsBackToHeuristicsOnTheTranscript() = runBlocking {
        val id = conversation("client ko Monday tak bhejna hai", "we decided to use vendor A")
        run(happy("totally not json"), id)
        val tasks = db.tasks().observe(id).first()
        assertTrue(tasks.single().text.contains("bhejna hai"))
        assertEquals("HEURISTIC", tasks.single().extractionConfidence)
        assertTrue(db.decisions().observe(id).first().single().extractionConfidence == "HEURISTIC")
        assertEquals("DONE", db.conversations().get(id)!!.status)
    }

    @Test fun nothingFoundIsNotAnError() = runBlocking {
        val id = conversation("ok chalo thik hai", "haan bilkul")
        run(happy("{\"tasks\":[],\"decisions\":[]}"), id)
        assertTrue(db.tasks().observe(id).first().isEmpty())
        assertTrue(db.decisions().observe(id).first().isEmpty())
        assertEquals("DONE", db.conversations().get(id)!!.status)
    }

    @Test fun emptyMapOutputIsRetriedThenSkippedAndCounted() = runBlocking {
        val id = conversation(*longTranscript(40))
        val engine = FakeEngine { p, s ->
            when {
                p.startsWith("Summarize this conversation") && p.contains("segment0 ") -> ""
                p.startsWith("Summarize this conversation") -> "- ok"
                p.startsWith("Summarize these notes") -> "- final"
                else -> "{\"tasks\":[],\"decisions\":[]}"
            }
        }
        run(engine, id)
        val summary = db.summaries().observe(id).first()!!
        assertEquals(1, summary.failedChunks)
        assertTrue(summary.chunkCount >= 2)
        assertEquals("one retry before giving up on a chunk", 2, engine.prompts.count { it.contains("segment0 ") && it.startsWith("Summarize this conversation") })
        assertEquals("DONE", db.conversations().get(id)!!.status)
    }

    @Test fun allMapsFailingMarksFailedButKeepsTheTranscript() = runBlocking {
        val id = conversation(*longTranscript(10))
        run(FakeEngine { _, _ -> "" }, id)
        val c = db.conversations().get(id)!!
        assertEquals("FAILED", c.status)
        assertEquals("Summary unavailable", c.statusDetail)
        assertEquals(10, db.transcripts().get(id).size)
    }

    @Test fun repetitionLoopsAreTruncated() = runBlocking {
        val id = conversation("we met today")
        val loop = "The plan covers the whole budget today. " + "We should really finish the budget soon. ".repeat(5)
        val engine = FakeEngine { p, _ ->
            when {
                p.startsWith("Summarize this conversation") -> loop
                p.startsWith("Summarize these notes") -> "- final"
                else -> goodJson
            }
        }
        run(engine, id)
        val saved = db.summaries().observe(id).first()!!.bulletsText
        assertTrue("looped text must be cut before it is saved", saved.length < loop.length)
        assertTrue(saved.contains("plan covers"))
    }

    @Test fun noSpeechFinishesWithoutCallingTheModel() = runBlocking {
        val id = conversation()
        val engine = happy()
        run(engine, id)
        assertTrue(engine.prompts.isEmpty())
        val c = db.conversations().get(id)!!
        assertEquals("DONE", c.status)
        assertEquals("No speech was detected", c.statusDetail)
    }

    @Test fun veryLongRecordingsAreCappedAndSaySo() = runBlocking {
        val id = conversation(*longTranscript(200))
        run(happy(), id, maxChunks = 3)
        assertEquals(3, db.summaries().observe(id).first()!!.chunkCount)
        assertTrue(db.conversations().get(id)!!.statusDetail!!.startsWith("Summary covers"))
    }

    @Test fun everyMapPromptFitsTheContext() = runBlocking {
        val id = conversation(*longTranscript(60))
        val engine = happy()
        run(engine, id)
        engine.prompts.filter { it.startsWith("Summarize this conversation") }.forEach {
            val tokens = engine.tokenCount(it)
            assertTrue("map prompt of $tokens tokens leaves no room to generate", tokens + 299 + 160 <= 1024)
        }
    }
}
